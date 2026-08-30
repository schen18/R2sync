package com.dissonance.r2sync.provider

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import com.dissonance.r2sync.R
import com.dissonance.r2sync.data.database.AppDatabase
import com.dissonance.r2sync.data.entity.FileMetadataEntity
import com.dissonance.r2sync.data.repository.SyncRepository
import com.dissonance.r2sync.provider.vault.HubVaultManager
import com.dissonance.r2sync.work.R2SyncWorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

class R2DocumentsProvider : DocumentsProvider() {

    companion object {
        const val TAG = "R2DocumentsProvider"
        const val AUTHORITY = "com.dissonance.r2sync.provider.documents"
        private const val ROOT_ID = "r2_vault_root"
        private const val ROOT_DOC_ID = "root"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_CAPACITY_BYTES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
    }

    private lateinit var database: AppDatabase
    private lateinit var repository: SyncRepository
    private lateinit var vaultManager: HubVaultManager

    // Post-close work (dirty marking) runs off the binder/main thread.
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        database = AppDatabase.getDatabase(ctx)
        repository = SyncRepository(ctx, database)
        vaultManager = HubVaultManager(ctx)
        Log.i(TAG, "R2DocumentsProvider initialized on authority: $AUTHORITY")
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return result
        val r2Config = repository.loadR2Config()

        val flags = DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD

        val totalBytes = vaultManager.getVaultTotalSizeBytes()
        val totalFiles = vaultManager.getVaultTotalFileCount()

        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
        row.add(DocumentsContract.Root.COLUMN_TITLE, "Cloudflare R2 Vault")
        row.add(DocumentsContract.Root.COLUMN_FLAGS, flags)
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, "Bucket: ${r2Config.bucketName} • $totalFiles Files (${totalBytes / 1024} KB)")
        row.add(DocumentsContract.Root.COLUMN_CAPACITY_BYTES, 10L * 1024 * 1024 * 1024) // 10 GB
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, (10L * 1024 * 1024 * 1024) - totalBytes)

        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        if (documentId == ROOT_DOC_ID) {
            val row = result.newRow()
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "R2 Vault")
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
            row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
            return result
        }

        val (namespace, relativePath) = parseDocId(documentId)
        val file = vaultManager.getLocalFile(namespace, relativePath)

        if (file.exists()) {
            val isDir = file.isDirectory
            val row = result.newRow()
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            row.add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else vaultManager.detectMimeType(file.name)
            )
            var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME
            if (isDir) {
                flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            } else {
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
            }
            row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            row.add(DocumentsContract.Document.COLUMN_SIZE, file.length())
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }

        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        val targetDir = if (parentDocumentId == ROOT_DOC_ID) {
            vaultManager.getVaultRootDirectory()
        } else {
            val (namespace, relativePath) = parseDocId(parentDocumentId)
            vaultManager.getLocalFile(namespace, relativePath)
        }

        val children = targetDir.listFiles() ?: arrayOf()
        for (child in children) {
            val isDir = child.isDirectory
            val childDocId = if (parentDocumentId == ROOT_DOC_ID) {
                "ns:${child.name}"
            } else {
                "$parentDocumentId/${child.name}"
            }

            val row = result.newRow()
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, childDocId)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, child.name)
            row.add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else vaultManager.detectMimeType(child.name)
            )
            var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME
            if (isDir) {
                flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            } else {
                flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
            }
            row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            row.add(DocumentsContract.Document.COLUMN_SIZE, if (isDir) 0L else child.length())
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, child.lastModified())
        }

        // Observers: the cursor watches its own children URI so it refreshes
        // when our mutations notify below.
        context?.contentResolver?.let { resolver ->
            result.setNotificationUri(
                resolver,
                DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId)
            )
        }

        return result
    }

    /**
     * FLAG_SUPPORTS_IS_CHILD is advertised on the root, so tree grants need a
     * real containment check — the framework default just returns false.
     */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return try {
            if (documentId == parentDocumentId) return false
            val parentPrefix = if (parentDocumentId == ROOT_DOC_ID) "" else "$parentDocumentId/"
            val isNamespaceChild = parentDocumentId == ROOT_DOC_ID &&
                    documentId.startsWith("ns:") && !documentId.drop(3).contains('/')
            val isDescendant = documentId.startsWith(parentPrefix) && documentId.length > parentPrefix.length
            if (!isNamespaceChild && !isDescendant) return false

            val (namespace, relativePath) = parseDocId(documentId)
            val parentDir = if (parentDocumentId == ROOT_DOC_ID) {
                vaultManager.getVaultRootDirectory()
            } else {
                val (pNs, pPath) = parseDocId(parentDocumentId)
                vaultManager.getLocalFile(pNs, pPath)
            }
            val target = vaultManager.getLocalFile(namespace, relativePath)
            if (!target.exists()) return false
            val canonicalParent = parentDir.canonicalFile
            val canonicalTarget = target.canonicalFile
            canonicalTarget.path.startsWith(canonicalParent.path + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val isWrite = mode.contains("w") || mode.contains("wt") || mode.contains("wa") || mode.contains("rw")
        val (namespace, relativePath) = parseDocId(documentId)
        val file = vaultManager.getLocalFile(namespace, relativePath)

        if (!file.exists()) {
            if (isWrite) {
                file.parentFile?.mkdirs()
                file.createNewFile()
            } else {
                throw FileNotFoundException("SAF Document not found: $documentId")
            }
        }

        val modeFlag = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }

        if (isWrite) {
            val ctx = context
            // Dirty-mark and schedule ONLY after the writer closes the
            // descriptor: doing it at open time raced the immediate sync
            // worker into uploading partially-written files, and tail writes
            // were never re-marked dirty.
            return ParcelFileDescriptor.open(
                file, modeFlag,
                android.os.Handler(android.os.Looper.getMainLooper())
            ) {
                providerScope.launch {
                    try {
                        val now = System.currentTimeMillis()
                        val existing = repository.getFileByNamespaceAndPath(namespace, relativePath)
                        if (existing != null) {
                            repository.markFileDirty(existing.id, now, file.length(), vaultManager.computeFileSha256(file))
                        } else {
                            repository.saveFileMetadata(
                                FileMetadataEntity(
                                    folderId = 0L,
                                    relativePath = relativePath,
                                    namespace = namespace,
                                    clientPackage = "com.android.documentsui",
                                    mimeType = vaultManager.detectMimeType(file.name),
                                    localSizeBytes = file.length(),
                                    localLastModified = now,
                                    isDirty = true,
                                    dirtyTimestamp = now,
                                    state = "DIRTY",
                                    cachedLocalPath = file.absolutePath
                                )
                            )
                        }
                        ctx?.let { R2SyncWorkScheduler.scheduleImmediateDirtySync(it) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to mark vault write dirty: $documentId", e)
                    }
                }
            }
        }

        return ParcelFileDescriptor.open(file, modeFlag)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val (namespace, parentPath) = if (parentDocumentId == ROOT_DOC_ID) {
            Pair("default", "")
        } else {
            parseDocId(parentDocumentId)
        }

        val childPath = if (parentPath.isEmpty()) displayName else "$parentPath/$displayName"
        val newFile = vaultManager.getLocalFile(namespace, childPath)

        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            newFile.mkdirs()
        } else {
            newFile.parentFile?.mkdirs()
            newFile.createNewFile()

            val now = System.currentTimeMillis()
            runBlocking {
                repository.saveFileMetadata(
                    FileMetadataEntity(
                        folderId = 0L,
                        relativePath = childPath,
                        namespace = namespace,
                        clientPackage = "com.android.documentsui",
                        mimeType = mimeType,
                        localSizeBytes = 0L,
                        localLastModified = now,
                        isDirty = true,
                        dirtyTimestamp = now,
                        state = "DIRTY",
                        cachedLocalPath = newFile.absolutePath
                    )
                )
            }
            context?.let { R2SyncWorkScheduler.scheduleImmediateDirtySync(it) }
        }

        notifyVaultChanged(buildDocId(namespace, childPath))
        return buildDocId(namespace, childPath)
    }

    override fun deleteDocument(documentId: String) {
        val (namespace, relativePath) = parseDocId(documentId)
        val deleted = vaultManager.deleteLocalFile(namespace, relativePath)
        if (!deleted) {
            // SAF treats a non-throwing return as success — the picker would
            // show the folder as deleted while it remains on disk, with its
            // metadata row already gone.
            throw FileNotFoundException("Failed to delete vault document: $documentId")
        }
        runBlocking {
            repository.deleteFileByNamespaceAndPath(namespace, relativePath)
        }
        notifyVaultChanged(documentId)
    }

    private fun notifyVaultChanged(documentId: String) {
        val ctx = context ?: return
        val parentDocId = documentId.substringBeforeLast('/', "")
            .ifEmpty { if (documentId.startsWith("ns:")) ROOT_DOC_ID else documentId }
        ctx.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocId),
            null
        )
        ctx.contentResolver.notifyChange(
            DocumentsContract.buildDocumentUri(AUTHORITY, documentId),
            null
        )
    }

    private fun parseDocId(docId: String): Pair<String, String> {
        if (docId.startsWith("ns:")) {
            val ns = docId.removePrefix("ns:")
            return Pair(ns, "")
        }
        val parts = docId.split("/", limit = 2)
        return if (parts.size == 2) {
            val ns = parts[0].removePrefix("ns:")
            Pair(ns, parts[1])
        } else {
            Pair("default", docId)
        }
    }

    private fun buildDocId(namespace: String, relativePath: String): String {
        return if (relativePath.isEmpty()) {
            "ns:$namespace"
        } else {
            "ns:$namespace/$relativePath"
        }
    }
}
