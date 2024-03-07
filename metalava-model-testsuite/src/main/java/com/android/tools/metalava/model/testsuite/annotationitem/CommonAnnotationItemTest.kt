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

package com.android.tools.metalava.model.testsuite.annotationitem

import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.getAttributeValue
import com.android.tools.metalava.model.getAttributeValues
import com.android.tools.metalava.model.testsuite.BaseModelTest
import com.android.tools.metalava.testing.java
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Common tests for implementations of [ClassItem]. */
@RunWith(Parameterized::class)
class CommonAnnotationItemTest : BaseModelTest() {

    @Test
    fun `annotation with annotation values`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      @test.pkg.Test.Anno(
                        annotationValue = @test.pkg.Other("other"),
                        annotationArrayValue = {@test.pkg.Other("other1"), @test.pkg.Other("other2")}
                      )
                      public class Test {
                        ctor public Test();
                      }

                      public @interface Test.Anno {
                          method public Other annotationValue();
                          method public Other[] annotationArrayValue();
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    @Test.Anno(
                      annotationValue = @test.pkg.Other("other"),
                      annotationArrayValue = {@test.pkg.Other("other1"), @test.pkg.Other("other2")}
                    )
                    public class Test {
                        public Test() {}

                        public @interface Anno {
                          Other annotationValue();
                          Other[] annotationArrayValue();
                        }
                    }

                    @interface Other {
                        String value();
                    }
                """
            ),
        ) { codebase ->
            val testClass = codebase.assertClass("test.pkg.Test")
            val anno = testClass.modifiers.annotations().single()

            val other = anno.getAttributeValue<AnnotationItem>("annotationValue")!!
            assertEquals("test.pkg.Other", other.qualifiedName)
            other.assertAttributeValue("value", "other")

            val otherAsList = anno.getAttributeValues<AnnotationItem>("annotationValue")
            assertEquals(listOf(other), otherAsList)

            val others = anno.getAttributeValues<AnnotationItem>("annotationArrayValue")!!
            assertEquals(
                "other1, other2",
                others.mapNotNull { it.getAttributeValue("value") }.joinToString()
            )
        }
    }

    @Test
    fun `annotation with boolean values`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      @test.pkg.Test.Anno(
                          booleanValue = true,
                          booleanArrayValue = {true, false},
                      )
                      public class Test {
                        ctor public Test();
                      }

                      public @interface Test.Anno {
                          method public boolean booleanValue();
                          method public boolean[] booleanArrayValue();
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    @Test.Anno(
                      booleanValue = true,
                      booleanArrayValue = {true, false}
                    )
                    public class Test {
                        public Test() {}

                        public @interface Anno {
                          boolean booleanValue();
                          boolean[] booleanArrayValue();
                        }
                    }
                """
            ),
        ) { codebase ->
            val testClass = codebase.assertClass("test.pkg.Test")
            val anno = testClass.modifiers.annotations().single()

            anno.assertAttributeValue("booleanValue", true)
            anno.assertAttributeValues("booleanValue", listOf(true))
            anno.assertAttributeValues("booleanArrayValue", listOf(true, false))
        }
    }

    @Test
    fun `annotation with char values`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      @test.pkg.Test.Anno(
                          charValue = 'a',
                          charArrayValue = {'a', 'b'},
                      )
                      public class Test {
                        ctor public Test();
                      }

                      public @interface Test.Anno {
                          method public char charValue();
                          method public char[] charArrayValue();
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    @Test.Anno(
                      charValue = 'a',
                      charArrayValue = {'a', 'b'}
                    )
                    public class Test {
                        public Test() {}

                        public @interface Anno {
                          char charValue();
                          char[] charArrayValue();
                        }
                    }
                """
            ),
        ) { codebase ->
            val testClass = codebase.assertClass("test.pkg.Test")
            val anno = testClass.modifiers.annotations().single()

            anno.assertAttributeValue("charValue", 'a')
            anno.assertAttributeValues("charValue", listOf('a'))
            anno.assertAttributeValues("charArrayValue", listOf('a', 'b'))
        }
    }

    @Test
    fun `annotation with class values`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      @test.pkg.Test.Anno(
                          stringValue = "string",
                          stringArrayValue = {"string1", "string2"},

                          classValue = test.pkg.Test,
                          classArrayValue = {test.pkg.Test, Anno}
                      )
                      public class Test {
                        ctor public Test();
                      }

                      public @interface Test.Anno {
                          method public String stringValue();
                          method public String[] stringArrayValue();

                          method public Class<?> classValue();
                          method public Class<?>[] classArrayValue();
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    @Test.Anno(
                      stringValue = "string",
                      stringArrayValue = {"string1", "string2"},

                      classValue = Test.class,
                      classArrayValue = {Test.class, Anno.class}
                    )
                    public class Test {
                        public Test() {}

                        public @interface Anno {
                          String stringValue();
                          String[] stringArrayValue();

                          Class<?> classValue();
                          Class<?>[] classArrayValue();
                        }
                    }
                """
            ),
        ) { codebase ->
            val testClass = codebase.assertClass("test.pkg.Test")
            val anno = testClass.modifiers.annotations().single()

            anno.assertAttributeValue("stringValue", "string")
            anno.assertAttributeValues("stringValue", listOf("string"))
            anno.assertAttributeValues("stringArrayValue", listOf("string1", "string2"))

            // A class value can be retrieved as a string.
            anno.assertAttributeValue("classValue", "test.pkg.Test")
            anno.assertAttributeValues("classValue", listOf("test.pkg.Test"))
            anno.assertAttributeValues("classArrayValue", listOf("test.pkg.Test", "Anno"))
        }
    }

    @Test
    fun `annotation with number values`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      @test.pkg.Test.Anno(
                          byteValue = 1,
                          byteArrayValue = {1, 2},

                          doubleValue = 1.5,
                          doubleArrayValue = {1.5, 2.5},

                          floatValue = 0.5F,
                          floatArrayValue = {0.5F, 1.5F},

                          intValue = 1,
                          intArrayValue = {1, 2, 3},

                          longValue = 2,
                          longArrayValue = {2, 4},

                          shortValue = 3,
                          shortArrayValue = {3, 5},
                      )
                      public class Test {
                        ctor public Test();
                      }

                      public @interface Test.Anno {
                          method public byte byteValue();
                          method public byte[] byteArrayValue();

                          method public double doubleValue();
                          method public double[] doubleArrayValue();

                          method public float floatValue();
                          method public float[] floatArrayValue();

                          method public int intValue();
                          method public int[] intArrayValue();

                          method public long longValue();
                          method public long[] longArrayValue();

                          method public short shortValue();
                          method public short[] shortArrayValue();
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    @Test.Anno(
                      byteValue = 1,
                      byteArrayValue = {1, 2},

                      doubleValue = 1.5,
                      doubleArrayValue = {1.5, 2.5},

                      floatValue = 0.5F,
                      floatArrayValue = {0.5F, 1.5F},

                      intValue = 1,
                      intArrayValue = {1, 2, 3},

                      longValue = 2L,
                      longArrayValue = {2L, 4L},

                      shortValue = 3,
                      shortArrayValue = {3, 5}
                    )
                    public class Test {
                        public Test() {}

                        public @interface Anno {
                          byte byteValue();
                          byte[] byteArrayValue();

                          double doubleValue();
                          double[] doubleArrayValue();

                          float floatValue();
                          float[] floatArrayValue();

                          int intValue();
                          int[] intArrayValue();

                          long longValue();
                          long[] longArrayValue();

                          short shortValue();
                          short[] shortArrayValue();
                        }
                    }
                """
            ),
        ) { codebase ->
            val testClass = codebase.assertClass("test.pkg.Test")
            val anno = testClass.modifiers.annotations().single()

            anno.assertAttributeValue("byteValue", 1.toByte())
            anno.assertAttributeValues("byteValue", byteArrayOf(1).toList())
            anno.assertAttributeValues("byteArrayValue", byteArrayOf(1, 2).toList())

            anno.assertAttributeValue("doubleValue", 1.5)
            anno.assertAttributeValues("doubleValue", listOf(1.5))
            anno.assertAttributeValues("doubleArrayValue", listOf(1.5, 2.5))

            anno.assertAttributeValue("floatValue", 0.5F)
            anno.assertAttributeValues("floatValue", listOf(0.5F))
            anno.assertAttributeValues("floatArrayValue", listOf(0.5F, 1.5F))

            anno.assertAttributeValue("intValue", 1)
            anno.assertAttributeValues("intValue", listOf(1))
            anno.assertAttributeValues("intArrayValue", listOf(1, 2, 3))

            anno.assertAttributeValue("longValue", 2L)
            anno.assertAttributeValues("longValue", listOf(2L))
            anno.assertAttributeValues("longArrayValue", listOf(2L, 4L))

            anno.assertAttributeValue("shortValue", 3.toShort())
            anno.assertAttributeValues("shortValue", listOf(3.toShort()))
            anno.assertAttributeValues("shortArrayValue", shortArrayOf(3, 5).toList())
        }
    }

    @Test
    fun `annotation with string values`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      @test.pkg.Test.Anno(
                          stringValue = "string",
                          stringArrayValue = {"string1", "string2"},
                      )
                      public class Test {
                        ctor public Test();
                      }

                      public @interface Test.Anno {
                          method public String stringValue();
                          method public String[] stringArrayValue();
                      }
                    }
                """
            ),
            java(
                """
                    package test.pkg;

                    @Test.Anno(
                      stringValue = "string",
                      stringArrayValue = {"string1", "string2"}
                    )
                    public class Test {
                        public Test() {}

                        public @interface Anno {
                          String stringValue();
                          String[] stringArrayValue();
                        }
                    }
                """
            ),
        ) { codebase ->
            val testClass = codebase.assertClass("test.pkg.Test")
            val anno = testClass.modifiers.annotations().single()

            anno.assertAttributeValue("stringValue", "string")
            anno.assertAttributeValues("stringValue", listOf("string"))
            anno.assertAttributeValues("stringArrayValue", listOf("string1", "string2"))
        }
    }

    inline fun <reified T : Any> AnnotationItem.assertAttributeValue(
        attributeName: String,
        expected: T
    ) {
        assertEquals(
            expected,
            getAttributeValue(attributeName),
            message = "getAttributeValue($attributeName)"
        )
    }

    inline fun <reified T : Any> AnnotationItem.assertAttributeValues(
        attributeName: String,
        expected: List<T>
    ) {
        assertEquals(
            expected,
            getAttributeValues(attributeName),
            message = "getAttributeValues($attributeName)"
        )
    }
}
