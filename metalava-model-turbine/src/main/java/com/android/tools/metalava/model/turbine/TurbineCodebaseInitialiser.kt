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

import com.android.tools.metalava.model.AnnotationAttribute
import com.android.tools.metalava.model.AnnotationAttributeValue
import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.ArrayTypeItem
import com.android.tools.metalava.model.BaseItemVisitor
import com.android.tools.metalava.model.DefaultAnnotationArrayAttributeValue
import com.android.tools.metalava.model.DefaultAnnotationAttribute
import com.android.tools.metalava.model.DefaultAnnotationSingleAttributeValue
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.PrimitiveTypeItem.Primitive
import com.android.tools.metalava.model.TypeNullability
import com.android.tools.metalava.model.TypeParameterList
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.turbine.binder.Binder
import com.google.turbine.binder.Binder.BindingResult
import com.google.turbine.binder.ClassPathBinder
import com.google.turbine.binder.Processing.ProcessorInfo
import com.google.turbine.binder.bound.SourceTypeBoundClass
import com.google.turbine.binder.bound.TurbineClassValue
import com.google.turbine.binder.bound.TypeBoundClass
import com.google.turbine.binder.bound.TypeBoundClass.FieldInfo
import com.google.turbine.binder.bound.TypeBoundClass.MethodInfo
import com.google.turbine.binder.bound.TypeBoundClass.ParamInfo
import com.google.turbine.binder.bound.TypeBoundClass.TyVarInfo
import com.google.turbine.binder.bytecode.BytecodeBoundClass
import com.google.turbine.binder.env.CompoundEnv
import com.google.turbine.binder.lookup.LookupKey
import com.google.turbine.binder.lookup.TopLevelIndex
import com.google.turbine.binder.sym.ClassSymbol
import com.google.turbine.binder.sym.TyVarSymbol
import com.google.turbine.diag.TurbineLog
import com.google.turbine.model.Const
import com.google.turbine.model.Const.ArrayInitValue
import com.google.turbine.model.Const.Kind
import com.google.turbine.model.Const.Value
import com.google.turbine.model.TurbineConstantTypeKind as PrimKind
import com.google.turbine.model.TurbineFlag
import com.google.turbine.tree.Tree.CompUnit
import com.google.turbine.tree.Tree.Ident
import com.google.turbine.type.AnnoInfo
import com.google.turbine.type.Type
import com.google.turbine.type.Type.ArrayTy
import com.google.turbine.type.Type.ClassTy
import com.google.turbine.type.Type.ClassTy.SimpleClassTy
import com.google.turbine.type.Type.PrimTy
import com.google.turbine.type.Type.TyKind
import com.google.turbine.type.Type.TyVar
import com.google.turbine.type.Type.WildTy
import com.google.turbine.type.Type.WildTy.BoundKind
import java.io.File
import java.util.Optional
import javax.lang.model.SourceVersion

/**
 * This initializer acts as an adapter between codebase and the output from Turbine parser.
 *
 * This is used for populating all the classes,packages and other items from the data present in the
 * parsed Tree
 */
open class TurbineCodebaseInitialiser(
    val units: List<CompUnit>,
    val codebase: TurbineBasedCodebase,
    val classpath: List<File>,
) {
    /** The output from Turbine Binder */
    private lateinit var bindingResult: BindingResult

    /** Map between ClassSymbols and TurbineClass for classes present in source */
    private lateinit var sourceClassMap: ImmutableMap<ClassSymbol, SourceTypeBoundClass>

    /** Map between ClassSymbols and TurbineClass for classes present in classPath */
    private lateinit var envClassMap: CompoundEnv<ClassSymbol, BytecodeBoundClass>

    private lateinit var index: TopLevelIndex

    /**
     * Binds the units with the help of Turbine's binder.
     *
     * Then creates the packages, classes and their members, as well as sets up various class
     * hierarchies using the binder's output
     */
    fun initialize() {
        // Bind the units
        try {
            val procInfo =
                ProcessorInfo.create(
                    ImmutableList.of(),
                    null,
                    ImmutableMap.of(),
                    SourceVersion.latest()
                )

            // Any non-fatal error (like unresolved symbols) will be captured in this log and will
            // be ignored.
            val log = TurbineLog()

            bindingResult =
                Binder.bind(
                    log,
                    ImmutableList.copyOf(units),
                    ClassPathBinder.bindClasspath(classpath.map { it.toPath() }),
                    procInfo,
                    ClassPathBinder.bindClasspath(listOf()),
                    Optional.empty()
                )!!
            sourceClassMap = bindingResult.units()
            envClassMap = bindingResult.classPathEnv()
            index = bindingResult.tli()
        } catch (e: Throwable) {
            throw e
        }
        createAllPackages()
        createAllClasses()
        correctNullability()
    }

    /**
     * Corrects the nullability of types in the codebase based on their context items. If an item is
     * non-null or nullable, its type is too.
     */
    private fun correctNullability() {
        codebase.accept(
            object : BaseItemVisitor() {
                override fun visitItem(item: Item) {
                    val type = item.type() ?: return
                    val implicitNullness = item.implicitNullness()
                    if (implicitNullness == true || item.modifiers.isNullable()) {
                        type.modifiers.setNullability(TypeNullability.NULLABLE)
                    } else if (implicitNullness == false || item.modifiers.isNonNull()) {
                        type.modifiers.setNullability(TypeNullability.NONNULL)
                    }
                    // Also make array components for annotation types non-null
                    if (
                        type is ArrayTypeItem && item.containingClass()?.isAnnotationType() == true
                    ) {
                        type.componentType.modifiers.setNullability(TypeNullability.NONNULL)
                    }
                }
            }
        )
    }

    private fun createAllPackages() {
        // Root package
        findOrCreatePackage("")

        for (unit in units) {
            val optPkg = unit.pkg()
            val pkg = if (optPkg.isPresent()) optPkg.get() else null
            var pkgName = ""
            if (pkg != null) {
                val pkgNameList = pkg.name().map { it.value() }
                pkgName = pkgNameList.joinToString(separator = ".")
            }
            findOrCreatePackage(pkgName)
        }
    }

    /**
     * Searches for the package with supplied name in the codebase's package map and if not found
     * creates the corresponding TurbinePackageItem and adds it to the package map.
     */
    private fun findOrCreatePackage(name: String): TurbinePackageItem {
        val pkgItem = codebase.findPackage(name)
        if (pkgItem != null) {
            return pkgItem as TurbinePackageItem
        } else {
            val modifiers = TurbineModifierItem.create(codebase, 0, null, false)
            val turbinePkgItem = TurbinePackageItem.create(codebase, name, modifiers)
            codebase.addPackage(turbinePkgItem)
            return turbinePkgItem
        }
    }

    private fun createAllClasses() {
        val classes = sourceClassMap.keys
        for (cls in classes) {

            // Turbine considers package-info as class and creates one for empty packages which is
            // not consistent with Psi
            if (cls.simpleName() == "package-info") {
                continue
            }

            findOrCreateClass(cls)
        }
    }

    /** Tries to create a class if not already present in codebase's classmap */
    internal fun findOrCreateClass(name: String): TurbineClassItem? {
        var classItem = codebase.findClass(name)

        if (classItem == null) {
            val symbol = getClassSymbol(name)
            symbol?.let { createClass(symbol) }
            classItem = codebase.findClass(name)
        }

        return classItem
    }

    /** Creates a class if not already present in codebase's classmap */
    private fun findOrCreateClass(sym: ClassSymbol): TurbineClassItem {
        val className = getQualifiedName(sym.binaryName())
        var classItem = codebase.findClass(className)

        if (classItem == null) {
            // Inner class should not be created directly from here. Instead create its
            // TopLevelClass which
            // will automatically create the innerclass via createInnerClasses method
            if (sym.binaryName().contains("$")) {
                val topClassSym = getClassSymbol(className)!!
                createClass(topClassSym)
            } else {
                createClass(sym)
            }
        }

        return codebase.findClass(className)!!
    }

    private fun createClass(sym: ClassSymbol): TurbineClassItem {

        var cls: TypeBoundClass? = sourceClassMap[sym]
        cls = if (cls != null) cls else envClassMap.get(sym)!!
        val decl = (cls as? SourceTypeBoundClass)?.decl()

        // Get the package item
        val pkgName = sym.packageName().replace('/', '.')
        val pkgItem = findOrCreatePackage(pkgName)

        // Create class
        val qualifiedName = getQualifiedName(sym.binaryName())
        val simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1)
        val fullName = sym.simpleName().replace('$', '.')
        val annotations = createAnnotations(cls.annotations())
        val modifierItem =
            TurbineModifierItem.create(
                codebase,
                cls.access(),
                annotations,
                isDeprecated(decl?.javadoc())
            )
        val typeParameters = createTypeParameters(cls.typeParameterTypes())
        val classItem =
            TurbineClassItem(
                codebase,
                simpleName,
                fullName,
                qualifiedName,
                modifierItem,
                TurbineClassType.getClassType(cls.kind()),
                typeParameters
            )

        // Setup the SuperClass
        if (!classItem.isInterface()) {
            val superClassItem =
                cls.superclass()?.let { superClass -> findOrCreateClass(superClass) }
            val superClassType = cls.superClassType()
            val superClassTypeItem =
                if (superClassType == null || superClassType.tyKind() == TyKind.ERROR_TY) null
                else createType(superClassType, false)
            classItem.setSuperClass(superClassItem, superClassTypeItem)
        }

        // Set direct interfaces
        classItem.directInterfaces = cls.interfaces().map { itf -> findOrCreateClass(itf) }

        // Set interface types
        classItem.setInterfaceTypes(
            cls.interfaceTypes()
                .filter { it.tyKind() != TyKind.ERROR_TY }
                .map { createType(it, false) }
        )

        // Create fields
        createFields(classItem, cls.fields())

        // Create methods
        createMethods(classItem, cls.methods())

        // Create constructors
        createConstructors(classItem, cls.methods())

        // Add to the codebase
        val isTopClass = cls.owner() == null
        codebase.addClass(classItem, isTopClass)

        // Add the class to corresponding PackageItem
        if (isTopClass) {
            classItem.containingPackage = pkgItem
            pkgItem.addTopClass(classItem)
            // If the class is top class, fix the constructor return type right away. Otherwise wait
            // for containingClass to be set via setInnerClasses
            fixCtorReturnType(classItem)
        }

        // Set the throwslist for methods
        classItem.methods.forEach { it.setThrowsTypes() }

        // Set the throwslist for constructors
        classItem.constructors.forEach { it.setThrowsTypes() }

        // Create InnerClasses.
        val children = cls.children()
        createInnerClasses(classItem, children.values.asList())

        return classItem
    }

    /** Creates a list of AnnotationItems from given list of Turbine Annotations */
    private fun createAnnotations(annotations: List<AnnoInfo>): List<AnnotationItem> {
        return annotations.mapNotNull { createAnnotation(it) }
    }

    private fun createAnnotation(annotation: AnnoInfo): TurbineAnnotationItem? {
        val annoAttrs = getAnnotationAttributes(annotation.values())

        val nameList = annotation.tree()?.let { tree -> tree.name().map { it.value() } }
        val simpleName = nameList?.let { it -> it.joinToString(separator = ".") }
        val clsSym = annotation.sym()
        val qualifiedName =
            if (clsSym == null) simpleName!! else getQualifiedName(clsSym.binaryName())

        return TurbineAnnotationItem(codebase, qualifiedName, annoAttrs)
    }

    /** Creates a list of AnnotationAttribute from the map of name-value attribute pairs */
    private fun getAnnotationAttributes(
        attrs: ImmutableMap<String, Const>
    ): List<AnnotationAttribute> {
        val attributes = mutableListOf<AnnotationAttribute>()
        for ((name, value) in attrs) {
            attributes.add(DefaultAnnotationAttribute(name, createAttrValue(value)))
        }
        return attributes
    }

    private fun createAttrValue(const: Const): AnnotationAttributeValue {
        if (const.kind() == Kind.ARRAY) {
            val arrayVal = const as ArrayInitValue
            return DefaultAnnotationArrayAttributeValue(
                { arrayVal.toString() },
                { arrayVal.elements().map { createAttrValue(it) } }
            )
        }
        return DefaultAnnotationSingleAttributeValue({ const.toString() }, { getValue(const) })
    }

    private fun getValue(const: Const): Any? {
        when (const.kind()) {
            Kind.PRIMITIVE -> {
                val value = const as Value
                return value.getValue()
            }
            // For cases like AnyClass.class, return the qualified name of AnyClass
            Kind.CLASS_LITERAL -> {
                val value = const as TurbineClassValue
                return value.type().toString()
            }
            else -> return const.toString()
        }
    }

    private fun createType(type: Type, isVarArg: Boolean): TurbineTypeItem {
        return when (val kind = type.tyKind()) {
            TyKind.PRIM_TY -> {
                type as PrimTy
                val annotations = createAnnotations(type.annos())
                // Primitives are always non-null.
                val modifiers = TurbineTypeModifiers(annotations, TypeNullability.NONNULL)
                when (type.primkind()) {
                    PrimKind.BOOLEAN ->
                        TurbinePrimitiveTypeItem(codebase, modifiers, Primitive.BOOLEAN)
                    PrimKind.BYTE -> TurbinePrimitiveTypeItem(codebase, modifiers, Primitive.BYTE)
                    PrimKind.CHAR -> TurbinePrimitiveTypeItem(codebase, modifiers, Primitive.CHAR)
                    PrimKind.DOUBLE ->
                        TurbinePrimitiveTypeItem(codebase, modifiers, Primitive.DOUBLE)
                    PrimKind.FLOAT -> TurbinePrimitiveTypeItem(codebase, modifiers, Primitive.FLOAT)
                    PrimKind.INT -> TurbinePrimitiveTypeItem(codebase, modifiers, Primitive.INT)
                    PrimKind.LONG -> TurbinePrimitiveTypeItem(codebase, modifiers, Primitive.LONG)
                    PrimKind.SHORT -> TurbinePrimitiveTypeItem(codebase, modifiers, Primitive.SHORT)
                    else ->
                        throw IllegalStateException("Invalid primitive type in API surface: $type")
                }
            }
            TyKind.ARRAY_TY -> {
                type as ArrayTy
                val componentType = createType(type.elementType(), false)
                val annotations = createAnnotations(type.annos())
                val modifiers = TurbineTypeModifiers(annotations)
                TurbineArrayTypeItem(codebase, modifiers, componentType, isVarArg)
            }
            TyKind.CLASS_TY -> {
                type as ClassTy
                var outerClass: TurbineClassTypeItem? = null
                // A ClassTy is represented by list of SimpleClassTY each representing an inner
                // class. e.g. , Outer.Inner.Inner1 will be represented by three simple classes
                // Outer, Outer.Inner and Outer.Inner.Inner1
                for (simpleClass in type.classes()) {
                    // For all outer class types, set the nullability to non-null.
                    outerClass?.modifiers?.setNullability(TypeNullability.NONNULL)
                    outerClass = createSimpleClassType(simpleClass, outerClass)
                }
                outerClass!!
            }
            TyKind.TY_VAR -> {
                type as TyVar
                val annotations = createAnnotations(type.annos())
                val modifiers = TurbineTypeModifiers(annotations)
                TurbineVariableTypeItem(codebase, modifiers, type.sym())
            }
            TyKind.WILD_TY -> {
                type as WildTy
                val annotations = createAnnotations(type.annotations())
                // Wildcards themselves don't have a defined nullability.
                val modifiers = TurbineTypeModifiers(annotations, TypeNullability.UNDEFINED)
                when (type.boundKind()) {
                    BoundKind.UPPER -> {
                        val upperBound = createType(type.bound(), false)
                        TurbineWildcardTypeItem(codebase, modifiers, upperBound, null)
                    }
                    BoundKind.LOWER -> {
                        // LowerBounded types have java.lang.Object as upper bound
                        val upperBound = createType(ClassTy.OBJECT, false)
                        val lowerBound = createType(type.bound(), false)
                        TurbineWildcardTypeItem(codebase, modifiers, upperBound, lowerBound)
                    }
                    BoundKind.NONE -> {
                        // Unbounded types have java.lang.Object as upper bound
                        val upperBound = createType(ClassTy.OBJECT, false)
                        TurbineWildcardTypeItem(codebase, modifiers, upperBound, null)
                    }
                    else ->
                        throw IllegalStateException("Invalid wildcard type in API surface: $type")
                }
            }
            TyKind.VOID_TY ->
                TurbinePrimitiveTypeItem(
                    codebase,
                    // Primitives are always non-null.
                    TurbineTypeModifiers(emptyList(), TypeNullability.NONNULL),
                    Primitive.VOID
                )
            TyKind.NONE_TY ->
                TurbinePrimitiveTypeItem(
                    codebase,
                    // Primitives are always non-null.
                    TurbineTypeModifiers(emptyList(), TypeNullability.NONNULL),
                    Primitive.VOID
                )
            else -> throw IllegalStateException("Invalid type in API surface: $kind")
        }
    }

    private fun createSimpleClassType(
        type: SimpleClassTy,
        outerClass: TurbineClassTypeItem?
    ): TurbineClassTypeItem {
        val annotations = createAnnotations(type.annos())
        val modifiers = TurbineTypeModifiers(annotations)
        val qualifiedName = getQualifiedName(type.sym().binaryName())
        val parameters = type.targs().map { createType(it, false) }
        return TurbineClassTypeItem(codebase, modifiers, qualifiedName, parameters, outerClass)
    }

    private fun createTypeParameters(
        tyParams: ImmutableMap<TyVarSymbol, TyVarInfo>
    ): TypeParameterList {
        if (tyParams.isEmpty()) return TypeParameterList.NONE

        val tyParamList = TurbineTypeParameterList(codebase)
        val result = mutableListOf<TurbineTypeParameterItem>()
        for ((sym, tyParam) in tyParams) {
            result.add(createTypeParameter(sym, tyParam))
        }
        tyParamList.typeParameters = result
        return tyParamList
    }

    private fun createTypeParameter(sym: TyVarSymbol, param: TyVarInfo): TurbineTypeParameterItem {
        val typeBounds = mutableListOf<TurbineTypeItem>()
        val upperBounds = param.upperBound()
        upperBounds.bounds().mapTo(typeBounds) { createType(it, false) }
        param.lowerBound()?.let { typeBounds.add(createType(it, false)) }
        val modifiers =
            TurbineModifierItem.create(codebase, 0, createAnnotations(param.annotations()), false)
        val typeParamItem =
            TurbineTypeParameterItem(codebase, modifiers, symbol = sym, bounds = typeBounds)
        codebase.addTypeParameter(sym, typeParamItem)
        return typeParamItem
    }

    /** This method sets up the inner class hierarchy. */
    private fun createInnerClasses(
        classItem: TurbineClassItem,
        innerClasses: ImmutableList<ClassSymbol>
    ) {
        classItem.innerClasses =
            innerClasses.map { cls ->
                val innerClassItem = createClass(cls)
                innerClassItem.containingClass = classItem
                fixCtorReturnType(innerClassItem)
                innerClassItem
            }
    }

    /** This methods creates and sets the fields of a class */
    private fun createFields(classItem: TurbineClassItem, fields: ImmutableList<FieldInfo>) {
        classItem.fields =
            fields.map { field ->
                val annotations = createAnnotations(field.annotations())
                val fieldModifierItem =
                    TurbineModifierItem.create(
                        codebase,
                        field.access(),
                        annotations,
                        isDeprecated(field.decl()?.javadoc())
                    )
                val type = createType(field.type(), false)
                TurbineFieldItem(
                    codebase,
                    field.name(),
                    classItem,
                    type,
                    fieldModifierItem,
                )
            }
    }

    private fun createMethods(classItem: TurbineClassItem, methods: List<MethodInfo>) {
        classItem.methods =
            methods
                .filter { it.sym().name() != "<init>" }
                .map { method ->
                    val annotations = createAnnotations(method.annotations())
                    val methodModifierItem =
                        TurbineModifierItem.create(
                            codebase,
                            method.access(),
                            annotations,
                            isDeprecated(method.decl()?.javadoc())
                        )
                    val typeParams = createTypeParameters(method.tyParams())
                    val methodItem =
                        TurbineMethodItem(
                            codebase,
                            method.sym(),
                            classItem,
                            createType(method.returnType(), false),
                            methodModifierItem,
                            typeParams,
                        )
                    createParameters(methodItem, method.parameters())
                    methodItem.throwsClassNames = getThrowsList(method.exceptions())
                    methodItem
                }
    }

    private fun createParameters(methodItem: TurbineMethodItem, parameters: List<ParamInfo>) {
        methodItem.parameters =
            parameters.mapIndexed { idx, parameter ->
                val annotations = createAnnotations(parameter.annotations())
                val parameterModifierItem =
                    TurbineModifierItem.create(codebase, parameter.access(), annotations, false)
                val type = createType(parameter.type(), parameterModifierItem.isVarArg())
                TurbineParameterItem(
                    codebase,
                    parameter.name(),
                    methodItem,
                    idx,
                    type,
                    parameterModifierItem,
                )
            }
    }

    private fun createConstructors(classItem: TurbineClassItem, methods: List<MethodInfo>) {
        var hasImplicitDefaultConstructor = false
        classItem.constructors =
            methods
                .filter { it.sym().name() == "<init>" }
                .map { constructor ->
                    val annotations = createAnnotations(constructor.annotations())
                    val constructorModifierItem =
                        TurbineModifierItem.create(
                            codebase,
                            constructor.access(),
                            annotations,
                            isDeprecated(constructor.decl()?.javadoc())
                        )
                    val typeParams = createTypeParameters(constructor.tyParams())
                    hasImplicitDefaultConstructor =
                        (constructor.access() and TurbineFlag.ACC_SYNTH_CTOR) != 0
                    val name = classItem.simpleName()
                    val constructorItem =
                        TurbineConstructorItem(
                            codebase,
                            name,
                            constructor.sym(),
                            classItem,
                            createType(constructor.returnType(), false),
                            constructorModifierItem,
                            typeParams,
                        )
                    createParameters(constructorItem, constructor.parameters())
                    constructorItem.throwsClassNames = getThrowsList(constructor.exceptions())
                    constructorItem
                }
        classItem.hasImplicitDefaultConstructor = hasImplicitDefaultConstructor
    }

    private fun getQualifiedName(binaryName: String): String {
        return binaryName.replace('/', '.').replace('$', '.')
    }

    /**
     * Turbine's Binder gives return type of constructors as void. This needs to be changed to
     * Class.toType().
     */
    private fun fixCtorReturnType(classItem: TurbineClassItem) {
        val result =
            classItem.constructors.map {
                it.setReturnType(classItem.toType())
                it
            }
        classItem.constructors = result
    }

    /**
     * Get the ClassSymbol corresponding to a qualified name. Since the Turbine's lookup method
     * returns only top-level classes, this method will return the ClassSymbol of outermost class
     * for inner classes.
     */
    private fun getClassSymbol(name: String): ClassSymbol? {
        val result = index.scope().lookup(createLookupKey(name))
        return result?.let { it.sym() as ClassSymbol }
    }

    /** Creates a LookupKey from a given name */
    private fun createLookupKey(name: String): LookupKey {
        val idents = name.split(".").mapIndexed { idx, it -> Ident(idx, it) }
        return LookupKey(ImmutableList.copyOf(idents))
    }

    private fun isDeprecated(javadoc: String?): Boolean {
        return javadoc?.contains("@deprecated") ?: false
    }

    private fun getThrowsList(throwsTypes: List<Type>): List<String> {
        return throwsTypes.mapNotNull { it ->
            val sym = (it as? ClassTy)?.sym()
            sym?.let { getQualifiedName(it.binaryName()) }
        }
    }
}
