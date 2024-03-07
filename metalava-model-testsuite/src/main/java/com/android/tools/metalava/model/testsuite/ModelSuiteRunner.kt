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

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.metalava.model.Codebase
import java.io.File

/**
 * An API that defines a service which model test implementations must provide.
 *
 * An instance of this will be retrieved using the [ServiceLoader] mechanism.
 */
interface ModelSuiteRunner {

    /** The set of supported [InputFormat]s that this runner can handle. */
    val supportedInputFormats: Set<InputFormat>

    /**
     * Create a [Codebase] from the supplied [input] files and then run a test on that [Codebase].
     *
     * Implementations of this consume [input] to create a [Codebase] on which the test is run.
     */
    fun createCodebaseAndRun(
        tempDir: File,
        input: List<TestFile>,
        test: (Codebase) -> Unit,
    )

    /** The name of the runner used in parameterized test names. */
    override fun toString(): String
}
