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

import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.function.Predicate

/**
 * Represents a {@link https://docs.oracle.com/javase/8/docs/api/java/lang/Class.html Class}
 *
 * If you need to model array dimensions or resolved type parameters, see {@link
 * com.android.tools.metalava.model.TypeItem} instead
 */
@MetalavaApi
interface ClassItem : Item {
    /** The simple name of a class. In class foo.bar.Outer.Inner, the simple name is "Inner" */
    fun simpleName(): String

    /** The full name of a class. In class foo.bar.Outer.Inner, the full name is "Outer.Inner" */
    fun fullName(): String

    /**
     * The qualified name of a class. In class foo.bar.Outer.Inner, the qualified name is the whole
     * thing.
     */
    @MetalavaApi fun qualifiedName(): String

    /** Is the class explicitly defined in the source file? */
    fun isDefined(): Boolean

    /** Is this an innerclass? */
    @MetalavaApi fun isInnerClass(): Boolean = containingClass() != null

    /** Is this a top level class? */
    fun isTopLevelClass(): Boolean = containingClass() == null

    /** This [ClassItem] and all of its inner classes, recursively */
    fun allClasses(): Sequence<ClassItem> {
        return sequenceOf(this).plus(innerClasses().asSequence().flatMap { it.allClasses() })
    }

    override fun parent(): Item? = containingClass() ?: containingPackage()

    /**
     * The qualified name where inner classes use $ as a separator. In class foo.bar.Outer.Inner,
     * this method will return foo.bar.Outer$Inner. (This is the name format used in ProGuard keep
     * files for example.)
     */
    fun qualifiedNameWithDollarInnerClasses(): String {
        var curr: ClassItem? = this
        while (curr?.containingClass() != null) {
            curr = curr.containingClass()
        }

        if (curr == null) {
            return fullName().replace('.', '$')
        }

        return curr.containingPackage().qualifiedName() + "." + fullName().replace('.', '$')
    }

    /** Returns the internal name of the class, as seen in bytecode */
    fun internalName(): String {
        var curr: ClassItem? = this
        while (curr?.containingClass() != null) {
            curr = curr.containingClass()
        }

        if (curr == null) {
            return fullName().replace('.', '$')
        }

        return curr.containingPackage().qualifiedName().replace('.', '/') +
            "/" +
            fullName().replace('.', '$')
    }

    /** The super class of this class, if any */
    @MetalavaApi fun superClass(): ClassItem?

    /** All super classes, if any */
    fun allSuperClasses(): Sequence<ClassItem> {
        return superClass()?.let { cls ->
            return generateSequence(cls) { it.superClass() }
        }
            ?: return emptySequence()
    }

    /**
     * The super class type of this class, if any. The difference between this and [superClass] is
     * that the type reference can include type arguments; e.g. in "class MyList extends
     * List<String>" the super class is java.util.List and the super class type is
     * java.util.List<java.lang.String>.
     */
    fun superClassType(): TypeItem?

    /** Returns true if this class extends the given class (includes self) */
    fun extends(qualifiedName: String): Boolean {
        if (qualifiedName() == qualifiedName) {
            return true
        }

        val superClass = superClass()
        return superClass?.extends(qualifiedName)
            ?: when {
                isEnum() -> qualifiedName == JAVA_LANG_ENUM
                isAnnotationType() -> qualifiedName == JAVA_LANG_ANNOTATION
                else -> qualifiedName == JAVA_LANG_OBJECT
            }
    }

    /** Returns true if this class implements the given interface (includes self) */
    fun implements(qualifiedName: String): Boolean {
        if (qualifiedName() == qualifiedName) {
            return true
        }

        interfaceTypes().forEach {
            val cls = it.asClass()
            if (cls != null && cls.implements(qualifiedName)) {
                return true
            }
        }

        // Might be implementing via superclass
        if (superClass()?.implements(qualifiedName) == true) {
            return true
        }

        return false
    }

    /** Returns true if this class extends or implements the given class or interface */
    fun extendsOrImplements(qualifiedName: String): Boolean =
        extends(qualifiedName) || implements(qualifiedName)

    /** Any interfaces implemented by this class */
    @MetalavaApi fun interfaceTypes(): List<TypeItem>

    /**
     * All classes and interfaces implemented (by this class and its super classes and the
     * interfaces themselves)
     */
    fun allInterfaces(): Sequence<ClassItem>

    /** Any inner classes of this class */
    fun innerClasses(): List<ClassItem>

    /** The constructors in this class */
    @MetalavaApi fun constructors(): List<ConstructorItem>

    /** Whether this class has an implicit default constructor */
    fun hasImplicitDefaultConstructor(): Boolean

    /** The non-constructor methods in this class */
    @MetalavaApi fun methods(): List<MethodItem>

    /** The properties in this class */
    fun properties(): List<PropertyItem>

    /** The fields in this class */
    @MetalavaApi fun fields(): List<FieldItem>

    /** The members in this class: constructors, methods, fields/enum constants */
    fun members(): Sequence<MemberItem> {
        return fields().asSequence().plus(constructors().asSequence()).plus(methods().asSequence())
    }

    /** Whether this class is an interface */
    fun isInterface(): Boolean

    /** Whether this class is an annotation type */
    fun isAnnotationType(): Boolean

    /** Whether this class is an enum */
    fun isEnum(): Boolean

    /** Whether this class is a regular class (not an interface, not an enum, etc) */
    fun isClass(): Boolean = !isInterface() && !isAnnotationType() && !isEnum()

    /** The containing class, for inner classes */
    @MetalavaApi override fun containingClass(): ClassItem?

    /** The containing package */
    override fun containingPackage(): PackageItem

    /** Gets the type for this class */
    fun toType(): TypeItem

    override fun type(): TypeItem? = null

    override fun findCorrespondingItemIn(codebase: Codebase) = codebase.findClass(qualifiedName())

    /** Returns true if this class has type parameters */
    fun hasTypeVariables(): Boolean

    /**
     * Any type parameters for the class, if any, as a source string (with fully qualified class
     * names)
     */
    @MetalavaApi fun typeParameterList(): TypeParameterList

    fun isJavaLangObject(): Boolean {
        return qualifiedName() == JAVA_LANG_OBJECT
    }

    // Mutation APIs: Used to "fix up" the API hierarchy to only expose visible parts of the API.

    // This replaces the interface types implemented by this class
    fun setInterfaceTypes(interfaceTypes: List<TypeItem>)

    // Whether this class is a generic type parameter, such as T, rather than a non-generic type,
    // like String
    val isTypeParameter: Boolean

    var hasPrivateConstructor: Boolean

    /** The primary constructor for this class in Kotlin, if present. */
    val primaryConstructor: ConstructorItem?
        get() = constructors().singleOrNull { it.isPrimary }

    /**
     * Maven artifact of this class, if any. (Not used for the Android SDK, but used in for example
     * support libraries.
     */
    var artifact: String?

    override fun accept(visitor: ItemVisitor) {
        visitor.visit(this)
    }

    companion object {
        /** Looks up the retention policy for the given class */
        fun findRetention(cls: ClassItem): AnnotationRetention {
            val modifiers = cls.modifiers
            val annotation = modifiers.findAnnotation(AnnotationItem::isRetention)
            val value = annotation?.findAttribute(ANNOTATION_ATTR_VALUE)
            val source = value?.value?.toSource()
            return when {
                source == null -> AnnotationRetention.getDefault(cls)
                source.contains("CLASS") -> AnnotationRetention.CLASS
                source.contains("RUNTIME") -> AnnotationRetention.RUNTIME
                source.contains("SOURCE") -> AnnotationRetention.SOURCE
                source.contains("BINARY") -> AnnotationRetention.BINARY
                else -> AnnotationRetention.getDefault(cls)
            }
        }

        // Same as doclava1 (modulo the new handling when class names match)
        val comparator: Comparator<in ClassItem> = Comparator { o1, o2 ->
            val delta = o1.fullName().compareTo(o2.fullName())
            if (delta == 0) {
                o1.qualifiedName().compareTo(o2.qualifiedName())
            } else {
                delta
            }
        }

        /** A partial ordering over [ClassItem] comparing [ClassItem.fullName]. */
        val fullNameComparator: Comparator<ClassItem> = Comparator.comparing { it.fullName() }

        /** A total ordering over [ClassItem] comparing [ClassItem.qualifiedName]. */
        private val qualifiedComparator: Comparator<ClassItem> =
            Comparator.comparing { it.qualifiedName() }

        /**
         * A total ordering over [ClassItem] comparing [ClassItem.fullName] first and then
         * [ClassItem.qualifiedName].
         */
        val fullNameThenQualifierComparator: Comparator<ClassItem> =
            fullNameComparator.thenComparing(qualifiedComparator)

        fun classNameSorter(): Comparator<in ClassItem> = ClassItem.qualifiedComparator
    }

    fun findMethod(
        template: MethodItem,
        includeSuperClasses: Boolean = false,
        includeInterfaces: Boolean = false
    ): MethodItem? {
        if (template.isConstructor()) {
            return findConstructor(template as ConstructorItem)
        }

        methods()
            .asSequence()
            .filter { it.matches(template) }
            .forEach {
                return it
            }

        if (includeSuperClasses) {
            superClass()?.findMethod(template, true, includeInterfaces)?.let {
                return it
            }
        }

        if (includeInterfaces) {
            for (itf in interfaceTypes()) {
                val cls = itf.asClass() ?: continue
                cls.findMethod(template, includeSuperClasses, true)?.let {
                    return it
                }
            }
        }
        return null
    }

    /**
     * Finds a method matching the given method that satisfies the given predicate, considering all
     * methods defined on this class and its super classes
     */
    fun findPredicateMethodWithSuper(template: MethodItem, filter: Predicate<Item>?): MethodItem? {
        val method = findMethod(template, true, true)
        if (method == null) {
            return null
        }
        if (filter == null || filter.test(method)) {
            return method
        }
        return method.findPredicateSuperMethod(filter)
    }

    fun findConstructor(template: ConstructorItem): ConstructorItem? {
        constructors()
            .asSequence()
            .filter { it.matches(template) }
            .forEach {
                return it
            }
        return null
    }

    fun findField(
        fieldName: String,
        includeSuperClasses: Boolean = false,
        includeInterfaces: Boolean = false
    ): FieldItem? {
        val field = fields().firstOrNull { it.name() == fieldName }
        if (field != null) {
            return field
        }

        if (includeSuperClasses) {
            superClass()?.findField(fieldName, true, includeInterfaces)?.let {
                return it
            }
        }

        if (includeInterfaces) {
            for (itf in interfaceTypes()) {
                val cls = itf.asClass() ?: continue
                cls.findField(fieldName, includeSuperClasses, true)?.let {
                    return it
                }
            }
        }
        return null
    }

    /**
     * Find the [MethodItem] in this.
     *
     * If [methodName] is the same as [simpleName] then this will look for [ConstructorItem]s,
     * otherwise it will look for [MethodItem]s whose [MethodItem.name] is equal to [methodName].
     *
     * Out of those matching items it will select the first [MethodItem] (or [ConstructorItem]
     * subclass) whose parameters match the supplied parameters string. Parameters are matched
     * against a candidate [MethodItem] as follows:
     * * The [parameters] string is split on `,` and trimmed and then each item in the list is
     *   matched with the corresponding [ParameterItem] in `candidate.parameters()` as follows:
     * * Everything after `<` is removed.
     * * The result is compared to the result of calling [TypeItem.toErasedTypeString]`(candidate)`
     *   on the [ParameterItem.type].
     *
     * If every parameter matches then the matched [MethodItem] is returned. If no `candidate`
     * matches then it returns 'null`.
     *
     * @param methodName the name of the method or [simpleName] if looking for constructors.
     * @param parameters the comma separated erased types of the parameters.
     */
    fun findMethod(methodName: String, parameters: String): MethodItem? {
        if (methodName == simpleName()) {
            // Constructor
            constructors()
                .filter { parametersMatch(it, parameters) }
                .forEach {
                    return it
                }
        } else {
            methods()
                .filter { it.name() == methodName && parametersMatch(it, parameters) }
                .forEach {
                    return it
                }
        }

        return null
    }

    private fun parametersMatch(method: MethodItem, description: String): Boolean {
        val parameterStrings =
            description.splitToSequence(",").map(String::trim).filter(String::isNotEmpty).toList()
        val parameters = method.parameters()
        if (parameters.size != parameterStrings.size) {
            return false
        }
        for (i in parameters.indices) {
            var parameterString = parameterStrings[i]
            val index = parameterString.indexOf('<')
            if (index != -1) {
                parameterString = parameterString.substring(0, index)
            }
            val parameter = parameters[i].type().toErasedTypeString()
            if (parameter != parameterString) {
                return false
            }
        }

        return true
    }

    /** Returns the corresponding source file, if any */
    fun getSourceFile(): SourceFile? = null

    /** If this class is an annotation type, returns the retention of this class */
    fun getRetention(): AnnotationRetention

    /**
     * Return superclass matching the given predicate. When a superclass doesn't match, we'll keep
     * crawling up the tree until we find someone who matches.
     */
    fun filteredSuperclass(predicate: Predicate<Item>): ClassItem? {
        val superClass = superClass() ?: return null
        return if (predicate.test(superClass)) {
            superClass
        } else {
            superClass.filteredSuperclass(predicate)
        }
    }

    fun filteredSuperClassType(predicate: Predicate<Item>): TypeItem? {
        var superClassType: TypeItem? = superClassType() ?: return null
        var prev: ClassItem? = null
        while (superClassType != null) {
            val superClass = superClassType.asClass() ?: return null
            if (predicate.test(superClass)) {
                if (prev == null || superClass == superClass()) {
                    // Direct reference; no need to map type variables
                    return superClassType
                }
                if (!superClassType.hasTypeArguments()) {
                    // No type variables - also no need for mapping
                    return superClassType
                }

                return superClassType.convertType(this, prev)
            }

            prev = superClass
            superClassType = superClass.superClassType()
        }

        return null
    }

    /**
     * Return methods matching the given predicate. Forcibly includes local methods that override a
     * matching method in an ancestor class.
     */
    fun filteredMethods(
        predicate: Predicate<Item>,
        includeSuperClassMethods: Boolean = false
    ): Collection<MethodItem> {
        val methods = LinkedHashSet<MethodItem>()
        for (method in methods()) {
            if (predicate.test(method) || method.findPredicateSuperMethod(predicate) != null) {
                // val duplicated = method.duplicate(this)
                // methods.add(duplicated)
                methods.remove(method)
                methods.add(method)
            }
        }
        if (includeSuperClassMethods) {
            superClass()?.filteredMethods(predicate, includeSuperClassMethods)?.let {
                methods += it
            }
        }
        return methods
    }

    /** Returns the constructors that match the given predicate */
    fun filteredConstructors(predicate: Predicate<Item>): Sequence<ConstructorItem> {
        return constructors().asSequence().filter { predicate.test(it) }
    }

    /**
     * Return fields matching the given predicate. Also clones fields from ancestors that would
     * match had they been defined in this class.
     */
    fun filteredFields(predicate: Predicate<Item>, showUnannotated: Boolean): List<FieldItem> {
        val fields = LinkedHashSet<FieldItem>()
        if (showUnannotated) {
            for (clazz in allInterfaces()) {
                if (!clazz.isInterface()) {
                    continue
                }
                for (field in clazz.fields()) {
                    if (!predicate.test(field)) {
                        val duplicated = field.duplicate(this)
                        if (predicate.test(duplicated)) {
                            fields.remove(duplicated)
                            fields.add(duplicated)
                        }
                    }
                }
            }

            val superClass = superClass()
            if (superClass != null && !predicate.test(superClass) && predicate.test(this)) {
                // Include constants from hidden super classes.
                for (field in superClass.fields()) {
                    val fieldModifiers = field.modifiers
                    if (
                        !fieldModifiers.isStatic() ||
                            !fieldModifiers.isFinal() ||
                            !fieldModifiers.isPublic()
                    ) {
                        continue
                    }
                    if (!field.originallyHidden) {
                        val duplicated = field.duplicate(this)
                        if (predicate.test(duplicated)) {
                            duplicated.inheritedField = true
                            fields.remove(duplicated)
                            fields.add(duplicated)
                        }
                    }
                }
            }
        }
        for (field in fields()) {
            if (predicate.test(field)) {
                fields.remove(field)
                fields.add(field)
            }
        }
        if (fields.isEmpty()) {
            return emptyList()
        }
        val list = fields.toMutableList()
        list.sortWith(FieldItem.comparator)
        return list
    }

    fun filteredInterfaceTypes(predicate: Predicate<Item>): Collection<TypeItem> {
        val interfaceTypes =
            filteredInterfaceTypes(
                predicate,
                LinkedHashSet(),
                includeSelf = false,
                includeParents = false,
                target = this
            )
        if (interfaceTypes.isEmpty()) {
            return interfaceTypes
        }

        return interfaceTypes
    }

    fun allInterfaceTypes(predicate: Predicate<Item>): Collection<TypeItem> {
        val interfaceTypes =
            filteredInterfaceTypes(
                predicate,
                LinkedHashSet(),
                includeSelf = false,
                includeParents = true,
                target = this
            )
        if (interfaceTypes.isEmpty()) {
            return interfaceTypes
        }

        return interfaceTypes
    }

    private fun filteredInterfaceTypes(
        predicate: Predicate<Item>,
        types: LinkedHashSet<TypeItem>,
        includeSelf: Boolean,
        includeParents: Boolean,
        target: ClassItem
    ): LinkedHashSet<TypeItem> {
        val superClassType = superClassType()
        if (superClassType != null) {
            val superClass = superClassType.asClass()
            if (superClass != null) {
                if (!predicate.test(superClass)) {
                    superClass.filteredInterfaceTypes(
                        predicate,
                        types,
                        true,
                        includeParents,
                        target
                    )
                } else if (includeSelf && superClass.isInterface()) {
                    types.add(superClassType)
                    if (includeParents) {
                        superClass.filteredInterfaceTypes(
                            predicate,
                            types,
                            true,
                            includeParents,
                            target
                        )
                    }
                }
            }
        }
        for (type in interfaceTypes()) {
            val cls = type.asClass() ?: continue
            if (predicate.test(cls)) {
                if (hasTypeVariables() && type.hasTypeArguments()) {
                    val replacementMap = target.mapTypeVariables(this)
                    if (replacementMap.isNotEmpty()) {
                        val mapped = type.convertType(replacementMap)
                        types.add(mapped)
                        continue
                    }
                }
                types.add(type)
                if (includeParents) {
                    cls.filteredInterfaceTypes(predicate, types, true, includeParents, target)
                }
            } else {
                cls.filteredInterfaceTypes(predicate, types, true, includeParents, target)
            }
        }
        return types
    }

    fun allInnerClasses(includeSelf: Boolean = false): Sequence<ClassItem> {
        if (!includeSelf && innerClasses().isEmpty()) {
            return emptySequence()
        }

        val list = ArrayList<ClassItem>()
        if (includeSelf) {
            list.add(this)
        }
        addInnerClasses(list, this)
        return list.asSequence()
    }

    private fun addInnerClasses(list: MutableList<ClassItem>, cls: ClassItem) {
        for (innerClass in cls.innerClasses()) {
            list.add(innerClass)
            addInnerClasses(list, innerClass)
        }
    }

    /**
     * The default constructor to invoke on this class from subclasses; initially null but may be
     * updated during use. (Note that in some cases [stubConstructor] may not be in [constructors],
     * e.g. when we need to create a constructor to match a public parent class with a non-default
     * constructor and the one in the code is not a match, e.g. is marked @hide etc.)
     */
    var stubConstructor: ConstructorItem?

    /**
     * Creates a map of type variables from this class to the given target class. If class A<X,Y>
     * extends B<X,Y>, and B is declared as class B<M,N>, this returns the map {"X"->"M", "Y"->"N"}.
     * There could be multiple intermediate classes between this class and the target class, and in
     * some cases we could be substituting in a concrete class, e.g. class MyClass extends
     * B<String,Number> would return the map {"java.lang.String"->"M", "java.lang.Number"->"N"}.
     *
     * If [reverse] is true, compute the reverse map: keys are the variables in the target and the
     * values are the variables in the source.
     */
    fun mapTypeVariables(target: ClassItem): Map<String, String> = codebase.unsupported()

    /** Creates a constructor in this class */
    fun createDefaultConstructor(): ConstructorItem = codebase.unsupported()

    /** Creates a method corresponding to the given method signature in this class */
    fun createMethod(template: MethodItem): MethodItem = codebase.unsupported()

    fun addMethod(method: MethodItem): Unit = codebase.unsupported()

    fun addInnerClass(cls: ClassItem): Unit = codebase.unsupported()
}
