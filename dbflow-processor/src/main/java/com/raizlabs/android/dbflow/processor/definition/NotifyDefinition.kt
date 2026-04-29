package com.raizlabs.android.dbflow.processor.definition

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.raizlabs.android.dbflow.annotation.provider.Notify
import com.raizlabs.android.dbflow.processor.ClassNames
import com.raizlabs.android.dbflow.processor.KSP_SENTINEL_ELEMENT
import com.raizlabs.android.dbflow.processor.ProcessorManager
import com.raizlabs.android.dbflow.processor.utils.annotation
import com.raizlabs.android.dbflow.processor.utils.findKspAnnotation
import com.raizlabs.android.dbflow.processor.utils.getArrayArgument
import com.raizlabs.android.dbflow.processor.utils.getEnumArgument
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetTypeName
import com.squareup.javapoet.ClassName
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement

/**
 * Description:
 */
class NotifyDefinition(typeElement: Element, processorManager: ProcessorManager)
    : BaseDefinition(typeElement, processorManager) {

    var paths = arrayOf<String>()
    var method = Notify.Method.DELETE
    var parent: String = (typeElement.enclosingElement as? TypeElement)?.qualifiedName?.toString() ?: ""
    var methodName: String = typeElement.simpleName.toString()
    var params: String = ""
    var returnsArray: Boolean = false
    var returnsSingle: Boolean = false

    init {
        if (typeElement !== KSP_SENTINEL_ELEMENT) {
            typeElement.annotation<Notify>()?.let { notify ->
                paths = notify.paths
                method = notify.method
            }

            val executableElement = typeElement as? ExecutableElement
            if (executableElement != null) {
                val parameters = executableElement.parameters
                val paramsBuilder = StringBuilder()
                var first = true
                parameters.forEach { param ->
                    if (first) {
                        first = false
                    } else {
                        paramsBuilder.append(", ")
                    }
                    val paramType = param.asType()
                    val typeAsString = paramType.toString()
                    paramsBuilder.append(
                            if ("android.content.Context" == typeAsString) {
                                "getContext()"
                            } else if ("android.net.Uri" == typeAsString) {
                                "uri"
                            } else if ("android.content.ContentValues" == typeAsString) {
                                "values"
                            } else if ("long" == typeAsString) {
                                "id"
                            } else if ("java.lang.String" == typeAsString) {
                                "where"
                            } else if ("java.lang.String[]" == typeAsString) {
                                "whereArgs"
                            } else {
                                ""
                            })
                }

                params = paramsBuilder.toString()

                val typeMirror = executableElement.returnType
                if (ClassNames.URI.toString() + "[]" == typeMirror.toString()) {
                    returnsArray = true
                } else if (ClassNames.URI.toString() == typeMirror.toString()) {
                    returnsSingle = true
                } else {
                    processorManager.logError("Notify method returns wrong type. It must return Uri or Uri[]")
                }
            }
        }
    }

    override fun getElementClassName(element: Element?): ClassName? {
        return null
    }

    companion object {
        fun fromKsp(fn: KSFunctionDeclaration, processorManager: ProcessorManager): NotifyDefinition {
            val def = NotifyDefinition(KSP_SENTINEL_ELEMENT, processorManager)

            // parent = enclosing class qualified name
            def.parent = (fn.parentDeclaration as? KSClassDeclaration)
                ?.qualifiedName?.asString() ?: ""
            def.methodName = fn.simpleName.asString()

            val notifyAnnot = fn.findKspAnnotation<Notify>()
            if (notifyAnnot != null) {
                def.paths = notifyAnnot.getArrayArgument<String>("paths")?.toTypedArray() ?: arrayOf()
                val methodStr = notifyAnnot.getEnumArgument("method")
                def.method = when (methodStr) {
                    "INSERT" -> Notify.Method.INSERT
                    "UPDATE" -> Notify.Method.UPDATE
                    else -> Notify.Method.DELETE
                }
            }

            // Map KSP parameter types to param strings
            val paramsBuilder = StringBuilder()
            var first = true
            for (param in fn.parameters) {
                if (!first) paramsBuilder.append(", ")
                first = false
                val resolvedType = param.type.resolve()
                if (resolvedType.isError) continue
                val isArray = resolvedType.declaration.qualifiedName?.asString() == "kotlin.Array"
                val elementQName = if (isArray) {
                    val inner = resolvedType.arguments.firstOrNull()?.type?.resolve()
                    if (inner != null && !inner.isError) inner.declaration.qualifiedName?.asString() ?: "" else ""
                } else {
                    resolvedType.declaration.qualifiedName?.asString() ?: ""
                }
                paramsBuilder.append(when (elementQName) {
                    "android.content.Context" -> "getContext()"
                    "android.net.Uri" -> "uri"
                    "android.content.ContentValues" -> "values"
                    "kotlin.Long", "java.lang.Long", "long" -> "id"
                    "kotlin.String", "java.lang.String" -> "where"
                    else -> ""
                })
            }
            def.params = paramsBuilder.toString()

            // Determine return type: Uri[] or Uri
            val returnType = fn.returnType?.resolve()
            val returnDecl = if (returnType != null && !returnType.isError) returnType.declaration.qualifiedName?.asString() ?: "" else ""
            when {
                returnDecl == "kotlin.Array" -> def.returnsArray = true
                returnDecl == "android.net.Uri" -> def.returnsSingle = true
            }

            return def
        }
    }
}
