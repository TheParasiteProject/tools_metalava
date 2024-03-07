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

import com.android.tools.metalava.model.DefaultItem
import com.android.tools.metalava.model.MutableModifierList

abstract class TurbineItem(
    override val codebase: TurbineBasedCodebase,
    override val modifiers: TurbineModifierItem
) : DefaultItem(modifiers) {

    override var docOnly: Boolean = false

    override var documentation: String = ""

    override var hidden: Boolean = false

    override var originallyHidden: Boolean = false

    override var synthetic: Boolean = false

    override var removed: Boolean = false

    override fun appendDocumentation(comment: String, tagSection: String?, append: Boolean) {
        TODO("b/295800205")
    }

    override fun findTagDocumentation(tag: String, value: String?): String? {
        TODO("b/295800205")
    }

    override fun isCloned(): Boolean = false

    override fun mutableModifiers(): MutableModifierList = modifiers
}
