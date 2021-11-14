package org.koin.compiler.scanner

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.*
import org.koin.compiler.metadata.*
import java.util.*

class ModuleScanner(
    val logger: KSPLogger
) {

    fun createClassModule(element: KSAnnotated): ModuleIndex {
        val declaration = (element as KSClassDeclaration)
        logger.logging("module(Class) -> $element", element)
        val modulePackage = declaration.containingFile?.packageName?.asString() ?: ""
        logger.logging("module(Class) -> package: $modulePackage", element)

        val componentScan =
            getComponentScan(declaration)
        logger.logging("module(Class) componentScan=$componentScan", element)

        val name = "$element"
        val moduleMetadata = KoinMetaData.Module(
            packageName = modulePackage,
            name = name,
            type = KoinMetaData.ModuleType.CLASS,
            componentScan = componentScan
        )

        val annotatedFunctions = declaration.getAllFunctions()
            .filter {
                it.annotations.map { a -> a.shortName.asString() }.any { a -> isValidAnnotation(a) }
            }
            .toList()

        logger.logging("module(Class) -> $element | found class functions: ${annotatedFunctions.size}", element)
        val definitions = annotatedFunctions.mapNotNull { addDefinition(it) }
        moduleMetadata.definitions += definitions

        val moduleIndex = ModuleIndex(if (componentScan?.packageName?.isNotEmpty() == true) componentScan.packageName else modulePackage, moduleMetadata)
        logger.logging("module(Class) index -> ${moduleIndex.first}")
        return moduleIndex
    }

    private fun getComponentScan(declaration: KSClassDeclaration): KoinMetaData.Module.ComponentScan? {
        val componentScan = declaration.annotations.firstOrNull { it.shortName.asString() == "ComponentScan" }
        return componentScan?.let { a ->
            val value : String = a.arguments.firstOrNull { arg -> arg.name?.asString() == "value" }?.value as? String? ?: ""
            KoinMetaData.Module.ComponentScan(value)
        }
    }

    private fun addDefinition(element: KSAnnotated): KoinMetaData.Definition? {
        logger.logging("definition(function) -> $element", element)

        val ksFunctionDeclaration = (element as KSFunctionDeclaration)
        val packageName = ksFunctionDeclaration.containingFile!!.packageName.asString()
        val returnedType = ksFunctionDeclaration.returnType?.resolve()?.declaration?.simpleName?.toString()
        val qualifier = ksFunctionDeclaration.getStringQualifier()

        return returnedType?.let {
            val functionName = ksFunctionDeclaration.simpleName.asString()

            val annotations = element.getKoinAnnotations()
            val scopeAnnotation = annotations.getScopeAnnotation()

            return if (scopeAnnotation != null){
                declareDefinition(scopeAnnotation.first, scopeAnnotation.second, packageName, qualifier, functionName, ksFunctionDeclaration, annotations)
            } else {
                annotations.firstNotNullOf { (annotationName, annotation) ->
                    declareDefinition(annotationName, annotation, packageName, qualifier, functionName, ksFunctionDeclaration, annotations)
                }
            }
        }
    }

    private fun declareDefinition(
        annotationName: String,
        annotation: KSAnnotation,
        packageName: String,
        qualifier: String?,
        functionName: String,
        ksFunctionDeclaration: KSFunctionDeclaration,
        annotations: Map<String, KSAnnotation> = emptyMap()
    ): KoinMetaData.Definition.FunctionDefinition? {
        logger.logging("definition(function) -> kind $annotationName", annotation)
        logger.logging("definition(function) -> kind ${annotation.arguments}", annotation)

        val allBindings = declaredBindings(annotation) ?: emptyList()
        logger.logging("definition(function) -> binds=$allBindings", annotation)

        val functionParameters = ksFunctionDeclaration.parameters.getConstructorParameters()
        logger.logging("definition(function) ctor -> $functionParameters", annotation)
        return when (annotationName) {
            SINGLE.annotationName -> {
                val createdAtStart: Boolean =
                    annotation.arguments.firstOrNull { it.name?.asString() == "createdAtStart" }?.value as Boolean?
                        ?: false
                logger.logging("definition(function) -> createdAtStart=$createdAtStart", annotation)
                createFunctionDefinition(SINGLE,packageName,qualifier,functionName,functionParameters,allBindings, isCreatedAtStart = createdAtStart)
            }
            FACTORY.annotationName -> {
                createFunctionDefinition(FACTORY,packageName,qualifier,functionName,functionParameters,allBindings)
            }
            KOIN_VIEWMODEL.annotationName -> {
                createFunctionDefinition(KOIN_VIEWMODEL,packageName,qualifier,functionName,functionParameters,allBindings)
            }
            SCOPE.annotationName -> {
                val scopeData : KoinMetaData.Scope = annotation.arguments.getScope()
                logger.logging("definition(function) -> scope $scopeData", annotation)
                val extraAnnotation = getExtraScopeAnnotation(annotations)
                logger.logging("definition(function) -> extra scope annotation $extraAnnotation", annotation)
                createFunctionDefinition(extraAnnotation ?: SCOPE,packageName,qualifier,functionName,functionParameters,allBindings,scope = scopeData)
            }
            else -> null
        }
    }

    private fun createFunctionDefinition(
        keyword : DefinitionAnnotation,
        packageName: String,
        qualifier: String?,
        functionName: String,
        parameters: List<KoinMetaData.ConstructorParameter>?,
        allBindings: List<KSDeclaration>,
        isCreatedAtStart : Boolean? = null,
        scope: KoinMetaData.Scope? = null,
    ) = KoinMetaData.Definition.FunctionDefinition(
        packageName = packageName,
        qualifier = qualifier,
        isCreatedAtStart = isCreatedAtStart,
        functionName = functionName,
        parameters = parameters ?: emptyList(),
        bindings = allBindings,
        keyword = keyword,
        scope = scope
    )
}