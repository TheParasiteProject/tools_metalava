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

import com.android.tools.metalava.model.AnnotationRetention
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.ConstructorItem
import com.android.tools.metalava.model.DefaultModifierList
import com.android.tools.metalava.model.FieldItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.PackageItem
import com.android.tools.metalava.model.PropertyItem
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.TypeParameterItem
import com.android.tools.metalava.model.TypeParameterList
import com.android.tools.metalava.model.TypeParameterListOwner
import java.util.function.Predicate

open class TextClassItem(
    override val codebase: TextCodebase,
    position: SourcePositionInfo = SourcePositionInfo.UNKNOWN,
    modifiers: DefaultModifierList,
    private var isInterface: Boolean = false,
    private var isEnum: Boolean = false,
    internal var isAnnotation: Boolean = false,
    val qualifiedName: String = "",
    val qualifiedTypeName: String = qualifiedName,
    var name: String = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1),
    val annotations: List<String>? = null,
    val typeParameterList: TypeParameterList = TypeParameterList.NONE
) :
    TextItem(codebase = codebase, position = position, modifiers = modifiers),
    ClassItem,
    TypeParameterListOwner {

    init {
        @Suppress("LeakingThis") modifiers.setOwner(this)
        if (typeParameterList is TextTypeParameterList) {
            @Suppress("LeakingThis") typeParameterList.setOwner(this)
        }
    }

    override val isTypeParameter: Boolean = false

    override var artifact: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClassItem) return false

        return qualifiedName == other.qualifiedName()
    }

    override fun hashCode(): Int {
        return qualifiedName.hashCode()
    }

    override fun interfaceTypes(): List<TypeItem> = interfaceTypes

    override fun allInterfaces(): Sequence<ClassItem> {
        return sequenceOf(
                // Add this if and only if it is an interface.
                if (isInterface) sequenceOf(this) else emptySequence(),
                interfaceTypes.asSequence().map { it.asClass() }.filterNotNull(),
            )
            .flatten()
    }

    private var innerClasses: MutableList<ClassItem> = mutableListOf()

    override var stubConstructor: ConstructorItem? = null

    override var hasPrivateConstructor: Boolean = false

    override fun innerClasses(): List<ClassItem> = innerClasses

    override fun hasImplicitDefaultConstructor(): Boolean {
        return false
    }

    override fun isInterface(): Boolean = isInterface

    override fun isAnnotationType(): Boolean = isAnnotation

    override fun isEnum(): Boolean = isEnum

    var containingClass: ClassItem? = null

    override fun containingClass(): ClassItem? = containingClass

    private var containingPackage: PackageItem? = null

    fun setContainingPackage(containingPackage: TextPackageItem) {
        this.containingPackage = containingPackage
    }

    fun setIsAnnotationType(isAnnotation: Boolean) {
        this.isAnnotation = isAnnotation
    }

    fun setIsEnum(isEnum: Boolean) {
        this.isEnum = isEnum
    }

    override fun containingPackage(): PackageItem =
        containingClass?.containingPackage() ?: containingPackage ?: error(this)

    override fun hasTypeVariables(): Boolean = typeParameterList.typeParameterCount() > 0

    override fun typeParameterList(): TypeParameterList = typeParameterList

    override fun typeParameterListOwnerParent(): TypeParameterListOwner? {
        return containingClass as? TypeParameterListOwner
    }

    override fun resolveParameter(variable: String): TypeParameterItem? {
        if (hasTypeVariables()) {
            for (t in typeParameterList().typeParameters()) {
                if (t.simpleName() == variable) {
                    return t
                }
            }
        }

        return null
    }

    private var superClass: ClassItem? = null
    private var superClassType: TypeItem? = null

    override fun superClass(): ClassItem? = superClass

    override fun superClassType(): TypeItem? = superClassType

    internal fun setSuperClass(superClass: ClassItem?, superClassType: TypeItem?) {
        this.superClass = superClass
        this.superClassType = superClassType
    }

    override fun setInterfaceTypes(interfaceTypes: List<TypeItem>) {
        this.interfaceTypes = interfaceTypes.toMutableList()
    }

    private var typeInfo: TextTypeItem? = null

    override fun toType(): TextTypeItem {
        if (typeInfo == null) {
            typeInfo = codebase.typeResolver.obtainTypeFromClass(this)
        }
        return typeInfo!!
    }

    private var interfaceTypes = mutableListOf<TypeItem>()
    private val constructors = mutableListOf<ConstructorItem>()
    private val methods = mutableListOf<MethodItem>()
    private val fields = mutableListOf<FieldItem>()
    private val properties = mutableListOf<PropertyItem>()

    override fun constructors(): List<ConstructorItem> = constructors

    override fun methods(): List<MethodItem> = methods

    override fun fields(): List<FieldItem> = fields

    override fun properties(): List<PropertyItem> = properties

    fun addInterface(itf: TypeItem) {
        interfaceTypes.add(itf)
    }

    fun addConstructor(constructor: TextConstructorItem) {
        constructors += constructor
    }

    fun addMethod(method: TextMethodItem) {
        methods += method
    }

    fun addField(field: TextFieldItem) {
        fields += field
    }

    fun addProperty(property: TextPropertyItem) {
        properties += property
    }

    fun addEnumConstant(field: TextFieldItem) {
        field.setEnumConstant(true)
        fields += field
    }

    override fun addInnerClass(cls: ClassItem) {
        innerClasses.add(cls)
    }

    override fun filteredSuperClassType(predicate: Predicate<Item>): TypeItem? {
        // No filtering in signature files: we assume signature APIs
        // have already been filtered and all items should match.
        // This lets us load signature files and rewrite them using updated
        // output formats etc.
        return superClassType
    }

    private var retention: AnnotationRetention? = null

    override fun getRetention(): AnnotationRetention {
        retention?.let {
            return it
        }

        if (!isAnnotationType()) {
            error("getRetention() should only be called on annotation classes")
        }

        retention = ClassItem.findRetention(this)
        return retention!!
    }

    private var fullName: String = name

    override fun simpleName(): String = name.substring(name.lastIndexOf('.') + 1)

    override fun fullName(): String = fullName

    override fun qualifiedName(): String = qualifiedName

    override fun isDefined(): Boolean {
        assert(emit == (position != SourcePositionInfo.UNKNOWN))
        return emit
    }

    override fun toString(): String = "class ${qualifiedName()}"

    override fun mapTypeVariables(target: ClassItem): Map<String, String> {
        return emptyMap()
    }

    override fun createDefaultConstructor(): ConstructorItem {
        return TextConstructorItem.createDefaultConstructor(codebase, this, position)
    }

    companion object {
        internal fun createStubClass(
            codebase: TextCodebase,
            name: String,
            isInterface: Boolean
        ): TextClassItem {
            val index = if (name.endsWith(">")) name.indexOf('<') else -1
            val qualifiedName = if (index == -1) name else name.substring(0, index)
            val typeParameterList =
                if (index == -1) {
                    TypeParameterList.NONE
                } else {
                    TextTypeParameterList.create(codebase, name.substring(index))
                }
            val fullName = getFullName(qualifiedName)
            val cls =
                TextClassItem(
                    codebase = codebase,
                    name = fullName,
                    qualifiedName = qualifiedName,
                    isInterface = isInterface,
                    modifiers = DefaultModifierList(codebase, DefaultModifierList.PUBLIC),
                    typeParameterList = typeParameterList
                )
            cls.emit = false // it's a stub

            return cls
        }

        private fun getFullName(qualifiedName: String): String {
            var end = -1
            val length = qualifiedName.length
            var prev = qualifiedName[length - 1]
            for (i in length - 2 downTo 0) {
                val c = qualifiedName[i]
                if (c == '.' && prev.isUpperCase()) {
                    end = i + 1
                }
                prev = c
            }
            if (end != -1) {
                return qualifiedName.substring(end)
            }

            return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
        }
    }
}
