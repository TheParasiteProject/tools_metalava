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

import com.android.tools.metalava.testing.getAndroidJar
import com.android.tools.metalava.testing.java
import com.android.tools.metalava.testing.kotlin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Test

class PsiMethodItemTest : BasePsiTest() {

    @Test
    fun `property accessors have properties`() {
        testCodebase(kotlin("class Foo { var bar: Int = 0 }")) { codebase ->
            val classItem = codebase.assertClass("Foo")
            val getter = classItem.methods().single { it.name() == "getBar" }
            val setter = classItem.methods().single { it.name() == "setBar" }

            assertNotNull(getter.property)
            assertNotNull(setter.property)

            assertSame(getter.property, setter.property)
            assertSame(getter, getter.property?.getter)
            assertSame(setter, setter.property?.setter)
        }
    }

    @Test
    fun `destructuring functions do not have a property relationship`() {
        testCodebase(kotlin("data class Foo(val bar: Int)")) { codebase ->
            val classItem = codebase.assertClass("Foo")
            val component1 = classItem.methods().single { it.name() == "component1" }

            assertNull(component1.property)
        }
    }

    @Test
    fun `method return type is non-null`() {
        val codebase =
            java(
                """
            public class Foo {
                public Foo() {}
                public void bar() {}
            }
            """
            )
        testCodebase(codebase) { c ->
            val ctorItem = c.assertClass("Foo").findMethod("Foo", "")
            val ctorReturnType = ctorItem!!.returnType()

            val methodItem = c.assertClass("Foo").findMethod("bar", "")
            val methodReturnType = methodItem!!.returnType()

            assertNotNull(ctorReturnType)
            assertEquals(
                "Foo",
                ctorReturnType.toString(),
                "Return type of the constructor item must be the containing class."
            )

            assertNotNull(methodReturnType)
            assertEquals(
                "void",
                methodReturnType.toString(),
                "Return type of an method item should match the expected value."
            )
        }
    }

    @Test
    fun `child method does not need to be added to signature file if super method is concrete`() {
        val codebase =
            java(
                """
                    public class ParentClass {
                        public void bar() {}
                    }
                    public class ChildClass extends ParentClass {
                        public ChildClass() {}
                        @Override public void bar() {}
                    }
                """
            )

        testCodebase(codebase) { c ->
            val childMethodItem = c.assertClass("ChildClass").assertMethod("bar", "")
            assertEquals(false, childMethodItem.isRequiredOverridingMethodForTextStub())
        }
    }

    @Test
    fun `child method only needs to be added to signature file if all multiple direct super methods requires override`() {

        // `ParentClass` implements `ParentInterface.bar()`, thus the implementation is not
        // required at `ChildClass` even if it directly implements `ParentInterface`
        // Therefore, the method does not need to be added to the signature file.
        val codebase =
            java(
                """
                    public interface ParentInterface {
                        void bar();
                    }
                    public abstract class ParentClass implements ParentInterface {
                        @Override
                        public void bar() {}
                    }
                    public class ChildClass extends ParentClass implements ParentInterface {
                        @Override public void bar() {}
                    }
                """
            )

        testCodebase(codebase) { c ->
            val childMethodItem = c.assertClass("ChildClass").assertMethod("bar", "")
            assertEquals(false, childMethodItem.isRequiredOverridingMethodForTextStub())
        }
    }

    @Test
    fun `child method does not need to be added to signature file if override requiring super method is hidden`() {

        // Hierarchy is identical to the above test case.
        // but omitting `ChildClass.bar()` does not lead to error as `ParentInterface.bar()` is
        // marked hidden
        val codebase =
            java(
                """
                    public interface ParentInterface {
                        /** @hide */
                        void bar();
                    }
                    public abstract class ParentClass implements ParentInterface {
                        @Override
                        public void bar() {}
                    }
                    public class ChildClass extends ParentClass implements ParentInterface {
                        @Override public void bar() {}
                    }
                """
            )

        testCodebase(codebase) { c ->
            val childMethodItem = c.assertClass("ChildClass").assertMethod("bar", "")
            assertEquals(false, childMethodItem.isRequiredOverridingMethodForTextStub())
        }
    }

    @Test
    fun `child method need to be added to signature file if extending Object method and return type changes`() {
        val codebase =
            java(
                """
                    public class ChildClass {
                        @Override public ChildClass clone() {}
                    }
                """
            )

        testCodebase(codebase, classPath = listOf(getAndroidJar())) { c ->
            val childMethodItem = c.assertClass("ChildClass").assertMethod("clone", "")
            assertEquals(true, childMethodItem.isRequiredOverridingMethodForTextStub())
        }
    }

    @Test
    fun `child method need to be added to signature file if extending Object method and visibility changes`() {
        val codebase =
            java(
                """
                    public class ChildClass {
                        @Override protected Object clone() {}
                    }
                """
            )

        testCodebase(codebase, classPath = listOf(getAndroidJar())) { c ->
            val childMethodItem = c.assertClass("ChildClass").assertMethod("clone", "")
            assertEquals(true, childMethodItem.isRequiredOverridingMethodForTextStub())
        }
    }

    @Test
    fun `child method does not need to be added to signature file even if extending Object method and modifier changes when it is not a direct override`() {
        val codebase =
            java(
                """
                    public class ParentClass {
                        @Override public ParentClass clone() {}
                    }
                    public class ChildClass extends ParentClass {
                        @Override public ParentClass clone() {}
                    }
                """
            )

        testCodebase(codebase, classPath = listOf(getAndroidJar())) { c ->
            val childMethodItem = c.assertClass("ChildClass").assertMethod("clone", "")
            assertEquals(false, childMethodItem.isRequiredOverridingMethodForTextStub())
        }
    }

    @Test
    fun `child method does not need to be added to signature file if extending Object method and modifier does not change`() {
        val codebase =
            java(
                """
                    public class ChildClass{
                        @Override public String toString() {}
                    }
                """
            )

        testCodebase(codebase, classPath = listOf(getAndroidJar())) { c ->
            val childMethodItem = c.assertClass("ChildClass").assertMethod("toString", "")
            assertEquals(false, childMethodItem.isRequiredOverridingMethodForTextStub())
        }
    }

    @Test
    fun `hidden child method can be added to signature file to resolve compile error`() {
        val codebase =
            java(
                """
                    public interface ParentInterface {
                        void bar();
                    }
                    public abstract class ParentClass implements ParentInterface {
                        @Override
                        public abstract void bar() {}
                    }
                    public class ChildClass extends ParentClass {
                        /** @hide */
                        @Override public void bar() {}
                    }
                """
            )

        testCodebase(codebase) { c ->
            val childMethodItem = c.assertClass("ChildClass").assertMethod("bar", "")
            assertEquals(true, childMethodItem.isRequiredOverridingMethodForTextStub())
        }
    }

    @Test
    fun `child method overriding a hidden parent method can be added to signature file`() {
        val codebase =
            java(
                """
                    public interface SuperParentInterface {
                        void bar();
                    }
                    public interface ParentInterface extends SuperParentInterface {
                        /** @hide */
                        @Override
                        void bar();
                    }
                    public abstract class ParentClass implements ParentInterface {
                        @Override
                        abstract public void bar() {}
                    }
                    public class ChildClass extends ParentClass {
                        @Override
                        public void bar() {}
                    }
                """
            )

        testCodebase(codebase) { c ->
            val childMethodItem = c.assertClass("ChildClass").assertMethod("bar", "")
            assertEquals(true, childMethodItem.isRequiredOverridingMethodForTextStub())
        }
    }
}
