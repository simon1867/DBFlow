package com.raizlabs.android.dbflow.processor.definition

import com.grosner.kpoet.*
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.raizlabs.android.dbflow.annotation.ForeignKey
import com.raizlabs.android.dbflow.annotation.ManyToMany
import com.raizlabs.android.dbflow.annotation.PrimaryKey
import com.raizlabs.android.dbflow.annotation.Table
import com.raizlabs.android.dbflow.processor.ClassNames
import com.raizlabs.android.dbflow.processor.ProcessorManager
import com.raizlabs.android.dbflow.processor.utils.annotation
import com.raizlabs.android.dbflow.processor.utils.findKspAnnotation
import com.raizlabs.android.dbflow.processor.utils.getBooleanArgument
import com.raizlabs.android.dbflow.processor.utils.getKsTypeArgument
import com.raizlabs.android.dbflow.processor.utils.getStringArgument
import com.raizlabs.android.dbflow.processor.utils.isNullOrEmpty
import com.raizlabs.android.dbflow.processor.utils.lower
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetClassName
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetTypeName
import com.raizlabs.android.dbflow.processor.utils.toTypeElement
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypeException
import javax.lang.model.type.TypeMirror

/**
 * Description: Generates the Model class that is used in a many to many.
 */
class ManyToManyDefinition(element: Element, processorManager: ProcessorManager,
                           manyToMany: ManyToMany? = (element as? TypeElement)?.annotation<ManyToMany>())
    : BaseDefinition(element, processorManager) {

    internal var referencedTable: TypeName = TypeName.OBJECT
    var databaseTypeName: TypeName? = null
    internal var generateAutoIncrement: Boolean = false
    internal var sameTableReferenced: Boolean = false
    internal var generatedTableClassName: String = ""
    internal var saveForeignKeyModels: Boolean = false
    internal var thisColumnName: String = ""
    internal var referencedColumnName: String = ""

    init {
        if (manyToMany != null) {
            var clazz: TypeMirror? = null
            try {
                manyToMany.referencedTable
            } catch (mte: MirroredTypeException) {
                clazz = mte.typeMirror
            }
            referencedTable = if (clazz != null) TypeName.get(clazz) else TypeName.OBJECT
            generateAutoIncrement = manyToMany.generateAutoIncrement
            saveForeignKeyModels = manyToMany.saveForeignKeyModels
            generatedTableClassName = manyToMany.generatedTableClassName
            thisColumnName = manyToMany.thisTableColumnName
            referencedColumnName = manyToMany.referencedTableColumnName

            sameTableReferenced = referencedTable == elementTypeName

            (element as? TypeElement)?.annotation<Table>()?.let { table ->
                try {
                    table.database
                } catch (mte: MirroredTypeException) {
                    databaseTypeName = TypeName.get(mte.typeMirror)
                }
            }

            if (!thisColumnName.isNullOrEmpty() && !referencedColumnName.isNullOrEmpty()
                    && thisColumnName == referencedColumnName) {
                manager.logError(ManyToManyDefinition::class, "The thisTableColumnName and referenceTableColumnName cannot be the same")
            }
        }
    }

    fun kspInit(ksClass: KSClassDeclaration) {
        elementName = ksClass.simpleName.asString()
        packageName = ksClass.packageName.asString()
        elementClassName = ksClass.toJavaPoetClassName()
        elementTypeName = elementClassName

        val annot = ksClass.findKspAnnotation<ManyToMany>() ?: return

        referencedTable = annot.getKsTypeArgument("referencedTable")?.toJavaPoetTypeName() ?: TypeName.OBJECT
        generateAutoIncrement = annot.getBooleanArgument("generateAutoIncrement") ?: true
        saveForeignKeyModels = annot.getBooleanArgument("saveForeignKeyModels") ?: false
        generatedTableClassName = annot.getStringArgument("generatedTableClassName") ?: ""
        thisColumnName = annot.getStringArgument("thisTableColumnName") ?: ""
        referencedColumnName = annot.getStringArgument("referencedTableColumnName") ?: ""

        databaseTypeName = ksClass.findKspAnnotation<Table>()?.getKsTypeArgument("database")?.toJavaPoetTypeName()

        sameTableReferenced = referencedTable == elementTypeName
    }

    fun prepareForWrite() {
        val databaseDefinition = manager.getDatabaseHolderDefinition(databaseTypeName)?.databaseDefinition
        if (databaseDefinition == null) {
            manager.logError("DatabaseDefinition was null for : $elementName")
        } else {
            if (generatedTableClassName.isNullOrEmpty()) {
                // Try ClassName.simpleName() first (works in both KSP and KAPT).
                // Fall back to KAPT TypeElement lookup only if needed.
                val simpleName = (referencedTable as? ClassName)?.simpleName()
                    ?: getElementClassName(referencedTable.toTypeElement(manager))?.simpleName()
                setOutputClassName(databaseDefinition.classSeparator + simpleName)
            } else {
                setOutputClassNameFull(generatedTableClassName)
            }
        }
    }

    override fun onWriteDefinition(typeBuilder: TypeSpec.Builder) {
        typeBuilder.apply {
            addAnnotation(AnnotationSpec.builder(Table::class.java)
                    .addMember("database", "\$T.class", databaseTypeName).build())

            val referencedDefinition = manager.getTableDefinition(databaseTypeName, referencedTable)
            val selfDefinition = manager.getTableDefinition(databaseTypeName, elementTypeName)

            if (generateAutoIncrement) {
                addField(field(`@`(PrimaryKey::class) { this["autoincrement"] = "true" }, TypeName.LONG, "_id").build())

                `fun`(TypeName.LONG, "getId") {
                    modifiers(public, final)
                    `return`("_id")
                }
            }

            referencedDefinition?.let { appendColumnDefinitions(this, it, 0, referencedColumnName) }
            selfDefinition?.let { appendColumnDefinitions(this, it, 1, thisColumnName) }
        }
    }

    override val extendsClass: TypeName?
        get() = ClassNames.BASE_MODEL

    private fun appendColumnDefinitions(typeBuilder: TypeSpec.Builder,
                                        referencedDefinition: TableDefinition, index: Int, optionalName: String) {
        var fieldName = referencedDefinition.elementName.lower()
        if (sameTableReferenced) {
            fieldName += index.toString()
        }
        // override with the name (if specified)
        if (!optionalName.isNullOrEmpty()) {
            fieldName = optionalName
        }

        typeBuilder.apply {
            `field`(referencedDefinition.elementClassName!!, fieldName) {
                if (!generateAutoIncrement) {
                    `@`(PrimaryKey::class)
                }
                `@`(ForeignKey::class) { member("saveForeignKeyModel", saveForeignKeyModels.toString()) }
            }
            `fun`(referencedDefinition.elementClassName!!, "get${fieldName.capitalize()}") {
                modifiers(public, final)
                `return`(fieldName.L)
            }
            `fun`(TypeName.VOID, "set${fieldName.capitalize()}",
                    param(referencedDefinition.elementClassName!!, "param")) {
                modifiers(public, final)
                statement("$fieldName = param")
            }
        }
    }
}
