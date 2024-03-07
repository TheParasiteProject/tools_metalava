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

package com.android.tools.metalava.model.psi

import com.android.tools.metalava.testing.java
import kotlin.test.assertEquals
import org.junit.Test

class PsiSourceParserTest : BasePsiTest() {

    @Test
    fun `Regression test for 124333557`() {
        // Regression test for 124333557: Handle empty java files
        testCodebase(
            java(
                "src/test/pkg/Something.java",
                """
                    /** Nothing much here */
                    """
            ),
            java(
                "src/test/pkg/Something2.java",
                """
                    /** Nothing much here */
                    package test.pkg;
                    """
            ),
            java(
                "src/test/Something2.java",
                """
                    /** Wrong package */
                    package test.wrong;
                    """
            ),
            java(
                """
                    package test.pkg;
                    public class Test {
                        private Test() { }
                    }
                    """
            ),
        ) {
            // Make sure we handle blank/doc-only java doc files in root extraction. This is
            // basically redoing what the previous code did to make sure that the underlying code
            // behaved exactly as expected. That means that the same error will be reported twice.
            val src = listOf(projectDir.resolve("src"))
            val files = gatherSources(reporter, src)
            val roots = extractRoots(reporter, files)
            assertEquals(1, roots.size)
            assertEquals(src[0].path, roots[0].path)

            // Make sure that the error about the invalid package name is reported twice. Once by
            // the testCodebase and once by the immediately preceding code.
            assertEquals(
                """
                    TESTROOT/src/test/Something2.java: error: Unable to determine the package name. This usually means that a source file was where the directory does not seem to match the package declaration; we expected the path TESTROOT/src/test/Something2.java to end with /test/wrong/Something2.java [IoError]
                    TESTROOT/src/test/Something2.java: error: Unable to determine the package name. This usually means that a source file was where the directory does not seem to match the package declaration; we expected the path TESTROOT/src/test/Something2.java to end with /test/wrong/Something2.java [IoError]
                """
                    .trimIndent(),
                output
            )
        }
    }
}
