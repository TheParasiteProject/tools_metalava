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

import com.android.tools.metalava.model.ArrayTypeItem
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.ClassTypeItem
import com.android.tools.metalava.model.DefaultTypeItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.JAVA_LANG_PREFIX
import com.android.tools.metalava.model.PrimitiveTypeItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.TypeModifiers
import com.android.tools.metalava.model.TypeParameterItem
import com.android.tools.metalava.model.VariableTypeItem
import com.android.tools.metalava.model.WildcardTypeItem
import java.util.function.Predicate

sealed class TextTypeItem(open val codebase: TextCodebase, open val type: String) :
    DefaultTypeItem(codebase) {
    override fun toString(): String = type

    override fun toTypeString(
        annotations: Boolean,
        kotlinStyleNulls: Boolean,
        context: Item?,
        filter: Predicate<Item>?,
        spaceBetweenParameters: Boolean
    ): String {
        if (!kotlinStyleNulls) {
            return super.toTypeString(
                annotations,
                kotlinStyleNulls,
                context,
                filter,
                spaceBetweenParameters
            )
        }

        val typeString = toTypeString(type, annotations)

        if (kotlinStyleNulls && this !is PrimitiveTypeItem && context != null) {
            var nullable: Boolean? = context.implicitNullness()

            if (nullable == null) {
                for (annotation in context.modifiers.annotations()) {
                    if (annotation.isNullable()) {
                        nullable = true
                    } else if (annotation.isNonNull()) {
                        nullable = false
                    }
                }
            }
            when (nullable) {
                null -> return "$typeString!"
                true -> return "$typeString?"
                else -> {
                    /* non-null: nothing to add */
                }
            }
        }
        return typeString
    }

    override fun asClass(): ClassItem? {
        if (this is PrimitiveTypeItem) {
            return null
        }
        val cls = run {
            val erased = toErasedTypeString()
            // Also chop off array dimensions
            val index = erased.indexOf('[')
            if (index != -1) {
                erased.substring(0, index)
            } else {
                erased
            }
        }
        return codebase.getOrCreateClass(cls)
    }

    private fun qualifiedTypeName(): String = type

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        return when (other) {
            // Note: when we support type-use annotations, this is not safe: there could be a string
            // literal inside which is significant
            is TextTypeItem -> TypeItem.equalsWithoutSpace(toString(), other.toString())
            is TypeItem -> {
                val thisString = toTypeString()
                val otherString = other.toTypeString()
                if (TypeItem.equalsWithoutSpace(thisString, otherString)) {
                    return true
                }
                if (
                    thisString.startsWith(JAVA_LANG_PREFIX) &&
                        thisString.endsWith(otherString) &&
                        thisString.length == otherString.length + JAVA_LANG_PREFIX.length
                ) {
                    // When reading signature files, it's sometimes ambiguous whether a name
                    // references a java.lang. implicit class or a type parameter.
                    return true
                }

                return false
            }
            else -> false
        }
    }

    override fun hashCode(): Int {
        return qualifiedTypeName().hashCode()
    }

    override fun convertType(replacementMap: Map<String, String>?, owner: Item?): TypeItem {
        return codebase.typeResolver.obtainTypeFromString(convertTypeString(replacementMap))
    }

    companion object {
        fun toTypeString(
            type: String,
            annotations: Boolean,
        ): String = if (annotations) type else eraseAnnotations(type)

        fun eraseTypeArguments(s: String): String {
            val index = s.indexOf('<')
            if (index != -1) {
                var balance = 0
                for (i in index..s.length) {
                    val c = s[i]
                    if (c == '<') {
                        balance++
                    } else if (c == '>') {
                        balance--
                        if (balance == 0) {
                            return if (i == s.length - 1) {
                                s.substring(0, index)
                            } else {
                                s.substring(0, index) + s.substring(i + 1)
                            }
                        }
                    }
                }

                return s.substring(0, index)
            }
            return s
        }

        /**
         * Given a type possibly using the Kotlin-style null syntax, strip out any Kotlin-style null
         * syntax characters, e.g. "String?" -> "String", but make sure not to damage types like
         * "Set<? extends Number>".
         */
        fun stripKotlinNullChars(s: String): String {
            var found = false
            var prev = ' '
            for (c in s) {
                if (c == '!' || c == '?' && (prev != '<' && prev != ',' && prev != ' ')) {
                    found = true
                    break
                }
                prev = c
            }

            if (!found) {
                return s
            }

            val sb = StringBuilder(s.length)
            for (c in s) {
                if (c == '!' || c == '?' && (prev != '<' && prev != ',' && prev != ' ')) {
                    // skip
                } else {
                    sb.append(c)
                }
                prev = c
            }

            return sb.toString()
        }

        private fun eraseAnnotations(type: String): String {
            if (type.indexOf('@') == -1) {
                // If using Kotlin-style null syntax, strip those markers as well
                return stripKotlinNullChars(type)
            }

            // Assumption: top level annotations appear first
            val length = type.length
            var max = length

            var s = type
            while (true) {
                val index = s.indexOf('@')
                if (index == -1 || index >= max) {
                    break
                }

                // Find end
                val end = TextTypeParser.findAnnotationEnd(s, index + 1)
                val oldLength = s.length
                s = s.substring(0, index).trim() + s.substring(end).trim()
                val newLength = s.length
                val removed = oldLength - newLength
                max -= removed
            }

            // Sometimes we have a second type after the max, such as
            // @androidx.annotation.NonNull java.lang.reflect.@androidx.annotation.NonNull
            // TypeVariable<...>
            for (i in s.indices) {
                val c = s[i]
                if (Character.isJavaIdentifierPart(c) || c == '.') {
                    continue
                } else if (c == '@') {
                    // Found embedded annotation within the type
                    val end = TextTypeParser.findAnnotationEnd(s, i + 1)
                    if (end == -1 || end == length) {
                        break
                    }

                    s = s.substring(0, i).trim() + s.substring(end).trim()
                    break
                } else {
                    break
                }
            }

            return s
        }
    }
}

/** A [PrimitiveTypeItem] parsed from a signature file. */
internal class TextPrimitiveTypeItem(
    override val codebase: TextCodebase,
    override val type: String,
    override val kind: PrimitiveTypeItem.Primitive,
    override val modifiers: TypeModifiers
) : PrimitiveTypeItem, TextTypeItem(codebase, type)

/** An [ArrayTypeItem] parsed from a signature file. */
internal class TextArrayTypeItem(
    override val codebase: TextCodebase,
    override val type: String,
    override val componentType: TypeItem,
    override val isVarargs: Boolean,
    override val modifiers: TypeModifiers
) : ArrayTypeItem, TextTypeItem(codebase, type)

/** A [ClassTypeItem] parsed from a signature file. */
internal class TextClassTypeItem(
    override val codebase: TextCodebase,
    override val type: String,
    override val qualifiedName: String,
    override val parameters: List<TypeItem>,
    override val outerClassType: ClassTypeItem?,
    override val modifiers: TypeModifiers
) : ClassTypeItem, TextTypeItem(codebase, type) {
    override val className: String = ClassTypeItem.computeClassName(qualifiedName)
}

/** A [VariableTypeItem] parsed from a signature file. */
internal class TextVariableTypeItem(
    override val codebase: TextCodebase,
    override val type: String,
    override val name: String,
    override val asTypeParameter: TypeParameterItem,
    override val modifiers: TypeModifiers
) : VariableTypeItem, TextTypeItem(codebase, type)

/** A [WildcardTypeItem] parsed from a signature file. */
internal class TextWildcardTypeItem(
    override val codebase: TextCodebase,
    override val type: String,
    override val extendsBound: TypeItem?,
    override val superBound: TypeItem?,
    override val modifiers: TypeModifiers
) : WildcardTypeItem, TextTypeItem(codebase, type)
