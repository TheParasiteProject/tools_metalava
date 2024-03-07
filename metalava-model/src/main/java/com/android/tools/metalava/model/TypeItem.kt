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

import java.util.function.Predicate

/**
 * Whether metalava supports type use annotations. Note that you can't just turn this flag back on;
 * you have to also add TYPE_USE back to the handful of nullness annotations in
 * stub-annotations/src/main/java/.
 */
const val SUPPORT_TYPE_USE_ANNOTATIONS = false

/**
 * Represents a {@link https://docs.oracle.com/javase/8/docs/api/java/lang/reflect/Type.html Type}
 */
@MetalavaApi
interface TypeItem {
    /** Modifiers for the type. Contains type-use annotation information. */
    val modifiers: TypeModifiers

    fun accept(visitor: TypeVisitor)

    /**
     * Generates a string for this type.
     *
     * @param annotations For a type like this: @Nullable java.util.List<@NonNull java.lang.String>,
     *   [annotations] controls whether the annotations like @Nullable and @NonNull are included.
     * @param kotlinStyleNulls Controls whether it should return "@Nullable List<String>" as
     *   "List<String!>?".
     * @param filter Specifies a filter to apply to the type annotations, if any.
     * @param spaceBetweenParameters Controls whether there should be a space between class type
     *   parameters, e.g. "java.util.Map<java.lang.Integer, java.lang.Number>" or
     *   "java.util.Map<java.lang.Integer,java.lang.Number>".
     */
    fun toTypeString(
        annotations: Boolean = false,
        kotlinStyleNulls: Boolean = false,
        context: Item? = null,
        filter: Predicate<Item>? = null,
        spaceBetweenParameters: Boolean = false
    ): String

    /** Legacy alias for [toErasedTypeString]`()`. */
    @Deprecated(
        "the context item is no longer used",
        replaceWith = ReplaceWith("toErasedTypeString()")
    )
    @MetalavaApi
    fun toErasedTypeString(context: Item?): String = toErasedTypeString()

    /**
     * Get a string representation of the erased type.
     *
     * Implements the behavior described
     * [here](https://docs.oracle.com/javase/tutorial/java/generics/genTypes.html).
     *
     * One point to note is that vararg parameters are represented using standard array syntax, i.e.
     * `[]`, not the special source `...` syntax. The reason for that is that the erased type is
     * mainly used at runtime which treats a vararg parameter as a standard array type.
     */
    @MetalavaApi fun toErasedTypeString(): String

    /** Array dimensions of this type; for example, for String it's 0 and for String[][] it's 2. */
    @MetalavaApi fun arrayDimensions(): Int = 0

    /** Returns the internal name of the type, as seen in bytecode. */
    fun internalName(): String

    fun asClass(): ClassItem?

    fun toSimpleType(): String {
        return stripJavaLangPrefix(toTypeString())
    }

    /**
     * Helper methods to compare types, especially types from signature files with types from
     * parsing, which may have slightly different formats, e.g. varargs ("...") versus arrays
     * ("[]"), java.lang. prefixes removed in wildcard signatures, etc.
     */
    fun toCanonicalType(context: Item? = null): String {
        var s = toTypeString(context = context)
        while (s.contains(JAVA_LANG_PREFIX)) {
            s = s.replace(JAVA_LANG_PREFIX, "")
        }
        if (s.contains("...")) {
            s = s.replace("...", "[]")
        }

        return s
    }

    fun convertType(from: ClassItem, to: ClassItem): TypeItem {
        val map = from.mapTypeVariables(to)
        if (map.isNotEmpty()) {
            return convertType(map)
        }

        return this
    }

    fun convertType(replacementMap: Map<String, String>?, owner: Item? = null): TypeItem

    fun convertTypeString(replacementMap: Map<String, String>?): String {
        val typeString = toTypeString(annotations = true, kotlinStyleNulls = false)
        return convertTypeString(typeString, replacementMap)
    }

    fun isJavaLangObject(): Boolean {
        return toTypeString() == JAVA_LANG_OBJECT
    }

    fun isString(): Boolean {
        return toTypeString() == JAVA_LANG_STRING
    }

    fun defaultValue(): Any? = null

    fun defaultValueString(): String = "null"

    fun hasTypeArguments(): Boolean = toTypeString().contains("<")

    companion object {
        /** Shortens types, if configured */
        fun shortenTypes(type: String): String {
            var cleaned = type
            if (cleaned.contains("@androidx.annotation.")) {
                cleaned = cleaned.replace("@androidx.annotation.", "@")
            }
            return stripJavaLangPrefix(cleaned)
        }

        /**
         * Removes java.lang. prefixes from types, unless it's in a subpackage such as
         * java.lang.reflect. For simplicity we may also leave inner classes in the java.lang
         * package untouched.
         *
         * NOTE: We only remove this from the front of the type; e.g. we'll replace
         * java.lang.Class<java.lang.String> with Class<java.lang.String>. This is because the
         * signature parsing of types is not 100% accurate and we don't want to run into trouble
         * with more complicated generic type signatures where we end up not mapping the simplified
         * types back to the real fully qualified type names.
         */
        fun stripJavaLangPrefix(type: String): String {
            if (type.startsWith(JAVA_LANG_PREFIX)) {
                // Replacing java.lang is harder, since we don't want to operate in sub packages,
                // e.g. java.lang.String -> String, but java.lang.reflect.Method -> unchanged
                val start = JAVA_LANG_PREFIX.length
                val end = type.length
                for (index in start until end) {
                    if (type[index] == '<') {
                        return type.substring(start)
                    } else if (type[index] == '.') {
                        return type
                    }
                }

                return type.substring(start)
            }

            return type
        }

        fun formatType(type: String?): String {
            return if (type == null) {
                ""
            } else cleanupGenerics(type)
        }

        fun cleanupGenerics(signature: String): String {
            // <T extends java.lang.Object> is the same as <T>
            //  but NOT for <T extends Object & java.lang.Comparable> -- you can't
            //  shorten this to <T & java.lang.Comparable
            // return type.replace(" extends java.lang.Object", "")
            return signature.replace(" extends java.lang.Object>", ">")
        }

        /**
         * Create a [Comparator] that when given two [TypeItem] will treat them as equal if either
         * returns `null` from [TypeItem.asClass] and will otherwise compare the two [ClassItem]s
         * using [comparator].
         *
         * This only defines a partial ordering over [TypeItem].
         */
        private fun typeItemAsClassComparator(
            comparator: Comparator<ClassItem>
        ): Comparator<TypeItem> {
            return Comparator { type1, type2 ->
                val cls1 = type1.asClass()
                val cls2 = type2.asClass()
                if (cls1 != null && cls2 != null) {
                    comparator.compare(cls1, cls2)
                } else {
                    0
                }
            }
        }

        /** A total ordering over [TypeItem] comparing [TypeItem.toTypeString]. */
        private val typeStringComparator =
            Comparator.comparing<TypeItem, String> { it.toTypeString() }

        /**
         * A total ordering over [TypeItem] comparing [TypeItem.asClass] using
         * [ClassItem.fullNameThenQualifierComparator] and then comparing [TypeItem.toTypeString].
         */
        val totalComparator: Comparator<TypeItem> =
            typeItemAsClassComparator(ClassItem.fullNameThenQualifierComparator)
                .thenComparing(typeStringComparator)

        @Deprecated(
            "" +
                "this should not be used as it only defines a partial ordering which means that the " +
                "source order will affect the result"
        )
        val partialComparator: Comparator<TypeItem> = Comparator { type1, type2 ->
            val cls1 = type1.asClass()
            val cls2 = type2.asClass()
            if (cls1 != null && cls2 != null) {
                ClassItem.fullNameComparator.compare(cls1, cls2)
            } else {
                type1.toTypeString().compareTo(type2.toTypeString())
            }
        }

        fun convertTypeString(typeString: String, replacementMap: Map<String, String>?): String {
            var string = typeString
            if (replacementMap != null && replacementMap.isNotEmpty()) {
                // This is a moved method (typically an implementation of an interface
                // method provided in a hidden superclass), with generics signatures.
                // We need to rewrite the generics variables in case they differ
                // between the classes.
                if (replacementMap.isNotEmpty()) {
                    replacementMap.forEach { (from, to) ->
                        // We can't just replace one string at a time:
                        // what if I have a map of {"A"->"B", "B"->"C"} and I tried to convert
                        // A,B,C?
                        // If I do the replacements one letter at a time I end up with C,C,C; if I
                        // do the substitutions
                        // simultaneously I get B,C,C. Therefore, we insert "___" as a magical
                        // prefix to prevent
                        // scenarios like this, and then we'll drop them afterwards.
                        string =
                            string.replace(Regex(pattern = """\b$from\b"""), replacement = "___$to")
                    }
                }
                string = string.replace("___", "")
                return string
            } else {
                return string
            }
        }

        /**
         * Convert a type string containing to its lambda representation or return the original.
         *
         * E.g.: `"kotlin.jvm.functions.Function1<Integer, String>"` to `"(Integer) -> String"`.
         */
        fun toLambdaFormat(typeName: String): String {
            // Bail if this isn't a Kotlin function type
            if (!typeName.startsWith(KOTLIN_FUNCTION_PREFIX)) {
                return typeName
            }

            // Find the first character after the first opening angle bracket. This will either be
            // the first character of the paramTypes of the lambda if it has parameters.
            val paramTypesStart =
                typeName.indexOf('<', startIndex = KOTLIN_FUNCTION_PREFIX.length) + 1

            // The last type param is always the return type. We find and set these boundaries with
            // the push down loop below.
            var paramTypesEnd = -1
            var returnTypeStart = -1

            // Get the exclusive end of the return type parameter by finding the last closing
            // angle bracket.
            val returnTypeEnd = typeName.lastIndexOf('>')

            // Bail if an an unexpected format broke the indexOf's above.
            if (paramTypesStart <= 0 || paramTypesStart >= returnTypeEnd) {
                return typeName
            }

            // This loop looks for the last comma that is not inside the type parameters of a type
            // parameter. It's a simple push down state machine that stores its depth as a counter
            // instead of a stack. It runs backwards from the last character of the type parameters
            // just before the last closing angle bracket to the beginning just before the first
            // opening angle bracket.
            var depth = 0
            for (i in returnTypeEnd - 1 downTo paramTypesStart) {
                val c = typeName[i]

                // Increase or decrease stack depth on angle brackets
                when (c) {
                    '>' -> depth++
                    '<' -> depth--
                }

                when {
                    depth == 0 ->
                        when { // At the top level
                            c == ',' -> {
                                // When top level comma is found, mark it as the exclusive end of
                                // the
                                // parameter types and end the loop
                                paramTypesEnd = i
                                break
                            }
                            !c.isWhitespace() -> {
                                // Keep moving the start of the return type back until whitespace
                                returnTypeStart = i
                            }
                        }
                    depth < 0 -> return typeName // Bail, unbalanced nesting
                }
            }

            // Bail if some sort of unbalanced nesting occurred or the indices around the comma
            // appear grossly incorrect.
            if (depth > 0 || returnTypeStart < 0 || returnTypeStart <= paramTypesEnd) {
                return typeName
            }

            return buildString(typeName.length) {
                append("(")

                // Slice param types, if any, and append them between the parenthesis
                if (paramTypesEnd > 0) {
                    append(typeName, paramTypesStart, paramTypesEnd)
                }

                append(") -> ")

                // Slice out the return type param and append it after the arrow
                append(typeName, returnTypeStart, returnTypeEnd)
            }
        }

        /** Prefix of Kotlin JVM function types, used for lambdas. */
        private const val KOTLIN_FUNCTION_PREFIX = "kotlin.jvm.functions.Function"

        /** Compares two strings, ignoring space diffs (spaces, not whitespace in general) */
        fun equalsWithoutSpace(s1: String, s2: String): Boolean {
            if (s1 == s2) {
                return true
            }
            val sp1 = s1.indexOf(' ') // first space
            val sp2 = s2.indexOf(' ')
            if (sp1 == -1 && sp2 == -1) {
                // no spaces in strings and aren't equal
                return false
            }

            val l1 = s1.length
            val l2 = s2.length
            var i1 = 0
            var i2 = 0

            while (i1 < l1 && i2 < l2) {
                var c1 = s1[i1++]
                var c2 = s2[i2++]

                while (c1 == ' ' && i1 < l1) {
                    c1 = s1[i1++]
                }
                while (c2 == ' ' && i2 < l2) {
                    c2 = s2[i2++]
                }
                if (c1 != c2) {
                    return false
                }
            }
            // Skip trailing spaces
            while (i1 < l1 && s1[i1] == ' ') {
                i1++
            }
            while (i2 < l2 && s2[i2] == ' ') {
                i2++
            }
            return i1 == l1 && i2 == l2
        }
    }
}

abstract class DefaultTypeItem(private val codebase: Codebase) : TypeItem {

    private lateinit var cachedDefaultType: String
    private lateinit var cachedErasedType: String

    override fun toTypeString(
        annotations: Boolean,
        kotlinStyleNulls: Boolean,
        context: Item?,
        filter: Predicate<Item>?,
        spaceBetweenParameters: Boolean
    ): String {
        return toTypeString(
            TypeStringConfiguration(
                codebase,
                annotations,
                kotlinStyleNulls,
                filter,
                spaceBetweenParameters
            )
        )
    }

    private fun toTypeString(configuration: TypeStringConfiguration): String {
        // Cache the default type string. Other configurations are less likely to be reused.
        return if (configuration.isDefault) {
            if (!::cachedDefaultType.isInitialized) {
                cachedDefaultType = buildString {
                    appendTypeString(this@DefaultTypeItem, configuration)
                }
            }
            cachedDefaultType
        } else {
            buildString { appendTypeString(this@DefaultTypeItem, configuration) }
        }
    }

    override fun toErasedTypeString(): String {
        if (!::cachedErasedType.isInitialized) {
            cachedErasedType = buildString { appendErasedTypeString(this@DefaultTypeItem) }
        }
        return cachedErasedType
    }

    override fun internalName(): String {
        // Default implementation; PSI subclass is more accurate
        return toSlashFormat(toErasedTypeString())
    }

    companion object {
        /**
         * Configuration options for how to represent a type as a string.
         *
         * @param codebase The codebase the type is in.
         * @param annotations Whether to include annotations on the type.
         * @param kotlinStyleNulls Whether to represent nullability with Kotlin-style suffixes: `?`
         *   for nullable, no suffix for non-null, and `!` for platform nullability. For example,
         *   the Java type `@Nullable List<String>` would be represented as `List<String!>?`.
         * @param filter A filter to apply to the type annotations, if any.
         * @param spaceBetweenParameters Whether to include a space between class type params.
         */
        private data class TypeStringConfiguration(
            val codebase: Codebase,
            val annotations: Boolean = false,
            val kotlinStyleNulls: Boolean = false,
            val filter: Predicate<Item>? = null,
            val spaceBetweenParameters: Boolean = false,
        ) {
            val isDefault =
                !annotations && !kotlinStyleNulls && filter == null && !spaceBetweenParameters
        }

        private fun StringBuilder.appendTypeString(
            type: TypeItem,
            configuration: TypeStringConfiguration
        ) {
            when (type) {
                is PrimitiveTypeItem -> {
                    if (configuration.annotations) {
                        appendAnnotations(type.modifiers, configuration)
                    }
                    append(type.kind.primitiveName)
                }
                is ArrayTypeItem -> {
                    // The ordering of array annotations means this can't just use a recursive
                    // approach for annotated multi-dimensional arrays, but it can if annotations
                    // aren't included.
                    if (configuration.annotations) {
                        var deepComponentType = type.componentType
                        val arrayModifiers = mutableListOf(type.modifiers)
                        while (deepComponentType is ArrayTypeItem) {
                            arrayModifiers.add(deepComponentType.modifiers)
                            deepComponentType = deepComponentType.componentType
                        }

                        // Print the innermost component type.
                        appendTypeString(deepComponentType, configuration)

                        // Print modifiers from the outermost array type in, and the array suffixes.
                        arrayModifiers.forEachIndexed { index, modifiers ->
                            appendAnnotations(modifiers, configuration, leadingSpace = true)
                            // Only the outermost array can be varargs.
                            if (index < arrayModifiers.size - 1 || !type.isVarargs) {
                                append("[]")
                            } else {
                                append("...")
                            }
                        }
                    } else {
                        // Non-annotated case: just recur to the component
                        appendTypeString(type.componentType, configuration)
                        if (type.isVarargs) {
                            append("...")
                        } else {
                            append("[]")
                        }
                    }
                    // TODO: kotlin nulls
                }
                is ClassTypeItem -> {
                    if (type.outerClassType != null) {
                        appendTypeString(type.outerClassType!!, configuration)
                        append('.')
                        if (configuration.annotations) {
                            appendAnnotations(type.modifiers, configuration)
                        }
                        append(type.className)
                    } else {
                        if (configuration.annotations) {
                            append(type.qualifiedName.substringBeforeLast(type.className))
                            appendAnnotations(type.modifiers, configuration)
                            append(type.className)
                        } else {
                            append(type.qualifiedName)
                        }
                    }

                    if (type.parameters.isNotEmpty()) {
                        append("<")
                        type.parameters.forEachIndexed { index, parameter ->
                            appendTypeString(parameter, configuration)
                            if (index != type.parameters.size - 1) {
                                append(",")
                                if (configuration.spaceBetweenParameters) {
                                    append(" ")
                                }
                            }
                        }
                        append(">")
                    }
                    // TODO: kotlin nulls
                }
                is VariableTypeItem -> {
                    if (configuration.annotations) {
                        appendAnnotations(type.modifiers, configuration)
                    }
                    append(type.name)
                    // TODO: kotlin nulls
                }
                is WildcardTypeItem -> {
                    if (configuration.annotations) {
                        appendAnnotations(type.modifiers, configuration)
                    }
                    append("?")
                    type.extendsBound?.let {
                        // Leave out object bounds, because they're implied
                        if (!it.isJavaLangObject()) {
                            append(" extends ")
                            appendTypeString(it, configuration)
                        }
                    }
                    type.superBound?.let {
                        append(" super ")
                        appendTypeString(it, configuration)
                    }
                }
            }
        }

        private fun StringBuilder.appendAnnotations(
            modifiers: TypeModifiers,
            configuration: TypeStringConfiguration,
            leadingSpace: Boolean = false,
            trailingSpace: Boolean = true
        ) {
            val annotations =
                modifiers.annotations().filter { annotation ->
                    val filter = configuration.filter ?: return@filter true
                    val qualifiedName = annotation.qualifiedName ?: return@filter true
                    val annotationClass =
                        configuration.codebase.findClass(qualifiedName) ?: return@filter true
                    filter.test(annotationClass)
                }
            if (annotations.isEmpty()) return

            if (leadingSpace) {
                append(' ')
            }
            annotations.forEachIndexed { index, annotation ->
                append(annotation.toSource())
                if (index != annotations.size - 1) {
                    append(' ')
                }
            }
            if (trailingSpace) {
                append(' ')
            }
        }

        private fun StringBuilder.appendErasedTypeString(type: TypeItem) {
            when (type) {
                is PrimitiveTypeItem -> append(type.kind.primitiveName)
                is ArrayTypeItem -> {
                    appendErasedTypeString(type.componentType)
                    append("[]")
                }
                is ClassTypeItem -> append(type.qualifiedName)
                is VariableTypeItem ->
                    type.asTypeParameter.typeBounds().firstOrNull()?.let {
                        appendErasedTypeString(it)
                    }
                        ?: append(JAVA_LANG_OBJECT)
                else ->
                    throw IllegalStateException(
                        "should never visit $type of type ${type.javaClass} while generating erased type string"
                    )
            }
        }

        // Copied from doclava1
        private fun toSlashFormat(typeName: String): String {
            var name = typeName
            var dimension = ""
            while (name.endsWith("[]")) {
                dimension += "["
                name = name.substring(0, name.length - 2)
            }

            val base: String
            base =
                when (name) {
                    "void" -> "V"
                    "byte" -> "B"
                    "boolean" -> "Z"
                    "char" -> "C"
                    "short" -> "S"
                    "int" -> "I"
                    "long" -> "J"
                    "float" -> "F"
                    "double" -> "D"
                    else -> "L" + getInternalName(name) + ";"
                }

            return dimension + base
        }

        /**
         * Computes the internal class name of the given fully qualified class name. For example, it
         * converts foo.bar.Foo.Bar into foo/bar/Foo$Bar
         *
         * @param qualifiedName the fully qualified class name
         * @return the internal class name
         */
        private fun getInternalName(qualifiedName: String): String {
            if (qualifiedName.indexOf('.') == -1) {
                return qualifiedName
            }

            // If class name contains $, it's not an ambiguous inner class name.
            if (qualifiedName.indexOf('$') != -1) {
                return qualifiedName.replace('.', '/')
            }
            // Let's assume that components that start with Caps are class names.
            return buildString {
                var prev: String? = null
                for (part in qualifiedName.split(".")) {
                    if (!prev.isNullOrEmpty()) {
                        if (Character.isUpperCase(prev[0])) {
                            append('$')
                        } else {
                            append('/')
                        }
                    }
                    append(part)
                    prev = part
                }
            }
        }
    }
}

/** Represents a primitive type, like int or boolean. */
interface PrimitiveTypeItem : TypeItem {
    /** The kind of [Primitive] this type is. */
    val kind: Primitive

    /** The possible kinds of primitives. */
    enum class Primitive(
        val primitiveName: String,
        val defaultValue: Any?,
        val defaultValueString: String
    ) {
        BOOLEAN("boolean", false, "false"),
        BYTE("byte", 0.toByte(), "0"),
        CHAR("char", 0.toChar(), "0"),
        DOUBLE("double", 0.0, "0"),
        FLOAT("float", 0F, "0"),
        INT("int", 0, "0"),
        LONG("long", 0L, "0"),
        SHORT("short", 0.toShort(), "0"),
        VOID("void", null, "null")
    }

    override fun defaultValue(): Any? = kind.defaultValue

    override fun defaultValueString(): String = kind.defaultValueString

    override fun accept(visitor: TypeVisitor) {
        visitor.visit(this)
    }
}

/** Represents an array type, including vararg types. */
interface ArrayTypeItem : TypeItem {
    /** The array's inner type (which for multidimensional arrays is another array type). */
    val componentType: TypeItem

    /** Whether this array type represents a varargs parameter. */
    val isVarargs: Boolean

    override fun arrayDimensions(): Int = 1 + componentType.arrayDimensions()

    override fun accept(visitor: TypeVisitor) {
        visitor.visit(this)
    }
}

/** Represents a class type. */
interface ClassTypeItem : TypeItem {
    /** The qualified name of this class, e.g. "java.lang.String". */
    val qualifiedName: String

    /** The class's parameter types, empty if it has none. */
    val parameters: List<TypeItem>

    /** The outer class type of this class, if it is an inner type. */
    val outerClassType: ClassTypeItem?

    /**
     * The name of the class, e.g. "String" for "java.lang.String" and "Inner" for
     * "test.pkg.Outer.Inner".
     */
    val className: String

    override fun accept(visitor: TypeVisitor) {
        visitor.visit(this)
    }

    companion object {
        /** Computes the simple name of a class from a qualified class name. */
        fun computeClassName(qualifiedName: String): String {
            val lastDotIndex = qualifiedName.lastIndexOf('.')
            return if (lastDotIndex == -1) {
                qualifiedName
            } else {
                qualifiedName.substring(lastDotIndex + 1)
            }
        }
    }
}

/** Represents a type variable type. */
interface VariableTypeItem : TypeItem {
    /** The name of the type variable */
    val name: String

    /** The corresponding type parameter for this type variable. */
    val asTypeParameter: TypeParameterItem

    override fun accept(visitor: TypeVisitor) {
        visitor.visit(this)
    }
}

/**
 * Represents a wildcard type, like `?`, `? extends String`, and `? super String` in Java, or `*`,
 * `out String`, and `in String` in Kotlin.
 */
interface WildcardTypeItem : TypeItem {
    /** The type this wildcard must extend. If null, the extends bound is implicitly `Object`. */
    val extendsBound: TypeItem?

    /** The type this wildcard must be a super class of. */
    val superBound: TypeItem?

    override fun accept(visitor: TypeVisitor) {
        visitor.visit(this)
    }
}
