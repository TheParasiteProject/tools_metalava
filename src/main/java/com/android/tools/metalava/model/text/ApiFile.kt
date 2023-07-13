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

import com.android.SdkConstants.DOT_TXT
import com.android.tools.lint.checks.infrastructure.stripComments
import com.android.tools.metalava.ANDROIDX_NONNULL
import com.android.tools.metalava.ANDROIDX_NULLABLE
import com.android.tools.metalava.FileFormat
import com.android.tools.metalava.FileFormat.Companion.parseHeader
import com.android.tools.metalava.JAVA_LANG_ANNOTATION
import com.android.tools.metalava.JAVA_LANG_ENUM
import com.android.tools.metalava.JAVA_LANG_OBJECT
import com.android.tools.metalava.JAVA_LANG_STRING
import com.android.tools.metalava.JAVA_LANG_THROWABLE
import com.android.tools.metalava.model.AnnotationItem.Companion.unshortenAnnotation
import com.android.tools.metalava.model.AnnotationManager
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.ClassResolver
import com.android.tools.metalava.model.DefaultAnnotationManager
import com.android.tools.metalava.model.DefaultModifierList
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.TypeParameterList
import com.android.tools.metalava.model.TypeParameterList.Companion.NONE
import com.android.tools.metalava.model.VisibilityLevel
import com.android.tools.metalava.model.javaUnescapeString
import com.android.tools.metalava.model.text.TextTypeItem.Companion.isPrimitive
import com.android.tools.metalava.model.text.TextTypeParameterList.Companion.create
import com.google.common.annotations.VisibleForTesting
import com.google.common.io.Files
import java.io.File
import java.io.IOException
import javax.annotation.Nonnull
import kotlin.text.Charsets.UTF_8

class ApiFile(
    /** Implements [ResolverContext] interface */
    override val classResolver: ClassResolver?
) : ResolverContext {

    /**
     * Whether types should be interpreted to be in Kotlin format (e.g. ? suffix means nullable, !
     * suffix means unknown, and absence of a suffix means not nullable.
     *
     * Updated based on the header of the signature file being parsed.
     */
    private var kotlinStyleNulls: Boolean = false

    /** The file format of the file being parsed. */
    var format: FileFormat = FileFormat.UNKNOWN

    private val mClassToSuper = HashMap<TextClassItem, String>(30000)
    private val mClassToInterface = HashMap<TextClassItem, ArrayList<String>>(10000)

    companion object {
        /**
         * Same as [.parseApi]}, but take a single file for convenience.
         *
         * @param file input signature file
         */
        @Throws(ApiParseException::class)
        fun parseApi(
            @Nonnull file: File,
            annotationManager: AnnotationManager,
        ) = parseApi(listOf(file), null, annotationManager)

        /**
         * Read API signature files into a [TextCodebase].
         *
         * Note: when reading from them multiple files, [TextCodebase.location] would refer to the
         * first file specified. each [com.android.tools.metalava.model.text.TextItem.position]
         * would correctly point out the source file of each item.
         *
         * @param files input signature files
         */
        @Throws(ApiParseException::class)
        fun parseApi(
            @Nonnull files: List<File>,
            classResolver: ClassResolver? = null,
            annotationManager: AnnotationManager,
        ): TextCodebase {
            require(files.isNotEmpty()) { "files must not be empty" }
            val api = TextCodebase(files[0], annotationManager)
            val description = StringBuilder("Codebase loaded from ")
            val parser = ApiFile(classResolver)
            var first = true
            for (file in files) {
                if (!first) {
                    description.append(", ")
                }
                description.append(file.path)
                val apiText: String =
                    try {
                        Files.asCharSource(file, UTF_8).read()
                    } catch (ex: IOException) {
                        throw ApiParseException("Error reading API file", file.path, ex)
                    }
                parser.parseApiSingleFile(api, !first, file.path, apiText)
                first = false
            }
            api.description = description.toString()
            parser.postProcess(api)
            return api
        }

        /** <p>DO NOT MODIFY - used by com/android/gts/api/ApprovedApis.java */
        @Deprecated("Exists only for external callers. ")
        @JvmStatic
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

        /** Entry point for testing. Take a filename and content separately. */
        @VisibleForTesting
        @Throws(ApiParseException::class)
        fun parseApi(
            @Nonnull filename: String,
            @Nonnull apiText: String,
            classResolver: ClassResolver? = null,
        ): TextCodebase {
            val api = TextCodebase(File(filename), DefaultAnnotationManager())
            api.description = "Codebase loaded from $filename"
            val parser = ApiFile(classResolver)
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

    @Throws(ApiParseException::class)
    private fun parseApiSingleFile(
        api: TextCodebase,
        appending: Boolean,
        filename: String,
        apiText: String,
    ) {
        // Infer the format.
        format = parseHeader(apiText)

        // If it's the first file, set the format. Otherwise, make sure the format is the same as
        // the prior files.
        if (!appending) {
            // This is the first file to process.
            api.format = format
        } else {
            // If we're appending to another API file, make sure the format is the same.
            if (format != api.format) {
                throw ApiParseException(
                    String.format(
                        "Cannot merge different formats of signature files. First file format=%s, current file format=%s: file=%s",
                        api.format,
                        format,
                        filename
                    )
                )
            }
            // When we're appending, and the content is empty, nothing to do.
            if (apiText.isBlank()) {
                return
            }
        }

        if (format.isSignatureFormat()) {
            kotlinStyleNulls = format.useKotlinStyleNulls()
        } else if (apiText.isBlank()) {
            // Sometimes, signature files are empty, and we do want to accept them.
        } else {
            throw ApiParseException("Unknown file format of $filename")
        }

        // Remove the block comments.
        val strippedApiText =
            if (apiText.contains("/*")) {
                stripComments(
                    apiText,
                    DOT_TXT,
                    false
                ) // line comments are used to stash field constants
            } else {
                apiText
            }
        val tokenizer = Tokenizer(filename, strippedApiText.toCharArray())
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

    @Throws(ApiParseException::class)
    private fun parsePackage(api: TextCodebase, tokenizer: Tokenizer) {
        var pkg: TextPackageItem
        var token: String = tokenizer.requireToken()

        // Metalava: including annotations in file now
        val annotations: List<String> = getAnnotations(tokenizer, token)
        val modifiers = TextModifiers(api, DefaultModifierList.PUBLIC, null)
        modifiers.addAnnotations(annotations)
        token = tokenizer.current
        assertIdent(tokenizer, token)
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
            if (pkg.modifiers.toString() != existing.modifiers.toString()) {
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

    @Throws(ApiParseException::class)
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
        assertIdent(tokenizer, token)
        val name: String = token
        val qualifiedName = qualifiedName(pkg.name(), name)
        val typeInfo = api.obtainTypeFromString(qualifiedName)
        // Simple type info excludes the package name (but includes enclosing class names)
        var rawName = name
        val variableIndex = rawName.indexOf('<')
        if (variableIndex != -1) {
            rawName = rawName.substring(0, variableIndex)
        }
        token = tokenizer.requireToken()
        val maybeExistingClass =
            TextClassItem(
                api,
                tokenizer.pos(),
                modifiers,
                isInterface,
                isEnum,
                isAnnotation,
                typeInfo.toErasedTypeString(null),
                typeInfo.qualifiedTypeName(),
                rawName,
                annotations
            )
        val cl =
            when (val foundClass = api.findClass(maybeExistingClass.qualifiedName())) {
                null -> maybeExistingClass
                else -> {
                    if (!foundClass.isCompatible(maybeExistingClass)) {
                        throw ApiParseException("Incompatible $foundClass definitions")
                    } else {
                        foundClass
                    }
                }
            }

        cl.setContainingPackage(pkg)
        cl.setTypeInfo(typeInfo)
        cl.deprecated = modifiers.isDeprecated()
        if ("extends" == token) {
            token = tokenizer.requireToken()
            assertIdent(tokenizer, token)
            ext = token
            token = tokenizer.requireToken()
        }
        // Resolve superclass after done parsing
        mapClassToSuper(cl, ext)
        if (
            "implements" == token ||
                "extends" == token ||
                isInterface && ext != null && token != "{"
        ) {
            if (token != "implements" && token != "extends") {
                mapClassToInterface(cl, token)
            }
            while (true) {
                token = tokenizer.requireToken()
                if ("{" == token) {
                    break
                } else {
                    // / TODO
                    if ("," != token) {
                        mapClassToInterface(cl, token)
                    }
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

    @Throws(ApiParseException::class)
    private fun processKotlinTypeSuffix(
        startingType: String,
        annotations: MutableList<String>
    ): Pair<String, MutableList<String>> {
        var type = startingType
        var varArgs = false
        if (type.endsWith("...")) {
            type = type.substring(0, type.length - 3)
            varArgs = true
        }
        if (kotlinStyleNulls) {
            if (type.endsWith("?")) {
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
        return Pair(type, annotations)
    }

    @Throws(ApiParseException::class)
    private fun getAnnotations(tokenizer: Tokenizer, startingToken: String): MutableList<String> {
        var token = startingToken
        val annotations: MutableList<String> = mutableListOf()
        while (true) {
            if (token.startsWith("@")) {
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
                    token = tokenizer.requireToken()
                }
                annotations.add(annotation)
            } else {
                break
            }
        }
        return annotations
    }

    @Throws(ApiParseException::class)
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
        assertIdent(tokenizer, token)
        val name: String =
            token.substring(
                token.lastIndexOf('.') + 1
            ) // For inner classes, strip outer classes from name
        token = tokenizer.requireToken()
        if ("(" != token) {
            throw ApiParseException("expected (", tokenizer)
        }
        method = TextConstructorItem(api, name, cl, modifiers, cl.asTypeInfo(), tokenizer.pos())
        method.deprecated = modifiers.isDeprecated()
        parseParameterList(api, tokenizer, method)
        method.setTypeParameterList(typeParameterList)
        if (typeParameterList is TextTypeParameterList) {
            typeParameterList.owner = method
        }
        token = tokenizer.requireToken()
        if ("throws" == token) {
            token = parseThrows(tokenizer, method)
        }
        if (";" != token) {
            throw ApiParseException("expected ; found $token", tokenizer)
        }
        cl.addConstructor(method)
    }

    @Throws(ApiParseException::class)
    private fun parseMethod(
        api: TextCodebase,
        tokenizer: Tokenizer,
        cl: TextClassItem,
        startingToken: String
    ) {
        var token = startingToken
        val returnType: TextTypeItem
        val method: TextMethodItem
        var typeParameterList = NONE

        // Metalava: including annotations in file now
        var annotations = getAnnotations(tokenizer, token)
        token = tokenizer.current
        val modifiers = parseModifiers(api, tokenizer, token, null)
        token = tokenizer.current
        if ("<" == token) {
            typeParameterList = parseTypeParameterList(api, tokenizer)
            token = tokenizer.requireToken()
        }
        assertIdent(tokenizer, token)
        val (first, second) = processKotlinTypeSuffix(token, annotations)
        token = first
        annotations = second
        modifiers.addAnnotations(annotations)
        var returnTypeString = token
        token = tokenizer.requireToken()
        if (
            returnTypeString.contains("@") &&
                (returnTypeString.indexOf('<') == -1 ||
                    returnTypeString.indexOf('@') < returnTypeString.indexOf('<'))
        ) {
            returnTypeString += " $token"
            token = tokenizer.requireToken()
        }
        while (true) {
            if (
                token.contains("@") &&
                    (token.indexOf('<') == -1 || token.indexOf('@') < token.indexOf('<'))
            ) {
                // Type-use annotations in type; keep accumulating
                returnTypeString += " $token"
                token = tokenizer.requireToken()
                if (
                    token.startsWith("[")
                ) { // TODO: This isn't general purpose; make requireToken smarter!
                    returnTypeString += " $token"
                    token = tokenizer.requireToken()
                }
            } else {
                break
            }
        }
        returnType = api.obtainTypeFromString(returnTypeString, cl, typeParameterList)
        assertIdent(tokenizer, token)
        val name: String = token
        method = TextMethodItem(api, name, cl, modifiers, returnType, tokenizer.pos())
        method.deprecated = modifiers.isDeprecated()
        if (cl.isInterface() && !modifiers.isDefault() && !modifiers.isStatic()) {
            modifiers.setAbstract(true)
        }
        method.setTypeParameterList(typeParameterList)
        if (typeParameterList is TextTypeParameterList) {
            typeParameterList.owner = method
        }
        token = tokenizer.requireToken()
        if ("(" != token) {
            throw ApiParseException("expected (, was $token", tokenizer)
        }
        parseParameterList(api, tokenizer, method)
        token = tokenizer.requireToken()
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

    @Throws(ApiParseException::class)
    private fun parseField(
        api: TextCodebase,
        tokenizer: Tokenizer,
        cl: TextClassItem,
        startingToken: String,
        isEnum: Boolean
    ) {
        var token = startingToken
        var annotations = getAnnotations(tokenizer, token)
        token = tokenizer.current
        val modifiers = parseModifiers(api, tokenizer, token, null)
        token = tokenizer.current
        assertIdent(tokenizer, token)
        val (first, second) = processKotlinTypeSuffix(token, annotations)
        token = first
        annotations = second
        modifiers.addAnnotations(annotations)
        val type = token
        val typeInfo = api.obtainTypeFromString(type)
        token = tokenizer.requireToken()
        assertIdent(tokenizer, token)
        val name = token
        token = tokenizer.requireToken()
        var value: Any? = null
        if ("=" == token) {
            token = tokenizer.requireToken(false)
            value = parseValue(type, token)
            token = tokenizer.requireToken()
        }
        if (";" != token) {
            throw ApiParseException("expected ; found $token", tokenizer)
        }
        val field = TextFieldItem(api, name, cl, modifiers, typeInfo, value, tokenizer.pos())
        field.deprecated = modifiers.isDeprecated()
        if (isEnum) {
            cl.addEnumConstant(field)
        } else {
            cl.addField(field)
        }
    }

    @Throws(ApiParseException::class)
    private fun parseModifiers(
        api: TextCodebase,
        tokenizer: Tokenizer,
        startingToken: String?,
        annotations: List<String>?
    ): TextModifiers {
        var token = startingToken
        val modifiers = TextModifiers(api, DefaultModifierList.PACKAGE_PRIVATE, null)
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

    private fun parseValue(type: String?, value: String?): Any? {
        return if (value != null) {
            when (type) {
                "boolean" ->
                    if ("true" == value) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE
                "byte" -> Integer.valueOf(value)
                "short" -> Integer.valueOf(value)
                "int" -> Integer.valueOf(value)
                "long" -> java.lang.Long.valueOf(value.substring(0, value.length - 1))
                "float" ->
                    when (value) {
                        "(1.0f/0.0f)",
                        "(1.0f / 0.0f)" -> Float.POSITIVE_INFINITY
                        "(-1.0f/0.0f)",
                        "(-1.0f / 0.0f)" -> Float.NEGATIVE_INFINITY
                        "(0.0f/0.0f)",
                        "(0.0f / 0.0f)" -> Float.NaN
                        else -> java.lang.Float.valueOf(value)
                    }
                "double" ->
                    when (value) {
                        "(1.0/0.0)",
                        "(1.0 / 0.0)" -> Double.POSITIVE_INFINITY
                        "(-1.0/0.0)",
                        "(-1.0 / 0.0)" -> Double.NEGATIVE_INFINITY
                        "(0.0/0.0)",
                        "(0.0 / 0.0)" -> Double.NaN
                        else -> java.lang.Double.valueOf(value)
                    }
                "char" -> value.toInt().toChar()
                JAVA_LANG_STRING,
                "String" ->
                    if ("null" == value) {
                        null
                    } else {
                        javaUnescapeString(value.substring(1, value.length - 1))
                    }
                "null" -> null
                else -> value
            }
        } else null
    }

    @Throws(ApiParseException::class)
    private fun parseProperty(
        api: TextCodebase,
        tokenizer: Tokenizer,
        cl: TextClassItem,
        startingToken: String
    ) {
        var token = startingToken

        // Metalava: including annotations in file now
        var annotations = getAnnotations(tokenizer, token)
        token = tokenizer.current
        val modifiers = parseModifiers(api, tokenizer, token, null)
        token = tokenizer.current
        assertIdent(tokenizer, token)
        val (first, second) = processKotlinTypeSuffix(token, annotations)
        token = first
        annotations = second
        modifiers.addAnnotations(annotations)
        val type: String = token
        val typeInfo = api.obtainTypeFromString(type)
        token = tokenizer.requireToken()
        assertIdent(tokenizer, token)
        val name: String = token
        token = tokenizer.requireToken()
        if (";" != token) {
            throw ApiParseException("expected ; found $token", tokenizer)
        }
        val property = TextPropertyItem(api, name, cl, modifiers, typeInfo, tokenizer.pos())
        property.deprecated = modifiers.isDeprecated()
        cl.addProperty(property)
    }

    @Throws(ApiParseException::class)
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
            create(codebase, null, typeParameterList)
        }
    }

    @Throws(ApiParseException::class)
    private fun parseParameterList(
        api: TextCodebase,
        tokenizer: Tokenizer,
        method: TextMethodItem
    ) {
        var token: String = tokenizer.requireToken()
        var index = 0
        while (true) {
            if (")" == token) {
                return
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
            var annotations = getAnnotations(tokenizer, token)
            token = tokenizer.current
            val modifiers = parseModifiers(api, tokenizer, token, null)
            token = tokenizer.current

            // Token should now represent the type
            var type = token
            token = tokenizer.requireToken()
            if (token.startsWith("@")) {
                // Type use annotations within the type, which broke up the tokenizer;
                // put it back together
                type += " $token"
                token = tokenizer.requireToken()
                if (
                    token.startsWith("[")
                ) { // TODO: This isn't general purpose; make requireToken smarter!
                    type += " $token"
                    token = tokenizer.requireToken()
                }
            }
            val (typeString, second) = processKotlinTypeSuffix(type, annotations)
            annotations = second
            modifiers.addAnnotations(annotations)
            if (typeString.endsWith("...")) {
                modifiers.setVarArg(true)
            }
            val typeInfo =
                api.obtainTypeFromString(
                    typeString,
                    (method.containingClass() as TextClassItem),
                    method.typeParameterList()
                )
            var name: String
            var publicName: String?
            if (isIdent(token) && token != "=") {
                name = token
                publicName = name
                token = tokenizer.requireToken()
            } else {
                name = "arg" + (index + 1)
                publicName = null
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
            method.addParameter(
                TextParameterItem(
                    api,
                    method,
                    name,
                    publicName,
                    hasDefaultValue,
                    defaultValue,
                    index,
                    typeInfo,
                    modifiers,
                    tokenizer.pos()
                )
            )
            if (modifiers.isVarArg()) {
                method.setVarargs(true)
            }
            index++
        }
    }

    @Throws(ApiParseException::class)
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

    @Throws(ApiParseException::class)
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

    private fun qualifiedName(pkg: String, className: String): String {
        return "$pkg.$className"
    }

    private fun isIdent(token: String): Boolean {
        return isIdent(token[0])
    }

    @Throws(ApiParseException::class)
    private fun assertIdent(tokenizer: Tokenizer, token: String) {
        if (!isIdent(token[0])) {
            throw ApiParseException("Expected identifier: $token", tokenizer)
        }
    }

    private fun isSpace(c: Char): Boolean {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r'
    }

    private fun isNewline(c: Char): Boolean {
        return c == '\n' || c == '\r'
    }

    private fun isSeparator(c: Char, parenIsSep: Boolean): Boolean {
        if (parenIsSep) {
            if (c == '(' || c == ')') {
                return true
            }
        }
        return c == '{' || c == '}' || c == ',' || c == ';' || c == '<' || c == '>'
    }

    private fun isIdent(c: Char): Boolean {
        return c != '"' && !isSeparator(c, true)
    }

    internal inner class Tokenizer(val fileName: String, private val buffer: CharArray) {
        var position = 0
        var line = 1

        fun pos(): SourcePositionInfo {
            return SourcePositionInfo(fileName, line)
        }

        private fun eatWhitespace(): Boolean {
            var ate = false
            while (position < buffer.size && isSpace(buffer[position])) {
                if (buffer[position] == '\n') {
                    line++
                }
                position++
                ate = true
            }
            return ate
        }

        private fun eatComment(): Boolean {
            if (position + 1 < buffer.size) {
                if (buffer[position] == '/' && buffer[position + 1] == '/') {
                    position += 2
                    while (position < buffer.size && !isNewline(buffer[position])) {
                        position++
                    }
                    return true
                }
            }
            return false
        }

        private fun eatWhitespaceAndComments() {
            while (eatWhitespace() || eatComment()) {
                // intentionally consume whitespace and comments
            }
        }

        @Throws(ApiParseException::class)
        fun requireToken(parenIsSep: Boolean = true, eatWhitespace: Boolean = true): String {
            val token = getToken(parenIsSep, eatWhitespace)
            return token ?: throw ApiParseException("Unexpected end of file", this)
        }

        fun offset(): Int {
            return position
        }

        fun getStringFromOffset(offset: Int): String {
            return String(buffer, offset, position - offset)
        }

        lateinit var current: String

        @Throws(ApiParseException::class)
        fun getToken(parenIsSep: Boolean = true, eatWhitespace: Boolean = true): String? {
            if (eatWhitespace) {
                eatWhitespaceAndComments()
            }
            if (position >= buffer.size) {
                return null
            }
            val line = line
            val c = buffer[position]
            val start = position
            position++
            if (c == '"') {
                val STATE_BEGIN = 0
                val STATE_ESCAPE = 1
                var state = STATE_BEGIN
                while (true) {
                    if (position >= buffer.size) {
                        throw ApiParseException(
                            "Unexpected end of file for \" starting at $line",
                            this
                        )
                    }
                    val k = buffer[position]
                    if (k == '\n' || k == '\r') {
                        throw ApiParseException(
                            "Unexpected newline for \" starting at $line in $fileName",
                            this
                        )
                    }
                    position++
                    when (state) {
                        STATE_BEGIN ->
                            when (k) {
                                '\\' -> state = STATE_ESCAPE
                                '"' -> {
                                    current = String(buffer, start, position - start)
                                    return current
                                }
                            }
                        STATE_ESCAPE -> state = STATE_BEGIN
                    }
                }
            } else if (isSeparator(c, parenIsSep)) {
                current = c.toString()
                return current
            } else {
                var genericDepth = 0
                do {
                    while (position < buffer.size) {
                        val d = buffer[position]
                        if (isSpace(d) || isSeparator(d, parenIsSep)) {
                            break
                        } else if (d == '"') {
                            // String literal in token: skip the full thing
                            position++
                            while (position < buffer.size) {
                                if (buffer[position] == '"') {
                                    position++
                                    break
                                } else if (buffer[position] == '\\') {
                                    position++
                                }
                                position++
                            }
                            continue
                        }
                        position++
                    }
                    if (position < buffer.size) {
                        if (buffer[position] == '<') {
                            genericDepth++
                            position++
                        } else if (genericDepth != 0) {
                            if (buffer[position] == '>') {
                                genericDepth--
                            }
                            position++
                        }
                    }
                } while (
                    position < buffer.size &&
                        (!isSpace(buffer[position]) && !isSeparator(buffer[position], parenIsSep) ||
                            genericDepth != 0)
                )
                if (position >= buffer.size) {
                    throw ApiParseException("Unexpected end of file for \" starting at $line", this)
                }
                current = String(buffer, start, position - start)
                return current
            }
        }
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
            // java.lang.Object has no superclass
            if (cl.isJavaLangObject()) {
                continue
            }
            var scName: String? = context.nameOfSuperClass(cl)
            if (scName == null) {
                scName =
                    when {
                        cl.isEnum() -> JAVA_LANG_ENUM
                        cl.isAnnotationType() -> JAVA_LANG_ANNOTATION
                        else -> {
                            val existing = cl.superClassType()?.toTypeString()
                            existing ?: JAVA_LANG_OBJECT
                        }
                    }
            }

            val superclass = getOrCreateClass(scName)
            cl.setSuperClass(superclass, codebase.obtainTypeFromString(scName))
        }
    }

    private fun resolveInterfaces() {
        for (cl in classes) {
            val interfaces = context.namesOfInterfaces(cl) ?: continue
            for (interfaceName in interfaces) {
                getOrCreateClass(interfaceName, isInterface = true)
                cl.addInterface(codebase.obtainTypeFromString(interfaceName))
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
                    // Exception not provided by this codebase. Inject a stub.
                    exceptionClass = getOrCreateClass(exception)
                    // Set super class to throwable?
                    if (exception != JAVA_LANG_THROWABLE) {
                        exceptionClass.setSuperClass(
                            getOrCreateClass(JAVA_LANG_THROWABLE),
                            TextTypeItem(codebase, JAVA_LANG_THROWABLE)
                        )
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
