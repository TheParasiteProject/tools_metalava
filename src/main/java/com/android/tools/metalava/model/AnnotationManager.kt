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

package com.android.tools.metalava.model

/** Provides support for managing annotations within Metalava. */
interface AnnotationManager {

    /** Get the [AnnotationInfo] for the specified [annotation]. */
    fun getAnnotationInfo(annotation: AnnotationItem): AnnotationInfo

    /**
     * Maps an annotation name to the name to be used internally.
     *
     * Annotations that should not be used internally are mapped to null.
     */
    fun normalizeInputName(qualifiedName: String?): String?

    /**
     * Maps an annotation name to the name to be used in signatures/stubs/external annotation files.
     * Annotations that should not be exported are mapped to null.
     */
    fun normalizeOutputName(
        qualifiedName: String?,
        target: AnnotationTarget = AnnotationTarget.SIGNATURE_FILE
    ): String?

    /** Get the applicable targets for the annotation */
    fun computeTargets(
        annotation: AnnotationItem,
        classFinder: (String) -> ClassItem?
    ): Set<AnnotationTarget>

    /**
     * Checks to see if the modifiers contain any show annotations.
     *
     * Returns `true` if it does, `false` otherwise. If `true` then the owning item (and any
     * contents) will be added to the API.
     *
     * e.g. if the modifiers is for a class then it will also apply (unless overridden by a closer)
     * annotation to all its contents like nested classes, methods, fields, constructors,
     * properties, etc.
     */
    fun hasShowAnnotation(modifiers: ModifierList): Boolean = false

    /**
     * Checks to see if the modifiers contain any show single annotations.
     *
     * Returns `true` if it does, `false` otherwise. If `true` then the owning item and only that
     * item will be added to the API.
     *
     * e.g. if the modifiers is for a class then it only applies to that class and not its contents
     * like nested classes, methods, fields, constructors, properties, etc.
     */
    fun hasShowSingleAnnotation(modifiers: ModifierList): Boolean = false

    /**
     * Checks to see if the modifiers contain any show for stubs purposes annotations and no other
     * show annotations.
     *
     * Returns `true` if it does, `false` otherwise. If `true` then the owning item will only be
     * added to stub files.
     */
    fun onlyShowForStubPurposes(modifiers: ModifierList): Boolean = false

    /**
     * Checks to see if the modifiers contain any hide annotations.
     *
     * Returns `true` if it does, `false` otherwise. If `true` then the owning item (and any
     * contents) will be excluded from the API.
     *
     * e.g. if the modifiers is for a class then it will also apply (unless overridden by a closer)
     * annotation to all its contents like nested classes, methods, fields, constructors,
     * properties, etc.
     */
    fun hasHideAnnotations(modifiers: ModifierList): Boolean = false

    /**
     * Checks to see if the modifiers contain any suppress compatibility annotations.
     *
     * Returns `true` if it does, `false` otherwise. If `true` then the owning item (and any
     * contents) will have their compatibility checks suppressed but they may still be written to
     * API files or stub JARs.
     *
     * "Suppress compatibility" meta-annotations allow Metalava to handle concepts like Jetpack
     * experimental APIs, where developers can use the [RequiresOptIn] meta-annotation to mark
     * feature sets with unstable APIs.
     */
    fun hasSuppressCompatibilityMetaAnnotations(modifiers: ModifierList): Boolean = false

    /** Determine how to handle typedef annotations, i.e. annotations like `@IntDef`. */
    val typedefMode: TypedefMode
}

/**
 * The default empty [AnnotationInfo] used when a more applicable one cannot be created, e.g. when
 * the [AnnotationItem.qualifiedName] is `null`.
 */
private val noInfoAvailable = AnnotationInfo(qualifiedName = "")

/** Base class for [AnnotationManager] instances. */
abstract class BaseAnnotationManager : AnnotationManager {

    /**
     * A map from the annotation key (returned by [getKeyForAnnotationItem]) to the corresponding
     * [AnnotationInfo] (returned by [computeAnnotationInfo]).
     */
    private val annotationKeyToInfo = mutableMapOf<String, AnnotationInfo>()

    override fun getAnnotationInfo(annotation: AnnotationItem): AnnotationInfo {
        annotation.qualifiedName ?: return noInfoAvailable
        val key = getKeyForAnnotationItem(annotation)
        val existing = annotationKeyToInfo[key]
        if (existing != null) {
            return existing
        }
        val info = computeAnnotationInfo(annotation)
        annotationKeyToInfo[key] = info
        return info
    }

    /**
     * Construct a key that differentiates between all instances of the annotation class with the
     * same qualified name as [annotationItem] that have different [AnnotationInfo].
     *
     * e.g. if annotation `A()` and `A(value=2)` would both produce the same [AnnotationInfo]
     * (because the `value` attribute is ignored when computing it) then they should just use `A` as
     * the key. However, if they would produce different [AnnotationInfo] objects (because the
     * `value` attribute is used when computing it) then they should create a key that includes the
     * `value`.
     *
     * Note: it is safe to use `annotationItem.qualifiedName!!` as [AnnotationItem.qualifiedName] is
     * guaranteed not to be `null` when this method is called.
     */
    protected abstract fun getKeyForAnnotationItem(annotationItem: AnnotationItem): String

    /**
     * Compute an [AnnotationInfo] from the [annotationItem].
     *
     * This should only use attributes of the [annotationItem] if they are included in the key
     * returned by [getKeyForAnnotationItem] for this [annotationItem].
     *
     * Note: it is safe to use `annotationItem.qualifiedName!!` as [AnnotationItem.qualifiedName] is
     * guaranteed not to be `null` when this method is called.
     */
    protected abstract fun computeAnnotationInfo(annotationItem: AnnotationItem): AnnotationInfo
}
