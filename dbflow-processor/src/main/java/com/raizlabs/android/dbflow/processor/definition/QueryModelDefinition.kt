package com.raizlabs.android.dbflow.processor.definition

import com.grosner.kpoet.`return`
import com.grosner.kpoet.final
import com.grosner.kpoet.modifiers
import com.grosner.kpoet.public
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.raizlabs.android.dbflow.annotation.Column
import com.raizlabs.android.dbflow.annotation.ColumnMap
import com.raizlabs.android.dbflow.annotation.QueryModel
import com.raizlabs.android.dbflow.processor.ClassNames
import com.raizlabs.android.dbflow.processor.ColumnValidator
import com.raizlabs.android.dbflow.processor.KSP_SENTINEL_ELEMENT
import com.raizlabs.android.dbflow.processor.ProcessorManager
import com.raizlabs.android.dbflow.processor.definition.column.ColumnDefinition
import com.raizlabs.android.dbflow.processor.definition.column.ReferenceColumnDefinition
import com.raizlabs.android.dbflow.processor.utils.ElementUtility
import com.raizlabs.android.dbflow.processor.utils.`override fun`
import com.raizlabs.android.dbflow.processor.utils.annotation
import com.raizlabs.android.dbflow.processor.utils.findKspAnnotation
import com.raizlabs.android.dbflow.processor.utils.getBooleanArgument
import com.raizlabs.android.dbflow.processor.utils.getKsTypeArgument
import com.raizlabs.android.dbflow.processor.utils.implementsClass
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetClassName
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetTypeName
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import java.util.*
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypeException

/**
 * Description:
 */
class QueryModelDefinition(typeElement: Element, processorManager: ProcessorManager)
    : BaseTableDefinition(typeElement, processorManager) {

    var allFields: Boolean = false

    var implementsLoadFromCursorListener = false

    internal var methods: Array<MethodDefinition>

    internal var kspMode = false
    internal var ksClassDeclaration: KSClassDeclaration? = null

    private var preparedKspWrite = false

    init {

        typeElement.annotation<QueryModel>()?.let { queryModel ->
            try {
                queryModel.database
            } catch (mte: MirroredTypeException) {
                databaseTypeName = TypeName.get(mte.typeMirror)
            }
        }

        elementClassName?.let { elementClassName -> databaseTypeName?.let { processorManager.addModelToDatabase(elementClassName, it) } }

        if (element is TypeElement) {
            implementsLoadFromCursorListener =
                (element as TypeElement).implementsClass(manager.processingEnvironment, ClassNames.LOAD_FROM_CURSOR_LISTENER)
        }


        methods = arrayOf<MethodDefinition>(LoadFromCursorMethod(this))

    }

    fun kspInit(ksClass: KSClassDeclaration) {
        kspMode = true
        ksClassDeclaration = ksClass
        originatingFile = ksClass.containingFile

        elementName = ksClass.simpleName.asString()
        elementClassName = ksClass.toJavaPoetClassName()
        elementTypeName = elementClassName
        packageName = ksClass.packageName.asString()

        val annot = ksClass.findKspAnnotation<QueryModel>() ?: return

        val dbKsType = annot.getKsTypeArgument("database")
        databaseTypeName = dbKsType?.toJavaPoetTypeName()

        allFields = annot.getBooleanArgument("allFields") ?: false

        elementClassName?.let { className ->
            databaseTypeName?.let { dbType -> manager.addModelToDatabase(className, dbType) }
        }

        val superTypes = ksClass.getAllSuperTypes().map { it.declaration.qualifiedName?.asString() }
        implementsLoadFromCursorListener = ClassNames.LOAD_FROM_CURSOR_LISTENER.toString() in superTypes
    }

    override fun prepareForWrite() {
        if (kspMode && preparedKspWrite) return

        classElementLookUpMap.clear()
        columnDefinitions.clear()
        packagePrivateList.clear()

        if (kspMode) {
            prepareForWriteKsp()
            preparedKspWrite = true
            return
        }

        val queryModel = typeElement.annotation<QueryModel>()
        if (queryModel != null) {
            allFields = queryModel.allFields
        } else {
            allFields = true
        }

        databaseDefinition = manager.getDatabaseHolderDefinition(databaseTypeName)?.databaseDefinition
        setOutputClassName("${databaseDefinition?.classSeparator}QueryTable")

        typeElement?.let { createColumnDefinitions(it) }
    }

    private fun prepareForWriteKsp() {
        val ksClass = ksClassDeclaration ?: return
        databaseDefinition = manager.getDatabaseHolderDefinition(databaseTypeName)?.databaseDefinition
        if (databaseDefinition == null) {
            manager.logError("DatabaseDefinition was null for KSP QueryModel: $elementName")
            return
        }
        setOutputClassName("${databaseDefinition?.classSeparator}QueryTable")
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

        for (property in ksClass.getAllProperties()) {
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
                    manager.logError("QueryModel $elementName cannot have primary keys")
                }
            }
        }
    }

    override val extendsClass: TypeName?
        get() = ParameterizedTypeName.get(ClassNames.QUERY_MODEL_ADAPTER, elementClassName)

    override fun onWriteDefinition(typeBuilder: TypeSpec.Builder) {
        typeBuilder.apply {
            elementClassName?.let { className -> columnDefinitions.forEach { it.addPropertyDefinition(this, className) } }

            writeGetModelClass(typeBuilder, elementClassName)

            writeConstructor(this)

            `override fun`(elementClassName!!, "newInstance") {
                modifiers(public, final)
                `return`("new \$T()", elementClassName)
            }
        }

        methods.mapNotNull { it.methodSpec }
            .forEach { typeBuilder.addMethod(it) }
    }

    override fun createColumnDefinitions(typeElement: TypeElement) {
        val variableElements = ElementUtility.getAllElements(typeElement, manager)

        for (element in variableElements) {
            classElementLookUpMap.put(element.simpleName.toString(), element)
        }

        val columnValidator = ColumnValidator()
        for (variableElement in variableElements) {

            // no private static or final fields
            val isAllFields = ElementUtility.isValidAllFields(allFields, variableElement)
            // package private, will generate helper
            val isPackagePrivate = ElementUtility.isPackagePrivate(variableElement)
            val isPackagePrivateNotInSamePackage = isPackagePrivate && !ElementUtility.isInSamePackage(manager, variableElement, this.element)
            val isColumnMap = variableElement.annotation<ColumnMap>() != null

            if (variableElement.annotation<Column>() != null || isAllFields || isColumnMap) {

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
                    manager.logError("QueryModel $elementName cannot have primary keys")
                }
            }
        }
    }

    override // Shouldn't include any
    val primaryColumnDefinitions: List<ColumnDefinition>
        get() = ArrayList()

}
