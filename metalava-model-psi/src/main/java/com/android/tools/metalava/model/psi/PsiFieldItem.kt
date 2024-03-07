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

import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.FieldItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.isNonNullAnnotation
import com.intellij.psi.PsiCallExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.JavaConstantExpressionEvaluator
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement

class PsiFieldItem(
    override val codebase: PsiBasedCodebase,
    private val psiField: PsiField,
    private val containingClass: PsiClassItem,
    private val name: String,
    modifiers: PsiModifierItem,
    documentation: String,
    private val fieldType: PsiTypeItem,
    private val isEnumConstant: Boolean,
    private val initialValue: Any?
) :
    PsiItem(
        codebase = codebase,
        modifiers = modifiers,
        documentation = documentation,
        element = psiField
    ),
    FieldItem {

    override var property: PsiPropertyItem? = null

    override fun type(): TypeItem = fieldType

    override fun initialValue(requireConstant: Boolean): Any? {
        if (initialValue != null) {
            return initialValue
        }
        val constant = psiField.computeConstantValue()
        // Offset [ClsFieldImpl#computeConstantValue] for [TYPE] field in boxed primitive types.
        // Those fields hold [Class] object, but the constant value should not be of [PsiType].
        if (
            constant is PsiPrimitiveType &&
                "TYPE" == name &&
                (fieldType as? PsiClassTypeItem)?.qualifiedName == "java.lang.Class"
        ) {
            return null
        }
        if (constant != null) {
            return constant
        }

        return if (!requireConstant) {
            val initializer = psiField.initializer ?: return null
            JavaConstantExpressionEvaluator.computeConstantExpression(initializer, false)
        } else {
            null
        }
    }

    override fun isEnumConstant(): Boolean = isEnumConstant

    override fun name(): String = name

    override fun containingClass(): PsiClassItem = containingClass

    override fun psi(): PsiField = psiField

    override fun isCloned(): Boolean {
        val psiClass = run {
            val p = containingClass().psi()
            if (p is UClass) {
                p.sourcePsi as? PsiClass ?: return false
            } else {
                p
            }
        }
        return psiField.containingClass != psiClass
    }

    override fun duplicate(targetContainingClass: ClassItem): PsiFieldItem {
        val duplicated = create(codebase, targetContainingClass as PsiClassItem, psiField)
        duplicated.inheritedFrom = containingClass
        duplicated.inheritedField = inheritedField

        // Preserve flags that may have been inherited (propagated) from surrounding packages
        if (targetContainingClass.hidden) {
            duplicated.hidden = true
        }
        if (targetContainingClass.removed) {
            duplicated.removed = true
        }
        if (targetContainingClass.docOnly) {
            duplicated.docOnly = true
        }

        return duplicated
    }

    override var inheritedFrom: ClassItem? = null
    override var inheritedField: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        return other is FieldItem &&
            name == other.name() &&
            containingClass == other.containingClass()
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun toString(): String = "field ${containingClass.fullName()}.${name()}"

    companion object {
        fun create(
            codebase: PsiBasedCodebase,
            containingClass: PsiClassItem,
            psiField: PsiField
        ): PsiFieldItem {
            val name = psiField.name
            val commentText = javadoc(psiField)
            val modifiers = modifiers(codebase, psiField, commentText)

            val fieldType = codebase.getType(psiField.type)
            val isEnumConstant = psiField is PsiEnumConstant
            val initialValue = null // compute lazily

            val field =
                PsiFieldItem(
                    codebase = codebase,
                    psiField = psiField,
                    containingClass = containingClass,
                    name = name,
                    documentation = commentText,
                    modifiers = modifiers,
                    fieldType = fieldType,
                    isEnumConstant = isEnumConstant,
                    initialValue = initialValue
                )
            field.modifiers.setOwner(field)
            return field
        }
    }

    override fun implicitNullness(): Boolean? {
        // Is this a Kotlin object declaration (such as a companion object) ?
        // If so, it is always non-null.
        if (psiField is UElement && psiField.sourcePsi is KtObjectDeclaration) {
            return false
        }

        // Delegate to the super class, only dropping through if it did not determine an implicit
        // nullness.
        super<FieldItem>.implicitNullness()?.let { nullable ->
            return nullable
        }

        if (modifiers.isFinal()) {
            // If we're looking at a final field, look on the right hand side of the field to the
            // field initialization. If that right hand side for example represents a method call,
            // and the method we're calling is annotated with @NonNull, then the field (since it is
            // final) will always be @NonNull as well.
            when (val initializer = psiField.initializer) {
                is PsiReference -> {
                    val resolved = initializer.resolve()
                    if (
                        resolved is PsiModifierListOwner &&
                            resolved.annotations.any { isNonNullAnnotation(it.qualifiedName ?: "") }
                    ) {
                        return false
                    }
                }
                is PsiCallExpression -> {
                    val resolved = initializer.resolveMethod()
                    if (
                        resolved != null &&
                            resolved.annotations.any { isNonNullAnnotation(it.qualifiedName ?: "") }
                    ) {
                        return false
                    }
                }
            }
        }

        return null
    }
}
