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

package com.android.tools.metalava.model.text

/** Determines the locations where classes are searched for while loading a signature api file. */
enum class ApiClassResolution(val optionValue: String) {
    /** Only look for classes in the API signature text files. */
    API("api"),

    /** Look for classes in the API signature text files first, then the classpath. */
    API_CLASSPATH("api:classpath")
}
