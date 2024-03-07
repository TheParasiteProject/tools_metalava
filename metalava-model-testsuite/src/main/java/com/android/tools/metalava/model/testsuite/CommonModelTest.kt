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

import com.android.tools.metalava.model.BaseItemVisitor
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.testing.java
import kotlin.test.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class CommonModelTest : BaseModelTest() {
    @Test
    fun `empty file`() {
        runCodebaseTest(
            signature("""
                    // Signature format: 2.0
                """),
            java(""),
        ) { codebase ->
            assertNotNull(codebase)
        }
    }

    @Test
    fun `test findCorrespondingItemIn`() {
        runCodebaseTest(
            inputSet(
                signature(
                    """
                        // Signature format: 2.0
                        package test.pkg {
                          public class Foo {
                            ctor public Foo();
                            method public void foo(int i);
                          }
                          public class Bar extends test.pkg.Foo {
                            ctor public Bar();
                            method public void foo(int i);
                            method public int bar(String s);
                          }
                        }
                    """
                ),
            ),
            inputSet(
                java(
                    """
                        package test.pkg;
                        public class Foo {
                            public void foo(int i) {}                        
                        }
                    """
                ),
                java(
                    """
                        package test.pkg;
                        public class Bar extends Foo {
                            public void foo(int i) {}                        
                            public int bar(String s) {return s.length();}                        
                        }
                    """
                ),
            ),
        ) { codebase ->
            // Iterate over the codebase and try and find every item that is visited.
            codebase.accept(
                object : BaseItemVisitor() {
                    override fun visitItem(item: Item) {
                        val foundItem = item.findCorrespondingItemIn(codebase)
                        assertSame(item, foundItem)
                    }
                }
            )
        }
    }
}
