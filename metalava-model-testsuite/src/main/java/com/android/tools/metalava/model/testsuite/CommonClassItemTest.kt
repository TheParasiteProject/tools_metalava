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

import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.testing.java
import com.android.tools.metalava.testing.kotlin
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Common tests for implementations of [ClassItem]. */
@RunWith(Parameterized::class)
class CommonClassItemTest : BaseModelTest() {

    @Test
    fun `empty class`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Test {
                        ctor public Test();
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    public class Test {
                        public Test() {}
                    }
                """
            ),
        ) { codebase ->
            val testClass = codebase.assertClass("test.pkg.Test")
            assertEquals("Test", testClass.fullName())
            assertEquals("test/pkg/Test", testClass.internalName())
            assertEquals("test.pkg.Test", testClass.qualifiedName())
            assertEquals("test.pkg.Test", testClass.qualifiedNameWithDollarInnerClasses())
            assertEquals(1, testClass.constructors().size)
            assertEquals(emptyList(), testClass.methods())
            assertEquals(emptyList(), testClass.fields())
            assertEquals(emptyList(), testClass.properties())
        }
    }

    @Test
    fun `Find method with type parameterized by two types`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo {
                        method public void foo(java.util.Map<String, Integer>);
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    public class Foo {
                        public void foo(java.util.Map<String, Integer> map) {}
                    }
                """
            ),
        ) { codebase ->
            val fooClass = codebase.assertClass("test.pkg.Foo")
            val fooMethod = fooClass.methods().single()

            // This should not find the method as `findMethod` splits parameters by `,` so it looks
            // for one parameter of type `java.util.Map<String` and one of type `Integer>`.
            val foundMethod = fooClass.findMethod("foo", "java.util.Map<String, Integer>")
            assertNull(
                foundMethod,
                message = "unexpectedly found method with multiple type parameters"
            )

            // This should find the method.
            assertSame(fooMethod, fooClass.findMethod("foo", "java.util.Map"))
        }
    }

    @Test
    fun `Test interface no extends list`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public interface Foo {
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    public interface Foo {}
                """
            ),
        ) { codebase ->
            val fooInterface = codebase.assertClass("test.pkg.Foo")

            assertNull(fooInterface.superClassType())
            assertNull(fooInterface.superClass())

            val interfaceList = fooInterface.interfaceTypes().map { it.asClass() }
            assertEquals(emptyList(), interfaceList)

            val allInterfaces = fooInterface.allInterfaces().toList()
            assertEquals(listOf(fooInterface), allInterfaces)
        }
    }

    @Test
    fun `Test interface extends list`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public interface A {
                      }
                      public interface B {
                      }
                      public interface C {
                      }
                      public interface Foo extends test.pkg.A, test.pkg.B, test.pkg.C {
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    public interface A {}
                    public interface B {}
                    public interface C {}
                    public interface Foo extends A, B, C {}
                """
            ),
        ) { codebase ->
            val interfaceA = codebase.assertClass("test.pkg.A")
            val interfaceB = codebase.assertClass("test.pkg.B")
            val interfaceC = codebase.assertClass("test.pkg.C")
            val fooInterface = codebase.assertClass("test.pkg.Foo")

            assertNull(fooInterface.superClassType()?.asClass())
            assertNull(fooInterface.superClass())

            val interfaceList = fooInterface.interfaceTypes().map { it.asClass() }
            assertEquals(listOf(interfaceA, interfaceB, interfaceC), interfaceList)

            val allInterfaces = fooInterface.allInterfaces().toList()
            assertEquals(listOf(fooInterface, interfaceA, interfaceB, interfaceC), allInterfaces)
        }
    }

    @Test
    fun `Test class no super class or implements lists`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo {
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    public class Foo {}
                """
            ),
        ) { codebase ->
            val objectClass = codebase.assertClass("java.lang.Object")
            val fooClass = codebase.assertClass("test.pkg.Foo")

            assertSame(objectClass, fooClass.superClassType()?.asClass())
            assertSame(objectClass, fooClass.superClass())

            val interfaceList = fooClass.interfaceTypes().map { it.asClass() }
            assertEquals(emptyList(), interfaceList)

            val allInterfaces = fooClass.allInterfaces().toList()
            assertEquals(emptyList(), allInterfaces)
        }
    }

    @Test
    fun `Test class super class no implements lists`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Bar {
                      }
                      public class Foo extends test.pkg.Bar {
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    public class Bar {}
                    public class Foo extends Bar {}
                """
            ),
        ) { codebase ->
            val barClass = codebase.assertClass("test.pkg.Bar")
            val fooClass = codebase.assertClass("test.pkg.Foo")

            assertSame(barClass, fooClass.superClassType()?.asClass())
            assertSame(barClass, fooClass.superClass())

            val interfaceList = fooClass.interfaceTypes().map { it.asClass() }
            assertEquals(emptyList(), interfaceList)

            val allInterfaces = fooClass.allInterfaces().toList()
            assertEquals(emptyList(), allInterfaces)
        }
    }

    @Test
    fun `Test class no super class but implements lists`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public interface A {
                      }
                      public interface B {
                      }
                      public interface C {
                      }
                      public class Foo implements test.pkg.A, test.pkg.B, test.pkg.C {
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    public interface A {}
                    public interface B {}
                    public interface C {}
                    public class Foo implements A, B, C {}
                """
            ),
        ) { codebase ->
            val interfaceA = codebase.assertClass("test.pkg.A")
            val interfaceB = codebase.assertClass("test.pkg.B")
            val interfaceC = codebase.assertClass("test.pkg.C")
            val objectClass = codebase.assertClass("java.lang.Object")
            val fooClass = codebase.assertClass("test.pkg.Foo")

            assertSame(objectClass, fooClass.superClassType()?.asClass())
            assertSame(objectClass, fooClass.superClass())

            val interfaceList = fooClass.interfaceTypes().map { it.asClass() }
            assertEquals(listOf(interfaceA, interfaceB, interfaceC), interfaceList)

            val allInterfaces = fooClass.allInterfaces().toList()
            assertEquals(listOf(interfaceA, interfaceB, interfaceC), allInterfaces)
        }
    }

    @Test
    fun `Test class super class and implements lists`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Bar {
                      }
                      public interface A {
                      }
                      public interface B {
                      }
                      public interface C {
                      }
                      public class Foo extends test.pkg.Bar implements test.pkg.A, test.pkg.B, test.pkg.C {
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    public class Bar {}
                    public interface A {}
                    public interface B {}
                    public interface C {}
                    public class Foo extends Bar implements A, B, C {}
                """
            ),
        ) { codebase ->
            val barClass = codebase.assertClass("test.pkg.Bar")
            val interfaceA = codebase.assertClass("test.pkg.A")
            val interfaceB = codebase.assertClass("test.pkg.B")
            val interfaceC = codebase.assertClass("test.pkg.C")
            val fooClass = codebase.assertClass("test.pkg.Foo")

            assertSame(barClass, fooClass.superClassType()?.asClass())
            assertSame(barClass, fooClass.superClass())

            val interfaceList = fooClass.interfaceTypes().map { it.asClass() }
            assertEquals(listOf(interfaceA, interfaceB, interfaceC), interfaceList)

            val allInterfaces = fooClass.allInterfaces().toList()
            assertEquals(listOf(interfaceA, interfaceB, interfaceC), allInterfaces)
        }
    }

    @Test
    fun `Test deprecated class by javadoc tag`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;

                    /**
                     * @deprecated
                     */
                    public class Bar {}
                """
            ),
        ) { codebase ->
            val barClass = codebase.assertClass("test.pkg.Bar")
            assertEquals(true, barClass.deprecated)
            assertEquals(true, barClass.originallyDeprecated)
        }
    }

    @Test
    fun `Test deprecated class by annotation`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      @Deprecated public class Bar {
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    @Deprecated
                    public class Bar {}
                """
            ),
            kotlin(
                """
                    package test.pkg

                    @Deprecated
                    class Bar {}
                """
            ),
        ) { codebase ->
            val barClass = codebase.assertClass("test.pkg.Bar")
            assertEquals(true, barClass.deprecated)
            assertEquals(true, barClass.originallyDeprecated)
        }
    }

    @Test
    fun `Test not deprecated class`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Bar {
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    public class Bar {}
                """
            ),
            kotlin(
                """
                    package test.pkg

                    class Bar {}
                """
            ),
        ) { codebase ->
            val barClass = codebase.assertClass("test.pkg.Bar")
            assertEquals(false, barClass.deprecated)
            assertEquals(false, barClass.originallyDeprecated)
        }
    }
}
