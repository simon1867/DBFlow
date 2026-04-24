package com.raizlabs.android.dbflow.processor.definition

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.raizlabs.android.dbflow.annotation.provider.ContentUri
import com.raizlabs.android.dbflow.annotation.provider.Notify
import com.raizlabs.android.dbflow.annotation.provider.TableEndpoint
import com.raizlabs.android.dbflow.processor.ProcessorManager
import com.raizlabs.android.dbflow.processor.utils.annotation
import com.raizlabs.android.dbflow.processor.utils.findKspAnnotation
import com.raizlabs.android.dbflow.processor.utils.getKsTypeArgument
import com.raizlabs.android.dbflow.processor.utils.getStringArgument
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetClassName
import com.raizlabs.android.dbflow.processor.utils.toJavaPoetTypeName
import com.squareup.javapoet.TypeName
import javax.lang.model.element.Element
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypeException

/**
 * Description:
 */
class TableEndpointDefinition(typeElement: Element, processorManager: ProcessorManager)
    : BaseDefinition(typeElement, processorManager) {

    var contentUriDefinitions: MutableList<ContentUriDefinition> = mutableListOf()

    /**
     * Dont want duplicate paths.
     */
    internal var pathValidationMap: Map<String, ContentUriDefinition> = mutableMapOf()

    var notifyDefinitionPathMap: MutableMap<String, MutableMap<Notify.Method, MutableList<NotifyDefinition>>>
            = mutableMapOf()

    var tableName: String? = null

    var contentProviderName: TypeName? = null

    var isTopLevel = false

    init {

        typeElement.annotation<TableEndpoint>()?.let { endpoint ->
            tableName = endpoint.name

            try {
                endpoint.contentProvider
            } catch (mte: MirroredTypeException) {
                contentProviderName = TypeName.get(mte.typeMirror)
            }
        }

        isTopLevel = typeElement.enclosingElement is PackageElement

        val typeEl = typeElement as? TypeElement
        val elements = if (typeEl != null) processorManager.elements.getAllMembers(typeEl) else emptyList()
        for (innerElement in elements) {
            if (innerElement.annotation<ContentUri>() != null) {
                val contentUriDefinition = ContentUriDefinition(innerElement, processorManager)
                if (!pathValidationMap.containsKey(contentUriDefinition.path)) {
                    contentUriDefinitions.add(contentUriDefinition)
                } else {
                    processorManager.logError("There must be unique paths for the specified @ContentUri" + " %1s from %1s", contentUriDefinition.name, contentProviderName)
                }
            } else if (innerElement.annotation<Notify>() != null) {
                val notifyDefinition = NotifyDefinition(innerElement, processorManager)

                for (path in notifyDefinition.paths) {
                    var methodListMap = notifyDefinitionPathMap[path]
                    if (methodListMap == null) {
                        methodListMap = mutableMapOf()
                        notifyDefinitionPathMap.put(path, methodListMap)
                    }

                    var notifyDefinitionList: MutableList<NotifyDefinition>? = methodListMap[notifyDefinition.method]
                    if (notifyDefinitionList == null) {
                        notifyDefinitionList = arrayListOf()
                        methodListMap.put(notifyDefinition.method, notifyDefinitionList)
                    }
                    notifyDefinitionList.add(notifyDefinition)
                }
            }
        }

    }

    fun kspInit(ksClass: KSClassDeclaration) {
        elementName = ksClass.simpleName.asString()
        packageName = ksClass.packageName.asString()
        elementClassName = ksClass.toJavaPoetClassName()
        elementTypeName = elementClassName

        val endpointAnnot = ksClass.findKspAnnotation<TableEndpoint>() ?: return
        tableName = endpointAnnot.getStringArgument("name") ?: ""
        contentProviderName = endpointAnnot.getKsTypeArgument("contentProvider")?.toJavaPoetTypeName()

        isTopLevel = ksClass.parentDeclaration == null

        fun processDecl(decl: KSDeclaration) {
            when {
                decl is KSPropertyDeclaration && decl.findKspAnnotation<ContentUri>() != null -> {
                    val contentUriDef = ContentUriDefinition.fromKsp(decl, manager)
                    if (!pathValidationMap.containsKey(contentUriDef.path)) {
                        contentUriDefinitions.add(contentUriDef)
                    } else {
                        manager.logError("Duplicate @ContentUri path %1s in %1s", contentUriDef.path, elementName)
                    }
                }
                decl is KSFunctionDeclaration && decl.findKspAnnotation<ContentUri>() != null -> {
                    val contentUriDef = ContentUriDefinition.fromKsp(decl, manager)
                    if (!pathValidationMap.containsKey(contentUriDef.path)) {
                        contentUriDefinitions.add(contentUriDef)
                    } else {
                        manager.logError("Duplicate @ContentUri path %1s in %1s", contentUriDef.path, elementName)
                    }
                }
                decl is KSFunctionDeclaration && decl.findKspAnnotation<Notify>() != null -> {
                    val notifyDef = NotifyDefinition.fromKsp(decl, manager)
                    for (path in notifyDef.paths) {
                        val methodListMap = notifyDefinitionPathMap.getOrPut(path) { mutableMapOf() }
                        val list = methodListMap.getOrPut(notifyDef.method) { mutableListOf() }
                        list.add(notifyDef)
                    }
                }
            }
        }

        for (decl in ksClass.declarations) {
            processDecl(decl)
            // Also traverse companion objects for @ContentUri / @Notify members
            if (decl is KSClassDeclaration && decl.isCompanionObject) {
                for (innerDecl in decl.declarations) {
                    processDecl(innerDecl)
                }
            }
        }
    }
}
