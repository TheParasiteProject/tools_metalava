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

package com.android.tools.metalava.model.text

import com.android.tools.metalava.JAVA_LANG_DEPRECATED
import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.AnnotationTarget
import com.android.tools.metalava.model.DefaultModifierList
import com.android.tools.metalava.model.ModifierList
import java.io.StringWriter

class TextModifiers(
    override val codebase: TextCodebase,
    flags: Int = PACKAGE_PRIVATE,
    annotations: MutableList<AnnotationItem>? = null
) : DefaultModifierList(codebase, flags, annotations) {

    fun duplicate(): TextModifiers {
        val annotations = this.annotations
        val newAnnotations =
            if (annotations == null || annotations.isEmpty()) {
                null
            } else {
                annotations.toMutableList()
            }
        return TextModifiers(codebase, flags, newAnnotations)
    }

    fun addAnnotations(annotationSources: List<String>) {
        if (annotationSources.isEmpty()) {
            return
        }

        val annotations = ArrayList<AnnotationItem>(annotationSources.size)
        annotationSources.forEach { source ->
            val item = codebase.createAnnotation(source)

            // @Deprecated is also treated as a "modifier"
            if (item.qualifiedName == JAVA_LANG_DEPRECATED) {
                setDeprecated(true)
            }

            annotations.add(item)
        }
        this.annotations = annotations
    }

    override fun toString(): String {
        val item = owner()
        val writer = StringWriter()
        ModifierList.write(writer, this, item, target = AnnotationTarget.SDK_STUBS_FILE)
        return writer.toString()
    }
}
