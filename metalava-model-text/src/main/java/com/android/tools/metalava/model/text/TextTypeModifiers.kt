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

package com.android.tools.metalava.model.text

import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.DefaultAnnotationItem
import com.android.tools.metalava.model.TypeModifiers
import com.android.tools.metalava.model.TypeNullability

/** Modifiers for a [TextTypeItem]. */
internal class TextTypeModifiers(
    private val codebase: TextCodebase,
    private val annotations: List<AnnotationItem>
) : TypeModifiers {

    override fun annotations(): List<AnnotationItem> = annotations

    override fun addAnnotation(annotation: AnnotationItem) =
        codebase.unsupported("TextTypeModifiers are immutable because TextTypes are cached")

    override fun removeAnnotation(annotation: AnnotationItem) =
        codebase.unsupported("TextTypeModifiers are immutable because TextTypes are cached")

    override fun nullability(): TypeNullability {
        TODO("Not yet implemented")
    }

    override fun setNullability(newNullability: TypeNullability) =
        codebase.unsupported("TextTypeModifiers are immutable because TextTypes are cached")

    companion object {
        /** Creates modifiers in the given [codebase] based on the text of the [annotations]. */
        fun create(codebase: TextCodebase, annotations: List<String>): TextTypeModifiers {
            return TextTypeModifiers(
                codebase,
                annotations.map { DefaultAnnotationItem.create(codebase, it) }
            )
        }
    }
}
