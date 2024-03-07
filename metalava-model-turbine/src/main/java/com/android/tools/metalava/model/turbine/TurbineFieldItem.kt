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

package com.android.tools.metalava.model.turbine

import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.FieldItem
import com.android.tools.metalava.model.TypeItem

class TurbineFieldItem(
    codebase: TurbineBasedCodebase,
    private val name: String,
    private val containingClass: TurbineClassItem,
    private val type: TurbineTypeItem,
    modifiers: TurbineModifierItem,
) : TurbineItem(codebase, modifiers), FieldItem {

    override var inheritedFrom: ClassItem? = null

    override var inheritedField: Boolean = false

    override fun name(): String = name

    override fun containingClass(): ClassItem = containingClass

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        return other is FieldItem &&
            name == other.name() &&
            containingClass == other.containingClass()
    }

    override fun hashCode(): Int = name.hashCode()

    override fun type(): TypeItem = type

    override fun duplicate(targetContainingClass: ClassItem): FieldItem {
        TODO("b/295800205")
    }

    // TODO("b/295800205")
    override fun initialValue(requireConstant: Boolean): Any? = null

    // TODO("b/295800205")
    override fun isEnumConstant(): Boolean = false
}
