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

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.metalava.model.ArrayTypeItem
import com.android.tools.metalava.model.ClassTypeItem
import com.android.tools.metalava.model.Codebase
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.PrimitiveTypeItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.TypeModifiers
import com.android.tools.metalava.model.TypeNullability.NONNULL
import com.android.tools.metalava.model.TypeNullability.NULLABLE
import com.android.tools.metalava.model.TypeNullability.PLATFORM
import com.android.tools.metalava.model.TypeNullability.UNDEFINED
import com.android.tools.metalava.model.VariableTypeItem
import com.android.tools.metalava.model.WildcardTypeItem
import com.android.tools.metalava.model.isNullnessAnnotation
import com.android.tools.metalava.model.source.SourceLanguage
import com.android.tools.metalava.model.testsuite.BaseModelTest
import com.android.tools.metalava.testing.KnownSourceFiles
import com.android.tools.metalava.testing.java
import com.android.tools.metalava.testing.kotlin
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CommonTypeModifiersTest : BaseModelTest() {
    /**
     * Runs a test where it matters whether nullability is provided by annotations (which it is in
     * [javaSource] and [annotatedSignature]) or kotlin null suffixes (which it is in [kotlinSource]
     * and [kotlinNullsSignature]).
     *
     * Runs [test] for the nullability-through-annotations inputs with `true` as the boolean
     * parameter, and runs [test] for the nullability-through-suffixes inputs with `false` as the
     * boolean parameter.
     */
    private fun runNullabilityTest(
        javaSource: TestFile,
        annotatedSignature: TestFile,
        kotlinSource: TestFile,
        kotlinNullsSignature: TestFile,
        test: (Codebase, Boolean) -> Unit
    ) {
        runCodebaseTest(
            inputSet(
                javaSource,
                KnownSourceFiles.libcoreNullableSource,
                KnownSourceFiles.libcoreNonNullSource
            ),
            inputSet(annotatedSignature)
        ) {
            test(it, true)
        }

        runCodebaseTest(kotlinSource, kotlinNullsSignature) { test(it, false) }
    }

    private fun assertNonNull(type: TypeItem, expectAnnotation: Boolean) {
        assertThat(type.modifiers.nullability()).isEqualTo(NONNULL)
        if (expectAnnotation) {
            assertThat(type.modifiers.annotations().single().isNonNull()).isTrue()
        } else {
            assertThat(type.modifiers.annotations().isEmpty())
        }
    }

    private fun assertNullable(type: TypeItem, expectAnnotation: Boolean) {
        assertThat(type.modifiers.nullability()).isEqualTo(NULLABLE)
        if (expectAnnotation) {
            assertThat(type.modifiers.annotations().single().isNullable()).isTrue()
        } else {
            assertThat(type.modifiers.annotations().isEmpty())
        }
    }

    private fun assertPlatform(type: TypeItem) {
        assertThat(type.modifiers.nullability()).isEqualTo(PLATFORM)
    }

    private fun assertUndefinedNullness(type: TypeItem) {
        assertThat(type.modifiers.nullability()).isEqualTo(UNDEFINED)
    }

    private fun TypeItem.annotationNames(): List<String?> {
        return modifiers.annotations().map { it.qualifiedName }
    }

    private fun Item.annotationNames(): List<String?> {
        return modifiers.annotations().map { it.qualifiedName }
    }

    @Test
    fun `Test annotation on basic types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public @A int foo1() {}
                        public @A String foo2() {}
                        public <T> @A T foo3() {}
                    }
                    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)
                    public @interface A {}
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun foo1(): @A Int {}
                        fun foo2(): @A String {}
                        fun <T> foo3(): @A T {}
                    }
                    @Target(AnnotationTarget.TYPE)
                    annotation class A
                """
            ),
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo {
                        method public @test.pkg.A int foo1();
                        method public @test.pkg.A String foo2();
                        method public <T> @test.pkg.A T foo3();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
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
            // The Kotlin version puts a nullability annotation on the method
            if (stringMethodAnnotations.isNotEmpty()) {
                assertThat(stringMethodAnnotations)
                    .containsExactly("org.jetbrains.annotations.NotNull")
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
    }

    @Test
    fun `Test type-use annotations with multiple allowed targets`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public @A int foo1() {}
                        public @A String foo2() {}
                        public @A <T> T foo3() {}
                    }
                    @java.lang.annotation.Target({ java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.TYPE_USE })
                    public @interface A {}
                """
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        @A fun foo(): @A Int {}
                        @A fun foo(): @A String {}
                        @A fun <T> foo(): @A T {}
                    }
                    @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
                    annotation class A
                """
            ),
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo {
                        method @test.pkg.A public @test.pkg.A int foo1();
                        method @test.pkg.A public @test.pkg.A String foo2();
                        method @test.pkg.A public <T> @test.pkg.A T foo3();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val methods = codebase.assertClass("test.pkg.Foo").methods()
            assertThat(methods).hasSize(3)

            // @test.pkg.A int
            val primitiveMethod = methods[0]
            val primitive = primitiveMethod.returnType()
            assertThat(primitive).isInstanceOf(PrimitiveTypeItem::class.java)
            assertThat(primitive.annotationNames()).containsExactly("test.pkg.A")
            assertThat(primitiveMethod.annotationNames()).containsExactly("test.pkg.A")

            // @test.pkg.A String
            val stringMethod = methods[1]
            val string = stringMethod.returnType()
            assertThat(string).isInstanceOf(ClassTypeItem::class.java)
            assertThat(string.annotationNames()).containsExactly("test.pkg.A")
            // The Kotlin version puts a nullability annotation on the method
            val stringMethodAnnotations =
                stringMethod.annotationNames().filter { !isNullnessAnnotation(it.orEmpty()) }
            assertThat(stringMethodAnnotations).containsExactly("test.pkg.A")

            // @test.pkg.A T
            val variableMethod = methods[2]
            val variable = variableMethod.returnType()
            val typeParameter = variableMethod.typeParameterList().typeParameters().single()
            assertThat(variable).isInstanceOf(VariableTypeItem::class.java)
            assertThat((variable as VariableTypeItem).asTypeParameter).isEqualTo(typeParameter)
            assertThat(variable.annotationNames()).containsExactly("test.pkg.A")
            assertThat(variableMethod.annotationNames()).containsExactly("test.pkg.A")
        }
    }

    @Test
    fun `Test kotlin type-use annotations with multiple allowed targets on non-type target`() {
        runCodebaseTest(
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        // @A can be applied to a function or type.
                        // Because of the positioning, it should apply to the function here.
                        @A fun foo(): Int {}
                        @A fun foo(): String {}
                        @A fun <T> foo(): T {}
                    }
                    @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
                    annotation class A
                """
            )
        ) { codebase ->
            val methods = codebase.assertClass("test.pkg.Foo").methods()
            assertThat(methods).hasSize(3)

            val primitiveMethod = methods[0]
            val primitive = primitiveMethod.returnType()
            assertThat(primitive).isInstanceOf(PrimitiveTypeItem::class.java)
            assertThat(primitive.annotationNames()).isEmpty()
            assertThat(primitiveMethod.annotationNames()).containsExactly("test.pkg.A")

            val stringMethod = methods[1]
            val string = stringMethod.returnType()
            assertThat(string).isInstanceOf(ClassTypeItem::class.java)
            assertThat(string.annotationNames()).isEmpty()
            assertThat(stringMethod.annotationNames())
                .containsExactly("org.jetbrains.annotations.NotNull", "test.pkg.A")

            val variableMethod = methods[2]
            val variable = variableMethod.returnType()
            val typeParameter = variableMethod.typeParameterList().typeParameters().single()
            assertThat(variable).isInstanceOf(VariableTypeItem::class.java)
            assertThat((variable as VariableTypeItem).asTypeParameter).isEqualTo(typeParameter)
            assertThat(variable.annotationNames()).isEmpty()
            assertThat(variableMethod.annotationNames()).containsExactly("test.pkg.A")
        }
    }

    @Test
    fun `Test filtering of annotations based on target usages`() {
        runCodebaseTest(
            java(
                """
                package test.pkg;
                public class Foo {
                    public @A String bar(@A int arg) {}
                    public @A String baz;
                }

                @java.lang.annotation.Target({ java.lang.annotation.ElementType.TYPE_USE, java.lang.annotation.ElementType.PARAMETER })
                public @interface A {}
            """
                    .trimIndent()
            )
        ) { codebase ->
            val fooClass = codebase.assertClass("test.pkg.Foo")

            // @A is TYPE_USE and PARAMETER, so it should not appear on the method
            val method = fooClass.methods().single()
            assertThat(method.annotationNames()).isEmpty()
            val methodReturn = method.returnType()
            assertThat(methodReturn.annotationNames()).containsExactly("test.pkg.A")

            // @A is TYPE_USE and PARAMETER, so it should appear on the parameter as well as type
            val methodParam = method.parameters().single()
            assertThat(methodParam.annotationNames()).containsExactly("test.pkg.A")
            val methodParamType = methodParam.type()
            assertThat(methodParamType.annotationNames()).containsExactly("test.pkg.A")

            // @A is TYPE_USE and PARAMETER, so it should not appear on the field
            val field = fooClass.fields().single()
            assertThat(field.annotationNames()).isEmpty()
            val fieldType = field.type()
            assertThat(fieldType.annotationNames()).containsExactly("test.pkg.A")
        }
    }

    @Test
    fun `Test annotations on qualified class type`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public test.pkg.@test.pkg.A Foo foo() {}
                    }
                """
            ),
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo {
                        method public test.pkg.@test.pkg.A Foo foo();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()
            assertThat(method.annotationNames()).isEmpty()

            val returnType = method.returnType()
            assertThat(returnType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((returnType as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Foo")
            assertThat(returnType.annotationNames()).containsExactly("test.pkg.A")
        }
    }

    @Test
    fun `Test annotations on class type parameters`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;

                    public class Foo {
                        public java.util.@test.pkg.A Map<java.lang.@test.pkg.B @test.pkg.C String, java.lang.@test.pkg.D String> foo() {}
                    }
                """
            ),
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo {
                        method public java.util.@test.pkg.A Map<java.lang.@test.pkg.B @test.pkg.C String, java.lang.@test.pkg.D String> foo();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()
            assertThat(method.annotationNames()).isEmpty()

            val mapType = method.returnType()
            assertThat(mapType).isInstanceOf(ClassTypeItem::class.java)
            assertThat(mapType.annotationNames()).containsExactly("test.pkg.A")
            assertThat((mapType as ClassTypeItem).parameters).hasSize(2)

            // java.lang.@test.pkg.B @test.pkg.C String
            val string1 = mapType.parameters[0]
            assertThat(string1.isString()).isTrue()
            assertThat(string1.annotationNames()).containsExactly("test.pkg.B", "test.pkg.C")

            // java.lang.@test.pkg.D String
            val string2 = mapType.parameters[1]
            assertThat(string2.isString()).isTrue()
            assertThat(string2.annotationNames()).containsExactly("test.pkg.D")
        }
    }

    @Test
    fun `Test annotations on array type and component type`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public test.pkg.@test.pkg.A @test.pkg.B Foo @test.pkg.B @test.pkg.C [] foo() {}
                    }
                """
            ),
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo {
                        method public test.pkg.@test.pkg.A @test.pkg.B Foo @test.pkg.B @test.pkg.C [] foo();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()
            assertThat(method.annotationNames()).isEmpty()

            val arrayType = method.returnType()
            assertThat(arrayType).isInstanceOf(ArrayTypeItem::class.java)
            assertThat(arrayType.annotationNames()).containsExactly("test.pkg.B", "test.pkg.C")

            val componentType = (arrayType as ArrayTypeItem).componentType
            assertThat(componentType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((componentType as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Foo")
            assertThat(componentType.annotationNames()).containsExactly("test.pkg.A", "test.pkg.B")
        }
    }

    @Test
    fun `Test leading annotation on array type`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public <T> @test.pkg.A T[] foo() {}
                    }
                """
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - kotlin-name-type-order=yes
                    // - include-type-use-annotations=yes
                    package test.pkg {
                      public class Foo {
                        method public <T> foo(): @test.pkg.A T[];
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()
            val methodTypeParam = method.typeParameterList().typeParameters().single()
            val arrayType = method.returnType()
            assertThat(arrayType).isInstanceOf(ArrayTypeItem::class.java)
            val componentType = (arrayType as ArrayTypeItem).componentType
            assertThat(componentType).isInstanceOf(VariableTypeItem::class.java)
            assertThat((componentType as VariableTypeItem).asTypeParameter)
                .isEqualTo(methodTypeParam)
            assertThat(componentType.annotationNames()).containsExactly("test.pkg.A")
        }
    }

    @Test
    fun `Test annotations on multidimensional array`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public test.pkg.@test.pkg.A Foo @test.pkg.B [] @test.pkg.C [] @test.pkg.D [] foo() {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 2.0
                    package test.pkg {
                      public class Foo {
                        method public test.pkg.@test.pkg.A Foo @test.pkg.B [] @test.pkg.C [] @test.pkg.D [] foo();
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val method = codebase.assertClass("test.pkg.Foo").methods().single()
            assertThat(method.annotationNames()).isEmpty()

            val outerArray = method.returnType()
            assertThat(outerArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat(outerArray.annotationNames()).containsExactly("test.pkg.B")

            val middleArray = (outerArray as ArrayTypeItem).componentType
            assertThat(middleArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat(middleArray.annotationNames()).containsExactly("test.pkg.C")

            val innerArray = (middleArray as ArrayTypeItem).componentType
            assertThat(innerArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat(innerArray.annotationNames()).containsExactly("test.pkg.D")

            val componentType = (innerArray as ArrayTypeItem).componentType
            assertThat(componentType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((componentType as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Foo")
            assertThat(componentType.annotationNames()).containsExactly("test.pkg.A")
        }
    }

    @Test
    fun `Test annotations on multidimensional vararg array`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public void foo(test.pkg.@test.pkg.A Foo @test.pkg.B [] @test.pkg.C [] @test.pkg.D ... arg) {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 4.0
                    package test.pkg {
                      public class Foo {
                        method public void foo(test.pkg.@test.pkg.A Foo @test.pkg.B [] @test.pkg.C [] @test.pkg.D ...);
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val outerArray =
                codebase.assertClass("test.pkg.Foo").methods().single().parameters().single().type()
            assertThat(outerArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat((outerArray as ArrayTypeItem).isVarargs).isTrue()
            assertThat(outerArray.annotationNames()).containsExactly("test.pkg.B")

            val middleArray = outerArray.componentType
            assertThat(middleArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat(middleArray.annotationNames()).containsExactly("test.pkg.C")

            val innerArray = (middleArray as ArrayTypeItem).componentType
            assertThat(innerArray).isInstanceOf(ArrayTypeItem::class.java)
            assertThat(innerArray.annotationNames()).containsExactly("test.pkg.D")

            val componentType = (innerArray as ArrayTypeItem).componentType
            assertThat(componentType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((componentType as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Foo")
            assertThat(componentType.annotationNames()).containsExactly("test.pkg.A")
        }
    }

    @Test
    fun `Test inner parameterized types with annotations`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Outer<O> {
                        public class Inner<I> {
                        }

                        public <P1, P2> test.pkg.@test.pkg.A Outer<@test.pkg.B P1>.@test.pkg.C Inner<@test.pkg.D P2> foo() {
                            return new Outer<P1>.Inner<P2>();
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
                        method public <P1, P2> test.pkg.@test.pkg.A Outer<@test.pkg.B P1!>.@test.pkg.C Inner<@test.pkg.D P2!>! foo();
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
            assertThat(innerType.parameters).hasSize(1)
            assertThat(innerType.annotationNames()).containsExactly("test.pkg.C")

            val innerTypeParameter = innerType.parameters.single()
            assertThat(innerTypeParameter).isInstanceOf(VariableTypeItem::class.java)
            assertThat((innerTypeParameter as VariableTypeItem).name).isEqualTo("P2")
            assertThat(innerTypeParameter.asTypeParameter).isEqualTo(p2)
            assertThat(innerTypeParameter.annotationNames()).containsExactly("test.pkg.D")

            val outerType = innerType.outerClassType
            assertThat(outerType).isNotNull()
            assertThat(outerType!!.qualifiedName).isEqualTo("test.pkg.Outer")
            assertThat(outerType.outerClassType).isNull()
            assertThat(outerType.parameters).hasSize(1)
            assertThat(outerType.annotationNames()).containsExactly("test.pkg.A")

            val outerClassParameter = outerType.parameters.single()
            assertThat(outerClassParameter).isInstanceOf(VariableTypeItem::class.java)
            assertThat((outerClassParameter as VariableTypeItem).name).isEqualTo("P1")
            assertThat(outerClassParameter.asTypeParameter).isEqualTo(p1)
            assertThat(outerClassParameter.annotationNames()).containsExactly("test.pkg.B")
        }
    }

    @Test
    fun `Test interface types`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo implements test.pkg.@test.pkg.A Bar, test.pkg.Baz {}
                """
            ),
            signature(
                """
                    // Signature format: 4.0
                    package test.pkg {
                      public class Foo implements test.pkg.@test.pkg.A Bar, test.pkg.Baz {
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val foo = codebase.assertClass("test.pkg.Foo")
            val interfaces = foo.interfaceTypes()
            assertThat(interfaces).hasSize(2)

            val bar = interfaces[0]
            assertThat(bar).isInstanceOf(ClassTypeItem::class.java)
            assertThat((bar as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Bar")
            val annotations = bar.modifiers.annotations()
            assertThat(annotations).hasSize(1)
            assertThat(annotations.single().qualifiedName).isEqualTo("test.pkg.A")

            val baz = interfaces[1]
            assertThat(baz).isInstanceOf(ClassTypeItem::class.java)
            assertThat((baz as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Baz")
        }
    }

    @Test
    fun `Test super class type`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo extends test.pkg.@test.pkg.A Bar {}
                    class Bar {}
                    @interface A {}
                """
            ),
            signature(
                """
                    // Signature format: 4.0
                    package test.pkg {
                      public class Foo extends test.pkg.@test.pkg.A Bar {
                      }
                      public class Bar {
                      }
                      public @interface A {
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val foo = codebase.assertClass("test.pkg.Foo")
            val superClass = foo.superClassType()
            assertThat(superClass).isNotNull()
            assertThat(superClass).isInstanceOf(ClassTypeItem::class.java)
            assertThat((superClass as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Bar")
            assertThat(superClass.annotationNames()).containsExactly("test.pkg.A")
        }
    }

    @Test
    fun `Test super class and interface types of interface`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public interface Foo extends test.pkg.@test.pkg.A Bar, test.pkg.@test.pkg.B Baz<@test.pkg.C String>, test.pkg.Biz {}
                """
            ),
            signature(
                """
                    // Signature format: 4.0
                    package test.pkg {
                      public interface Foo extends test.pkg.@test.pkg.A Bar test.pkg.@test.pkg.B Baz<@test.pkg.C String> test.pkg.Biz {
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val foo = codebase.assertClass("test.pkg.Foo")
            assertThat(foo.superClassType()).isNull()

            val interfaces = foo.interfaceTypes()
            assertThat(interfaces).hasSize(3)

            val bar = interfaces[0]
            assertThat(bar).isInstanceOf(ClassTypeItem::class.java)
            assertThat((bar as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Bar")
            assertThat(bar.annotationNames()).containsExactly("test.pkg.A")

            val baz = interfaces[1]
            assertThat(baz).isInstanceOf(ClassTypeItem::class.java)
            assertThat((baz as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Baz")
            assertThat(baz.parameters).hasSize(1)
            assertThat(baz.annotationNames()).containsExactly("test.pkg.B")

            val bazParam = baz.parameters.single()
            assertThat(bazParam.isString()).isTrue()
            assertThat(bazParam.annotationNames()).containsExactly("test.pkg.C")

            val biz = interfaces[2]
            assertThat(biz).isInstanceOf(ClassTypeItem::class.java)
            assertThat((biz as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Biz")
            assertThat(biz.annotationNames()).isEmpty()
        }
    }

    @Test
    fun `Test annotated array types in multiple contexts`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public test.pkg.Foo @test.pkg.A [] method(test.pkg.Foo @test.pkg.A [] arg) {}
                        public test.pkg.Foo @test.pkg.A [] field;
                    }
                """
            ),
            signature(
                """
                    // Signature format: 4.0
                    package test.pkg {
                      public class Foo {
                        method public test.pkg.Foo @test.pkg.A [] method(test.pkg.Foo @test.pkg.A []);
                        field public test.pkg.Foo @test.pkg.A [] field;
                        property public test.pkg.Foo @test.pkg.A [] prop;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val foo = codebase.assertClass("test.pkg.Foo")
            val method = foo.methods().single()
            val returnType = method.returnType()
            val paramType = method.parameters().single().type()
            val fieldType = foo.fields().single().type()
            // Properties can't be defined in java, this is only present for signature type
            val propertyType = foo.properties().singleOrNull()?.type()

            // Do full check for one type, then verify the others are equal
            assertThat(returnType).isInstanceOf(ArrayTypeItem::class.java)
            assertThat(returnType.annotationNames()).containsExactly("test.pkg.A")
            val componentType = (returnType as ArrayTypeItem).componentType
            assertThat(componentType).isInstanceOf(ClassTypeItem::class.java)
            assertThat((componentType as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Foo")
            assertThat(componentType.annotationNames()).isEmpty()

            assertThat(returnType).isEqualTo(paramType)
            assertThat(returnType).isEqualTo(fieldType)
            if (propertyType != null) {
                assertThat(returnType).isEqualTo(propertyType)
            }
        }
    }

    @Test
    fun `Test annotations with spaces in the annotation string`() {
        runCodebaseTest(
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    package test.pkg {
                      public class Foo extends test.pkg.@test.pkg.A(a=1, b=2, c=3) Bar implements test.pkg.@test.pkg.A(a=1, b=2, c=3) Baz test.pkg.@test.pkg.A(a=1, b=2, c=3) Biz {
                        method public <T> foo(_: @test.pkg.A(a=1, b=2, c=3) T @test.pkg.A(a=1, b=2, c=3) []): java.util.@test.pkg.A(a=1, b=2, c=3) List<java.lang.@test.pkg.A(a=1, b=2, c=3) String>;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            // Check the modifiers contain one annotation, `@test.pkg.A(a=1, b=2, c=3)`
            val testModifiers = { modifiers: TypeModifiers ->
                assertThat(modifiers.annotations()).hasSize(1)
                val annotation = modifiers.annotations().single()
                assertThat(annotation.qualifiedName).isEqualTo("test.pkg.A")
                val attributes = annotation.attributes
                assertThat(attributes.toString()).isEqualTo("[a=1, b=2, c=3]")
            }
            val fooClass = codebase.assertClass("test.pkg.Foo")

            val superClass = fooClass.superClassType()
            assertThat((superClass as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Bar")
            testModifiers(superClass.modifiers)

            val interfaces = fooClass.interfaceTypes()
            val bazInterface = interfaces[0]
            assertThat((bazInterface as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Baz")
            testModifiers(bazInterface.modifiers)
            val bizInterface = interfaces[1]
            assertThat((bizInterface as ClassTypeItem).qualifiedName).isEqualTo("test.pkg.Biz")
            testModifiers(bizInterface.modifiers)

            val fooMethod = fooClass.methods().single()
            val typeParam = fooMethod.typeParameterList().typeParameters().single()

            val typeVarArray = fooMethod.parameters().single().type()
            testModifiers(typeVarArray.modifiers)
            val typeVar = (typeVarArray as ArrayTypeItem).componentType
            assertThat((typeVar as VariableTypeItem).asTypeParameter).isEqualTo(typeParam)
            testModifiers(typeVar.modifiers)

            val stringList = fooMethod.returnType()
            assertThat((stringList as ClassTypeItem).qualifiedName).isEqualTo("java.util.List")
            testModifiers(stringList.modifiers)
            val string = stringList.parameters.single()
            assertThat(string.isString()).isTrue()
            testModifiers(string.modifiers)
        }
    }

    @Test
    fun `Test adding and removing annotations`() {
        // Not supported for text codebases due to caching
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Target;
                    public class Foo {
                        public @A @B String foo() {}
                    }
                    @Target(ElementType.TYPE_USE)
                    public @interface A {}
                    @Target(ElementType.TYPE_USE)
                    public @interface B {}
                """
                    .trimIndent()
            ),
        ) { codebase ->
            val stringType = codebase.assertClass("test.pkg.Foo").methods().single().returnType()
            assertThat(stringType.annotationNames()).containsExactly("test.pkg.A", "test.pkg.B")

            // Remove annotation
            val annotationA = stringType.modifiers.annotations().first()
            assertThat(annotationA.qualifiedName).isEqualTo("test.pkg.A")
            stringType.modifiers.removeAnnotation(annotationA)
            assertThat(stringType.annotationNames()).containsExactly("test.pkg.B")

            // Add annotation
            stringType.modifiers.addAnnotation(annotationA)
            assertThat(stringType.annotationNames()).containsExactly("test.pkg.B", "test.pkg.A")
        }
    }

    @Test
    fun `Test nullability of primitives`() {
        runNullabilityTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        public int foo() {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    // - kotlin-style-nulls=no
                    package test.pkg {
                      public class Foo {
                        method public foo(): int;
                      }
                    }
                """
                    .trimIndent()
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun foo(): Int {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    package test.pkg {
                      public class Foo {
                        method public foo(): int;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase, _ ->
            val primitive = codebase.assertClass("test.pkg.Foo").methods().single().returnType()
            // Primitives are always non-null without an annotation needed
            assertNonNull(primitive, expectAnnotation = false)
        }
    }

    @Test
    fun `Test nullability of simple classes`() {
        runNullabilityTest(
            java(
                """
                    package test.pkg;
                    import libcore.util.NonNull;
                    import libcore.util.Nullable;
                    public class Foo {
                        public String platformString() {}
                        public @Nullable String nullableString() {}
                        public @NonNull String nonNullString() {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    // - kotlin-style-nulls=no
                    package test.pkg {
                      public class Foo {
                        method public platformString(): String;
                        method public nullableString(): @libcore.util.Nullable String;
                        method public nonNullString(): @libcore.util.NonNull String;
                      }
                    }
                """
                    .trimIndent()
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun nullableString(): String? {}
                        fun nonNullString(): String {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    package test.pkg {
                      public class Foo {
                        method public platformString(): String!;
                        method public nullableString(): String?;
                        method public nonNullString(): String;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase: Codebase, annotations: Boolean ->
            val fooClass = codebase.assertClass("test.pkg.Foo")

            // Platform nullability isn't possible from Kotlin
            if (inputFormat.sourceLanguage != SourceLanguage.KOTLIN) {
                val platformString = fooClass.assertMethod("platformString", "").returnType()
                assertThat(platformString.modifiers.nullability()).isEqualTo(PLATFORM)
            }

            val nullableString = fooClass.assertMethod("nullableString", "").returnType()
            assertNullable(nullableString, annotations)

            val nonNullString = fooClass.assertMethod("nonNullString", "").returnType()
            assertNonNull(nonNullString, annotations)
        }
    }

    @Test
    fun `Test nullability of arrays`() {
        runNullabilityTest(
            java(
                """
                    package test.pkg;
                    import libcore.util.NonNull;
                    import libcore.util.Nullable;
                    public class Foo {
                        public String[] platformStringPlatformArray() {}
                        public java.lang.@NonNull String[] nonNullStringPlatformArray() {}
                        public String @Nullable [] platformStringNullableArray() {}
                        public java.lang.@Nullable String @Nullable [] nullableStringNullableArray() {}
                        public java.lang.@Nullable String @NonNull [] nullableStringNonNullArray() {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    // - kotlin-style-nulls=no
                    package test.pkg {
                      public class Foo {
                        method public nonNullStringPlatformArray(): @NonNull String[];
                        method public nullableStringNonNullArray(): @Nullable String @NonNull [];
                        method public nullableStringNullableArray(): @Nullable String @Nullable [];
                        method public platformStringNullableArray(): String @Nullable [];
                        method public platformStringPlatformArray(): String[];
                      }
                    }
                """
                    .trimIndent()
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun nullableStringNullableArray(): Array<String?>? {}
                        fun nullableStringNonNullArray(): Array<String?> {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    package test.pkg {
                      public class Foo {
                        method public nonNullStringPlatformArray(): String[]!;
                        method public nullableStringNonNullArray(): String?[];
                        method public nullableStringNullableArray(): String?[]?;
                        method public platformStringNullableArray(): String![]?;
                        method public platformStringPlatformArray(): String![]!;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase: Codebase, annotations: Boolean ->
            val fooClass = codebase.assertClass("test.pkg.Foo")

            // Platform nullability isn't possible from Kotlin
            if (inputFormat.sourceLanguage != SourceLanguage.KOTLIN) {
                val platformStringPlatformArray =
                    fooClass.assertMethod("platformStringPlatformArray", "").returnType()
                assertPlatform(platformStringPlatformArray)
                assertPlatform((platformStringPlatformArray as ArrayTypeItem).componentType)
            }

            // Platform nullability isn't possible from Kotlin
            if (inputFormat.sourceLanguage != SourceLanguage.KOTLIN) {
                val platformStringNullableArray =
                    fooClass.assertMethod("platformStringNullableArray", "").returnType()
                assertNullable(platformStringNullableArray, annotations)
                assertPlatform((platformStringNullableArray as ArrayTypeItem).componentType)
            }

            // Platform nullability isn't possible from Kotlin
            if (inputFormat.sourceLanguage != SourceLanguage.KOTLIN) {
                val nonNullStringPlatformArray =
                    fooClass.assertMethod("nonNullStringPlatformArray", "").returnType()
                assertPlatform(nonNullStringPlatformArray)
                assertNonNull(
                    (nonNullStringPlatformArray as ArrayTypeItem).componentType,
                    annotations
                )
            }

            val nullableStringNonNullArray =
                fooClass.assertMethod("nullableStringNonNullArray", "").returnType()
            assertNonNull(nullableStringNonNullArray, annotations)
            assertNullable((nullableStringNonNullArray as ArrayTypeItem).componentType, annotations)

            val nullableStringNullableArray =
                fooClass.assertMethod("nullableStringNullableArray", "").returnType()
            assertNullable(nullableStringNullableArray, annotations)
            assertNullable(
                (nullableStringNullableArray as ArrayTypeItem).componentType,
                annotations
            )
        }
    }

    @Test
    fun `Test nullability of multi-dimensional arrays`() {
        runNullabilityTest(
            java(
                """
                    package test.pkg;
                    import libcore.util.NonNull;
                    import libcore.util.Nullable;
                    public class Foo {
                        public java.lang.@Nullable String @NonNull [] @Nullable [] @NonNull [] foo() {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    // - kotlin-style-nulls=no
                    package test.pkg {
                      public class Foo {
                        ctor public Foo();
                        method public foo(): @Nullable String @NonNull [] @Nullable [] @NonNull [];
                      }
                    }
                """
                    .trimIndent()
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun foo(): Array<Array<Array<String?>>?>
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    package test.pkg {
                      public class Foo {
                        ctor public Foo();
                        method public foo(): String?[][]?[];
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase, annotations ->
            val fooClass = codebase.assertClass("test.pkg.Foo")

            val array3d = fooClass.methods().single().returnType()
            assertNonNull(array3d, annotations)

            val array2d = (array3d as ArrayTypeItem).componentType
            assertNullable(array2d, annotations)

            val array = (array2d as ArrayTypeItem).componentType
            assertNonNull(array, annotations)

            val string = (array as ArrayTypeItem).componentType
            assertNullable(string, annotations)
        }
    }

    @Test
    fun `Test nullability of varargs`() {
        runNullabilityTest(
            java(
                """
                    package test.pkg;
                    import libcore.util.NonNull;
                    import libcore.util.Nullable;
                    public class Foo {
                        public void platformStringPlatformVararg(String... arg) {}
                        public void nullableStringPlatformVararg(java.lang.@Nullable String... arg) {}
                        public void platformStringNullableVararg(String @Nullable ... arg) {}
                        public void nullableStringNullableVararg(java.lang.@Nullable String @Nullable ... arg) {}
                        public void nullableStringNonNullVararg(java.lang.@Nullable String @NonNull ... arg) {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    package test.pkg {
                      public class Foo {
                        method public platformStringPlatformVararg(arg: String...): void;
                        method public nullableStringPlatformVararg(arg: @Nullable String...): void;
                        method public platformStringNullableVararg(arg: String @Nullable ...): void;
                        method public nullableStringNullableVararg(arg: @Nullable String @Nullable ...): void;
                        method public nullableStringNonNullVararg(arg: @Nullable String @NonNull ...): void;
                      }
                    }
                """
                    .trimIndent()
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        // Platform nullability isn't possible
                        // Nullable varargs aren't possible
                        fun nullableStringNonNullVararg(vararg arg: String?) = Unit
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    // - kotlin-style-nulls=no
                    package test.pkg {
                      public class Foo {
                        method public platformStringPlatformVararg(arg: String!...!): void;
                        method public nullableStringPlatformVararg(arg: String?...): void;
                        method public platformStringNullableVararg(arg: String!...?): void;
                        method public nullableStringNullableVararg(arg: String?...?): void;
                        method public nullableStringNonNullVararg(arg: String?...): void;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase, annotations ->
            val fooClass = codebase.assertClass("test.pkg.Foo")

            if (inputFormat.sourceLanguage != SourceLanguage.KOTLIN) {
                val platformStringPlatformVararg =
                    fooClass
                        .assertMethod("platformStringPlatformVararg", "java.lang.String[]")
                        .parameters()
                        .single()
                        .type()
                assertPlatform(platformStringPlatformVararg)
                assertPlatform((platformStringPlatformVararg as ArrayTypeItem).componentType)
            }

            if (inputFormat.sourceLanguage != SourceLanguage.KOTLIN) {
                val nullableStringPlatformVararg =
                    fooClass
                        .assertMethod("nullableStringPlatformVararg", "java.lang.String[]")
                        .parameters()
                        .single()
                        .type()
                assertPlatform(nullableStringPlatformVararg)
                assertNullable(
                    (nullableStringPlatformVararg as ArrayTypeItem).componentType,
                    annotations
                )
            }

            if (inputFormat.sourceLanguage != SourceLanguage.KOTLIN) {
                val platformStringNullableVararg =
                    fooClass
                        .assertMethod("platformStringNullableVararg", "java.lang.String[]")
                        .parameters()
                        .single()
                        .type()
                assertNullable(platformStringNullableVararg, annotations)
                assertPlatform((platformStringNullableVararg as ArrayTypeItem).componentType)
            }

            if (inputFormat.sourceLanguage != SourceLanguage.KOTLIN) {
                val nullableStringNullableVararg =
                    fooClass
                        .assertMethod("nullableStringNullableVararg", "java.lang.String[]")
                        .parameters()
                        .single()
                        .type()
                assertNullable(nullableStringNullableVararg, annotations)
                assertNullable(
                    (nullableStringNullableVararg as ArrayTypeItem).componentType,
                    annotations
                )
            }

            // The only version that exists for Kotlin
            val nullableStringNonNullVararg =
                fooClass
                    .assertMethod("nullableStringNonNullVararg", "java.lang.String[]")
                    .parameters()
                    .single()
                    .type()
            assertNonNull(nullableStringNonNullVararg, annotations)
            assertNullable(
                (nullableStringNonNullVararg as ArrayTypeItem).componentType,
                annotations
            )
        }
    }

    @Test
    fun `Test nullability of classes with parameters`() {
        runNullabilityTest(
            java(
                """
                    package test.pkg;
                    import java.util.List;
                    import java.util.Map;
                    import libcore.util.NonNull;
                    import libcore.util.Nullable;
                    public class Foo {
                        public @Nullable List<String> nullableListPlatformString() {}
                        public @NonNull List<@Nullable String> nonNullListNullableString() {}
                        public @Nullable Map<@NonNull Integer, @Nullable String> nullableMap() {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    // - kotlin-style-nulls=no
                    package test.pkg {
                      public class Foo {
                        method public nullableListPlatformString(): java.util.@Nullable List<java.lang.String>;
                        method public nonNullListNullableString(): java.util.@NonNull List<java.lang.@Nullable String>;
                        method public nullableMap(): java.util.@Nullable Map<java.lang.@NonNull Integer, java.lang.@Nullable String>;
                      }
                    }
                """
                    .trimIndent()
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun nonNullListNullableString(): List<String?> {}
                        fun nullableMap(): Map<Int, String?>? {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    package test.pkg {
                      public class Foo {
                        method public nullableListPlatformString(): java.util.List<java.lang.String!>?;
                        method public nonNullListNullableString(): java.util.List<java.lang.String?>;
                        method public nullableMap(): java.util.Map<java.lang.Integer, java.lang.String?>?;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase, annotations ->
            val fooClass = codebase.assertClass("test.pkg.Foo")

            // Platform type doesn't exist in Kotlin
            if (inputFormat.sourceLanguage != SourceLanguage.KOTLIN) {
                val nullableListPlatformString =
                    fooClass.assertMethod("nullableListPlatformString", "").returnType()
                assertNullable(nullableListPlatformString, annotations)
                assertPlatform((nullableListPlatformString as ClassTypeItem).parameters.single())
            }

            val nonNullListNullableString =
                fooClass.assertMethod("nonNullListNullableString", "").returnType()
            assertNonNull(nonNullListNullableString, annotations)
            assertNullable(
                (nonNullListNullableString as ClassTypeItem).parameters.single(),
                annotations
            )

            val nullableMap = fooClass.assertMethod("nullableMap", "").returnType()
            assertNullable(nullableMap, annotations)
            val mapParams = (nullableMap as ClassTypeItem).parameters
            // Non-null Integer
            assertNonNull(mapParams[0], annotations)
            // Nullable String
            assertNullable(mapParams[1], annotations)
        }
    }

    @Test
    fun `Test nullability of outer classes`() {
        runNullabilityTest(
            java(
                """
                    package test.pkg;
                    import libcore.util.NonNull;
                    import libcore.util.Nullable;
                    public class Foo {
                        public Outer<@Nullable String>.@Nullable Inner<@NonNull String> foo();
                    }
                    public class Outer<P1> {
                        public class Inner<P2> {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    // - kotlin-style-nulls=no
                    package test.pkg {
                      public class Foo {
                        method public foo(): test.pkg.Outer<java.lang.@libcore.util.Nullable String>.@libcore.util.Nullable Inner<java.lang.@libcore.util.NonNull String>;
                      }
                    }
                """
                    .trimIndent()
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun foo(): Outer.Inner<String>? {}
                    }
                    class Outer<P1> {
                        class Inner<P2>
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - include-type-use-annotations=yes
                    // - kotlin-name-type-order=yes
                    package test.pkg {
                      public class Foo {
                        method public foo(): test.pkg.Outer<java.lang.String?>.Inner<java.lang.String>?;
                      }
                    }
                """
                    .trimIndent()
            ),
        ) { codebase, annotations ->
            val innerClass = codebase.assertClass("test.pkg.Foo").methods().single().returnType()
            assertNullable(innerClass, annotations)
            val outerClass = (innerClass as ClassTypeItem).outerClassType!!
            // Outer class types can't be null and don't need to be annotated.
            assertNonNull(outerClass, expectAnnotation = false)
        }
    }

    @Test
    fun `Test nullability of wildcards`() {
        runNullabilityTest(
            java(
                """
                    package test.pkg;
                    import libcore.util.NonNull;
                    import libcore.util.Nullable;
                    import java.util.List;
                    public class Foo<T> {
                        public @NonNull Foo<? extends @Nullable String> extendsBound() {}
                        public @NonNull Foo<? super @NonNull String> superBound() {}
                        public @NonNull Foo<?> unbounded() {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - kotlin-name-type-order=yes
                    // - include-type-use-annotations=yes
                    // - kotlin-style-nulls=no
                    package test.pkg {
                      public class Foo<T> {
                        method public extendsBound(): test.pkg.@NonNull Foo<? extends java.lang.@Nullable String>;
                        method public superBound(): test.pkg.@NonNull Foo<? super java.lang.@NonNull String>;
                        method public unbounded(): test.pkg.@NonNull Foo<?>;
                      }
                    }
                """
                    .trimIndent()
            ),
            kotlin(
                """
                    package test.pkg
                    class Foo<T> {
                        fun extendsBound(): Foo<out String?> {}
                        fun superBound(): Foo<in String> {}
                        fun unbounded(): Foo<*> {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - kotlin-name-type-order=yes
                    // - include-type-use-annotations=yes
                    package test.pkg {
                      public class Foo<T> {
                        method public extendsBound(): test.pkg.Foo<? extends java.lang.String?>;
                        method public superBound(): test.pkg.Foo<? super java.lang.String>;
                        method public unbounded(): test.pkg.Foo<?>;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase, annotations ->
            val fooClass = codebase.assertClass("test.pkg.Foo")

            val extendsBoundFoo = fooClass.assertMethod("extendsBound", "").returnType()
            assertNonNull(extendsBoundFoo, annotations)
            val extendsBound = (extendsBoundFoo as ClassTypeItem).parameters.single()
            assertUndefinedNullness(extendsBound)
            val nullableString = (extendsBound as WildcardTypeItem).extendsBound
            assertNullable(nullableString!!, annotations)

            val superBoundFoo = fooClass.assertMethod("superBound", "").returnType()
            assertNonNull(superBoundFoo, annotations)
            val superBound = (superBoundFoo as ClassTypeItem).parameters.single()
            assertUndefinedNullness(superBound)
            val nonNullString = (superBound as WildcardTypeItem).superBound
            assertNonNull(nonNullString!!, annotations)

            val unboundedFoo = fooClass.assertMethod("unbounded", "").returnType()
            assertNonNull(unboundedFoo, annotations)
            val unbounded = (unboundedFoo as ClassTypeItem).parameters.single()
            assertUndefinedNullness(unbounded)
        }
    }

    @Test
    fun `Test resetting nullability`() {
        // Mutating modifiers isn't supported for a text codebase due to type caching.
        val javaSource =
            java(
                """
                    package test.pkg;
                    import libcore.util.Nullable;
                    public class Foo {
                        public java.lang.@Nullable String foo() {}
                    }
                """
                    .trimIndent()
            )
        val kotlinSource =
            kotlin(
                """
                    package test.pkg
                    class Foo {
                        fun foo(): String? {}
                    }
                """
                    .trimIndent()
            )
        val nullabilityTest = { codebase: Codebase, annotations: Boolean ->
            val stringType = codebase.assertClass("test.pkg.Foo").methods().single().returnType()
            // The type is originally nullable
            assertNullable(stringType, annotations)

            // Set to platform
            stringType.modifiers.setNullability(PLATFORM)
            assertPlatform(stringType)
            // The annotation was not removed
            if (annotations) {
                assertThat(stringType.annotationNames().single()).endsWith("Nullable")
            }

            // Set to non-null
            stringType.modifiers.setNullability(NONNULL)
            // A non-null annotation wasn't added
            assertNonNull(stringType, expectAnnotation = false)
            // The nullable annotation was not removed
            if (annotations) {
                assertThat(stringType.annotationNames().single()).endsWith("Nullable")
            }
        }

        runCodebaseTest(javaSource) { nullabilityTest(it, true) }
        runCodebaseTest(kotlinSource) { nullabilityTest(it, false) }
    }

    @Test
    fun `Test nullability set through item annotations`() {
        runCodebaseTest(
            inputSet(
                java(
                    """
                        package test.pkg;
                        import org.jetbrains.annotations.Nullable;
                        public class Foo {
                            public @Nullable String foo() {}
                        }
                    """
                        .trimIndent()
                ),
                java(
                    """
                        package org.jetbrains.annotations;
                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Target;
                        @Target({ ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER })
                        public @interface Nullable {}
                    """
                        .trimIndent()
                )
            ),
            inputSet(
                signature(
                    """
                        // Signature format: 5.0
                        // - kotlin-name-type-order=yes
                        // - include-type-use-annotations=yes
                        // - kotlin-style-nulls=no
                        package test.pkg {
                          public class Foo {
                            method public @Nullable foo(): String;
                          }
                        }
                    """
                        .trimIndent()
                )
            )
        ) { codebase ->
            val strType = codebase.assertClass("test.pkg.Foo").methods().single().returnType()
            // The annotation is on the item, not the type.
            assertNullable(strType, expectAnnotation = false)
        }
    }

    @Test
    fun `Test implicit nullability of constants`() {
        runCodebaseTest(
            java(
                """
                package test.pkg;
                public class Foo {
                    public final String nonNullStringConstant = "non null value";
                    public final String nullStringConstant = null;
                    public String nonConstantString = "non null value";
                }
            """
                    .trimIndent()
            ),
            signature(
                """
                // Signature format: 2.0
                package test.pkg {
                  public class Foo {
                    field public final String nonNullStringConstant = "non null value";
                    field public final String nullStringConstant;
                    field public String nonConstantString;
                  }
                }
            """
                    .trimIndent()
            )
        ) { codebase ->
            val fooClass = codebase.assertClass("test.pkg.Foo")

            val nonNullConstantType =
                fooClass.fields().single { it.name() == "nonNullStringConstant" }.type()
            // Nullability not set through an annotation.
            assertNonNull(nonNullConstantType, expectAnnotation = false)

            val nullConstantType =
                fooClass.fields().single { it.name() == "nullStringConstant" }.type()
            assertPlatform(nullConstantType)

            val nonConstantType =
                fooClass.fields().single { it.name() == "nullStringConstant" }.type()
            assertPlatform(nonConstantType)
        }
    }

    @Test
    fun `Test implicit nullability of constructor returns`() {
        runNullabilityTest(
            java(
                """
                package test.pkg;
                public class Foo {}
            """
                    .trimIndent()
            ),
            signature(
                """
                // Signature format: 2.0
                package test.pkg {
                  public class Foo {
                    ctor public Foo();
                  }
                }
            """
                    .trimIndent()
            ),
            kotlin(
                """
                package test.pkg
                class Foo
            """
                    .trimIndent()
            ),
            signature(
                """
                // Signature format: 5.0
                package test.pkg {
                  public class Foo {
                    ctor public Foo();
                  }
                }
            """
                    .trimIndent()
            )
        ) { codebase, _ ->
            val ctorReturn =
                codebase.assertClass("test.pkg.Foo").constructors().single().returnType()
            // Constructor returns are always non-null without needing an annotation
            assertNonNull(ctorReturn, expectAnnotation = false)
        }
    }

    @Test
    fun `Test implicit nullability of equals parameter`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        @Override
                        public boolean equals(Object other) {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - kotlin-name-type-order=yes
                    // - include-type-use-annotations=yes
                    // - kotlin-style-nulls=no
                    package test.pkg {
                      public class Foo {
                        method public equals(other: Object): boolean;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val equals = codebase.assertClass("test.pkg.Foo").methods().single()
            val objType = equals.parameters().single().type()
            // equals must accept null
            assertNullable(objType, expectAnnotation = false)
        }
    }

    @Test
    fun `Test implicit nullability of toString`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public class Foo {
                        @Override
                        public String toString() {}
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - kotlin-name-type-order=yes
                    // - include-type-use-annotations=yes
                    // - kotlin-style-nulls=no
                    package test.pkg {
                      public class Foo {
                        method public toString(): String;
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val strType = codebase.assertClass("test.pkg.Foo").methods().single().returnType()
            // toString must not return null
            assertNonNull(strType, expectAnnotation = false)
        }
    }

    @Test
    fun `Test implicit nullability of annotation members`() {
        runCodebaseTest(
            java(
                """
                    package test.pkg;
                    public @interface Foo {
                        String[] value();
                    }
                """
                    .trimIndent()
            ),
            kotlin(
                """
                    package test.pkg
                    annotation class Foo {
                        fun value(): Array<String>
                    }
                """
                    .trimIndent()
            ),
            signature(
                """
                    // Signature format: 5.0
                    // - kotlin-name-type-order=yes
                    // - include-type-use-annotations=yes
                    // - kotlin-style-nulls=no
                    package test.pkg {
                      public @interface Foo {
                        method public value(): String[]
                      }
                    }
                """
                    .trimIndent()
            )
        ) { codebase ->
            val strArray = codebase.assertClass("test.pkg.Foo").methods().single().returnType()
            assertNonNull(strArray, expectAnnotation = false)
            assertNonNull((strArray as ArrayTypeItem).componentType, expectAnnotation = false)
        }
    }
}
