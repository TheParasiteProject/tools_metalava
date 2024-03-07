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

package com.android.tools.metalava.model.testsuite.methoditem

import com.android.tools.metalava.model.testsuite.BaseModelTest
import com.android.tools.metalava.testing.java
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Common tests for implementations of [MethodItem]. */
@RunWith(Parameterized::class)
class CommonMethodItemTest : BaseModelTest() {

    @Test
    fun `MethodItem type`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Test {
                        ctor public Test();
                        method public abstract boolean foo(test.pkg.Test, int...);
                        method public abstract void bar(test.pkg.Test... tests);
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    public abstract class Test {
                        public Test() {}

                        public abstract boolean foo(Test test, int... ints);
                        public abstract void bar(Test... tests);
                    }
                """
            ),
        ) { codebase ->
            val testClass = codebase.assertClass("test.pkg.Test")

            val actual = buildString {
                testClass.methods().forEach {
                    append(it.returnType())
                    append(" ")
                    append(it.name())
                    append("(")
                    it.parameters().forEachIndexed { i, p ->
                        if (i > 0) {
                            append(", ")
                        }
                        append(p.type())
                    }
                    append(")\n")
                }
            }

            assertEquals(
                """
                    boolean foo(test.pkg.Test, int...)
                    void bar(test.pkg.Test...)
                """
                    .trimIndent(),
                actual.trim()
            )
        }
    }

    @Test
    fun `MethodItem superMethods() on constructor`() {
        runCodebaseTest(
            inputSet(
                signature(
                    """
                        // Signature format: 2.0
                        package test.pkg {
                          public class Base {
                            ctor public Base();
                          }
                          public class Test extends test.pkg.Base {
                            ctor public Test();
                          }
                        }
                    """
                ),
            ),
            inputSet(
                java(
                    """
                        package test.pkg;
    
                        public class Base {
                            public Base() {}
                        }
                    """
                ),
                java(
                    """
                        package test.pkg;
    
                        public class Test extends Base {
                            public Test() {}
                        }
                    """
                ),
            ),
        ) { codebase ->
            val testClass = codebase.assertClass("test.pkg.Test")
            val testConstructor = testClass.constructors().single()
            assertEquals(emptyList(), testConstructor.superMethods())
        }
    }

    @Test
    fun `MethodItem superMethods() on simple method`() {
        runCodebaseTest(
            inputSet(
                signature(
                    """
                        // Signature format: 2.0
                        package test.pkg {
                          public class Base {
                            ctor public Base();
                            method public void foo();
                          }
                          public class Test extends test.pkg.Base {
                            ctor public Test();
                            method public void foo();
                          }
                        }
                    """
                ),
            ),
            inputSet(
                java(
                    """
                        package test.pkg;
    
                        public class Base {
                            public Base() {}
                            public void foo() {}
                        }
                    """
                ),
                java(
                    """
                        package test.pkg;
    
                        public class Test extends Base {
                            public Test() {}
                            public void foo() {}
                        }
                    """
                ),
            ),
        ) { codebase ->
            val baseClass = codebase.assertClass("test.pkg.Base")
            val testClass = codebase.assertClass("test.pkg.Test")

            val baseFoo = baseClass.methods().single()
            val testFoo = testClass.methods().single()

            assertEquals(listOf(baseFoo), testFoo.superMethods())
        }
    }
}
