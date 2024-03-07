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

import kotlin.test.assertIs
import kotlin.test.assertNotNull

interface Assertions {

    /** Get the class from the [Codebase], failing if it does not exist. */
    fun Codebase.assertClass(qualifiedName: String): ClassItem {
        val classItem = findClass(qualifiedName)
        assertNotNull(classItem) { "Expected $qualifiedName to be defined" }
        return classItem
    }

    /** Get the package from the [Codebase], failing if it does not exist. */
    fun Codebase.assertPackage(pkgName: String): PackageItem {
        val packageItem = findPackage(pkgName)
        assertNotNull(packageItem) { "Expected $pkgName to be defined" }
        return packageItem
    }

    /** Get the field from the [ClassItem], failing if it does not exist. */
    fun ClassItem.assertField(fieldName: String): FieldItem {
        val fieldItem = findField(fieldName)
        assertNotNull(fieldItem) { "Expected $fieldName to be defined" }
        return fieldItem
    }

    /** Get the method from the [ClassItem], failing if it does not exist. */
    fun ClassItem.assertMethod(methodName: String, parameters: String): MethodItem {
        val methodItem = findMethod(methodName, parameters)
        assertNotNull(methodItem) { "Expected $methodName($parameters) to be defined" }
        return methodItem
    }

    /** Get the constructor from the [ClassItem], failing if it does not exist. */
    fun ClassItem.assertConstructor(parameters: String): ConstructorItem {
        val methodItem = findMethod(simpleName(), parameters)
        assertNotNull(methodItem) { "Expected ${simpleName()}($parameters) to be defined" }
        return assertIs(methodItem)
    }

    /** Get the annotation from the [Item], failing if it does not exist. */
    fun Item.assertAnnotation(parameters: String): AnnotationItem {
        val annoItem =
            modifiers.annotations().filter { it.qualifiedName == parameters }.firstOrNull()
        assertNotNull(annoItem) { "Expected item to be annotated with ($parameters)" }
        return assertIs(annoItem)
    }
}
