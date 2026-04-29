package com.raizlabs.android.dbflow.processor.utils

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Variance
import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeVariableName
import com.squareup.javapoet.WildcardTypeName

fun KSClassDeclaration.toJavaPoetClassName(): ClassName {
    val pkg = packageName.asString()
    val names = mutableListOf<String>()
    var decl: KSDeclaration? = this
    while (decl is KSClassDeclaration) {
        names.add(0, decl.simpleName.asString())
        decl = decl.parentDeclaration
    }
    return if (names.size == 1) {
        ClassName.get(pkg, names[0])
    } else {
        ClassName.get(pkg, names[0], *names.drop(1).toTypedArray())
    }
}

private val KOTLIN_PRIMITIVE_MAP: Map<String, TypeName> = mapOf(
    "kotlin.Int" to TypeName.INT,
    "kotlin.Long" to TypeName.LONG,
    "kotlin.Double" to TypeName.DOUBLE,
    "kotlin.Float" to TypeName.FLOAT,
    "kotlin.Boolean" to TypeName.BOOLEAN,
    "kotlin.Byte" to TypeName.BYTE,
    "kotlin.Short" to TypeName.SHORT,
    "kotlin.Char" to TypeName.CHAR
)

private val KOTLIN_CLASS_MAP: Map<String, ClassName> = mapOf(
    "kotlin.String" to ClassName.get("java.lang", "String"),
    "kotlin.Any" to ClassName.get("java.lang", "Object"),
    "kotlin.Unit" to ClassName.get("java.lang", "Void")
)

// Kotlin array types map to Java array types (not representable as ClassName)
private val KOTLIN_ARRAY_TYPE_MAP: Map<String, TypeName> = mapOf(
    "kotlin.ByteArray" to ArrayTypeName.of(TypeName.BYTE),
    "kotlin.IntArray" to ArrayTypeName.of(TypeName.INT),
    "kotlin.LongArray" to ArrayTypeName.of(TypeName.LONG),
    "kotlin.ShortArray" to ArrayTypeName.of(TypeName.SHORT),
    "kotlin.FloatArray" to ArrayTypeName.of(TypeName.FLOAT),
    "kotlin.DoubleArray" to ArrayTypeName.of(TypeName.DOUBLE),
    "kotlin.BooleanArray" to ArrayTypeName.of(TypeName.BOOLEAN),
    "kotlin.CharArray" to ArrayTypeName.of(TypeName.CHAR)
)

fun KSType.toJavaPoetTypeName(): TypeName {
    if (isError) return TypeName.OBJECT
    return when (val declaration = this.declaration) {
        is KSTypeParameter -> TypeVariableName.get(declaration.name.asString())
        is KSClassDeclaration -> {
            val qualifiedName = declaration.qualifiedName?.asString()
            // Map Kotlin primitives to Java primitives/wrappers
            KOTLIN_PRIMITIVE_MAP[qualifiedName]?.let { primitive ->
                // PLATFORM nullability means a Java type without a nullability annotation — treat
                // as boxed (nullable) to avoid NPE on auto-unboxing in generated bind* calls.
                return if (isMarkedNullable || nullability == Nullability.PLATFORM) primitive.box() else primitive
            }
            KOTLIN_ARRAY_TYPE_MAP[qualifiedName]?.let { arrayType -> return arrayType }
            KOTLIN_CLASS_MAP[qualifiedName]?.let { classType -> return classType }

            val className = declaration.toJavaPoetClassName()
            val typeArgs = arguments
            if (typeArgs.isEmpty()) {
                className
            } else {
                val resolvedArgs = typeArgs.map { arg ->
                    when (arg.variance) {
                        Variance.STAR -> WildcardTypeName.subtypeOf(TypeName.OBJECT)
                        Variance.CONTRAVARIANT -> WildcardTypeName.supertypeOf(
                            arg.type?.resolve()?.toJavaPoetTypeName() ?: TypeName.OBJECT
                        )
                        else -> arg.type?.resolve()?.toJavaPoetTypeName() ?: TypeName.OBJECT
                    }
                }
                ParameterizedTypeName.get(className, *resolvedArgs.toTypedArray())
            }
        }
        else -> TypeName.OBJECT
    }
}

fun KSAnnotated.findAnnotationByName(qualifiedName: String): KSAnnotation? =
    annotations.find {
        val resolved = it.annotationType.resolve()
        !resolved.isError && resolved.declaration.qualifiedName?.asString() == qualifiedName
    }

inline fun <reified T : Annotation> KSAnnotated.findKspAnnotation(): KSAnnotation? =
    findAnnotationByName(T::class.qualifiedName ?: "")

fun KSAnnotation.getKsTypeArgument(name: String): KSType? =
    arguments.find { it.name?.asString() == name }?.value as? KSType

fun KSAnnotation.getStringArgument(name: String): String? =
    (arguments.find { it.name?.asString() == name }?.value as? String)

fun KSAnnotation.getIntArgument(name: String): Int? =
    arguments.find { it.name?.asString() == name }?.value as? Int

fun KSAnnotation.getBooleanArgument(name: String): Boolean? =
    arguments.find { it.name?.asString() == name }?.value as? Boolean

@Suppress("UNCHECKED_CAST")
fun <T : Any> KSAnnotation.getArrayArgument(name: String): List<T>? =
    (arguments.find { it.name?.asString() == name }?.value as? List<*>)?.filterNotNull() as? List<T>

fun KSClassDeclaration.isEnum(): Boolean = classKind == ClassKind.ENUM_CLASS

/** Returns the simple name string for an enum annotation argument.
 *
 * KSP1 typically delivers the value as a [KSType] (the enum entry's type) or a [String];
 * KSP2 delivers a [KSClassDeclaration] for the enum entry directly. Handle all three.
 */
fun KSAnnotation.getEnumArgument(name: String): String? {
    val value = arguments.find { it.name?.asString() == name }?.value
    return when (value) {
        is KSClassDeclaration -> value.simpleName.asString()
        is KSType -> value.declaration.simpleName.asString()
        is String -> value
        else -> null
    }
}
