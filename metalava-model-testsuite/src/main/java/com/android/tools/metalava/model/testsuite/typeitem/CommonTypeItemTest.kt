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

package com.android.tools.metalava.model.testsuite.typeitem

import com.android.tools.metalava.model.ArrayTypeItem
import com.android.tools.metalava.model.ClassTypeItem
import com.android.tools.metalava.model.PrimitiveTypeItem
import com.android.tools.metalava.model.VariableTypeItem
import com.android.tools.metalava.model.WildcardTypeItem
import com.android.tools.metalava.model.testsuite.BaseModelTest
import com.android.tools.metalava.testing.java
import com.android.tools.metalava.testing.kotlin
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CommonTypeItemTest : BaseModelTest() {
    @Test
    fun `Test primitive types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public void foo(
                            boolean p0,
                            byte p1,
                            char p2,
                            double p3,
                            float p4,
                            int p5,
                            long p6,
                            short p7
                        ) {}
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun foo(
                            p0: Boolean,
                            p1: Byte,
                            p2: Char,
                            p3: Double,
                            p4: Float,
                            p5: Int,
                            p6: Long,
                            p7: Short
                        ) = Unit
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo {
                        ctor public Foo();
                        method public void foo(boolean, byte, char, double, float, int, long, short);
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()

            val returnType = method.returnType()
            assertThat(returnType).isInstanceOf(PrimitiveTypeItem::class.java)
            assertThat((returnType as PrimitiveTypeItem).kind)
                .isEqualTo(PrimitiveTypeItem.Primitive.VOID)

            val expectedParamTypes =
                listOf(
                    PrimitiveTypeItem.Primitive.BOOLEAN,
                    PrimitiveTypeItem.Primitive.BYTE,
                    PrimitiveTypeItem.Primitive.CHAR,
                    PrimitiveTypeItem.Primitive.DOUBLE,
                    PrimitiveTypeItem.Primitive.FLOAT,
                    PrimitiveTypeItem.Primitive.INT,
                    PrimitiveTypeItem.Primitive.LONG,
                    PrimitiveTypeItem.Primitive.SHORT
                )

            val params = method.parameters().map { it.type() }
            assertThat(params).hasSize(expectedParamTypes.size)
            for ((param, expectedKind) in params.zip(expectedParamTypes)) {
                assertThat(param).isInstanceOf(PrimitiveTypeItem::class.java)
                assertThat((param as PrimitiveTypeItem).kind).isEqualTo(expectedKind)
            }
        }
    }

    @Test
    fun `Test primitive array types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public void foo(
                            int[] p0,
                            char[] p1
                        ) {}
                    }
                """
            ),
            // The Kotlin equivalent can be interpreted with java.lang types instead of primitives
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo {
                        ctor public Foo();
                        method public void foo(int[], char[]);
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()

            val paramTypes = method.parameters().map { it.type() }
            assertThat(paramTypes).hasSize(2)

            // int[]
            val intArray = paramTypes[0]
            assertThat(intArray).isInstanceOf(ArrayTypeItem::class.java)
            val int = (intArray as ArrayTypeItem).componentType
            assertThat(int).isInstanceOf(PrimitiveTypeItem::class.java)
            assertThat((int as PrimitiveTypeItem).kind).isEqualTo(PrimitiveTypeItem.Primitive.INT)
            assertThat(intArray.isVarargs).isFalse()

            // char[]
            val charArray = paramTypes[1]
            assertThat(charArray).isInstanceOf(ArrayTypeItem::class.java)
            val char = (charArray as ArrayTypeItem).componentType
            assertThat(char).isInstanceOf(PrimitiveTypeItem::class.java)
            assertThat((char as PrimitiveTypeItem).kind).isEqualTo(PrimitiveTypeItem.Primitive.CHAR)
            assertThat(charArray.isVarargs).isFalse()
        }
    }

    @Test
    fun `Test primitive vararg types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public void foo(int... p0) {}
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun foo(vararg p0: Int
                        ) = Unit
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo {
                        ctor public Foo();
                        method public void foo(int...);
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()

            val paramTypes = method.parameters().map { it.type() }
            assertThat(paramTypes).hasSize(1)

            // int... / vararg int
            val intArray = paramTypes[0]
            assertThat(intArray).isInstanceOf(ArrayTypeItem::class.java)
            val int = (intArray as ArrayTypeItem).componentType
            assertThat(int).isInstanceOf(PrimitiveTypeItem::class.java)
            assertThat((int as PrimitiveTypeItem).kind).isEqualTo(PrimitiveTypeItem.Primitive.INT)
            assertThat(intArray.isVarargs).isTrue()
        }
    }

    @Test
    fun `Test multidimensional primitive array types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public void foo(
                            int[][] p0,
                            char[]... p1
                        ) {}
                    }
                """
            ),
            // The Kotlin equivalent can be interpreted with java.lang types instead of primitives
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo {
                        ctor public Foo();
                        method public void foo(int[][], char[]...);
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()

            val paramTypes = method.parameters().map { it.type() }
            assertThat(paramTypes).hasSize(2)

            // int[][]
            val intArrayArray = paramTypes[0]
            assertThat(intArrayArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat((intArrayArray as ArrayTypeItem).isVarargs).isFalse()

            val intArray = intArrayArray.componentType
            assertThat(intArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat((intArray as ArrayTypeItem).isVarargs).isFalse()

            val int = intArray.componentType
            assertThat(int).isInstanceOf(PrimitiveTypeItem::class.java)
            assertThat((int as PrimitiveTypeItem).kind).isEqualTo(PrimitiveTypeItem.Primitive.INT)

            // char[]...
            val charArrayArray = paramTypes[1]
            assertThat(charArrayArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat((charArrayArray as ArrayTypeItem).isVarargs).isTrue()

            val charArray = charArrayArray.componentType
            assertThat(charArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat((charArray as ArrayTypeItem).isVarargs).isFalse()

            val char = charArray.componentType
            assertThat(char).isInstanceOf(PrimitiveTypeItem::class.java)
            assertThat((char as PrimitiveTypeItem).kind).isEqualTo(PrimitiveTypeItem.Primitive.CHAR)
        }
    }

    @Test
    fun `Test class array types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public void foo(
                            java.lang.String[] p0,
                            java.lang.String[][] p1,
                            java.lang.String... p2
                        ) {}
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun foo(
                            p0: Array<String>,
                            p1: Array<Array<String>>,
                            vararg p2: String
                        ) = Unit
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo {
                        ctor public Foo();
                        method public void foo(String![]!, String![]![]!, java.lang.String!...);
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()

            val paramTypes = method.parameters().map { it.type() }
            assertThat(paramTypes).hasSize(3)

            // String[] / Array<String>
            val simpleArray = paramTypes[0]
            assertThat(simpleArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat((simpleArray as ArrayTypeItem).componentType.isString()).isTrue()
            assertThat(simpleArray.isVarargs).isFalse()

            // String[][] / Array<Array<String>>
            val twoDimensionalArray = paramTypes[1]
            assertThat(twoDimensionalArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat((twoDimensionalArray as ArrayTypeItem).isVarargs).isFalse()
            val innerArray = twoDimensionalArray.componentType
            assertThat(innerArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat((innerArray as ArrayTypeItem).componentType.isString()).isTrue()
            assertThat(innerArray.isVarargs).isFalse()

            // String... / vararg String
            val varargs = paramTypes[2]
            assertThat(twoDimensionalArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat((varargs as ArrayTypeItem).componentType.isString()).isTrue()
            assertThat(varargs.isVarargs).isTrue()
        }
    }

    @Test
    fun `Test wildcard types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo<T> {
                        public void foo(
                            Foo<?> p0,
                            Foo<? extends java.lang.String> p1,
                            Foo<? super java.lang.String> p2,
                            Foo<? extends java.lang.String[]> p3
                        ) {}
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<T> {
                        fun foo(
                            p0: Foo<*>,
                            p1: Foo<out String>,
                            p2: Foo<in String>,
                            p3: Foo<out Array<String>>
                        ) = Unit
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo<T> {
                        ctor public Foo();
                        method public void foo(test.pkg.Foo<?>!, test.pkg.Foo<? extends java.lang.String>!, test.pkg.Foo<? super java.lang.String>!, test.pkg.Foo<? extends java.lang.String[]>!);
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()

            val wildcardTypes =
                method.parameters().map {
                    val paramType = it.type()
                    assertThat(paramType).isInstanceOf(ClassTypeItem::class.java)
                    assertThat((paramType as ClassTypeItem).parameters).hasSize(1)
                    paramType.parameters.single()
                }
            assertThat(wildcardTypes).hasSize(4)

            // Foo<?> / Foo<*>
            // Unbounded wildcards implicitly have an Object extends bound
            val unboundedWildcard = wildcardTypes[0]
            assertThat(unboundedWildcard).isInstanceOf(WildcardTypeItem::class.java)
            val unboundedExtendsBound = (unboundedWildcard as WildcardTypeItem).extendsBound
            assertThat(unboundedExtendsBound).isNotNull()
            assertThat(unboundedExtendsBound!!.isJavaLangObject()).isTrue()
            assertThat(unboundedWildcard.superBound).isNull()

            // Foo<? extends String> / Foo<out String>
            val extendsBoundWildcard = wildcardTypes[1]
            assertThat(extendsBoundWildcard).isInstanceOf(WildcardTypeItem::class.java)
            val extendsBound = (extendsBoundWildcard as WildcardTypeItem).extendsBound
            assertThat(extendsBound).isNotNull()
            assertThat(extendsBound!!.isString()).isTrue()
            assertThat(extendsBoundWildcard.superBound).isNull()

            // Foo<? super String> / Foo<in String>
            // A super bounded wildcard implicitly has an Object extends bound
            val superBoundWildcard = wildcardTypes[2]
            assertThat(superBoundWildcard).isInstanceOf(WildcardTypeItem::class.java)
            val superExtendsBound = (superBoundWildcard as WildcardTypeItem).extendsBound
            assertThat(superExtendsBound).isNotNull()
            assertThat(superExtendsBound!!.isJavaLangObject()).isTrue()
            val superBound = superBoundWildcard.superBound
            assertThat(superBound).isNotNull()
            assertThat(superBound!!.isString()).isTrue()

            // Foo<? extends java.lang.String[]> / Foo<in Array<String>>
            val arrayExtendsBoundWildcard = wildcardTypes[3]
            assertThat(arrayExtendsBoundWildcard).isInstanceOf(WildcardTypeItem::class.java)
            val arrayExtendsBound = (arrayExtendsBoundWildcard as WildcardTypeItem).extendsBound
            assertThat(arrayExtendsBound).isNotNull()
            assertThat(arrayExtendsBound).isInstanceOf(ArrayTypeItem::class.java)
            assertThat((arrayExtendsBound as ArrayTypeItem).componentType.isString()).isTrue()
        }
    }

    @Test
    fun `Test variable types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo<C> {
                        public <M> void foo(C p0, M p1) {}
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<C> {
                        fun <M> foo(p0: C, p1: M) = Unit
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo<C> {
                        ctor public Foo();
                        method public <M> void foo(C!, M!);
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val clz = codebase.assertClass("test.pkg.Foo")
            val classTypeParam = clz.typeParameterList().typeParameters().single()
            val method = clz.methods().single()
            val methodTypeParam = method.typeParameterList().typeParameters().single()
            val paramTypes = method.parameters().map { it.type() }
            assertThat(paramTypes).hasSize(2)

            val classTypeVariable = paramTypes[0]
            assertThat(classTypeVariable).isInstanceOf(VariableTypeItem::class.java)
            assertThat((classTypeVariable as VariableTypeItem).name).isEqualTo("C")
            assertThat(classTypeVariable.asTypeParameter).isEqualTo(classTypeParam)

            val methodTypeVariable = paramTypes[1]
            assertThat(methodTypeVariable).isInstanceOf(VariableTypeItem::class.java)
            assertThat((methodTypeVariable as VariableTypeItem).name).isEqualTo("M")
            assertThat(methodTypeVariable.asTypeParameter).isEqualTo(methodTypeParam)
        }
    }

    @Test
    fun `Test method return type variable types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo<T> {
                        public T bar1() {}
                        public <A extends java.lang.String> A bar2() {}
                        public <A extends java.lang.String> T bar3() {}
                        public <T extends java.lang.String> T bar4() {}
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<T> {
                        fun bar1(): T {}
                        fun <A: java.lang.String> bar2(): A {}
                        fun <A: java.lang.String> bar3(): T {}
                        fun <T: java.lang.String> bar4(): T {}
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo<T> {
                        method public T bar1();
                        method public <A extends java.lang.String> A bar2();
                        method public <A extends java.lang.String> T bar3();
                        method public <T extends java.lang.String> T bar4();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val foo = codebase.assertClass("test.pkg.Foo")
            val fooTypeParam = foo.typeParameterList().typeParameters().single()

            val bar1 = foo.methods().single { it.name() == "bar1" }
            val bar1Return = bar1.returnType()
            assertThat(bar1Return).isInstanceOf(VariableTypeItem::class.java)
            assertThat((bar1Return as VariableTypeItem).asTypeParameter).isEqualTo(fooTypeParam)

            val bar2 = foo.methods().single { it.name() == "bar2" }
            val bar2TypeParam = bar2.typeParameterList().typeParameters().single()
            val bar2Return = bar2.returnType()
            assertThat(bar2Return).isInstanceOf(VariableTypeItem::class.java)
            assertThat((bar2Return as VariableTypeItem).asTypeParameter).isEqualTo(bar2TypeParam)

            val bar3 = foo.methods().single { it.name() == "bar3" }
            val bar3Return = bar3.returnType()
            assertThat(bar3Return).isInstanceOf(VariableTypeItem::class.java)
            assertThat((bar3Return as VariableTypeItem).asTypeParameter).isEqualTo(fooTypeParam)

            val bar4 = foo.methods().single { it.name() == "bar4" }
            val bar4TypeParam = bar4.typeParameterList().typeParameters().single()
            val bar4Return = bar4.returnType()
            assertThat(bar4Return).isInstanceOf(VariableTypeItem::class.java)
            assertThat((bar4Return as VariableTypeItem).asTypeParameter).isEqualTo(bar4TypeParam)
        }
    }

    @Test
    fun `Test method parameter type variable types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo<T> {
                        public void bar1(T p0) {}
                        public <A extends java.lang.String> void bar2(A p0) {}
                        public <A extends java.lang.String> void bar3(T p0) {}
                        public <T extends java.lang.String> void bar4(T p0) {}
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<T> {
                        fun bar1(p0: T) = Unit
                        fun <A: java.lang.String> bar2(p0: A) = Unit
                        fun <A: java.lang.String> bar3(p0: T) = Unit
                        fun <T: java.lang.String> bar4(p0: T) = Unit
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo<T> {
                        method public void bar1(T p);
                        method public <A extends java.lang.String> void bar2(A p);
                        method public <A extends java.lang.String> void bar3(T p);
                        method public <T extends java.lang.String> void bar4(T p);
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val foo = codebase.assertClass("test.pkg.Foo")
            val fooParam = foo.typeParameterList().typeParameters().single()

            val bar1 = foo.methods().single { it.name() == "bar1" }
            val bar1Param = bar1.parameters().single().type()
            assertThat(bar1Param).isInstanceOf(VariableTypeItem::class.java)
            assertThat((bar1Param as VariableTypeItem).asTypeParameter).isEqualTo(fooParam)

            val bar2 = foo.methods().single { it.name() == "bar2" }
            val bar2TypeParam = bar2.typeParameterList().typeParameters().single()
            val bar2Param = bar2.parameters().single().type()
            assertThat(bar2Param).isInstanceOf(VariableTypeItem::class.java)
            assertThat((bar2Param as VariableTypeItem).asTypeParameter).isEqualTo(bar2TypeParam)

            val bar3 = foo.methods().single { it.name() == "bar3" }
            val bar3Param = bar3.parameters().single().type()
            assertThat(bar3Param).isInstanceOf(VariableTypeItem::class.java)
            assertThat((bar3Param as VariableTypeItem).asTypeParameter).isEqualTo(fooParam)

            val bar4 = foo.methods().single { it.name() == "bar4" }
            val bar4TypeParam = bar4.typeParameterList().typeParameters().single()
            val bar4Param = bar4.parameters().single().type()
            assertThat(bar4Param).isInstanceOf(VariableTypeItem::class.java)
            assertThat((bar4Param as VariableTypeItem).asTypeParameter).isEqualTo(bar4TypeParam)
        }
    }

    @Test
    fun `Test field type variable types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo<T> {
                        public T foo;
                    }
                """
                    .trimIndent()
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<T> {
                        @JvmField val foo: T
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo<T> {
                        field public T foo;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val foo = codebase.assertClass("test.pkg.Foo")
            val fooParam = foo.typeParameterList().typeParameters().single()

            val fieldType = foo.fields().single { it.name() == "foo" }.type()
            assertThat(fieldType).isInstanceOf(VariableTypeItem::class.java)
            assertThat((fieldType as VariableTypeItem).asTypeParameter).isEqualTo(fooParam)
        }
    }

    @Test
    fun `Test property type variable types`() {
        runCodebaseTest(
            // No java equivalent
            kotlin(
                """
                    package test.pkg
                    class Foo<T> {
                        val foo: T
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo<T> {
                        property public T foo;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val foo = codebase.assertClass("test.pkg.Foo")
            val fooParam = foo.typeParameterList().typeParameters().single()

            val propertyType = foo.properties().single { it.name() == "foo" }.type()
            assertThat(propertyType).isInstanceOf(VariableTypeItem::class.java)
            assertThat((propertyType as VariableTypeItem).asTypeParameter).isEqualTo(fooParam)
        }
    }

    @Test
    fun `Test class types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public <T> void foo(
                            java.lang.String p0,
                            java.util.List<java.lang.String> p1,
                            java.util.List<java.lang.String[]> p2,
                            java.util.Map<java.lang.String, Foo> p3
                        ) {}
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun <T> foo(
                            p0: String,
                            p1: List<String>,
                            p2: List<Array<String>>,
                            p3: Map<String, Foo>
                        ) = Unit
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo {
                        ctor public Foo();
                        method public <T> void foo(String!, java.util.List<java.lang.String!>!, java.util.List<java.lang.String![]!>!, java.util.Map<java.lang.String!,test.pkg.Foo!>!);
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()
            val paramTypes = method.parameters().map { it.type() }

            val stringType = paramTypes[0]
            assertThat(stringType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((stringType as ClassTypeItem).qualifiedName).isEqualTo("java.lang.String")
            assertThat(stringType.className).isEqualTo("String")
            assertThat(stringType.parameters).isEmpty()

            // List<String>
            val stringListType = paramTypes[1]
            assertThat(stringListType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((stringListType as ClassTypeItem).qualifiedName).isEqualTo("java.util.List")
            assertThat(stringListType.className).isEqualTo("List")
            assertThat(stringListType.parameters).hasSize(1)
            assertThat(stringListType.parameters.single().isString()).isTrue()

            // List<String[]> / List<Array<String>>
            val arrayListType = paramTypes[2]
            assertThat(arrayListType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((arrayListType as ClassTypeItem).qualifiedName).isEqualTo("java.util.List")
            assertThat(arrayListType.parameters).hasSize(1)
            val arrayType = arrayListType.parameters.single()
            assertThat(arrayType).isInstanceOf(ArrayTypeItem::class.java)
            assertThat((arrayType as ArrayTypeItem).componentType.isString()).isTrue()

            // Map<String, Foo>
            val mapType = paramTypes[3]
            assertThat(mapType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((mapType as ClassTypeItem).qualifiedName).isEqualTo("java.util.Map")
            assertThat(mapType.parameters).hasSize(2)
            val mapKeyType = mapType.parameters.first()
            assertThat(mapKeyType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((mapKeyType as ClassTypeItem).isString()).isTrue()
            val mapValueType = mapType.parameters.last()
            assertThat(mapValueType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((mapValueType as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Foo")
        }
    }

    @Test
    fun `Test inner types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Outer {
                        public class Middle {
                            public class Inner {}
                        }

                        public Outer.Middle.Inner foo() {
                            return new Outer.Middle.Inner();
                        }
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Outer {
                        inner class Middle {
                            inner class Inner
                        }

                        fun foo(): Outer.Middle.Inner {
                            return Outer.Middle.Inner()
                        }
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Outer {
                        ctor public Outer();
                        method public test.pkg.Outer.Middle.Inner foo();
                      }
                      public class Outer.Middle {
                        ctor public Outer.Middle();
                      }
                      public class Outer.Middle.Inner {
                        ctor public Outer.Middle.Inner();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Outer").methods().single()

            // Outer.Middle.Inner
            val innerType = method.returnType()
            assertThat(innerType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((innerType as ClassTypeItem).qualifiedName)
                .isEqualTo("test.pkg.Outer.Middle.Inner")
            assertThat(innerType.className).isEqualTo("Inner")
            assertThat(innerType.parameters).isEmpty()

            val middleType = innerType.outerClassType
            assertThat(middleType).isNotNull()
            assertThat(middleType!!.qualifiedName).isEqualTo("test.pkg.Outer.Middle")
            assertThat(middleType.className).isEqualTo("Middle")
            assertThat(middleType.parameters).isEmpty()

            val outerType = middleType.outerClassType
            assertThat(outerType).isNotNull()
            assertThat(outerType!!.qualifiedName).isEqualTo("test.pkg.Outer")
            assertThat(outerType.className).isEqualTo("Outer")
            assertThat(outerType.parameters).isEmpty()
            assertThat(outerType.outerClassType).isNull()
        }
    }

    @Test
    fun `Test inner types from classpath`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;

                    import java.util.Map;

                    public class Test {
                        public Map.Entry<String,String> foo() {
                            return new Map.Entry<String,String>();
                        }
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg

                    import java.util.Map;

                    class Test {
                        fun foo(): Map.Entry<String,String> {
                            return Map.Entry<String,String>()
                        }
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Test {
                        ctor public Outer();
                        method public java.util.Map.Entry<java.lang.String,java.lang.String> foo();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Test").methods().single()

            // Map.Entry<String,String>
            val innerType = method.returnType()
            assertThat(innerType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((innerType as ClassTypeItem).qualifiedName).isEqualTo("java.util.Map.Entry")
            assertThat(innerType.className).isEqualTo("Entry")

            val outerType = innerType.outerClassType
            assertThat(outerType).isNotNull()
            assertThat(outerType!!.qualifiedName).isEqualTo("java.util.Map")
            assertThat(outerType.className).isEqualTo("Map")
            assertThat(outerType.outerClassType).isNull()
        }
    }

    @Test
    fun `Test inner parameterized types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Outer<O> {
                        public class Inner<I> {
                        }

                        public <P1, P2> Outer<P1>.Inner<P2> foo() {
                            return new Outer<P1>.Inner<P2>();
                        }
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Outer<O> {
                        inner class Inner<I>

                        fun <P1, P2> foo(): Outer<P1>.Inner<P2> {
                            return Outer<P1>.Inner<P2>()
                        }
                    }
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Outer<O> {
                        ctor public Outer();
                        method public <P1, P2> test.pkg.Outer<P1!>.Inner<P2!>! foo();
                      }
                      public class Outer.Inner<I> {
                        ctor public Outer.Inner();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Outer").methods().single()
            val methodTypeParameters = method.typeParameterList().typeParameters()
            assertThat(methodTypeParameters).hasSize(2)
            val p1 = methodTypeParameters[0]
            val p2 = methodTypeParameters[1]

            // Outer<P1>.Inner<P2>
            val innerType = method.returnType()
            assertThat(innerType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((innerType as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Outer.Inner")
            assertThat(innerType.className).isEqualTo("Inner")
            assertThat(innerType.parameters).hasSize(1)
            val innerTypeParameter = innerType.parameters.single()
            assertThat(innerTypeParameter).isInstanceOf(VariableTypeItem::class.java)
            assertThat((innerTypeParameter as VariableTypeItem).name).isEqualTo("P2")
            assertThat(innerTypeParameter.asTypeParameter).isEqualTo(p2)

            val outerType = innerType.outerClassType
            assertThat(outerType).isNotNull()
            assertThat(outerType!!.qualifiedName).isEqualTo("test.pkg.Outer")
            assertThat(outerType.className).isEqualTo("Outer")
            assertThat(outerType.outerClassType).isNull()
            assertThat(outerType.parameters).hasSize(1)
            val outerClassParameter = outerType.parameters.single()
            assertThat(outerClassParameter).isInstanceOf(VariableTypeItem::class.java)
            assertThat((outerClassParameter as VariableTypeItem).name).isEqualTo("P1")
            assertThat(outerClassParameter.asTypeParameter).isEqualTo(p1)
        }
    }

    @Test
    fun `Test superclass and interface types using type variables`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;

                    public class Cache<Query, Result> extends java.util.HashMap<Query,Result> {}

                    public class MyList<E> implements java.util.List<E> {}
                """
                    .trimIndent()
            ),
            kotlin(
                """
                    package test.pkg

                    class Cache<Query, Result> : java.util.HashMap<Query, Result>

                    class MyList<E> : java.util.List<E>
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Cache<Query, Result> extends java.util.HashMap<Query,Result> {
                      }
                      public class MyList<E> implements java.util.List<E> {
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            // Verify that the Cache superclass type uses the Cache type variables
            val cache = codebase.assertClass("test.pkg.Cache")
            val cacheTypeParams = cache.typeParameterList().typeParameters()
            assertThat(cacheTypeParams).hasSize(2)
            val queryParam = cacheTypeParams[0]
            val resultParam = cacheTypeParams[1]

            val cacheSuperclassType = cache.superClassType()
            assertThat(cacheSuperclassType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((cacheSuperclassType as ClassTypeItem).qualifiedName)
                .isEqualTo("java.util.HashMap")
            assertThat(cacheSuperclassType.parameters).hasSize(2)

            val queryVar = cacheSuperclassType.parameters[0]
            assertThat(queryVar).isInstanceOf(VariableTypeItem::class.java)
            assertThat((queryVar as VariableTypeItem).asTypeParameter).isEqualTo(queryParam)

            val resultVar = cacheSuperclassType.parameters[1]
            assertThat(resultVar).isInstanceOf(VariableTypeItem::class.java)
            assertThat((resultVar as VariableTypeItem).asTypeParameter).isEqualTo(resultParam)

            // Verify that the MyList interface type uses the MyList type variable
            val myList = codebase.assertClass("test.pkg.MyList")
            val myListTypeParams = myList.typeParameterList().typeParameters()
            assertThat(myListTypeParams).hasSize(1)
            val eParam = myListTypeParams.single()

            val myListInterfaces = myList.interfaceTypes()
            assertThat(myListInterfaces).hasSize(1)

            val myListInterfaceType = myListInterfaces.single()
            assertThat(myListInterfaceType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((myListInterfaceType as ClassTypeItem).qualifiedName)
                .isEqualTo("java.util.List")
            assertThat(myListInterfaceType.parameters).hasSize(1)

            val eVar = myListInterfaceType.parameters.single()
            assertThat(eVar).isInstanceOf(VariableTypeItem::class.java)
            assertThat((eVar as VariableTypeItem).asTypeParameter).isEqualTo(eParam)
        }
    }

    @Test
    fun `Test array of type with parameter used as type parameter`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo {
                        method public java.util.Collection<java.util.List<java.lang.String>[]> foo();
                      }
                    }
                """
                    .trimIndent()
            ),
            java(
                """
                    package test.pkg;

                    import java.util.Collection;
                    import java.util.List;

                    public class Foo {
                        public Collection<List<String>[]> foo() {}
                    }
                """,
            ),
            kotlin(
                """
                    package test.pkg

                    class Foo {
                        fun foo(): Collection<Array<List<String>>> {}
                    }
                """
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()

            // java.util.Collection<java.util.List<java.lang.String>[]>
            val collectionOfArrayOfStringList = method.returnType()
            assertThat(collectionOfArrayOfStringList).isInstanceOf(ClassTypeItem::class.java)
            assertThat((collectionOfArrayOfStringList as ClassTypeItem).qualifiedName)
                .isEqualTo("java.util.Collection")
            assertThat(collectionOfArrayOfStringList.parameters).hasSize(1)

            // java.util.List<java.lang.String>[]
            val arrayOfStringList = collectionOfArrayOfStringList.parameters.single()
            assertThat(arrayOfStringList).isInstanceOf(ArrayTypeItem::class.java)

            // java.util.List<java.lang.String>
            val stringList = (arrayOfStringList as ArrayTypeItem).componentType
            assertThat(stringList).isInstanceOf(ClassTypeItem::class.java)
            assertThat((stringList as ClassTypeItem).qualifiedName).isEqualTo("java.util.List")
            assertThat(stringList.parameters).hasSize(1)

            // java.lang.String
            val string = stringList.parameters.single()
            assertThat(string.isString()).isTrue()
        }
    }

    @Test
    fun `check TypeItem asClass()`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;

                    import java.util.Map.Entry;

                    public class Test {
                        public int field;

                        public <T extends Comparable> void method(Outer<String> a,Entry<? extends String,T> b,T c,String [] ... d){}
                    }

                    class Outer<P> {}
                """
            ),
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Test {
                        field public int field;
                        method public <T extends java.lang.Comparable> void method(test.pkg.Outer<java.lang.String>,java.util.Map.Entry<? extends java.lang.String,T>,T,java.lang.String[]...);
                      }
                      public class Outer<P> {}
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val classItem = codebase.assertClass("test.pkg.Test")
            val methodItem1 = classItem.methods()[0]

            val fieldTypeClassItem = classItem.assertField("field").type().asClass()
            val parameterTypeClassItem1 = methodItem1.parameters()[0].type().asClass()
            val parameterTypeClassItem2 = methodItem1.parameters()[1].type().asClass()
            val parameterTypeClassItem3 = methodItem1.parameters()[2].type().asClass()
            val parameterTypeClassItem4 = methodItem1.parameters()[3].type().asClass()

            val outerClassItem = codebase.assertClass("test.pkg.Outer")
            val stringClassItem = codebase.assertClass("java.lang.String")
            val entryClassItem = codebase.assertClass("java.util.Map.Entry")
            val comparableClassItem = codebase.assertClass("java.lang.Comparable")

            assertThat(fieldTypeClassItem).isNull()
            assertThat(parameterTypeClassItem1).isEqualTo(outerClassItem)
            assertThat(parameterTypeClassItem2).isEqualTo(entryClassItem)
            assertThat(parameterTypeClassItem3).isEqualTo(comparableClassItem)
            assertThat(parameterTypeClassItem4).isEqualTo(stringClassItem)
        }
    }
}
