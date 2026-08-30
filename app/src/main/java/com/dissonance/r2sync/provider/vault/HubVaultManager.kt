package com.dissonance.r2sync.provider.vault

import android.content.Context
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale

class HubVaultManager(private val context: Context) {

    private val vaultRootDir: File by lazy {
        File(context.filesDir, "hub_vault").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    fun getVaultRootDirectory(): File = vaultRootDir

    fun getNamespaceDirectory(namespace: String): File {
        val cleanNamespace = sanitizeSegment(namespace)
        return File(vaultRootDir, cleanNamespace).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    /**
     * Resolves a vault file, rejecting any path that escapes the namespace
     * directory (".." segments or symlinks resolving outside it).
     */
    fun getLocalFile(namespace: String, relativePath: String): File {
        val nsDir = getNamespaceDirectory(namespace)
        val cleanPath = relativePath.trimStart('/')
        val targetFile = File(nsDir, cleanPath)
        val canonicalNs = nsDir.canonicalFile
        val canonicalTarget = targetFile.canonicalFile
        if (!canonicalTarget.path.startsWith(canonicalNs.path + File.separator) && canonicalTarget != canonicalNs) {
            throw IllegalArgumentException("Path escapes namespace vault: $namespace/$relativePath")
        }
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        return targetFile
    }

    fun writeLocalFile(namespace: String, relativePath: String, data: ByteArray): File {
        val file = getLocalFile(namespace, relativePath)
        FileOutputStream(file).use { out ->
            out.write(data)
            out.flush()
        }
        return file
    }

    fun writeLocalStream(namespace: String, relativePath: String, inputStream: InputStream): File {
        val file = getLocalFile(namespace, relativePath)
        FileOutputStream(file).use { out ->
            inputStream.copyTo(out)
            out.flush()
        }
        return file
    }

    fun readLocalFile(namespace: String, relativePath: String): ByteArray? {
        val file = getLocalFile(namespace, relativePath)
        return if (file.exists() && file.isFile) {
            file.readBytes()
        } else {
            null
        }
    }

    fun openInputStream(namespace: String, relativePath: String): InputStream? {
        val file = getLocalFile(namespace, relativePath)
        return if (file.exists() && file.isFile) {
            FileInputStream(file)
        } else {
            null
        }
    }

    fun openOutputStream(namespace: String, relativePath: String): OutputStream {
        val file = getLocalFile(namespace, relativePath)
        return FileOutputStream(file)
    }

    /** Deletes a vault file or (recursively) a directory. Returns success. */
    fun deleteLocalFile(namespace: String, relativePath: String): Boolean {
        return try {
            val file = getLocalFile(namespace, relativePath)
            when {
                !file.exists() -> true
                file.isDirectory -> file.deleteRecursively()
                else -> file.delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun listNamespaceFiles(namespace: String): List<File> {
        val nsDir = getNamespaceDirectory(namespace)
        val results = mutableListOf<File>()
        nsDir.walkTopDown().filter { it.isFile }.forEach { results.add(it) }
        return results
    }

    fun computeFileSha256(file: File): String {
        if (!file.exists() || !file.isFile) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    fun detectMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) return "application/octet-stream"
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return mime ?: when (extension) {
            "json" -> "application/json"
            "md", "markdown" -> "text/markdown"
            "txt", "log" -> "text/plain"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    fun getVaultTotalSizeBytes(): Long {
        return vaultRootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun getVaultTotalFileCount(): Int {
        return vaultRootDir.walkTopDown().count { it.isFile }
    }

    fun clearAllVaultData() {
        vaultRootDir.listFiles()?.forEach { file ->
            file.deleteRecursively()
        }
    }

    private fun sanitizeSegment(segment: String): String {
        return segment.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifEmpty { "default" }
    }
}
