package com.raizlabs.android.dbflow.processor.definition

import com.grosner.kpoet.L
import com.grosner.kpoet.S
import com.grosner.kpoet.`=`
import com.grosner.kpoet.`public static final field`
import com.grosner.kpoet.`return`
import com.grosner.kpoet.`throw new`
import com.grosner.kpoet.code
import com.grosner.kpoet.default
import com.grosner.kpoet.final
import com.grosner.kpoet.modifiers
import com.grosner.kpoet.param
import com.grosner.kpoet.protected
import com.grosner.kpoet.public
import com.grosner.kpoet.statement
import com.grosner.kpoet.switch
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.raizlabs.android.dbflow.annotation.Column
import com.raizlabs.android.dbflow.annotation.ColumnMap
import com.raizlabs.android.dbflow.annotation.ConflictAction
import com.raizlabs.android.dbflow.annotation.ForeignKey
import com.raizlabs.android.dbflow.annotation.InheritedColumn
import com.raizlabs.android.dbflow.annotation.InheritedPrimaryKey
import com.raizlabs.android.dbflow.annotation.ModelCacheField
import com.raizlabs.android.dbflow.annotation.MultiCacheField
import com.raizlabs.android.dbflow.annotation.OneToMany
import com.raizlabs.android.dbflow.annotation.PrimaryKey
import com.raizlabs.android.dbflow.annotation.Table
import com.raizlabs.android.dbflow.processor.ClassNames
import com.raizlabs.android.dbflow.processor.ColumnValidator
import com.raizlabs.android.dbflow.processor.KSP_SENTINEL_ELEMENT
import com.raizlabs.android.dbflow.processor.OneToManyValidator
import com.raizlabs.android.dbflow.processor.ProcessorManager
import com.raizlabs.android.dbflow.processor.definition.BindToStatementMethod.Mode.*
import com.raizlabs.android.dbflow.processor.definition.column.ColumnDefinition
import com.raizlabs.android.dbflow.processor.definition.column.DefinitionUtils
import com.raizlabs.android.dbflow.processor.definition.column.ReferenceColumnDefinition
import com.raizlabs.android.dbflow.processor.utils.ElementUtility
import com.raizlabs.android.dbflow.processor.utils.ModelUtils
import com.raizlabs.android.dbflow.processor.utils.ModelUtils.wrapper
import com.raizlabs.android.dbflow.processor.utils.`override fun`
import com.raizlabs.android.dbflow.processor.utils.annotation
import com.raizlabs.android.dbflow.processor.utils.ensureVisibleStatic
import com.raizlabs.android.dbflow.processor.utils.findKspAnnotation
import com.raizlabs.android.dbflow.processor.utils.getBooleanArgument
import com.raizlabs.android.dbflow.processor.utils.getEnumArgument
import com.raizlabs.android.dbflow.processor.utils.getKsTypeArgument
import com.raizlabs.android.dbflow.processor.utils.getStringArgument
import com.raizlabs.android.dbflow.processor.utils.implementsClass
import com.raizlabs.android.dbflow.processor.utils.isNullOrEmpty
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetClassName
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetTypeName
import com.raizlabs.android.dbflow.sql.QueryBuilder
import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.NameAllocator
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.WildcardTypeName
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypeException

/**
 * Description: Used in writing ModelAdapters
 */
class TableDefinition(manager: ProcessorManager, element: javax.lang.model.element.Element) : BaseTableDefinition(element, manager) {

    var tableName: String? = null

    var insertConflictActionName: String = ""

    var updateConflictActionName: String = ""

    var primaryKeyConflictActionName: String = ""

    val _primaryColumnDefinitions = mutableListOf<ColumnDefinition>()
    val foreignKeyDefinitions = mutableListOf<ReferenceColumnDefinition>()
    val uniqueGroupsDefinitions = mutableListOf<UniqueGroupsDefinition>()
    val indexGroupsDefinitions = mutableListOf<IndexGroupsDefinition>()

    var implementsContentValuesListener = false

    var implementsSqlStatementListener = false

    var implementsLoadFromCursorListener = false

    private val methods: Array<MethodDefinition>

    var cachingEnabled = false
    var cacheSize: Int = 0
    var customCacheFieldName: String? = null
    var customMultiCacheFieldName: String? = null

    var createWithDatabase = true

    var allFields = false
    var useIsForPrivateBooleans: Boolean = false

    val columnMap = mutableMapOf<String, ColumnDefinition>()

    var columnUniqueMap = mutableMapOf<Int, MutableSet<ColumnDefinition>>()

    var oneToManyDefinitions = mutableListOf<OneToManyDefinition>()

    /** Set to true when this definition is populated via [kspInit] rather than from a TypeElement. */
    internal var kspMode = false
    internal var ksClassDeclaration: KSClassDeclaration? = null

    var inheritedColumnMap = hashMapOf<String, InheritedColumn>()
    var inheritedFieldNameList = mutableListOf<String>()
    var inheritedPrimaryKeyMap = hashMapOf<String, InheritedPrimaryKey>()

    var hasPrimaryConstructor = false

    init {

        element.annotation<Table>()?.let { table ->
            this.tableName = table.name

            if (tableName == null || tableName!!.isEmpty()) {
                tableName = element.simpleName.toString()
            }

            try {
                table.database
            } catch (mte: MirroredTypeException) {
                databaseTypeName = TypeName.get(mte.typeMirror)
            }

            cachingEnabled = table.cachingEnabled
            cacheSize = table.cacheSize

            orderedCursorLookUp = table.orderedCursorLookUp
            assignDefaultValuesFromCursor = table.assignDefaultValuesFromCursor

            createWithDatabase = table.createWithDatabase

            allFields = table.allFields
            useIsForPrivateBooleans = table.useBooleanGetterSetters

            elementClassName?.let { databaseTypeName?.let { it1 -> manager.addModelToDatabase(it, it1) } }


            val inheritedColumns = table.inheritedColumns
            inheritedColumns.forEach {
                if (inheritedFieldNameList.contains(it.fieldName)) {
                    manager.logError("A duplicate inherited column with name %1s was found for %1s",
                            it.fieldName, tableName)
                }
                inheritedFieldNameList.add(it.fieldName)
                inheritedColumnMap.put(it.fieldName, it)
            }

            val inheritedPrimaryKeys = table.inheritedPrimaryKeys
            inheritedPrimaryKeys.forEach {
                if (inheritedFieldNameList.contains(it.fieldName)) {
                    manager.logError("A duplicate inherited column with name %1s was found for %1s",
                            it.fieldName, tableName)
                }
                inheritedFieldNameList.add(it.fieldName)
                inheritedPrimaryKeyMap.put(it.fieldName, it)
            }

            implementsLoadFromCursorListener = typeElement?.implementsClass(manager.processingEnvironment,
                    ClassNames.LOAD_FROM_CURSOR_LISTENER) ?: false

            implementsContentValuesListener = typeElement?.implementsClass(manager.processingEnvironment,
                    ClassNames.CONTENT_VALUES_LISTENER) ?: false

            implementsSqlStatementListener = typeElement?.implementsClass(manager.processingEnvironment,
                    ClassNames.SQLITE_STATEMENT_LISTENER) ?: false
        }

        methods = arrayOf(BindToContentValuesMethod(this, true, implementsContentValuesListener),
                BindToContentValuesMethod(this, false, implementsContentValuesListener),
                BindToStatementMethod(this, INSERT), BindToStatementMethod(this, NON_INSERT),
                BindToStatementMethod(this, UPDATE), BindToStatementMethod(this, DELETE),
                InsertStatementQueryMethod(this, true), InsertStatementQueryMethod(this, false),
                UpdateStatementQueryMethod(this), DeleteStatementQueryMethod(this),
                CreationQueryMethod(this), LoadFromCursorMethod(this), ExistenceMethod(this),
                PrimaryConditionMethod(this), OneToManyDeleteMethod(this, false),
                OneToManyDeleteMethod(this, true),
                OneToManySaveMethod(this, OneToManySaveMethod.METHOD_SAVE, false),
                OneToManySaveMethod(this, OneToManySaveMethod.METHOD_INSERT, false),
                OneToManySaveMethod(this, OneToManySaveMethod.METHOD_UPDATE, false),
                OneToManySaveMethod(this, OneToManySaveMethod.METHOD_SAVE, true),
                OneToManySaveMethod(this, OneToManySaveMethod.METHOD_INSERT, true),
                OneToManySaveMethod(this, OneToManySaveMethod.METHOD_UPDATE, true))
    }

    override fun createColumnDefinitions(typeElement: TypeElement) {
        val elements = ElementUtility.getAllElements(typeElement, manager)

        for (element in elements) {
            classElementLookUpMap.put(element.simpleName.toString(), element)
            if (element is ExecutableElement && element.parameters.isEmpty()
                    && element.simpleName.toString() == "<init>"
                    && element.enclosingElement == typeElement
                    && !element.modifiers.contains(Modifier.PRIVATE)) {
                hasPrimaryConstructor = true
            }
        }

        val columnValidator = ColumnValidator()
        val oneToManyValidator = OneToManyValidator()
        elements.forEach { element ->
            // no private static or final fields for all columns, or any inherited columns here.
            val isAllFields = ElementUtility.isValidAllFields(allFields, element)

            // package private, will generate helper
            val isPackagePrivate = ElementUtility.isPackagePrivate(element)
            val isPackagePrivateNotInSamePackage = isPackagePrivate && !ElementUtility.isInSamePackage(manager, element, this.element)

            val isForeign = element.annotation<ForeignKey>() != null
            val isPrimary = element.annotation<PrimaryKey>() != null
            val isInherited = inheritedColumnMap.containsKey(element.simpleName.toString())
            val isInheritedPrimaryKey = inheritedPrimaryKeyMap.containsKey(element.simpleName.toString())
            val isColumnMap = element.annotation<ColumnMap>() != null
            if (element.annotation<Column>() != null || isForeign || isPrimary
                    || isAllFields || isInherited || isInheritedPrimaryKey || isColumnMap) {

                if (checkInheritancePackagePrivate(isPackagePrivateNotInSamePackage, element)) return

                val columnDefinition = if (isInheritedPrimaryKey) {
                    val inherited = inheritedPrimaryKeyMap[element.simpleName.toString()]
                    ColumnDefinition(manager, element, this, isPackagePrivateNotInSamePackage,
                            inherited?.column, inherited?.primaryKey)
                } else if (isInherited) {
                    val inherited = inheritedColumnMap[element.simpleName.toString()]
                    ColumnDefinition(manager, element, this, isPackagePrivateNotInSamePackage,
                            inherited?.column, null, inherited?.nonNullConflict ?: ConflictAction.NONE)
                } else if (isForeign || isColumnMap) {
                    ReferenceColumnDefinition(manager, this,
                            element, isPackagePrivateNotInSamePackage)
                } else {
                    ColumnDefinition(manager, element,
                            this, isPackagePrivateNotInSamePackage)
                }

                if (columnValidator.validate(manager, columnDefinition)) {
                    columnDefinitions.add(columnDefinition)
                    columnMap.put(columnDefinition.columnName, columnDefinition)
                    if (columnDefinition.isPrimaryKey) {
                        _primaryColumnDefinitions.add(columnDefinition)
                    } else if (columnDefinition.isPrimaryKeyAutoIncrement) {
                        autoIncrementColumn = columnDefinition
                        hasAutoIncrement = true
                    } else if (columnDefinition.isRowId) {
                        autoIncrementColumn = columnDefinition
                        hasRowID = true
                    }

                    autoIncrementColumn?.let {
                        // check to ensure not null.
                        if (it.isNullableType) {
                            manager.logWarning("Attempting to use nullable field type on an autoincrementing column. " +
                                    "To suppress or remove this warning " +
                                    "switch to java primitive, add @android.support.annotation.NonNull," +
                                    "@org.jetbrains.annotation.NotNull, or in Kotlin don't make it nullable. Check the column ${it.columnName} " +
                                    "on $tableName")
                        }
                    }

                    if (columnDefinition is ReferenceColumnDefinition && !columnDefinition.isColumnMap) {
                        foreignKeyDefinitions.add(columnDefinition)
                    }

                    if (!columnDefinition.uniqueGroups.isEmpty()) {
                        val groups = columnDefinition.uniqueGroups
                        for (group in groups) {
                            var groupList = columnUniqueMap[group]
                            if (groupList == null) {
                                groupList = mutableSetOf()
                                columnUniqueMap.put(group, groupList)
                            }
                            groupList.add(columnDefinition)
                        }
                    }

                    if (isPackagePrivate) {
                        packagePrivateList.add(columnDefinition)
                    }
                }
            } else if (element.annotation<OneToMany>() != null) {
                val oneToManyDefinition = OneToManyDefinition(element, manager, elements)
                if (oneToManyValidator.validate(manager, oneToManyDefinition)) {
                    oneToManyDefinitions.add(oneToManyDefinition)
                }
            } else if (element.annotation<ModelCacheField>() != null) {
                ensureVisibleStatic(element, typeElement, "ModelCacheField")
                if (!customCacheFieldName.isNullOrEmpty()) {
                    manager.logError("ModelCacheField can only be declared once from: " + typeElement)
                } else {
                    customCacheFieldName = element.simpleName.toString()
                }
            } else if (element.annotation<MultiCacheField>() != null) {
                ensureVisibleStatic(element, typeElement, "MultiCacheField")
                if (!customMultiCacheFieldName.isNullOrEmpty()) {
                    manager.logError("MultiCacheField can only be declared once from: " + typeElement)
                } else {
                    customMultiCacheFieldName = element.simpleName.toString()
                }
            }
        }
    }

    /**
     * KSP initialiser. Reads @Table annotation data from KSP instead of the KAPT init block.
     * Must be called right after construction when the definition is created in KSP mode.
     */
    fun kspInit(ksClass: KSClassDeclaration) {
        kspMode = true
        ksClassDeclaration = ksClass

        elementName = ksClass.simpleName.asString()
        elementClassName = ksClass.toJavaPoetClassName()
        elementTypeName = elementClassName
        packageName = ksClass.packageName.asString()

        val annot = ksClass.findKspAnnotation<Table>() ?: return

        tableName = annot.getStringArgument("name").takeIf { !it.isNullOrEmpty() } ?: elementName

        val dbKsType = annot.getKsTypeArgument("database")
        databaseTypeName = dbKsType?.toJavaPoetTypeName()

        cachingEnabled = annot.getBooleanArgument("cachingEnabled") ?: false
        cacheSize = annot.arguments.find { it.name?.asString() == "cacheSize" }?.value as? Int ?: 0
        orderedCursorLookUp = annot.getBooleanArgument("orderedCursorLookUp") ?: false
        assignDefaultValuesFromCursor = annot.getBooleanArgument("assignDefaultValuesFromCursor") ?: true
        createWithDatabase = annot.getBooleanArgument("createWithDatabase") ?: true
        allFields = annot.getBooleanArgument("allFields") ?: false
        useIsForPrivateBooleans = annot.getBooleanArgument("useBooleanGetterSetters") ?: true

        elementClassName?.let { databaseTypeName?.let { dbt -> manager.addModelToDatabase(it, dbt) } }

        // Interface checks via KSP super types
        val superTypes = ksClass.getAllSuperTypes().map { it.declaration.qualifiedName?.asString() }
        implementsLoadFromCursorListener = ClassNames.LOAD_FROM_CURSOR_LISTENER.toString() in superTypes
        implementsContentValuesListener = ClassNames.CONTENT_VALUES_LISTENER.toString() in superTypes
        implementsSqlStatementListener = ClassNames.SQLITE_STATEMENT_LISTENER.toString() in superTypes
    }

    override fun prepareForWrite() {
        columnDefinitions = ArrayList()
        columnMap.clear()
        classElementLookUpMap.clear()
        _primaryColumnDefinitions.clear()
        uniqueGroupsDefinitions.clear()
        indexGroupsDefinitions.clear()
        foreignKeyDefinitions.clear()
        columnUniqueMap.clear()
        oneToManyDefinitions.clear()
        customCacheFieldName = null
        customMultiCacheFieldName = null

        if (kspMode) {
            prepareForWriteKsp()
            return
        }

        val table = element.getAnnotation(Table::class.java)
        if (table != null) {
            databaseDefinition = manager.getDatabaseHolderDefinition(databaseTypeName)?.databaseDefinition
            if (databaseDefinition == null) {
                manager.logError("DatabaseDefinition was null for : $tableName for db type: $databaseTypeName")
            }
            databaseDefinition?.let {
                setOutputClassName("${it.classSeparator}Table")

                var insertConflict = table.insertConflict
                if (insertConflict == ConflictAction.NONE && it.insertConflict != ConflictAction.NONE) {
                    insertConflict = it.insertConflict ?: ConflictAction.NONE
                }

                var updateConflict = table.updateConflict
                if (updateConflict == ConflictAction.NONE && it.updateConflict != ConflictAction.NONE) {
                    updateConflict = it.updateConflict ?: ConflictAction.NONE
                }

                val primaryKeyConflict = table.primaryKeyConflict

                insertConflictActionName = if (insertConflict == ConflictAction.NONE) "" else insertConflict.name
                updateConflictActionName = if (updateConflict == ConflictAction.NONE) "" else updateConflict.name
                primaryKeyConflictActionName = if (primaryKeyConflict == ConflictAction.NONE) "" else primaryKeyConflict.name
            }

            typeElement?.let { createColumnDefinitions(it) }

            val groups = table.uniqueColumnGroups
            var uniqueNumbersSet: MutableSet<Int> = HashSet()
            for (uniqueGroup in groups) {
                if (uniqueNumbersSet.contains(uniqueGroup.groupNumber)) {
                    manager.logError("A duplicate unique group with number %1s was found for %1s", uniqueGroup.groupNumber, tableName)
                }
                val definition = UniqueGroupsDefinition(uniqueGroup)
                columnDefinitions.filter { it.uniqueGroups.contains(definition.number) }
                        .forEach { definition.addColumnDefinition(it) }
                uniqueGroupsDefinitions.add(definition)
                uniqueNumbersSet.add(uniqueGroup.groupNumber)
            }

            val indexGroups = table.indexGroups
            uniqueNumbersSet = HashSet()
            for (indexGroup in indexGroups) {
                if (uniqueNumbersSet.contains(indexGroup.number)) {
                    manager.logError(TableDefinition::class, "A duplicate unique index number %1s was found for %1s", indexGroup.number, elementName)
                }
                val definition = IndexGroupsDefinition(this, indexGroup)
                columnDefinitions.filter { it.indexGroups.contains(definition.indexNumber) }
                        .forEach { definition.columnDefinitionList.add(it) }
                indexGroupsDefinitions.add(definition)
                uniqueNumbersSet.add(indexGroup.number)
            }
        }
    }

    private fun prepareForWriteKsp() {
        val ksClass = ksClassDeclaration ?: return
        databaseDefinition = manager.getDatabaseHolderDefinition(databaseTypeName)?.databaseDefinition
        if (databaseDefinition == null) {
            manager.logError("DatabaseDefinition was null for KSP table: $tableName for db type: $databaseTypeName")
            return
        }

        // Determine whether the class has a usable no-arg constructor.
        // In Kotlin, a primary ctor where all params have defaults generates a synthetic no-arg ctor.
        val primaryCtor = ksClass.primaryConstructor
        hasPrimaryConstructor = if (primaryCtor == null) {
            true // implicit no-arg constructor
        } else {
            com.google.devtools.ksp.symbol.Modifier.PRIVATE !in primaryCtor.modifiers &&
                (primaryCtor.parameters.isEmpty() || primaryCtor.parameters.all { it.hasDefault })
        }

        databaseDefinition?.let { dbDef ->
            setOutputClassName("${dbDef.classSeparator}Table")

            val annot = ksClass.findKspAnnotation<Table>()
            val insertConflictName = annot?.getEnumArgument("insertConflict") ?: "NONE"
            val updateConflictName = annot?.getEnumArgument("updateConflict") ?: "NONE"
            val primaryKeyConflictName = annot?.getEnumArgument("primaryKeyConflict") ?: "NONE"

            var insertConflict = runCatching { ConflictAction.valueOf(insertConflictName) }.getOrDefault(ConflictAction.NONE)
            if (insertConflict == ConflictAction.NONE && dbDef.insertConflict != ConflictAction.NONE) {
                insertConflict = dbDef.insertConflict ?: ConflictAction.NONE
            }
            var updateConflict = runCatching { ConflictAction.valueOf(updateConflictName) }.getOrDefault(ConflictAction.NONE)
            if (updateConflict == ConflictAction.NONE && dbDef.updateConflict != ConflictAction.NONE) {
                updateConflict = dbDef.updateConflict ?: ConflictAction.NONE
            }
            val primaryKeyConflict = runCatching { ConflictAction.valueOf(primaryKeyConflictName) }.getOrDefault(ConflictAction.NONE)

            insertConflictActionName = if (insertConflict == ConflictAction.NONE) "" else insertConflict.name
            updateConflictActionName = if (updateConflict == ConflictAction.NONE) "" else updateConflict.name
            primaryKeyConflictActionName = if (primaryKeyConflict == ConflictAction.NONE) "" else primaryKeyConflict.name
        }

        createColumnDefinitionsFromKsp(ksClass)
    }

    // KSP's getAllProperties() skips private members from superclasses; we need them too (e.g.
    // @PrimaryKey private Integer id in a Java superclass). Collect own declarations + all
    // superclass declarations without a private-visibility filter.
    private fun collectAllPropertiesIncludingInherited(
        decl: KSClassDeclaration,
        seen: MutableSet<String> = mutableSetOf()
    ): List<com.google.devtools.ksp.symbol.KSPropertyDeclaration> {
        val result = mutableListOf<com.google.devtools.ksp.symbol.KSPropertyDeclaration>()
        for (prop in decl.declarations.filterIsInstance<com.google.devtools.ksp.symbol.KSPropertyDeclaration>()) {
            if (seen.add(prop.simpleName.asString())) result.add(prop)
        }
        for (superTypeRef in decl.superTypes) {
            val superDecl = superTypeRef.resolve().declaration as? KSClassDeclaration ?: continue
            val qName = superDecl.qualifiedName?.asString() ?: continue
            if (qName == "java.lang.Object" || qName == "kotlin.Any") continue
            result.addAll(collectAllPropertiesIncludingInherited(superDecl, seen))
        }
        return result
    }

    private fun createColumnDefinitionsFromKsp(ksClass: KSClassDeclaration) {
        // Collect all properties including private ones from superclasses.
        val allProperties = collectAllPropertiesIncludingInherited(ksClass)

        // Pre-populate classElementLookUpMap so ColumnValidator's getter/setter checks pass.
        // Kotlin generates get/set accessors for every var property that lacks @JvmField.
        for (property in allProperties) {
            val name = property.simpleName.asString()
            classElementLookUpMap[name] = KSP_SENTINEL_ELEMENT
            val cap = name.replaceFirstChar { it.uppercase() }
            classElementLookUpMap["get$cap"] = KSP_SENTINEL_ELEMENT
            classElementLookUpMap["set$cap"] = KSP_SENTINEL_ELEMENT
            // Boolean "isXxx" → getter stays "isXxx", setter becomes "setXxx"
            if (name.startsWith("is", ignoreCase = true)) {
                classElementLookUpMap[name] = KSP_SENTINEL_ELEMENT
                val withoutIs = name.removePrefix("is").removePrefix("Is")
                    .replaceFirstChar { it.uppercase() }
                classElementLookUpMap["set$withoutIs"] = KSP_SENTINEL_ELEMENT
            }
        }
        // Also add functions from the whole hierarchy (needed for Java getter/setter presence checks)
        fun addFunctions(decl: KSClassDeclaration) {
            for (fn in decl.declarations.filterIsInstance<com.google.devtools.ksp.symbol.KSFunctionDeclaration>()) {
                classElementLookUpMap[fn.simpleName.asString()] = KSP_SENTINEL_ELEMENT
            }
            for (superTypeRef in decl.superTypes) {
                val superDecl = superTypeRef.resolve().declaration as? KSClassDeclaration ?: continue
                val qName = superDecl.qualifiedName?.asString() ?: continue
                if (qName == "java.lang.Object" || qName == "kotlin.Any") continue
                addFunctions(superDecl)
            }
        }
        addFunctions(ksClass)

        // Scan companion objects for @MultiCacheField and @ModelCacheField properties
        for (decl in ksClass.declarations) {
            val companion = decl as? KSClassDeclaration ?: continue
            if (!companion.isCompanionObject) continue
            for (prop in companion.declarations.filterIsInstance<com.google.devtools.ksp.symbol.KSPropertyDeclaration>()) {
                when {
                    prop.findKspAnnotation<MultiCacheField>() != null -> {
                        if (!customMultiCacheFieldName.isNullOrEmpty()) {
                            manager.logError("MultiCacheField can only be declared once from: $elementName")
                        } else {
                            customMultiCacheFieldName = prop.simpleName.asString()
                        }
                    }
                    prop.findKspAnnotation<ModelCacheField>() != null -> {
                        if (!customCacheFieldName.isNullOrEmpty()) {
                            manager.logError("ModelCacheField can only be declared once from: $elementName")
                        } else {
                            customCacheFieldName = prop.simpleName.asString()
                        }
                    }
                }
            }
        }

        // Scan methods for @OneToMany annotations
        val oneToManyValidator = OneToManyValidator()
        fun scanFunctionsForOneToMany(decl: KSClassDeclaration) {
            for (fn in decl.declarations.filterIsInstance<com.google.devtools.ksp.symbol.KSFunctionDeclaration>()) {
                if (fn.findKspAnnotation<OneToMany>() != null) {
                    val def = OneToManyDefinition(KSP_SENTINEL_ELEMENT, manager)
                    def.kspInit(fn, classElementLookUpMap)
                    if (oneToManyValidator.validate(manager, def)) {
                        oneToManyDefinitions.add(def)
                    }
                }
            }
        }
        scanFunctionsForOneToMany(ksClass)
        for (superTypeRef in ksClass.superTypes) {
            val superDecl = superTypeRef.resolve().declaration as? KSClassDeclaration ?: continue
            val qName = superDecl.qualifiedName?.asString() ?: continue
            if (qName == "java.lang.Object" || qName == "kotlin.Any") continue
            scanFunctionsForOneToMany(superDecl)
        }

        val columnValidator = ColumnValidator()
        val properties = allProperties

        for (property in properties) {
            val hasColumn = property.findKspAnnotation<Column>() != null
            val hasPrimaryKey = property.findKspAnnotation<PrimaryKey>() != null
            val hasForeignKey = property.findKspAnnotation<ForeignKey>() != null
            val isColumnMap = property.findKspAnnotation<ColumnMap>() != null

            // allFields: include public non-static mutable fields that aren't ignored
            // Exclude val/final fields to match KAPT behavior (KAPT skips Java `final` backing fields)
            val isAllFieldsCandidate = allFields &&
                    com.google.devtools.ksp.symbol.Modifier.PRIVATE !in property.modifiers &&
                    com.google.devtools.ksp.symbol.Modifier.JAVA_STATIC !in property.modifiers &&
                    property.isMutable

            if (!hasColumn && !hasPrimaryKey && !hasForeignKey && !isColumnMap && !isAllFieldsCandidate) continue

            if (isColumnMap) {
                manager.logWarning("KSP: @ColumnMap on ${property.simpleName.asString()} in $tableName " +
                        "is not yet fully supported – skipped.")
                continue
            }

            val colDef: ColumnDefinition
            if (hasForeignKey) {
                val refDef = ReferenceColumnDefinition(manager, this, KSP_SENTINEL_ELEMENT, false)
                refDef.kspInit(property)
                colDef = refDef
            } else {
                colDef = ColumnDefinition(manager, KSP_SENTINEL_ELEMENT, this, false)
                colDef.kspInit(property)
            }

            if (columnValidator.validate(manager, colDef)) {
                columnDefinitions.add(colDef)
                columnMap[colDef.columnName] = colDef
                when {
                    colDef.isPrimaryKey -> _primaryColumnDefinitions.add(colDef)
                    colDef.isPrimaryKeyAutoIncrement -> {
                        autoIncrementColumn = colDef
                        hasAutoIncrement = true
                    }
                    colDef.isRowId -> {
                        autoIncrementColumn = colDef
                        hasRowID = true
                    }
                }
                autoIncrementColumn?.let {
                    if (it.isNullableType) {
                        manager.logWarning("Nullable autoincrement column ${it.columnName} on $tableName")
                    }
                }
                if (colDef is ReferenceColumnDefinition && !colDef.isColumnMap) {
                    foreignKeyDefinitions.add(colDef)
                }
                if (!colDef.uniqueGroups.isEmpty()) {
                    colDef.uniqueGroups.forEach { group ->
                        columnUniqueMap.getOrPut(group) { mutableSetOf() }.add(colDef)
                    }
                }
            }
        }
    }

    override val primaryColumnDefinitions: List<ColumnDefinition>
        get() = autoIncrementColumn?.let { arrayListOf(it) } ?: _primaryColumnDefinitions

    override val extendsClass: TypeName?
        get() = ParameterizedTypeName.get(ClassNames.MODEL_ADAPTER, elementClassName)

    override fun onWriteDefinition(typeBuilder: TypeSpec.Builder) {
        // check references to properly set them up.
        foreignKeyDefinitions.forEach { it.checkNeedsReferences() }
        typeBuilder.apply {

            writeGetModelClass(this, elementClassName)
            writeConstructor(this)

            `override fun`(String::class, "getTableName") {
                modifiers(public, final)
                `return`(QueryBuilder.quote(tableName).S)
            }

            `override fun`(elementClassName!!, "newInstance") {
                modifiers(public, final)
                `return`("new \$T()", elementClassName)
            }

            if (updateConflictActionName.isNotEmpty()) {
                `override fun`(ClassNames.CONFLICT_ACTION, "getUpdateOnConflictAction") {
                    modifiers(public, final)
                    `return`("\$T.$updateConflictActionName", ClassNames.CONFLICT_ACTION)
                }
            }

            if (insertConflictActionName.isNotEmpty()) {
                `override fun`(ClassNames.CONFLICT_ACTION, "getInsertOnConflictAction") {
                    modifiers(public, final)
                    `return`("\$T.$insertConflictActionName", ClassNames.CONFLICT_ACTION)
                }
            }

            val paramColumnName = "columnName"
            val getPropertiesBuilder = CodeBlock.builder()

            `override fun`(ClassNames.PROPERTY, "getProperty",
                    param(String::class, paramColumnName)) {
                modifiers(public, final)
                statement("$paramColumnName = \$T.quoteIfNeeded($paramColumnName)", ClassName.get(QueryBuilder::class.java))

                switch("($paramColumnName)") {
                    columnDefinitions.indices.forEach { i ->
                        if (i > 0) {
                            getPropertiesBuilder.add(",")
                        }
                        val columnDefinition = columnDefinitions[i]
                        elementClassName?.let { columnDefinition.addPropertyDefinition(typeBuilder, it) }
                        columnDefinition.addPropertyCase(this)
                        columnDefinition.addColumnName(getPropertiesBuilder)
                    }

                    default {
                        `throw new`(IllegalArgumentException::class, "Invalid column name passed. Ensure you are calling the correct table's column")
                    }
                }
            }

            `public static final field`(ArrayTypeName.of(ClassNames.IPROPERTY), "ALL_COLUMN_PROPERTIES") {
                `=`("new \$T[]{\$L}", ClassNames.IPROPERTY, getPropertiesBuilder.build().toString())
            }

            // add index properties here
            for (indexGroupsDefinition in indexGroupsDefinitions) {
                addField(indexGroupsDefinition.fieldSpec)
            }

            if (hasAutoIncrement || hasRowID) {
                val autoIncrement = autoIncrementColumn
                autoIncrement?.let {
                    `override fun`(TypeName.VOID, "updateAutoIncrement", param(elementClassName!!, ModelUtils.variable),
                            param(Number::class, "id")) {
                        modifiers(public, final)
                        addCode(autoIncrement.updateAutoIncrementMethod)
                    }

                    `override fun`(Number::class, "getAutoIncrementingId", param(elementClassName!!, ModelUtils.variable)) {
                        modifiers(public, final)
                        addCode(autoIncrement.getSimpleAccessString())
                    }
                    `override fun`(String::class, "getAutoIncrementingColumnName") {
                        modifiers(public, final)
                        `return`(QueryBuilder.stripQuotes(autoIncrement.columnName).S)
                    }

                    `override fun`(ParameterizedTypeName.get(ClassNames.SINGLE_MODEL_SAVER, elementClassName!!), "createSingleModelSaver") {
                        modifiers(public, final)
                        `return`("new \$T<>()", ClassNames.AUTOINCREMENT_MODEL_SAVER)
                    }
                }
            }

            val saveForeignKeyFields = columnDefinitions
                    .filter { (it is ReferenceColumnDefinition) && it.saveForeignKeyModel }
                    .map { it as ReferenceColumnDefinition }
            if (saveForeignKeyFields.isNotEmpty()) {
                val code = CodeBlock.builder()
                saveForeignKeyFields.forEach { it.appendSaveMethod(code) }

                `override fun`(TypeName.VOID, "saveForeignKeys", param(elementClassName!!, ModelUtils.variable),
                        param(ClassNames.DATABASE_WRAPPER, ModelUtils.wrapper)) {
                    modifiers(public, final)
                    addCode(code.build())
                }
            }

            val deleteForeignKeyFields = columnDefinitions
                    .filter { (it is ReferenceColumnDefinition) && it.deleteForeignKeyModel }
                    .map { it as ReferenceColumnDefinition }
            if (deleteForeignKeyFields.isNotEmpty()) {
                val code = CodeBlock.builder()
                deleteForeignKeyFields.forEach { it.appendDeleteMethod(code) }

                `override fun`(TypeName.VOID, "deleteForeignKeys", param(elementClassName!!, ModelUtils.variable),
                        param(ClassNames.DATABASE_WRAPPER, ModelUtils.wrapper)) {
                    modifiers(public, final)
                    addCode(code.build())
                }
            }

            `override fun`(ArrayTypeName.of(ClassNames.IPROPERTY), "getAllColumnProperties") {
                modifiers(public, final)
                `return`("ALL_COLUMN_PROPERTIES")
            }

            if (!createWithDatabase) {
                `override fun`(TypeName.BOOLEAN, "createWithDatabase") {
                    modifiers(public, final)
                    `return`(false.L)
                }
            }

            if (cachingEnabled) {

                val singlePrimaryKey = primaryColumnDefinitions.size == 1

                `override fun`(ClassNames.SINGLE_MODEL_LOADER, "createSingleModelLoader") {
                    modifiers(public, final)
                    addStatement("return new \$T<>(getModelClass())",
                            if (singlePrimaryKey)
                                ClassNames.SINGLE_KEY_CACHEABLE_MODEL_LOADER
                            else
                                ClassNames.CACHEABLE_MODEL_LOADER)
                }
                `override fun`(ClassNames.LIST_MODEL_LOADER, "createListModelLoader") {
                    modifiers(public, final)
                    `return`("new \$T<>(getModelClass())",
                            if (singlePrimaryKey)
                                ClassNames.SINGLE_KEY_CACHEABLE_LIST_MODEL_LOADER
                            else
                                ClassNames.CACHEABLE_LIST_MODEL_LOADER)
                }
                `override fun`(ParameterizedTypeName.get(ClassNames.CACHEABLE_LIST_MODEL_SAVER, elementClassName),
                        "createListModelSaver") {
                    modifiers(protected)
                    `return`("new \$T<>(getModelSaver())", ClassNames.CACHEABLE_LIST_MODEL_SAVER)
                }
                `override fun`(TypeName.BOOLEAN, "cachingEnabled") {
                    modifiers(public, final)
                    `return`(true.L)
                }

                `override fun`(TypeName.VOID, "load", param(elementClassName!!, "model"),
                        param(ClassNames.DATABASE_WRAPPER, wrapper)) {
                    modifiers(public, final)
                    statement("super.load(model, $wrapper)")
                    statement("getModelCache().addModel(getCachingId(${ModelUtils.variable}), ${ModelUtils.variable})")
                }

                val primaryColumns = primaryColumnDefinitions
                if (primaryColumns.size > 1) {
                    `override fun`(ArrayTypeName.of(Any::class.java), "getCachingColumnValuesFromModel",
                            param(ArrayTypeName.of(Any::class.java), "inValues"),
                            param(elementClassName!!, ModelUtils.variable)) {
                        modifiers(public, final)
                        for (i in primaryColumns.indices) {
                            val column = primaryColumns[i]
                            addCode(column.getColumnAccessString(i))
                        }

                        `return`("inValues")
                    }

                    `override fun`(ArrayTypeName.of(Any::class.java), "getCachingColumnValuesFromCursor",
                            param(ArrayTypeName.of(Any::class.java), "inValues"),
                            param(ClassNames.FLOW_CURSOR, "cursor")) {
                        modifiers(public, final)
                        for (i in primaryColumns.indices) {
                            val column = primaryColumns[i]
                            val method = DefinitionUtils.getLoadFromCursorMethodString(column.elementTypeName, column.wrapperTypeName)
                            statement("inValues[$i] = ${LoadFromCursorMethod.PARAM_CURSOR}" +
                                    ".$method(${LoadFromCursorMethod.PARAM_CURSOR}.getColumnIndex(${column.columnName.S}))")
                        }
                        `return`("inValues")
                    }
                } else {
                    // single primary key
                    `override fun`(Any::class, "getCachingColumnValueFromModel",
                            param(elementClassName!!, ModelUtils.variable)) {
                        modifiers(public, final)
                        addCode(primaryColumns[0].getSimpleAccessString())
                    }

                    `override fun`(Any::class, "getCachingColumnValueFromCursor", param(ClassNames.FLOW_CURSOR, "cursor")) {
                        modifiers(public, final)
                        val column = primaryColumns[0]
                        val method = DefinitionUtils.getLoadFromCursorMethodString(column.elementTypeName, column.wrapperTypeName)
                        `return`("${LoadFromCursorMethod.PARAM_CURSOR}.$method(${LoadFromCursorMethod.PARAM_CURSOR}.getColumnIndex(${column.columnName.S}))")
                    }
                    `override fun`(Any::class, "getCachingId", param(elementClassName!!, ModelUtils.variable)) {
                        modifiers(public, final)
                        `return`("getCachingColumnValueFromModel(${ModelUtils.variable})")
                    }
                }

                `override fun`(ArrayTypeName.of(ClassName.get(String::class.java)), "createCachingColumns") {
                    modifiers(public, final)
                    `return`("new String[]{${primaryColumns.joinToString { QueryBuilder.quoteIfNeeded(it.columnName).S }}}")
                }

                if (cacheSize != Table.DEFAULT_CACHE_SIZE) {
                    `override fun`(TypeName.INT, "getCacheSize") {
                        modifiers(public, final)
                        `return`(cacheSize.L)
                    }
                }

                if (!customCacheFieldName.isNullOrEmpty()) {
                    `override fun`(ParameterizedTypeName.get(ClassNames.MODEL_CACHE, elementClassName,
                            WildcardTypeName.subtypeOf(Any::class.java)), "createModelCache") {
                        modifiers(public, final)
                        `return`("\$T.$customCacheFieldName", elementClassName)
                    }
                }

                if (!customMultiCacheFieldName.isNullOrEmpty()) {
                    `override fun`(ParameterizedTypeName.get(ClassNames.MULTI_KEY_CACHE_CONVERTER,
                            WildcardTypeName.subtypeOf(Any::class.java)), "getCacheConverter") {
                        modifiers(public, final)
                        `return`("\$T.$customMultiCacheFieldName", elementClassName)
                    }
                }

                if (foreignKeyDefinitions.isNotEmpty()) {
                    `override fun`(TypeName.VOID, "reloadRelationships",
                            param(elementClassName!!, ModelUtils.variable),
                            param(ClassNames.FLOW_CURSOR, LoadFromCursorMethod.PARAM_CURSOR)) {
                        modifiers(public, final)
                        code {
                            val noIndex = AtomicInteger(-1)
                            val nameAllocator = NameAllocator()
                            foreignKeyDefinitions.forEach { add(it.getLoadFromCursorMethod(false, noIndex, nameAllocator)) }
                            this
                        }
                    }
                }
            }
        }

        methods.mapNotNull { it.methodSpec }
                .forEach { typeBuilder.addMethod(it) }
    }
}
