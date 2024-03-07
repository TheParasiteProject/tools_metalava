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

import com.android.tools.metalava.model.text.FileFormat
import com.android.tools.metalava.testing.java
import com.android.tools.metalava.testing.kotlin

// Base class to collect test inputs whose behaviors (API/lint) vary depending on UAST versions.
abstract class UastTestBase : DriverTest() {

    protected fun `Test RequiresOptIn and OptIn`(isK2: Boolean) {
        // See http://b/248341155 for more details
        val klass = if (isK2) "Class" else "kotlin.reflect.KClass"
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                    package test.pkg

                    @RequiresOptIn
                    @Retention(AnnotationRetention.BINARY)
                    @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
                    annotation class ExperimentalBar

                    @ExperimentalBar
                    class FancyBar

                    @OptIn(FancyBar::class) // @OptIn should not be tracked as it is not API
                    class SimpleClass {
                        fun methodUsingFancyBar() {
                            val fancyBar = FancyBar()
                        }
                    }

                    @androidx.annotation.experimental.UseExperimental(FancyBar::class) // @UseExperimental should not be tracked as it is not API
                    class AnotherSimpleClass {
                        fun methodUsingFancyBar() {
                            val fancyBar = FancyBar()
                        }
                    }
                """
                    ),
                    kotlin(
                        """
                    package androidx.annotation.experimental

                    import kotlin.annotation.Retention
                    import kotlin.annotation.Target
                    import kotlin.reflect.KClass

                    @Retention(AnnotationRetention.BINARY)
                    @Target(
                        AnnotationTarget.CLASS,
                        AnnotationTarget.PROPERTY,
                        AnnotationTarget.LOCAL_VARIABLE,
                        AnnotationTarget.VALUE_PARAMETER,
                        AnnotationTarget.CONSTRUCTOR,
                        AnnotationTarget.FUNCTION,
                        AnnotationTarget.PROPERTY_GETTER,
                        AnnotationTarget.PROPERTY_SETTER,
                        AnnotationTarget.FILE,
                        AnnotationTarget.TYPEALIAS
                    )
                    annotation class UseExperimental(
                        /**
                         * Defines the experimental API(s) whose usage this annotation allows.
                         */
                        vararg val markerClass: KClass<out Annotation>
                    )
                """
                    )
                ),
            format = FileFormat.V3,
            api =
                """
                // Signature format: 3.0
                package androidx.annotation.experimental {
                  @kotlin.annotation.Retention(kotlin.annotation.AnnotationRetention.BINARY) @kotlin.annotation.Target(allowedTargets={kotlin.annotation.AnnotationTarget.CLASS, kotlin.annotation.AnnotationTarget.PROPERTY, kotlin.annotation.AnnotationTarget.LOCAL_VARIABLE, kotlin.annotation.AnnotationTarget.VALUE_PARAMETER, kotlin.annotation.AnnotationTarget.CONSTRUCTOR, kotlin.annotation.AnnotationTarget.FUNCTION, kotlin.annotation.AnnotationTarget.PROPERTY_GETTER, kotlin.annotation.AnnotationTarget.PROPERTY_SETTER, kotlin.annotation.AnnotationTarget.FILE, kotlin.annotation.AnnotationTarget.TYPEALIAS}) public @interface UseExperimental {
                    method public abstract $klass<? extends java.lang.annotation.Annotation>[] markerClass();
                    property public abstract $klass<? extends java.lang.annotation.Annotation>[] markerClass;
                  }
                }
                package test.pkg {
                  public final class AnotherSimpleClass {
                    ctor public AnotherSimpleClass();
                    method public void methodUsingFancyBar();
                  }
                  @kotlin.RequiresOptIn @kotlin.annotation.Retention(kotlin.annotation.AnnotationRetention.BINARY) @kotlin.annotation.Target(allowedTargets={kotlin.annotation.AnnotationTarget.CLASS, kotlin.annotation.AnnotationTarget.FUNCTION}) public @interface ExperimentalBar {
                  }
                  @test.pkg.ExperimentalBar public final class FancyBar {
                    ctor public FancyBar();
                  }
                  public final class SimpleClass {
                    ctor public SimpleClass();
                    method public void methodUsingFancyBar();
                  }
                }
            """
        )
    }

    protected fun `renamed via @JvmName`(isK2: Boolean, api: String) {
        // Regression test from http://b/257444932: @get:JvmName on constructor property
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg

                        class ColorRamp(
                            val colors: IntArray,
                            @get:JvmName("isInterpolated")
                            val interpolated: Boolean,
                        ) {
                            @get:JvmName("isInitiallyEnabled")
                            val initiallyEnabled: Boolean

                            @set:JvmName("updateOtherColors")
                            var otherColors: IntArray
                        }
                    """
                    )
                ),
            format = FileFormat.V4,
            api = api,
        )
    }

    protected fun `Kotlin Reified Methods`(isK2: Boolean) {
        // TODO: once fix for https://youtrack.jetbrains.com/issue/KT-39209 is available (231),
        //  FE1.0 UAST will have implicit nullability too.
        //  Put this back to ApiFileTest, before `Kotlin Reified Methods 2`
        val n = if (isK2) " @Nullable" else ""
        uastCheck(
            isK2,
            format = FileFormat.V2,
            sourceFiles =
                arrayOf(
                    java(
                        """
                    package test.pkg;

                    public class Context {
                        @SuppressWarnings("unchecked")
                        public final <T> T getSystemService(Class<T> serviceClass) {
                            return null;
                        }
                    }
                    """
                    ),
                    kotlin(
                        """
                    package test.pkg

                    inline fun <reified T> Context.systemService1() = getSystemService(T::class.java)
                    inline fun Context.systemService2() = getSystemService(String::class.java)
                    """
                    )
                ),
            api =
                """
                package test.pkg {
                  public class Context {
                    ctor public Context();
                    method public final <T> T getSystemService(Class<T>);
                  }
                  public final class TestKt {
                    method$n public static inline <reified T> T systemService1(@NonNull test.pkg.Context);
                    method public static inline String systemService2(@NonNull test.pkg.Context);
                  }
                }
                """
        )
    }

    protected fun `Annotation on parameters of data class synthetic copy`(isK2: Boolean) {
        // TODO: https://youtrack.jetbrains.com/issue/KT-57003
        val typeAnno = if (isK2) "" else "@test.pkg.MyAnnotation "
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg
                        annotation class MyAnnotation

                        data class Foo(@MyAnnotation val p1: Int, val p2: String)
                    """
                    )
                ),
            api =
                """
                package test.pkg {
                  public final class Foo {
                    ctor public Foo(@test.pkg.MyAnnotation int p1, String p2);
                    method public int component1();
                    method public String component2();
                    method public test.pkg.Foo copy(${typeAnno}int p1, String p2);
                    method public int getP1();
                    method public String getP2();
                    property public final int p1;
                    property public final String p2;
                  }
                  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface MyAnnotation {
                  }
                }
            """
        )
    }

    protected fun `declarations with value class in its signature`(isK2: Boolean) {
        // https://youtrack.jetbrains.com/issue/KT-57546
        // https://youtrack.jetbrains.com/issue/KT-57577
        // TODO(b/297113621)
        val alignmentMembersDelegateToCompanion =
            if (isK2) ""
            else
                """
                    method public int getHorizontal();
                    method public int getVertical();
                    property public final int horizontal;
                    property public final int vertical;"""
        val alignmentCompanionAccessors =
            if (isK2) ""
            else
                """
                    method public int getStart();
                    method public int getTop();"""
        val alignmentCompanionProperties =
            if (isK2) ""
            else
                """
                    property public final int Start;
                    property public final int Top;"""
        val alignmentHorizontalCompanionMembers =
            if (isK2) ""
            else
                """
                    method public int getCenterHorizontally();
                    method public int getEnd();
                    method public int getStart();
                    property public final int CenterHorizontally;
                    property public final int End;
                    property public final int Start;"""
        val alignmentVerticalCompanionMembers =
            if (isK2) ""
            else
                """
                    method public int getBottom();
                    method public int getCenterVertically();
                    method public int getTop();
                    property public final int Bottom;
                    property public final int CenterVertically;
                    property public final int Top;"""
        val userPropertyAndAccessors =
            if (isK2) ""
            else
                """
                    method public float getP();
                    method public float getQ();
                    method public void setQ(float);
                    property public final float p;
                    property public final float q;"""
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg
                        @kotlin.jvm.JvmInline
                        value class AnchorType internal constructor(internal val ratio: Float) {
                            companion object {
                                val Start = AnchorType(0f)
                                val Center = AnchorType(0.5f)
                                val End = AnchorType(1f)
                            }
                        }
                        class User(
                          val p : AnchorType,
                          var q : AnchorType,
                        ) {
                          fun foo() = p
                          fun bar(): () -> AnchorType = { foo() }
                        }

                        class Alignment(val horizontal: Horizontal, val vertical: Vertical) {
                          @kotlin.jvm.JvmInline
                          value class Horizontal private constructor(private val value: Int) {
                            companion object {
                              val Start: Horizontal = Horizontal(0)
                              val CenterHorizontally: Horizontal = Horizontal(1)
                              val End: Horizontal = Horizontal(2)
                            }
                          }

                          @kotlin.jvm.JvmInline
                          value class Vertical private constructor(private val value: Int) {
                            companion object {
                              val Top: Vertical = Vertical(0)
                              val CenterVertically: Vertical = Vertical(1)
                              val Bottom: Vertical = Vertical(2)
                            }
                          }

                          companion object {
                            val TopStart: Alignment = Alignment(Horizontal.Start, Vertical.Top)
                            val Top: Vertical = Vertical.Top
                            val Start: Horizontal = Horizontal.Start
                          }
                        }
                    """
                    )
                ),
            api =
                """
                package test.pkg {
                  public final class Alignment {
                    ctor public Alignment(int horizontal, int vertical);$alignmentMembersDelegateToCompanion
                    field public static final test.pkg.Alignment.Companion Companion;
                  }
                  public static final class Alignment.Companion {$alignmentCompanionAccessors
                    method public test.pkg.Alignment getTopStart();$alignmentCompanionProperties
                    property public final test.pkg.Alignment TopStart;
                  }
                  @kotlin.jvm.JvmInline public static final value class Alignment.Horizontal {
                    field public static final test.pkg.Alignment.Horizontal.Companion Companion;
                  }
                  public static final class Alignment.Horizontal.Companion {$alignmentHorizontalCompanionMembers
                  }
                  @kotlin.jvm.JvmInline public static final value class Alignment.Vertical {
                    field public static final test.pkg.Alignment.Vertical.Companion Companion;
                  }
                  public static final class Alignment.Vertical.Companion {$alignmentVerticalCompanionMembers
                  }
                  @kotlin.jvm.JvmInline public final value class AnchorType {
                    field public static final test.pkg.AnchorType.Companion Companion;
                  }
                  public static final class AnchorType.Companion {
                    method public float getCenter();
                    method public float getEnd();
                    method public float getStart();
                    property public final float Center;
                    property public final float End;
                    property public final float Start;
                  }
                  public final class User {
                    ctor public User(float p, float q);
                    method public kotlin.jvm.functions.Function0<test.pkg.AnchorType> bar();
                    method public float foo();$userPropertyAndAccessors
                  }
                }
        """
        )
    }

    protected fun `non-last vararg type`(isK2: Boolean) {
        // https://youtrack.jetbrains.com/issue/KT-57547
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg
                        fun foo(vararg vs: String, b: Boolean = true) {
                        }
                    """
                    )
                ),
            api =
                """
                package test.pkg {
                  public final class TestKt {
                    method public static void foo(String![] vs, optional boolean b);
                  }
                }
            """
        )
    }

    protected fun `implements Comparator`(isK2: Boolean) {
        // https://youtrack.jetbrains.com/issue/KT-57548
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg
                        class Foo(val x : Int)
                        class FooComparator : Comparator<Foo> {
                          override fun compare(firstFoo: Foo, secondFoo: Foo): Int =
                            firstFoo.x - secondFoo.x
                        }
                    """
                    )
                ),
            api =
                """
                package test.pkg {
                  public final class Foo {
                    ctor public Foo(int x);
                    method public int getX();
                    property public final int x;
                  }
                  public final class FooComparator implements java.util.Comparator<test.pkg.Foo> {
                    ctor public FooComparator();
                    method public int compare(test.pkg.Foo firstFoo, test.pkg.Foo secondFoo);
                  }
                }
            """
        )
    }

    protected fun `constant in file-level annotation`(isK2: Boolean) {
        // https://youtrack.jetbrains.com/issue/KT-57550
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        @file:RequiresApi(31)
                        package test.pkg
                        import androidx.annotation.RequiresApi

                        @RequiresApi(31)
                        fun foo(p: Int) {}
                    """
                    ),
                    requiresApiSource
                ),
            extraArguments = arrayOf(ARG_HIDE_PACKAGE, "androidx.annotation"),
            api =
                """
                package test.pkg {
                  @RequiresApi(31) public final class TestKt {
                    method @RequiresApi(31) public static void foo(int p);
                  }
                }
            """
        )
    }

    protected fun `final modifier in enum members`(isK2: Boolean) {
        // https://youtrack.jetbrains.com/issue/KT-57567
        val e = if (isK2) "test.pkg.Event" else "E!"
        val s = if (isK2) "test.pkg.State" else "E!"
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg
                        enum class Event {
                          ON_CREATE, ON_START, ON_STOP, ON_DESTROY;
                          companion object {
                            @JvmStatic
                            fun upTo(state: State): Event? {
                              return when(state) {
                                State.ENQUEUED -> ON_CREATE
                                State.RUNNING -> ON_START
                                State.BLOCKED -> ON_STOP
                                else -> null
                              }
                            }
                          }
                        }
                        enum class State {
                          ENQUEUED, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED;
                          val isFinished: Boolean
                            get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
                          fun isAtLeast(state: State): Boolean {
                            return compareTo(state) >= 0
                          }
                        }
                    """
                    )
                ),
            api =
                """
                package test.pkg {
                  public enum Event {
                    method public static kotlin.enums.EnumEntries<$e> getEntries();
                    method public static final test.pkg.Event? upTo(test.pkg.State state);
                    method public static test.pkg.Event valueOf(String value) throws java.lang.IllegalArgumentException, java.lang.NullPointerException;
                    method public static test.pkg.Event[] values();
                    enum_constant public static final test.pkg.Event ON_CREATE;
                    enum_constant public static final test.pkg.Event ON_DESTROY;
                    enum_constant public static final test.pkg.Event ON_START;
                    enum_constant public static final test.pkg.Event ON_STOP;
                    field public static final test.pkg.Event.Companion Companion;
                  }
                  public static final class Event.Companion {
                    method public test.pkg.Event? upTo(test.pkg.State state);
                  }
                  public enum State {
                    method public static kotlin.enums.EnumEntries<$s> getEntries();
                    method public final boolean isAtLeast(test.pkg.State state);
                    method public final boolean isFinished();
                    method public static test.pkg.State valueOf(String value) throws java.lang.IllegalArgumentException, java.lang.NullPointerException;
                    method public static test.pkg.State[] values();
                    property public final boolean isFinished;
                    enum_constant public static final test.pkg.State BLOCKED;
                    enum_constant public static final test.pkg.State CANCELLED;
                    enum_constant public static final test.pkg.State ENQUEUED;
                    enum_constant public static final test.pkg.State FAILED;
                    enum_constant public static final test.pkg.State RUNNING;
                    enum_constant public static final test.pkg.State SUCCEEDED;
                  }
                }
            """
        )
    }

    protected fun `lateinit var as mutable bare field`(isK2: Boolean) {
        // https://youtrack.jetbrains.com/issue/KT-57569
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg
                        class Bar
                        class Foo {
                          lateinit var bars: List<Bar>
                            private set
                        }
                    """
                    )
                ),
            api =
                """
                package test.pkg {
                  public final class Bar {
                    ctor public Bar();
                  }
                  public final class Foo {
                    ctor public Foo();
                    method public java.util.List<test.pkg.Bar> getBars();
                    property public final java.util.List<test.pkg.Bar> bars;
                  }
                }
            """
        )
    }

    protected fun `Upper bound wildcards -- enum members`(isK2: Boolean) {
        // https://youtrack.jetbrains.com/issue/KT-57578
        val upperBound = "? extends "
        val c = if (isK2) "test.pkg.PowerCategory" else "E!"
        val d = if (isK2) "test.pkg.PowerCategoryDisplayLevel" else "E!"
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg
                        enum class PowerCategoryDisplayLevel {
                          BREAKDOWN, TOTAL
                        }

                        enum class PowerCategory {
                          CPU, MEMORY
                        }

                        class PowerMetric {
                          companion object {
                            @JvmStatic
                            fun Battery(): Type.Battery {
                              return Type.Battery()
                            }

                            @JvmStatic
                            fun Energy(
                              categories: Map<PowerCategory, PowerCategoryDisplayLevel> = emptyMap()
                            ): Type.Energy {
                              return Type.Energy(categories)
                            }

                            @JvmStatic
                            fun Power(
                              categories: Map<PowerCategory, PowerCategoryDisplayLevel> = emptyMap()
                            ): Type.Power {
                              return Type.Power(categories)
                            }
                          }
                          sealed class Type(var categories: Map<PowerCategory, PowerCategoryDisplayLevel> = emptyMap()) {
                            class Power(
                              powerCategories: Map<PowerCategory, PowerCategoryDisplayLevel> = emptyMap()
                            ) : Type(powerCategories)

                            class Energy(
                              energyCategories: Map<PowerCategory, PowerCategoryDisplayLevel> = emptyMap()
                            ) : Type(energyCategories)

                            class Battery : Type()
                          }
                        }
                    """
                    )
                ),
            api =
                """
                package test.pkg {
                  public enum PowerCategory {
                    method public static kotlin.enums.EnumEntries<$c> getEntries();
                    method public static test.pkg.PowerCategory valueOf(String value) throws java.lang.IllegalArgumentException, java.lang.NullPointerException;
                    method public static test.pkg.PowerCategory[] values();
                    enum_constant public static final test.pkg.PowerCategory CPU;
                    enum_constant public static final test.pkg.PowerCategory MEMORY;
                  }
                  public enum PowerCategoryDisplayLevel {
                    method public static kotlin.enums.EnumEntries<$d> getEntries();
                    method public static test.pkg.PowerCategoryDisplayLevel valueOf(String value) throws java.lang.IllegalArgumentException, java.lang.NullPointerException;
                    method public static test.pkg.PowerCategoryDisplayLevel[] values();
                    enum_constant public static final test.pkg.PowerCategoryDisplayLevel BREAKDOWN;
                    enum_constant public static final test.pkg.PowerCategoryDisplayLevel TOTAL;
                  }
                  public final class PowerMetric {
                    ctor public PowerMetric();
                    method public static test.pkg.PowerMetric.Type.Battery Battery();
                    method public static test.pkg.PowerMetric.Type.Energy Energy(optional java.util.Map<test.pkg.PowerCategory,${upperBound}test.pkg.PowerCategoryDisplayLevel> categories);
                    method public static test.pkg.PowerMetric.Type.Power Power(optional java.util.Map<test.pkg.PowerCategory,${upperBound}test.pkg.PowerCategoryDisplayLevel> categories);
                    field public static final test.pkg.PowerMetric.Companion Companion;
                  }
                  public static final class PowerMetric.Companion {
                    method public test.pkg.PowerMetric.Type.Battery Battery();
                    method public test.pkg.PowerMetric.Type.Energy Energy(optional java.util.Map<test.pkg.PowerCategory,${upperBound}test.pkg.PowerCategoryDisplayLevel> categories);
                    method public test.pkg.PowerMetric.Type.Power Power(optional java.util.Map<test.pkg.PowerCategory,${upperBound}test.pkg.PowerCategoryDisplayLevel> categories);
                  }
                  public abstract static sealed class PowerMetric.Type {
                    method public final java.util.Map<test.pkg.PowerCategory,test.pkg.PowerCategoryDisplayLevel> getCategories();
                    method public final void setCategories(java.util.Map<test.pkg.PowerCategory,${upperBound}test.pkg.PowerCategoryDisplayLevel>);
                    property public final java.util.Map<test.pkg.PowerCategory,test.pkg.PowerCategoryDisplayLevel> categories;
                  }
                  public static final class PowerMetric.Type.Battery extends test.pkg.PowerMetric.Type {
                    ctor public PowerMetric.Type.Battery();
                  }
                  public static final class PowerMetric.Type.Energy extends test.pkg.PowerMetric.Type {
                    ctor public PowerMetric.Type.Energy(optional java.util.Map<test.pkg.PowerCategory,${upperBound}test.pkg.PowerCategoryDisplayLevel> energyCategories);
                  }
                  public static final class PowerMetric.Type.Power extends test.pkg.PowerMetric.Type {
                    ctor public PowerMetric.Type.Power(optional java.util.Map<test.pkg.PowerCategory,${upperBound}test.pkg.PowerCategoryDisplayLevel> powerCategories);
                  }
                }
            """
        )
    }

    protected fun `Upper bound wildcards -- type alias`(isK2: Boolean) {
        // TODO: https://youtrack.jetbrains.com/issue/KT-61460
        val upperBound = if (isK2) "? extends " else ""
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg

                        class PerfettoSdkHandshake(
                          private val targetPackage: String,
                          private val parseJsonMap: (jsonString: String) -> Map<String, String>,
                          private val executeShellCommand: ShellCommandExecutor,
                        )

                        internal typealias ShellCommandExecutor = (command: String) -> String
                        """
                    )
                ),
            api =
                """
                package test.pkg {
                  public final class PerfettoSdkHandshake {
                    ctor public PerfettoSdkHandshake(String targetPackage, kotlin.jvm.functions.Function1<? super java.lang.String,? extends java.util.Map<java.lang.String,java.lang.String>> parseJsonMap, kotlin.jvm.functions.Function1<? super java.lang.String,${upperBound}java.lang.String> executeShellCommand);
                  }
                }
                """
        )
    }

    protected fun `Upper bound wildcards -- extension function type`(isK2: Boolean) {
        // TODO: https://youtrack.jetbrains.com/issue/KT-61734
        val wildcard1 = if (isK2) "? super " else ""
        val wildcard2 = if (isK2) "? extends " else ""
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg

                        interface NavGraphBuilder

                        interface AnimatedContentTransitionScope<S>

                        interface NavBackStackEntry

                        interface EnterTransition

                        fun NavGraphBuilder.compose(
                          enterTransition: (@JvmSuppressWildcards
                              AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition?)? = null,
                        ) = TODO()
                        """
                    )
                ),
            api =
                """
                package test.pkg {
                  public interface AnimatedContentTransitionScope<S> {
                  }
                  public interface EnterTransition {
                  }
                  public interface NavBackStackEntry {
                  }
                  public interface NavGraphBuilder {
                  }
                  public final class NavGraphBuilderKt {
                    method public static Void compose(test.pkg.NavGraphBuilder, optional kotlin.jvm.functions.Function1<${wildcard1}test.pkg.AnimatedContentTransitionScope<test.pkg.NavBackStackEntry>,${wildcard2}test.pkg.EnterTransition>? enterTransition);
                  }
                }
                """
        )
    }

    protected fun `boxed type argument as method return type`(isK2: Boolean) {
        // https://youtrack.jetbrains.com/issue/KT-57579
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg
                        abstract class ActivityResultContract<I, O> {
                          abstract fun parseResult(resultCode: Int, intent: Intent?): O
                        }

                        interface Intent

                        class StartActivityForResult : ActivityResultContract<Intent, Boolean>() {
                          override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
                            return resultCode == 42
                          }
                        }
                    """
                    )
                ),
            api =
                """
                package test.pkg {
                  public abstract class ActivityResultContract<I, O> {
                    ctor public ActivityResultContract();
                    method public abstract O parseResult(int resultCode, test.pkg.Intent? intent);
                  }
                  public interface Intent {
                  }
                  public final class StartActivityForResult extends test.pkg.ActivityResultContract<test.pkg.Intent,java.lang.Boolean> {
                    ctor public StartActivityForResult();
                    method public Boolean parseResult(int resultCode, test.pkg.Intent? intent);
                  }
                }
            """
        )
    }

    protected fun `setter returns this with type cast`(isK2: Boolean) {
        // TODO: https://youtrack.jetbrains.com/issue/KT-61459
        val extends = if (isK2) "" else " extends test.pkg.AbstractAlarm.Builder<Self, Built>"
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg

                        interface Alarm {
                          interface Builder<Self : Builder<Self>> {
                            fun build(): Alarm
                          }
                        }

                        abstract class AbstractAlarm<
                          Self : AbstractAlarm<Self, Builder>, Builder : AbstractAlarm.Builder<Builder, Self>>
                        internal constructor(
                          val identifier: String,
                        ) : Alarm {
                          abstract class Builder<Self : Builder<Self, Built>, Built : AbstractAlarm<Built, Self>> : Alarm.Builder<Self> {
                            private var identifier: String = ""

                            fun setIdentifier(text: String): Self {
                              this.identifier = text
                              return this as Self
                            }

                            final override fun build(): Built = TODO()
                          }
                        }
                    """
                    )
                ),
            api =
                """
                package test.pkg {
                  public abstract class AbstractAlarm<Self extends test.pkg.AbstractAlarm<Self, Builder>, Builder extends test.pkg.AbstractAlarm.Builder<Builder, Self>> implements test.pkg.Alarm {
                    method public final String getIdentifier();
                    property public final String identifier;
                  }
                  public abstract static class AbstractAlarm.Builder<Self$extends, Built extends test.pkg.AbstractAlarm<Built, Self>> implements test.pkg.Alarm.Builder<Self> {
                    ctor public AbstractAlarm.Builder();
                    method public final Built build();
                    method public final Self setIdentifier(String text);
                  }
                  public interface Alarm {
                  }
                  public static interface Alarm.Builder<Self extends test.pkg.Alarm.Builder<Self>> {
                    method public test.pkg.Alarm build();
                  }
                }
            """
        )
    }

    protected fun `suspend fun in interface`(isK2: Boolean) {
        // https://youtrack.jetbrains.com/issue/KT-61544
        // TODO(b/297113621)
        val n = if (isK2) "" else "?"
        val contByte =
            if (isK2) ""
            else ", kotlin.coroutines.Continuation<? super kotlin.Result<? extends byte[]>>"
        val contUnit =
            if (isK2) ""
            else ", kotlin.coroutines.Continuation<? super kotlin.Result<? extends kotlin.Unit>>"
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg

                        interface MyInterface

                        interface GattClientScope {
                          suspend fun await(block: () -> Unit)
                          suspend fun readCharacteristic(p: MyInterface): Result<ByteArray>
                          suspend fun writeCharacteristic(p: MyInterface, value: ByteArray): Result<Unit>
                        }
                        """
                    )
                ),
            api =
                """
                package test.pkg {
                  public interface GattClientScope {
                    method public suspend Object? await(kotlin.jvm.functions.Function0<kotlin.Unit> block, kotlin.coroutines.Continuation<? super kotlin.Unit>);
                    method public suspend Object$n readCharacteristic(test.pkg.MyInterface p$contByte);
                    method public suspend Object$n writeCharacteristic(test.pkg.MyInterface p, byte[] value$contUnit);
                  }
                  public interface MyInterface {
                  }
                }
                """
        )
    }

    protected fun `nullable return type via type alias`(isK2: Boolean) {
        // TODO: https://youtrack.jetbrains.com/issue/KT-61460
        val extends = if (isK2) "? extends " else ""
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg

                        typealias HasAuthenticationResultsDelegate = () -> Boolean

                        class PrepareGetCredentialResponse private constructor(
                          val hasAuthResultsDelegate: HasAuthenticationResultsDelegate?,
                        )
                        """
                    )
                ),
            api =
                """
                package test.pkg {
                  public final class PrepareGetCredentialResponse {
                    method public kotlin.jvm.functions.Function0<${extends}java.lang.Boolean>? getHasAuthResultsDelegate();
                    property public final kotlin.jvm.functions.Function0<${extends}java.lang.Boolean>? hasAuthResultsDelegate;
                  }
                }
            """
        )
    }

    protected fun `IntDef with constant in companion object`(isK2: Boolean) {
        // TODO: https://youtrack.jetbrains.com/issue/KT-61497
        val fq = if (isK2) "" else "test.pkg.RemoteAuthClient."
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg

                        @Retention(AnnotationRetention.SOURCE)
                        @Target(AnnotationTarget.ANNOTATION_CLASS)
                        annotation class MyIntDef(
                          vararg val value: Int = [],
                          val flag: Boolean = false,
                        )

                        class RemoteAuthClient internal constructor(
                          private val packageName: String,
                        ) {
                          companion object {
                            const val NO_ERROR: Int = -1
                            const val ERROR_UNSUPPORTED: Int = 0
                            const val ERROR_PHONE_UNAVAILABLE: Int = 1

                            @MyIntDef(NO_ERROR, ERROR_UNSUPPORTED, ERROR_PHONE_UNAVAILABLE)
                            @Retention(AnnotationRetention.SOURCE)
                            annotation class ErrorCode
                          }
                        }
                        """
                    ),
                ),
            api =
                """
                package test.pkg {
                  @kotlin.annotation.Retention(kotlin.annotation.AnnotationRetention.SOURCE) @kotlin.annotation.Target(allowedTargets=kotlin.annotation.AnnotationTarget.ANNOTATION_CLASS) public @interface MyIntDef {
                    method public abstract boolean flag() default false;
                    method public abstract int[] value();
                    property public abstract boolean flag;
                    property public abstract int[] value;
                  }
                  public final class RemoteAuthClient {
                    field public static final test.pkg.RemoteAuthClient.Companion Companion;
                    field public static final int ERROR_PHONE_UNAVAILABLE = 1; // 0x1
                    field public static final int ERROR_UNSUPPORTED = 0; // 0x0
                    field public static final int NO_ERROR = -1; // 0xffffffff
                  }
                  public static final class RemoteAuthClient.Companion {
                  }
                  @kotlin.annotation.Retention(kotlin.annotation.AnnotationRetention.SOURCE) @test.pkg.MyIntDef({${fq}NO_ERROR, ${fq}ERROR_UNSUPPORTED, ${fq}ERROR_PHONE_UNAVAILABLE}) public static @interface RemoteAuthClient.Companion.ErrorCode {
                  }
                }
                """
        )
    }

    protected fun `APIs before and after @Deprecated(HIDDEN) on properties or accessors`(
        isK2: Boolean,
        api: String,
    ) {
        // TODO: https://youtrack.jetbrains.com/issue/KTIJ-27244
        uastCheck(
            isK2,
            sourceFiles =
                arrayOf(
                    kotlin(
                        """
                        package test.pkg

                        class Test_noAccessor {
                            @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                            var pOld_noAccessor_deprecatedOnProperty: String = "42"

                            @get:Deprecated(level = DeprecationLevel.HIDDEN, "no more getter")
                            var pOld_noAccessor_deprecatedOnGetter: String = "42"

                            @set:Deprecated(level = DeprecationLevel.HIDDEN, "no more setter")
                            var pOld_noAccessor_deprecatedOnSetter: String = "42"

                            var pNew_noAccessor: String = "42"
                        }

                        class Test_getter {
                            @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                            var pOld_getter_deprecatedOnProperty: String? = null
                                get() = field ?: "null?"

                            @get:Deprecated(level = DeprecationLevel.HIDDEN, "no more getter")
                            var pOld_getter_deprecatedOnGetter: String? = null
                                get() = field ?: "null?"

                            @set:Deprecated(level = DeprecationLevel.HIDDEN, "no more setter")
                            var pOld_getter_deprecatedOnSetter: String? = null
                                get() = field ?: "null?"

                            var pNew_getter: String? = null
                                get() = field ?: "null?"
                        }

                        class Test_setter {
                            @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                            var pOld_setter_deprecatedOnProperty: String? = null
                                set(value) {
                                    if (field == null) {
                                        field = value
                                    }
                                }

                            @get:Deprecated(level = DeprecationLevel.HIDDEN, "no more getter")
                            var pOld_setter_deprecatedOnGetter: String? = null
                                set(value) {
                                    if (field == null) {
                                        field = value
                                    }
                                }

                            @set:Deprecated(level = DeprecationLevel.HIDDEN, "no more setter")
                            var pOld_setter_deprecatedOnSetter: String? = null
                                set(value) {
                                    if (field == null) {
                                        field = value
                                    }
                                }

                            var pNew_setter: String? = null
                                set(value) {
                                    if (field == null) {
                                        field = value
                                    }
                                }
                        }

                        class Test_accessors {
                            @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                            var pOld_accessors_deprecatedOnProperty: String? = null
                                get() = field ?: "null?"
                                set(value) {
                                    if (field == null) {
                                        field = value
                                    }
                                }

                            @get:Deprecated(level = DeprecationLevel.HIDDEN, "no more getter")
                            var pOld_accessors_deprecatedOnGetter: String? = null
                                get() = field ?: "null?"
                                set(value) {
                                    if (field == null) {
                                        field = value
                                    }
                                }

                            @set:Deprecated(level = DeprecationLevel.HIDDEN, "no more setter")
                            var pOld_accessors_deprecatedOnSetter: String? = null
                                get() = field ?: "null?"
                                set(value) {
                                    if (field == null) {
                                        field = value
                                    }
                                }

                            var pNew_accessors: String? = null
                                get() = field ?: "null?"
                                set(value) {
                                    if (field == null) {
                                        field = value
                                    }
                                }
                        }

                        @Target(
                          AnnotationTarget.PROPERTY,
                          AnnotationTarget.PROPERTY_GETTER,
                          AnnotationTarget.PROPERTY_SETTER
                        )
                        annotation class MyAnnotation

                        interface TestInterface {
                            @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                            var pOld_deprecatedOnProperty: Int

                            @get:MyAnnotation
                            @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                            var pOld_deprecatedOnProperty_myAnnoOnGetter: Int

                            @set:MyAnnotation
                            @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                            var pOld_deprecatedOnProperty_myAnnoOnSetter: Int

                            @get:MyAnnotation
                            @set:MyAnnotation
                            @Deprecated(level = DeprecationLevel.HIDDEN, "no more property")
                            var pOld_deprecatedOnProperty_myAnnoOnBoth: Int

                            @get:Deprecated(level = DeprecationLevel.HIDDEN, "no more getter")
                            var pOld_deprecatedOnGetter: Int

                            @get:MyAnnotation
                            @get:Deprecated(level = DeprecationLevel.HIDDEN, "no more getter")
                            var pOld_deprecatedOnGetter_myAnnoOnGetter: Int

                            @set:MyAnnotation
                            @get:Deprecated(level = DeprecationLevel.HIDDEN, "no more getter")
                            var pOld_deprecatedOnGetter_myAnnoOnSetter: Int

                            @get:MyAnnotation
                            @set:MyAnnotation
                            @get:Deprecated(level = DeprecationLevel.HIDDEN, "no more getter")
                            var pOld_deprecatedOnGetter_myAnnoOnBoth: Int

                            @set:Deprecated(level = DeprecationLevel.HIDDEN, "no more setter")
                            var pOld_deprecatedOnSetter: Int

                            @get:MyAnnotation
                            @set:Deprecated(level = DeprecationLevel.HIDDEN, "no more setter")
                            var pOld_deprecatedOnSetter_myAnnoOnGetter: Int

                            @set:MyAnnotation
                            @set:Deprecated(level = DeprecationLevel.HIDDEN, "no more setter")
                            var pOld_deprecatedOnSetter_myAnnoOnSetter: Int

                            @get:MyAnnotation
                            @set:MyAnnotation
                            @set:Deprecated(level = DeprecationLevel.HIDDEN, "no more setter")
                            var pOld_deprecatedOnSetter_myAnnoOnBoth: Int
                        }
                        """
                    )
                ),
            api = api,
        )
    }
}
