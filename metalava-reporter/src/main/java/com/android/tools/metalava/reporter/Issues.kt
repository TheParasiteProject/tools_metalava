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
package com.android.tools.metalava.reporter

import java.util.Locale
import kotlin.reflect.full.declaredMemberProperties

object Issues {
    private val allIssues: MutableList<Issue> = ArrayList(300)

    /** A list of all the issues. */
    val all: List<Issue> by this::allIssues

    private val nameToIssue: MutableMap<String, Issue> = HashMap(300)

    val PARSE_ERROR = Issue(Severity.ERROR)
    // Compatibility issues
    val ADDED_PACKAGE = Issue(Severity.HIDDEN, Category.COMPATIBILITY)
    val ADDED_CLASS = Issue(Severity.HIDDEN, Category.COMPATIBILITY)
    val ADDED_METHOD = Issue(Severity.HIDDEN, Category.COMPATIBILITY)
    val ADDED_FIELD = Issue(Severity.HIDDEN, Category.COMPATIBILITY)
    val ADDED_INTERFACE = Issue(Severity.HIDDEN, Category.COMPATIBILITY)
    val REMOVED_PACKAGE = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val REMOVED_CLASS = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val REMOVED_METHOD = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val REMOVED_FIELD = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val REMOVED_INTERFACE = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_STATIC = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val ADDED_FINAL = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_TRANSIENT = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_VOLATILE = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_TYPE = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_VALUE = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_SUPERCLASS = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_SCOPE = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_ABSTRACT = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_DEFAULT = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_THROWS = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_NATIVE = Issue(Severity.HIDDEN, Category.COMPATIBILITY)
    val CHANGED_CLASS = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val CHANGED_DEPRECATED = Issue(Severity.HIDDEN, Category.COMPATIBILITY)
    val CHANGED_SYNCHRONIZED = Issue(Severity.HIDDEN, Category.COMPATIBILITY)
    val CONFLICTING_SHOW_ANNOTATIONS = Issue(Severity.ERROR, Category.UNKNOWN)
    val ADDED_FINAL_UNINSTANTIABLE = Issue(Severity.HIDDEN, Category.COMPATIBILITY)
    val REMOVED_FINAL = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val REMOVED_FINAL_STRICT = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val REMOVED_DEPRECATED_CLASS = Issue(REMOVED_CLASS, Category.COMPATIBILITY)
    val REMOVED_DEPRECATED_METHOD = Issue(REMOVED_METHOD, Category.COMPATIBILITY)
    val REMOVED_DEPRECATED_FIELD = Issue(REMOVED_FIELD, Category.COMPATIBILITY)
    val ADDED_ABSTRACT_METHOD = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val ADDED_REIFIED = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val REMOVED_JVM_DEFAULT_WITH_COMPATIBILITY = Issue(Severity.ERROR, Category.COMPATIBILITY)

    // Issues in javadoc generation
    val UNRESOLVED_LINK = Issue(Severity.LINT, Category.DOCUMENTATION)
    val UNAVAILABLE_SYMBOL = Issue(Severity.WARNING, Category.DOCUMENTATION)
    val HIDDEN_SUPERCLASS = Issue(Severity.WARNING, Category.DOCUMENTATION)
    val DEPRECATED = Issue(Severity.HIDDEN, Category.DOCUMENTATION)
    val DEPRECATION_MISMATCH = Issue(Severity.ERROR, Category.DOCUMENTATION)
    val IO_ERROR = Issue(Severity.ERROR)
    val HIDDEN_TYPE_PARAMETER = Issue(Severity.WARNING, Category.DOCUMENTATION)
    val PRIVATE_SUPERCLASS = Issue(Severity.WARNING, Category.DOCUMENTATION)
    val NULLABLE = Issue(Severity.HIDDEN, Category.DOCUMENTATION)
    val INT_DEF = Issue(Severity.HIDDEN, Category.DOCUMENTATION)
    val REQUIRES_PERMISSION = Issue(Severity.LINT, Category.DOCUMENTATION)
    val BROADCAST_BEHAVIOR = Issue(Severity.LINT, Category.DOCUMENTATION)
    val SDK_CONSTANT = Issue(Severity.LINT, Category.DOCUMENTATION)
    val TODO = Issue(Severity.LINT, Category.DOCUMENTATION)
    val NO_ARTIFACT_DATA = Issue(Severity.HIDDEN, Category.DOCUMENTATION)
    val BROKEN_ARTIFACT_FILE = Issue(Severity.ERROR, Category.DOCUMENTATION)

    // Metalava warnings (not from doclava)

    val INVALID_FEATURE_ENFORCEMENT = Issue(Severity.LINT, Category.DOCUMENTATION)

    val MISSING_PERMISSION = Issue(Severity.LINT, Category.DOCUMENTATION)
    val MULTIPLE_THREAD_ANNOTATIONS = Issue(Severity.LINT, Category.DOCUMENTATION)
    val UNRESOLVED_CLASS = Issue(Severity.LINT, Category.DOCUMENTATION)
    val INVALID_NULL_CONVERSION = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val PARAMETER_NAME_CHANGE = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val OPERATOR_REMOVAL = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val INFIX_REMOVAL = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val VARARG_REMOVAL = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val ADD_SEALED = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val FUN_REMOVAL = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val BECAME_UNCHECKED = Issue(Severity.ERROR, Category.COMPATIBILITY)
    val ANNOTATION_EXTRACTION = Issue(Severity.ERROR)
    val SUPERFLUOUS_PREFIX = Issue(Severity.WARNING)
    val HIDDEN_TYPEDEF_CONSTANT = Issue(Severity.ERROR)
    val EXPECTED_PLATFORM_TYPE = Issue(Severity.HIDDEN)
    val INTERNAL_ERROR = Issue(Severity.ERROR)
    val RETURNING_UNEXPECTED_CONSTANT = Issue(Severity.WARNING)
    val DEPRECATED_OPTION = Issue(Severity.WARNING)
    val BOTH_PACKAGE_INFO_AND_HTML = Issue(Severity.WARNING, Category.DOCUMENTATION)
    val UNMATCHED_MERGE_ANNOTATION = Issue(Severity.WARNING)
    // The plan is for this to be set as an error once (1) existing code is marked as @deprecated
    // and (2) the principle is adopted by the API council
    val REFERENCES_DEPRECATED = Issue(Severity.HIDDEN)
    val UNHIDDEN_SYSTEM_API = Issue(Severity.ERROR)
    val SHOWING_MEMBER_IN_HIDDEN_CLASS = Issue(Severity.ERROR)
    val INVALID_NULLABILITY_ANNOTATION = Issue(Severity.ERROR)
    val REFERENCES_HIDDEN = Issue(Severity.ERROR)
    val IGNORING_SYMLINK = Issue(Severity.INFO)
    val INVALID_NULLABILITY_ANNOTATION_WARNING = Issue(Severity.WARNING)
    // The plan is for this to be set as an error once (1) existing code is marked as @deprecated
    // and (2) the principle is adopted by the API council
    val EXTENDS_DEPRECATED = Issue(Severity.HIDDEN)
    val FORBIDDEN_TAG = Issue(Severity.ERROR)
    val MISSING_COLUMN = Issue(Severity.WARNING, Category.DOCUMENTATION)
    val INVALID_SYNTAX = Issue(Severity.ERROR)
    val UNRESOLVED_IMPORT = Issue(Severity.INFO)
    val HIDDEN_ABSTRACT_METHOD = Issue(Severity.ERROR)

    // API lint
    val START_WITH_LOWER = Issue(Severity.ERROR, Category.API_LINT)
    val START_WITH_UPPER = Issue(Severity.ERROR, Category.API_LINT)
    val ALL_UPPER = Issue(Severity.ERROR, Category.API_LINT)
    val ACRONYM_NAME = Issue(Severity.WARNING, Category.API_LINT)
    val ENUM = Issue(Severity.ERROR, Category.API_LINT)
    val ENDS_WITH_IMPL = Issue(Severity.ERROR, Category.API_LINT)
    val MIN_MAX_CONSTANT = Issue(Severity.WARNING, Category.API_LINT)
    val COMPILE_TIME_CONSTANT = Issue(Severity.ERROR, Category.API_LINT)
    val SINGULAR_CALLBACK = Issue(Severity.ERROR, Category.API_LINT)
    val CALLBACK_NAME = Issue(Severity.WARNING, Category.API_LINT)
    // Obsolete per https://s.android.com/api-guidelines.
    val CALLBACK_INTERFACE = Issue(Severity.HIDDEN, Category.API_LINT)
    val CALLBACK_METHOD_NAME = Issue(Severity.ERROR, Category.API_LINT)
    val LISTENER_INTERFACE = Issue(Severity.ERROR, Category.API_LINT)
    val SINGLE_METHOD_INTERFACE = Issue(Severity.ERROR, Category.API_LINT)
    val INTENT_NAME = Issue(Severity.ERROR, Category.API_LINT)
    val ACTION_VALUE = Issue(Severity.ERROR, Category.API_LINT)
    val EQUALS_AND_HASH_CODE = Issue(Severity.ERROR, Category.API_LINT)
    val PARCEL_CREATOR = Issue(Severity.ERROR, Category.API_LINT)
    val PARCEL_NOT_FINAL = Issue(Severity.ERROR, Category.API_LINT)
    val PARCEL_CONSTRUCTOR = Issue(Severity.ERROR, Category.API_LINT)
    val PROTECTED_MEMBER = Issue(Severity.ERROR, Category.API_LINT)
    val PAIRED_REGISTRATION = Issue(Severity.ERROR, Category.API_LINT)
    val REGISTRATION_NAME = Issue(Severity.ERROR, Category.API_LINT)
    val VISIBLY_SYNCHRONIZED = Issue(Severity.ERROR, Category.API_LINT)
    val INTENT_BUILDER_NAME = Issue(Severity.WARNING, Category.API_LINT)
    val CONTEXT_NAME_SUFFIX = Issue(Severity.ERROR, Category.API_LINT)
    val INTERFACE_CONSTANT = Issue(Severity.ERROR, Category.API_LINT)
    val ON_NAME_EXPECTED = Issue(Severity.WARNING, Category.API_LINT)
    val TOP_LEVEL_BUILDER = Issue(Severity.WARNING, Category.API_LINT)
    val MISSING_BUILD_METHOD = Issue(Severity.WARNING, Category.API_LINT)
    val BUILDER_SET_STYLE = Issue(Severity.WARNING, Category.API_LINT)
    val SETTER_RETURNS_THIS = Issue(Severity.WARNING, Category.API_LINT)
    val RAW_AIDL = Issue(Severity.ERROR, Category.API_LINT)
    val INTERNAL_CLASSES = Issue(Severity.ERROR, Category.API_LINT)
    val PACKAGE_LAYERING = Issue(Severity.WARNING, Category.API_LINT)
    val GETTER_SETTER_NAMES = Issue(Severity.ERROR, Category.API_LINT)
    val CONCRETE_COLLECTION = Issue(Severity.ERROR, Category.API_LINT)
    val OVERLAPPING_CONSTANTS = Issue(Severity.WARNING, Category.API_LINT)
    val GENERIC_EXCEPTION = Issue(Severity.ERROR, Category.API_LINT)
    val ILLEGAL_STATE_EXCEPTION = Issue(Severity.WARNING, Category.API_LINT)
    val RETHROW_REMOTE_EXCEPTION = Issue(Severity.ERROR, Category.API_LINT)
    val MENTIONS_GOOGLE = Issue(Severity.ERROR, Category.API_LINT)
    val HEAVY_BIT_SET = Issue(Severity.ERROR, Category.API_LINT)
    val MANAGER_CONSTRUCTOR = Issue(Severity.ERROR, Category.API_LINT)
    val MANAGER_LOOKUP = Issue(Severity.ERROR, Category.API_LINT)
    val AUTO_BOXING = Issue(Severity.ERROR, Category.API_LINT)
    val STATIC_UTILS = Issue(Severity.ERROR, Category.API_LINT)
    val CONTEXT_FIRST = Issue(Severity.ERROR, Category.API_LINT)
    val LISTENER_LAST = Issue(Severity.WARNING, Category.API_LINT)
    val EXECUTOR_REGISTRATION = Issue(Severity.WARNING, Category.API_LINT)
    val CONFIG_FIELD_NAME = Issue(Severity.ERROR, Category.API_LINT)
    val RESOURCE_FIELD_NAME = Issue(Severity.ERROR, Category.API_LINT)
    val RESOURCE_VALUE_FIELD_NAME = Issue(Severity.ERROR, Category.API_LINT)
    val RESOURCE_STYLE_FIELD_NAME = Issue(Severity.ERROR, Category.API_LINT)
    val STREAM_FILES = Issue(Severity.WARNING, Category.API_LINT)
    val PARCELABLE_LIST = Issue(Severity.WARNING, Category.API_LINT)
    val ABSTRACT_INNER = Issue(Severity.WARNING, Category.API_LINT)
    val BANNED_THROW = Issue(Severity.ERROR, Category.API_LINT)
    val EXTENDS_ERROR = Issue(Severity.ERROR, Category.API_LINT)
    val EXCEPTION_NAME = Issue(Severity.ERROR, Category.API_LINT)
    val METHOD_NAME_UNITS = Issue(Severity.ERROR, Category.API_LINT)
    val FRACTION_FLOAT = Issue(Severity.ERROR, Category.API_LINT)
    val PERCENTAGE_INT = Issue(Severity.ERROR, Category.API_LINT)
    val NOT_CLOSEABLE = Issue(Severity.WARNING, Category.API_LINT)
    val KOTLIN_OPERATOR = Issue(Severity.INFO, Category.API_LINT)
    val ARRAY_RETURN = Issue(Severity.WARNING, Category.API_LINT)
    val USER_HANDLE = Issue(Severity.WARNING, Category.API_LINT)
    val USER_HANDLE_NAME = Issue(Severity.WARNING, Category.API_LINT)
    val SERVICE_NAME = Issue(Severity.ERROR, Category.API_LINT)
    val METHOD_NAME_TENSE = Issue(Severity.WARNING, Category.API_LINT)
    val NO_CLONE = Issue(Severity.ERROR, Category.API_LINT)
    val USE_ICU = Issue(Severity.WARNING, Category.API_LINT)
    val USE_PARCEL_FILE_DESCRIPTOR = Issue(Severity.ERROR, Category.API_LINT)
    val NO_BYTE_OR_SHORT = Issue(Severity.WARNING, Category.API_LINT)
    val SINGLETON_CONSTRUCTOR = Issue(Severity.ERROR, Category.API_LINT)
    val KOTLIN_KEYWORD = Issue(Severity.ERROR, Category.API_LINT)
    val UNIQUE_KOTLIN_OPERATOR = Issue(Severity.ERROR, Category.API_LINT)
    val SAM_SHOULD_BE_LAST = Issue(Severity.WARNING, Category.API_LINT)
    val MISSING_JVMSTATIC = Issue(Severity.WARNING, Category.API_LINT)
    val DEFAULT_VALUE_CHANGE = Issue(Severity.ERROR, Category.API_LINT)
    val DOCUMENT_EXCEPTIONS = Issue(Severity.ERROR, Category.API_LINT)
    val FORBIDDEN_SUPER_CLASS = Issue(Severity.ERROR, Category.API_LINT)
    val MISSING_NULLABILITY = Issue(Severity.ERROR, Category.API_LINT)
    val INVALID_NULLABILITY_OVERRIDE = Issue(Severity.ERROR, Category.API_LINT)
    val MUTABLE_BARE_FIELD = Issue(Severity.ERROR, Category.API_LINT)
    val INTERNAL_FIELD = Issue(Severity.ERROR, Category.API_LINT)
    val PUBLIC_TYPEDEF = Issue(Severity.ERROR, Category.API_LINT)
    val ANDROID_URI = Issue(Severity.ERROR, Category.API_LINT)
    val BAD_FUTURE = Issue(Severity.ERROR, Category.API_LINT)
    val STATIC_FINAL_BUILDER = Issue(Severity.WARNING, Category.API_LINT)
    val GETTER_ON_BUILDER = Issue(Severity.WARNING, Category.API_LINT)
    val MISSING_GETTER_MATCHING_BUILDER = Issue(Severity.WARNING, Category.API_LINT)
    val OPTIONAL_BUILDER_CONSTRUCTOR_ARGUMENT = Issue(Severity.WARNING, Category.API_LINT)
    val NO_SETTINGS_PROVIDER = Issue(Severity.HIDDEN, Category.API_LINT)
    val NULLABLE_COLLECTION = Issue(Severity.WARNING, Category.API_LINT)
    val ASYNC_SUFFIX_FUTURE = Issue(Severity.ERROR, Category.API_LINT)
    val GENERIC_CALLBACKS = Issue(Severity.ERROR, Category.API_LINT)
    val KOTLIN_DEFAULT_PARAMETER_ORDER = Issue(Severity.ERROR, Category.API_LINT_ANDROIDX_MISC)
    val UNFLAGGED_API = Issue(Severity.HIDDEN, Category.API_LINT)

    fun findIssueById(id: String?): Issue? {
        return nameToIssue[id]
    }

    fun findIssueByIdIgnoringCase(id: String): Issue? {
        for (e in allIssues) {
            if (id.equals(e.name, ignoreCase = true)) {
                return e
            }
        }
        return null
    }

    fun findCategoryById(id: String?): Category? = Category.values().find { it.id == id }

    fun findIssuesByCategory(category: Category?): List<Issue> =
        allIssues.filter { it.category == category }

    class Issue
    private constructor(
        val defaultLevel: Severity,
        /**
         * When `level` is set to [Severity.INHERIT], this is the parent from which the issue will
         * inherit its level.
         */
        val parent: Issue?,
        /** Applicable category */
        val category: Category,
    ) {
        /** The name of this issue */
        lateinit var name: String
            internal set

        internal constructor(
            defaultLevel: Severity,
            category: Category = Category.UNKNOWN
        ) : this(defaultLevel, null, category)

        internal constructor(
            parent: Issue,
            category: Category
        ) : this(Severity.INHERIT, parent, category)

        override fun toString(): String {
            return "Issue $name"
        }

        init {
            allIssues.add(this)
        }
    }

    enum class Category(val description: String) {
        COMPATIBILITY("Compatibility"),
        DOCUMENTATION("Documentation"),
        API_LINT("API Lint"),
        // AndroidX API guidelines are split across multiple files, so add a category per-file
        API_LINT_ANDROIDX_MISC("API Lint"),
        UNKNOWN("Default");

        /** Identifier for use in command-line arguments and reporting. */
        val id: String = enumConstantToCamelCase(name)
    }

    init { // Initialize issue names based on the field names
        for (property in Issues::class.declaredMemberProperties) {
            if (property.returnType.classifier != Issue::class) continue
            val issue = property.getter.call(Issues) as Issue

            issue.name = enumConstantToCamelCase(property.name)
            nameToIssue[issue.name] = issue
        }
        for (issue in allIssues) {
            check(issue.name != "")
        }
    }
}

/**
 * Convert enum constant name to camel case starting with an upper case letter.
 *
 * e.g. `ALPHA_BETA` becomes `AlphaBeta`.
 */
private fun enumConstantToCamelCase(name: String): String {
    return name
        .splitToSequence("_")
        .map { "${it[0]}${it.substring(1).lowercase(Locale.US)}" }
        .joinToString("")
}
