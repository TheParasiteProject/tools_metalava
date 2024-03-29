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

package com.android.tools.metalava.model

import java.io.PrintWriter

@MetalavaApi
interface FieldItem : MemberItem {
    /** The property this field backs; inverse of [PropertyItem.backingField] */
    val property: PropertyItem?
        get() = null

    /** The type of this field */
    @MetalavaApi override fun type(): TypeItem

    override fun findCorrespondingItemIn(codebase: Codebase) =
        containingClass().findCorrespondingItemIn(codebase)?.findField(name())

    /**
     * The initial/constant value, if any. If [requireConstant] the initial value will only be
     * returned if it's constant.
     */
    fun initialValue(requireConstant: Boolean = true): Any?

    /**
     * An enum can contain both enum constants and fields; this method provides a way to distinguish
     * between them.
     */
    fun isEnumConstant(): Boolean

    /**
     * If this field is inherited from a hidden super class, this property is set. This is necessary
     * because these fields should not be listed in signature files, whereas in stub files it's
     * necessary for them to be included.
     */
    var inheritedField: Boolean

    /**
     * If this field is copied from a super class (typically via [duplicate]) this field points to
     * the original class it was copied from
     */
    var inheritedFrom: ClassItem?

    /**
     * Duplicates this field item. Used when we need to insert inherited fields from interfaces etc.
     */
    fun duplicate(targetContainingClass: ClassItem): FieldItem

    override fun accept(visitor: ItemVisitor) {
        visitor.visit(this)
    }

    /**
     * Check the declared value with a typed comparison, not a string comparison, to accommodate
     * toolchains with different fp -> string conversions.
     */
    fun hasSameValue(other: FieldItem): Boolean {
        val thisConstant = initialValue()
        val otherConstant = other.initialValue()
        if (thisConstant == null != (otherConstant == null)) {
            return false
        }

        // Null values are considered equal
        if (thisConstant == null) {
            return true
        }

        if (type() != other.type()) {
            return false
        }

        if (thisConstant == otherConstant) {
            return true
        }

        if (thisConstant.toString() == otherConstant.toString()) {
            // e.g. Integer(3) and Short(3) are the same; when comparing
            // with signature files we sometimes don't have the right
            // types from signatures
            return true
        }

        return false
    }

    override fun hasNullnessInfo(): Boolean {
        if (!requiresNullnessInfo()) {
            return true
        }

        return modifiers.hasNullnessInfo()
    }

    override fun requiresNullnessInfo(): Boolean {
        if (type() is PrimitiveTypeItem) {
            return false
        }

        if (modifiers.isFinal() && initialValue(true) != null) {
            return false
        }

        return true
    }

    override fun implicitNullness(): Boolean? {
        // Delegate to the super class, only dropping through if it did not determine an implicit
        // nullness.
        super.implicitNullness()?.let { nullable ->
            return nullable
        }

        // Constant field not initialized to null?
        if (isEnumConstant() || modifiers.isFinal() && initialValue(false) != null) {
            // Assigned to constant: not nullable
            return false
        }

        return null
    }

    companion object {
        val comparator: java.util.Comparator<FieldItem> = Comparator { a, b ->
            a.name().compareTo(b.name())
        }
    }

    /**
     * If this field has an initial value, it just writes ";", otherwise it writes " = value;" with
     * the correct Java syntax for the initial value
     */
    fun writeValueWithSemicolon(
        writer: PrintWriter,
        allowDefaultValue: Boolean = false,
        requireInitialValue: Boolean = false
    ) {
        val value =
            initialValue(!allowDefaultValue)
                ?: if (allowDefaultValue && !containingClass().isClass()) type().defaultValue()
                else null
        if (value != null) {
            when (value) {
                is Int -> {
                    writer.print(" = ")
                    writer.print(value)
                    writer.print("; // 0x")
                    writer.print(Integer.toHexString(value))
                }
                is String -> {
                    writer.print(" = ")
                    writer.print('"')
                    writer.print(javaEscapeString(value))
                    writer.print('"')
                    writer.print(";")
                }
                is Long -> {
                    writer.print(" = ")
                    writer.print(value)
                    writer.print(String.format("L; // 0x%xL", value))
                }
                is Boolean -> {
                    writer.print(" = ")
                    writer.print(value)
                    writer.print(";")
                }
                is Byte -> {
                    writer.print(" = ")
                    writer.print(value)
                    writer.print("; // 0x")
                    writer.print(Integer.toHexString(value.toInt()))
                }
                is Short -> {
                    writer.print(" = ")
                    writer.print(value)
                    writer.print("; // 0x")
                    writer.print(Integer.toHexString(value.toInt()))
                }
                is Float -> {
                    writer.print(" = ")
                    when {
                        value == Float.POSITIVE_INFINITY -> writer.print("(1.0f/0.0f);")
                        value == Float.NEGATIVE_INFINITY -> writer.print("(-1.0f/0.0f);")
                        java.lang.Float.isNaN(value) -> writer.print("(0.0f/0.0f);")
                        else -> {
                            writer.print(canonicalizeFloatingPointString(value.toString()))
                            writer.print("f;")
                        }
                    }
                }
                is Double -> {
                    writer.print(" = ")
                    when {
                        value == Double.POSITIVE_INFINITY -> writer.print("(1.0/0.0);")
                        value == Double.NEGATIVE_INFINITY -> writer.print("(-1.0/0.0);")
                        java.lang.Double.isNaN(value) -> writer.print("(0.0/0.0);")
                        else -> {
                            writer.print(canonicalizeFloatingPointString(value.toString()))
                            writer.print(";")
                        }
                    }
                }
                is Char -> {
                    writer.print(" = ")
                    val intValue = value.code
                    writer.print(intValue)
                    writer.print("; // ")
                    writer.print(
                        String.format("0x%04x '%s'", intValue, javaEscapeString(value.toString()))
                    )
                }
                else -> {
                    writer.print(';')
                }
            }
        } else {
            // in interfaces etc we must have an initial value
            if (requireInitialValue && !containingClass().isClass()) {
                writer.print(" = null")
            }
            writer.print(';')
        }
    }
}

fun javaEscapeString(str: String): String {
    var result = ""
    val n = str.length
    for (i in 0 until n) {
        val c = str[i]
        result +=
            when (c) {
                '\\' -> "\\\\"
                '\t' -> "\\t"
                '\b' -> "\\b"
                '\r' -> "\\r"
                '\n' -> "\\n"
                '\'' -> "\\'"
                '\"' -> "\\\""
                in ' '..'~' -> c
                else -> String.format("\\u%04x", c.code)
            }
    }
    return result
}

// From doclava1 TextFieldItem#javaUnescapeString
@Suppress("LocalVariableName")
fun javaUnescapeString(str: String): String {
    val n = str.length
    var simple = true
    for (i in 0 until n) {
        val c = str[i]
        if (c == '\\') {
            simple = false
            break
        }
    }
    if (simple) {
        return str
    }

    val buf = StringBuilder(str.length)
    var escaped: Char = 0.toChar()
    val START = 0
    val CHAR1 = 1
    val CHAR2 = 2
    val CHAR3 = 3
    val CHAR4 = 4
    val ESCAPE = 5
    var state = START

    for (i in 0 until n) {
        val c = str[i]
        when (state) {
            START ->
                if (c == '\\') {
                    state = ESCAPE
                } else {
                    buf.append(c)
                }
            ESCAPE ->
                when (c) {
                    '\\' -> {
                        buf.append('\\')
                        state = START
                    }
                    't' -> {
                        buf.append('\t')
                        state = START
                    }
                    'b' -> {
                        buf.append('\b')
                        state = START
                    }
                    'r' -> {
                        buf.append('\r')
                        state = START
                    }
                    'n' -> {
                        buf.append('\n')
                        state = START
                    }
                    '\'' -> {
                        buf.append('\'')
                        state = START
                    }
                    '\"' -> {
                        buf.append('\"')
                        state = START
                    }
                    'u' -> {
                        state = CHAR1
                        escaped = 0.toChar()
                    }
                }
            CHAR1,
            CHAR2,
            CHAR3,
            CHAR4 -> {
                escaped = (escaped.code shl 4).toChar()
                escaped =
                    when (c) {
                        in '0'..'9' -> (escaped.code or (c - '0')).toChar()
                        in 'a'..'f' -> (escaped.code or (10 + (c - 'a'))).toChar()
                        in 'A'..'F' -> (escaped.code or (10 + (c - 'A'))).toChar()
                        else ->
                            throw IllegalArgumentException(
                                "bad escape sequence: '" +
                                    c +
                                    "' at pos " +
                                    i +
                                    " in: \"" +
                                    str +
                                    "\""
                            )
                    }
                if (state == CHAR4) {
                    buf.append(escaped)
                    state = START
                } else {
                    state++
                }
            }
        }
    }
    if (state != START) {
        throw IllegalArgumentException("unfinished escape sequence: $str")
    }
    return buf.toString()
}

/**
 * Returns a canonical string representation of a floating point number. The representation is
 * suitable for use as Java source code. This method also addresses bug #4428022 in the Sun JDK.
 */
// From doclava1
fun canonicalizeFloatingPointString(value: String): String {
    var str = value
    if (str.indexOf('E') != -1) {
        return str
    }

    // 1.0 is the only case where a trailing "0" is allowed.
    // 1.00 is canonicalized as 1.0.
    var i = str.length - 1
    val d = str.indexOf('.')
    while (i >= d + 2 && str[i] == '0') {
        str = str.substring(0, i--)
    }
    return str
}
