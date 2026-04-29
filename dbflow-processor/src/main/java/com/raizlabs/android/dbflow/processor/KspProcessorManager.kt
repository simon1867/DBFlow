package com.raizlabs.android.dbflow.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.TypeSpec
import javax.annotation.processing.Messager
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

class KspProcessorManager(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    options: Map<String, String> = emptyMap(),
) : ProcessorManager(processingEnvironment = null) {

    override val messager: Messager = KspMessager(logger)
    override val typeUtils: Types = NoOpTypes()
    override val elements: Elements = NoOpElements()
    override val options: Map<String, String> = options

    /** Aggregating overload — used for outputs that depend on every source (e.g. the database holder). */
    override fun writeJavaFile(packageName: String, typeSpec: TypeSpec) {
        writeFile(packageName, typeSpec, Dependencies.ALL_FILES)
    }

    /**
     * Isolating overload — when an [originatingFile] is provided, declare the output as
     * depending only on that single source. KSP can then skip regeneration when unrelated files
     * change. Falls back to aggregating semantics if [originatingFile] is null.
     */
    override fun writeJavaFile(packageName: String, typeSpec: TypeSpec, originatingFile: KSFile?) {
        // We deliberately ignore [originatingFile] and always declare aggregating dependencies.
        //
        // The natural optimisation would be `Dependencies(false, originatingFile)` for per-class
        // outputs (e.g. `_Table.java` files), letting KSP skip regeneration when unrelated
        // sources change. But our processor uses a deferred-holder multi-round strategy:
        // round 1 writes per-class files, round 2 writes the aggregating database holder.
        // Once we declare an isolating dep on a round-1 KSFile, KSP2 retains a tracking entry
        // that holds the file's lifetime token. That token is invalidated when round 2 starts,
        // and any subsequent aggregating write (including the holder) then fails with
        // "PSI has changed since creation", leaving 0-byte files behind that break javac.
        //
        // Until KSP2 handles this gracefully, fall back to aggregating dependencies for every
        // write. The perf regression vs. isolating outputs is the cost of correctness here.
        writeFile(packageName, typeSpec, Dependencies.ALL_FILES)
    }

    private fun writeFile(packageName: String, typeSpec: TypeSpec, deps: Dependencies) {
        try {
            val javaFile = JavaFile.builder(packageName, typeSpec).build()
            val fileName = typeSpec.name ?: return
            codeGenerator.createNewFile(
                dependencies = deps,
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
