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

import java.io.Writer

interface ModifierList {
    val codebase: Codebase

    fun annotations(): List<AnnotationItem>

    fun owner(): Item

    fun getVisibilityLevel(): VisibilityLevel

    fun isPublic(): Boolean

    fun isProtected(): Boolean

    fun isPrivate(): Boolean

    @MetalavaApi fun isStatic(): Boolean

    fun isAbstract(): Boolean

    fun isFinal(): Boolean

    fun isNative(): Boolean

    fun isSynchronized(): Boolean

    fun isStrictFp(): Boolean

    fun isTransient(): Boolean

    fun isVolatile(): Boolean

    fun isDefault(): Boolean

    // Modifier in Kotlin, separate syntax (...) in Java but modeled as modifier here
    fun isVarArg(): Boolean = false

    // Kotlin
    fun isSealed(): Boolean = false

    fun isFunctional(): Boolean = false

    fun isCompanion(): Boolean = false

    fun isInfix(): Boolean = false

    fun isConst(): Boolean = false

    fun isSuspend(): Boolean = false

    fun isOperator(): Boolean = false

    fun isInline(): Boolean = false

    fun isValue(): Boolean = false

    fun isData(): Boolean = false

    fun isEmpty(): Boolean

    fun isPackagePrivate() = !(isPublic() || isProtected() || isPrivate())

    fun isPublicOrProtected() = isPublic() || isProtected()

    // Rename? It's not a full equality, it's whether an override's modifier set is significant
    fun equivalentTo(other: ModifierList): Boolean {
        if (isPublic() != other.isPublic()) return false
        if (isProtected() != other.isProtected()) return false
        if (isPrivate() != other.isPrivate()) return false

        if (isStatic() != other.isStatic()) return false
        if (isAbstract() != other.isAbstract()) return false
        if (isFinal() != other.isFinal()) {
            return false
        }
        if (isTransient() != other.isTransient()) return false
        if (isVolatile() != other.isVolatile()) return false

        // Default does not require an override to "remove" it
        // if (isDefault() != other.isDefault()) return false

        return true
    }

    /** Returns true if this modifier list contains any nullness information */
    fun hasNullnessInfo(): Boolean = hasAnnotation(AnnotationItem::isNullnessAnnotation)

    /** Returns true if this modifier list contains any a Nullable annotation */
    fun isNullable(): Boolean = hasAnnotation(AnnotationItem::isNullable)

    /** Returns true if this modifier list contains any a NonNull annotation */
    fun isNonNull(): Boolean = hasAnnotation(AnnotationItem::isNonNull)

    /** Returns true if this modifier list contains the `@JvmSynthetic` annotation */
    fun hasJvmSyntheticAnnotation(): Boolean = hasAnnotation(AnnotationItem::isJvmSynthetic)

    /**
     * Returns true if this modifier list contains any suppress compatibility meta-annotations.
     *
     * Metalava will suppress compatibility checks for APIs which are within the scope of a
     * "suppress compatibility" meta-annotation, but they may still be written to API files or stub
     * JARs.
     *
     * "Suppress compatibility" meta-annotations allow Metalava to handle concepts like Jetpack
     * experimental APIs, where developers can use the [RequiresOptIn] meta-annotation to mark
     * feature sets with unstable APIs.
     */
    fun hasSuppressCompatibilityMetaAnnotations(): Boolean {
        return codebase.annotationManager.hasSuppressCompatibilityMetaAnnotations(this)
    }

    /** Returns true if this modifier list contains the given annotation */
    fun isAnnotatedWith(qualifiedName: String): Boolean {
        return findAnnotation(qualifiedName) != null
    }

    /**
     * Returns the annotation of the given qualified name (or equivalent) if found in this modifier
     * list
     */
    fun findAnnotation(qualifiedName: String): AnnotationItem? {
        val mappedName = codebase.annotationManager.normalizeInputName(qualifiedName)
        return findAnnotation { mappedName == it.qualifiedName }
    }

    /**
     * Returns true if the visibility modifiers in this modifier list is as least as visible as the
     * ones in the given [other] modifier list
     */
    fun asAccessibleAs(other: ModifierList): Boolean {
        val otherLevel = other.getVisibilityLevel()
        val thisLevel = getVisibilityLevel()
        // Generally the access level enum order determines relative visibility. However, there is
        // an exception because
        // package private and internal are not directly comparable.
        val result = thisLevel >= otherLevel
        return when (otherLevel) {
            VisibilityLevel.PACKAGE_PRIVATE -> result && thisLevel != VisibilityLevel.INTERNAL
            VisibilityLevel.INTERNAL -> result && thisLevel != VisibilityLevel.PACKAGE_PRIVATE
            else -> result
        }
    }

    /** User visible description of the visibility in this modifier list */
    fun getVisibilityString(): String {
        return getVisibilityLevel().userVisibleDescription
    }

    /**
     * Like [getVisibilityString], but package private has no modifiers; this typically corresponds
     * to the source code for the visibility modifiers in the modifier list
     */
    fun getVisibilityModifiers(): String {
        return getVisibilityLevel().javaSourceCodeModifier
    }

    companion object {
        fun write(
            writer: Writer,
            modifiers: ModifierList,
            item: Item,
            target: AnnotationTarget,
            runtimeAnnotationsOnly: Boolean = false,
            skipNullnessAnnotations: Boolean = false,
            omitCommonPackages: Boolean = false,
            removeAbstract: Boolean = false,
            removeFinal: Boolean = false,
            addPublic: Boolean = false,
            separateLines: Boolean = false,
            language: Language = Language.JAVA
        ) {
            val list =
                if (removeAbstract || removeFinal || addPublic) {
                    class AbstractFiltering : ModifierList by modifiers {
                        override fun isAbstract(): Boolean {
                            return if (removeAbstract) false else modifiers.isAbstract()
                        }

                        override fun isFinal(): Boolean {
                            return if (removeFinal) false else modifiers.isFinal()
                        }

                        override fun getVisibilityLevel(): VisibilityLevel {
                            return if (addPublic) VisibilityLevel.PUBLIC
                            else modifiers.getVisibilityLevel()
                        }
                    }
                    AbstractFiltering()
                } else {
                    modifiers
                }

            writeAnnotations(
                item,
                target,
                runtimeAnnotationsOnly,
                writer,
                separateLines,
                list,
                skipNullnessAnnotations,
                omitCommonPackages
            )

            if (item is PackageItem) {
                // Packages use a modifier list, but only annotations apply
                return
            }

            // Kotlin order:
            //   https://kotlinlang.org/docs/reference/coding-conventions.html#modifiers

            // Abstract: should appear in interfaces if in compat mode
            val classItem = item as? ClassItem
            val methodItem = item as? MethodItem

            val visibilityLevel = list.getVisibilityLevel()
            val modifier =
                if (language == Language.JAVA) {
                    visibilityLevel.javaSourceCodeModifier
                } else {
                    visibilityLevel.kotlinSourceCodeModifier
                }
            if (modifier.isNotEmpty()) {
                writer.write("$modifier ")
            }

            val isInterface =
                classItem?.isInterface() == true ||
                    (methodItem?.containingClass()?.isInterface() == true &&
                        !list.isDefault() &&
                        !list.isStatic())

            if (
                list.isAbstract() &&
                    classItem?.isEnum() != true &&
                    classItem?.isAnnotationType() != true &&
                    !isInterface
            ) {
                writer.write("abstract ")
            }

            if (list.isDefault() && item !is ParameterItem) {
                writer.write("default ")
            }

            if (list.isStatic() && (classItem == null || !classItem.isEnum())) {
                writer.write("static ")
            }

            if (
                list.isFinal() &&
                    language == Language.JAVA &&
                    // Don't show final on parameters: that's an implementation side detail
                    item !is ParameterItem &&
                    classItem?.isEnum() != true
            ) {
                writer.write("final ")
            } else if (!list.isFinal() && language == Language.KOTLIN) {
                writer.write("open ")
            }

            if (list.isSealed()) {
                writer.write("sealed ")
            }

            if (list.isSuspend()) {
                writer.write("suspend ")
            }

            if (list.isInline()) {
                writer.write("inline ")
            }

            if (list.isValue()) {
                writer.write("value ")
            }

            if (list.isInfix()) {
                writer.write("infix ")
            }

            if (list.isOperator()) {
                writer.write("operator ")
            }

            if (list.isTransient()) {
                writer.write("transient ")
            }

            if (list.isVolatile()) {
                writer.write("volatile ")
            }

            if (list.isSynchronized() && target.isStubsFile()) {
                writer.write("synchronized ")
            }

            if (list.isNative() && (target.isStubsFile() || isSignaturePolymorphic(item))) {
                writer.write("native ")
            }

            if (list.isFunctional()) {
                writer.write("fun ")
            }

            if (language == Language.KOTLIN) {
                if (list.isData()) {
                    writer.write("data ")
                }
            }
        }

        fun writeAnnotations(
            item: Item,
            target: AnnotationTarget,
            runtimeAnnotationsOnly: Boolean,
            writer: Writer,
            separateLines: Boolean,
            list: ModifierList,
            skipNullnessAnnotations: Boolean,
            omitCommonPackages: Boolean
        ) {
            if (item.deprecated) {
                // Do not write @Deprecated for a parameter unless it was explicitly marked as
                // deprecated.
                if (item !is ParameterItem || item.originallyDeprecated) {
                    writer.write("@Deprecated")
                    writer.write(if (separateLines) "\n" else " ")
                }
            }

            if (item.hasSuppressCompatibilityMetaAnnotation()) {
                writer.write("@$SUPPRESS_COMPATIBILITY_ANNOTATION")
                writer.write(if (separateLines) "\n" else " ")
            }

            writeAnnotations(
                list = list,
                runtimeAnnotationsOnly = runtimeAnnotationsOnly,
                skipNullnessAnnotations = skipNullnessAnnotations,
                omitCommonPackages = omitCommonPackages,
                separateLines = separateLines,
                writer = writer,
                target = target
            )
        }

        fun writeAnnotations(
            list: ModifierList,
            skipNullnessAnnotations: Boolean = false,
            runtimeAnnotationsOnly: Boolean = false,
            omitCommonPackages: Boolean = false,
            separateLines: Boolean = false,
            filterDuplicates: Boolean = false,
            writer: Writer,
            target: AnnotationTarget
        ) {
            var annotations = list.annotations()

            // Ensure stable signature file order
            if (annotations.size > 1) {
                annotations = annotations.sortedBy { it.qualifiedName }
            }

            if (annotations.isNotEmpty()) {
                var index = -1
                for (annotation in annotations) {
                    index++

                    if (
                        runtimeAnnotationsOnly &&
                            annotation.retention != AnnotationRetention.RUNTIME
                    ) {
                        continue
                    }

                    var printAnnotation = annotation
                    if (!annotation.targets.contains(target)) {
                        continue
                    } else if ((annotation.isNullnessAnnotation())) {
                        if (skipNullnessAnnotations) {
                            continue
                        }
                    } else if (annotation.qualifiedName == "java.lang.Deprecated") {
                        // Special cased in stubs and signature files: emitted first
                        continue
                    } else {
                        val typedefMode = list.codebase.annotationManager.typedefMode
                        if (typedefMode == TypedefMode.INLINE) {
                            val typedef = annotation.findTypedefAnnotation()
                            if (typedef != null) {
                                printAnnotation = typedef
                            }
                        } else if (
                            typedefMode == TypedefMode.REFERENCE &&
                                annotation.targets === ANNOTATION_SIGNATURE_ONLY &&
                                annotation.findTypedefAnnotation() != null
                        ) {
                            // For annotation references, only include the simple name
                            writer.write("@")
                            writer.write(
                                annotation.resolve()?.simpleName() ?: annotation.qualifiedName!!
                            )
                            if (separateLines) {
                                writer.write("\n")
                            } else {
                                writer.write(" ")
                            }
                            continue
                        }
                    }

                    // Optionally filter out duplicates
                    if (index > 0 && filterDuplicates) {
                        val qualifiedName = annotation.qualifiedName
                        var found = false
                        for (i in 0 until index) {
                            val prev = annotations[i]
                            if (prev.qualifiedName == qualifiedName) {
                                found = true
                                break
                            }
                        }
                        if (found) {
                            continue
                        }
                    }

                    val source = printAnnotation.toSource(target, showDefaultAttrs = false)

                    if (omitCommonPackages) {
                        writer.write(AnnotationItem.shortenAnnotation(source))
                    } else {
                        writer.write(source)
                    }
                    if (separateLines) {
                        writer.write("\n")
                    } else {
                        writer.write(" ")
                    }
                }
            }
        }

        /** The set of classes that may contain polymorphic methods. */
        private val polymorphicHandleTypes =
            setOf(
                "java.lang.invoke.MethodHandle",
                "java.lang.invoke.VarHandle",
            )

        /**
         * Check to see whether a native item is actually a method with a polymorphic signature.
         *
         * The java compiler treats methods with polymorphic signatures specially. It identifies a
         * method as being polymorphic according to the rules defined in JLS 15.12.3. See
         * https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.12.3 for the latest
         * (at time of writing rules). They state:
         *
         * A method is signature polymorphic if all of the following are true:
         * * It is declared in the [java.lang.invoke.MethodHandle] class or the
         *   [java.lang.invoke.VarHandle] class.
         * * It has a single variable arity parameter (§8.4.1) whose declared type is Object[].
         * * It is native.
         *
         * The latter point means that the `native` modifier is an important part of a polymorphic
         * method's signature even though Metalava generally views the `native` modifier as an
         * implementation detail that should not be part of the API. So, if this method returns
         * `true` then the `native` modifier will be output to API signatures.
         */
        private fun isSignaturePolymorphic(item: Item): Boolean {
            return item is MethodItem &&
                item.containingClass().qualifiedName() in polymorphicHandleTypes &&
                item.parameters().let { parameters ->
                    parameters.size == 1 &&
                        parameters[0].let { parameter ->
                            parameter.isVarArgs() &&
                                // Check type is java.lang.Object[]
                                parameter.type().let { type ->
                                    type is ArrayTypeItem &&
                                        type.componentType.let { componentType ->
                                            componentType is ClassTypeItem &&
                                                componentType.qualifiedName == "java.lang.Object"
                                        }
                                }
                        }
                }
        }

        /**
         * Synthetic annotation used to mark an API as suppressed for compatibility checks.
         *
         * This is added automatically when an API has a meta-annotation that suppresses
         * compatibility but is defined outside the source set and may not always be available on
         * the classpath.
         *
         * Because this is used in API files, it needs to maintain compatibility.
         */
        const val SUPPRESS_COMPATIBILITY_ANNOTATION = "SuppressCompatibility"
    }
}

/**
 * Returns the first annotation in the modifier list that matches the supplied predicate, or null
 * otherwise.
 */
inline fun ModifierList.findAnnotation(predicate: (AnnotationItem) -> Boolean): AnnotationItem? {
    return annotations().firstOrNull(predicate)
}

/**
 * Returns true iff the modifier list contains any annotation that matches the supplied predicate.
 */
inline fun ModifierList.hasAnnotation(predicate: (AnnotationItem) -> Boolean): Boolean {
    return annotations().any(predicate)
}
