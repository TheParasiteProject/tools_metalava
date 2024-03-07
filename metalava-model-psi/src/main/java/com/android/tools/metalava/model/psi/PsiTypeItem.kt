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

package com.android.tools.metalava.model.psi

import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.ArrayTypeItem
import com.android.tools.metalava.model.ClassTypeItem
import com.android.tools.metalava.model.DefaultTypeItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.JAVA_LANG_STRING
import com.android.tools.metalava.model.MemberItem
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.ParameterItem
import com.android.tools.metalava.model.PrimitiveTypeItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.TypeModifiers
import com.android.tools.metalava.model.TypeParameterItem
import com.android.tools.metalava.model.VariableTypeItem
import com.android.tools.metalava.model.WildcardTypeItem
import com.android.tools.metalava.model.findAnnotation
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEllipsisType
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.PsiNameHelper
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.psi.PsiReferenceList
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeElement
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypeParameterList
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.util.TypeConversionUtil
import java.lang.IllegalStateException
import java.util.function.Predicate

/** Represents a type backed by PSI */
sealed class PsiTypeItem(
    open val codebase: PsiBasedCodebase,
    open val psiType: PsiType,
    override val modifiers: TypeModifiers = PsiTypeModifiers.create(codebase, psiType)
) : DefaultTypeItem(codebase) {
    private var toString: String? = null
    private var toAnnotatedString: String? = null
    private var asClass: PsiClassItem? = null

    override fun toString(): String {
        return toTypeString()
    }

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

        if (filter != null) {
            // No caching when specifying filter.
            // TODO: When we support type use annotations, here we need to deal with markRecent
            //  and clearAnnotations not really having done their job.
            return toTypeString(
                codebase = codebase,
                type = psiType,
                annotations = annotations,
                kotlinStyleNulls = kotlinStyleNulls,
                context = context,
                filter = filter
            )
        }

        return when {
            kotlinStyleNulls && annotations -> {
                if (toAnnotatedString == null) {
                    toAnnotatedString =
                        toTypeString(
                            codebase = codebase,
                            type = psiType,
                            annotations = annotations,
                            kotlinStyleNulls = kotlinStyleNulls,
                            context = context,
                            filter = filter
                        )
                }
                toAnnotatedString!!
            }
            kotlinStyleNulls || annotations ->
                toTypeString(
                    codebase = codebase,
                    type = psiType,
                    annotations = annotations,
                    kotlinStyleNulls = kotlinStyleNulls,
                    context = context,
                    filter = filter
                )
            else -> {
                if (toString == null) {
                    toString =
                        TypeItem.formatType(
                            getCanonicalText(
                                codebase = codebase,
                                owner = context,
                                type = psiType,
                                annotated = false,
                                mapAnnotations = false,
                                kotlinStyleNulls = kotlinStyleNulls,
                                filter = filter
                            )
                        )
                }
                toString!!
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true

        return when (other) {
            is TypeItem -> TypeItem.equalsWithoutSpace(toTypeString(), other.toTypeString())
            else -> false
        }
    }

    override fun asClass(): PsiClassItem? {
        if (this is PrimitiveTypeItem) {
            return null
        }
        if (asClass == null) {
            asClass = codebase.findClass(psiType)
        }
        return asClass
    }

    override fun hashCode(): Int {
        return psiType.hashCode()
    }

    override fun convertType(replacementMap: Map<String, String>?, owner: Item?): TypeItem {
        val s = convertTypeString(replacementMap)
        return create(codebase, codebase.createPsiType(s, (owner as? PsiItem)?.psi()))
    }

    override fun hasTypeArguments(): Boolean {
        val type = psiType
        return type is PsiClassType && type.hasParameters()
    }

    /** Returns `true` if `this` type can be assigned from `other` without unboxing the other. */
    fun isAssignableFromWithoutUnboxing(other: PsiTypeItem): Boolean {
        if (this is PrimitiveTypeItem && other !is PrimitiveTypeItem) {
            return false
        }
        return TypeConversionUtil.isAssignable(psiType, other.psiType)
    }

    companion object {
        private fun toTypeString(
            codebase: PsiBasedCodebase,
            type: PsiType,
            annotations: Boolean,
            kotlinStyleNulls: Boolean,
            context: Item?,
            filter: Predicate<Item>?
        ): String {
            val typeString =
                if (kotlinStyleNulls || annotations) {
                    try {
                        getCanonicalText(
                            codebase = codebase,
                            owner = context,
                            type = type,
                            annotated = annotations,
                            mapAnnotations = true,
                            kotlinStyleNulls = kotlinStyleNulls,
                            filter = filter
                        )
                    } catch (ignore: Throwable) {
                        type.canonicalText
                    }
                } else {
                    type.canonicalText
                }

            return TypeItem.formatType(typeString)
        }

        private fun getCanonicalText(
            codebase: PsiBasedCodebase,
            owner: Item?,
            type: PsiType,
            annotated: Boolean,
            mapAnnotations: Boolean,
            kotlinStyleNulls: Boolean,
            filter: Predicate<Item>?
        ): String {
            return try {
                if (kotlinStyleNulls) {
                    // Any nullness annotations on the element to merge in? When we have something
                    // like
                    //  @Nullable String foo
                    // the Nullable annotation can be on the element itself rather than the type,
                    // so if we print the type without knowing the nullness annotation on the
                    // element, we'll think it's unannotated and we'll display it as "String!".
                    val nullness =
                        owner?.modifiers?.findAnnotation(AnnotationItem::isNullnessAnnotation)
                    var elementAnnotations =
                        if (nullness != null) {
                            listOf(nullness)
                        } else null

                    val implicitNullness = if (owner != null) owner.implicitNullness() else null
                    val annotatedType =
                        if (implicitNullness != null) {
                            val provider =
                                if (implicitNullness == true) {
                                    codebase.getNullableAnnotationProvider()
                                } else {
                                    codebase.getNonNullAnnotationProvider()
                                }

                            // Special handling for implicitly non-null arrays that also have an
                            // implicitly non-null component type
                            if (
                                implicitNullness == false &&
                                    type is PsiArrayType &&
                                    owner != null &&
                                    owner.impliesNonNullArrayComponents()
                            ) {
                                type.componentType
                                    .annotate(provider)
                                    .createArrayType()
                                    .annotate(provider)
                            } else if (
                                implicitNullness == false &&
                                    owner is ParameterItem &&
                                    owner.containingMethod().isEnumSyntheticMethod()
                            ) {
                                // Workaround the fact that the Kotlin synthetic enum methods
                                // do not have nullness information; this must be the parameter
                                // to the valueOf(String) method.
                                // See https://youtrack.jetbrains.com/issue/KT-39667.
                                return JAVA_LANG_STRING
                            } else {
                                type.annotate(provider)
                            }
                        } else if (
                            nullness != null &&
                                owner.modifiers.isVarArg() &&
                                owner.isKotlin() &&
                                type is PsiEllipsisType
                        ) {
                            // Varargs the annotation applies to the component type instead
                            val nonNullProvider = codebase.getNonNullAnnotationProvider()
                            val provider =
                                if (nullness.isNonNull()) {
                                    nonNullProvider
                                } else codebase.getNullableAnnotationProvider()
                            val componentType = type.componentType.annotate(provider)
                            elementAnnotations = null
                            PsiEllipsisType(componentType, nonNullProvider)
                        } else {
                            type
                        }
                    val printer =
                        PsiTypePrinter(
                            codebase,
                            filter,
                            mapAnnotations,
                            kotlinStyleNulls,
                            annotated
                        )

                    printer.getAnnotatedCanonicalText(annotatedType, elementAnnotations)
                } else if (annotated) {
                    type.getCanonicalText(true)
                } else {
                    type.getCanonicalText(false)
                }
            } catch (e: Throwable) {
                return type.getCanonicalText(false)
            }
        }

        /**
         * Determine if this item implies that its associated type is a non-null array with non-null
         * components. This is true for the synthetic `Enum.values()` method and any annotation
         * properties or accessors.
         */
        private fun Item.impliesNonNullArrayComponents(): Boolean {
            fun MemberItem.isAnnotationPropertiesOrAccessors(): Boolean =
                containingClass().isAnnotationType() && !modifiers.isStatic()

            // TODO: K2 UAST regression, KTIJ-24754
            fun MethodItem.isEnumValues(): Boolean =
                containingClass().isEnum() &&
                    modifiers.isStatic() &&
                    name() == "values" &&
                    parameters().isEmpty()

            return when (this) {
                is MemberItem -> {
                    isAnnotationPropertiesOrAccessors() || (this is MethodItem && isEnumValues())
                }
                else -> false
            }
        }

        fun create(codebase: PsiBasedCodebase, psiType: PsiType): PsiTypeItem {
            return when (psiType) {
                is PsiPrimitiveType -> PsiPrimitiveTypeItem(codebase, psiType)
                is PsiArrayType -> PsiArrayTypeItem(codebase, psiType)
                is PsiClassType -> {
                    if (psiType.resolve() is PsiTypeParameter) {
                        PsiVariableTypeItem(codebase, psiType)
                    } else {
                        PsiClassTypeItem(codebase, psiType)
                    }
                }
                is PsiWildcardType -> PsiWildcardTypeItem(codebase, psiType)
                // There are other [PsiType]s, but none can appear in API surfaces.
                else -> throw IllegalStateException("Invalid type in API surface: $psiType")
            }
        }

        fun create(codebase: PsiBasedCodebase, original: PsiTypeItem): PsiTypeItem {
            return when (original) {
                is PsiPrimitiveTypeItem -> PsiPrimitiveTypeItem(codebase, original.psiType)
                is PsiArrayTypeItem -> PsiArrayTypeItem(codebase, original.psiType)
                is PsiClassTypeItem -> PsiClassTypeItem(codebase, original.psiType)
                is PsiWildcardTypeItem -> PsiWildcardTypeItem(codebase, original.psiType)
                is PsiVariableTypeItem -> PsiVariableTypeItem(codebase, original.psiType)
            }
        }

        fun typeParameterList(typeList: PsiTypeParameterList?): String? {
            if (typeList != null && typeList.typeParameters.isNotEmpty()) {
                // TODO: Filter the type list classes? Try to construct a typelist of a private API!
                // We can't just use typeList.text here, because that just
                // uses the declaration from the source, which may not be
                // fully qualified - e.g. we might get
                //    <T extends View> instead of <T extends android.view.View>
                // Therefore, we'll need to compute it ourselves; I can't find
                // a utility for this
                val sb = StringBuilder()
                typeList.accept(
                    object : PsiRecursiveElementVisitor() {
                        override fun visitElement(element: PsiElement) {
                            if (element is PsiTypeParameterList) {
                                val typeParameters = element.typeParameters
                                if (typeParameters.isEmpty()) {
                                    return
                                }
                                sb.append("<")
                                var first = true
                                for (parameter in typeParameters) {
                                    if (!first) {
                                        sb.append(", ")
                                    }
                                    first = false
                                    visitElement(parameter)
                                }
                                sb.append(">")
                                return
                            } else if (element is PsiTypeParameter) {
                                if (PsiTypeParameterItem.isReified(element)) {
                                    sb.append("reified ")
                                }
                                sb.append(element.name)
                                // TODO: How do I get super -- e.g. "Comparable<? super T>"
                                val extendsList = element.extendsList
                                val refList = extendsList.referenceElements
                                if (refList.isNotEmpty()) {
                                    sb.append(" extends ")
                                    var first = true
                                    for (refElement in refList) {
                                        if (!first) {
                                            sb.append(" & ")
                                        } else {
                                            first = false
                                        }

                                        if (refElement is PsiJavaCodeReferenceElement) {
                                            visitElement(refElement)
                                            continue
                                        }
                                        val resolved = refElement.resolve()
                                        if (resolved is PsiClass) {
                                            sb.append(resolved.qualifiedName ?: resolved.name)
                                            resolved.typeParameterList?.accept(this)
                                        } else {
                                            sb.append(refElement.referenceName)
                                        }
                                    }
                                } else {
                                    val extendsListTypes = element.extendsListTypes
                                    if (extendsListTypes.isNotEmpty()) {
                                        sb.append(" extends ")
                                        var first = true
                                        for (type in extendsListTypes) {
                                            if (!first) {
                                                sb.append(" & ")
                                            } else {
                                                first = false
                                            }
                                            val resolved = type.resolve()
                                            if (resolved == null) {
                                                sb.append(type.className)
                                            } else {
                                                sb.append(resolved.qualifiedName ?: resolved.name)
                                                resolved.typeParameterList?.accept(this)
                                            }
                                        }
                                    }
                                }
                                return
                            } else if (element is PsiJavaCodeReferenceElement) {
                                val resolved = element.resolve()
                                if (resolved is PsiClass) {
                                    if (resolved.qualifiedName == null) {
                                        sb.append(resolved.name)
                                    } else {
                                        sb.append(resolved.qualifiedName)
                                    }
                                    val typeParameters = element.parameterList
                                    if (typeParameters != null) {
                                        val typeParameterElements =
                                            typeParameters.typeParameterElements
                                        if (typeParameterElements.isEmpty()) {
                                            return
                                        }

                                        // When reading in this from bytecode, the order is
                                        // sometimes wrong
                                        // (for example, for
                                        //    public interface BaseStream<T, S extends BaseStream<T,
                                        // S>>
                                        // the extends type BaseStream<T, S> will return the
                                        // typeParameterElements
                                        // as [S,T] instead of [T,S]. However, the
                                        // typeParameters.typeArguments
                                        // list is correct, so order the elements by the
                                        // typeArguments array instead

                                        // Special case: just one type argument: no sorting issue
                                        if (typeParameterElements.size == 1) {
                                            sb.append("<")
                                            var first = true
                                            for (parameter in typeParameterElements) {
                                                if (!first) {
                                                    sb.append(", ")
                                                }
                                                first = false
                                                visitElement(parameter)
                                            }
                                            sb.append(">")
                                            return
                                        }

                                        // More than one type argument

                                        val typeArguments = typeParameters.typeArguments
                                        if (typeArguments.isNotEmpty()) {
                                            sb.append("<")
                                            var first = true
                                            for (parameter in typeArguments) {
                                                if (!first) {
                                                    sb.append(", ")
                                                }
                                                first = false
                                                // Try to match up a type parameter element
                                                var found = false
                                                for (typeElement in typeParameterElements) {
                                                    if (parameter == typeElement.type) {
                                                        found = true
                                                        visitElement(typeElement)
                                                        break
                                                    }
                                                }
                                                if (!found) {
                                                    // No type element matched: use type instead
                                                    val classType =
                                                        PsiTypesUtil.getPsiClass(parameter)
                                                    if (classType != null) {
                                                        visitElement(classType)
                                                    } else {
                                                        sb.append(parameter.canonicalText)
                                                    }
                                                }
                                            }
                                            sb.append(">")
                                        }
                                    }
                                    return
                                }
                            } else if (element is PsiTypeElement) {
                                val type = element.type
                                if (type is PsiWildcardType) {
                                    sb.append("?")
                                    if (type.isBounded) {
                                        if (type.isExtends) {
                                            sb.append(" extends ")
                                            sb.append(type.extendsBound.canonicalText)
                                        }
                                        if (type.isSuper) {
                                            sb.append(" super ")
                                            sb.append(type.superBound.canonicalText)
                                        }
                                    }
                                    return
                                }
                                sb.append(type.canonicalText)
                                return
                            } else if (
                                element is PsiJavaToken && element.tokenType == JavaTokenType.COMMA
                            ) {
                                sb.append(",")
                                return
                            }
                            if (element.firstChild == null) { // leaf nodes only
                                if (element is PsiCompiledElement) {
                                    if (element is PsiReferenceList) {
                                        val referencedTypes = element.referencedTypes
                                        var first = true
                                        for (referenceType in referencedTypes) {
                                            if (first) {
                                                first = false
                                            } else {
                                                sb.append(", ")
                                            }
                                            sb.append(referenceType.canonicalText)
                                        }
                                    }
                                } else {
                                    sb.append(element.text)
                                }
                            }
                            super.visitElement(element)
                        }
                    }
                )

                val typeString = sb.toString()
                return TypeItem.cleanupGenerics(typeString)
            }

            return null
        }
    }
}

/** A [PsiTypeItem] backed by a [PsiPrimitiveType]. */
class PsiPrimitiveTypeItem(
    override val codebase: PsiBasedCodebase,
    override val psiType: PsiPrimitiveType
) : PrimitiveTypeItem, PsiTypeItem(codebase, psiType) {
    override val kind: PrimitiveTypeItem.Primitive = getKind(psiType)

    companion object {
        private fun getKind(type: PsiPrimitiveType): PrimitiveTypeItem.Primitive {
            return when (type) {
                PsiTypes.booleanType() -> PrimitiveTypeItem.Primitive.BOOLEAN
                PsiTypes.byteType() -> PrimitiveTypeItem.Primitive.BYTE
                PsiTypes.charType() -> PrimitiveTypeItem.Primitive.CHAR
                PsiTypes.doubleType() -> PrimitiveTypeItem.Primitive.DOUBLE
                PsiTypes.floatType() -> PrimitiveTypeItem.Primitive.FLOAT
                PsiTypes.intType() -> PrimitiveTypeItem.Primitive.INT
                PsiTypes.longType() -> PrimitiveTypeItem.Primitive.LONG
                PsiTypes.shortType() -> PrimitiveTypeItem.Primitive.SHORT
                PsiTypes.voidType() -> PrimitiveTypeItem.Primitive.VOID
                else -> throw IllegalStateException("Invalid primitive type in API surface: $type")
            }
        }
    }
}

/** A [PsiTypeItem] backed by a [PsiArrayType]. */
class PsiArrayTypeItem(
    override val codebase: PsiBasedCodebase,
    override val psiType: PsiArrayType
) : ArrayTypeItem, PsiTypeItem(codebase, psiType) {
    override val componentType: TypeItem = create(codebase, psiType.componentType)
    override val isVarargs: Boolean = psiType is PsiEllipsisType
}

/** A [PsiTypeItem] backed by a [PsiClassType] that does not represent a type variable. */
class PsiClassTypeItem(
    override val codebase: PsiBasedCodebase,
    override val psiType: PsiClassType
) : ClassTypeItem, PsiTypeItem(codebase, psiType) {
    // It should be possible to do `psiType.rawType().canonicalText` instead, but this doesn't
    // always work if psi is unable to resolve the reference.
    // See https://youtrack.jetbrains.com/issue/KTIJ-27093 for more details.
    override val qualifiedName = PsiNameHelper.getQualifiedClassName(psiType.canonicalText, true)
    override val parameters: List<TypeItem> = psiType.parameters.map { create(codebase, it) }
    override val outerClassType =
        // TODO(b/300081840): this drops annotations on the outer class
        PsiNameHelper.getOuterClassReference(psiType.canonicalText).let { outerClassName ->
            // [PsiNameHelper.getOuterClassReference] returns an empty string if there is no outer
            // class reference.
            // If the type is not an inner type, it returns the package name (e.g. for
            // "java.lang.String" it returns "java.lang").
            if (outerClassName == "" || codebase.findPsiPackage(outerClassName) != null) {
                null
            } else {
                val psiOuterClassType = codebase.createPsiType(outerClassName, psiType.psiContext)
                create(codebase, psiOuterClassType) as ClassTypeItem
            }
        }
    // This should be able to use `psiType.name`, but that sometimes returns null when run on the
    // AndroidX codebase.
    override val className: String = ClassTypeItem.computeClassName(qualifiedName)
}

/** A [PsiTypeItem] backed by a [PsiClassType] that represents a type variable.e */
class PsiVariableTypeItem(
    override val codebase: PsiBasedCodebase,
    override val psiType: PsiClassType
) : VariableTypeItem, PsiTypeItem(codebase, psiType) {
    override val name: String = psiType.name
    override val asTypeParameter: TypeParameterItem by lazy { asClass() as TypeParameterItem }
}

/** A [PsiTypeItem] backed by a [PsiWildcardType]. */
class PsiWildcardTypeItem(
    override val codebase: PsiBasedCodebase,
    override val psiType: PsiWildcardType
) : WildcardTypeItem, PsiTypeItem(codebase, psiType) {
    override val extendsBound: TypeItem? = createBound(psiType.extendsBound)
    override val superBound: TypeItem? = createBound(psiType.superBound)

    /**
     * If a [PsiWildcardType] doesn't have a bound, the bound is represented as the null [PsiType]
     * instead of just `null`.
     */
    private fun createBound(bound: PsiType): PsiTypeItem? {
        return if (bound == PsiTypes.nullType()) {
            null
        } else {
            create(codebase, bound)
        }
    }
}
