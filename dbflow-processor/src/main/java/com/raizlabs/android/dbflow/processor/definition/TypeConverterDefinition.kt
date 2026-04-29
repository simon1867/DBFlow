package com.raizlabs.android.dbflow.processor.definition

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.raizlabs.android.dbflow.annotation.TypeConverter
import com.raizlabs.android.dbflow.processor.ClassNames
import com.raizlabs.android.dbflow.processor.KSP_SENTINEL_TYPE_MIRROR
import com.raizlabs.android.dbflow.processor.ProcessorManager
import com.raizlabs.android.dbflow.processor.utils.annotation
import com.raizlabs.android.dbflow.processor.utils.findKspAnnotation
import com.raizlabs.android.dbflow.processor.utils.getArrayArgument
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetClassName
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.TypeName
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.MirroredTypesException
import javax.lang.model.type.TypeMirror

/**
 * Description: Holds data about type converters in order to write them.
 */
class TypeConverterDefinition(val className: ClassName,
                              typeMirror: TypeMirror, manager: ProcessorManager,
                              typeElement: TypeElement? = null) {

    var modelTypeName: TypeName? = null

    var dbTypeName: TypeName? = null

    var allowedSubTypes: List<TypeName>? = null

    init {

        typeElement.annotation<TypeConverter>()?.let { annotation ->
            val allowedSubTypes: MutableList<TypeName> = mutableListOf()
            try {
                annotation.allowedSubtypes;
            } catch (e: MirroredTypesException) {
                val types = e.typeMirrors
                types.forEach { allowedSubTypes.add(TypeName.get(it)) }
            }
            this.allowedSubTypes = allowedSubTypes
        }

        val types = manager.typeUtils

        var typeConverterSuper: DeclaredType? = null
        val typeConverter = manager.typeUtils.getDeclaredType(manager.elements
                .getTypeElement(ClassNames.TYPE_CONVERTER.toString()))

        for (superType in types.directSupertypes(typeMirror)) {
            val erasure = types.erasure(superType)
            if (types.isAssignable(erasure, typeConverter) || erasure.toString() == typeConverter.toString()) {
                typeConverterSuper = superType as DeclaredType
            }
        }

        if (typeConverterSuper != null) {
            val typeArgs = typeConverterSuper.typeArguments
            dbTypeName = ClassName.get(typeArgs[0])
            modelTypeName = ClassName.get(typeArgs[1])
        }
    }

    companion object {
        private val TYPE_CONVERTER_QNAME = ClassNames.TYPE_CONVERTER.toString()

        fun fromKsp(ksClass: KSClassDeclaration, manager: ProcessorManager): TypeConverterDefinition? {
            val className = ksClass.toJavaPoetClassName()

            var dbTypeName: TypeName? = null
            var modelTypeName: TypeName? = null

            for (superType in ksClass.getAllSuperTypes()) {
                val qName = superType.declaration.qualifiedName?.asString() ?: continue
                if (qName == TYPE_CONVERTER_QNAME) {
                    val args = superType.arguments
                    if (args.size >= 2) {
                        // Box primitives — Java generics cannot use primitive types as type arguments
                        dbTypeName = args[0].type?.resolve()?.toJavaPoetTypeName()?.box()
                        modelTypeName = args[1].type?.resolve()?.toJavaPoetTypeName()?.box()
                    }
                    break
                }
            }

            if (dbTypeName == null || modelTypeName == null) return null

            val definition = TypeConverterDefinition(className, KSP_SENTINEL_TYPE_MIRROR, manager)
            definition.dbTypeName = dbTypeName
            definition.modelTypeName = modelTypeName

            ksClass.findKspAnnotation<TypeConverter>()?.let { annot ->
                val subTypes = annot.getArrayArgument<KSType>("allowedSubtypes")
                definition.allowedSubTypes = subTypes?.map { it.toJavaPoetTypeName() } ?: emptyList()
            }

            return definition
        }
    }

}
