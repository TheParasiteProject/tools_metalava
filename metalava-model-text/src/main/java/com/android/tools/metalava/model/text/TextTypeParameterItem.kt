/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.tools.metalava.model.DefaultModifierList
import com.android.tools.metalava.model.TypeItem
import com.android.tools.metalava.model.TypeParameterItem
import com.android.tools.metalava.model.TypeParameterList
import com.android.tools.metalava.model.TypeParameterListOwner

class TextTypeParameterItem(
    codebase: TextCodebase,
    private var owner: TypeParameterListOwner?,
    private val typeParameterString: String,
    name: String,
    private var bounds: List<TypeItem>? = null
) :
    TextClassItem(
        codebase = codebase,
        modifiers = DefaultModifierList(codebase, DefaultModifierList.PUBLIC),
        name = name,
        qualifiedName = name,
        typeParameterList = TypeParameterList.NONE
    ),
    TypeParameterItem {

    override fun typeBounds(): List<TypeItem> {
        if (bounds == null) {
            val boundsStringList = bounds(typeParameterString, owner)
            bounds =
                if (boundsStringList.isEmpty()) {
                    emptyList()
                } else {
                    boundsStringList.map {
                        codebase.typeResolver.obtainTypeFromString(it, gatherTypeParams(owner))
                    }
                }
        }
        return bounds!!
    }

    override fun isReified(): Boolean {
        return typeParameterString.startsWith("reified")
    }

    internal fun setOwner(newOwner: TypeParameterListOwner) {
        owner = newOwner
    }

    companion object {
        fun create(
            codebase: TextCodebase,
            owner: TypeParameterListOwner?,
            typeParameterString: String,
            bounds: List<TypeItem>? = null
        ): TextTypeParameterItem {
            val length = typeParameterString.length
            var nameEnd = length
            for (i in 0 until length) {
                val c = typeParameterString[i]
                if (!Character.isJavaIdentifierPart(c)) {
                    nameEnd = i
                    break
                }
            }
            val name = typeParameterString.substring(0, nameEnd)
            return TextTypeParameterItem(
                codebase = codebase,
                owner = owner,
                typeParameterString = typeParameterString,
                name = name,
                bounds = bounds
            )
        }

        fun bounds(typeString: String?, owner: TypeParameterListOwner? = null): List<String> {
            val s = typeString ?: return emptyList()
            val index = s.indexOf("extends ")
            if (index == -1) {
                // See if this is a type variable that has bounds in the parent
                val parameters =
                    (owner as? TextMemberItem)
                        ?.containingClass()
                        ?.typeParameterList()
                        ?.typeParameters()
                        ?: return emptyList()
                for (p in parameters) {
                    if (p.simpleName() == s) {
                        return p.typeBounds().map { it.toTypeString() }
                    }
                }

                return emptyList()
            }
            val list = mutableListOf<String>()
            var angleBracketBalance = 0
            var start = index + "extends ".length
            val length = s.length
            for (i in start until length) {
                val c = s[i]
                if (c == '&' && angleBracketBalance == 0) {
                    add(list, typeString, start, i)
                    start = i + 1
                } else if (c == '<') {
                    angleBracketBalance++
                } else if (c == '>') {
                    angleBracketBalance--
                    if (angleBracketBalance == 0) {
                        add(list, typeString, start, i + 1)
                        start = i + 1
                    }
                }
            }
            if (start < length) {
                add(list, typeString, start, length)
            }
            return list
        }

        private fun add(list: MutableList<String>, s: String, from: Int, to: Int) {
            for (i in from until to) {
                if (!Character.isWhitespace(s[i])) {
                    var end = to
                    while (end > i && s[end - 1].isWhitespace()) {
                        end--
                    }
                    var begin = i
                    while (begin < end && s[begin].isWhitespace()) {
                        begin++
                    }
                    if (begin == end) {
                        return
                    }
                    val element = s.substring(begin, end)
                    list.add(element)
                    return
                }
            }
        }

        /** Collect all the type parameters in scope for the given [owner]. */
        private fun gatherTypeParams(owner: TypeParameterListOwner?): List<TypeParameterItem> {
            return owner?.let {
                it.typeParameterList().typeParameters() +
                    gatherTypeParams(owner.typeParameterListOwnerParent())
            }
                ?: emptyList()
        }
    }
}
