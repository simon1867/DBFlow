package com.raizlabs.android.dbflow.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.TypeSpec
import javax.annotation.processing.Messager
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

class KspProcessorManager(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : ProcessorManager(processingEnvironment = null) {

    override val messager: Messager = KspMessager(logger)
    override val typeUtils: Types = NoOpTypes()
    override val elements: Elements = NoOpElements()

    override fun writeJavaFile(packageName: String, typeSpec: TypeSpec) {
        try {
            val javaFile = JavaFile.builder(packageName, typeSpec).build()
            val fileName = typeSpec.name ?: return
            codeGenerator.createNewFile(
                dependencies = Dependencies.ALL_FILES,
                packageName = packageName,
                fileName = fileName,
                extensionName = "java"
            ).bufferedWriter().use { writer ->
                javaFile.writeTo(writer)
            }
        } catch (e: Exception) {
            logger.warn("KSP: failed to write ${typeSpec.name}: ${e.message}")
        }
    }
}
