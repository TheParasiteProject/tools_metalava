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

import com.android.tools.metalava.model.MetalavaApi

@MetalavaApi
class ApiParseException(
    message: String,
    private val file: String? = null,
    private val line: Int = 0,
    cause: Exception? = null,
) : Exception(message, cause) {

    internal constructor(
        message: String,
        tokenizer: Tokenizer,
        cause: Exception? = null,
    ) : this(message, file = tokenizer.fileName, line = tokenizer.line, cause = cause)

    internal constructor(
        message: String,
        position: SourcePositionInfo,
        cause: Exception? = null,
    ) : this(message, file = position.file, line = position.line, cause = cause)

    override val message: String
        get() {
            val sb = StringBuilder()
            if (file != null) {
                sb.append(file).append(':')
            }
            if (line > 0) {
                sb.append(line).append(':')
            }
            if (sb.isNotEmpty()) {
                sb.append(' ')
            }
            sb.append(super.message)
            return sb.toString()
        }
}
