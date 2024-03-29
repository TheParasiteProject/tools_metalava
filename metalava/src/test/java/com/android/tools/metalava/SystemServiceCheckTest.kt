/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.tools.metalava

import com.android.tools.metalava.testing.java
import org.junit.Test

class SystemServiceCheckTest : DriverTest() {
    @Test
    fun `SystemService OK, loaded from signature file`() {
        check(
            expectedIssues = "", // OK
            includeSystemApiAnnotations = true,
            sourceFiles =
                arrayOf(
                    java(
                        """
                    package test.pkg;
                    import android.annotation.RequiresPermission;
                    @android.annotation.SystemService
                    public class MyTest2 {
                        @RequiresPermission(anyOf={"foo.bar.PERMISSION1","foo.bar.PERMISSION2"})
                        public int myMethod1() { }
                    }
                    """
                    ),
                    systemServiceSource,
                    requiresPermissionSource
                ),
            manifest =
                """<?xml version="1.0" encoding="UTF-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <permission
                        android:name="foo.bar.PERMISSION1"
                        android:label="@string/foo"
                        android:description="@string/foo"
                        android:protectionLevel="signature"/>
                    <permission
                        android:name="foo.bar.PERMISSION2"
                        android:protectionLevel="signature"/>

                </manifest>
                """
        )
    }

    @Test
    fun `SystemService OK, loaded from source`() {
        check(
            expectedIssues = "", // OK
            includeSystemApiAnnotations = true,
            sourceFiles =
                arrayOf(
                    java(
                        """
                    package test.pkg;

                    @android.annotation.SystemService
                    public class MyTest2 {
                        @android.annotation.RequiresPermission(anyOf={"foo.bar.PERMISSION1","foo.bar.PERMISSION2"})
                        public void myMethod1() {
                        }
                    }
                    """
                    ),
                    systemServiceSource
                ),
            manifest =
                """<?xml version="1.0" encoding="UTF-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <permission
                        android:name="foo.bar.PERMISSION1"
                        android:label="@string/foo"
                        android:description="@string/foo"
                        android:protectionLevel="signature"/>
                    <permission
                        android:name="foo.bar.PERMISSION2"
                        android:protectionLevel="signature"/>

                </manifest>
                """
        )
    }

    @Test
    fun `Check SystemService -- no permission annotation`() {
        check(
            expectedIssues =
                "src/test/pkg/MyTest1.java:4: lint: Method 'myMethod2' must be protected with a system permission. [RequiresPermission]",
            includeSystemApiAnnotations = true,
            sourceFiles =
                arrayOf(
                    java(
                        """
                    package test.pkg;
                    @android.annotation.SystemService
                    public class MyTest1 {
                        public int myMethod2() { }
                    }
                    """
                    ),
                    systemServiceSource,
                    requiresPermissionSource
                ),
            manifest =
                """<?xml version="1.0" encoding="UTF-8"?>
                <manifest/>
                """
        )
    }

    @Test
    fun `Check SystemService -- can miss a permission with anyOf`() {
        check(
            expectedIssues = "",
            includeSystemApiAnnotations = true,
            sourceFiles =
                arrayOf(
                    java(
                        """
                    package test.pkg;
                    import android.annotation.RequiresPermission;
                    @android.annotation.SystemService
                    public class MyTest2 {
                        @RequiresPermission(anyOf={"foo.bar.PERMISSION1","foo.bar.PERMISSION2"})
                        public int myMethod1() { }
                    }
                    """
                    ),
                    systemServiceSource,
                    requiresPermissionSource
                ),
            manifest =
                """<?xml version="1.0" encoding="UTF-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <permission
                        android:name="foo.bar.PERMISSION1"
                        android:label="@string/foo"
                        android:description="@string/foo"
                        android:protectionLevel="signature"/>
                </manifest>
                """
        )
    }

    @Test
    fun `Check SystemService such that at least one permission must be defined with anyOf`() {
        check(
            expectedIssues =
                """
                src/test/pkg/MyTest2.java:6: lint: None of the permissions foo.bar.PERMISSION1, foo.bar.PERMISSION2 are defined by manifest TESTROOT/manifest.xml. [RequiresPermission]
                src/test/pkg/MyTest2.java:6: lint: Method 'myMethod1' must be protected with a system permission. [RequiresPermission]
                """,
            includeSystemApiAnnotations = true,
            sourceFiles =
                arrayOf(
                    java(
                        """
                    package test.pkg;
                    import android.annotation.RequiresPermission;
                    @android.annotation.SystemService
                    public class MyTest2 {
                        @RequiresPermission(anyOf={"foo.bar.PERMISSION1","foo.bar.PERMISSION2"})
                        public int myMethod1() { }
                    }
                    """
                    ),
                    systemServiceSource,
                    requiresPermissionSource
                ),
            manifest =
                """<?xml version="1.0" encoding="UTF-8"?>
                <manifest/>
                """
        )
    }

    @Test
    fun `Check SystemService -- missing one permission with allOf`() {
        check(
            expectedIssues =
                "src/test/pkg/MyTest2.java:6: lint: Permission 'foo.bar.PERMISSION2' is not defined by manifest TESTROOT/manifest.xml. [RequiresPermission]",
            includeSystemApiAnnotations = true,
            sourceFiles =
                arrayOf(
                    java(
                        """
                        package test.pkg;
                        import android.annotation.RequiresPermission;
                        @android.annotation.SystemService
                        public class MyTest2 {
                            @RequiresPermission(allOf={"foo.bar.PERMISSION1","foo.bar.PERMISSION2"})
                            public int test() { }
                        }
                        """
                    ),
                    systemServiceSource,
                    requiresPermissionSource
                ),
            manifest =
                """<?xml version="1.0" encoding="UTF-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <permission
                        android:name="foo.bar.PERMISSION1"
                        android:label="@string/foo"
                        android:description="@string/foo"
                        android:protectionLevel="signature"/>
                </manifest>
                """
        )
    }

    @Test
    fun `Check SystemService -- must be system permission, not normal`() {
        check(
            expectedIssues =
                "src/test/pkg/MyTest2.java:7: lint: Method 'test' must be protected with a system " +
                    "permission; it currently allows non-system callers holding [foo.bar.PERMISSION1, " +
                    "foo.bar.PERMISSION2] [RequiresPermission]",
            includeSystemApiAnnotations = true,
            sourceFiles =
                arrayOf(
                    java(
                        """
                    package test.pkg;
                    import android.annotation.RequiresPermission;
                    @SuppressWarnings("WeakerAccess")
                    @android.annotation.SystemService
                    public class MyTest2 {
                        @RequiresPermission(anyOf={"foo.bar.PERMISSION1","foo.bar.PERMISSION2"})
                        public int test() { }
                    }
                    """
                    ),
                    systemServiceSource,
                    requiresPermissionSource
                ),
            manifest =
                """<?xml version="1.0" encoding="UTF-8"?>
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                        <permission
                            android:name="foo.bar.PERMISSION1"
                            android:label="@string/foo"
                            android:description="@string/foo"
                            android:protectionLevel="normal"/>
                        <permission
                            android:name="foo.bar.PERMISSION2"
                            android:protectionLevel="normal"/>

                    </manifest>
                """
        )
    }

    @Test
    fun `Check SystemService -- missing manifest permissions`() {
        check(
            expectedIssues =
                """
                src/test/pkg/MyTest2.java:6: lint: Permission 'Manifest.permission.MY_PERMISSION' is not defined by manifest TESTROOT/manifest.xml. [RequiresPermission]
                src/test/pkg/MyTest2.java:6: lint: Permission 'Manifest.permission.MY_PERMISSION2' is not defined by manifest TESTROOT/manifest.xml. [RequiresPermission]
                src/test/pkg/MyTest2.java:6: lint: Method 'test' must be protected with a system permission. [RequiresPermission]
                """,
            includeSystemApiAnnotations = true,
            sourceFiles =
                arrayOf(
                    java(
                        """
                    package test.pkg;
                    import android.annotation.RequiresPermission;
                    @android.annotation.SystemService
                    public class MyTest2 {
                        @RequiresPermission(allOf={Manifest.permission.MY_PERMISSION,Manifest.permission.MY_PERMISSION2})
                        public int test() { }
                    }
                    """
                    ),
                    systemServiceSource,
                    requiresPermissionSource
                ),
            manifest =
                """<?xml version="1.0" encoding="UTF-8"?>
                <manifest/>
                """
        )
    }

    @Test
    fun `Invalid manifest`() {
        check(
            expectedIssues =
                """
                manifest.xml: error: Failed to parse TESTROOT/manifest.xml: The markup in the document preceding the root element must be well-formed. [ParseError]
                src/test/pkg/MyTest2.java:7: lint: None of the permissions foo.bar.PERMISSION1, foo.bar.PERMISSION2 are defined by manifest TESTROOT/manifest.xml. [RequiresPermission]
                src/test/pkg/MyTest2.java:7: lint: Method 'test' must be protected with a system permission. [RequiresPermission]
                """,
            includeSystemApiAnnotations = true,
            sourceFiles =
                arrayOf(
                    java(
                        """
                    package test.pkg;
                    import android.annotation.RequiresPermission;
                    @SuppressWarnings("WeakerAccess")
                    @android.annotation.SystemService
                    public class MyTest2 {
                        @RequiresPermission(anyOf={"foo.bar.PERMISSION1","foo.bar.PERMISSION2"})
                        public int test() { }
                    }
                    """
                    ),
                    systemServiceSource,
                    requiresPermissionSource
                ),
            manifest =
                """<?xml version="1.0" encoding="UTF-8"?>
                </error>
                """
        )
    }

    @Test
    fun `Warning suppressed via annotation`() {
        check(
            expectedIssues = "", // OK (suppressed)
            includeSystemApiAnnotations = true,
            sourceFiles =
                arrayOf(
                    java(
                        """
                    package test.pkg;

                    @android.annotation.SystemService
                    public class MyTest1 {
                        @android.annotation.SuppressLint({"RemovedField","RequiresPermission"})
                        @android.annotation.RequiresPermission(anyOf={"foo.bar.PERMISSION1","foo.bar.PERMISSION2"})
                        public void myMethod1() {
                        }
                    }
                    """
                    ),
                    systemServiceSource
                ),
            manifest =
                """<?xml version="1.0" encoding="UTF-8"?>
                <manifest/>
                """
        )
    }
}
