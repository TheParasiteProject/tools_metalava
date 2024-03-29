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

package com.android.tools.metalava.model.text

import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.DefaultModifierList
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.ParameterItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.TypeParameterItem
import com.android.tools.metalava.model.TypeParameterList
import com.android.tools.metalava.model.TypeParameterListOwner
import java.util.function.Predicate

open class TextMethodItem(
    codebase: TextCodebase,
    name: String,
    containingClass: ClassItem,
    modifiers: DefaultModifierList,
    private val returnType: TextTypeItem,
    private val parameters: List<TextParameterItem>,
    position: SourcePositionInfo
) :
    TextMemberItem(codebase, name, containingClass, position, modifiers = modifiers),
    MethodItem,
    TypeParameterListOwner {
    init {
        @Suppress("LeakingThis") modifiers.setOwner(this)
        parameters.forEach { it.containingMethod = this }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MethodItem) return false

        if (name() != other.name()) {
            return false
        }

        if (containingClass() != other.containingClass()) {
            return false
        }

        val parameters1 = parameters()
        val parameters2 = other.parameters()

        if (parameters1.size != parameters2.size) {
            return false
        }

        for (i in parameters1.indices) {
            val parameter1 = parameters1[i]
            val parameter2 = parameters2[i]
            if (parameter1.type() != parameter2.type()) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        return name().hashCode()
    }

    override fun isConstructor(): Boolean = false

    override fun returnType(): TypeItem = returnType

    override fun superMethods(): List<MethodItem> {
        if (isConstructor()) {
            return emptyList()
        }

        val list = mutableListOf<MethodItem>()

        var curr = containingClass().superClass()
        while (curr != null) {
            val superMethod = curr.findMethod(this)
            if (superMethod != null) {
                list.add(superMethod)
                break
            }
            curr = curr.superClass()
        }

        // Interfaces
        for (itf in containingClass().allInterfaces()) {
            val interfaceMethod = itf.findMethod(this)
            if (interfaceMethod != null) {
                list.add(interfaceMethod)
            }
        }

        return list
    }

    override fun findMainDocumentation(): String = documentation

    override fun findPredicateSuperMethod(predicate: Predicate<Item>): MethodItem? = null

    private var typeParameterList: TypeParameterList = TypeParameterList.NONE

    fun setTypeParameterList(typeParameterList: TypeParameterList) {
        this.typeParameterList = typeParameterList
    }

    override fun typeParameterList(): TypeParameterList = typeParameterList

    override fun typeParameterListOwnerParent(): TypeParameterListOwner? {
        return containingClass() as TextClassItem?
    }

    override fun resolveParameter(variable: String): TypeParameterItem? {
        for (t in typeParameterList.typeParameters()) {
            if (t.simpleName() == variable) {
                return t
            }
        }

        return (containingClass() as TextClassItem).resolveParameter(variable)
    }

    override fun duplicate(targetContainingClass: ClassItem): MethodItem {
        val duplicated =
            TextMethodItem(
                codebase,
                name(),
                targetContainingClass,
                modifiers.duplicate(),
                returnType,
                // Consider cloning these: they have back references to the parent method (though
                // it's unlikely anyone will care about the difference in parent methods)
                parameters,
                position
            )
        duplicated.inheritedFrom = containingClass()

        // Preserve flags that may have been inherited (propagated) from surrounding packages
        if (targetContainingClass.hidden) {
            duplicated.hidden = true
        }
        if (targetContainingClass.removed) {
            duplicated.removed = true
        }
        if (targetContainingClass.docOnly) {
            duplicated.docOnly = true
        }

        duplicated.deprecated = deprecated
        duplicated.annotationDefault = annotationDefault
        duplicated.throwsTypes.addAll(throwsTypes)
        duplicated.throwsClasses = throwsClasses
        duplicated.typeParameterList = typeParameterList

        return duplicated
    }

    override val synthetic: Boolean
        get() = isEnumSyntheticMethod()

    private val throwsTypes = mutableListOf<String>()
    private var throwsClasses: List<ClassItem>? = null

    fun throwsTypeNames(): List<String> {
        return throwsTypes
    }

    override fun throwsTypes(): List<ClassItem> =
        if (throwsClasses == null) emptyList() else throwsClasses!!

    fun setThrowsList(throwsClasses: List<ClassItem>) {
        this.throwsClasses = throwsClasses
    }

    override fun parameters(): List<ParameterItem> = parameters

    fun addException(throwsType: String) {
        throwsTypes += throwsType
    }

    private val varargs: Boolean = parameters.any { it.isVarArgs() }

    fun isVarArg(): Boolean = varargs

    override fun isExtensionMethod(): Boolean = codebase.unsupported()

    override var inheritedMethod: Boolean = false
    override var inheritedFrom: ClassItem? = null

    @Deprecated("This property should not be accessed directly.")
    override var _requiresOverride: Boolean? = null

    override fun toString(): String =
        "${if (isConstructor()) "constructor" else "method"} ${containingClass().qualifiedName()}.${toSignatureString()}"

    fun toSignatureString(): String =
        "${name()}(${parameters().joinToString { it.type().toSimpleType() }})"

    private var annotationDefault = ""

    fun setAnnotationDefault(default: String) {
        annotationDefault = default
    }

    override fun defaultValue(): String {
        return annotationDefault
    }

    override fun checkGenericParameterTypes(typeString1: String, typeString2: String): Boolean {
        if (typeString1[0].isUpperCase() && typeString1.length == 1) {
            return true
        }
        if (typeString2.length >= 2 && !typeString2[1].isLetterOrDigit()) {
            return true
        }
        return false
    }
}
