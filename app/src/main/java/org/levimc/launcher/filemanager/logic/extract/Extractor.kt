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

package org.levimc.launcher.filemanager.logic.extract

import org.levimc.launcher.filemanager.logic.entry.ArchiveType
import org.levimc.launcher.filemanager.logic.ops.FilePermissions
import org.levimc.launcher.filemanager.os.FmLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private const val TAG = "FmExtract"
private const val BUFFER_SIZE = 64 * 1024

/** 文件存在，但不是可识别的压缩包格式（或压缩包已损坏无法解析） */
class NotArchiveException(message: String) : Exception(message)

object Extractor {
    /**
     * 将压缩包全部条目解压到目标目录
     * @param archive 压缩包路径
     * @param outputDir 目标目录
     * @param options 解压选项
     * @param onProgress 进度回调（completed, total, currentName）
     * @param checkCancel 取消检查；返回 true 表示取消
     * @return 解压结果汇总
     */
    suspend fun extract(
        archive: Path,
        outputDir: Path,
        options: ExtractOptions,
        onProgress: (completed: Int, total: Int, currentName: String?) -> Unit,
        checkCancel: () -> Boolean,
        onBytes: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> }
    ): ExtractSummary {
        // v664：JDK 版仅支持 ZIP（7z/tar 需 commons-compress，离线环境无依赖）
        if (!isZipSignature(archive)) {
            throw if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                NotArchiveException(archive.toString())
            } else {
                NoSuchFileException(archive.toString())
            }
        }
        if (options.password != null) {
            throw ArchivePasswordException(
                ArchivePasswordException.Type.REQUIRED,
                cause = IllegalStateException("加密压缩包暂不支持（v664 仅普通 ZIP）")
            )
        }
        return extractWithType(
            type = ArchiveType.ZIP,
            archive = archive,
            outputDir = outputDir,
            options = options,
            onProgress = onProgress,
            checkCancel = checkCancel,
            onBytes = onBytes
        )
    }

    private suspend fun extractWithType(
        type: ArchiveType,
        archive: Path,
        outputDir: Path,
        options: ExtractOptions,
        onProgress: (Int, Int, String?) -> Unit,
        checkCancel: () -> Boolean,
        onBytes: (Long, Long) -> Unit
    ): ExtractSummary {
        val total = countZip(archive)
        var completed = 0
        var bytesDone = 0L
        val bytesTotal = runCatching { Files.size(archive) }.getOrDefault(0L)
        fun report(name: String?) = onProgress(completed, total, name)
        fun countBytes(n: Int) {
            if (n > 0) {
                bytesDone += n
                onBytes(bytesDone, bytesTotal)
            }
        }

        extractZip(
            archive, outputDir, options, ::report, checkCancel,
            setCompleted = { completed = it }, onBytesN = ::countBytes
        )
        return ExtractSummary(total)
    }

    /**
     * 判断文件头部是否具有 ZIP 签名
     */
    private fun isZipSignature(archive: Path): Boolean = runCatching {
        Files.newInputStream(archive).use { input ->
            val buf = ByteArray(4)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            read >= 4 && buf[0] == 'P'.code.toByte() && buf[1] == 'K'.code.toByte() &&
                (buf[2] == 0x03.toByte() || buf[2] == 0x05.toByte() || buf[2] == 0x07.toByte()) &&
                buf[3] == 0x04.toByte()
        }
    }.getOrDefault(false)

    private fun countZip(archive: Path): Int {
        var count = 0
        runCatching {
            BufferedInputStream(Files.newInputStream(archive)).use { bis ->
                ZipInputStream(bis).use { zip ->
                    while (zip.nextEntry != null) {
                        count++
                    }
                }
            }
        }
        return count
    }

    private suspend fun extractZip(
        archive: Path,
        outputDir: Path,
        options: ExtractOptions,
        report: (String?) -> Unit,
        checkCancel: () -> Boolean,
        setCompleted: (Int) -> Unit,
        onBytesN: (Int) -> Unit
    ) {
        val rootDir = if (options.independentFolder) {
            val name = archive.fileName?.toString()?.substringBeforeLast('.') ?: "archive"
            outputDir.resolve(name)
        } else {
            outputDir
        }
        Files.createDirectories(rootDir)

        var completed = 0
        withContext(Dispatchers.IO) {
            BufferedInputStream(Files.newInputStream(archive)).use { bis ->
                ZipInputStream(bis).use { zip ->
                    while (true) {
                        if (checkCancel()) throw CancellationException("extract cancelled")
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        // Zip Slip 防护：目标路径必须落在 rootDir 内
                        val target = rootDir.resolve(name).normalize()
                        if (!target.startsWith(rootDir.normalize())) {
                            FmLog.warn(TAG, "Skip unsafe entry: $name")
                            zip.closeEntry()
                            continue
                        }
                        if (entry.isDirectory) {
                            Files.createDirectories(target)
                        } else {
                            Files.createDirectories(target.parent ?: rootDir)
                            Files.newOutputStream(target).use { out ->
                                copyStream(zip, out, onBytesN)
                            }
                        }
                        zip.closeEntry()
                        completed++
                        setCompleted(completed)
                        report(name)
                    }
                }
            }
        }
        FilePermissions.apply(rootDir)
    }

    private fun copyStream(input: InputStream, output: OutputStream, onBytes: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            onBytes(read)
        }
    }
}

/** v664：ZIP 顶层条目名（冲突预检用）。 */
fun topLevelNamesCompat(archive: Path): List<String> {
    val names = mutableListOf<String>()
    runCatching {
        java.util.zip.ZipInputStream(java.nio.file.Files.newInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
            }
        }
    }
    return names
}
