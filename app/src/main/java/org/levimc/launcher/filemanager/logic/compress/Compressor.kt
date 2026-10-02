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

package org.levimc.launcher.filemanager.logic.compress

import org.levimc.launcher.filemanager.logic.ops.FilePermissions
import org.levimc.launcher.filemanager.os.FmLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val TAG = "FmCompress"
private const val BUFFER_SIZE = 64 * 1024

object Compressor {
    /**
     * 执行压缩，将一组条目打包为 ZIP 压缩包
     * @param sources 待压缩条目
     * @param output 输出压缩包路径（已含正确后缀）
     * @param options 压缩参数
     * @param onProgress 进度回调（completed, total, currentName）
     * @param checkCancel 取消检查；返回 true 表示取消
     * @return 输出路径与总条目数
     */
    suspend fun compress(
        sources: List<Path>,
        output: Path,
        options: CompressOptions,
        onProgress: (completed: Int, total: Int, currentName: String?) -> Unit,
        checkCancel: () -> Boolean,
        onBytes: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> }
    ): CompressSummary {
        require(sources.isNotEmpty()) { "No sources to compress" }

        val total = countFiles(sources)
        val bytesTotal = totalBytes(sources)
        val context = Context(options, total, onProgress, checkCancel, onBytes, bytesTotal)

        when (options.format) {
            CompressFormat.ZIP -> jdkZip(sources, output, context)
            // v664：7z/tar 需要 commons-compress（离线环境无依赖），暂不支持
            else -> throw IllegalStateException("暂不支持该压缩格式（v664 仅 ZIP）")
        }
        FilePermissions.apply(output)
        return CompressSummary(output, total)
    }

    private suspend fun jdkZip(
        sources: List<Path>,
        output: Path,
        ctx: Context
    ) {
        if (ctx.options.password != null) {
            // v664：JDK zip 不支持加密
            throw IllegalStateException("暂不支持加密压缩（v664 仅普通 ZIP）")
        }
        withContext(Dispatchers.IO) {
            BufferedOutputStream(Files.newOutputStream(output)).use { bos ->
                ZipOutputStream(bos).use { zip ->
                    val method = if (ctx.options.method == CompressMethod.STORE) {
                        ZipEntry.STORED
                    } else {
                        ZipEntry.DEFLATED
                    }
                    val defaultLevel = if (ctx.options.method == CompressMethod.STORE) 0 else 5
                    val level = (ctx.options.level ?: defaultLevel).coerceIn(0, 9)
                    zip.setLevel(level)
                    zip.setMethod(method)
                    for (source in sources) {
                        ctx.check()
                        val name = source.fileName?.toString() ?: "entry"
                        addJdkZipPath(zip, source, name, ctx, method)
                    }
                }
            }
        }
    }

    private suspend fun addJdkZipPath(
        zip: ZipOutputStream,
        source: Path,
        entryRoot: String,
        ctx: Context,
        method: Int
    ) {
        val attrs = withContext(Dispatchers.IO) {
            Files.readAttributes(
                source,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
        }
        if (attrs.isSymbolicLink) {
            FmLog.info(TAG, "Skip symlink during compress: $source")
            return
        }
        withContext(Dispatchers.IO) {
            if (attrs.isDirectory) {
                val dirName = if (entryRoot.endsWith("/")) entryRoot else "$entryRoot/"
                val entry = ZipEntry(dirName)
                zip.putNextEntry(entry)
                zip.closeEntry()
                val children = sortedChildren(source)
                for (child in children) {
                    ctx.check()
                    addJdkZipPath(zip, child, "$entryRoot/${child.fileName}", ctx, method)
                }
            } else {
                val entry = ZipEntry(entryRoot)
                if (method == ZipEntry.STORED) {
                    // STORED 需要预写 size/crc
                    entry.size = attrs.size()
                    entry.crc = crc32(source)
                }
                zip.putNextEntry(entry)
                Files.newInputStream(source).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        zip.write(buffer, 0, read)
                        ctx.bytes(read)
                    }
                }
                zip.closeEntry()
                ctx.tick(entryRoot)
            }
        }
    }

    private fun crc32(path: Path): Long {
        val crc = java.util.zip.CRC32()
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private suspend fun sortedChildren(dir: Path): List<Path> = withContext(Dispatchers.IO) {
        Files.list(dir).use { stream ->
            stream.sorted { a, b -> a.fileName.toString().compareTo(b.fileName.toString()) }
                .toList()
        }
    }

    private fun countFiles(sources: List<Path>): Int {
        var count = 0
        for (source in sources) {
            if (!Files.exists(source)) continue
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isSymbolicLink) count++
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                count++
            }
        }
        return count
    }

    private fun totalBytes(sources: List<Path>): Long {
        var total = 0L
        for (source in sources) {
            if (!Files.exists(source)) continue
            if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isSymbolicLink) total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                total += runCatching { Files.size(source) }.getOrDefault(0L)
            }
        }
        return total
    }

    private class Context(
        val options: CompressOptions,
        val total: Int,
        private val onProgress: (completed: Int, total: Int, currentName: String?) -> Unit,
        private val checkCancel: () -> Boolean,
        private val onBytes: (bytesDone: Long, bytesTotal: Long) -> Unit,
        val progressBytesTotal: Long
    ) {
        private var completed = 0
        private var bytesDone = 0L

        fun check() {
            if (checkCancel()) throw CancellationException("compress cancelled")
        }

        fun tick(name: String?) {
            completed++
            onProgress(completed, total, name)
        }

        fun bytes(n: Int) {
            bytesDone += n
            onBytes(bytesDone, progressBytesTotal)
        }
    }
}
