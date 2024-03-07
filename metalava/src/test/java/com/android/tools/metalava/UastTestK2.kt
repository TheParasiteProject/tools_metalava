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

package com.android.tools.metalava

import org.junit.Test

class UastTestK2 : UastTestBase() {

    @Test
    fun `Test RequiresOptIn and OptIn -- K2`() {
        `Test RequiresOptIn and OptIn`(isK2 = true)
    }

    @Test
    fun `renamed via @JvmName -- K2`() {
        // NB: getInterpolated -> isInterpolated
        `renamed via @JvmName`(
            isK2 = true,
            api =
                """
                // Signature format: 4.0
                package test.pkg {
                  public final class ColorRamp {
                    ctor public ColorRamp(int[] colors, boolean interpolated);
                    method public int[] getColors();
                    method public int[] getOtherColors();
                    method public boolean isInitiallyEnabled();
                    method public boolean isInterpolated();
                    method public void updateOtherColors(int[]);
                    property public final int[] colors;
                    property public final boolean initiallyEnabled;
                    property public final boolean interpolated;
                    property public final int[] otherColors;
                  }
                }
            """
        )
    }

    @Test
    fun `Kotlin Reified Methods -- K2`() {
        `Kotlin Reified Methods`(isK2 = true)
    }

    @Test
    fun `Annotation on parameters of data class synthetic copy -- K2`() {
        `Annotation on parameters of data class synthetic copy`(isK2 = true)
    }

    @Test
    fun `declarations with value class in its signature -- K2`() {
        `declarations with value class in its signature`(isK2 = true)
    }

    @Test
    fun `non-last vararg type -- K2`() {
        `non-last vararg type`(isK2 = true)
    }

    @Test
    fun `implements Comparator -- K2`() {
        `implements Comparator`(isK2 = true)
    }

    @Test
    fun `constant in file-level annotation -- K2`() {
        `constant in file-level annotation`(isK2 = true)
    }

    @Test
    fun `final modifier in enum members -- K2`() {
        `final modifier in enum members`(isK2 = true)
    }

    @Test
    fun `lateinit var as mutable bare field -- K2`() {
        `lateinit var as mutable bare field`(isK2 = true)
    }

    @Test
    fun `Upper bound wildcards -- enum members -- K2`() {
        `Upper bound wildcards -- enum members`(isK2 = true)
    }

    @Test
    fun `Upper bound wildcards -- type alias -- K2`() {
        `Upper bound wildcards -- type alias`(isK2 = true)
    }

    @Test
    fun `Upper bound wildcards -- extension function type -- K2`() {
        `Upper bound wildcards -- extension function type`(isK2 = true)
    }

    @Test
    fun `boxed type argument as method return type -- K2`() {
        `boxed type argument as method return type`(isK2 = true)
    }

    @Test
    fun `setter returns this with type cast -- K2`() {
        `setter returns this with type cast`(isK2 = true)
    }

    @Test
    fun `suspend fun in interface -- K2`() {
        `suspend fun in interface`(isK2 = true)
    }

    @Test
    fun `nullable return type via type alias -- K2`() {
        `nullable return type via type alias`(isK2 = true)
    }

    @Test
    fun `IntDef with constant in companion object -- K2`() {
        `IntDef with constant in companion object`(isK2 = true)
    }

    @Test
    fun `APIs before and after @Deprecated(HIDDEN) on properties or accessors -- K2`() {
        // NB: better tracking non-deprecated accessors (thanks to better use-site handling)
        `APIs before and after @Deprecated(HIDDEN) on properties or accessors`(
            isK2 = true,
            api =
                """
                package test.pkg {
                  @kotlin.annotation.Target(allowedTargets={kotlin.annotation.AnnotationTarget.PROPERTY, kotlin.annotation.AnnotationTarget.PROPERTY_GETTER, kotlin.annotation.AnnotationTarget.PROPERTY_SETTER}) public @interface MyAnnotation {
                  }
                  public interface TestInterface {
                    method public int getPOld_deprecatedOnSetter();
                    method @test.pkg.MyAnnotation public int getPOld_deprecatedOnSetter_myAnnoOnBoth();
                    method @test.pkg.MyAnnotation public int getPOld_deprecatedOnSetter_myAnnoOnGetter();
                    method public int getPOld_deprecatedOnSetter_myAnnoOnSetter();
                    method public void setPOld_deprecatedOnGetter(int);
                    method @test.pkg.MyAnnotation public void setPOld_deprecatedOnGetter_myAnnoOnBoth(int);
                    method public void setPOld_deprecatedOnGetter_myAnnoOnGetter(int);
                    method @test.pkg.MyAnnotation public void setPOld_deprecatedOnGetter_myAnnoOnSetter(int);
                    property public abstract int pOld_deprecatedOnSetter;
                    property @test.pkg.MyAnnotation public abstract int pOld_deprecatedOnSetter_myAnnoOnBoth;
                    property @test.pkg.MyAnnotation public abstract int pOld_deprecatedOnSetter_myAnnoOnGetter;
                    property public abstract int pOld_deprecatedOnSetter_myAnnoOnSetter;
                  }
                  public final class Test_accessors {
                    ctor public Test_accessors();
                    method public String? getPNew_accessors();
                    method public String? getPOld_accessors_deprecatedOnSetter();
                    method public void setPNew_accessors(String?);
                    method public void setPOld_accessors_deprecatedOnGetter(String?);
                    property public final String? pNew_accessors;
                    property public final String? pOld_accessors_deprecatedOnSetter;
                  }
                  public final class Test_getter {
                    ctor public Test_getter();
                    method public String? getPNew_getter();
                    method public String? getPOld_getter_deprecatedOnSetter();
                    method public void setPNew_getter(String?);
                    method public void setPOld_getter_deprecatedOnGetter(String?);
                    property public final String? pNew_getter;
                    property public final String? pOld_getter_deprecatedOnSetter;
                  }
                  public final class Test_noAccessor {
                    ctor public Test_noAccessor();
                    method public String getPNew_noAccessor();
                    method public String getPOld_noAccessor_deprecatedOnSetter();
                    method public void setPNew_noAccessor(String);
                    method public void setPOld_noAccessor_deprecatedOnGetter(String);
                    property public final String pNew_noAccessor;
                    property public final String pOld_noAccessor_deprecatedOnSetter;
                  }
                  public final class Test_setter {
                    ctor public Test_setter();
                    method public String? getPNew_setter();
                    method public String? getPOld_setter_deprecatedOnSetter();
                    method public void setPNew_setter(String?);
                    method public void setPOld_setter_deprecatedOnGetter(String?);
                    property public final String? pNew_setter;
                    property public final String? pOld_setter_deprecatedOnSetter;
                  }
                }
            """
        )
    }
}
