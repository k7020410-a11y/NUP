package com.nupmanager

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object NupFormat {
    val MAGIC = byteArrayOf('N'.code.toByte(), 'U'.code.toByte(), 'P'.code.toByte(), 1)
    val TOC_MAGIC = byteArrayOf('T'.code.toByte(), 'O'.code.toByte(), 'C'.code.toByte(), 1)

    const val HEADER_SIZE = 0x80
    const val PKG_SIZE = 0x40
    const val DIR_SIZE = 0x18
    const val FILE_SIZE = 0x50
    const val EXTENT_ALIGN = 16
    const val NO_DIR = 0xFFFFFFFF.toInt()

    const val CODEC_RAW = 0
    const val CODEC_ZSTD = 1

    const val KIND_BASE = 0
    const val KIND_UPDATE = 1
    const val KIND_ADDON = 2

    const val PKG_FLAG_STANDALONE_UPDATE = 0x01
    const val FILE_TAG = 0x80000000.toInt()
}

class NupPacker {

    data class VirtualFile(
        val nameBytes: ByteArray,
        val docFile: DocumentFile,
        var size: Long = 0L,
        var sha256: ByteArray = ByteArray(32),
        var extentOffset: Long = 0L,
        var extentSize: Long = 0L,
        var codec: Int = NupFormat.CODEC_RAW
    )

    data class VirtualDir(
        val nameBytes: ByteArray,
        val children: MutableList<Any> = mutableListOf(), // VirtualDir or VirtualFile
        var id: Int = -1,
        var firstChild: Int = 0,
        var childCount: Int = 0
    )

    data class PackageInput(
        val kind: Int,
        val dirDoc: DocumentFile,
        var titleId: Long = 0L,
        var version: Int = 0,
        var flags: Int = 0,
        var npdm: ByteArray? = null,
        var buildId: ByteArray? = null
    )

    data class PackageRecord(
        val titleId: Long,
        val rootDir: Int,
        var exefsDir: Int = NupFormat.NO_DIR,
        var romfsDir: Int = NupFormat.NO_DIR,
        val version: Int,
        val kind: Int,
        val flags: Int,
        var metaOffset: Long = 0L,
        var metaSize: Long = 0L
    )

    class Tree {
        val dirs = mutableListOf<VirtualDir>()
        val files = mutableListOf<VirtualFile>()
        val children = mutableListOf<Int>()
        val pool = linkedMapOf<String, Int>()
        var poolSize = 0

        fun intern(nameBytes: ByteArray): Int {
            val key = String(nameBytes, Charsets.ISO_8859_1)
            val existing = pool[key]
            if (existing != null) return existing
            val off = poolSize
            pool[key] = off
            poolSize += nameBytes.size
            return off
        }

        fun assign() {
            var dirId = 0
            for (d in dirs) {
                d.id = dirId++
            }
            var fileId = 0
            val fileMap = mutableMapOf<VirtualFile, Int>()
            for (f in files) {
                fileMap[f] = fileId++
            }

            for (d in dirs) {
                d.firstChild = children.size
                d.childCount = d.children.size
                for (ch in d.children) {
                    if (ch is VirtualDir) {
                        children.add(ch.id)
                    } else if (ch is VirtualFile) {
                        val fid = fileMap[ch] ?: 0
                        children.add(NupFormat.FILE_TAG or fid)
                    }
                }
            }
        }
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val va = a[i].toInt() and 0xFF
            val vb = b[i].toInt() and 0xFF
            if (va != vb) return va.compareTo(vb)
        }
        return a.size.compareTo(b.size)
    }

    private fun walk(docDir: DocumentFile, nameBytes: ByteArray, tree: Tree): VirtualDir {
        val node = VirtualDir(nameBytes)
        val fileList = docDir.listFiles()

        // Sort deterministic bytewise UTF-8
        val sortedList = fileList.sortedWith { o1, o2 ->
            val b1 = (o1.name ?: "").toByteArray(Charsets.UTF_8)
            val b2 = (o2.name ?: "").toByteArray(Charsets.UTF_8)
            compareByteArrays(b1, b2)
        }

        for (item in sortedList) {
            val itemName = item.name ?: continue
            val itemBytes = itemName.toByteArray(Charsets.UTF_8)
            if (itemName == ".listing") continue

            if (item.isDirectory) {
                val childDir = walk(item, itemBytes, tree)
                node.children.add(childDir)
            } else if (item.isFile) {
                val vFile = VirtualFile(itemBytes, item)
                tree.files.add(vFile)
                node.children.add(vFile)
            }
        }
        tree.dirs.add(node)
        return node
    }

    private fun parseNpdmProgramId(data: ByteArray): Long? {
        val aci0 = "ACI0".toByteArray(Charsets.US_ASCII)
        for (i in 0 until data.size - 0x18) {
            if (data[i] == aci0[0] && data[i + 1] == aci0[1] &&
                data[i + 2] == aci0[2] && data[i + 3] == aci0[3]
            ) {
                val bb = ByteBuffer.wrap(data, i + 0x10, 8).order(ByteOrder.LITTLE_ENDIAN)
                return bb.long
            }
        }
        return null
    }

    private fun resolvePackageMeta(context: Context, pkg: PackageInput) {
        val exefs = pkg.dirDoc.findFile("exefs")
        val npdmFile = exefs?.findFile("main.npdm") ?: pkg.dirDoc.findFile("main.npdm")
        if (npdmFile != null && npdmFile.isFile) {
            try {
                context.contentResolver.openInputStream(npdmFile.uri)?.use { stream ->
                    val data = stream.readBytes()
                    pkg.npdm = data
                    val pid = parseNpdmProgramId(data)
                    if (pid != null && pid != 0L) {
                        pkg.titleId = pid
                    }
                }
            } catch (_: Exception) {}
        }

        val mainFile = exefs?.findFile("main") ?: pkg.dirDoc.findFile("main")
        if (mainFile != null && mainFile.isFile) {
            try {
                context.contentResolver.openInputStream(mainFile.uri)?.use { stream ->
                    val buf = ByteArray(0x60)
                    val read = stream.read(buf)
                    if (read == 0x60) {
                        val isNso = buf[0] == 'N'.code.toByte() && buf[1] == 'S'.code.toByte() &&
                                buf[2] == 'O'.code.toByte() && buf[3] == '0'.code.toByte()
                        val isNro = buf[0x10] == 'N'.code.toByte() && buf[0x11] == 'R'.code.toByte() &&
                                buf[0x12] == 'O'.code.toByte() && buf[0x13] == '0'.code.toByte()
                        if (isNso || isNro) {
                            pkg.buildId = buf.copyOfRange(0x40, 0x60)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun discoverPackages(context: Context, rootDir: DocumentFile): List<PackageInput> {
        val packages = mutableListOf<PackageInput>()
        val baseDir = rootDir.findFile("base")
        val updatesDir = rootDir.findFile("updates")
        val dlcDir = rootDir.findFile("dlc")

        if (baseDir != null && baseDir.isDirectory) {
            val basePkg = PackageInput(NupFormat.KIND_BASE, baseDir)
            resolvePackageMeta(context, basePkg)
            packages.add(basePkg)

            if (updatesDir != null && updatesDir.isDirectory) {
                collectUpdates(context, updatesDir, basePkg.titleId, 0, packages)
            }
            if (dlcDir != null && dlcDir.isDirectory) {
                collectDlcs(context, dlcDir, packages)
            }
        } else if (updatesDir != null && updatesDir.isDirectory) {
            collectUpdates(context, updatesDir, 0L, NupFormat.PKG_FLAG_STANDALONE_UPDATE, packages)
            if (dlcDir != null && dlcDir.isDirectory) {
                collectDlcs(context, dlcDir, packages)
            }
        } else if (rootDir.findFile("exefs") != null || rootDir.findFile("romfs") != null) {
            val basePkg = PackageInput(NupFormat.KIND_BASE, rootDir)
            resolvePackageMeta(context, basePkg)
            packages.add(basePkg)
        } else {
            val basePkg = PackageInput(NupFormat.KIND_BASE, rootDir)
            resolvePackageMeta(context, basePkg)
            packages.add(basePkg)
        }
        return packages
    }

    private fun collectUpdates(
        context: Context,
        updatesDir: DocumentFile,
        baseTitleId: Long,
        flags: Int,
        out: MutableList<PackageInput>
    ) {
        val files = updatesDir.listFiles().sortedBy { it.name ?: "" }
        for (f in files) {
            if (f.isDirectory) {
                val ver = f.name?.toIntOrNull() ?: 0
                val upd = PackageInput(NupFormat.KIND_UPDATE, f, version = ver, flags = flags)
                resolvePackageMeta(context, upd)
                if (upd.titleId == 0L) {
                    upd.titleId = baseTitleId
                }
                out.add(upd)
            }
        }
    }

    private fun collectDlcs(context: Context, dlcDir: DocumentFile, out: MutableList<PackageInput>) {
        val files = dlcDir.listFiles().sortedBy { it.name ?: "" }
        for (f in files) {
            if (f.isDirectory) {
                val tid = f.name?.toLongOrNull(16) ?: 0L
                for (sub in f.listFiles().sortedBy { it.name ?: "" }) {
                    if (sub.isDirectory) {
                        val dlcPkg = PackageInput(NupFormat.KIND_ADDON, sub, titleId = tid)
                        resolvePackageMeta(context, dlcPkg)
                        out.add(dlcPkg)
                    }
                }
            }
        }
    }

    fun pack(
        context: Context,
        inputDir: DocumentFile,
        outputFile: File,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit
    ) {
        val packages = discoverPackages(context, inputDir)
        if (packages.isEmpty()) {
            throw IllegalArgumentException("No content packages found in selected directory")
        }

        val tree = Tree()
        val roots = packages.map { pkg ->
            walk(pkg.dirDoc, ByteArray(0), tree)
        }
        tree.assign()

        // Intern names
        for (d in tree.dirs) tree.intern(d.nameBytes)
        for (f in tree.files) tree.intern(f.nameBytes)

        // Locate exefs/romfs
        val pkgRecords = mutableListOf<PackageRecord>()
        for (i in packages.indices) {
            val pkg = packages[i]
            val root = roots[i]
            val rec = PackageRecord(
                titleId = pkg.titleId,
                rootDir = root.id,
                version = pkg.version,
                kind = pkg.kind,
                flags = pkg.flags
            )
            for (ch in root.children) {
                if (ch is VirtualDir) {
                    val name = String(ch.nameBytes, Charsets.UTF_8)
                    if (name.equals("exefs", ignoreCase = true)) {
                        rec.exefsDir = ch.id
                    } else if (name.equals("romfs", ignoreCase = true)) {
                        rec.romfsDir = ch.id
                    }
                }
            }
            pkgRecords.add(rec)
        }

        // UUID calculation
        val uuidDigest = MessageDigest.getInstance("SHA-256")
        for (rec in pkgRecords) {
            val bb = ByteBuffer.allocate(8 + 4 + 1).order(ByteOrder.LITTLE_ENDIAN)
            bb.putLong(rec.titleId)
            bb.putInt(rec.version)
            bb.put(rec.kind.toByte())
            uuidDigest.update(bb.array())
        }
        val uuid = uuidDigest.digest().copyOfRange(0, 16)

        // TOC size (including zero padding to the format's 16-byte boundary)
        val tocContentSize = 48 + (pkgRecords.size * NupFormat.PKG_SIZE) +
                (tree.dirs.size * NupFormat.DIR_SIZE) +
                (tree.files.size * NupFormat.FILE_SIZE) +
                (tree.children.size * 4) +
                tree.poolSize
        val tocSize = ((tocContentSize + NupFormat.EXTENT_ALIGN - 1) / NupFormat.EXTENT_ALIGN) * NupFormat.EXTENT_ALIGN

        val blobOffset = (NupFormat.HEADER_SIZE + tocSize + NupFormat.EXTENT_ALIGN - 1) and
                (NupFormat.EXTENT_ALIGN - 1).inv()

        val totalFiles = tree.files.size
        val seen = mutableMapOf<String, Triple<Long, Long, Int>>()

        outputFile.parentFile?.mkdirs()
        java.io.RandomAccessFile(outputFile, "rw").use { raf ->
            raf.setLength(blobOffset.toLong())
            raf.seek(blobOffset.toLong())

            var blobCursor = blobOffset.toLong()
            val md = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024) // 128KB buffer for streaming without OOM

            for ((index, fnode) in tree.files.withIndex()) {
                val fName = String(fnode.nameBytes, Charsets.UTF_8)
                onProgress(index + 1, totalFiles, fName)

                // Pass 1: Compute SHA-256 and size in a streaming fashion (0 MB memory consumption)
                md.reset()
                var fileLen = 0L

                context.contentResolver.openInputStream(fnode.docFile.uri)?.use { stream ->
                    var r: Int
                    while (stream.read(buffer).also { r = it } != -1) {
                        md.update(buffer, 0, r)
                        fileLen += r
                    }
                }
                fnode.size = fileLen
                fnode.sha256 = md.digest()

                val hashHex = fnode.sha256.joinToString("") { "%02x".format(it) }
                val hit = seen[hashHex]

                if (hit != null && fnode.size > 0L) {
                    // Dedup: identical file, point to existing extent
                    fnode.extentOffset = hit.first
                    fnode.extentSize = hit.second
                    fnode.codec = hit.third
                } else if (fnode.size == 0L) {
                    // Empty file
                    fnode.extentOffset = 0L
                    fnode.extentSize = 0L
                    fnode.codec = NupFormat.CODEC_RAW
                } else {
                    // Pass 2: Write extent directly to output file via streaming
                    val aligned = (blobCursor + NupFormat.EXTENT_ALIGN - 1) and
                            (NupFormat.EXTENT_ALIGN - 1).toLong().inv()
                    val gap = (aligned - blobCursor).toInt()
                    if (gap > 0) {
                        raf.write(ByteArray(gap))
                    }
                    blobCursor = aligned
                    fnode.extentOffset = blobCursor
                    fnode.extentSize = fnode.size
                    fnode.codec = NupFormat.CODEC_RAW

                    context.contentResolver.openInputStream(fnode.docFile.uri)?.use { stream ->
                        var r: Int
                        while (stream.read(buffer).also { r = it } != -1) {
                            raf.write(buffer, 0, r)
                        }
                    }

                    blobCursor += fnode.size
                    seen[hashHex] = Triple(fnode.extentOffset, fnode.extentSize, fnode.codec)
                }
            }

            // Meta blocks
            val metaOffsets = mutableListOf<Pair<Long, Long>>()
            val dumpLabel = inputDir.name ?: "dump"
            for (pkg in packages) {
                val metaBytes = buildMetaBlock(pkg, dumpLabel)
                val mo = blobCursor
                raf.write(metaBytes)
                blobCursor += metaBytes.size
                metaOffsets.add(Pair(mo, metaBytes.size.toLong()))
            }
            val fileSize = blobCursor

            // Build TOC
            val tocBytes = buildToc(tree, pkgRecords, metaOffsets, tocSize)
            val tocSha = MessageDigest.getInstance("SHA-256").digest(tocBytes)

            // Superblock
            val superBytes = ByteBuffer.allocate(NupFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(NupFormat.MAGIC)
                putShort(NupFormat.HEADER_SIZE.toShort())
                putShort(1.toShort()) // header_version
                putLong(NupFormat.HEADER_SIZE.toLong()) // toc_offset
                putLong(tocSize.toLong())
                put(tocSha)
                putLong(blobOffset.toLong())
                putLong(fileSize)
                putInt(pkgRecords.size)
                putInt(0) // flags
                put(uuid)
                put(ByteArray(32)) // reserved
            }.array()

            raf.seek(0)
            raf.write(superBytes)
            raf.write(tocBytes)
            raf.setLength(fileSize)
        }
    }

    private fun buildMetaBlock(pkg: PackageInput, dumpLabel: String): ByteArray {
        val out = ByteArrayOutputStream()
        // NPDM TLV
        pkg.npdm?.let { npdmBytes ->
            writeTlv(out, 0x0001, npdmBytes)
        }
        // Build ID TLV
        pkg.buildId?.let { bId ->
            if (bId.size == 32) {
                writeTlv(out, 0x0007, bId)
            }
        }
        // Source dump name TLV
        writeTlv(out, 0x0008, dumpLabel.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun writeTlv(out: OutputStream, type: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(type.toShort())
            putShort(0.toShort()) // reserved
            putInt(data.size)
        }.array()
        out.write(header)
        out.write(data)
    }

    private fun buildToc(
        tree: Tree,
        pkgRecords: List<PackageRecord>,
        metaOffsets: List<Pair<Long, Long>>,
        tocSize: Int
    ): ByteArray {
        val buf = ByteBuffer.allocate(tocSize).order(ByteOrder.LITTLE_ENDIAN)

        // Reserve 48 bytes for toc header
        buf.position(48)

        // Packages
        for (i in pkgRecords.indices) {
            val rec = pkgRecords[i]
            val (mo, ms) = metaOffsets[i]
            buf.putLong(rec.titleId)
            buf.putInt(rec.rootDir)
            buf.putInt(rec.exefsDir)
            buf.putInt(rec.romfsDir)
            buf.putInt(rec.version)
            buf.put(rec.kind.toByte())
            buf.put(rec.flags.toByte())
            buf.putShort(0)
            buf.putInt(0)
            buf.putLong(mo)
            buf.putInt(ms.toInt())
            buf.put(ByteArray(20))
        }

        // Directories
        for (d in tree.dirs) {
            val off = tree.intern(d.nameBytes)
            buf.putInt(off)
            buf.putInt(d.nameBytes.size)
            buf.putInt(d.firstChild)
            buf.putInt(d.childCount)
            buf.putLong(0L)
        }

        // Files
        for (f in tree.files) {
            val off = tree.intern(f.nameBytes)
            buf.putInt(off)
            buf.putInt(f.nameBytes.size)
            buf.putLong(f.size)
            buf.putLong(f.extentOffset)
            buf.putLong(f.extentSize)
            buf.put(f.sha256)
            buf.put(f.codec.toByte())
            buf.put(0.toByte()) // flags
            buf.putShort(0)
            buf.put(ByteArray(12))
        }

        // Children
        for (cid in tree.children) {
            var c = cid
            if (c and NupFormat.FILE_TAG != 0) {
                val fid = c and NupFormat.FILE_TAG.inv()
                c = tree.dirs.size + fid
            }
            buf.putInt(c)
        }

        // String pool
        val poolOff = buf.position()
        val poolBytes = ByteArray(tree.poolSize)
        for ((strKey, off) in tree.pool) {
            val b = strKey.toByteArray(Charsets.ISO_8859_1)
            System.arraycopy(b, 0, poolBytes, off, b.size)
        }
        buf.put(poolBytes)

        // Write toc header at 0
        buf.position(0)
        buf.put(NupFormat.TOC_MAGIC)
        buf.putInt(pkgRecords.size)
        buf.putLong(tree.dirs.size.toLong())
        buf.putLong(tree.files.size.toLong())
        buf.putLong(poolOff.toLong())
        buf.putLong(poolBytes.size.toLong())
        buf.putLong(0L) // reserved

        return buf.array()
    }
}
