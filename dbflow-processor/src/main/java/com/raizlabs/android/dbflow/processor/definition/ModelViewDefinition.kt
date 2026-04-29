package com.raizlabs.android.dbflow.processor.definition

import com.grosner.kpoet.S
import com.grosner.kpoet.`=`
import com.grosner.kpoet.`public static final field`
import com.grosner.kpoet.`return`
import com.grosner.kpoet.final
import com.grosner.kpoet.modifiers
import com.grosner.kpoet.public
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.raizlabs.android.dbflow.annotation.Column
import com.raizlabs.android.dbflow.annotation.ColumnMap
import com.raizlabs.android.dbflow.annotation.ModelView
import com.raizlabs.android.dbflow.annotation.ModelViewQuery
import com.raizlabs.android.dbflow.processor.ClassNames
import com.raizlabs.android.dbflow.processor.ColumnValidator
import com.raizlabs.android.dbflow.processor.KSP_SENTINEL_ELEMENT
import com.raizlabs.android.dbflow.processor.ProcessorManager
import com.raizlabs.android.dbflow.processor.definition.column.ColumnDefinition
import com.raizlabs.android.dbflow.processor.definition.column.ReferenceColumnDefinition
import com.raizlabs.android.dbflow.processor.utils.ElementUtility
import com.raizlabs.android.dbflow.processor.utils.`override fun`
import com.raizlabs.android.dbflow.processor.utils.annotation
import com.raizlabs.android.dbflow.processor.utils.ensureVisibleStatic
import com.raizlabs.android.dbflow.processor.utils.findKspAnnotation
import com.raizlabs.android.dbflow.processor.utils.getBooleanArgument
import com.raizlabs.android.dbflow.processor.utils.getKsTypeArgument
import com.raizlabs.android.dbflow.processor.utils.getStringArgument
import com.raizlabs.android.dbflow.processor.utils.implementsClass
import com.raizlabs.android.dbflow.processor.utils.isNullOrEmpty
import com.raizlabs.android.dbflow.processor.utils.simpleString
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetClassName
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetTypeName
import com.raizlabs.android.dbflow.processor.utils.toTypeErasedElement
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypeException

/**
 * Description: Used in writing ModelViewAdapters
 */
class ModelViewDefinition(manager: ProcessorManager, element: Element) : BaseTableDefinition(element, manager) {

    internal var implementsLoadFromCursorListener: Boolean = false

    private var queryFieldName: String? = null

    private var name: String? = null

    private val methods: Array<MethodDefinition> =
        arrayOf(LoadFromCursorMethod(this), ExistenceMethod(this), PrimaryConditionMethod(this))

    var allFields: Boolean = false

    var priority: Int = 0

    internal var kspMode = false
    internal var ksClassDeclaration: KSClassDeclaration? = null

    private var preparedKspWrite = false

    init {

        element.annotation<ModelView>()?.let { modelView ->
            try {
                modelView.database
            } catch (mte: MirroredTypeException) {
                this.databaseTypeName = TypeName.get(mte.typeMirror)
            }

            allFields = modelView.allFields

            this.name = modelView.name
            if (name == null || name!!.isEmpty()) {
                name = modelClassName
            }
            this.priority = modelView.priority
        }

        implementsLoadFromCursorListener = if (element is TypeElement) {
            element.implementsClass(manager.processingEnvironment, ClassNames.LOAD_FROM_CURSOR_LISTENER)
        } else {
            false
        }

    }

    fun kspInit(ksClass: KSClassDeclaration) {
        kspMode = true
        ksClassDeclaration = ksClass
        originatingFile = ksClass.containingFile

        elementName = ksClass.simpleName.asString()
        elementClassName = ksClass.toJavaPoetClassName()
        elementTypeName = elementClassName
        packageName = ksClass.packageName.asString()

        val annot = ksClass.findKspAnnotation<ModelView>() ?: return

        val dbKsType = annot.getKsTypeArgument("database")
        databaseTypeName = dbKsType?.toJavaPoetTypeName()

        allFields = annot.getBooleanArgument("allFields") ?: false
        name = annot.getStringArgument("name").takeIf { !it.isNullOrEmpty() } ?: elementName
        priority = annot.arguments.find { it.name?.asString() == "priority" }?.value as? Int ?: 0

        val superTypes = ksClass.getAllSuperTypes().map { it.declaration.qualifiedName?.asString() }
        implementsLoadFromCursorListener = ClassNames.LOAD_FROM_CURSOR_LISTENER.toString() in superTypes
    }

    override fun prepareForWrite() {
        if (kspMode && preparedKspWrite) return

        classElementLookUpMap.clear()
        columnDefinitions.clear()
        queryFieldName = null

        if (kspMode) {
            prepareForWriteKsp()
            preparedKspWrite = true
            return
        }

        val modelView = element.getAnnotation(ModelView::class.java)
        if (modelView != null) {
            databaseDefinition = manager.getDatabaseHolderDefinition(databaseTypeName)?.databaseDefinition
            setOutputClassName("${databaseDefinition?.classSeparator}ViewTable")

            typeElement?.let { createColumnDefinitions(it) }
        } else {
            setOutputClassName("ViewTable")
        }
    }

    private fun prepareForWriteKsp() {
        val ksClass = ksClassDeclaration ?: return
        databaseDefinition = manager.getDatabaseHolderDefinition(databaseTypeName)?.databaseDefinition
        if (databaseDefinition == null) {
            manager.logError("DatabaseDefinition was null for KSP ModelView: $elementName")
            return
        }
        setOutputClassName("${databaseDefinition?.classSeparator}ViewTable")
        createColumnDefinitionsFromKsp(ksClass)
    }

    private fun createColumnDefinitionsFromKsp(ksClass: KSClassDeclaration) {
        for (property in ksClass.getAllProperties()) {
            val name = property.simpleName.asString()
            classElementLookUpMap[name] = KSP_SENTINEL_ELEMENT
            val cap = name.replaceFirstChar { it.uppercase() }
            classElementLookUpMap["get$cap"] = KSP_SENTINEL_ELEMENT
            classElementLookUpMap["set$cap"] = KSP_SENTINEL_ELEMENT
            if (name.startsWith("is", ignoreCase = true)) {
                val withoutIs = name.removePrefix("is").removePrefix("Is")
                    .replaceFirstChar { it.uppercase() }
                classElementLookUpMap["set$withoutIs"] = KSP_SENTINEL_ELEMENT
            }
        }
        for (function in ksClass.declarations.filterIsInstance<com.google.devtools.ksp.symbol.KSFunctionDeclaration>()) {
            classElementLookUpMap[function.simpleName.asString()] = KSP_SENTINEL_ELEMENT
        }

        val columnValidator = ColumnValidator()

        // Check companion object for @ModelViewQuery (Kotlin companion object pattern)
        val companion = ksClass.declarations
            .filterIsInstance<KSClassDeclaration>()
            .find { it.isCompanionObject }
        companion?.declarations?.filterIsInstance<com.google.devtools.ksp.symbol.KSPropertyDeclaration>()
            ?.find { it.findKspAnnotation<ModelViewQuery>() != null }
            ?.let { queryProp ->
                if (!queryFieldName.isNullOrEmpty()) {
                    manager.logError("Found duplicate queryField name: $queryFieldName for $elementClassName")
                }
                queryFieldName = queryProp.simpleName.asString()
            }

        // Check direct declarations for Java static fields with @ModelViewQuery
        if (queryFieldName.isNullOrEmpty()) {
            ksClass.declarations.filterIsInstance<com.google.devtools.ksp.symbol.KSPropertyDeclaration>()
                .find { it.findKspAnnotation<ModelViewQuery>() != null }
                ?.let { queryProp ->
                    queryFieldName = queryProp.simpleName.asString()
                }
        }

        for (property in ksClass.getAllProperties()) {
            val propName = property.simpleName.asString()

            if (property.findKspAnnotation<ModelViewQuery>() != null) {
                if (!queryFieldName.isNullOrEmpty()) {
                    manager.logError("Found duplicate queryField name: $queryFieldName for $elementClassName")
                }
                queryFieldName = propName
                continue
            }

            val hasColumn = property.findKspAnnotation<Column>() != null
            val isAllFieldsCandidate = allFields &&
                    com.google.devtools.ksp.symbol.Modifier.PRIVATE !in property.modifiers &&
                    com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in property.modifiers

            if (!hasColumn && !isAllFieldsCandidate) continue

            val colDef = ColumnDefinition(manager, KSP_SENTINEL_ELEMENT, this, false)
            colDef.kspInit(property)

            if (columnValidator.validate(manager, colDef)) {
                columnDefinitions.add(colDef)
                if (colDef.isPrimaryKey || colDef.isPrimaryKeyAutoIncrement || colDef.isRowId) {
                    manager.logError("ModelView $elementName cannot have primary keys")
                }
            }
        }

        if (queryFieldName.isNullOrEmpty()) {
            manager.logError("$elementClassName is missing the @ModelViewQuery field.")
        }
    }

    override fun createColumnDefinitions(typeElement: TypeElement) {
        val variableElements = ElementUtility.getAllElements(typeElement, manager)

        for (element in variableElements) {
            classElementLookUpMap.put(element.simpleName.toString(), element)
        }

        val columnValidator = ColumnValidator()
        for (variableElement in variableElements) {

            val isValidAllFields = ElementUtility.isValidAllFields(allFields, variableElement)
            val isColumnMap = variableElement.annotation<ColumnMap>() != null

            if (variableElement.annotation<Column>() != null || isValidAllFields
                || isColumnMap) {

                // package private, will generate helper
                val isPackagePrivate = ElementUtility.isPackagePrivate(variableElement)
                val isPackagePrivateNotInSamePackage = isPackagePrivate && !ElementUtility.isInSamePackage(manager, variableElement, this.element)

                if (checkInheritancePackagePrivate(isPackagePrivateNotInSamePackage, variableElement)) return

                val columnDefinition = if (isColumnMap) {
                    ReferenceColumnDefinition(manager, this, variableElement, isPackagePrivateNotInSamePackage)
                } else {
                    ColumnDefinition(manager, variableElement, this, isPackagePrivateNotInSamePackage)
                }
                if (columnValidator.validate(manager, columnDefinition)) {
                    columnDefinitions.add(columnDefinition)
                    if (isPackagePrivate) {
                        packagePrivateList.add(columnDefinition)
                    }
                }

                if (columnDefinition.isPrimaryKey || columnDefinition.isPrimaryKeyAutoIncrement || columnDefinition.isRowId) {
                    manager.logError("ModelView $elementName cannot have primary keys")
                }
            } else if (variableElement.annotation<ModelViewQuery>() != null) {
                if (!queryFieldName.isNullOrEmpty()) {
                    manager.logError("Found duplicate queryField name: $queryFieldName for $elementClassName")
                }
                ensureVisibleStatic(variableElement, typeElement, "ModelViewQuery")

                val element = variableElement.toTypeErasedElement()
                if (!element.implementsClass(manager.processingEnvironment, ClassNames.QUERY)) {
                    manager.logError("The field ${variableElement.simpleName} must implement ${ClassNames.QUERY}")
                }

                queryFieldName = variableElement.simpleString
            }
        }

        if (queryFieldName.isNullOrEmpty()) {
            manager.logError("$elementClassName is missing the @ModelViewQuery field.")
        }
    }

    override val primaryColumnDefinitions: List<ColumnDefinition>
        get() = columnDefinitions

    override val extendsClass: TypeName?
        get() = ParameterizedTypeName.get(ClassNames.MODEL_VIEW_ADAPTER, elementClassName)

    override fun onWriteDefinition(typeBuilder: TypeSpec.Builder) {
        typeBuilder.apply {
            `public static final field`(String::class, "VIEW_NAME") { `=`(name.S) }

            elementClassName?.let { elementClassName ->
                columnDefinitions.forEach { it.addPropertyDefinition(typeBuilder, elementClassName) }
            }

            writeConstructor(this)

            writeGetModelClass(typeBuilder, elementClassName)

            `override fun`(String::class, "getCreationQuery") {
                modifiers(public, final)
                `return`("\$T.\$L.getQuery()", elementClassName, queryFieldName)
            }
            `override fun`(String::class, "getViewName") {
                modifiers(public, final)
                `return`(name.S)
            }
            `override fun`(elementClassName!!, "newInstance") {
                modifiers(public, final)
                `return`("new \$T()", elementClassName)
            }

        }

        methods.mapNotNull { it.methodSpec }
            .forEach { typeBuilder.addMethod(it) }
    }

}