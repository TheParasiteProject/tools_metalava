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

package com.android.tools.metalava.model.source

import java.util.ServiceLoader

/** Service provider interface for a model implementation that consumes source code. */
interface SourceModelProvider {

    /** The name of the provider. */
    val providerName: String

    /** The set of supported languages. */
    val supportedLanguages: Set<SourceLanguage>

    /**
     * Create an [EnvironmentManager] that will manage any resources needed while creating
     * [SourceCodebase]s from source files.
     *
     * @param disableStderrDumping if false then the manager will output useful information to
     *   stderr, otherwise it will suppress the errors.
     * @param forTesting if true then the manager is being used in tests and should behave
     *   appropriately.
     */
    fun createEnvironmentManager(
        disableStderrDumping: Boolean = false,
        forTesting: Boolean = false,
    ): EnvironmentManager

    companion object {
        /**
         * Get an implementation of this interface that matches the [filter].
         *
         * @param filter the filter that selects the required provider.
         * @throws IllegalStateException if there is not exactly one provider that matches.
         */
        fun getImplementation(
            filter: (SourceModelProvider) -> Boolean,
            filterDescription: String
        ): SourceModelProvider {
            val unfiltered = ServiceLoader.load(SourceModelProvider::class.java).toList()
            val sourceModelProviders = unfiltered.filter(filter).toList()
            return sourceModelProviders.singleOrNull()
                ?: throw IllegalStateException(
                    "Expected exactly one SourceModelProvider $filterDescription but found $unfiltered of which $sourceModelProviders matched"
                )
        }

        /**
         * Get an implementation of this interface that matches the [requiredProvider].
         *
         * @param requiredProvider the [SourceModelProvider.providerName] of the required provider.
         * @throws IllegalStateException if there is not exactly one provider that matches.
         */
        fun getImplementation(requiredProvider: String): SourceModelProvider {
            return getImplementation(
                { it.providerName == requiredProvider },
                "called $requiredProvider"
            )
        }
    }
}
