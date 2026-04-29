package com.raizlabs.android.dbflow.processor.utils

import com.raizlabs.android.dbflow.processor.ProcessorManager
import com.raizlabs.android.dbflow.processor.definition.BaseDefinition
import java.io.IOException

/**
 * Description: Provides some handy writing methods.
 */
object WriterUtils {

    fun writeBaseDefinition(baseDefinition: BaseDefinition, processorManager: ProcessorManager): Boolean {
        if (baseDefinition.fileWritten) return true
        return try {
            // Per-class definitions pass their originating KSFile so KSP can mark the output
            // isolating; the KAPT path ignores the extra parameter.
            processorManager.writeJavaFile(baseDefinition.packageName, baseDefinition.typeSpec, baseDefinition.originatingFile)
            baseDefinition.fileWritten = true
            true
        } catch (e: IOException) {
            false
        } catch (i: IllegalStateException) {
            processorManager.logError(WriterUtils::class, "Found error for class:" + baseDefinition.elementName)
            processorManager.logError(WriterUtils::class, i.message)
            false
        }
    }

}
