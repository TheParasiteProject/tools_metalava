/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.metalava.model.psi

import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.ParameterItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.VisibilityLevel
import com.android.tools.metalava.model.findAnnotation
import com.android.tools.metalava.model.hasAnnotation
import com.android.tools.metalava.model.psi.CodePrinter.Companion.constantToSource
import com.intellij.psi.LambdaUtil
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiEllipsisType
import com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KtFunctionLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtParameterSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.KtTypeNullability
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.hasActualModifier
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.UastFacade

class PsiParameterItem
internal constructor(
    override val codebase: PsiBasedCodebase,
    private val psiParameter: PsiParameter,
    private val name: String,
    override val parameterIndex: Int,
    modifiers: PsiModifierItem,
    documentation: String,
    private val type: PsiTypeItem
) :
    PsiItem(
        codebase = codebase,
        modifiers = modifiers,
        documentation = documentation,
        element = psiParameter
    ),
    ParameterItem {
    lateinit var containingMethod: PsiMethodItem

    override var property: PsiPropertyItem? = null

    override fun name(): String = name

    override fun publicName(): String? {
        if (isKotlin(psiParameter)) {
            // Omit names of some special parameters in Kotlin. None of these parameters may be
            // set through Kotlin keyword arguments, so there's no need to track their names for
            // compatibility. This also helps avoid signature file churn if PSI or the compiler
            // change what name they're using for these parameters.

            // Receiver parameter of extension function
            if (isReceiver()) {
                return null
            }
            // Property setter parameter
            if (containingMethod.isKotlinProperty()) {
                return null
            }
            // Continuation parameter of suspend function
            if (
                containingMethod.modifiers.isSuspend() &&
                    "kotlin.coroutines.Continuation" == type.asClass()?.qualifiedName() &&
                    containingMethod.parameters().size - 1 == parameterIndex
            ) {
                return null
            }
            return name
        } else {
            // Java: Look for @ParameterName annotation
            val annotation = modifiers.findAnnotation(AnnotationItem::isParameterName)
            if (annotation != null) {
                return annotation.attributes.firstOrNull()?.value?.value()?.toString()
            }

            // Parameter names from classpath jars are not present as annotations
            if (isFromClassPath()) {
                return name()
            }
        }

        return null
    }

    override fun hasDefaultValue(): Boolean = isDefaultValueKnown()

    override fun isDefaultValueKnown(): Boolean {
        return if (isKotlin(psiParameter)) {
            defaultValue() != INVALID_VALUE
        } else {
            // Java: Look for @ParameterName annotation
            modifiers.hasAnnotation(AnnotationItem::isDefaultValue)
        }
    }

    // Note receiver parameter used to be named $receiver in previous UAST versions, now it is
    // $this$functionName
    private fun isReceiver(): Boolean = parameterIndex == 0 && name.startsWith("\$this\$")

    private fun getKtParameterSymbol(functionSymbol: KtFunctionLikeSymbol): KtParameterSymbol? {
        if (isReceiver()) {
            return functionSymbol.receiverParameter
        }

        // Perform matching based on parameter names, because indices won't work in the
        // presence of @JvmOverloads where UAST generates multiple permutations of the
        // method from the same KtParameters array.
        val parameters = functionSymbol.valueParameters

        val index = if (functionSymbol.isExtension) parameterIndex - 1 else parameterIndex
        val isSuspend = functionSymbol is KtFunctionSymbol && functionSymbol.isSuspend
        if (isSuspend && index >= parameters.size) {
            // suspend functions have continuation as a last parameter, which is not
            // defined in the symbol
            return null
        }

        // Quick lookup first which usually works
        if (index >= 0) {
            val parameter = parameters[index]
            if (parameter.name.asString() == name) {
                return parameter
            }
        }

        for (parameter in parameters) {
            if (parameter.name.asString() == name) {
                return parameter
            }
        }

        // Fallback to handle scenario where the real parameter names are hidden by
        // UAST (see UastKotlinPsiParameter which replaces parameter names to p$index)
        if (index >= 0) {
            val parameter = parameters[index]
            if (!isReceiver()) {
                return parameter
            }
        }

        return null
    }

    override val synthetic: Boolean
        get() = containingMethod.isEnumSyntheticMethod()

    private var defaultValue: String? = null

    override fun defaultValue(): String? {
        if (defaultValue == null) {
            defaultValue = computeDefaultValue()
        }
        return defaultValue
    }

    private fun computeDefaultValue(): String? {
        if (isKotlin(psiParameter)) {
            val ktFunction =
                ((containingMethod.psiMethod as? UMethod)?.sourcePsi as? KtFunction)
                    ?: return INVALID_VALUE

            analyze(ktFunction) {
                val function =
                    if (ktFunction.hasActualModifier()) {
                        ktFunction.getSymbol().getExpectForActual()
                    } else {
                        ktFunction.getSymbol()
                    }
                if (function !is KtFunctionLikeSymbol) return INVALID_VALUE
                val symbol = getKtParameterSymbol(function) ?: return INVALID_VALUE
                if (symbol is KtValueParameterSymbol && symbol.hasDefaultValue) {
                    val defaultValue =
                        (symbol.psi as? KtParameter)?.defaultValue ?: return INVALID_VALUE
                    if (defaultValue is KtConstantExpression) {
                        return defaultValue.text
                    }

                    val defaultExpression =
                        UastFacade.convertElement(defaultValue, null, UExpression::class.java)
                            as? UExpression
                            ?: return INVALID_VALUE
                    val constant = defaultExpression.evaluate()
                    return if (constant != null && constant !is Pair<*, *>) {
                        constantToSource(constant)
                    } else {
                        // Expression: Compute from UAST rather than just using the source text
                        // such that we can ensure references are fully qualified etc.
                        codebase.printer.toSourceString(defaultExpression)
                    }
                }
            }

            return INVALID_VALUE
        } else {
            // Java: Look for @ParameterName annotation
            val annotation = modifiers.findAnnotation(AnnotationItem::isDefaultValue)
            if (annotation != null) {
                return annotation.attributes.firstOrNull()?.value?.value()?.toString()
            }
        }

        return null
    }

    override fun type(): TypeItem = type

    override fun containingMethod(): MethodItem = containingMethod

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        return other is ParameterItem &&
            parameterIndex == other.parameterIndex &&
            containingMethod == other.containingMethod()
    }

    override fun hashCode(): Int {
        return parameterIndex
    }

    override fun toString(): String = "parameter ${name()}"

    override fun isVarArgs(): Boolean {
        return psiParameter.isVarArgs || modifiers.isVarArg()
    }

    /**
     * Returns whether this parameter is SAM convertible or a Kotlin lambda. If this parameter is
     * the last parameter, it also means that it could be called in Kotlin using the trailing lambda
     * syntax.
     *
     * Specifically this will attempt to handle the follow cases:
     * - Java SAM interface = true
     * - Kotlin SAM interface = false // Kotlin (non-fun) interfaces are not SAM convertible
     * - Kotlin fun interface = true
     * - Kotlin lambda = true
     * - Any other type = false
     */
    fun isSamCompatibleOrKotlinLambda(): Boolean {
        // Method is defined in Java source
        if (isJava()) {
            // Check the parameter type to see if it is defined in Kotlin or not.
            // Interfaces defined in Kotlin do not support SAM conversion, but `fun` interfaces do.
            // This is a best-effort check, since external dependencies (bytecode) won't appear to
            // be Kotlin, and won't have a `fun` modifier visible. To resolve this, we could parse
            // the kotlin.metadata annotation on the bytecode declaration (and special case
            // kotlin.jvm.functions.Function* since the actual Kotlin lambda type can always be used
            // with trailing lambda syntax), but in reality the amount of Java methods with a Kotlin
            // interface with a single abstract method from an external dependency should be
            // minimal, so just checking source will make this easier to maintain in the future.
            val cls = type.asClass()
            if (cls != null && cls.isKotlin()) {
                return cls.isInterface() && cls.modifiers.isFunctional()
            }
            // Note: this will return `true` if the interface is defined in Kotlin, hence why we
            // need the prior check as well
            return LambdaUtil.isFunctionalType(type.psiType)
            // Method is defined in Kotlin source
        } else {
            // For Kotlin declarations we can re-use the existing utilities for calculating whether
            // a type is SAM convertible or not, which should handle external dependencies better
            // and avoid any divergence from the actual compiler behaviour, if there are changes.
            val parameter = (psi() as? UParameter)?.sourcePsi as? KtParameter ?: return false
            analyze(parameter) {
                val ktType = parameter.getParameterSymbol().returnType
                val isSamType = ktType.isFunctionalInterfaceType
                val isFunctionalType =
                    ktType.isFunctionType ||
                        ktType.isSuspendFunctionType ||
                        ktType.isKFunctionType ||
                        ktType.isKSuspendFunctionType
                return isSamType || isFunctionalType
            }
        }
    }

    companion object {
        fun create(
            codebase: PsiBasedCodebase,
            psiParameter: PsiParameter,
            parameterIndex: Int
        ): PsiParameterItem {
            val name = psiParameter.name
            val commentText = "" // no javadocs on individual parameters
            val modifiers = createParameterModifiers(codebase, psiParameter, commentText)
            val psiType = psiParameter.type
            // UAST workaround: nullity of element type in last `vararg` parameter's array type
            val workaroundPsiType =
                if (
                    psiParameter is UParameter &&
                        psiParameter.sourcePsi is KtParameter &&
                        psiParameter.isVarArgs && // last `vararg`
                        psiType is PsiArrayType
                ) {
                    val ktParameter = psiParameter.sourcePsi as KtParameter
                    val annotationProvider =
                        when (codebase.uastResolveService?.nullability(ktParameter)) {
                            KtTypeNullability.NON_NULLABLE ->
                                codebase.getNonNullAnnotationProvider()
                            KtTypeNullability.NULLABLE -> codebase.getNullableAnnotationProvider()
                            else -> null
                        }
                    val annotatedType =
                        if (annotationProvider != null) {
                            psiType.componentType.annotate(annotationProvider)
                        } else {
                            psiType.componentType
                        }
                    PsiEllipsisType(annotatedType, annotatedType.annotationProvider)
                } else {
                    psiType
                }
            val type = codebase.getType(workaroundPsiType)
            val parameter =
                PsiParameterItem(
                    codebase = codebase,
                    psiParameter = psiParameter,
                    name = name,
                    parameterIndex = parameterIndex,
                    documentation = commentText,
                    modifiers = modifiers,
                    type = type
                )
            parameter.modifiers.setOwner(parameter)
            return parameter
        }

        fun create(codebase: PsiBasedCodebase, original: PsiParameterItem): PsiParameterItem {
            val parameter =
                PsiParameterItem(
                    codebase = codebase,
                    psiParameter = original.psiParameter,
                    name = original.name,
                    parameterIndex = original.parameterIndex,
                    documentation = original.documentation,
                    modifiers = PsiModifierItem.create(codebase, original.modifiers),
                    type = PsiTypeItem.create(codebase, original.type)
                )
            parameter.modifiers.setOwner(parameter)
            return parameter
        }

        fun create(
            codebase: PsiBasedCodebase,
            original: List<ParameterItem>
        ): List<PsiParameterItem> {
            return original.map { create(codebase, it as PsiParameterItem) }
        }

        private fun createParameterModifiers(
            codebase: PsiBasedCodebase,
            psiParameter: PsiParameter,
            commentText: String
        ): PsiModifierItem {
            val modifiers = PsiModifierItem.create(codebase, psiParameter, commentText)
            // Method parameters don't have a visibility level; they are visible to anyone that can
            // call their method. However, Kotlin constructors sometimes appear to specify the
            // visibility of a constructor parameter by putting visibility inside the constructor
            // signature. This is really to indicate that the matching property should have the
            // mentioned visibility.
            // If the method parameter seems to specify a visibility level, we correct it back to
            // the default, here, to ensure we don't attempt to incorrectly emit this information
            // into a signature file.
            modifiers.setVisibilityLevel(VisibilityLevel.PACKAGE_PRIVATE)
            return modifiers
        }

        /**
         * Private marker return value from [#computeDefaultValue] signifying that the parameter has
         * a default value but we were unable to compute a suitable static string representation for
         * it
         */
        private const val INVALID_VALUE = "__invalid_value__"
    }
}
