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

import com.android.tools.metalava.model.ANDROID_DEPRECATED_FOR_SDK
import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.Codebase
import com.android.tools.metalava.model.DefaultModifierList
import com.android.tools.metalava.model.JAVA_LANG_ANNOTATION_TARGET
import com.android.tools.metalava.model.JAVA_LANG_TYPE_USE_TARGET
import com.android.tools.metalava.model.ModifierList
import com.android.tools.metalava.model.MutableModifierList
import com.android.tools.metalava.model.isNullnessAnnotation
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiDocCommentOwner
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.impl.light.LightModifierList
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolWithVisibility
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtModifierList
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.psiUtil.hasFunModifier
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifier
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.kotlin.KotlinUMethodWithFakeLightDelegate

class PsiModifierItem
internal constructor(
    codebase: Codebase,
    flags: Int = PACKAGE_PRIVATE,
    annotations: MutableList<AnnotationItem>? = null
) : DefaultModifierList(codebase, flags, annotations), ModifierList, MutableModifierList {
    companion object {
        fun create(
            codebase: PsiBasedCodebase,
            element: PsiModifierListOwner,
            documentation: String?
        ): PsiModifierItem {
            val modifiers =
                if (element is UAnnotated) {
                    create(codebase, element, element)
                } else {
                    create(codebase, element)
                }
            if (
                documentation?.contains("@deprecated") == true ||
                    // Check for @Deprecated annotation
                    ((element as? PsiDocCommentOwner)?.isDeprecated == true) ||
                    hasDeprecatedAnnotation(modifiers) ||
                    // Check for @Deprecated on sourcePsi
                    isDeprecatedFromSourcePsi(element)
            ) {
                modifiers.setDeprecated(true)
            }

            return modifiers
        }

        private fun hasDeprecatedAnnotation(modifiers: PsiModifierItem) =
            modifiers.annotations?.any {
                it.qualifiedName?.let { qualifiedName ->
                    qualifiedName == "Deprecated" ||
                        qualifiedName.endsWith(".Deprecated") ||
                        // DeprecatedForSdk that do not apply to this API surface have been filtered
                        // out so if any are left then treat it as a standard Deprecated annotation.
                        qualifiedName == ANDROID_DEPRECATED_FOR_SDK
                }
                    ?: false
            } == true

        private fun isDeprecatedFromSourcePsi(element: PsiModifierListOwner): Boolean {
            if (element is UMethod) {
                // NB: we can't use sourcePsi.annotationEntries directly due to
                // annotation use-site targets. The given `javaPsi` as a light element,
                // which spans regular functions, property accessors, etc., is already
                // built with targeted annotations. Even KotlinUMethod is using LC annotations.
                //
                // BUT!
                // ```
                //   return element.javaPsi.isDeprecated
                // ```
                // is redundant, since we already check:
                // ```
                //   (element as? PsiDocCommentOwner)?.isDeprecated == true
                // ```
                return false
            }
            return ((element as? UElement)?.sourcePsi as? KtAnnotated)?.annotationEntries?.any {
                it.shortName?.toString() == "Deprecated"
            }
                ?: false
        }

        private fun computeFlag(element: PsiModifierListOwner, modifierList: PsiModifierList): Int {
            var flags = 0
            if (modifierList.hasModifierProperty(PsiModifier.STATIC)) {
                flags = flags or STATIC
            }
            if (modifierList.hasModifierProperty(PsiModifier.ABSTRACT)) {
                flags = flags or ABSTRACT
            }
            if (modifierList.hasModifierProperty(PsiModifier.FINAL)) {
                flags = flags or FINAL
            }
            if (modifierList.hasModifierProperty(PsiModifier.NATIVE)) {
                flags = flags or NATIVE
            }
            if (modifierList.hasModifierProperty(PsiModifier.SYNCHRONIZED)) {
                flags = flags or SYNCHRONIZED
            }
            if (modifierList.hasModifierProperty(PsiModifier.STRICTFP)) {
                flags = flags or STRICT_FP
            }
            if (modifierList.hasModifierProperty(PsiModifier.TRANSIENT)) {
                flags = flags or TRANSIENT
            }
            if (modifierList.hasModifierProperty(PsiModifier.VOLATILE)) {
                flags = flags or VOLATILE
            }
            if (modifierList.hasModifierProperty(PsiModifier.DEFAULT)) {
                flags = flags or DEFAULT
            }

            // Look for special Kotlin keywords
            var ktModifierList: KtModifierList? = null
            val sourcePsi = (element as? UElement)?.sourcePsi
            when (modifierList) {
                is KtLightElement<*, *> -> {
                    ktModifierList = modifierList.kotlinOrigin as? KtModifierList
                }
                is LightModifierList -> {
                    if (element is UMethod && sourcePsi is KtModifierListOwner) {
                        ktModifierList = sourcePsi.modifierList
                    }
                }
            }
            var visibilityFlags =
                when {
                    modifierList.hasModifierProperty(PsiModifier.PUBLIC) -> PUBLIC
                    modifierList.hasModifierProperty(PsiModifier.PROTECTED) -> PROTECTED
                    modifierList.hasModifierProperty(PsiModifier.PRIVATE) -> PRIVATE
                    ktModifierList != null ->
                        when {
                            ktModifierList.hasModifier(KtTokens.PRIVATE_KEYWORD) -> PRIVATE
                            ktModifierList.hasModifier(KtTokens.PROTECTED_KEYWORD) -> PROTECTED
                            ktModifierList.hasModifier(KtTokens.INTERNAL_KEYWORD) -> INTERNAL
                            else -> PUBLIC
                        }
                    // UAST workaround: fake light method for inline/hidden function may not have a
                    // concrete modifier list, but overrides `hasModifierProperty` to mimic
                    // modifiers.
                    element is KotlinUMethodWithFakeLightDelegate ->
                        when {
                            element.hasModifierProperty(PsiModifier.PUBLIC) -> PUBLIC
                            element.hasModifierProperty(PsiModifier.PROTECTED) -> PROTECTED
                            element.hasModifierProperty(PsiModifier.PRIVATE) -> PRIVATE
                            else -> PUBLIC
                        }
                    else -> PACKAGE_PRIVATE
                }
            if (ktModifierList != null) {
                if (ktModifierList.hasModifier(KtTokens.INTERNAL_KEYWORD)) {
                    // Reset visibilityFlags to INTERNAL if the internal modifier is explicitly
                    // present on the element
                    visibilityFlags = INTERNAL
                } else if (
                    ktModifierList.hasModifier(KtTokens.OVERRIDE_KEYWORD) &&
                        ktModifierList.visibilityModifier() == null &&
                        sourcePsi is KtElement
                ) {
                    // Reset visibilityFlags to INTERNAL if the element has no explicit visibility
                    // modifier, but overrides an internal declaration. Adapted from
                    // org.jetbrains.kotlin.asJava.classes.UltraLightMembersCreator.isInternal
                    analyze(sourcePsi) {
                        val symbol = (sourcePsi as? KtDeclaration)?.getSymbol()
                        val visibility = (symbol as? KtSymbolWithVisibility)?.visibility
                        if (visibility == Visibilities.Internal) {
                            visibilityFlags = INTERNAL
                        }
                    }
                }
                if (ktModifierList.hasModifier(KtTokens.VARARG_KEYWORD)) {
                    flags = flags or VARARG
                }
                if (ktModifierList.hasModifier(KtTokens.SEALED_KEYWORD)) {
                    flags = flags or SEALED
                }
                if (ktModifierList.hasModifier(KtTokens.INFIX_KEYWORD)) {
                    flags = flags or INFIX
                }
                if (ktModifierList.hasModifier(KtTokens.CONST_KEYWORD)) {
                    flags = flags or CONST
                }
                if (ktModifierList.hasModifier(KtTokens.OPERATOR_KEYWORD)) {
                    flags = flags or OPERATOR
                }
                if (ktModifierList.hasModifier(KtTokens.INLINE_KEYWORD)) {
                    flags = flags or INLINE

                    // Workaround for b/117565118:
                    val func = sourcePsi as? KtNamedFunction
                    if (
                        func != null &&
                            (func.typeParameterList?.text ?: "").contains(
                                KtTokens.REIFIED_KEYWORD.value
                            ) &&
                            !ktModifierList.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
                            !ktModifierList.hasModifier(KtTokens.INTERNAL_KEYWORD)
                    ) {
                        // Switch back from private to public
                        visibilityFlags = PUBLIC
                    }
                }
                if (ktModifierList.hasModifier(KtTokens.VALUE_KEYWORD)) {
                    flags = flags or VALUE
                }
                if (ktModifierList.hasModifier(KtTokens.SUSPEND_KEYWORD)) {
                    flags = flags or SUSPEND
                }
                if (ktModifierList.hasModifier(KtTokens.COMPANION_KEYWORD)) {
                    flags = flags or COMPANION
                }
                if (ktModifierList.hasFunModifier()) {
                    flags = flags or FUN
                }
                if (ktModifierList.hasModifier(KtTokens.DATA_KEYWORD)) {
                    flags = flags or DATA
                }
            }
            // Methods that are property accessors inherit visibility from the source element
            if (element is UMethod && (element.sourceElement is KtPropertyAccessor)) {
                val sourceElement = element.sourceElement
                if (sourceElement is KtModifierListOwner) {
                    val sourceModifierList = sourceElement.modifierList
                    if (sourceModifierList != null) {
                        if (sourceModifierList.hasModifier(KtTokens.INTERNAL_KEYWORD)) {
                            visibilityFlags = INTERNAL
                        }
                    }
                }
            }

            // Merge in the visibility flags.
            flags = flags or visibilityFlags

            return flags
        }

        /**
         * Returns a list of the targets this annotation is defined to apply to, as qualified names
         * (e.g. "java.lang.annotation.ElementType.TYPE_USE").
         *
         * If the annotation can't be resolved or does not use `@Target`, returns an empty list.
         */
        private fun PsiAnnotation.targets(): List<String> {
            return resolveAnnotationType()
                ?.annotations
                ?.firstOrNull { it.hasQualifiedName(JAVA_LANG_ANNOTATION_TARGET) }
                ?.findAttributeValue("value")
                ?.targets()
                ?: emptyList()
        }

        /**
         * Returns the element types listed in the annotation value, if the value is a direct
         * reference or an array of direct references.
         */
        private fun PsiAnnotationMemberValue.targets(): List<String> {
            return when (this) {
                is PsiReferenceExpression -> listOf(qualifiedName)
                is PsiArrayInitializerMemberValue ->
                    initializers.mapNotNull { (it as? PsiReferenceExpression)?.qualifiedName }
                else -> emptyList()
            }
        }

        /**
         * Annotations which are only `TYPE_USE` should only apply to types, not the owning item of
         * the type. However, psi incorrectly applies these items to both their types and owning
         * items.
         *
         * If an annotation targets both `TYPE_USE` and other use sites, it should be applied to
         * both types and owning items of the type if the owning item is one of the targeted use
         * sites. See https://docs.oracle.com/javase/specs/jls/se11/html/jls-9.html#jls-9.7.4 for
         * more detail.
         *
         * To work around psi incorrectly applying exclusively `TYPE_USE` annotations to non-type
         * items, this filters all annotations which should apply to types but not the [forOwner]
         * item.
         */
        private fun List<PsiAnnotation>.filterIncorrectTypeUseAnnotations(
            forOwner: PsiModifierListOwner
        ): List<PsiAnnotation> {
            val expectedTarget =
                when (forOwner) {
                    is PsiMethod -> "java.lang.annotation.ElementType.METHOD"
                    is PsiParameter -> "java.lang.annotation.ElementType.PARAMETER"
                    is PsiField -> "java.lang.annotation.ElementType.FIELD"
                    else -> return this
                }

            return filter { annotation ->
                val applicableTargets = annotation.targets()
                // If the annotation is not type use, it has been correctly applied to the item.
                !applicableTargets.contains(JAVA_LANG_TYPE_USE_TARGET) ||
                    // If the annotation has the item type as a target, it should be applied here.
                    applicableTargets.contains(expectedTarget) ||
                    // For now, leave in nullness annotations until they are specially handled.
                    isNullnessAnnotation(annotation.qualifiedName.orEmpty())
            }
        }

        private fun create(
            codebase: PsiBasedCodebase,
            element: PsiModifierListOwner
        ): PsiModifierItem {
            val modifierList = element.modifierList ?: return PsiModifierItem(codebase)
            var flags = computeFlag(element, modifierList)

            val psiAnnotations = modifierList.annotations
            return if (psiAnnotations.isEmpty()) {
                PsiModifierItem(codebase, flags)
            } else {
                val annotations: MutableList<AnnotationItem> =
                    // psi sometimes returns duplicate annotations, using distinct() to counter
                    // that.
                    psiAnnotations
                        .distinct()
                        // Remove any type-use annotations that psi incorrectly applied to the item.
                        .filterIncorrectTypeUseAnnotations(element)
                        .map {
                            val qualifiedName = it.qualifiedName
                            // Consider also supporting
                            // com.android.internal.annotations.VisibleForTesting?
                            if (qualifiedName == ANDROIDX_VISIBLE_FOR_TESTING) {
                                val otherwise = it.findAttributeValue(ATTR_OTHERWISE)
                                val ref =
                                    when {
                                        otherwise is PsiReferenceExpression ->
                                            otherwise.referenceName ?: ""
                                        otherwise != null -> otherwise.text
                                        else -> ""
                                    }
                                flags = getVisibilityFlag(ref, flags)
                            }

                            PsiAnnotationItem.create(codebase, it, qualifiedName)
                        }
                        .filter { !it.isDeprecatedForSdk() }
                        .toMutableList()
                PsiModifierItem(codebase, flags, annotations)
            }
        }

        private fun create(
            codebase: PsiBasedCodebase,
            element: PsiModifierListOwner,
            annotated: UAnnotated
        ): PsiModifierItem {
            val modifierList = element.modifierList ?: return PsiModifierItem(codebase)
            val uAnnotations = annotated.uAnnotations
            val psiAnnotations =
                modifierList.annotations.takeIf { it.isNotEmpty() }
                    ?: (annotated.javaPsi as? PsiModifierListOwner)?.annotations
                        ?: PsiAnnotation.EMPTY_ARRAY

            var flags = computeFlag(element, modifierList)

            return if (uAnnotations.isEmpty()) {
                if (psiAnnotations.isNotEmpty()) {
                    val annotations: MutableList<AnnotationItem> =
                        psiAnnotations
                            .map { PsiAnnotationItem.create(codebase, it) }
                            .toMutableList()
                    PsiModifierItem(codebase, flags, annotations)
                } else {
                    PsiModifierItem(codebase, flags)
                }
            } else {
                val isPrimitiveVariable = element is UVariable && element.type is PsiPrimitiveType

                val annotations: MutableList<AnnotationItem> =
                    uAnnotations
                        // Uast sometimes puts nullability annotations on primitives!?
                        .filter {
                            !isPrimitiveVariable ||
                                it.qualifiedName == null ||
                                !it.isKotlinNullabilityAnnotation
                        }
                        .map {
                            val qualifiedName = it.qualifiedName
                            if (qualifiedName == ANDROIDX_VISIBLE_FOR_TESTING) {
                                val otherwise = it.findAttributeValue(ATTR_OTHERWISE)
                                val ref =
                                    when {
                                        otherwise is PsiReferenceExpression ->
                                            otherwise.referenceName ?: ""
                                        otherwise != null -> otherwise.asSourceString()
                                        else -> ""
                                    }
                                flags = getVisibilityFlag(ref, flags)
                            }

                            UAnnotationItem.create(codebase, it, qualifiedName)
                        }
                        .filter { !it.isDeprecatedForSdk() }
                        .toMutableList()

                if (!isPrimitiveVariable) {
                    if (
                        psiAnnotations.isNotEmpty() &&
                            annotations.none { it.isNullnessAnnotation() }
                    ) {
                        val ktNullAnnotation =
                            psiAnnotations.firstOrNull { psiAnnotation ->
                                psiAnnotation.qualifiedName?.let { isNullnessAnnotation(it) } ==
                                    true
                            }
                        ktNullAnnotation?.let {
                            annotations.add(PsiAnnotationItem.create(codebase, it))
                        }
                    }
                }

                PsiModifierItem(codebase, flags, annotations)
            }
        }

        /** Returns whether this is a `@DeprecatedForSdk` annotation **that should be skipped**. */
        private fun AnnotationItem.isDeprecatedForSdk(): Boolean {
            if (qualifiedName != ANDROID_DEPRECATED_FOR_SDK) {
                return false
            }

            val allowIn = findAttribute(ATTR_ALLOW_IN) ?: return false

            for (api in allowIn.leafValues()) {
                val annotationName = api.value() as? String ?: continue
                if (codebase.annotationManager.isShowAnnotationName(annotationName)) {
                    return true
                }
            }

            return false
        }

        private val NOT_NULL = NotNull::class.qualifiedName
        private val NULLABLE = Nullable::class.qualifiedName

        private val UAnnotation.isKotlinNullabilityAnnotation: Boolean
            get() = qualifiedName == NOT_NULL || qualifiedName == NULLABLE

        /** Modifies the modifier flags based on the VisibleForTesting otherwise constants */
        private fun getVisibilityFlag(ref: String, flags: Int): Int {
            val visibilityFlags =
                if (ref.endsWith("PROTECTED")) {
                    PROTECTED
                } else if (ref.endsWith("PACKAGE_PRIVATE")) {
                    PACKAGE_PRIVATE
                } else if (ref.endsWith("PRIVATE") || ref.endsWith("NONE")) {
                    PRIVATE
                } else {
                    flags and VISIBILITY_MASK
                }

            return (flags and VISIBILITY_MASK.inv()) or visibilityFlags
        }

        fun create(codebase: PsiBasedCodebase, original: PsiModifierItem): PsiModifierItem {
            val originalAnnotations =
                original.annotations ?: return PsiModifierItem(codebase, original.flags)
            val copy: MutableList<AnnotationItem> = ArrayList(originalAnnotations.size)
            originalAnnotations.mapTo(copy) { item ->
                when (item) {
                    is PsiAnnotationItem -> PsiAnnotationItem.create(codebase, item)
                    is UAnnotationItem -> UAnnotationItem.create(codebase, item)
                    else -> {
                        throw Exception("Unexpected annotation type ${item::class.qualifiedName}")
                    }
                }
            }
            return PsiModifierItem(codebase, original.flags, copy)
        }
    }
}

private const val ANDROIDX_VISIBLE_FOR_TESTING = "androidx.annotation.VisibleForTesting"
private const val ATTR_OTHERWISE = "otherwise"
private const val ATTR_ALLOW_IN = "allowIn"
