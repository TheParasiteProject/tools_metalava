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

package com.android.tools.metalava.model.testsuite.cli

import com.github.ajalt.clikt.core.PrintHelpMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateBaselineCommandTest {

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `Test help`() {
        val command = UpdateBaselineCommand()
        val e = assertThrows(PrintHelpMessage::class.java) { command.parse(arrayOf("-h")) }

        assertEquals(
            """
Usage: update-baseline [OPTIONS] <test-report-files>...

  Update the src/test/resources/model-test-suite-baseline.txt file.

Options:
  --project-dir <dir>  Project directory for the project of the metalava-model
                       provider that is running the
                       `metalava-model-test-suite`.
  -h, --help           Show this message and exit

Arguments:
  <test-report-files>  Test report files generated by gradle when running the
                       `metalava-model-test-suite`.
            """
                .trimIndent(),
            e.command.getFormattedHelp()
        )
    }

    fun writeFile(file: File, contents: String) {
        file.parentFile.mkdirs()
        file.writeText(contents)
    }

    @Test
    fun `Test update file`() {
        val projectDir = temporaryFolder.newFolder("project")
        val file = projectDir.resolve("src/test/resources/model-test-suite-baseline.txt")
        val contents =
            """
                Class1
                  Method2
                  Method3

            """
                .trimIndent()

        writeFile(file, contents)

        val testReportFile1 = temporaryFolder.newFile("TEST-Class1.xml")
        writeFile(
            testReportFile1,
            """
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Class1" tests="3" skipped="1" failures="1" errors="0" timestamp="now" hostname="wherever" time="fast">
  <properties/>
  <!-- Will add the Class1/Method1 entry in the baseline because this failed. -->
  <testcase name="Method1" classname="Class1" time="1">
    <failure message="message" type="type">message
    at ...
</failure>
  </testcase>
  <!-- Will keep the Class1/Method2 entry in the baseline because this was skipped. -->
  <testcase name="Method2" classname="Class1" time="1">
    <skipped/>
  </testcase>
  <!-- Will remove the Class1/Method3 entry in the baseline because this passed. -->
  <testcase name="Method3" classname="Class1" time="1"/>
  <system-out><![CDATA[Blah
]]></system-out>
  <system-err><![CDATA[Blah
]]></system-err>
</testsuite>
            """
                .trimIndent(),
        )

        val testReportFile2 = temporaryFolder.newFile("TEST-Class2.xml")
        writeFile(
            testReportFile2,
            """
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="Class2" tests="2" failures="1" errors="0" timestamp="now" hostname="wherever" time="fast">
  <properties/>
  <!-- Will add the Class2/Method1 entry in the baseline because this failed. -->
  <testcase name="Method1" classname="Class2" time="1">
    <failure message="message" type="type">message
    at ...
</failure>
  </testcase>
  <!--
   ! Will try and remove a Class2/Method2 entry from the baseline because this passed but there is
   ! no such entry so it will essentially do nothing.
   !-->
  <testcase name="Method2" classname="Class2" time="1"/>
  <system-out><![CDATA[Blah
]]></system-out>
  <system-err><![CDATA[Blah
]]></system-err>
</testsuite>
            """
                .trimIndent(),
        )

        val command = UpdateBaselineCommand()
        command.parse(
            arrayOf(testReportFile1.path, testReportFile2.path, "--project-dir", projectDir.path)
        )

        assertEquals(
            """
                Class1
                  Method1
                  Method2

                Class2
                  Method1

            """
                .trimIndent(),
            file.readText()
        )
    }
}
