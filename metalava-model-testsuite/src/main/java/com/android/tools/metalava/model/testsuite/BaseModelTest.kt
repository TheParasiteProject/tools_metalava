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
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.metalava.model.Assertions
import com.android.tools.metalava.model.Codebase
import com.android.tools.metalava.model.source.SourceCodebase
import java.util.ServiceLoader
import kotlin.test.fail
import org.junit.AssumptionViolatedException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter
import org.junit.runners.model.Statement

/**
 * Base class for tests that verify the behavior of model implementations.
 *
 * This is parameterized by [TestParameters] as even though the tests are run in different projects
 * the test results are collated and reported together. Having the parameters in the test name makes
 * it easier to differentiate them.
 *
 * Note: In the top-level test report produced by Gradle it appears to just display whichever test
 * ran last. However, the test reports in the model implementation projects do list each run
 * separately. If this is an issue then the [ModelSuiteRunner] implementations could all be moved
 * into the same project and run tests against them all at the same time.
 */
abstract class BaseModelTest : Assertions {

    /**
     * Set by injection by [Parameterized] after class initializers are called.
     *
     * Anything that accesses this, either directly or indirectly must do it after initialization,
     * e.g. from lazy fields or in methods called from test methods.
     *
     * The basic process is that each test class gets given a list of parameters. There are two ways
     * to do that, through field injection or via constructor. If any fields in the test class
     * hierarchy are annotated with the [Parameter] annotation then field injection is used,
     * otherwise they are passed via constructor.
     *
     * The [Parameter] specifies the index within the list of parameters of the parameter that
     * should be inserted into the field. The number of [Parameter] annotated fields must be the
     * same as the number of parameters in the list and each index within the list must be specified
     * by exactly one [Parameter].
     *
     * The life-cycle of a parameterized test class is as follows:
     * 1. The test class instance is created.
     * 2. The parameters are injected into the [Parameter] annotated fields.
     * 3. Follows the normal test class life-cycle.
     */
    @Parameter(0) lateinit var baseParameters: TestParameters

    /** The [ModelSuiteRunner] that this test must use. */
    private val runner by lazy { baseParameters.runner }

    /**
     * The [InputFormat] of the test files that should be processed by this test. It must ignore all
     * other [InputFormat]s.
     */
    protected val inputFormat by lazy { baseParameters.inputFormat }

    @get:Rule val temporaryFolder = TemporaryFolder()

    @get:Rule val baselineTestRule: TestRule by lazy { BaselineTestRule(runner) }

    companion object {
        /** Compute the list of [TestParameters] based on the available runners. */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun testParameters(): Iterable<TestParameters> {
            val loader = ServiceLoader.load(ModelSuiteRunner::class.java)
            val runners = loader.toList()
            if (runners.isEmpty()) {
                fail("No runners found")
            }
            val list =
                runners.flatMap { runner ->
                    runner.supportedInputFormats
                        .map { inputFormat -> TestParameters(runner, inputFormat) }
                        .toList()
                }
            return list
        }

        /**
         * Compute the cross product of the supplied [data] and the [testParameters].
         *
         * This must be called from the parameters method of a parameterized test class that is
         * parameterized in two dimensions, i.e. the available runners as returned by
         * [testParameters] and its own custom dimension.
         *
         *         @JvmStatic
         *         @Parameterized.Parameters(name = "{0},{1}")
         *         fun combinedTestParameters(): Iterable<Array<Any>> {
         *             return crossProduct(myData)
         *         }
         */
        fun crossProduct(data: Iterable<Any>): List<Array<Any>> =
            testParameters().flatMap { baseParameters -> data.map { arrayOf(baseParameters, it) } }
    }

    /**
     * Set of inputs for a test.
     *
     * Currently, this is limited to one file but in future it may be more.
     */
    data class InputSet(
        /** The [InputFormat] of the [testFiles]. */
        val inputFormat: InputFormat,

        /** The [TestFile]s to process. */
        val testFiles: List<TestFile>,
    )

    /**
     * Create an [InputSet].
     *
     * It is an error if [testFiles] is empty or if [testFiles] have different [InputFormat]. That
     * means that it is not currently possible to mix Kotlin and Java files.
     */
    fun inputSet(vararg testFiles: TestFile): InputSet {
        if (testFiles.isEmpty()) {
            throw IllegalStateException("Must provide at least one source file")
        }

        val (htmlFiles, nonHtmlFiles) =
            testFiles.partition { it.targetRelativePath.endsWith(".html") }

        // Make sure that all the test files are the same InputFormat. Ignore HTML files.
        val byInputFormat = nonHtmlFiles.groupBy { InputFormat.fromFilename(it.targetRelativePath) }

        val inputFormatCount = byInputFormat.size
        if (inputFormatCount != 1) {
            throw IllegalStateException(
                buildString {
                    append(
                        "All files in the list must be the same input format, but found $inputFormatCount different input formats:\n"
                    )
                    byInputFormat.forEach { (format, files) ->
                        append("    $format\n")
                        files.forEach { append("        $it\n") }
                    }
                }
            )
        }

        val (inputFormat, files) = byInputFormat.entries.single()
        return InputSet(inputFormat, files + htmlFiles)
    }

    /**
     * Create a [Codebase] from one of the supplied [inputSets] and then run a test on that
     * [Codebase].
     *
     * The [InputSet] that is selected is the one whose [InputSet.inputFormat] is the same as the
     * current [inputFormat]. There can be at most one of those.
     */
    private fun createCodebaseFromInputSetAndRun(
        vararg inputSets: InputSet,
        test: (Codebase) -> Unit,
    ) {
        // Run the input set that matches the current inputFormat, if there is one.
        inputSets
            .singleOrNull { it.inputFormat == inputFormat }
            ?.let {
                val tempDir = temporaryFolder.newFolder()
                runner.createCodebaseAndRun(tempDir, it.testFiles, test)
            }
    }

    private fun testFilesToInputSets(testFiles: Array<out TestFile>): Array<InputSet> {
        return testFiles.map { inputSet(it) }.toTypedArray()
    }

    /**
     * Create a [Codebase] from one of the supplied [sources] and then run the [test] on that
     * [Codebase].
     *
     * The [sources] array should have at most one [TestFile] whose extension matches an
     * [InputFormat.extension].
     */
    fun runCodebaseTest(
        vararg sources: TestFile,
        test: (Codebase) -> Unit,
    ) {
        runCodebaseTest(
            sources = testFilesToInputSets(sources),
            test = test,
        )
    }

    /**
     * Create a [Codebase] from one of the supplied [sources] [InputSet] and then run the [test] on
     * that [Codebase].
     *
     * The [sources] array should have at most one [InputSet] of each [InputFormat].
     */
    fun runCodebaseTest(
        vararg sources: InputSet,
        test: (Codebase) -> Unit,
    ) {
        createCodebaseFromInputSetAndRun(
            *sources,
            test = test,
        )
    }

    /**
     * Create a [SourceCodebase] from one of the supplied [sources] and then run the [test] on that
     * [SourceCodebase].
     *
     * The [sources] array should have at most one [TestFile] whose extension matches an
     * [InputFormat.extension].
     */
    fun runSourceCodebaseTest(
        vararg sources: TestFile,
        test: (SourceCodebase) -> Unit,
    ) {
        runSourceCodebaseTest(
            sources = testFilesToInputSets(sources),
            test = test,
        )
    }

    /**
     * Create a [SourceCodebase] from one of the supplied [sources] [InputSet]s and then run the
     * [test] on that [SourceCodebase].
     *
     * The [sources] array should have at most one [InputSet] of each [InputFormat].
     */
    fun runSourceCodebaseTest(
        vararg sources: InputSet,
        test: (SourceCodebase) -> Unit,
    ) {
        createCodebaseFromInputSetAndRun(
            *sources,
        ) {
            test(it as SourceCodebase)
        }
    }

    /** Create a signature [TestFile] with the supplied [contents]. */
    fun signature(contents: String): TestFile {
        return TestFiles.source("api.txt", contents.trimIndent())
    }
}

private const val GRADLEW_UPDATE_MODEL_TEST_SUITE_BASELINE =
    "`scripts/refresh-testsuite-baselines.sh` to update the baseline"

/** A JUnit [TestRule] that uses information from the [ModelTestSuiteBaseline] to ignore tests. */
private class BaselineTestRule(private val runner: ModelSuiteRunner) : TestRule {

    /**
     * The [ModelTestSuiteBaseline] that indicates whether the tests are expected to fail or not.
     */
    private val baseline = ModelTestSuiteBaseline.fromResource

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val expectedFailure =
                    baseline.isExpectedFailure(description.className, description.methodName)
                try {
                    // Run the test even if it is expected to fail as a change that fixes one test
                    // may fix more. Instead, this will just discard any failure.
                    base.evaluate()
                    if (expectedFailure) {
                        // If a test that was expected to fail passes then updating the baseline
                        // will remove that test from the expected test failures.
                        System.err.println(
                            "Test was expected to fail but passed, please run $GRADLEW_UPDATE_MODEL_TEST_SUITE_BASELINE"
                        )
                    }
                } catch (e: Throwable) {
                    if (expectedFailure) {
                        // If this was expected to fail then throw an AssumptionViolatedException
                        // so it is not treated as either a pass or fail.
                        throw AssumptionViolatedException(
                            "Test skipped since it is listed in the baseline file for $runner"
                        )
                    } else {
                        // Inform the developer on how to ignore this failing test.
                        System.err.println(
                            "Failing tests can be ignored by running $GRADLEW_UPDATE_MODEL_TEST_SUITE_BASELINE"
                        )

                        // Rethrow the error
                        throw e
                    }
                }
            }
        }
    }
}
