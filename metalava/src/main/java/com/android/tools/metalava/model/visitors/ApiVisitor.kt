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

package com.android.tools.metalava.model.visitors

import com.android.tools.metalava.ApiPredicate
import com.android.tools.metalava.PackageFilter
import com.android.tools.metalava.model.BaseItemVisitor
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.FieldItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.PackageItem
import com.android.tools.metalava.model.PropertyItem
import com.android.tools.metalava.options
import java.util.function.Predicate

open class ApiVisitor(
    /**
     * Whether constructors should be visited as part of a [#visitMethod] call instead of just a
     * [#visitConstructor] call. Helps simplify visitors that don't care to distinguish between the
     * two cases. Defaults to true.
     */
    visitConstructorsAsMethods: Boolean = true,
    /**
     * Whether inner classes should be visited "inside" a class; when this property is true, inner
     * classes are visited before the [#afterVisitClass] method is called; when false, it's done
     * afterwards. Defaults to false.
     */
    nestInnerClasses: Boolean = false,

    /** Whether to include inherited fields too */
    val inlineInheritedFields: Boolean = true,

    /** Comparator to sort methods with, or null to use natural (source) order */
    val methodComparator: Comparator<MethodItem>? = null,

    /** Comparator to sort fields with, or null to use natural (source) order */
    val fieldComparator: Comparator<FieldItem>? = null,

    /** The filter to use to determine if we should emit an item */
    val filterEmit: Predicate<Item>,

    /** The filter to use to determine if we should emit a reference to an item */
    val filterReference: Predicate<Item>,

    /**
     * Whether the visitor should include visiting top-level classes that have nothing other than
     * non-empty inner classes within. Typically these are not included in signature files, but when
     * generating stubs we need to include them.
     */
    val includeEmptyOuterClasses: Boolean = false,

    /**
     * Whether this visitor should visit elements that have not been annotated with one of the
     * annotations passed in using the --show-annotation flag. This is normally true, but signature
     * files sometimes sets this to false so the signature file only contains the "diff" of the
     * annotated API relative to the base API.
     */
    val showUnannotated: Boolean = true,

    /** Configuration that may come from the command line. */
    @Suppress("DEPRECATION") config: Config = options.apiVisitorConfig,
) : BaseItemVisitor(visitConstructorsAsMethods, nestInnerClasses) {

    private val packageFilter: PackageFilter? = config.packageFilter

    /**
     * Contains configuration for [ApiVisitor] that can, or at least could, come from command line
     * options.
     */
    data class Config(
        val packageFilter: PackageFilter? = null,

        /** Configuration for any [ApiPredicate] instances this needs to create. */
        val apiPredicateConfig: ApiPredicate.Config = ApiPredicate.Config()
    )

    constructor(
        /**
         * Whether constructors should be visited as part of a [#visitMethod] call instead of just a
         * [#visitConstructor] call. Helps simplify visitors that don't care to distinguish between
         * the two cases. Defaults to true.
         */
        visitConstructorsAsMethods: Boolean = true,
        /**
         * Whether inner classes should be visited "inside" a class; when this property is true,
         * inner classes are visited before the [#afterVisitClass] method is called; when false,
         * it's done afterwards. Defaults to false.
         */
        nestInnerClasses: Boolean = false,

        /** Whether to ignore APIs with annotations in the --show-annotations list */
        ignoreShown: Boolean = true,

        /** Whether to match APIs marked for removal instead of the normal API */
        remove: Boolean = false,

        /** Comparator to sort methods with, or null to use natural (source) order */
        methodComparator: Comparator<MethodItem>? = null,

        /** Comparator to sort fields with, or null to use natural (source) order */
        fieldComparator: Comparator<FieldItem>? = null,

        /**
         * The filter to use to determine if we should emit an item. If null, the default value is
         * an [ApiPredicate] based on the values of [remove], [includeApisForStubPurposes],
         * [config], and [ignoreShown].
         */
        filterEmit: Predicate<Item>? = null,

        /**
         * The filter to use to determine if we should emit a reference to an item. If null, the
         * default value is an [ApiPredicate] based on the values of [remove] and [config].
         */
        filterReference: Predicate<Item>? = null,

        /**
         * Whether to include "for stub purposes" APIs.
         *
         * See [ApiPredicate.includeOnlyForStubPurposes]
         */
        includeApisForStubPurposes: Boolean = true,

        /** Configuration that may come from the command line. */
        @Suppress("DEPRECATION") config: Config = options.apiVisitorConfig,
    ) : this(
        visitConstructorsAsMethods = visitConstructorsAsMethods,
        nestInnerClasses = nestInnerClasses,
        inlineInheritedFields = true,
        methodComparator = methodComparator,
        fieldComparator = fieldComparator,
        filterEmit = filterEmit
                ?: ApiPredicate(
                    matchRemoved = remove,
                    includeApisForStubPurposes = includeApisForStubPurposes,
                    config = config.apiPredicateConfig.copy(ignoreShown = ignoreShown),
                ),
        filterReference = filterReference
                ?: ApiPredicate(
                    ignoreRemoved = remove,
                    config = config.apiPredicateConfig.copy(ignoreShown = true),
                ),
        config = config,
    )

    // The API visitor lazily visits packages only when there's a match within at least one class;
    // this property keeps track of whether we've already visited the current package
    var visitingPackage = false

    override fun visit(cls: ClassItem) {
        if (!include(cls)) {
            return
        }

        // We build up a separate data structure such that we can compute the
        // sets of fields, methods, etc even for inner classes (recursively); that way
        // we can easily and up front determine whether we have any matches for
        // inner classes (which is vital for computing the removed-api for example, where
        // only something like the appearance of a removed method inside an inner class
        // results in the outer class being described in the signature file.
        val candidate = VisitCandidate(cls, this)
        candidate.accept()
    }

    override fun visit(pkg: PackageItem) {
        if (!pkg.emit) {
            return
        }

        // For the API visitor packages are visited lazily; only when we encounter
        // an unfiltered item within the class
        pkg.topLevelClasses().asSequence().sortedWith(ClassItem.classNameSorter()).forEach {
            it.accept(this)
        }

        if (visitingPackage) {
            visitingPackage = false
            afterVisitPackage(pkg)
            afterVisitItem(pkg)
        }
    }

    /** @return Whether this class is generally one that we want to recurse into */
    open fun include(cls: ClassItem): Boolean {
        if (skip(cls)) {
            return false
        }
        if (packageFilter != null && !packageFilter.matches(cls.containingPackage())) {
            return false
        }

        return cls.emit || cls.codebase.preFiltered
    }

    /**
     * @return Whether the given VisitCandidate's visitor should recurse into the given
     *   VisitCandidate's class
     */
    fun include(vc: VisitCandidate): Boolean {
        if (!include(vc.cls)) {
            return false
        }
        return shouldEmitClassBody(vc) || shouldEmitInnerClasses(vc)
    }

    /**
     * @return Whether this class should be visited Note that if [include] returns true then we will
     *   still visit classes that are contained by this one
     */
    open fun shouldEmitClass(vc: VisitCandidate): Boolean {
        return vc.cls.emit && (includeEmptyOuterClasses || shouldEmitClassBody(vc))
    }

    /**
     * @return Whether the body of this class (everything other than the inner classes) emits
     *   anything
     */
    private fun shouldEmitClassBody(vc: VisitCandidate): Boolean {
        return when {
            filterEmit.test(vc.cls) -> true
            vc.nonEmpty() -> filterReference.test(vc.cls)
            else -> false
        }
    }

    /** @return Whether the inner classes of this class will emit anything */
    fun shouldEmitInnerClasses(vc: VisitCandidate): Boolean {
        return vc.innerClasses.any { shouldEmitAnyClass(it) }
    }

    /** @return Whether this class will emit anything */
    private fun shouldEmitAnyClass(vc: VisitCandidate): Boolean {
        return shouldEmitClassBody(vc) || shouldEmitInnerClasses(vc)
    }
}

class VisitCandidate(val cls: ClassItem, private val visitor: ApiVisitor) {
    val innerClasses: Sequence<VisitCandidate>
    private val constructors: Sequence<MethodItem>
    private val methods: Sequence<MethodItem>
    private val fields: Sequence<FieldItem>
    private val enums: Sequence<FieldItem>
    private val properties: Sequence<PropertyItem>

    init {
        val filterEmit = visitor.filterEmit

        constructors =
            cls.constructors()
                .asSequence()
                .filter { filterEmit.test(it) }
                .sortedWith(MethodItem.comparator)

        methods =
            cls.methods()
                .asSequence()
                .filter { filterEmit.test(it) }
                .sortedWith(MethodItem.comparator)

        val fieldSequence =
            if (visitor.inlineInheritedFields) {
                cls.filteredFields(filterEmit, visitor.showUnannotated).asSequence()
            } else {
                cls.fields().asSequence().filter { filterEmit.test(it) }
            }
        if (cls.isEnum()) {
            fields = fieldSequence.filter { !it.isEnumConstant() }.sortedWith(FieldItem.comparator)
            enums =
                fieldSequence
                    .filter { it.isEnumConstant() }
                    .filter { filterEmit.test(it) }
                    .sortedWith(FieldItem.comparator)
        } else {
            fields = fieldSequence.sortedWith(FieldItem.comparator)
            enums = emptySequence()
        }

        properties =
            if (cls.properties().isEmpty()) {
                emptySequence()
            } else {
                cls.properties()
                    .asSequence()
                    .filter { filterEmit.test(it) }
                    .sortedWith(PropertyItem.comparator)
            }

        innerClasses =
            cls.innerClasses().asSequence().sortedWith(ClassItem.classNameSorter()).map {
                VisitCandidate(it, visitor)
            }
    }

    /** Whether the class body contains any Item's (other than inner Classes) */
    fun nonEmpty(): Boolean {
        return !(constructors.none() &&
            methods.none() &&
            enums.none() &&
            fields.none() &&
            properties.none())
    }

    fun accept() {
        if (!visitor.include(this)) {
            return
        }

        val emitThis = visitor.shouldEmitClass(this)
        if (emitThis) {
            if (!visitor.visitingPackage) {
                visitor.visitingPackage = true
                val pkg = cls.containingPackage()
                visitor.visitItem(pkg)
                visitor.visitPackage(pkg)
            }

            visitor.visitItem(cls)
            visitor.visitClass(cls)

            val sortedConstructors =
                if (visitor.methodComparator != null) {
                    constructors.sortedWith(visitor.methodComparator)
                } else {
                    constructors
                }
            val sortedMethods =
                if (visitor.methodComparator != null) {
                    methods.sortedWith(visitor.methodComparator)
                } else {
                    methods
                }
            val sortedFields =
                if (visitor.fieldComparator != null) {
                    fields.sortedWith(visitor.fieldComparator)
                } else {
                    fields
                }

            for (constructor in sortedConstructors) {
                constructor.accept(visitor)
            }

            for (method in sortedMethods) {
                method.accept(visitor)
            }

            for (property in properties) {
                property.accept(visitor)
            }
            for (enumConstant in enums) {
                enumConstant.accept(visitor)
            }
            for (field in sortedFields) {
                field.accept(visitor)
            }
        }

        if (visitor.nestInnerClasses) { // otherwise done below
            innerClasses.forEach { it.accept() }
        }

        if (emitThis) {
            visitor.afterVisitClass(cls)
            visitor.afterVisitItem(cls)
        }

        if (!visitor.nestInnerClasses) {
            innerClasses.forEach { it.accept() }
        }
    }
}
