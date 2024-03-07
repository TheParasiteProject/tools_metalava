/*
 * Copyright (C) 2020 The Android Open Source Project
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

import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.AnnotationManager
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.ClassResolver
import com.android.tools.metalava.model.DefaultAnnotationItem
import com.android.tools.metalava.model.DefaultCodebase
import com.android.tools.metalava.model.DefaultModifierList
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.PackageItem
import com.android.tools.metalava.model.PackageList
import java.io.File
import java.util.ArrayList
import java.util.HashMap

// Copy of ApiInfo in doclava1 (converted to Kotlin + some cleanup to make it work with metalava's
// data structures.
// (Converted to Kotlin such that I can inherit behavior via interfaces, in particular Codebase.)
class TextCodebase(
    location: File,
    annotationManager: AnnotationManager,
) : DefaultCodebase(location, "Codebase", true, annotationManager) {
    internal val mPackages = HashMap<String, TextPackageItem>(300)
    internal val mAllClasses = HashMap<String, TextClassItem>(30000)

    private val externalClasses = HashMap<String, ClassItem>()

    internal val typeResolver = TextTypeParser(this)

    override fun trustedApi(): Boolean = true

    override fun getPackages(): PackageList {
        val list = ArrayList<PackageItem>(mPackages.values)
        list.sortWith(PackageItem.comparator)
        return PackageList(this, list)
    }

    override fun size(): Int {
        return mPackages.size
    }

    override fun findClass(className: String): TextClassItem? {
        return mAllClasses[className]
    }

    override fun supportsDocumentation(): Boolean = false

    fun addPackage(pInfo: TextPackageItem) {
        // track the set of organized packages in the API
        mPackages[pInfo.name()] = pInfo

        // accumulate a direct map of all the classes in the API
        for (cl in pInfo.allClasses()) {
            mAllClasses[cl.qualifiedName()] = cl as TextClassItem
        }
    }

    fun registerClass(cls: TextClassItem) {
        mAllClasses[cls.qualifiedName] = cls
    }

    /**
     * Tries to find [name] in [mAllClasses]. If not found, then if a [classResolver] is provided it
     * will invoke that and return the [ClassItem] it returns if any. Otherwise, it will create an
     * empty stub class (or interface, if [isInterface] is true).
     *
     * Initializes outer classes and packages for the created class as needed.
     */
    fun getOrCreateClass(
        name: String,
        isInterface: Boolean = false,
        classResolver: ClassResolver? = null,
    ): ClassItem {
        val erased = TextTypeItem.eraseTypeArguments(name)
        val cls = mAllClasses[erased] ?: externalClasses[erased]
        if (cls != null) {
            return cls
        }

        if (classResolver != null) {
            val classItem = classResolver.resolveClass(erased)
            if (classItem != null) {
                // Save the class item, so it can be retrieved the next time this is loaded. This is
                // needed because otherwise TextTypeItem.asClass would not work properly.
                externalClasses[erased] = classItem
                return classItem
            }
        }

        val stubClass = TextClassItem.createStubClass(this, name, isInterface)
        mAllClasses[erased] = stubClass
        stubClass.emit = false

        val fullName = stubClass.fullName()
        if (fullName.contains('.')) {
            // We created a new inner class stub. We need to fully initialize it with outer classes,
            // themselves possibly stubs
            val outerName = erased.substring(0, erased.lastIndexOf('.'))
            // Pass classResolver = null, so it only looks in this codebase for the outer class.
            val outerClass = getOrCreateClass(outerName, isInterface = false, classResolver = null)

            // It makes no sense for a Foo to come from one codebase and Foo.Bar to come from
            // another.
            if (outerClass.codebase != stubClass.codebase) {
                throw IllegalStateException(
                    "Outer class $outerClass is from ${outerClass.codebase} but" +
                        " inner class $stubClass is from ${stubClass.codebase}"
                )
            }

            stubClass.containingClass = outerClass
            outerClass.addInnerClass(stubClass)
        } else {
            // Add to package
            val endIndex = erased.lastIndexOf('.')
            val pkgPath = if (endIndex != -1) erased.substring(0, endIndex) else ""
            val pkg =
                findPackage(pkgPath)
                    ?: run {
                        val newPkg =
                            TextPackageItem(
                                this,
                                pkgPath,
                                DefaultModifierList(this, DefaultModifierList.PUBLIC),
                                SourcePositionInfo.UNKNOWN
                            )
                        addPackage(newPkg)
                        newPkg.emit = false
                        newPkg
                    }
            stubClass.setContainingPackage(pkg)
            pkg.addClass(stubClass)
        }
        return stubClass
    }

    override fun findPackage(pkgName: String): TextPackageItem? {
        return mPackages[pkgName]
    }

    override fun createAnnotation(
        source: String,
        context: Item?,
    ): AnnotationItem {
        return DefaultAnnotationItem.create(this, source)
    }

    override fun toString(): String {
        return description
    }

    override fun unsupported(desc: String?): Nothing {
        error(desc ?: "Not supported for a signature-file based codebase")
    }
}
