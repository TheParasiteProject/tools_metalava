/*
 * Copyright (C) 2019 The Android Open Source Project
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
import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.reporter.BasicReporter
import com.android.tools.metalava.testing.KnownSourceFiles.libcoreNonNullSource
import com.android.tools.metalava.testing.KnownSourceFiles.libcoreNullableSource
import com.android.tools.metalava.testing.KnownSourceFiles.nonNullSource
import com.android.tools.metalava.testing.KnownSourceFiles.nullableSource
import com.android.tools.metalava.testing.TemporaryFolderOwner
import com.android.tools.metalava.testing.getAndroidJar
import com.android.tools.metalava.testing.java
import com.android.tools.metalava.testing.kotlin
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeElement
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.function.Predicate
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UTypeReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.AbstractUastVisitor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class PsiTypePrinterTest : TemporaryFolderOwner {

    @get:Rule override val temporaryFolder = TemporaryFolder()

    /** A rule for creating and closing a [PsiEnvironmentManager] */
    @get:Rule val psiEnvironmentManagerRule = PsiEnvironmentManagerRule()

    class PsiEnvironmentManagerRule : TestRule {

        lateinit var manager: PsiEnvironmentManager
            private set

        override fun apply(base: Statement, description: Description): Statement {
            return object : Statement() {
                override fun evaluate() {
                    // TODO(b/302708854): can be back to implicit lambda parameter `it`
                    //  after AGP 8.3.0-alpha08 or later
                    PsiEnvironmentManager().use { m ->
                        manager = m
                        base.evaluate()
                    }
                }
            }
        }
    }

    @Test
    fun `Test merge annotations`() {
        assertEquals(
            """
            Type: PsiClassReferenceType
            Canonical: java.lang.String
            Merged: [@Nullable]
            Printed: java.lang.String?

            Type: PsiArrayType
            Canonical: java.lang.String[]
            Merged: [@Nullable]
            Printed: java.lang.String![]?

            Type: PsiClassReferenceType
            Canonical: java.util.List<java.lang.String>
            Merged: [@Nullable]
            Printed: java.util.List<java.lang.String!>?

            Type: PsiClassReferenceType
            Canonical: java.util.Map<java.lang.String,java.lang.Number>
            Merged: [@Nullable]
            Printed: java.util.Map<java.lang.String!,java.lang.Number!>?

            Type: PsiClassReferenceType
            Canonical: java.lang.Number
            Merged: [@Nullable]
            Printed: java.lang.Number?

            Type: PsiEllipsisType
            Canonical: java.lang.String...
            Merged: [@Nullable]
            Printed: java.lang.String!...
            """
                .trimIndent(),
            prettyPrintTypes(
                    supportTypeUseAnnotations = true,
                    kotlinStyleNulls = true,
                    skip = setOf("int", "long", "void"),
                    extraAnnotations = listOf("@libcore.util.Nullable"),
                    files =
                        listOf(
                            java(
                                """
                        package test.pkg;
                        import java.util.List;
                        import java.util.Map;

                        @SuppressWarnings("ALL")
                        public class MyClass extends Object {
                           public String myPlatformField1;
                           public String[] myPlatformField2;
                           public List<String> getList(Map<String, Number> keys) { return null; }
                           public void method(Number number) { }
                           public void ellipsis(String... args) { }
                        }
                        """
                            ),
                            nullableSource,
                            nonNullSource,
                            libcoreNonNullSource,
                            libcoreNullableSource
                        )
                )
                .trimIndent()
        )
    }

    @Test
    fun `Test kotlin`() {
        assertEquals(
            """
            Type: PsiClassReferenceType
            Canonical: java.lang.String
            Merged: [@org.jetbrains.annotations.NotNull]
            Printed: java.lang.String

            Type: PsiClassReferenceType
            Canonical: java.util.Map<java.lang.String,java.lang.String>
            Merged: [@org.jetbrains.annotations.Nullable]
            Printed: java.util.Map<java.lang.String,java.lang.String>?

            Type: PsiPrimitiveType
            Canonical: void
            Printed: void

            Type: PsiPrimitiveType
            Canonical: int
            Merged: [@org.jetbrains.annotations.NotNull]
            Printed: int

            Type: PsiClassReferenceType
            Canonical: java.lang.Integer
            Merged: [@org.jetbrains.annotations.Nullable]
            Printed: java.lang.Integer?

            Type: PsiEllipsisType
            Canonical: java.lang.String...
            Merged: [@org.jetbrains.annotations.NotNull]
            Printed: java.lang.String!...
            """
                .trimIndent(),
            prettyPrintTypes(
                    supportTypeUseAnnotations = true,
                    kotlinStyleNulls = true,
                    files =
                        listOf(
                            kotlin(
                                """
                        package test.pkg
                        class Foo {
                            val foo1: String = "test1"
                            val foo2: String? = "test1"
                            val foo3: MutableMap<String?, String>? = null
                            fun method1(int: Int = 42,
                                int2: Int? = null,
                                byte: Int = 2 * 21,
                                str: String = "hello " + "world",
                                vararg args: String) { }
                        }
                        """
                            )
                        )
                )
                .trimIndent()
        )
    }

    data class Entry(
        val type: PsiType,
        val elementAnnotations: List<AnnotationItem>?,
        val canonical: String,
        val annotated: String,
        val printed: String
    )

    private fun prettyPrintTypes(
        files: List<TestFile>,
        filter: Predicate<Item>? = null,
        kotlinStyleNulls: Boolean = true,
        supportTypeUseAnnotations: Boolean = true,
        skip: Set<String> = emptySet(),
        include: Set<String> = emptySet(),
        extraAnnotations: List<String> = emptyList()
    ): String {
        val dir = createProject(files.toTypedArray())
        val sourcePath = listOf(File(dir, "src"))

        val sourceFiles = mutableListOf<File>()
        fun addFiles(file: File) {
            if (file.isFile) {
                sourceFiles.add(file)
            } else {
                for (child in file.listFiles()) {
                    addFiles(child)
                }
            }
        }
        addFiles(dir)

        val classPath = mutableListOf<File>()
        val classPathProperty: String = System.getProperty("java.class.path")
        for (path in classPathProperty.split(':')) {
            val file = File(path)
            if (file.isFile) {
                classPath.add(file)
            }
        }
        classPath.add(getAndroidJar())

        val reporter = BasicReporter(PrintWriter(System.err))
        // TestDriver#check normally sets this for all the other tests
        val codebase =
            PsiSourceParser(psiEnvironmentManagerRule.manager, reporter)
                .parseSources(
                    sourceFiles,
                    "test project",
                    sourcePath = sourcePath,
                    classPath = classPath,
                )

        val results = LinkedHashMap<String, Entry>()
        fun handleType(type: PsiType, annotations: List<AnnotationItem> = emptyList()) {
            val key = type.getCanonicalText(true)
            if (results.contains(key)) {
                return
            }
            val canonical = type.getCanonicalText(false)
            if (skip.contains(key) || skip.contains(canonical)) {
                return
            }
            if (include.isNotEmpty() && !(include.contains(key) || include.contains(canonical))) {
                return
            }

            val mapAnnotations = false
            val printer =
                PsiTypePrinter(
                    codebase,
                    filter,
                    mapAnnotations,
                    kotlinStyleNulls,
                    supportTypeUseAnnotations
                )

            var mergeAnnotations: MutableList<AnnotationItem>? = null
            if (extraAnnotations.isNotEmpty()) {
                val list = mutableListOf<AnnotationItem>()
                for (annotation in extraAnnotations) {
                    list.add(codebase.createAnnotation(annotation))
                }
                mergeAnnotations = list
            }
            if (annotations.isNotEmpty()) {
                val list = mutableListOf<AnnotationItem>()
                for (annotation in annotations) {
                    list.add(annotation)
                }
                if (mergeAnnotations == null) {
                    mergeAnnotations = list
                } else {
                    mergeAnnotations.addAll(list)
                }
            }

            val pretty = printer.getAnnotatedCanonicalText(type, mergeAnnotations)
            results[key] = Entry(type, mergeAnnotations, canonical, key, pretty)
        }

        for (unit in codebase.units) {
            unit
                .toUElement()
                ?.accept(
                    object : AbstractUastVisitor() {
                        override fun visitMethod(node: UMethod): Boolean {
                            handle(node.returnType, node.uAnnotations)

                            // Visit all the type elements in the method: this helps us pick up
                            // the type parameter lists for example which contains some interesting
                            // stuff such as type bounds
                            val psi = node.sourcePsi
                            psi?.accept(
                                object : JavaRecursiveElementVisitor() {
                                    override fun visitTypeElement(type: PsiTypeElement) {
                                        handle(type.type, psiAnnotations = type.annotations)
                                        super.visitTypeElement(type)
                                    }
                                }
                            )
                            return super.visitMethod(node)
                        }

                        override fun visitVariable(node: UVariable): Boolean {
                            handle(node.type, node.uAnnotations)
                            return super.visitVariable(node)
                        }

                        private fun handle(
                            type: PsiType?,
                            uastAnnotations: List<UAnnotation> = emptyList(),
                            psiAnnotations: Array<PsiAnnotation> = emptyArray()
                        ) {
                            type ?: return

                            val annotations = mutableListOf<AnnotationItem>()
                            for (annotation in uastAnnotations) {
                                annotations.add(UAnnotationItem.create(codebase, annotation))
                            }
                            for (annotation in psiAnnotations) {
                                annotations.add(PsiAnnotationItem.create(codebase, annotation))
                            }

                            handleType(type, annotations)
                        }

                        override fun visitTypeReferenceExpression(
                            node: UTypeReferenceExpression
                        ): Boolean {
                            handleType(node.type)
                            return super.visitTypeReferenceExpression(node)
                        }
                    }
                )
        }

        val writer = StringWriter()
        val printWriter = PrintWriter(writer)

        results.keys.forEach { key ->
            val cleanKey = key.replace("libcore.util.", "").replace("androidx.annotation.", "")
            val entry = results[key]!!
            val string = entry.printed
            val type = entry.type
            val typeName = type.javaClass.simpleName
            val canonical = entry.canonical
            printWriter.printf("Type: %s\n", typeName)
            printWriter.printf("Canonical: %s\n", canonical)
            if (cleanKey != canonical) {
                printWriter.printf("Annotated: %s\n", cleanKey)
            }
            val elementAnnotations = entry.elementAnnotations
            if (elementAnnotations != null && elementAnnotations.isNotEmpty()) {
                printWriter.printf(
                    "Merged: %s\n",
                    elementAnnotations
                        .toString()
                        .replace("androidx.annotation.", "")
                        .replace("libcore.util.", "")
                )
            }
            printWriter.printf("Printed: %s\n\n", string)
        }

        return writer.toString().removeSuffix("\n\n")
    }
}
