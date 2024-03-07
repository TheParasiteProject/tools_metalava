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

import com.android.tools.metalava.model.ClassTypeItem
import com.android.tools.metalava.model.Codebase
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.PrimitiveTypeItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.VariableTypeItem
import com.android.tools.metalava.model.VisibilityLevel
import com.android.tools.metalava.model.psi.PsiItem.Companion.isKotlin
import com.android.tools.metalava.testing.KnownSourceFiles.jetbrainsNullableTypeUseSource
import com.android.tools.metalava.testing.java
import com.android.tools.metalava.testing.kotlin
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PsiModifierItemTest : BasePsiTest() {
    private fun TypeItem.annotationNames(): List<String?> {
        return modifiers.annotations().map { it.qualifiedName }
    }

    private fun Item.annotationNames(): List<String?> {
        return modifiers.annotations().map { it.qualifiedName }
    }

    @Test
    fun `Test type-use annotations from Java and Kotlin source, used by Java and Kotlin source`() {
        val javaUsageSource =
            java(
                """
                package test.pkg;
                public class Foo {
                    public @A int foo1() {}
                    public @A String foo2() {}
                    public @A <T> T foo3() {}
                }
            """
                    .trimIndent()
            )
        val kotlinUsageSource =
            kotlin(
                """
                package test.pkg
                class Foo {
                    fun foo1(): @A Int {}
                    fun foo2(): @A String {}
                    fun <T> foo3(): @A T {}
                }
            """
                    .trimIndent()
            )
        val javaAnnotationSource =
            java(
                """
                package test.pkg;
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
                public @interface A {}
            """
                    .trimIndent()
            )
        val kotlinAnnotationSource =
            kotlin(
                """
                package test.pkg
                @Target(AnnotationTarget.TYPE)
                annotation class A
            """
                    .trimIndent()
            )

        val codebaseTest = { codebase: Codebase ->
            val methods = codebase.assertClass("test.pkg.Foo").methods()
            assertThat(methods).hasSize(3)

            // @test.pkg.A int
            val primitiveMethod = methods[0]
            val primitive = primitiveMethod.returnType()
            assertThat(primitive).isInstanceOf(PrimitiveTypeItem::class.java)
            assertThat(primitive.annotationNames()).containsExactly("test.pkg.A")
            assertThat(primitiveMethod.annotationNames()).isEmpty()

            // @test.pkg.A String
            val stringMethod = methods[1]
            val string = stringMethod.returnType()
            assertThat(string).isInstanceOf(ClassTypeItem::class.java)
            assertThat(string.annotationNames()).containsExactly("test.pkg.A")
            val stringMethodAnnotations = stringMethod.annotationNames()
            if (isKotlin((stringMethod as PsiMethodItem).psiMethod)) {
                // The Kotlin version puts a nullability annotation on the method
                assertThat(stringMethodAnnotations)
                    .containsExactly("org.jetbrains.annotations.NotNull")
            } else {
                assertThat(stringMethodAnnotations).isEmpty()
            }

            // @test.pkg.A T
            val variableMethod = methods[2]
            val variable = variableMethod.returnType()
            val typeParameter = variableMethod.typeParameterList().typeParameters().single()
            assertThat(variable).isInstanceOf(VariableTypeItem::class.java)
            assertThat((variable as VariableTypeItem).asTypeParameter).isEqualTo(typeParameter)
            assertThat(variable.annotationNames()).containsExactly("test.pkg.A")
            assertThat(variableMethod.annotationNames()).isEmpty()
        }

        testCodebase(javaUsageSource, javaAnnotationSource, action = codebaseTest)
        testCodebase(javaUsageSource, kotlinAnnotationSource, action = codebaseTest)
        testCodebase(kotlinUsageSource, javaAnnotationSource, action = codebaseTest)
        testCodebase(kotlinUsageSource, kotlinAnnotationSource, action = codebaseTest)
    }

    @Test
    fun `Test type-use nullability annotation used from Java and Kotlin source`() {
        val javaSource =
            java(
                """
            package test.pkg;
            public class Foo {
                public @org.jetbrains.annotations.Nullable String foo() {}
            }
        """
                    .trimIndent()
            )
        val kotlinSource =
            kotlin(
                """
                package test.pkg
                class Foo {
                    fun foo(): String?
                }
            """
                    .trimIndent()
            )
        val codebaseTest = { codebase: Codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()
            // For now, the nullability annotation needs to be attached to the method.
            assertThat(method.annotationNames())
                .containsExactly("org.jetbrains.annotations.Nullable")
            // Explicit Unit return is required by compiler
            Unit
        }
        testCodebase(javaSource, jetbrainsNullableTypeUseSource, action = codebaseTest)
        testCodebase(kotlinSource, jetbrainsNullableTypeUseSource, action = codebaseTest)
    }

    @Test
    fun `Kotlin implicit internal visibility inheritance`() {
        testCodebase(
            kotlin(
                """
                    open class Base {
                        internal open fun method(): Int = 1
                        internal open val property: Int = 2
                    }

                    class Inherited : Base() {
                        override fun method(): Int = 3
                        override val property = 4
                    }
                """
            )
        ) { codebase ->
            val inherited = codebase.assertClass("Inherited")
            val method = inherited.methods().first { it.name().startsWith("method") }
            val property = inherited.properties().single()

            assertEquals(VisibilityLevel.INTERNAL, method.modifiers.getVisibilityLevel())
            assertEquals(VisibilityLevel.INTERNAL, property.modifiers.getVisibilityLevel())
        }
    }

    @Test
    fun `Kotlin class visibility modifiers`() {
        testCodebase(
            kotlin(
                """
                    internal class Internal
                    public class Public
                    class DefaultPublic
                    abstract class Outer {
                        private class Private
                        protected class Protected
                    }
                """
            )
        ) { codebase ->
            assertTrue(codebase.assertClass("Internal").isInternal)
            assertTrue(codebase.assertClass("Public").isPublic)
            assertTrue(codebase.assertClass("DefaultPublic").isPublic)
            assertTrue(codebase.assertClass("Outer.Private").isPrivate)
            assertTrue(codebase.assertClass("Outer.Protected").isProtected)
        }
    }

    @Test
    fun `Kotlin class abstract and final modifiers`() {
        testCodebase(
            kotlin(
                """
                    abstract class Abstract
                    sealed class Sealed
                    open class Open
                    final class Final
                    class FinalDefault
                    interface Interface
                    annotation class Annotation
                """
            )
        ) { codebase ->
            codebase.assertClass("Abstract").modifiers.let {
                assertTrue(it.isAbstract())
                assertFalse(it.isSealed())
                assertFalse(it.isFinal())
            }

            codebase.assertClass("Sealed").modifiers.let {
                assertTrue(it.isAbstract())
                assertTrue(it.isSealed())
                assertFalse(it.isFinal())
            }

            codebase.assertClass("Open").modifiers.let {
                assertFalse(it.isAbstract())
                assertFalse(it.isFinal())
            }

            codebase.assertClass("Final").modifiers.let {
                assertFalse(it.isAbstract())
                assertTrue(it.isFinal())
            }

            codebase.assertClass("FinalDefault").modifiers.let {
                assertFalse(it.isAbstract())
                assertTrue(it.isFinal())
            }

            codebase.assertClass("Interface").modifiers.let {
                assertTrue(it.isAbstract())
                assertFalse(it.isFinal())
            }

            codebase.assertClass("Annotation").modifiers.let {
                assertTrue(it.isAbstract())
                assertFalse(it.isFinal())
            }
        }
    }

    @Test
    fun `Kotlin class type modifiers`() {
        testCodebase(
            kotlin(
                """
                    inline class Inline(val value: Int)
                    value class Value(val value: Int)
                    data class Data(val data: Int) {
                        companion object {
                            const val DATA = 0
                        }
                    }
                    fun interface FunInterface {
                        fun foo()
                    }
                """
            )
        ) { codebase ->
            assertTrue(codebase.assertClass("Inline").modifiers.isInline())
            assertTrue(codebase.assertClass("Value").modifiers.isValue())
            assertTrue(codebase.assertClass("Data").modifiers.isData())
            assertTrue(codebase.assertClass("Data.Companion").modifiers.isCompanion())
            assertTrue(codebase.assertClass("FunInterface").modifiers.isFunctional())
        }
    }

    @Test
    fun `Kotlin class static modifiers`() {
        testCodebase(
            kotlin(
                """
                    class TopLevel {
                        inner class Inner
                        class Nested
                        interface Interface
                        annotation class Annotation
                        object Object
                    }
                    object Object
                """
            )
        ) { codebase ->
            assertFalse(codebase.assertClass("TopLevel").modifiers.isStatic())
            assertFalse(codebase.assertClass("TopLevel.Inner").modifiers.isStatic())
            assertFalse(codebase.assertClass("Object").modifiers.isStatic())

            assertTrue(codebase.assertClass("TopLevel.Nested").modifiers.isStatic())
            assertTrue(codebase.assertClass("TopLevel.Interface").modifiers.isStatic())
            assertTrue(codebase.assertClass("TopLevel.Annotation").modifiers.isStatic())
            assertTrue(codebase.assertClass("TopLevel.Object").modifiers.isStatic())
        }
    }

    fun `Kotlin vararg parameters`() {
        testCodebase(
            kotlin(
                "Foo.kt",
                """
                    fun varArg(vararg parameter: Int) { TODO() }
                    fun nonVarArg(parameter: Int) { TODO() }
                """
            )
        ) { codebase ->
            val facade = codebase.assertClass("FooKt")
            val varArg = facade.methods().single { it.name() == "varArg" }.parameters().single()
            val nonVarArg =
                facade.methods().single { it.name() == "nonVarArg" }.parameters().single()

            assertTrue(varArg.modifiers.isVarArg())
            assertFalse(nonVarArg.modifiers.isVarArg())
        }
    }
}
