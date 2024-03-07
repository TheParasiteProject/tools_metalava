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

package com.android.tools.metalava.model.turbine

import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.TypeParameterItem
import com.android.tools.metalava.model.TypeParameterList
import com.google.turbine.binder.sym.TyVarSymbol

internal class TurbineTypeParameterItem(
    codebase: TurbineBasedCodebase,
    modifiers: TurbineModifierItem,
    internal val symbol: TyVarSymbol,
    name: String = symbol.name(),
    private val bounds: List<TypeItem>,
) :
    TurbineClassItem(
        codebase,
        name,
        name,
        name,
        modifiers,
        TurbineClassType.TYPE_PARAMETER,
        TypeParameterList.NONE
    ),
    TypeParameterItem {

    // Java does not supports reified generics
    override fun isReified(): Boolean = false

    override fun typeBounds(): List<TypeItem> = bounds
}
