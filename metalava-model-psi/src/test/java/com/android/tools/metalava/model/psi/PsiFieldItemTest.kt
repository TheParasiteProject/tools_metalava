/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.tools.metalava.testing.kotlin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class PsiFieldItemTest : BasePsiTest() {
    @Test
    fun `backing fields have properties`() {
        testCodebase(kotlin("class Foo(val bar: Int)")) { codebase ->
            val field = codebase.assertClass("Foo").fields().single()

            assertNotNull(field.property)
            assertSame(field, field.property?.backingField)
        }
    }
}
