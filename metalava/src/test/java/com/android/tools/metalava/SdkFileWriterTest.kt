/*
 * Copyright (C) 2018 The Android Open Source Project
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

@file:Suppress("ALL")

package com.android.tools.metalava

import com.android.tools.metalava.testing.java
import org.junit.Test

class SdkFileWriterTest : DriverTest() {
    @Test
    fun `Test generating broadcast actions`() {
        check(
            expectedIssues =
                """
                src/android/telephony/SubscriptionManager.java:11: lint: Field 'ACTION_DEFAULT_SUBSCRIPTION_CHANGED' is missing @BroadcastBehavior [BroadcastBehavior]
                """,
            sourceFiles =
                arrayOf(
                    java(
                        """
                package android.telephony;

                import android.annotation.SdkConstant;
                import android.annotation.SdkConstant.SdkConstantType;

                public class SubscriptionManager {
                    /**
                     * Broadcast Action: The default subscription has changed.
                     */
                    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
                    public static final String ACTION_DEFAULT_SUBSCRIPTION_CHANGED
                            = "android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED";
                }
            """
                    ),
                    sdkConstantSource
                ),
            sdkBroadcastActions =
                """
            android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED
            """
        )
    }

    @Test
    fun `Test generating activity actions`() {
        check(
            sourceFiles =
                arrayOf(
                    java(
                        """
                package android.content;

                import android.annotation.SdkConstant;
                import android.annotation.SdkConstant.SdkConstantType;

                public class Intent {
                    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
                    public static final String ACTION_MAIN = "android.intent.action.MAIN";
                }
                """
                    ),
                    sdkConstantSource
                ),
            sdkActivityActions = """
            android.intent.action.MAIN
            """
        )
    }

    @Test
    fun `Test generating widgets`() {
        check(
            sourceFiles =
                arrayOf(
                    java(
                        """
                package android.widget;

                import android.content.Context;
                import android.annotation.Widget;

                @Widget
                public class MyButton extends android.view.View {
                    public MyButton(Context context) {
                        super(context, null);
                    }
                }
            """
                    ),
                    widgetSource
                ),
            sdkWidgets =
                """
            Wandroid.view.View java.lang.Object
            Wandroid.widget.MyButton android.view.View java.lang.Object
            """
        )
    }
}
