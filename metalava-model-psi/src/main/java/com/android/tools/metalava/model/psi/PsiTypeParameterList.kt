/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.tools.metalava.model.TypeParameterItem
import com.android.tools.metalava.model.TypeParameterList

internal class PsiTypeParameterList(
    val codebase: PsiBasedCodebase,
    private val psiTypeParameterList: com.intellij.psi.PsiTypeParameterList
) : TypeParameterList {
    private val typeParameters by lazy {
        psiTypeParameterList.typeParameters.map {
            PsiTypeParameterItem.create(codebase, it).apply { finishInitialization() }
        }
    }

    override fun toString(): String {
        return PsiTypeItem.typeParameterList(psiTypeParameterList) ?: ""
    }

    override fun typeParameterNames(): List<String> {
        return typeParameters.map { it.simpleName() }
    }

    override fun typeParameters(): List<TypeParameterItem> {
        return typeParameters
    }
}
