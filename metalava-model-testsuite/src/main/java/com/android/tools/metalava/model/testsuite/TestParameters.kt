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

package com.android.tools.metalava.model.testsuite

import java.util.Locale

/** Encapsulates all the parameters for the [BaseModelTest] */
data class TestParameters(
    /** The [ModelSuiteRunner] to use. */
    val runner: ModelSuiteRunner,
    val inputFormat: InputFormat,
) {
    /** Override this to return the string that will be used in the test name. */
    override fun toString(): String = "$runner,${inputFormat.name.lowercase(Locale.US)}"
}
