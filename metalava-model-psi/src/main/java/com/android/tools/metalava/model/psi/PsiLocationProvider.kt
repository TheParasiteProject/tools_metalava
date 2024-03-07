/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.tools.metalava.model.BaselineKey
import com.android.tools.metalava.model.Location
import com.android.tools.metalava.reporter.Issues
import com.android.tools.metalava.reporter.Reporter
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiParameter
import com.intellij.psi.impl.light.LightElement
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClass
import org.jetbrains.kotlin.psi.psiUtil.parameterIndex
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement

class PsiLocationProvider {

    companion object {
        private fun getTextRange(element: PsiElement): TextRange? {
            var range: TextRange? = null

            if (element is UClass) {
                range = element.sourcePsi?.textRange
            } else if (element is PsiCompiledElement) {
                if (element is LightElement) {
                    range = (element as PsiElement).textRange
                }
                if (range == null || TextRange.EMPTY_RANGE == range) {
                    return null
                }
            } else {
                range = element.textRange
            }

            return range
        }

        /** Returns the 0-based line number of character position <offset> in <text> */
        private fun getLineNumber(text: String, offset: Int): Int {
            var line = 0
            var curr = 0
            val target = offset.coerceAtMost(text.length)
            while (curr < target) {
                if (text[curr++] == '\n') {
                    line++
                }
            }
            return line
        }

        /**
         * Compute a [Location] (including [BaselineKey]) from a [PsiElement]
         *
         * @param element the optional element from which the path, line and [BaselineKey] will be
         *   computed.
         * @param overridingBaselineKey the optional [BaselineKey] to use instead of the
         *   [BaselineKey] computed from the element.
         */
        fun elementToLocation(
            element: PsiElement?,
            overridingBaselineKey: BaselineKey? = null
        ): Location {
            element ?: return Location.unknownLocationAndBaselineKey
            val actualBaselineKey = overridingBaselineKey ?: getBaselineKey(element)
            val psiFile =
                element.containingFile
                    ?: return Location.unknownLocationWithBaselineKey(actualBaselineKey)
            val virtualFile =
                psiFile.virtualFile
                    ?: return Location.unknownLocationWithBaselineKey(actualBaselineKey)
            val virtualFileAbsolutePath =
                try {
                    virtualFile.toNioPath().toAbsolutePath()
                } catch (e: UnsupportedOperationException) {
                    return Location.unknownLocationWithBaselineKey(actualBaselineKey)
                }

            // Unwrap UAST for accurate Kotlin line numbers (UAST synthesizes text offsets
            // sometimes)
            val sourceElement = (element as? UElement)?.sourcePsi ?: element

            // Skip doc comments for classes, methods and fields by pointing at the line where the
            // element's name is or falling back to the first line of its modifier list (which may
            // include annotations) or lastly to the start of the element itself
            val rangeElement =
                (sourceElement as? PsiNameIdentifierOwner)?.nameIdentifier
                    ?: (sourceElement as? KtModifierListOwner)?.modifierList
                        ?: (sourceElement as? PsiModifierListOwner)?.modifierList ?: sourceElement

            val range = getTextRange(rangeElement)
            val lineNumber =
                if (range == null) {
                    -1 // No source offsets, use invalid line number
                } else {
                    getLineNumber(psiFile.text, range.startOffset) + 1
                }
            return Location(virtualFileAbsolutePath, lineNumber, actualBaselineKey)
        }

        private fun getBaselineKey(element: PsiElement): BaselineKey {
            return when (element) {
                is PsiFile -> {
                    val virtualFile = element.virtualFile
                    val file = VfsUtilCore.virtualToIoFile(virtualFile)
                    return Location.getBaselineKeyForFile(file)
                }
                else -> {
                    val elementId = getElementId(element)
                    Location.getBaselineKeyForElementId(elementId)
                }
            }
        }

        private fun getElementId(element: PsiElement): String {
            return when (element) {
                is PsiClass -> element.qualifiedName ?: element.name ?: "?"
                is KtClass -> element.fqName?.asString() ?: element.name ?: "?"
                is PsiMethod -> {
                    val containingClass = element.containingClass
                    val name = element.name
                    val parameterList =
                        "(" +
                            element.parameterList.parameters.joinToString {
                                it.type.canonicalText
                            } +
                            ")"
                    if (containingClass != null) {
                        getElementId(containingClass) + "#" + name + parameterList
                    } else {
                        name + parameterList
                    }
                }
                is PsiField -> {
                    val containingClass = element.containingClass
                    val name = element.name
                    if (containingClass != null) {
                        getElementId(containingClass) + "#" + name
                    } else {
                        name
                    }
                }
                is KtProperty -> {
                    val containingClass = element.containingClass()
                    val name = element.nameAsSafeName.asString()
                    if (containingClass != null) {
                        getElementId(containingClass) + "#" + name
                    } else {
                        name
                    }
                }
                is PsiPackage -> element.qualifiedName
                is PsiParameter -> {
                    val method = element.declarationScope.parent
                    if (method is PsiMethod) {
                        getElementId(method) + " parameter #" + element.parameterIndex()
                    } else {
                        "?"
                    }
                }
                else -> element.toString()
            }
        }
    }
}

fun Reporter.report(id: Issues.Issue, element: PsiElement?, message: String): Boolean {
    val location = PsiLocationProvider.elementToLocation(element)
    return report(id, null, message, location)
}
