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

import com.android.tools.metalava.model.JAVA_LANG_OBJECT
import com.android.tools.metalava.model.PrimitiveTypeItem
import com.android.tools.metalava.model.TypeParameterItem
import java.util.HashMap

/** Parses and caches types for a [codebase]. */
internal class TextTypeParser(val codebase: TextCodebase) {
    private val typeCache = Cache<String, TextTypeItem>()

    /**
     * Creates a [TextTypeItem] representing the type of [cl]. Since this is definitely a class
     * type, the steps in [obtainTypeFromString] aren't needed.
     */
    fun obtainTypeFromClass(cl: TextClassItem): TextTypeItem {
        val params = cl.typeParameterList.typeParameters().map { it.toType() }
        return TextClassTypeItem(
            codebase,
            cl.qualifiedTypeName,
            cl.qualifiedName,
            params,
            null,
            modifiers(emptyList())
        )
    }

    /** Creates or retrieves from cache a [TextTypeItem] representing `java.lang.Object` */
    fun obtainObjectType(): TextTypeItem {
        return typeCache.obtain(JAVA_LANG_OBJECT) {
            TextClassTypeItem(
                codebase,
                JAVA_LANG_OBJECT,
                JAVA_LANG_OBJECT,
                emptyList(),
                null,
                modifiers(emptyList())
            )
        }
    }

    /**
     * Creates or retrieves from the cache a [TextTypeItem] representing [type], in the context of
     * the type parameters from [typeParams], if applicable.
     *
     * The [annotations] are optional leading type-use annotations that have already been removed
     * from the type string.
     */
    fun obtainTypeFromString(
        type: String,
        typeParams: List<TypeParameterItem> = emptyList(),
        annotations: List<String> = emptyList()
    ): TextTypeItem {
        // Only use the cache if there are no type parameters to prevent identically named type
        // variables from different contexts being parsed as the same type.
        // Also don't use the cache when there are type-use annotations not contained in the string.
        return if (typeParams.isEmpty() && annotations.isEmpty()) {
            typeCache.obtain(type) { parseType(it, typeParams, annotations) }
        } else {
            parseType(type, typeParams, annotations)
        }
    }

    /** Converts the [type] to a [TextTypeItem] in the context of the [typeParams]. */
    private fun parseType(
        type: String,
        typeParams: List<TypeParameterItem>,
        annotations: List<String> = emptyList()
    ): TextTypeItem {
        val (unannotated, annotationsFromString) = trimLeadingAnnotations(type)
        val allAnnotations = annotations + annotationsFromString
        val (withoutNullability, suffix) = splitNullabilitySuffix(unannotated)
        val trimmed = withoutNullability.trim()

        // Figure out what kind of type this is. Start with the simple cases: primitive or variable.
        return asPrimitive(type, trimmed, allAnnotations)
            ?: asVariable(type, trimmed, typeParams, allAnnotations)
            // Try parsing as a wildcard before trying to parse as an array.
            // `? extends java.lang.String[]` should be parsed as a wildcard with an array bound,
            // not as an array of wildcards, for consistency with how this would be compiled.
            ?: asWildcard(type, trimmed, typeParams, allAnnotations)
            // Try parsing as an array.
            ?: asArray(trimmed, allAnnotations, suffix, typeParams)
            // If it isn't anything else, parse the type as a class.
            ?: asClass(type, trimmed, typeParams, allAnnotations)
    }

    /**
     * Try parsing [type] as a primitive. This will return a non-null [TextPrimitiveTypeItem] if
     * [type] exactly matches a primitive name.
     *
     * [type] should have annotations and nullability markers stripped, with [original] as the
     * complete annotated type. Once annotations are properly handled (b/300081840), preserving
     * [original] won't be necessary.
     */
    private fun asPrimitive(
        original: String,
        type: String,
        annotations: List<String>
    ): TextPrimitiveTypeItem? {
        val kind =
            when (type) {
                "byte" -> PrimitiveTypeItem.Primitive.BYTE
                "char" -> PrimitiveTypeItem.Primitive.CHAR
                "double" -> PrimitiveTypeItem.Primitive.DOUBLE
                "float" -> PrimitiveTypeItem.Primitive.FLOAT
                "int" -> PrimitiveTypeItem.Primitive.INT
                "long" -> PrimitiveTypeItem.Primitive.LONG
                "short" -> PrimitiveTypeItem.Primitive.SHORT
                "boolean" -> PrimitiveTypeItem.Primitive.BOOLEAN
                "void" -> PrimitiveTypeItem.Primitive.VOID
                else -> return null
            }
        return TextPrimitiveTypeItem(codebase, original, kind, modifiers(annotations))
    }

    /**
     * Try parsing [type] as an array. This will return a non-null [TextArrayTypeItem] if [type]
     * ends with `[]` or `...`.
     *
     * The context [typeParams] are used to parse the component type of the array.
     */
    private fun asArray(
        type: String,
        componentAnnotations: List<String>,
        nullability: String,
        typeParams: List<TypeParameterItem>
    ): TextArrayTypeItem? {
        // Check if this is a regular array or varargs.
        val (inner, varargs) =
            if (type.endsWith("...")) {
                Pair(type.dropLast(3), true)
            } else if (type.endsWith("[]")) {
                Pair(type.dropLast(2), false)
            } else {
                return null
            }

        // Create lists of the annotations and nullability markers for each dimension of the array.
        // These are in separate lists because annotations appear in the type string in order from
        // outermost array annotations to innermost array annotations (for `T @A [] @B [] @ C[]`,
        // `@A` applies to the three-dimensional array, `@B` applies to the inner two-dimensional
        // arrays, and `@C` applies to the inner one-dimensional arrays), while nullability markers
        // appear in order from the innermost array nullability to the outermost array nullability
        // (for `T[]![]?[]`, the three-dimensional array has no nullability marker, the inner
        // two-dimensional arrays have `?` as the nullability marker, and the innermost arrays have
        // `!` as a nullability marker.
        val allAnnotations = mutableListOf<List<String>>()
        // The nullability marker for the outer array is already known, include it in the list.
        val allNullability = mutableListOf(nullability)

        // Remove annotations from the end of the string, add them to the list.
        var annotationsResult = trimTrailingAnnotations(inner)
        var componentString = annotationsResult.first
        allAnnotations.add(annotationsResult.second)

        // Remove nullability marker from the component type, but don't add it to the list yet, as
        // it might not be an array.
        var nullabilityResult = splitNullabilitySuffix(componentString)
        componentString = nullabilityResult.first
        var componentNullability = nullabilityResult.second

        // Work through all layers of arrays to get to the inner component type.
        // Inner arrays can't be varargs.
        while (componentString.endsWith("[]")) {
            // The component is an array, add the nullability to the list.
            allNullability.add(componentNullability)

            // Remove annotations from the end of the string, add them to the list.
            annotationsResult = trimTrailingAnnotations(componentString.removeSuffix("[]"))
            componentString = annotationsResult.first
            allAnnotations.add(annotationsResult.second)

            // Remove nullability marker from the new component type, but don't add it to the list
            // yet, as the next component type might not be an array.
            nullabilityResult = splitNullabilitySuffix(componentString)
            componentString = nullabilityResult.first
            componentNullability = nullabilityResult.second
        }

        // Re-add the component's nullability suffix when parsing the component type, and include
        // the leading annotations already removed from the type string.
        componentString += componentNullability
        val deepComponentType =
            obtainTypeFromString(componentString, typeParams, componentAnnotations)

        // Join the annotations and nullability markers -- as described in the comment above, these
        // appear in the string in reverse order of each other. The modifiers list will be ordered
        // from innermost array modifiers to outermost array modifiers.
        val allModifiers =
            allAnnotations.zip(allNullability.reversed()).map { (annotations, _) ->
                // TODO: use the nullability
                modifiers(annotations)
            }
        // The final modifiers are in the list apply to the outermost array.
        val componentModifiers = allModifiers.dropLast(1)
        val arrayModifiers = allModifiers.last()
        // Create the component type of the outermost array by building up the inner component type.
        val componentType =
            componentModifiers.fold(deepComponentType) { component, modifiers ->
                TextArrayTypeItem(codebase, "$component[]", component, false, modifiers)
            }

        // Create the outer array.
        val reassembledTypeString =
            reassembleArrayTypeString(
                deepComponentType,
                componentAnnotations,
                allAnnotations,
                allNullability,
                varargs
            )
        return TextArrayTypeItem(
            codebase,
            reassembledTypeString,
            componentType,
            varargs,
            arrayModifiers
        )
    }

    /**
     * Reassemble the full text of the array. The reason this is needed instead of simply using the
     * original type like the other constructors do is that the component type might be an implicit
     * `java.lang` type. If that's true, we need to add the `java.lang` prefix to the array type
     * too. Once annotations and nullability are properly handled (b/300081840), this shouldn't be
     * necessary.
     *
     * This isn't the case for any other complex types, because java.lang is only stripped from the
     * beginning of a type string and wildcard bounds and class parameters are at the end.
     */
    private fun reassembleArrayTypeString(
        deepComponentType: TextTypeItem,
        deepComponentAnnotations: List<String>,
        allArrayAnnotations: List<List<String>>,
        allNullability: List<String>,
        varargs: Boolean
    ): String {
        if (allArrayAnnotations.isNotEmpty()) {
            // This is an array type -- make the component string, then add modifiers.
            val component =
                reassembleArrayTypeString(
                    deepComponentType = deepComponentType,
                    deepComponentAnnotations = deepComponentAnnotations,
                    // Drop the modifiers for the outermost level of the string.
                    allArrayAnnotations = allArrayAnnotations.drop(1),
                    allNullability = allNullability.drop(1),
                    // Inner array types can't be varargs
                    varargs = false
                )
            val outerArrayAnnotations = allArrayAnnotations.first()
            val trailingAnnotations =
                if (outerArrayAnnotations.isEmpty()) ""
                else {
                    " " + outerArrayAnnotations.joinToString(" ") + " "
                }
            val suffix = (if (varargs) "..." else "[]") + allNullability.first()
            return "$component$trailingAnnotations$suffix"
        } else {
            // End of the recursion, create a string for the non-array component type.
            val leadingAnnotations =
                if (deepComponentAnnotations.isEmpty()) ""
                else {
                    deepComponentAnnotations.joinToString(" ") + " "
                }
            return "$leadingAnnotations$deepComponentType"
        }
    }

    /**
     * Try parsing [type] as a wildcard. This will return a non-null [TextWildcardTypeItem] if
     * [type] begins with `?`.
     *
     * The context [typeParams] are needed to parse the bounds of the wildcard.
     *
     * [type] should have annotations and nullability markers stripped, with [original] as the
     * complete annotated type. Once annotations are properly handled (b/300081840), preserving
     * [original] won't be necessary.
     */
    private fun asWildcard(
        original: String,
        type: String,
        typeParams: List<TypeParameterItem>,
        annotations: List<String>
    ): TextWildcardTypeItem? {
        // See if this is a wildcard
        if (!type.startsWith("?")) return null

        // Unbounded wildcard type: there is an implicit Object extends bound
        if (type == "?")
            return TextWildcardTypeItem(
                codebase,
                type,
                obtainObjectType(),
                null,
                modifiers(annotations)
            )

        val bound = type.substring(2)
        return if (bound.startsWith("extends")) {
            val extendsBound = bound.substring(8)
            TextWildcardTypeItem(
                codebase,
                original,
                obtainTypeFromString(extendsBound, typeParams),
                null,
                modifiers(annotations)
            )
        } else if (bound.startsWith("super")) {
            val superBound = bound.substring(6)
            TextWildcardTypeItem(
                codebase,
                original,
                // All wildcards have an implicit Object extends bound
                obtainObjectType(),
                obtainTypeFromString(superBound, typeParams),
                modifiers(annotations)
            )
        } else {
            throw ApiParseException(
                "Type starts with \"?\" but doesn't appear to be wildcard: $type"
            )
        }
    }

    /**
     * Try parsing [type] as a type variable. This will return a non-null [TextVariableTypeItem] if
     * [type] matches a parameter from [typeParams].
     *
     * [type] should have annotations and nullability markers stripped, with [original] as the
     * complete annotated type. Once annotations are properly handled (b/300081840), preserving
     * [original] won't be necessary.
     */
    private fun asVariable(
        original: String,
        type: String,
        typeParams: List<TypeParameterItem>,
        annotations: List<String>
    ): TextVariableTypeItem? {
        val param = typeParams.firstOrNull { it.simpleName() == type } ?: return null
        return TextVariableTypeItem(codebase, original, type, param, modifiers(annotations))
    }

    /**
     * Parse the [type] as a class. This function will always return a non-null [TextClassTypeItem],
     * so it should only be used when it is certain that [type] is not a different kind of type.
     *
     * The context [typeParams] are used to parse the parameters of the class type.
     *
     * [type] should have annotations and nullability markers stripped, with [original] as the
     * complete annotated type. Once annotations are properly handled (b/300081840), preserving
     * [original] won't be necessary.
     */
    private fun asClass(
        original: String,
        type: String,
        typeParams: List<TypeParameterItem>,
        annotations: List<String>
    ): TextClassTypeItem {
        return createClassType(original, type, null, typeParams, annotations)
    }

    /**
     * Creates a class name for the class represented by [type] with optional qualified name prefix
     * [outerQualifiedName].
     *
     * For instance, `test.pkg.Outer<P1>` would be the [outerQualifiedName] when parsing `Inner<P2>`
     * from the [original] type `test.pkg.Outer<P1>.Inner<P2>`.
     */
    private fun createClassType(
        original: String,
        type: String,
        outerClassType: TextClassTypeItem?,
        typeParams: List<TypeParameterItem>,
        annotations: List<String>
    ): TextClassTypeItem {
        val (name, afterName, classAnnotations) = splitClassType(type)
        val allAnnotations = annotations + classAnnotations

        val (qualifiedName, fullName) =
            if (outerClassType != null) {
                // This is an inner type, add the prefix of the outer name
                Pair("${outerClassType.qualifiedName}.$name", original)
            } else if (!name.contains('.')) {
                // Reverse the effect of [TypeItem.stripJavaLangPrefix].
                Pair("java.lang.$name", "java.lang.$original")
            } else {
                Pair(name, original)
            }

        val (paramStrings, remainder) = typeParameterStringsWithRemainder(afterName)
        val params = paramStrings.map { obtainTypeFromString(it, typeParams) }
        val classType =
            TextClassTypeItem(
                codebase,
                fullName,
                qualifiedName,
                params,
                outerClassType,
                modifiers(allAnnotations)
            )

        if (remainder != null) {
            if (!remainder.startsWith('.')) {
                throw ApiParseException(
                    "Could not parse type `$type`. Found unexpected string after type parameters: $remainder"
                )
            }
            // This is an inner class type, recur with the new outer class
            return createClassType(
                fullName,
                remainder.substring(1),
                classType,
                typeParams,
                emptyList()
            )
        }

        return classType
    }

    private fun modifiers(annotations: List<String>): TextTypeModifiers {
        return TextTypeModifiers.create(codebase, annotations)
    }

    private class Cache<Key, Value> {
        private val cache = HashMap<Key, Value>()

        fun obtain(o: Key, make: (Key) -> Value): Value {
            var r = cache[o]
            if (r == null) {
                r = make(o)
                cache[o] = r
            }
            // r must be non-null: either it was cached or created with make
            return r!!
        }
    }

    companion object {
        /** Whether the string represents a primitive type. */
        fun isPrimitive(type: String): Boolean {
            return when (type) {
                "byte",
                "char",
                "double",
                "float",
                "int",
                "long",
                "short",
                "boolean",
                "void" -> true
                else -> false
            }
        }

        /**
         * Splits the Kotlin-style nullability marker off the type string, returning a pair of the
         * cleaned type string and the nullability suffix.
         */
        fun splitNullabilitySuffix(type: String): Pair<String, String> {
            // Don't interpret the wildcard type `?` as a nullability marker.
            return if (type.length == 1) {
                Pair(type, "")
            } else if (type.endsWith("?") || type.endsWith("!")) {
                Pair(type.dropLast(1), type.last().toString())
            } else {
                Pair(type, "")
            }
        }

        /**
         * Removes all annotations at the beginning of the type, returning the trimmed type and list
         * of annotations.
         */
        fun trimLeadingAnnotations(type: String): Pair<String, List<String>> {
            val annotations = mutableListOf<String>()
            var trimmed = type.trim()
            while (trimmed.startsWith('@')) {
                val end = findAnnotationEnd(trimmed, 1)
                annotations.add(trimmed.substring(0, end).trim())
                trimmed = trimmed.substring(end).trim()
            }
            return Pair(trimmed, annotations)
        }

        /**
         * Removes all annotations at the end of the [type], returning the trimmed type and list of
         * annotations. This is for use with arrays where annotations applying to the array type go
         * after the component type, for instance `String @A []`. The input [type] should **not**
         * include the array suffix (`[]` or `...`).
         */
        fun trimTrailingAnnotations(type: String): Pair<String, List<String>> {
            // The simple way to implement this would be to work from the end of the string, finding
            // `@` and removing annotations from the end. However, it is possible for an annotation
            // string to contain an `@`, so this is not a safe way to remove the annotations.
            // Instead, this finds all annotations starting from the beginning of the string, then
            // works backwards to find which ones are the trailing annotations.
            val allAnnotationIndices = mutableListOf<Pair<Int, Int>>()
            var trimmed = type.trim()

            // First find all annotations, saving the first and last index.
            var currIndex = 0
            while (currIndex < trimmed.length) {
                if (trimmed[currIndex] == '@') {
                    val endIndex = findAnnotationEnd(trimmed, currIndex + 1)
                    allAnnotationIndices.add(Pair(currIndex, endIndex))
                    currIndex = endIndex + 1
                } else {
                    currIndex++
                }
            }

            val annotations = mutableListOf<String>()
            // Go through all annotations from the back, seeing if they're at the end of the string.
            for ((start, end) in allAnnotationIndices.reversed()) {
                // This annotation isn't at the end, so we've hit the last trailing annotation
                if (end < trimmed.length) {
                    break
                }
                annotations.add(trimmed.substring(start))
                // Cut this annotation off, so now the next one can end at the last index.
                trimmed = trimmed.substring(0, start).trim()
            }
            return Pair(trimmed, annotations.reversed())
        }

        /**
         * Given [type] which represents a class, splits the string into the qualified name of the
         * class, the remainder of the type string, and a list of type-use annotations. The
         * remainder of the type string might be the type parameter list, inner class names, or a
         * combination
         *
         * For `java.util.@A @B List<java.lang.@C String>`, returns the triple ("java.util.List",
         * "<java.lang.@C String", listOf("@A", "@B")).
         *
         * For `test.pkg.Outer.Inner`, returns the triple ("test.pkg.Outer", ".Inner", emptyList()).
         *
         * For `test.pkg.@test.pkg.A Outer<P1>.@test.pkg.B Inner<P2>`, returns the triple
         * ("test.pkg.Outer", "<P1>.@test.pkg.B Inner<P2>", listOf("@test.pkg.A")).
         */
        fun splitClassType(type: String): Triple<String, String?, List<String>> {
            // The constructed qualified type name
            var name = ""
            // The part of the type which still needs to be parsed
            var remaining = type.trim()
            // The annotations of the type, may be set later
            var annotations = emptyList<String>()

            var dotIndex = remaining.indexOf('.')
            var paramIndex = remaining.indexOf('<')
            var annotationIndex = remaining.indexOf('@')

            // Find which of '.', '<', or '@' comes first, if any
            var minIndex = minIndex(dotIndex, paramIndex, annotationIndex)
            while (minIndex != null) {
                when (minIndex) {
                    // '.' is first, the next part is part of the qualified class name.
                    dotIndex -> {
                        val nextNameChunk = remaining.substring(0, dotIndex)
                        name += nextNameChunk
                        remaining = remaining.substring(dotIndex)
                        // Assumes that package names are all lower case and class names will have
                        // an upper class character (the [START_WITH_UPPER] API lint check should
                        // make this a safe assumption). If the name is a class name, we've found
                        // the complete class name, return.
                        if (nextNameChunk.any { it.isUpperCase() }) {
                            return Triple(name, remaining, annotations)
                        }
                    }
                    // '<' is first, the end of the class name has been reached.
                    paramIndex -> {
                        name += remaining.substring(0, paramIndex)
                        remaining = remaining.substring(paramIndex)
                        return Triple(name, remaining, annotations)
                    }
                    // '@' is first, trim all annotations.
                    annotationIndex -> {
                        name += remaining.substring(0, annotationIndex)
                        trimLeadingAnnotations(remaining.substring(annotationIndex)).let {
                            (first, second) ->
                            remaining = first
                            annotations = second
                        }
                    }
                }
                // Reset indices -- the string may now start with '.' for the next chunk of the name
                // but this should find the end of the next chunk.
                dotIndex = remaining.indexOf('.', 1)
                paramIndex = remaining.indexOf('<')
                annotationIndex = remaining.indexOf('@')
                minIndex = minIndex(dotIndex, paramIndex, annotationIndex)
            }
            // End of the name reached with no leftover string.
            name += remaining
            return Triple(name, null, annotations)
        }

        /**
         * Returns the minimum valid list index from the input, or null if there isn't one. -1 is
         * not a valid index.
         */
        private fun minIndex(vararg index: Int): Int? = index.filter { it != -1 }.minOrNull()

        /**
         * Given a string and the index in that string which is the start of an annotation (the
         * character _after_ the `@`), returns the index of the end of the annotation.
         */
        fun findAnnotationEnd(type: String, start: Int): Int {
            var index = start
            val length = type.length
            var balance = 0
            while (index < length) {
                val c = type[index]
                if (c == '(') {
                    balance++
                } else if (c == ')') {
                    balance--
                    if (balance == 0) {
                        return index + 1
                    }
                } else if (c != '.' && !Character.isJavaIdentifierPart(c) && balance == 0) {
                    break
                }
                index++
            }
            return index
        }

        /**
         * Breaks a string representing type parameters into a list of the type parameter strings.
         *
         * E.g. `"<A, B, C>"` -> `["A", "B", "C"]` and `"<List<A>, B>"` -> `["List<A>", "B"]`.
         */
        fun typeParameterStrings(typeString: String?): List<String> {
            return typeParameterStringsWithRemainder(typeString).first
        }

        /**
         * Breaks a string representing type parameters into a list of the type parameter strings,
         * and also returns the remainder of the string after the closing ">".
         *
         * E.g. `"<A, B, C>.Inner"` -> `Pair(["A", "B", "C"], ".Inner")`
         */
        fun typeParameterStringsWithRemainder(typeString: String?): Pair<List<String>, String?> {
            val s = typeString ?: return Pair(emptyList(), null)
            if (!s.startsWith("<")) return Pair(emptyList(), s)
            val list = mutableListOf<String>()
            var balance = 0
            var expect = false
            var start = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '<') {
                    balance++
                    expect = balance == 1
                } else if (c == '>') {
                    balance--
                    if (balance == 0) {
                        add(list, s, start, i)
                        return if (i == s.length - 1) {
                            Pair(list, null)
                        } else {
                            Pair(list, s.substring(i + 1))
                        }
                    }
                } else if (c == ',') {
                    expect =
                        if (balance == 1) {
                            add(list, s, start, i)
                            true
                        } else {
                            false
                        }
                } else {
                    // This is the start of a parameter
                    if (expect && balance == 1) {
                        start = i
                        expect = false
                    }

                    if (c == '@') {
                        // Skip the entire text of the annotation
                        i = findAnnotationEnd(typeString, i + 1)
                        continue
                    }
                }
                i++
            }
            return Pair(list, null)
        }

        /**
         * Adds the substring of [s] from [from] to [to] to the [list], trimming whitespace from the
         * front.
         */
        private fun add(list: MutableList<String>, s: String, from: Int, to: Int) {
            for (i in from until to) {
                if (!Character.isWhitespace(s[i])) {
                    list.add(s.substring(i, to))
                    return
                }
            }
        }
    }
}
