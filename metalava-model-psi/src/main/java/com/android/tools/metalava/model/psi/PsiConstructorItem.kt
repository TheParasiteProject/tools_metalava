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

import com.android.tools.metalava.model.ConstructorItem
import com.android.tools.metalava.model.DefaultModifierList.Companion.PACKAGE_PRIVATE
import com.android.tools.metalava.model.Location
import com.android.tools.metalava.model.MethodItem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.uast.UMethod

class PsiConstructorItem
private constructor(
    codebase: PsiBasedCodebase,
    psiMethod: PsiMethod,
    containingClass: PsiClassItem,
    name: String,
    modifiers: PsiModifierItem,
    documentation: String,
    parameters: List<PsiParameterItem>,
    returnType: PsiTypeItem,
    val implicitConstructor: Boolean = false,
    override val isPrimary: Boolean = false
) :
    PsiMethodItem(
        codebase = codebase,
        modifiers = modifiers,
        documentation = documentation,
        psiMethod = psiMethod,
        containingClass = containingClass,
        name = name,
        returnType = returnType,
        parameters = parameters
    ),
    ConstructorItem {

    init {
        if (implicitConstructor) {
            setThrowsTypes(emptyList())
        }
    }

    override fun isImplicitConstructor(): Boolean = implicitConstructor

    override fun isConstructor(): Boolean = true

    override var superConstructor: ConstructorItem? = null

    override fun isCloned(): Boolean = false

    override fun superMethods(): List<MethodItem> = emptyList()

    /**
     * Override to handle providing the location for a synthetic/implicit constructor which has no
     * associated file.
     */
    override fun location(): Location {
        // If no PSI element, is this a synthetic/implicit constructor? If so
        // grab the parent class' PSI element instead for file/location purposes
        val element =
            if (implicitConstructor && element.containingFile?.virtualFile == null) {
                (containingClass() as PsiClassItem).psi()
            } else {
                element
            }

        return PsiLocationProvider.elementToLocation(element, Location.getBaselineKeyForItem(this))
    }

    companion object {
        fun create(
            codebase: PsiBasedCodebase,
            containingClass: PsiClassItem,
            psiMethod: PsiMethod
        ): PsiConstructorItem {
            assert(psiMethod.isConstructor)
            val name = psiMethod.name
            val commentText = javadoc(psiMethod)
            val modifiers = modifiers(codebase, psiMethod, commentText)
            val parameters = parameterList(codebase, psiMethod)
            val constructor =
                PsiConstructorItem(
                    codebase = codebase,
                    psiMethod = psiMethod,
                    containingClass = containingClass,
                    name = name,
                    documentation = commentText,
                    modifiers = modifiers,
                    parameters = parameters,
                    returnType = codebase.getType(containingClass.psiClass),
                    implicitConstructor = false,
                    isPrimary = (psiMethod as? UMethod)?.isPrimaryConstructor ?: false
                )
            constructor.modifiers.setOwner(constructor)
            return constructor
        }

        fun createDefaultConstructor(
            codebase: PsiBasedCodebase,
            containingClass: PsiClassItem,
            psiClass: PsiClass
        ): PsiConstructorItem {
            val name = psiClass.name!!

            val factory = JavaPsiFacade.getInstance(psiClass.project).elementFactory
            val psiMethod = factory.createConstructor(name, psiClass)
            val modifiers = PsiModifierItem(codebase, PACKAGE_PRIVATE, null)
            modifiers.setVisibilityLevel(containingClass.modifiers.getVisibilityLevel())

            val item =
                PsiConstructorItem(
                    codebase = codebase,
                    psiMethod = psiMethod,
                    containingClass = containingClass,
                    name = name,
                    documentation = "",
                    modifiers = modifiers,
                    parameters = emptyList(),
                    returnType = codebase.getType(psiClass),
                    implicitConstructor = true
                )
            modifiers.setOwner(item)
            return item
        }

        private val UMethod.isPrimaryConstructor: Boolean
            get() = sourcePsi is KtPrimaryConstructor || sourcePsi is KtClassOrObject
    }
}
