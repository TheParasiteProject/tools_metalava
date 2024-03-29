/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tools.metalava

import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.MethodItem
import java.util.function.Predicate

class FilterPredicate(private val wrapped: Predicate<Item>) : Predicate<Item> {

    override fun test(method: Item): Boolean {
        return when {
            wrapped.test(method) -> true
            method is MethodItem ->
                !method.isConstructor() && method.findPredicateSuperMethod(wrapped) != null
            else -> false
        }
    }
}
