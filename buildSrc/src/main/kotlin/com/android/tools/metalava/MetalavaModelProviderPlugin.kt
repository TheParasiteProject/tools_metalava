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

import java.io.File
import java.io.FileFilter
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.named

/**
 * Plugin that must be applied by a project that provides an implementation of the `metalava-model`
 * API.
 */
class MetalavaModelProviderPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            // Add a special non-transitive configuration for the metalava-model-test-suite,
            val modelTestSuite =
                configurations.register("modelTestSuite") { it.setTransitive(false) }

            // For every jar in the modelTestSuite configuration unzip it and add the tree to the
            // test.testClassesDirs list which is what is searched to find the tests to run.
            tasks.named<Test>("test").configure { test ->
                modelTestSuite.get().forEach { test.testClassesDirs += zipTree(it) }
            }

            val modelProject = project(":metalava-model")
            val testSuiteProject = project(":metalava-model-testsuite")

            // Add a dependency onto the metalava-model project.
            dependencies.add("implementation", modelProject)

            // Add dependencies to the metalava-model-testsuite project in the testImplementation
            // and modelTestSuite configurations. The first is needed for compilation, the second is
            // needed to add the testsuite classes to the list of test classes to run.
            dependencies.add(modelTestSuite.name, testSuiteProject)
            dependencies.add("testImplementation", testSuiteProject)

            // Register a task that will update the model test suite baseline file using information
            // extracted from test report files.
            tasks.register("updateModelTestSuiteBaseline", JavaExec::class.java).configure { exec ->
                exec.apply {
                    description =
                        "Updates the metalava model test suite baseline file for project `${project.name}`"

                    // The class path must include the jar and the runtimeClasspath for the
                    // metalava-model-testsuite-cli project.
                    val testSuiteCliProject = project(":metalava-model-testsuite-cli")
                    classpath =
                        files(
                            testSuiteCliProject.tasks.named("jar"),
                            testSuiteCliProject.configurations.named("runtimeClasspath"),
                        )

                    mainClass.set("com.android.tools.metalava.model.testsuite.cli.UpdateBaseline")

                    val propertyTestResultsDir = project.property("testResultsDir") as File
                    val testTaskResultsDir = propertyTestResultsDir.resolve("test")
                    val testReportFiles =
                        testTaskResultsDir.listFiles(FileFilter { it.isFile })?.map { it.path }
                            ?: emptyList()
                    args = testReportFiles + listOf("--project-dir", project.projectDir.path)
                }
            }
        }
    }
}
