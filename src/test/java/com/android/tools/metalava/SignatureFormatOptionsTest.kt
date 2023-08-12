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

package com.android.tools.metalava

import com.github.ajalt.clikt.core.BadParameterValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

val SIGNATURE_FORMAT_OPTIONS_HELP =
    """
Signature Format Output:

  Options controlling the format of the generated signature files.

  --api-overloaded-method-order [source|signature]
                                             Specifies the order of overloaded methods in signature files. Applies to
                                             the contents of the files specified on --api and --removed-api.

                                             source - preserves the order in which overloaded methods appear in the
                                             source files. This means that refactorings of the source files which change
                                             the order but not the API can cause unnecessary changes in the API
                                             signature files.

                                             signature (default) - sorts overloaded methods by their signature. This
                                             means that refactorings of the source files which change the order but not
                                             the API will have no effect on the API signature files.
  --format [v2|v3|v4|latest|recommended]     Sets the output signature file format to be the given version.

                                             v2 - The main version used in Android.

                                             v3 - Adds support for using kotlin style syntax to embed nullability
                                             information instead of using explicit and verbose @NonNull and @Nullable
                                             annotations. This can be used for Java files and Kotlin files alike.

                                             v4 - Adds support for using concise default values in parameters. Instead
                                             of specifying the actual default values it just uses the `default` keyword.

                                             latest - The latest in the supported versions. Only use this if you want to
                                             have the very latest and are prepared to update signature files on a
                                             continuous basis.

                                             recommended (default) - The recommended version to use. This is currently
                                             set to `v2` and will only change very infrequently so can be considered
                                             stable.
  --output-kotlin-nulls [yes|no]             Controls whether nullness annotations should be formatted as in Kotlin
                                             (with "?" for nullable types, "" for non nullable types, and "!" for
                                             unknown. The default is `yes` if --format >= v3 and must be `no` (or
                                             unspecified) if --format < v3."
    """
        .trimIndent()

class SignatureFormatOptionsTest {

    private fun runTest(vararg args: String, test: (SignatureFormatOptions) -> Unit) {
        val command = MockCommand(test)
        command.parse(args.toList())
    }

    private class MockCommand(val test: (SignatureFormatOptions) -> Unit) : CliktCommand() {
        val options by SignatureFormatOptions()

        override fun run() {
            test(options)
        }
    }

    @Test
    fun `V1 not supported`() {
        val e = assertThrows(BadParameterValue::class.java) { runTest("--format=v1") {} }
        assertThat(e.message).startsWith("""Invalid value for "--format": invalid choice: v1.""")
    }

    @Test
    fun `V2 not compatible with --output-kotlin-nulls=yes (format first)`() {
        val e =
            assertThrows(BadParameterValue::class.java) {
                runTest("--format=v2", "--output-kotlin-nulls=yes") {}
            }
        assertThat(e.message)
            .startsWith(
                """Invalid value for "--output-kotlin-nulls": '--output-kotlin-nulls=yes' requires '--format=v3'"""
            )
    }

    @Test
    fun `V2 not compatible with --output-kotlin-nulls=yes (format last)`() {
        val e =
            assertThrows(BadParameterValue::class.java) {
                runTest("--output-kotlin-nulls=yes", "--format=v2") {}
            }
        assertThat(e.message)
            .startsWith(
                """Invalid value for "--output-kotlin-nulls": '--output-kotlin-nulls=yes' requires '--format=v3'"""
            )
    }

    @Test
    fun `Can override format default with --output-kotlin-nulls=no`() {
        runTest("--output-kotlin-nulls=no", "--format=v3") {
            assertThat(it.fileFormat.kotlinStyleNulls).isFalse()
        }
    }
}
