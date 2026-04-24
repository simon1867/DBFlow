package com.raizlabs.android.dbflow.processor.utils

import com.raizlabs.android.dbflow.processor.ProcessorManager
import com.raizlabs.android.dbflow.processor.definition.BaseDefinition
import java.io.IOException

/**
 * Description: Provides some handy writing methods.
 */
object WriterUtils {

    fun writeBaseDefinition(baseDefinition: BaseDefinition, processorManager: ProcessorManager): Boolean {
        return try {
            processorManager.writeJavaFile(baseDefinition.packageName, baseDefinition.typeSpec)
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
