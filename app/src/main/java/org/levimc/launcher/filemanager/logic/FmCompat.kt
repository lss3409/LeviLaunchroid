/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package org.levimc.launcher.filemanager.logic

import android.content.Context
import android.content.Intent
import java.io.File

/**
 * v664：原 com.movtery.zalithlauncher.utils.file 的工具适配
 * （Zalith 的 utils 未随文件管理器模块一起搬迁，此处按原语义补实现）。
 */

/** 文件名校验失败异常（携带错误类别与细节）。 */
class InvalidFilenameException(message: String) : Exception(message) {
    var isLeadingOrTrailingSpace: Boolean = false
    var isInvalidLength: Boolean = false
    var invalidLength: Int = -1
    var illegalCharacters: String? = null

    fun containsIllegalCharacters(): Boolean = !illegalCharacters.isNullOrEmpty()
}

/** Windows/Android 通用非法文件名字符。 */
private val ILLEGAL_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/**
 * 校验文件名合法性（首尾空格、长度 1..255、非法字符）。
 * @throws InvalidFilenameException 非法时抛出
 */
@Throws(InvalidFilenameException::class)
fun checkFilenameValidity(name: String) {
    if (name != name.trim()) {
        throw InvalidFilenameException("Leading or trailing space").apply {
            isLeadingOrTrailingSpace = true
        }
    }
    if (name.isEmpty() || name.length > 255) {
        throw InvalidFilenameException("Invalid length").apply {
            isInvalidLength = true
            invalidLength = name.length
        }
    }
    val illegal = name.filter { it in ILLEGAL_CHARS }
    if (illegal.isNotEmpty()) {
        throw InvalidFilenameException("Illegal characters: $illegal").apply {
            this.illegalCharacters = illegal
        }
    }
}

/** 分享文件（ACTION_SEND；无 FileProvider 配置时回退直接 file uri）。 */
fun shareFile(context: Context, file: File) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, android.net.Uri.fromFile(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}
