/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.android.tools.metalava.model.ANDROIDX_NONNULL
import com.android.tools.metalava.model.ANDROIDX_NULLABLE
import com.android.tools.metalava.model.AnnotationItem.Companion.unshortenAnnotation
import com.android.tools.metalava.model.AnnotationManager
import com.android.tools.metalava.model.ArrayTypeItem
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.ClassResolver
import com.android.tools.metalava.model.DefaultModifierList
import com.android.tools.metalava.model.JAVA_LANG_ANNOTATION
import com.android.tools.metalava.model.JAVA_LANG_DEPRECATED
import com.android.tools.metalava.model.JAVA_LANG_ENUM
import com.android.tools.metalava.model.JAVA_LANG_OBJECT
import com.android.tools.metalava.model.JAVA_LANG_THROWABLE
import com.android.tools.metalava.model.MetalavaApi
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.PrimitiveTypeItem
import com.android.tools.metalava.model.PrimitiveTypeItem.Primitive
import com.android.tools.metalava.model.TypeParameterItem
import com.android.tools.metalava.model.TypeParameterList
import com.android.tools.metalava.model.TypeParameterList.Companion.NONE
import com.android.tools.metalava.model.VisibilityLevel
import com.android.tools.metalava.model.javaUnescapeString
import com.android.tools.metalava.model.noOpAnnotationManager
import com.android.tools.metalava.model.text.TextTypeParameterList.Companion.create
import com.android.tools.metalava.model.text.TextTypeParser.Companion.isPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import kotlin.text.Charsets.UTF_8

@MetalavaApi
class ApiFile
private constructor(
    /** Implements [ResolverContext] interface */
    override val classResolver: ClassResolver?,
    private val formatForLegacyFiles: FileFormat?
) : ResolverContext {

    /**
     * Whether types should be interpreted to be in Kotlin format (e.g. ? suffix means nullable, !
     * suffix means unknown, and absence of a suffix means not nullable.
     *
     * Updated based on the header of the signature file being parsed.
     */
    private var kotlinStyleNulls: Boolean = false

    /** The file format of the file being parsed. */
    lateinit var format: FileFormat

    private val mClassToSuper = HashMap<TextClassItem, String>(30000)
    private val mClassToInterface = HashMap<TextClassItem, ArrayList<String>>(10000)

    companion object {
        /**
         * Same as [.parseApi]}, but take a single file for convenience.
         *
         * @param file input signature file
         */
        fun parseApi(
            file: File,
            annotationManager: AnnotationManager,
        ) = parseApi(listOf(file), annotationManager)

        /**
         * Read API signature files into a [TextCodebase].
         *
         * Note: when reading from them multiple files, [TextCodebase.location] would refer to the
         * first file specified. each [com.android.tools.metalava.model.text.TextItem.position]
         * would correctly point out the source file of each item.
         *
         * @param files input signature files
         */
        fun parseApi(
            files: List<File>,
            annotationManager: AnnotationManager = noOpAnnotationManager,
            classResolver: ClassResolver? = null,
            formatForLegacyFiles: FileFormat? = null,
        ): TextCodebase {
            require(files.isNotEmpty()) { "files must not be empty" }
            val api = TextCodebase(files[0], annotationManager)
            val description = StringBuilder("Codebase loaded from ")
            val parser = ApiFile(classResolver, formatForLegacyFiles)
            var first = true
            for (file in files) {
                if (!first) {
                    description.append(", ")
                }
                description.append(file.path)
                val apiText: String =
                    try {
                        file.readText(UTF_8)
                    } catch (ex: IOException) {
                        throw ApiParseException(
                            "Error reading API file",
                            file = file.path,
                            cause = ex
                        )
                    }
                parser.parseApiSingleFile(api, !first, file.path, apiText)
                first = false
            }
            api.description = description.toString()
            parser.postProcess(api)
            return api
        }

        /** <p>DO NOT MODIFY - used by com/android/gts/api/ApprovedApis.java */
        @Deprecated("Exists only for external callers.")
        @JvmStatic
        @MetalavaApi
        @Throws(ApiParseException::class)
        fun parseApi(
            filename: String,
            apiText: String,
            @Suppress("UNUSED_PARAMETER") kotlinStyleNulls: Boolean?,
        ): TextCodebase {
            return parseApi(
                filename,
                apiText,
            )
        }

        /**
         * Parse the API signature file from the [inputStream].
         *
         * This will consume the whole contents of the [inputStream] but it is the caller's
         * responsibility to close it.
         */
        @JvmStatic
        @MetalavaApi
        @Throws(ApiParseException::class)
        fun parseApi(filename: String, inputStream: InputStream): TextCodebase {
            val apiText = inputStream.bufferedReader().readText()
            return parseApi(filename, apiText)
        }

        /** Entry point for testing. Take a filename and content separately. */
        fun parseApi(
            filename: String,
            apiText: String,
            classResolver: ClassResolver? = null,
            formatForLegacyFiles: FileFormat? = null,
        ): TextCodebase {
            val api = TextCodebase(File(filename), noOpAnnotationManager)
            api.description = "Codebase loaded from $filename"
            val parser = ApiFile(classResolver, formatForLegacyFiles)
            parser.parseApiSingleFile(api, false, filename, apiText)
            parser.postProcess(api)
            return api
        }
    }

    /**
     * Perform any final steps to initialize the [TextCodebase] after parsing the signature files.
     */
    private fun postProcess(api: TextCodebase) {
        // Use this as the context for resolving references.
        ReferenceResolver.resolveReferences(this, api)
    }

    private fun parseApiSingleFile(
        api: TextCodebase,
        appending: Boolean,
        filename: String,
        apiText: String,
    ) {
        // Parse the header of the signature file to determine the format. If the signature file is
        // empty then `parseHeader` will return null, so it will default to `FileFormat.V2`.
        format =
            FileFormat.parseHeader(filename, StringReader(apiText), formatForLegacyFiles)
                ?: FileFormat.V2
        kotlinStyleNulls = format.kotlinStyleNulls

        if (appending) {
            // When we're appending, and the content is empty, nothing to do.
            if (apiText.isBlank()) {
                return
            }
        }

        val tokenizer = Tokenizer(filename, apiText.toCharArray())
        while (true) {
            val token = tokenizer.getToken() ?: break
            // TODO: Accept annotations on packages.
            if ("package" == token) {
                parsePackage(api, tokenizer)
            } else {
                throw ApiParseException("expected package got $token", tokenizer)
            }
        }
    }

    private fun parsePackage(api: TextCodebase, tokenizer: Tokenizer) {
        var pkg: TextPackageItem
        var token: String = tokenizer.requireToken()

        // Metalava: including annotations in file now
        val annotations: List<String> = getAnnotations(tokenizer, token)
        val modifiers = DefaultModifierList(api, DefaultModifierList.PUBLIC, null)
        modifiers.addAnnotations(annotations)
        token = tokenizer.current
        tokenizer.assertIdent(token)
        val name: String = token

        // If the same package showed up multiple times, make sure they have the same modifiers.
        // (Packages can't have public/private/etc, but they can have annotations, which are part of
        // ModifierList.)
        // ModifierList doesn't provide equals(), neither does AnnotationItem which ModifierList
        // contains,
        // so we just use toString() here for equality comparison.
        // However, ModifierList.toString() throws if the owner is not yet set, so we have to
        // instantiate an
        // (owner) TextPackageItem here.
        // If it's a duplicate package, then we'll replace pkg with the existing one in the
        // following if block.

        // TODO: However, currently this parser can't handle annotations on packages, so we will
        // never hit this case.
        // Once the parser supports that, we should add a test case for this too.
        pkg = TextPackageItem(api, name, modifiers, tokenizer.pos())
        val existing = api.findPackage(name)
        if (existing != null) {
            if (pkg.modifiers != existing.modifiers) {
                throw ApiParseException(
                    String.format(
                        "Contradicting declaration of package %s. Previously seen with modifiers \"%s\", but now with \"%s\"",
                        name,
                        pkg.modifiers,
                        modifiers
                    ),
                    tokenizer
                )
            }
            pkg = existing
        }
        token = tokenizer.requireToken()
        if ("{" != token) {
            throw ApiParseException("expected '{' got $token", tokenizer)
        }
        while (true) {
            token = tokenizer.requireToken()
            if ("}" == token) {
                break
            } else {
                parseClass(api, pkg, tokenizer, token)
            }
        }
        api.addPackage(pkg)
    }

    private fun mapClassToSuper(classInfo: TextClassItem, superclass: String?) {
        superclass?.let { mClassToSuper.put(classInfo, superclass) }
    }

    private fun mapClassToInterface(classInfo: TextClassItem, iface: String) {
        if (!mClassToInterface.containsKey(classInfo)) {
            mClassToInterface[classInfo] = ArrayList()
        }
        mClassToInterface[classInfo]?.let { if (!it.contains(iface)) it.add(iface) }
    }

    private fun implementsInterface(classInfo: TextClassItem, iface: String): Boolean {
        return mClassToInterface[classInfo]?.contains(iface) ?: false
    }

    /** Implements [ResolverContext] interface */
    override fun namesOfInterfaces(cl: TextClassItem): List<String>? = mClassToInterface[cl]

    /** Implements [ResolverContext] interface */
    override fun nameOfSuperClass(cl: TextClassItem): String? = mClassToSuper[cl]

    private fun parseClass(
        api: TextCodebase,
        pkg: TextPackageItem,
        tokenizer: Tokenizer,
        startingToken: String
    ) {
        var token = startingToken
        var isInterface = false
        var isAnnotation = false
        var isEnum = false
        var ext: String? = null

        // Metalava: including annotations in file now
        val annotations: List<String> = getAnnotations(tokenizer, token)
        token = tokenizer.current
        val modifiers = parseModifiers(api, tokenizer, token, annotations)
        token = tokenizer.current
        when (token) {
            "class" -> {
                token = tokenizer.requireToken()
            }
            "interface" -> {
                isInterface = true
                modifiers.setAbstract(true)
                token = tokenizer.requireToken()
            }
            "@interface" -> {
                // Annotation
                modifiers.setAbstract(true)
                isAnnotation = true
                token = tokenizer.requireToken()
            }
            "enum" -> {
                isEnum = true
                modifiers.setFinal(true)
                modifiers.setStatic(true)
                ext = JAVA_LANG_ENUM
                token = tokenizer.requireToken()
            }
            else -> {
                throw ApiParseException("missing class or interface. got: $token", tokenizer)
            }
        }
        tokenizer.assertIdent(token)
        // The classType and qualifiedClassType include the type parameter string, the className and
        // qualifiedClassName are just the name without type parameters.
        val classType: String = token
        val (className, typeParameters) = parseClassName(api, classType)
        val qualifiedClassType = qualifiedName(pkg.name(), classType)
        val qualifiedClassName = qualifiedName(pkg.name(), className)
        token = tokenizer.requireToken()
        var cl =
            TextClassItem(
                api,
                tokenizer.pos(),
                modifiers,
                isInterface,
                isEnum,
                isAnnotation,
                qualifiedClassName,
                qualifiedClassType,
                className,
                annotations,
                typeParameters
            )

        cl.setContainingPackage(pkg)
        if ("extends" == token && !isInterface) {
            token = getAnnotationCompleteToken(tokenizer, tokenizer.requireToken())
            var superClassName = token
            // Make sure full super class name is found if there are type use annotations.
            // This can't use [parseType] because the next token might be a separate type (classes
            // only have a single `extends` type, but all interface supertypes are listed as
            // `extends` instead of `implements`).
            // However, this type cannot be an array, so unlike [parseType] this does not need to
            // check if the next token has annotations.
            while (isIncompleteTypeToken(token)) {
                token = getAnnotationCompleteToken(tokenizer, tokenizer.current)
                superClassName += " $token"
            }
            ext = superClassName
            token = tokenizer.current
        }
        if ("implements" == token || "extends" == token) {
            token = tokenizer.requireToken()
            while (true) {
                if ("{" == token) {
                    break
                } else if ("," != token) {
                    var interfaceName = getAnnotationCompleteToken(tokenizer, token)
                    // Make sure full interface name is found if there are type use annotations.
                    // This can't use [parseType] because the next token might be a separate type.
                    // However, this type cannot be an array, so unlike [parseType] this does not
                    // need to check if the next token has annotations.
                    while (isIncompleteTypeToken(token)) {
                        token = getAnnotationCompleteToken(tokenizer, tokenizer.current)
                        interfaceName += " $token"
                    }
                    mapClassToInterface(cl, interfaceName)
                    token = tokenizer.current
                } else {
                    token = tokenizer.requireToken()
                }
            }
        }
        if (JAVA_LANG_ENUM == ext) {
            cl.setIsEnum(true)
            // Above we marked all enums as static but for a top level class it's implicit
            if (!cl.fullName().contains(".")) {
                cl.modifiers.setStatic(false)
            }
        } else if (isAnnotation) {
            mapClassToInterface(cl, JAVA_LANG_ANNOTATION)
        } else if (implementsInterface(cl, JAVA_LANG_ANNOTATION)) {
            cl.setIsAnnotationType(true)
        }
        if ("{" != token) {
            throw ApiParseException("expected {, was $token", tokenizer)
        }
        token = tokenizer.requireToken()
        cl =
            when (val foundClass = api.findClass(cl.qualifiedName())) {
                null -> {
                    // Duplicate class is not found, thus update super class string
                    // and keep cl
                    mapClassToSuper(cl, ext)
                    cl
                }
                else -> {
                    if (!foundClass.isCompatible(cl)) {
                        throw ApiParseException("Incompatible $foundClass definitions", cl.position)
                    } else if (mClassToSuper[foundClass] != ext) {
                        // Duplicate class with conflicting superclass names are found.
                        // Since the clas definition found later should be prioritized,
                        // overwrite the superclass name as ext but set cl as
                        // foundClass, where the class attributes are stored
                        // and continue to add methods/fields in foundClass
                        mapClassToSuper(cl, ext)
                        foundClass
                    } else {
                        foundClass
                    }
                }
            }
        while (true) {
            if ("}" == token) {
                break
            } else if ("ctor" == token) {
                token = tokenizer.requireToken()
                parseConstructor(api, tokenizer, cl, token)
            } else if ("method" == token) {
                token = tokenizer.requireToken()
                parseMethod(api, tokenizer, cl, token)
            } else if ("field" == token) {
                token = tokenizer.requireToken()
                parseField(api, tokenizer, cl, token, false)
            } else if ("enum_constant" == token) {
                token = tokenizer.requireToken()
                parseField(api, tokenizer, cl, token, true)
            } else if ("property" == token) {
                token = tokenizer.requireToken()
                parseProperty(api, tokenizer, cl, token)
            } else {
                throw ApiParseException("expected ctor, enum_constant, field or method", tokenizer)
            }
            token = tokenizer.requireToken()
        }
        pkg.addClass(cl)
    }

    /**
     * Splits the class type into its name and type parameter list.
     *
     * For example "Foo" would split into name "Foo" and an empty type parameter list, while "Foo<A,
     * B extends java.lang.String, C>" would split into name "Foo" and type parameter list with "A",
     * "B extends java.lang.String", and "C" as type parameters.
     */
    private fun parseClassName(api: TextCodebase, type: String): Pair<String, TypeParameterList> {
        val paramIndex = type.indexOf('<')
        return if (paramIndex == -1) {
            Pair(type, NONE)
        } else {
            Pair(type.substring(0, paramIndex), create(api, type.substring(paramIndex)))
        }
    }

    private fun processKotlinTypeSuffix(
        startingType: String,
        annotations: MutableList<String>
    ): String {
        var type = startingType
        var varArgs = false
        if (type.endsWith("...")) {
            type = type.substring(0, type.length - 3)
            varArgs = true
        }
        if (kotlinStyleNulls) {
            if (varArgs) {
                mergeAnnotations(annotations, ANDROIDX_NONNULL)
            } else if (type.endsWith("?")) {
                type = type.substring(0, type.length - 1)
                mergeAnnotations(annotations, ANDROIDX_NULLABLE)
            } else if (type.endsWith("!")) {
                type = type.substring(0, type.length - 1)
            } else if (!type.endsWith("!")) {
                if (!isPrimitive(type)) { // Don't add nullness on primitive types like void
                    mergeAnnotations(annotations, ANDROIDX_NONNULL)
                }
            }
        } else if (type.endsWith("?") || type.endsWith("!")) {
            throw ApiParseException(
                "Format $format does not support Kotlin-style null type syntax: $type"
            )
        }
        if (varArgs) {
            type = "$type..."
        }
        return type
    }

    /**
     * If the [startingToken] contains the beginning of an annotation, pulls additional tokens from
     * [tokenizer] to complete the annotation, returning the full token. If there isn't an
     * annotation, returns the original [startingToken].
     *
     * When the method returns, the [tokenizer] will point to the token after the end of the
     * returned string.
     */
    private fun getAnnotationCompleteToken(tokenizer: Tokenizer, startingToken: String): String {
        return if (startingToken.contains('@')) {
            val prefix = startingToken.substringBefore('@')
            val annotationStart = startingToken.substring(startingToken.indexOf('@'))
            val annotation = getAnnotation(tokenizer, annotationStart)
            "$prefix$annotation"
        } else {
            tokenizer.requireToken()
            startingToken
        }
    }

    /**
     * If the [startingToken] is the beginning of an annotation, returns the annotation parsed from
     * the [tokenizer]. Returns null otherwise.
     *
     * When the method returns, the [tokenizer] will point to the token after the annotation.
     */
    private fun getAnnotation(tokenizer: Tokenizer, startingToken: String): String? {
        var token = startingToken
        if (token.startsWith('@')) {
            // Annotation
            var annotation = token

            // Restore annotations that were shortened on export
            annotation = unshortenAnnotation(annotation)
            token = tokenizer.requireToken()
            if (token == "(") {
                // Annotation arguments; potentially nested
                var balance = 0
                val start = tokenizer.offset() - 1
                while (true) {
                    if (token == "(") {
                        balance++
                    } else if (token == ")") {
                        balance--
                        if (balance == 0) {
                            break
                        }
                    }
                    token = tokenizer.requireToken()
                }
                annotation += tokenizer.getStringFromOffset(start)
                // Move the tokenizer so that when the method returns it points to the token after
                // the end of the annotation.
                tokenizer.requireToken()
            }
            return annotation
        } else {
            return null
        }
    }

    /**
     * Collects all the sequential annotations from the [tokenizer] beginning with [startingToken],
     * returning them as a (possibly empty) mutable list.
     *
     * When the method returns, the [tokenizer] will point to the token after the annotation list.
     */
    private fun getAnnotations(tokenizer: Tokenizer, startingToken: String): MutableList<String> {
        val annotations: MutableList<String> = mutableListOf()
        var token = startingToken
        while (true) {
            val annotation = getAnnotation(tokenizer, token) ?: break
            token = tokenizer.current
            annotations.add(annotation)
        }
        return annotations
    }

    private fun parseConstructor(
        api: TextCodebase,
        tokenizer: Tokenizer,
        cl: TextClassItem,
        startingToken: String
    ) {
        var token = startingToken
        val method: TextConstructorItem
        var typeParameterList = NONE

        // Metalava: including annotations in file now
        val annotations: List<String> = getAnnotations(tokenizer, token)
        token = tokenizer.current
        val modifiers = parseModifiers(api, tokenizer, token, annotations)
        token = tokenizer.current
        if ("<" == token) {
            typeParameterList = parseTypeParameterList(api, tokenizer)
            token = tokenizer.requireToken()
        }
        tokenizer.assertIdent(token)
        val name: String =
            token.substring(
                token.lastIndexOf('.') + 1
            ) // For inner classes, strip outer classes from name
        // Collect all type parameters in scope into one list
        val typeParams = typeParameterList.typeParameters() + cl.typeParameterList.typeParameters()
        val parameters = parseParameterList(api, tokenizer, typeParams)
        method =
            TextConstructorItem(api, name, cl, modifiers, cl.toType(), parameters, tokenizer.pos())
        method.setTypeParameterList(typeParameterList)
        if (typeParameterList is TextTypeParameterList) {
            typeParameterList.setOwner(method)
        }
        token = tokenizer.requireToken()
        if ("throws" == token) {
            token = parseThrows(tokenizer, method)
        }
        if (";" != token) {
            throw ApiParseException("expected ; found $token", tokenizer)
        }
        if (!cl.constructors().contains(method)) {
            cl.addConstructor(method)
        }
    }

    private fun parseMethod(
        api: TextCodebase,
        tokenizer: Tokenizer,
        cl: TextClassItem,
        startingToken: String
    ) {
        var token = startingToken
        val method: TextMethodItem
        var typeParameterList = NONE

        // Metalava: including annotations in file now
        val annotations = getAnnotations(tokenizer, token)
        token = tokenizer.current
        val modifiers = parseModifiers(api, tokenizer, token, null)
        token = tokenizer.current
        if ("<" == token) {
            typeParameterList = parseTypeParameterList(api, tokenizer)
            token = tokenizer.requireToken()
        }
        tokenizer.assertIdent(token)
        // Collect all type parameters in scope into one list
        val typeParams = typeParameterList.typeParameters() + cl.typeParameterList.typeParameters()

        val returnType: TextTypeItem
        val parameters: List<TextParameterItem>
        val name: String
        if (format.kotlinNameTypeOrder) {
            // Kotlin style: parse the name, the parameter list, then the return type.
            name = token
            parameters = parseParameterList(api, tokenizer, typeParams)
            token = tokenizer.requireToken()
            if (token != ":") {
                throw ApiParseException(
                    "Expecting \":\" after parameter list, found $token.",
                    tokenizer
                )
            }
            token = tokenizer.requireToken()
            tokenizer.assertIdent(token)
            returnType = parseType(api, tokenizer, token, typeParams, annotations)
            // TODO(b/300081840): update nullability handling
            modifiers.addAnnotations(annotations)
            token = tokenizer.current
        } else {
            // Java style: parse the return type, the name, and then the parameter list.
            returnType = parseType(api, tokenizer, token, typeParams, annotations)
            modifiers.addAnnotations(annotations)
            token = tokenizer.current
            tokenizer.assertIdent(token)
            name = token
            parameters = parseParameterList(api, tokenizer, typeParams)
            token = tokenizer.requireToken()
        }

        if (cl.isInterface() && !modifiers.isDefault() && !modifiers.isStatic()) {
            modifiers.setAbstract(true)
        }
        method = TextMethodItem(api, name, cl, modifiers, returnType, parameters, tokenizer.pos())
        method.setTypeParameterList(typeParameterList)
        if (typeParameterList is TextTypeParameterList) {
            typeParameterList.setOwner(method)
        }
        if ("throws" == token) {
            token = parseThrows(tokenizer, method)
        }
        if ("default" == token) {
            token = parseDefault(tokenizer, method)
        }
        if (";" != token) {
            throw ApiParseException("expected ; found $token", tokenizer)
        }
        if (!cl.methods().contains(method)) {
            cl.addMethod(method)
        }
    }

    private fun mergeAnnotations(
        annotations: MutableList<String>,
        annotation: String
    ): MutableList<String> {
        // Reverse effect of TypeItem.shortenTypes(...)
        val qualifiedName =
            if (annotation.indexOf('.') == -1) "@androidx.annotation$annotation" else "@$annotation"
        annotations.add(qualifiedName)
        return annotations
    }

    private fun parseField(
        api: TextCodebase,
        tokenizer: Tokenizer,
        cl: TextClassItem,
        startingToken: String,
        isEnum: Boolean
    ) {
        var token = startingToken
        val annotations = getAnnotations(tokenizer, token)
        token = tokenizer.current
        val modifiers = parseModifiers(api, tokenizer, token, null)
        token = tokenizer.current
        tokenizer.assertIdent(token)

        val type: TextTypeItem
        val name: String
        if (format.kotlinNameTypeOrder) {
            // Kotlin style: parse the name, then the type.
            name = parseNameWithColon(token, tokenizer)
            token = tokenizer.requireToken()
            tokenizer.assertIdent(token)
            type =
                parseType(api, tokenizer, token, cl.typeParameterList.typeParameters(), annotations)
            // TODO(b/300081840): update nullability handling
            modifiers.addAnnotations(annotations)
            token = tokenizer.current
        } else {
            // Java style: parse the name, then the type.
            type =
                parseType(api, tokenizer, token, cl.typeParameterList.typeParameters(), annotations)
            modifiers.addAnnotations(annotations)
            token = tokenizer.current
            tokenizer.assertIdent(token)
            name = token
            token = tokenizer.requireToken()
        }

        var value: Any? = null
        if ("=" == token) {
            token = tokenizer.requireToken(false)
            value = parseValue(type, token, tokenizer)
            token = tokenizer.requireToken()
        }
        if (";" != token) {
            throw ApiParseException("expected ; found $token", tokenizer)
        }
        val field = TextFieldItem(api, name, cl, modifiers, type, value, tokenizer.pos())
        if (isEnum) {
            cl.addEnumConstant(field)
        } else {
            cl.addField(field)
        }
    }

    private fun parseModifiers(
        api: TextCodebase,
        tokenizer: Tokenizer,
        startingToken: String?,
        annotations: List<String>?
    ): DefaultModifierList {
        var token = startingToken
        val modifiers = DefaultModifierList(api, DefaultModifierList.PACKAGE_PRIVATE, null)
        processModifiers@ while (true) {
            token =
                when (token) {
                    "public" -> {
                        modifiers.setVisibilityLevel(VisibilityLevel.PUBLIC)
                        tokenizer.requireToken()
                    }
                    "protected" -> {
                        modifiers.setVisibilityLevel(VisibilityLevel.PROTECTED)
                        tokenizer.requireToken()
                    }
                    "private" -> {
                        modifiers.setVisibilityLevel(VisibilityLevel.PRIVATE)
                        tokenizer.requireToken()
                    }
                    "internal" -> {
                        modifiers.setVisibilityLevel(VisibilityLevel.INTERNAL)
                        tokenizer.requireToken()
                    }
                    "static" -> {
                        modifiers.setStatic(true)
                        tokenizer.requireToken()
                    }
                    "final" -> {
                        modifiers.setFinal(true)
                        tokenizer.requireToken()
                    }
                    "deprecated" -> {
                        modifiers.setDeprecated(true)
                        tokenizer.requireToken()
                    }
                    "abstract" -> {
                        modifiers.setAbstract(true)
                        tokenizer.requireToken()
                    }
                    "transient" -> {
                        modifiers.setTransient(true)
                        tokenizer.requireToken()
                    }
                    "volatile" -> {
                        modifiers.setVolatile(true)
                        tokenizer.requireToken()
                    }
                    "sealed" -> {
                        modifiers.setSealed(true)
                        tokenizer.requireToken()
                    }
                    "default" -> {
                        modifiers.setDefault(true)
                        tokenizer.requireToken()
                    }
                    "synchronized" -> {
                        modifiers.setSynchronized(true)
                        tokenizer.requireToken()
                    }
                    "native" -> {
                        modifiers.setNative(true)
                        tokenizer.requireToken()
                    }
                    "strictfp" -> {
                        modifiers.setStrictFp(true)
                        tokenizer.requireToken()
                    }
                    "infix" -> {
                        modifiers.setInfix(true)
                        tokenizer.requireToken()
                    }
                    "operator" -> {
                        modifiers.setOperator(true)
                        tokenizer.requireToken()
                    }
                    "inline" -> {
                        modifiers.setInline(true)
                        tokenizer.requireToken()
                    }
                    "value" -> {
                        modifiers.setValue(true)
                        tokenizer.requireToken()
                    }
                    "suspend" -> {
                        modifiers.setSuspend(true)
                        tokenizer.requireToken()
                    }
                    "vararg" -> {
                        modifiers.setVarArg(true)
                        tokenizer.requireToken()
                    }
                    "fun" -> {
                        modifiers.setFunctional(true)
                        tokenizer.requireToken()
                    }
                    "data" -> {
                        modifiers.setData(true)
                        tokenizer.requireToken()
                    }
                    else -> break@processModifiers
                }
        }
        if (annotations != null) {
            modifiers.addAnnotations(annotations)
        }
        return modifiers
    }

    private fun parseValue(type: TextTypeItem, value: String?, tokenizer: Tokenizer): Any? {
        return if (value != null) {
            if (type is PrimitiveTypeItem) {
                parsePrimitiveValue(type, value, tokenizer)
            } else if (type.isString()) {
                if ("null" == value) {
                    null
                } else {
                    javaUnescapeString(value.substring(1, value.length - 1))
                }
            } else {
                value
            }
        } else null
    }

    private fun parsePrimitiveValue(
        type: PrimitiveTypeItem,
        value: String,
        tokenizer: Tokenizer
    ): Any {
        return when (type.kind) {
            Primitive.BOOLEAN ->
                if ("true" == value) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE
            Primitive.BYTE,
            Primitive.SHORT,
            Primitive.INT -> Integer.valueOf(value)
            Primitive.LONG -> java.lang.Long.valueOf(value.substring(0, value.length - 1))
            Primitive.FLOAT ->
                when (value) {
                    "(1.0f/0.0f)",
                    "(1.0f / 0.0f)" -> Float.POSITIVE_INFINITY
                    "(-1.0f/0.0f)",
                    "(-1.0f / 0.0f)" -> Float.NEGATIVE_INFINITY
                    "(0.0f/0.0f)",
                    "(0.0f / 0.0f)" -> Float.NaN
                    else -> java.lang.Float.valueOf(value)
                }
            Primitive.DOUBLE ->
                when (value) {
                    "(1.0/0.0)",
                    "(1.0 / 0.0)" -> Double.POSITIVE_INFINITY
                    "(-1.0/0.0)",
                    "(-1.0 / 0.0)" -> Double.NEGATIVE_INFINITY
                    "(0.0/0.0)",
                    "(0.0 / 0.0)" -> Double.NaN
                    else -> java.lang.Double.valueOf(value)
                }
            Primitive.CHAR -> value.toInt().toChar()
            Primitive.VOID ->
                throw ApiParseException("Found value $value assigned to void type", tokenizer)
        }
    }

    private fun parseProperty(
        api: TextCodebase,
        tokenizer: Tokenizer,
        cl: TextClassItem,
        startingToken: String
    ) {
        var token = startingToken

        // Metalava: including annotations in file now
        val annotations = getAnnotations(tokenizer, token)
        token = tokenizer.current
        val modifiers = parseModifiers(api, tokenizer, token, null)
        token = tokenizer.current
        tokenizer.assertIdent(token)

        val type: TextTypeItem
        val name: String
        if (format.kotlinNameTypeOrder) {
            // Kotlin style: parse the name, then the type.
            name = parseNameWithColon(token, tokenizer)
            token = tokenizer.requireToken()
            tokenizer.assertIdent(token)
            type =
                parseType(api, tokenizer, token, cl.typeParameterList.typeParameters(), annotations)
            // TODO(b/300081840): update nullability handling
            modifiers.addAnnotations(annotations)
            token = tokenizer.current
        } else {
            // Java style: parse the type, then the name.
            type =
                parseType(api, tokenizer, token, cl.typeParameterList.typeParameters(), annotations)
            modifiers.addAnnotations(annotations)
            token = tokenizer.current
            tokenizer.assertIdent(token)
            name = token
            token = tokenizer.requireToken()
        }

        if (";" != token) {
            throw ApiParseException("expected ; found $token", tokenizer)
        }
        val property = TextPropertyItem(api, name, cl, modifiers, type, tokenizer.pos())
        cl.addProperty(property)
    }

    private fun parseTypeParameterList(
        codebase: TextCodebase,
        tokenizer: Tokenizer
    ): TypeParameterList {
        var token: String
        val start = tokenizer.offset() - 1
        var balance = 1
        while (balance > 0) {
            token = tokenizer.requireToken()
            if (token == "<") {
                balance++
            } else if (token == ">") {
                balance--
            }
        }
        val typeParameterList = tokenizer.getStringFromOffset(start)
        return if (typeParameterList.isEmpty()) {
            NONE
        } else {
            create(codebase, typeParameterList)
        }
    }

    /**
     * Parses a list of parameters. Before calling, [tokenizer] should point to the token *before*
     * the opening `(` of the parameter list (the method starts by calling
     * [Tokenizer.requireToken]).
     *
     * When the method returns, [tokenizer] will point to the closing `)` of the parameter list.
     */
    private fun parseParameterList(
        api: TextCodebase,
        tokenizer: Tokenizer,
        typeParameters: List<TypeParameterItem>
    ): List<TextParameterItem> {
        val parameters = mutableListOf<TextParameterItem>()
        var token: String = tokenizer.requireToken()
        if ("(" != token) {
            throw ApiParseException("expected (, was $token", tokenizer)
        }
        token = tokenizer.requireToken()
        var index = 0
        while (true) {
            if (")" == token) {
                return parameters
            }

            // Each item can be
            // optional annotations optional-modifiers type-with-use-annotations-and-generics
            // optional-name optional-equals-default-value

            // Used to represent the presence of a default value, instead of showing the entire
            // default value
            var hasDefaultValue = token == "optional"
            if (hasDefaultValue) {
                token = tokenizer.requireToken()
            }

            // Metalava: including annotations in file now
            val annotations = getAnnotations(tokenizer, token)
            token = tokenizer.current
            val modifiers = parseModifiers(api, tokenizer, token, null)
            token = tokenizer.current

            val type: TextTypeItem
            val name: String
            val publicName: String?
            if (format.kotlinNameTypeOrder) {
                // Kotlin style: parse the name (only considered a public name if it is not `_`,
                // which is used as a placeholder for params without public names), then the type.
                name = parseNameWithColon(token, tokenizer)
                publicName =
                    if (name == "_") {
                        null
                    } else {
                        name
                    }
                token = tokenizer.requireToken()
                // Token should now represent the type
                type = parseType(api, tokenizer, token, typeParameters, annotations)
                // TODO(b/300081840): update nullability handling
                modifiers.addAnnotations(annotations)
                token = tokenizer.current
            } else {
                // Java style: parse the type, then the public name if it has one.
                type = parseType(api, tokenizer, token, typeParameters, annotations)
                modifiers.addAnnotations(annotations)
                token = tokenizer.current
                if (Tokenizer.isIdent(token) && token != "=") {
                    name = token
                    publicName = name
                    token = tokenizer.requireToken()
                } else {
                    name = "arg" + (index + 1)
                    publicName = null
                }
            }
            if (type is ArrayTypeItem && type.isVarargs) {
                modifiers.setVarArg(true)
            }

            var defaultValue = UNKNOWN_DEFAULT_VALUE
            if ("=" == token) {
                defaultValue = tokenizer.requireToken(true)
                val sb = StringBuilder(defaultValue)
                if (defaultValue == "{") {
                    var balance = 1
                    while (balance > 0) {
                        token = tokenizer.requireToken(parenIsSep = false, eatWhitespace = false)
                        sb.append(token)
                        if (token == "{") {
                            balance++
                        } else if (token == "}") {
                            balance--
                            if (balance == 0) {
                                break
                            }
                        }
                    }
                    token = tokenizer.requireToken()
                } else {
                    var balance = if (defaultValue == "(") 1 else 0
                    while (true) {
                        token = tokenizer.requireToken(parenIsSep = true, eatWhitespace = false)
                        if ((token.endsWith(",") || token.endsWith(")")) && balance <= 0) {
                            if (token.length > 1) {
                                sb.append(token, 0, token.length - 1)
                                token = token[token.length - 1].toString()
                            }
                            break
                        }
                        sb.append(token)
                        if (token == "(") {
                            balance++
                        } else if (token == ")") {
                            balance--
                        }
                    }
                }
                defaultValue = sb.toString()
            }
            if (defaultValue != UNKNOWN_DEFAULT_VALUE) {
                hasDefaultValue = true
            }
            when (token) {
                "," -> {
                    token = tokenizer.requireToken()
                }
                ")" -> {
                    // closing parenthesis
                }
                else -> {
                    throw ApiParseException("expected , or ), found $token", tokenizer)
                }
            }
            parameters.add(
                TextParameterItem(
                    api,
                    name,
                    publicName,
                    hasDefaultValue,
                    defaultValue,
                    index,
                    type,
                    modifiers,
                    tokenizer.pos()
                )
            )
            index++
        }
    }

    private fun parseDefault(tokenizer: Tokenizer, method: TextMethodItem): String {
        val sb = StringBuilder()
        while (true) {
            val token = tokenizer.requireToken()
            if (";" == token) {
                method.setAnnotationDefault(sb.toString())
                return token
            } else {
                sb.append(token)
            }
        }
    }

    private fun parseThrows(tokenizer: Tokenizer, method: TextMethodItem): String {
        var token = tokenizer.requireToken()
        var comma = true
        while (true) {
            when (token) {
                ";" -> {
                    return token
                }
                "," -> {
                    if (comma) {
                        throw ApiParseException("Expected exception, got ','", tokenizer)
                    }
                    comma = true
                }
                else -> {
                    if (!comma) {
                        throw ApiParseException("Expected ',' or ';' got $token", tokenizer)
                    }
                    comma = false
                    method.addException(token)
                }
            }
            token = tokenizer.requireToken()
        }
    }

    /**
     * Parses a [TextTypeItem] from the [tokenizer], starting with the [startingToken] and ensuring
     * that the full type string is gathered, even when there are type-use annotations. Once the
     * full type string is found, this parses the type in the context of the [typeParameters].
     *
     * If the type string uses a Kotlin nullabililty suffix, this adds an annotation representing
     * that nullability to [annotations].
     *
     * After this method is called, `tokenizer.current` will point to the token after the type.
     *
     * Note: this **should not** be used when the token after the type could contain annotations,
     * such as when multiple types appear as consecutive tokens. (This happens in the `implements`
     * list of a class definition, e.g. `class Foo implements test.pkg.Bar test.pkg.@A Baz`.)
     *
     * To handle arrays with type-use annotations, this looks forward at the next token and includes
     * it if it contains an annotation. This is necessary to handle type strings like "Foo @A []".
     */
    private fun parseType(
        api: TextCodebase,
        tokenizer: Tokenizer,
        startingToken: String,
        typeParameters: List<TypeParameterItem>,
        annotations: MutableList<String>
    ): TextTypeItem {
        var prev = getAnnotationCompleteToken(tokenizer, startingToken)
        var type = prev
        var token = tokenizer.current
        // Look both at the last used token and the next one:
        // If the last token has annotations, the type string was broken up by annotations, and the
        // next token is also part of the type.
        // If the next token has annotations, this is an array type like "Foo @A []", so the next
        // token is part of the type.
        while (isIncompleteTypeToken(prev) || isIncompleteTypeToken(token)) {
            token = getAnnotationCompleteToken(tokenizer, token)
            type += " $token"
            prev = token
            token = tokenizer.current
        }

        // TODO: this should be handled by [obtainTypeFromString]
        type = processKotlinTypeSuffix(type, annotations)

        return api.typeResolver.obtainTypeFromString(type, typeParameters)
    }

    /**
     * Determines whether the [type] is an incomplete type string broken up by annotations. This is
     * the case when there's an annotation that isn't contained within a parameter list (because
     * [Tokenizer.requireToken] handles not breaking in the middle of a parameter list).
     */
    private fun isIncompleteTypeToken(type: String): Boolean {
        val firstAnnotationIndex = type.indexOf('@')
        val paramStartIndex = type.indexOf('<')
        val lastAnnotationIndex = type.lastIndexOf('@')
        val paramEndIndex = type.lastIndexOf('>')
        return firstAnnotationIndex != -1 &&
            (paramStartIndex == -1 ||
                firstAnnotationIndex < paramStartIndex ||
                paramEndIndex == -1 ||
                paramEndIndex < lastAnnotationIndex)
    }

    /**
     * For Kotlin-style name/type ordering in signature files, the name is generally followed by a
     * colon (besides methods, where the colon comes after the parameter list). This method takes
     * the name [token] and removes the trailing colon, throwing an [ApiParseException] if one isn't
     * present (the [tokenizer] is only used for context for the error, if needed).
     */
    private fun parseNameWithColon(token: String, tokenizer: Tokenizer): String {
        if (!token.endsWith(':')) {
            throw ApiParseException("Expecting name ending with \":\" but found $token.", tokenizer)
        }
        return token.removeSuffix(":")
    }

    private fun qualifiedName(pkg: String, className: String): String {
        return "$pkg.$className"
    }
}

/**
 * Provides access to information that is needed by the [ReferenceResolver].
 *
 * This is provided by [ApiFile] which tracks the names of interfaces and super classes that each
 * class implements/extends respectively before they are resolved.
 */
interface ResolverContext {
    /**
     * Get the names of the interfaces implemented by the supplied class, returns null if there are
     * no interfaces.
     */
    fun namesOfInterfaces(cl: TextClassItem): List<String>?

    /**
     * Get the name of the super class extended by the supplied class, returns null if there is no
     * super class.
     */
    fun nameOfSuperClass(cl: TextClassItem): String?

    /**
     * The optional [ClassResolver] that is used to resolve unknown classes within the
     * [TextCodebase].
     */
    val classResolver: ClassResolver?
}

/** Resolves any references in the codebase, e.g. to superclasses, interfaces, etc. */
class ReferenceResolver(
    private val context: ResolverContext,
    private val codebase: TextCodebase,
) {
    /**
     * A list of all the classes in the text codebase.
     *
     * This takes a copy of the `values` collection rather than use it correctly to avoid
     * [ConcurrentModificationException].
     */
    private val classes = codebase.mAllClasses.values.toList()

    /**
     * A list of all the packages in the text codebase.
     *
     * This takes a copy of the `values` collection rather than use it correctly to avoid
     * [ConcurrentModificationException].
     */
    private val packages = codebase.mPackages.values.toList()

    companion object {
        fun resolveReferences(context: ResolverContext, codebase: TextCodebase) {
            val resolver = ReferenceResolver(context, codebase)
            resolver.resolveReferences()
        }
    }

    fun resolveReferences() {
        resolveSuperclasses()
        resolveInterfaces()
        resolveThrowsClasses()
        resolveInnerClasses()
    }

    /**
     * Gets an existing, or creates a new [ClassItem].
     *
     * @param name the name of the class, may include generics.
     * @param isInterface true if the class must be an interface, i.e. is referenced from an
     *   `implements` list (or Kotlin equivalent).
     * @param mustBeFromThisCodebase true if the class must be from the same codebase as this class
     *   is currently resolving.
     */
    private fun getOrCreateClass(
        name: String,
        isInterface: Boolean = false,
        mustBeFromThisCodebase: Boolean = false
    ): ClassItem {
        return if (mustBeFromThisCodebase) {
            codebase.getOrCreateClass(name, isInterface = isInterface, classResolver = null)
        } else {
            codebase.getOrCreateClass(
                name,
                isInterface = isInterface,
                classResolver = context.classResolver
            )
        }
    }

    private fun resolveSuperclasses() {
        for (cl in classes) {
            // java.lang.Object has no superclass and neither do interfaces
            if (cl.isJavaLangObject() || cl.isInterface()) {
                continue
            }
            var scName: String? = context.nameOfSuperClass(cl)
            if (scName == null) {
                scName =
                    when {
                        cl.isEnum() -> JAVA_LANG_ENUM
                        cl.isAnnotationType() -> JAVA_LANG_ANNOTATION
                        // Interfaces do not extend java.lang.Object so drop out before the else
                        // clause.
                        cl.isInterface() -> return
                        else -> {
                            val existing = cl.superClassType()?.toTypeString()
                            existing ?: JAVA_LANG_OBJECT
                        }
                    }
            }

            val superclass = getOrCreateClass(scName)
            cl.setSuperClass(
                superclass,
                codebase.typeResolver.obtainTypeFromString(
                    scName,
                    cl.typeParameterList.typeParameters()
                )
            )
        }
    }

    private fun resolveInterfaces() {
        for (cl in classes) {
            val interfaces = context.namesOfInterfaces(cl) ?: continue
            for (interfaceName in interfaces) {
                getOrCreateClass(interfaceName, isInterface = true)
                cl.addInterface(
                    codebase.typeResolver.obtainTypeFromString(
                        interfaceName,
                        cl.typeParameterList.typeParameters()
                    )
                )
            }
        }
    }

    private fun resolveThrowsClasses() {
        for (cl in classes) {
            for (methodItem in cl.constructors()) {
                resolveThrowsClasses(methodItem)
            }
            for (methodItem in cl.methods()) {
                resolveThrowsClasses(methodItem)
            }
        }
    }

    private fun resolveThrowsClasses(methodItem: MethodItem) {
        val methodInfo = methodItem as TextMethodItem
        val names = methodInfo.throwsTypeNames()
        if (names.isNotEmpty()) {
            val result = ArrayList<ClassItem>()
            for (exception in names) {
                var exceptionClass: ClassItem? = codebase.mAllClasses[exception]
                if (exceptionClass == null) {
                    // Exception not provided by this codebase. Either try and retrieve it from a
                    // base codebase or create a stub.
                    exceptionClass = getOrCreateClass(exception)

                    // A class retrieved from another codebase is assumed to have been fully
                    // resolved by the codebase. However, a stub that has just been created will
                    // need some additional work. A stub can be differentiated from a ClassItem
                    // retrieved from another codebase because it belongs to this codebase and is
                    // a TextClassItem.
                    if (exceptionClass.codebase == codebase && exceptionClass is TextClassItem) {
                        // An exception class needs to extend Throwable, unless it is Throwable in
                        // which case it does not need modifying.
                        if (exception != JAVA_LANG_THROWABLE) {
                            val throwableClass = getOrCreateClass(JAVA_LANG_THROWABLE)
                            exceptionClass.setSuperClass(throwableClass, throwableClass.toType())
                        }
                    }
                }
                result.add(exceptionClass)
            }
            methodInfo.setThrowsList(result)
        }
    }

    private fun resolveInnerClasses() {
        for (pkg in packages) {
            // make copy: we'll be removing non-top level classes during iteration
            val classes = ArrayList(pkg.classList())
            for (cls in classes) {
                // External classes are already resolved.
                if (cls.codebase != codebase) continue
                val cl = cls as TextClassItem
                val name = cl.name
                var index = name.lastIndexOf('.')
                if (index != -1) {
                    cl.name = name.substring(index + 1)
                    val qualifiedName = cl.qualifiedName
                    index = qualifiedName.lastIndexOf('.')
                    assert(index != -1) { qualifiedName }
                    val outerClassName = qualifiedName.substring(0, index)
                    // If the outer class doesn't exist in the text codebase, it should not be
                    // resolved through the classpath--if it did exist there, this inner class
                    // would be overridden by the version from the classpath.
                    val outerClass = getOrCreateClass(outerClassName, mustBeFromThisCodebase = true)
                    cl.containingClass = outerClass
                    outerClass.addInnerClass(cl)
                }
            }
        }

        for (pkg in packages) {
            pkg.pruneClassList()
        }
    }
}

private fun DefaultModifierList.addAnnotations(annotationSources: List<String>) {
    if (annotationSources.isEmpty()) {
        return
    }

    annotationSources.forEach { source ->
        val item = codebase.createAnnotation(source)

        // @Deprecated is also treated as a "modifier"
        if (item.qualifiedName == JAVA_LANG_DEPRECATED) {
            setDeprecated(true)
        }

        addAnnotation(item)
    }
}

/**
 * Checks if the [cls] from different signature file can be merged with this [TextClassItem]. For
 * instance, `current.txt` and `system-current.txt` may contain equal class definitions with
 * different class methods. This method is used to determine if the two [TextClassItem]s can be
 * safely merged in such scenarios.
 *
 * @param cls [TextClassItem] to be checked if it is compatible with [this] and can be merged
 * @return a Boolean value representing if [cls] is compatible with [this]
 */
private fun TextClassItem.isCompatible(cls: TextClassItem): Boolean {
    if (this === cls) {
        return true
    }
    if (fullName() != cls.fullName()) {
        return false
    }

    return modifiers == cls.modifiers &&
        isInterface() == cls.isInterface() &&
        isEnum() == cls.isEnum() &&
        isAnnotation == cls.isAnnotation &&
        allInterfaces().toSet() == cls.allInterfaces().toSet()
}
