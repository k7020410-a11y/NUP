package com.nupmanager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale

/** Strict NUP v1 reader. Files are streamed and checked against the TOC size and SHA-256. */
class NupExtractor(private val context: Context) {
    data class Result(val outputFolderName: String, val extractedFiles: Int, val extractedBytes: Long)

    private data class Directory(val name: String, val firstChild: Int, val childCount: Int)
    private data class FileEntry(
        val name: String,
        val size: Long,
        val extentOffset: Long,
        val extentSize: Long,
        val sha256: ByteArray,
        val codec: Int
    )
    private data class PackageEntry(val rootDir: Int, val kind: Int, val titleId: Long, val version: Int)

    private class BoundedRandomAccessInputStream(
        private val file: RandomAccessFile,
        length: Long
    ) : InputStream() {
        private var remaining = length
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = file.read()
            if (value < 0) throw NupFormatException("Unexpected end of NUP extent")
            remaining--
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = file.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count < 0) throw NupFormatException("Unexpected end of NUP extent")
            remaining -= count
            return count
        }
        override fun close() = Unit
    }

    class NupFormatException(message: String) : IllegalArgumentException(message)

    fun extract(
        sourceUri: Uri,
        destinationTreeUri: Uri,
        onProgress: (completedFiles: Int, totalFiles: Int, fileName: String) -> Unit = { _, _, _ -> }
    ): Result {
        val temporary = spoolSourceToAvailableCache(sourceUri)
        try {
            val destination = DocumentFile.fromTreeUri(context, destinationTreeUri)
                ?: throw NupFormatException("Cannot open the selected destination folder")
            if (!destination.isDirectory || !destination.canWrite()) {
                throw NupFormatException("The selected destination is not writable")
            }

            RandomAccessFile(temporary, "r").use { raf ->
                val parsed = parse(raf)
                val sourceName = DocumentFile.fromSingleUri(context, sourceUri)?.name ?: "package.nup"
                val baseName = sourceName.substringBeforeLast('.', sourceName).ifBlank { "package" }
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "package" }
                val outputRoot = createFreshOutputDirectory(destination, "${baseName}_extracted")
                val totalFiles = parsed.second.size
                val estimatedBytes = parsed.second.fold(0L) { sum, entry ->
                    if (Long.MAX_VALUE - sum < entry.size) throw NupFormatException("Uncompressed size is too large")
                    sum + entry.size
                }
                onProgress(0, totalFiles, "Estimated extracted data: ${formatBytes(estimatedBytes)}")
                var completed = 0
                var extractedBytes = 0L

                try {
                    parsed.third.forEachIndexed { packageIndex, pkg ->
                    val packageFolderName = if (parsed.third.size == 1) null else {
                        val kindName = when (pkg.kind) {
                            0 -> "base"
                            1 -> "update"
                            2 -> "addon"
                            3 -> "addon_update"
                            else -> "system"
                        }
                        val id = java.lang.Long.toUnsignedString(pkg.titleId, 16).uppercase(Locale.ROOT).padStart(16, '0')
                        val packageName = "${kindName}_${id}_v${pkg.version}"
                        "${packageName}_${packageIndex + 1}"
                    }
                    val packageRoot = if (packageFolderName == null) outputRoot
                        else createDirectoryExclusive(outputRoot, packageFolderName)
                        walkDirectory(
                            raf = raf,
                            dirIndex = pkg.rootDir,
                            parent = packageRoot,
                            directories = parsed.first,
                            files = parsed.second,
                            children = parsed.fourth,
                            activeDirectories = HashSet(),
                            depth = 0,
                            totalFiles = totalFiles,
                            estimatedBytes = estimatedBytes,
                            onFile = { entry, relativePath ->
                                completed++
                                extractedBytes += entry.size
                                onProgress(completed, totalFiles, relativePath)
                            }
                        )
                    }
                } catch (error: Throwable) {
                    deleteDocumentTree(outputRoot)
                    throw error
                }
                return Result(outputRoot.name ?: "${baseName}_extracted", completed, extractedBytes)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun spoolSourceToAvailableCache(sourceUri: Uri): File {
        val candidates = listOfNotNull(context.externalCacheDir, context.cacheDir).distinct()
        candidates.forEach { dir ->
            dir.listFiles()?.filter { it.name.startsWith("nup-extract-") && it.name.endsWith(".nup") }
                ?.forEach { it.delete() }
        }
        val sourceSize = try {
            context.contentResolver.query(sourceUri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) { -1L }

        var lastNoSpace: IOException? = null
        for (dir in candidates) {
            if (!dir.exists() && !dir.mkdirs()) continue
            val freeBytes = dir.usableSpace
            if (sourceSize > 0L && freeBytes > 0L && freeBytes < sourceSize) {
                lastNoSpace = IOException("Not enough free space in ${dir.absolutePath}")
                continue
            }
            val temporary = try { File.createTempFile("nup-extract-", ".nup", dir) }
                catch (e: IOException) { lastNoSpace = e; continue }
            try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    temporary.outputStream().buffered().use { output -> input.copyTo(output, 128 * 1024) }
                } ?: throw NupFormatException("Cannot open the selected NUP file")
                return temporary
            } catch (e: IOException) {
                temporary.delete()
                if (!isNoSpace(e)) throw e
                lastNoSpace = e
            } catch (e: Throwable) {
                temporary.delete()
                throw e
            }
        }
        if (lastNoSpace != null) {
            throw NupFormatException(
                "Not enough free space for the temporary NUP copy. The app tried shared app storage and private cache; " +
                    "free space there or move the archive to a storage location with more room."
            )
        }
        throw NupFormatException("Cannot create temporary storage for extraction")
    }

    private fun isNoSpace(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase(Locale.ROOT)
            if (message.contains("enospc") || message.contains("no space left") || message.contains("disk full")) return true
            current = current.cause
        }
        return false
    }

    /** Parsed tuple: directories, files, packages, child node ids. */
    private fun parse(raf: RandomAccessFile): Quad<List<Directory>, List<FileEntry>, List<PackageEntry>, IntArray> {
        val actualLength = raf.length()
        if (actualLength < 0x80) throw NupFormatException("File is too short to be a NUP container")
        raf.seek(0)
        val headerBytes = ByteArray(0x80)
        raf.readFully(headerBytes)
        val h = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also(h::get)
        if (!magic.contentEquals(byteArrayOf('N'.code.toByte(), 'U'.code.toByte(), 'P'.code.toByte(), 1))) {
            throw NupFormatException("Invalid NUP magic or unsupported major version")
        }
        val headerSize = h.short.toInt() and 0xffff
        val headerVersion = h.short.toInt() and 0xffff
        val tocOffset = h.long
        val tocSize = h.long
        val expectedTocHash = ByteArray(32).also(h::get)
        val blobOffset = h.long
        val declaredFileSize = h.long
        val packageCount = h.int.toLong() and 0xffffffffL
        val flags = h.int
        if (headerSize < 0x80 || headerVersion < 1 || tocOffset != headerSize.toLong()) {
            throw NupFormatException("Unsupported or invalid NUP header")
        }
        if (flags != 0) throw NupFormatException("Unsupported NUP header flags")
        if (declaredFileSize != actualLength) throw NupFormatException("NUP file size does not match its header")
        if (packageCount !in 1..10_000) throw NupFormatException("Invalid package count")
        if (tocSize < 48 || tocSize % 16L != 0L || tocSize > Int.MAX_VALUE || tocOffset < 0 || tocOffset > actualLength - tocSize) {
            throw NupFormatException("Invalid table-of-contents bounds")
        }
        if (blobOffset < tocOffset + tocSize || blobOffset > actualLength) {
            throw NupFormatException("Invalid NUP blob offset")
        }
        raf.seek(tocOffset)
        val toc = ByteArray(tocSize.toInt())
        raf.readFully(toc)
        if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(toc), expectedTocHash)) {
            throw NupFormatException("NUP table-of-contents SHA-256 verification failed")
        }

        val b = ByteBuffer.wrap(toc).order(ByteOrder.LITTLE_ENDIAN)
        val tocMagic = ByteArray(4).also(b::get)
        if (!tocMagic.contentEquals(byteArrayOf('T'.code.toByte(), 'O'.code.toByte(), 'C'.code.toByte(), 1))) {
            throw NupFormatException("Invalid NUP table-of-contents magic")
        }
        val tocPackageCount = b.int.toLong() and 0xffffffffL
        if (tocPackageCount != packageCount) throw NupFormatException("Package count mismatch")
        val dirCountLong = b.long
        val fileCountLong = b.long
        val stringPoolOffsetLong = b.long
        val stringPoolSizeLong = b.long
        val reserved = b.long
        if (reserved != 0L || dirCountLong !in 1..1_000_000 || fileCountLong !in 0..1_000_000 ||
            stringPoolOffsetLong < 48 || stringPoolSizeLong < 0 ||
            stringPoolOffsetLong > tocSize || stringPoolSizeLong > tocSize - stringPoolOffsetLong) {
            throw NupFormatException("Invalid NUP table-of-contents fields")
        }
        val dirCount = dirCountLong.toInt()
        val fileCount = fileCountLong.toInt()
        val stringPoolOffset = stringPoolOffsetLong.toInt()
        val stringPoolSize = stringPoolSizeLong.toInt()
        val packagesStart = 48
        val dirsStartLong = packagesStart.toLong() + packageCount * 0x40L
        val filesStartLong = dirsStartLong + dirCountLong * 0x18L
        val childrenStartLong = filesStartLong + fileCountLong * 0x50L
        if (childrenStartLong > stringPoolOffsetLong) throw NupFormatException("Truncated NUP records")

        val packages = ArrayList<PackageEntry>(packageCount.toInt())
        b.position(packagesStart)
        repeat(packageCount.toInt()) {
            val titleId = b.long
            val rootDir = b.int
            b.int // exefs index
            b.int // romfs index
            val version = b.int
            val kind = b.get().toInt() and 0xff
            val packageFlags = b.get().toInt() and 0xff
            b.short
            b.int
            b.long
            b.int
            b.position(b.position() + 20)
            if (kind >= 5 || (packageFlags and 0xfe) != 0 || rootDir !in 0 until dirCount) {
                throw NupFormatException("Invalid NUP package record")
            }
            packages.add(PackageEntry(rootDir, kind, titleId, version))
        }

        val dirs = ArrayList<Directory>(dirCount)
        b.position(dirsStartLong.toInt())
        var childTotal = 0L
        repeat(dirCount) {
            val nameOffset = b.int.toLong() and 0xffffffffL
            val nameLength = b.int.toLong() and 0xffffffffL
            val firstChild = b.int.toLong() and 0xffffffffL
            val childCount = b.int.toLong() and 0xffffffffL
            val dirReserved = b.long
            if (dirReserved != 0L || nameOffset > stringPoolSizeLong || nameLength > stringPoolSizeLong - nameOffset ||
                childTotal > Int.MAX_VALUE || firstChild > Int.MAX_VALUE || childCount > Int.MAX_VALUE) {
                throw NupFormatException("Invalid NUP directory record")
            }
            val name = decodeName(toc, stringPoolOffset + nameOffset.toInt(), nameLength.toInt(), allowEmpty = true)
            dirs.add(Directory(name, firstChild.toInt(), childCount.toInt()))
            childTotal += childCount
        }
        if (childTotal > Int.MAX_VALUE || childrenStartLong + childTotal * 4L > stringPoolOffsetLong) {
            throw NupFormatException("Invalid NUP children array")
        }
        val childCount = childTotal.toInt()
        if (childrenStartLong + childTotal * 4L != stringPoolOffsetLong) {
            throw NupFormatException("Unexpected gap in NUP table-of-contents")
        }
        val children = IntArray(childCount)
        b.position(childrenStartLong.toInt())
        repeat(childCount) { children[it] = b.int }

        val files = ArrayList<FileEntry>(fileCount)
        b.position(filesStartLong.toInt())
        repeat(fileCount) {
            val nameOffset = b.int.toLong() and 0xffffffffL
            val nameLength = b.int.toLong() and 0xffffffffL
            val size = b.long
            val extentOffset = b.long
            val extentSize = b.long
            val hash = ByteArray(32).also(b::get)
            val codec = b.get().toInt() and 0xff
            val codecFlags = b.get().toInt() and 0xff
            val reserved2 = b.short.toInt()
            val reserved3 = ByteArray(12).also(b::get)
            if (nameOffset > stringPoolSizeLong || nameLength > stringPoolSizeLong - nameOffset || size < 0 ||
                extentOffset < 0 || extentSize < 0 || codec !in 0..1 || codecFlags != 0 || reserved2 != 0 ||
                reserved3.any { it.toInt() != 0 }) {
                throw NupFormatException("Invalid NUP file record")
            }
            if (size == 0L) {
                if (extentOffset != 0L || extentSize != 0L || codec != 0) throw NupFormatException("Invalid empty file extent")
            } else if (extentOffset < blobOffset || extentOffset % 16L != 0L || extentOffset > actualLength - extentSize || extentSize == 0L ||
                (codec == 0 && extentSize != size)) {
                throw NupFormatException("File extent is outside the NUP blob")
            }
            val name = decodeName(toc, stringPoolOffset + nameOffset.toInt(), nameLength.toInt(), allowEmpty = false)
            validateComponent(name)
            files.add(FileEntry(name, size, extentOffset, extentSize, hash, codec))
        }
        dirs.forEach { dir ->
            if (dir.firstChild > childCount || dir.childCount > childCount - dir.firstChild) {
                throw NupFormatException("Directory child range is outside the NUP table")
            }
            for (i in dir.firstChild until dir.firstChild + dir.childCount) {
                val childId = children[i].toLong() and 0xffffffffL
                if (childId >= dirCountLong + fileCountLong) throw NupFormatException("Invalid NUP child node id")
            }
        }
        return Quad(dirs, files, packages, children)
    }

    private fun walkDirectory(
        raf: RandomAccessFile,
        dirIndex: Int,
        parent: DocumentFile,
        directories: List<Directory>,
        files: List<FileEntry>,
        children: IntArray,
        activeDirectories: MutableSet<Int>,
        depth: Int,
        totalFiles: Int,
        estimatedBytes: Long,
        onFile: (FileEntry, String) -> Unit
    ) {
        if (depth > 512) throw NupFormatException("NUP directory nesting is too deep")
        if (dirIndex !in directories.indices || !activeDirectories.add(dirIndex)) {
            throw NupFormatException("Invalid or cyclic NUP directory tree")
        }
        val dir = directories[dirIndex]
        try {
            for (slot in dir.firstChild until dir.firstChild + dir.childCount) {
                val nodeId = children[slot].toLong() and 0xffffffffL
                if (nodeId < directories.size) {
                    val childDir = directories[nodeId.toInt()]
                    validateComponent(childDir.name)
                    val childOutput = createDirectoryExclusive(parent, childDir.name)
                    walkDirectory(raf, nodeId.toInt(), childOutput, directories, files, children,
                        activeDirectories, depth + 1, totalFiles, estimatedBytes, onFile)
                } else {
                    val fileIndex = (nodeId - directories.size).toInt()
                    val entry = files.getOrNull(fileIndex) ?: throw NupFormatException("Invalid NUP file node")
                    val output = parent.createFile("application/octet-stream", entry.name)
                        ?: throw NupFormatException("Cannot create output file: ${entry.name}")
                    try {
                        context.contentResolver.openOutputStream(output.uri, "w")?.buffered()?.use { stream ->
                            writeAndVerify(raf, entry, stream)
                        } ?: throw NupFormatException("Cannot write output file: ${entry.name}")
                    } catch (e: Throwable) {
                        output.delete()
                        if (isNoSpace(e)) {
                            throw NupFormatException(
                                "The selected destination could not store the output. This NUP expands to about ${formatBytes(estimatedBytes)}; " +
                                    "the selected volume or its provider may have less usable space. Choose another destination."
                            )
                        }
                        throw e
                    }
                    onFile(entry, entry.name)
                }
            }
        } finally {
            activeDirectories.remove(dirIndex)
        }
    }

    private fun writeAndVerify(raf: RandomAccessFile, entry: FileEntry, output: java.io.OutputStream) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        var written = 0L
        if (entry.size > 0) {
            raf.seek(entry.extentOffset)
            val bounded = BoundedRandomAccessInputStream(raf, entry.extentSize)
            val decoded: InputStream = if (entry.codec == 1) ZstdInputStream(bounded) else bounded
            decoded.use { input ->
                while (written < entry.size) {
                    val request = minOf(buffer.size.toLong(), entry.size - written).toInt()
                    val count = input.read(buffer, 0, request)
                    if (count < 0) throw NupFormatException("File ended before its declared size: ${entry.name}")
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    written += count
                }
                if (input.read() != -1) throw NupFormatException("File expands beyond its declared size: ${entry.name}")
            }
        }
        if (!MessageDigest.isEqual(digest.digest(), entry.sha256)) {
            throw NupFormatException("SHA-256 mismatch for extracted file: ${entry.name}")
        }
    }

    private fun createFreshOutputDirectory(parent: DocumentFile, baseName: String): DocumentFile {
        validateComponent(baseName)
        var candidate = baseName
        var suffix = 2
        while (parent.findFile(candidate) != null) {
            candidate = "${baseName}_$suffix"
            suffix++
        }
        return parent.createDirectory(candidate) ?: throw NupFormatException("Cannot create output folder: $candidate")
    }

    private fun deleteDocumentTree(document: DocumentFile) {
        if (document.isDirectory) document.listFiles().forEach(::deleteDocumentTree)
        document.delete()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun createDirectoryExclusive(parent: DocumentFile, name: String): DocumentFile {
        validateComponent(name)
        val existing = parent.findFile(name)
        if (existing != null) {
            throw NupFormatException("Duplicate or conflicting output path: $name")
        }
        return parent.createDirectory(name) ?: throw NupFormatException("Cannot create output folder: $name")
    }

    private fun decodeName(toc: ByteArray, offset: Int, length: Int, allowEmpty: Boolean): String {
        if (length == 0 && !allowEmpty) throw NupFormatException("Empty file name in NUP")
        if (offset < 0 || length < 0 || offset > toc.size - length) throw NupFormatException("Invalid NUP name bounds")
        val bytes = toc.copyOfRange(offset, offset + length)
        val decoder = Charsets.UTF_8.newDecoder()
        val text = try { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw NupFormatException("Invalid UTF-8 name in NUP") }
        if (text.indexOf('\u0000') >= 0) throw NupFormatException("Invalid NUL character in NUP name")
        return text
    }

    private fun validateComponent(name: String) {
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.contains('\u0000')) {
            throw NupFormatException("Unsafe path component in NUP")
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
