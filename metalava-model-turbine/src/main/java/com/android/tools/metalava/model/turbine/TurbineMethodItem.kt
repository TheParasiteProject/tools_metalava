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

package com.android.tools.metalava.model.turbine

import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.ParameterItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.TypeParameterList
import com.google.turbine.binder.sym.MethodSymbol

open class TurbineMethodItem(
    codebase: TurbineBasedCodebase,
    private val methodSymbol: MethodSymbol,
    private val containingClass: TurbineClassItem,
    protected var returnType: TurbineTypeItem,
    modifiers: TurbineModifierItem,
    private val typeParameters: TypeParameterList
) : TurbineItem(codebase, modifiers), MethodItem {

    private lateinit var superMethodList: List<MethodItem>
    internal lateinit var throwsClassNames: List<String>
    private lateinit var throwsTypes: List<ClassItem>
    internal lateinit var parameters: List<ParameterItem>

    override var inheritedMethod: Boolean = false
    override var inheritedFrom: ClassItem? = null

    override fun name(): String = methodSymbol.name()

    override fun parameters(): List<ParameterItem> = parameters

    override fun returnType(): TypeItem = returnType

    override fun throwsTypes(): List<ClassItem> = throwsTypes

    override fun isExtensionMethod(): Boolean {
        TODO("b/295800205")
    }

    override fun isConstructor(): Boolean = false

    override fun containingClass(): ClassItem = containingClass

    /**
     * Super methods for a given method M with containing class C are calculated as follows:
     * 1) Superclass Search: Traverse the class hierarchy, starting from C's direct superclass, and
     *    add the first method that matches M's signature to the list.
     * 2) Interface Supermethod Search: For each direct interface implemented by C, check if it
     *    contains a method matching M's signature. If found, return that method. If not,
     *    recursively apply this method to the direct interfaces of the current interface.
     *
     * Note: This method's implementation is based on MethodItem.matches method which only checks
     * that name and parameter list types match. Parameter names, Return types and Throws list types
     * are not matched
     */
    override fun superMethods(): List<MethodItem> {
        if (!::superMethodList.isInitialized) {
            if (isConstructor()) {
                superMethodList = emptyList()
            }

            val methods = mutableSetOf<MethodItem>()

            // Method from SuperClass or its ancestors
            containingClass().superClass()?.let {
                val superMethod = it.findMethod(this, includeSuperClasses = true)
                superMethod?.let { methods.add(superMethod) }
            }

            // Methods implemented from direct interfaces or its ancestors
            val containingTurbineClass = containingClass() as TurbineClassItem
            methods.addAll(superMethodsFromInterfaces(containingTurbineClass.directInterfaces()))

            superMethodList = methods.toList()
        }
        return superMethodList
    }

    private fun superMethodsFromInterfaces(interfaces: List<TurbineClassItem>): List<MethodItem> {
        var methods = mutableListOf<MethodItem>()

        for (itf in interfaces) {
            val itfMethod = itf.findMethod(this)
            if (itfMethod != null) methods.add(itfMethod)
            else methods.addAll(superMethodsFromInterfaces(itf.directInterfaces()))
        }
        return methods
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TurbineMethodItem

        if (methodSymbol != other.methodSymbol) return false

        return true
    }

    override fun hashCode(): Int {
        return methodSymbol.hashCode()
    }

    @Deprecated("This property should not be accessed directly.")
    override var _requiresOverride: Boolean? = null

    override fun duplicate(targetContainingClass: ClassItem): TurbineMethodItem =
        TODO("b/295800205")

    override fun findMainDocumentation(): String = TODO("b/295800205")

    override fun typeParameterList(): TypeParameterList = typeParameters

    internal fun setThrowsTypes() {
        val result = throwsClassNames.map { codebase.findOrCreateClass(it)!! }
        throwsTypes = result.sortedWith(ClassItem.fullNameComparator)
    }
}
