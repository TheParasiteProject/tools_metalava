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

import com.android.tools.metalava.model.ClassTypeItem
import com.android.tools.metalava.model.VariableTypeItem
import com.android.tools.metalava.model.testsuite.BaseModelTest
import com.android.tools.metalava.testing.java
import com.android.tools.metalava.testing.kotlin
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CommonTypeParameterItemTest : BaseModelTest() {
    @Test
    fun `Test typeBounds no extends`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo<T> {}
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<T>
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo<T> {
                        ctor public Foo();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val fooClass = codebase.assertClass("test.pkg.Foo")
            val typeParameter = fooClass.typeParameterList().typeParameters().single()
            assertThat(typeParameter.toSource()).isEqualTo("T")
            val typeBounds = typeParameter.typeBounds()
            assertThat(typeBounds.size).isEqualTo(0)
        }
    }

    @Test
    fun `Test typeBounds extends Comparable`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo<T extends Comparable<T>> {}
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<T: Comparable<T>>
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo<T extends Comparable<T>> {
                        ctor public Foo();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val fooClass = codebase.assertClass("test.pkg.Foo")
            val typeParameter = fooClass.typeParameterList().typeParameters().single()
            val typeBounds = typeParameter.typeBounds()
            assertThat(typeBounds.size).isEqualTo(1)
            val typeBound = typeBounds[0]
            assertThat(typeBound).isInstanceOf(ClassTypeItem::class.java)
            assertThat((typeBound as ClassTypeItem).qualifiedName).isEqualTo("java.lang.Comparable")
        }
    }

    @Test
    fun `Test typeBounds multiple`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo<T extends Object & Comparable<T>> {}
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<T> where T : Object, T : Comparable<T>
                """
            ),
            signature(
                """
                    // Signature format: 3.0
                    package test.pkg {
                      public class Foo<T extends Object & Comparable<T>> {
                        ctor public Foo();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val fooClass = codebase.assertClass("test.pkg.Foo")
            val typeParameter = fooClass.typeParameterList().typeParameters().single()
            val typeBounds = typeParameter.typeBounds()
            assertThat(typeBounds.size).isEqualTo(2)
            val (first, second) = typeBounds
            assertThat(first).isInstanceOf(ClassTypeItem::class.java)
            assertThat((first as ClassTypeItem).qualifiedName).isEqualTo("java.lang.Object")
            assertThat(second).isInstanceOf(ClassTypeItem::class.java)
            assertThat((second as ClassTypeItem).qualifiedName).isEqualTo("java.lang.Comparable")
        }
    }

    @Test
    fun `Test self-referential type parameter`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo<T extends test.pkg.Foo<T>> {
                        method public <T extends test.pkg.Foo<T>> T foo();
                      }
                    }
                """
                    .trimIndent()
            ),
            java(
                """
                    package test.pkg;
                    public class Foo<T extends Foo<T>> {
                        public <T extends Foo<T>> T foo() {}
                    }
                    """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<T : Foo<T>> {
                        fun foo<T : Foo<T>>(): T {}
                    }
                """
            )
        ) { codebase ->
            val clazz = codebase.assertClass("test.pkg.Foo")
            val classTypeParam = clazz.typeParameterList().typeParameters().single()
            val classTypeParamBound = classTypeParam.typeBounds().single()
            assertThat(classTypeParamBound).isInstanceOf(ClassTypeItem::class.java)
            assertThat((classTypeParamBound as ClassTypeItem).qualifiedName)
                .isEqualTo("test.pkg.Foo")
            assertThat(classTypeParamBound.parameters).hasSize(1)
            val classTypeParamBoundParam = classTypeParamBound.parameters.single()
            assertThat(classTypeParamBoundParam).isInstanceOf(VariableTypeItem::class.java)
            assertThat((classTypeParamBoundParam as VariableTypeItem).asTypeParameter)
                .isEqualTo(classTypeParam)

            val method = clazz.methods().single()
            val methodTypeParam = method.typeParameterList().typeParameters().single()
            val methodTypeParamBound = methodTypeParam.typeBounds().single()
            assertThat(methodTypeParamBound).isInstanceOf(ClassTypeItem::class.java)
            assertThat((methodTypeParamBound as ClassTypeItem).qualifiedName)
                .isEqualTo("test.pkg.Foo")
            assertThat(methodTypeParamBound.parameters).hasSize(1)
            val methodTypeParamBoundParam = methodTypeParamBound.parameters.single()
            assertThat(methodTypeParamBoundParam).isInstanceOf(VariableTypeItem::class.java)
            assertThat((methodTypeParamBoundParam as VariableTypeItem).asTypeParameter)
                .isEqualTo(methodTypeParam)
        }
    }

    @Test
    fun `Test type parameters that reference each other`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo<A extends C, B extends A, C> {
                      }
                    }
                """
                    .trimIndent()
            ),
            java(
                """
                    package test.pkg;
                    public class Foo<A extends C, B extends A, C> {}
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<A : C, B : A, C>
                """
            )
        ) { codebase ->
            val typeParams =
                codebase.assertClass("test.pkg.Foo").typeParameterList().typeParameters()
            assertThat(typeParams).hasSize(3)
            val a = typeParams[0]
            val b = typeParams[1]
            val c = typeParams[2]

            // A extends C
            val aBound = a.typeBounds().single()
            assertThat(aBound).isInstanceOf(VariableTypeItem::class.java)
            assertThat((aBound as VariableTypeItem).asTypeParameter).isEqualTo(c)

            // B extends A
            val bBound = b.typeBounds().single()
            assertThat(bBound).isInstanceOf(VariableTypeItem::class.java)
            assertThat((bBound as VariableTypeItem).asTypeParameter).isEqualTo(a)

            // C
            assertThat(c.typeBounds()).isEmpty()
        }
    }

    @Test
    fun `Test method type parameter that references class type parameter`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo<T> {
                        method public <E extends T> void foo();
                      }
                    }
                """
                    .trimIndent()
            ),
            java(
                """
                    package test.pkg;
                    public class Foo<T> {
                        public <E extends T> void foo() {}
                    }
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<T> {
                        fun <E : T> foo() {}
                    }
                """
            )
        ) { codebase ->
            val clazz = codebase.assertClass("test.pkg.Foo")
            val clazzTypeParam = clazz.typeParameterList().typeParameters().single()
            assertThat(clazzTypeParam.toSource()).isEqualTo("T")

            val method = clazz.methods().single()
            val methodTypeParam = method.typeParameterList().typeParameters().single()
            assertThat(methodTypeParam.toSource()).isEqualTo("E extends T")
            val methodTypeParamBound = methodTypeParam.typeBounds().single()
            assertThat(methodTypeParamBound).isInstanceOf(VariableTypeItem::class.java)
            assertThat((methodTypeParamBound as VariableTypeItem).asTypeParameter)
                .isEqualTo(clazzTypeParam)
        }
    }

    @Test
    fun `Test type parameter bounds with multiple class parameters`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    import java.util.Map;
                    public class Foo<T extends Map<Integer, String>> {}
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    package test.pkg {
                      public class Foo<T extends java.util.Map<java.lang.Integer, java.lang.String>> {
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val clazz = codebase.assertClass("test.pkg.Foo")
            val typeParameter = clazz.typeParameterList().typeParameters().single()
            assertThat(typeParameter.isReified()).isFalse()
            // There's an expected space between "java.lang.Integer" and "java.lang.String"
            assertThat(typeParameter.toSource())
                .isEqualTo("T extends java.util.Map<java.lang.Integer, java.lang.String>")
        }
    }

    @Test
    fun `Test reified type parameter`() {
        runCodebaseTest(
            // reified isn't possible from java source
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        inline fun <reified T: List<String>> foo(): T {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    package test.pkg {
                      public final class Foo {
                        ctor public Foo();
                        method public inline <reified T extends java.util.List<? extends java.lang.String>> T foo();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()
            val typeParam = method.typeParameterList().typeParameters().single()
            assertThat(typeParam.isReified()).isTrue()
            assertThat(typeParam.toSource())
                .isEqualTo("reified T extends java.util.List<? extends java.lang.String>")
        }
    }

    @Test
    fun `Test explicit Object bound`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo<T extends Object, U extends Object & Comparable<U>> {}
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    package test.pkg {
                      public final class Foo<T extends java.lang.Object, U extends java.lang.Object & java.lang.Comparable<U>> {
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val clazz = codebase.assertClass("test.pkg.Foo")
            val typeParameters = clazz.typeParameterList().typeParameters()

            val typeParameterT = typeParameters[0]
            assertThat(typeParameterT.isReified()).isFalse()
            val boundsT = typeParameterT.typeBounds()
            assertThat(boundsT).hasSize(1)
            assertThat(boundsT.single().isJavaLangObject()).isTrue()
            assertThat(typeParameterT.toSource()).isEqualTo("T")

            val typeParameterU = typeParameters[1]
            assertThat(typeParameterU.isReified()).isFalse()
            val boundsU = typeParameterU.typeBounds()
            assertThat(boundsU).hasSize(2)
            assertThat(boundsU[0].isJavaLangObject()).isTrue()
            assertThat((boundsU[1] as ClassTypeItem).qualifiedName)
                .isEqualTo("java.lang.Comparable")
            // Since this is not a single object bound, it is still included
            assertThat(typeParameterU.toSource())
                .isEqualTo("U extends java.lang.Object & java.lang.Comparable<U>")
        }
    }
}
