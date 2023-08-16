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

package com.android.tools.metalava.model.text

import com.android.tools.metalava.model.MethodItem
import java.io.LineNumberReader
import java.io.Reader

/**
 * Encapsulates all the information related to the format of a signature file.
 *
 * Some of these will be initialized from the version specific defaults and some will be overridden
 * on the command line.
 */
data class FileFormat(
    val defaultsVersion: DefaultsVersion,
    // This defaults to SIGNATURE but can be overridden on the command line.
    val overloadedMethodOrder: OverloadedMethodOrder = OverloadedMethodOrder.SIGNATURE,
    val kotlinStyleNulls: Boolean,
    val conciseDefaultValues: Boolean,
) {
    /** The base version of the file format. */
    enum class DefaultsVersion(
        internal val version: String,
        factory: (DefaultsVersion) -> FileFormat,
    ) {
        V2(
            version = "2.0",
            factory = { defaultsVersion ->
                FileFormat(
                    defaultsVersion = defaultsVersion,
                    kotlinStyleNulls = false,
                    conciseDefaultValues = false,
                )
            }
        ),
        V3(
            version = "3.0",
            factory = { defaultsVersion ->
                FileFormat(
                    defaultsVersion = defaultsVersion,
                    kotlinStyleNulls = true,
                    conciseDefaultValues = false,
                )
            }
        ),
        V4(
            version = "4.0",
            factory = { defaultsVersion ->
                FileFormat(
                    defaultsVersion = defaultsVersion,
                    kotlinStyleNulls = true,
                    conciseDefaultValues = true,
                )
            }
        );

        /**
         * The defaults associated with this version.
         *
         * It is initialized via a factory to break the cycles where the [DefaultsVersion]
         * constructor depends on the [FileFormat] constructor and vice versa.
         */
        val defaults = factory(this)
    }

    enum class OverloadedMethodOrder(val comparator: Comparator<MethodItem>) {
        /** Sort overloaded methods according to source order. */
        SOURCE(MethodItem.sourceOrderForOverloadedMethodsComparator),

        /** Sort overloaded methods by their signature. */
        SIGNATURE(MethodItem.comparator)
    }

    /**
     * Apply some optional overrides, provided from the command line, to this format, returning a
     * new format.
     *
     * @param overloadedMethodOrder If non-null then override the [overloadedMethodOrder] property.
     * @return a format with the overrides applied.
     */
    fun applyOptionalCommandLineSuppliedOverrides(
        overloadedMethodOrder: OverloadedMethodOrder? = null,
    ): FileFormat {
        // Always apply the overloadedMethodOrder command line override to the format from the file
        // because the overloadedMethodOrder is not determined by the version (yet) but only by the
        // command line argument and its default.
        val effectiveOverloadedMethodOrder = overloadedMethodOrder ?: this.overloadedMethodOrder

        return copy(
            overloadedMethodOrder = effectiveOverloadedMethodOrder,
        )
    }

    fun header(): String {
        val prefix = SIGNATURE_FORMAT_PREFIX
        return prefix + defaultsVersion.version + "\n"
    }

    companion object {
        private val allDefaults = DefaultsVersion.values().map { it.defaults }.toList()

        // The defaults associated with version 2.0.
        val V2 = DefaultsVersion.V2.defaults

        // The defaults associated with version 3.0.
        val V3 = DefaultsVersion.V3.defaults

        // The defaults associated with version 4.0.
        val V4 = DefaultsVersion.V4.defaults

        // The defaults associated with the latest version.
        val LATEST = allDefaults.last()

        const val SIGNATURE_FORMAT_PREFIX = "// Signature format: "

        /**
         * The size of the buffer and read ahead limit.
         *
         * Should be big enough to handle any first package line, even one with lots of annotations.
         */
        private const val BUFFER_SIZE = 1024

        /**
         * Parse the start of the contents provided by [reader] to obtain the [FileFormat]
         *
         * @return the [FileFormat] or null if the reader was blank.
         */
        fun parseHeader(filename: String, reader: Reader): FileFormat? {
            val lineNumberReader =
                if (reader is LineNumberReader) reader else LineNumberReader(reader, BUFFER_SIZE)
            return parseHeader(filename, lineNumberReader)
        }

        /**
         * Parse the start of the contents provided by [reader] to obtain the [FileFormat]
         *
         * This consumes only the content that makes up the header. So, the rest of the file
         * contents can be read from the reader.
         *
         * @return the [FileFormat] or null if the reader was blank.
         */
        private fun parseHeader(filename: String, reader: LineNumberReader): FileFormat? {
            var line = reader.readLine()
            while (line != null && line.isBlank()) {
                line = reader.readLine()
            }
            if (line == null) {
                return null
            }

            for (format in allDefaults) {
                val header = format.header()
                if (header.startsWith(line)) {
                    return format
                }
            }

            throw ApiParseException("Unknown file format of $filename")
        }
    }
}
