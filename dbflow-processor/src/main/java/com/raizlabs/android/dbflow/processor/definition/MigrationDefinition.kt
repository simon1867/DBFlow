package com.raizlabs.android.dbflow.processor.definition

import com.grosner.kpoet.typeName
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.raizlabs.android.dbflow.annotation.Migration
import com.raizlabs.android.dbflow.processor.KSP_SENTINEL_ELEMENT
import com.raizlabs.android.dbflow.processor.ProcessorManager
import com.raizlabs.android.dbflow.processor.utils.annotation
import com.raizlabs.android.dbflow.processor.utils.findKspAnnotation
import com.raizlabs.android.dbflow.processor.utils.getIntArgument
import com.raizlabs.android.dbflow.processor.utils.getKsTypeArgument
import com.raizlabs.android.dbflow.processor.utils.isNullOrEmpty
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetClassName
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypeException

/**
 * Description: Used in holding data about migration files.
 */
class MigrationDefinition(processorManager: ProcessorManager, element: Element)
    : BaseDefinition(element, processorManager) {

    var databaseName: TypeName? = null

    var version: Int = 0

    var priority = -1

    var constructorName: String? = null
        private set

    init {
        setOutputClassName("")

        val typeEl = element as? TypeElement
        val migration = typeEl?.annotation<Migration>()
        if (migration == null && element !== KSP_SENTINEL_ELEMENT) {
            processorManager.logError("Migration was null for: $element")
        } else if (migration != null) {
            try {
                migration.database
            } catch (mte: MirroredTypeException) {
                databaseName = mte.typeMirror.typeName
            }

            version = migration.version
            priority = migration.priority

            val enclosed = typeEl?.enclosedElements ?: emptyList()
            enclosed.forEach { enclosed ->
                if (enclosed is ExecutableElement && enclosed.simpleName.toString() == "<init>") {
                    if (!constructorName.isNullOrEmpty()) {
                        manager.logError(MigrationDefinition::class, "Migrations cannot have more than one constructor. " +
                                "They can only have an Empty() or single-parameter constructor Empty(Empty.class) that specifies " +
                                "the .class of this migration class.")
                    }

                    if (enclosed.parameters.isEmpty()) {
                        constructorName = "()"
                    } else if (enclosed.parameters.size == 1) {
                        val params = enclosed.parameters
                        val param = params[0]

                        val type = param.asType().typeName
                        if (type is ParameterizedTypeName && type.rawType == ClassName.get(Class::class.java)) {
                            val containedType = type.typeArguments[0]
                            constructorName = CodeBlock.of("(\$T.class)", containedType).toString()
                        } else {
                            manager.logError(MigrationDefinition::class, "Wrong parameter type found for $element. Found $type but required ModelClass.class")
                        }
                    }
                }
            }
        }
    }

    fun kspInit(ksClass: KSClassDeclaration) {
        elementName = ksClass.simpleName.asString()
        elementClassName = ksClass.toJavaPoetClassName()
        elementTypeName = elementClassName
        packageName = ksClass.packageName.asString()

        val annot = ksClass.findKspAnnotation<Migration>() ?: return

        val dbKsType = annot.getKsTypeArgument("database")
        databaseName = dbKsType?.toJavaPoetTypeName()

        version = annot.getIntArgument("version") ?: 0
        priority = annot.arguments.find { it.name?.asString() == "priority" }?.value as? Int ?: -1

        val constructors = ksClass.getConstructors().toList()
        for (ctor in constructors) {
            if (!constructorName.isNullOrEmpty()) {
                manager.logError(MigrationDefinition::class, "Migrations cannot have more than one constructor.")
            }
            if (ctor.parameters.isEmpty()) {
                constructorName = "()"
            } else if (ctor.parameters.size == 1) {
                val paramType = ctor.parameters[0].type.resolve().toJavaPoetTypeName()
                if (paramType is ParameterizedTypeName && paramType.rawType == ClassName.get(Class::class.java)) {
                    val containedType = paramType.typeArguments[0]
                    constructorName = CodeBlock.of("(\$T.class)", containedType).toString()
                } else {
                    manager.logError(MigrationDefinition::class, "Wrong parameter type for $elementName: found $paramType but required Class<MigrationClass>")
                }
            }
        }
    }

}
