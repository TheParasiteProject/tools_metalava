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

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.metalava.reporter.BasicReporter
import com.android.tools.metalava.testing.tempDirectory
import com.intellij.openapi.util.Disposer
import java.io.File
import java.io.PrintWriter
import kotlin.test.assertNotNull

fun testCodebase(
    vararg sources: TestFile,
    classPath: List<File> = emptyList(),
    action: (PsiBasedCodebase) -> Unit,
) {
    tempDirectory { tempDirectory ->
        PsiEnvironmentManager().use { psiEnvironmentManager ->
            val codebase =
                createTestCodebase(
                    psiEnvironmentManager,
                    tempDirectory,
                    sources.toList(),
                    classPath,
                )
            action(codebase)
        }
        Disposer.assertIsEmpty(true)
    }
}

private fun createTestCodebase(
    psiEnvironmentManager: PsiEnvironmentManager,
    directory: File,
    sources: List<TestFile>,
    classPath: List<File>,
): PsiBasedCodebase {
    Disposer.setDebugMode(true)

    val reporter = BasicReporter(PrintWriter(System.err))
    return PsiSourceParser(psiEnvironmentManager, reporter)
        .parseSources(
            sources = sources.map { it.createFile(directory) },
            description = "Test Codebase",
            sourcePath = listOf(directory),
            classPath = classPath,
        )
}

fun PsiBasedCodebase.assertClass(qualifiedName: String): PsiClassItem {
    val classItem = this.findClass(qualifiedName)
    assertNotNull(classItem) { "Expected $qualifiedName to be defined" }
    return classItem
}
