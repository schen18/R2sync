package com.dissonance.r2sync.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import com.dissonance.r2sync.data.database.AppDatabase
import com.dissonance.r2sync.data.entity.ClientAppEntity
import com.dissonance.r2sync.data.entity.FileMetadataEntity
import com.dissonance.r2sync.data.repository.SyncRepository
import com.dissonance.r2sync.energy.EnergyManager
import com.dissonance.r2sync.provider.vault.HubVaultManager
import com.dissonance.r2sync.work.R2SyncWorkScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

class R2HubContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "R2HubContentProvider"

        private const val CODE_FILES = 100
        private const val CODE_FILE_ID = 101
        private const val CODE_FILE_PATH = 102
        private const val CODE_SYNC = 200
        private const val CODE_STATUS = 300
        private const val CODE_CLIENTS = 400

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(R2HubContract.AUTHORITY, R2HubContract.PATH_FILES, CODE_FILES)
            addURI(R2HubContract.AUTHORITY, "${R2HubContract.PATH_FILES}/#", CODE_FILE_ID)
            addURI(R2HubContract.AUTHORITY, "${R2HubContract.PATH_FILE}/*/*", CODE_FILE_PATH)
            addURI(R2HubContract.AUTHORITY, R2HubContract.PATH_SYNC, CODE_SYNC)
            addURI(R2HubContract.AUTHORITY, R2HubContract.PATH_STATUS, CODE_STATUS)
            addURI(R2HubContract.AUTHORITY, R2HubContract.PATH_CLIENTS, CODE_CLIENTS)
        }
    }

    private lateinit var database: AppDatabase
    private lateinit var repository: SyncRepository
    private lateinit var vaultManager: HubVaultManager

    // Post-close work (dirty marking) runs off the binder/main thread.
    private val providerScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private lateinit var energyManager: EnergyManager

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        database = AppDatabase.getDatabase(ctx)
        repository = SyncRepository(ctx, database)
        vaultManager = HubVaultManager(ctx)
        energyManager = EnergyManager(ctx)
        Log.i(TAG, "R2HubContentProvider initialized successfully on authority: ${R2HubContract.AUTHORITY}")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        enforceSecurity(isWrite = false)
        val ctx = context ?: return null

        return when (uriMatcher.match(uri)) {
            CODE_FILES -> {
                val namespace = uri.getQueryParameter("namespace")
                val isDirtyOnly = uri.getQueryParameter("is_dirty")?.toBoolean() ?: false

                val files = runBlocking {
                    when {
                        isDirtyOnly -> repository.getDirtyFiles()
                        !namespace.isNullOrBlank() -> repository.getFilesForNamespace(namespace)
                        else -> repository.getDirtyFiles() + repository.getFilesForNamespace("default")
                    }
                }

                val cursor = MatrixCursor(R2HubContract.Files.ALL_COLUMNS)
                for (f in files) {
                    cursor.addRow(arrayOf<Any?>(
                        f.id,
                        f.namespace,
                        f.relativePath,
                        f.mimeType,
                        f.localSizeBytes,
                        f.localLastModified,
                        f.localHash,
                        f.state,
                        if (f.isDirty) 1 else 0,
                        f.dirtyTimestamp,
                        f.clientPackage,
                        f.remoteEtag
                    ))
                }
                cursor.setNotificationUri(ctx.contentResolver, uri)
                cursor
            }

            CODE_FILE_ID -> {
                val id = ContentUris.parseId(uri)
                val file = runBlocking { repository.getFileById(id) }
                val cursor = MatrixCursor(R2HubContract.Files.ALL_COLUMNS)
                if (file != null) {
                    cursor.addRow(arrayOf<Any?>(
                        file.id,
                        file.namespace,
                        file.relativePath,
                        file.mimeType,
                        file.localSizeBytes,
                        file.localLastModified,
                        file.localHash,
                        file.state,
                        if (file.isDirty) 1 else 0,
                        file.dirtyTimestamp,
                        file.clientPackage,
                        file.remoteEtag
                    ))
                }
                cursor.setNotificationUri(ctx.contentResolver, uri)
                cursor
            }

            CODE_FILE_PATH -> {
                val segments = uri.pathSegments
                if (segments.size >= 3) {
                    val namespace = segments[1]
                    val relativePath = segments.drop(2).joinToString("/")
                    val file = runBlocking { repository.getFileByNamespaceAndPath(namespace, relativePath) }
                    val cursor = MatrixCursor(R2HubContract.Files.ALL_COLUMNS)
                    if (file != null) {
                        cursor.addRow(arrayOf<Any?>(
                            file.id,
                            file.namespace,
                            file.relativePath,
                            file.mimeType,
                            file.localSizeBytes,
                            file.localLastModified,
                            file.localHash,
                            file.state,
                            if (file.isDirty) 1 else 0,
                            file.dirtyTimestamp,
                            file.clientPackage,
                            file.remoteEtag
                        ))
                    }
                    cursor.setNotificationUri(ctx.contentResolver, uri)
                    cursor
                } else null
            }

            CODE_STATUS -> {
                val dirtyCount = runBlocking { repository.getDirtyFiles().size }
                val totalBytes = vaultManager.getVaultTotalSizeBytes()
                val totalFiles = vaultManager.getVaultTotalFileCount()
                val energy = energyManager.energyState.value
                val r2Config = repository.loadR2Config()

                val cursor = MatrixCursor(arrayOf(
                    R2HubContract.Status.COLUMN_STATUS,
                    R2HubContract.Status.COLUMN_DIRTY_COUNT,
                    R2HubContract.Status.COLUMN_TOTAL_FILES,
                    R2HubContract.Status.COLUMN_TOTAL_BYTES,
                    R2HubContract.Status.COLUMN_IS_CHARGING,
                    R2HubContract.Status.COLUMN_IS_WIFI,
                    R2HubContract.Status.COLUMN_BATTERY_PCT,
                    R2HubContract.Status.COLUMN_LAST_SYNC_TIME,
                    R2HubContract.Status.COLUMN_BUCKET_NAME
                ))

                cursor.addRow(arrayOf<Any?>(
                    if (dirtyCount > 0) "SYNC_PENDING" else "IDLE",
                    dirtyCount,
                    totalFiles,
                    totalBytes,
                    if (energy.isCharging) 1 else 0,
                    if (energy.isWifi) 1 else 0,
                    energy.batteryPercent,
                    System.currentTimeMillis(),
                    r2Config.bucketName
                ))
                cursor.setNotificationUri(ctx.contentResolver, uri)
                cursor
            }

            CODE_CLIENTS -> {
                val clients = runBlocking { repository.getAllClients() }
                val cursor = MatrixCursor(arrayOf(
                    R2HubContract.Clients.COLUMN_PACKAGE_ID,
                    R2HubContract.Clients.COLUMN_APP_NAME,
                    R2HubContract.Clients.COLUMN_NAMESPACE,
                    R2HubContract.Clients.COLUMN_TOTAL_FILES,
                    R2HubContract.Clients.COLUMN_TOTAL_BYTES,
                    R2HubContract.Clients.COLUMN_DIRTY_COUNT,
                    R2HubContract.Clients.COLUMN_LAST_SYNC,
                    R2HubContract.Clients.COLUMN_IS_AUTHORIZED
                ))
                for (c in clients) {
                    cursor.addRow(arrayOf<Any?>(
                        c.packageId,
                        c.appName,
                        c.namespace,
                        c.totalFiles,
                        c.totalBytes,
                        c.dirtyCount,
                        c.lastSyncTimestamp,
                        if (c.isAuthorized) 1 else 0
                    ))
                }
                cursor.setNotificationUri(ctx.contentResolver, uri)
                cursor
            }

            else -> null
        }
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            CODE_FILES -> R2HubContract.Files.CONTENT_TYPE
            CODE_FILE_ID, CODE_FILE_PATH -> R2HubContract.Files.CONTENT_ITEM_TYPE
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceSecurity(isWrite = true)
        val ctx = context ?: return null

        when (uriMatcher.match(uri)) {
            CODE_FILES, CODE_FILE_PATH -> {
                if (values == null) return null
                val namespace = values.getAsString(R2HubContract.Files.COLUMN_NAMESPACE) ?: "default"
                val relativePath = values.getAsString(R2HubContract.Files.COLUMN_RELATIVE_PATH) ?: "file_${System.currentTimeMillis()}"
                val mimeType = values.getAsString(R2HubContract.Files.COLUMN_MIME_TYPE) ?: vaultManager.detectMimeType(relativePath)
                val clientPackage = callingPackage ?: values.getAsString(R2HubContract.Files.COLUMN_CLIENT_PACKAGE) ?: ctx.packageName
                val isDirty = values.getAsBoolean(R2HubContract.Files.COLUMN_IS_DIRTY) ?: true
                val sizeBytes = values.getAsLong(R2HubContract.Files.COLUMN_SIZE_BYTES) ?: 0L

                val now = System.currentTimeMillis()
                val entity = FileMetadataEntity(
                    folderId = 0L,
                    relativePath = relativePath,
                    namespace = namespace,
                    clientPackage = clientPackage,
                    mimeType = mimeType,
                    localSizeBytes = sizeBytes,
                    localLastModified = now,
                    isDirty = isDirty,
                    dirtyTimestamp = if (isDirty) now else 0L,
                    state = if (isDirty) "DIRTY" else "SYNCED",
                    cachedLocalPath = vaultManager.getLocalFile(namespace, relativePath).absolutePath
                )

                val id = runBlocking {
                    // Auto-register client if not registered
                    val existingClient = repository.getClient(clientPackage)
                    if (existingClient == null) {
                        repository.upsertClient(
                            ClientAppEntity(
                                packageId = clientPackage,
                                appName = clientPackage.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                                namespace = namespace
                            )
                        )
                    }

                    repository.saveFileMetadata(entity)
                }

                if (isDirty) {
                    R2SyncWorkScheduler.scheduleImmediateDirtySync(ctx)
                }

                val resultUri = R2HubContract.Files.buildFileIdUri(id)
                ctx.contentResolver.notifyChange(R2HubContract.Files.CONTENT_URI, null)
                ctx.contentResolver.notifyChange(R2HubContract.Status.CONTENT_URI, null)
                return resultUri
            }

            CODE_SYNC -> {
                R2SyncWorkScheduler.scheduleImmediateDirtySync(ctx)
                ctx.contentResolver.notifyChange(R2HubContract.Status.CONTENT_URI, null)
                return R2HubContract.Status.CONTENT_URI
            }

            else -> throw IllegalArgumentException("Unsupported insert URI: $uri")
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceSecurity(isWrite = true)
        val ctx = context ?: return 0

        val count = when (uriMatcher.match(uri)) {
            CODE_FILE_ID -> {
                val id = ContentUris.parseId(uri)
                val fileMeta = runBlocking { repository.getFileById(id) }
                if (fileMeta != null) {
                    deleteForRemote(fileMeta.id, fileMeta.namespace, fileMeta.relativePath)
                    1
                } else 0
            }

            CODE_FILE_PATH -> {
                val segments = uri.pathSegments
                if (segments.size >= 3) {
                    val namespace = segments[1]
                    val relativePath = segments.drop(2).joinToString("/")
                    val fileMeta = runBlocking {
                        repository.getFileByNamespaceAndPath(namespace, relativePath)
                    }
                    if (fileMeta != null) {
                        deleteForRemote(fileMeta.id, namespace, relativePath)
                        1
                    } else {
                        // Untracked file: best-effort local removal only.
                        vaultManager.deleteLocalFile(namespace, relativePath)
                        1
                    }
                } else 0
            }

            else -> 0
        }

        if (count > 0) {
            ctx.contentResolver.notifyChange(R2HubContract.Files.CONTENT_URI, null)
            ctx.contentResolver.notifyChange(R2HubContract.Status.CONTENT_URI, null)
        }
        return count
    }

    /**
     * Removes the cached bytes immediately and marks the metadata row
     * DELETE_PENDING so R2SyncWorker propagates the deletion to R2. A client
     * rewriting the same path clears the pending state via markFileDirty.
     */
    private fun deleteForRemote(id: Long, namespace: String, relativePath: String) {
        val ctx = context ?: return
        vaultManager.deleteLocalFile(namespace, relativePath)
        runBlocking { repository.markFileDeletePending(id) }
        R2SyncWorkScheduler.scheduleImmediateDirtySync(ctx)
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        enforceSecurity(isWrite = true)
        val ctx = context ?: return 0
        if (values == null) return 0

        when (uriMatcher.match(uri)) {
            CODE_FILE_ID -> {
                val id = ContentUris.parseId(uri)
                val file = runBlocking { repository.getFileById(id) } ?: return 0
                val isDirty = values.getAsBoolean(R2HubContract.Files.COLUMN_IS_DIRTY) ?: true
                val sizeBytes = values.getAsLong(R2HubContract.Files.COLUMN_SIZE_BYTES) ?: file.localSizeBytes
                val hash = values.getAsString(R2HubContract.Files.COLUMN_HASH) ?: file.localHash
                val now = System.currentTimeMillis()

                runBlocking {
                    if (isDirty) {
                        repository.markFileDirty(id, now, sizeBytes, hash)
                    }
                }

                if (isDirty) {
                    R2SyncWorkScheduler.scheduleImmediateDirtySync(ctx)
                }

                ctx.contentResolver.notifyChange(R2HubContract.Files.CONTENT_URI, null)
                ctx.contentResolver.notifyChange(R2HubContract.Status.CONTENT_URI, null)
                return 1
            }

            else -> return 0
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val isWrite = mode.contains("w") || mode.contains("wt") || mode.contains("wa") || mode.contains("rw")
        enforceSecurity(isWrite = isWrite)
        val ctx = context ?: throw FileNotFoundException("Context unavailable")

        val (namespace, relativePath) = resolveNamespaceAndPath(uri)
            ?: throw FileNotFoundException("Invalid URI for openFile: $uri")

        val localFile = vaultManager.getLocalFile(namespace, relativePath)
        val modeFlag = parseFileMode(mode)

        if (isWrite) {
            if (!localFile.exists()) {
                localFile.parentFile?.mkdirs()
                localFile.createNewFile()
            }

            // Flag as dirty only AFTER the writer closes the descriptor.
            // Marking at open time raced the immediate sync worker into
            // uploading partially-written files, and tail writes were never
            // re-marked dirty (open was the only dirtying site).
            return ParcelFileDescriptor.open(
                localFile, modeFlag,
                android.os.Handler(android.os.Looper.getMainLooper())
            ) {
                providerScope.launch {
                    try {
                        val now = System.currentTimeMillis()
                        val clientPkg = callingPackage ?: ctx.packageName

                        val existing = repository.getFileByNamespaceAndPath(namespace, relativePath)
                        if (existing != null) {
                            repository.markFileDirty(
                                id = existing.id,
                                timestamp = now,
                                size = localFile.length(),
                                hash = vaultManager.computeFileSha256(localFile)
                            )
                        } else {
                            repository.saveFileMetadata(
                                FileMetadataEntity(
                                    folderId = 0L,
                                    relativePath = relativePath,
                                    namespace = namespace,
                                    clientPackage = clientPkg,
                                    mimeType = vaultManager.detectMimeType(localFile.name),
                                    localSizeBytes = localFile.length(),
                                    localLastModified = now,
                                    isDirty = true,
                                    dirtyTimestamp = now,
                                    state = "DIRTY",
                                    cachedLocalPath = localFile.absolutePath
                                )
                            )

                            // Ensure client is registered
                            if (repository.getClient(clientPkg) == null) {
                                repository.upsertClient(
                                    ClientAppEntity(
                                        packageId = clientPkg,
                                        appName = clientPkg.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                                        namespace = namespace
                                    )
                                )
                            }
                        }

                        R2SyncWorkScheduler.scheduleImmediateDirtySync(ctx)

                        ctx.contentResolver.notifyChange(R2HubContract.Files.CONTENT_URI, null)
                        ctx.contentResolver.notifyChange(R2HubContract.Status.CONTENT_URI, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to mark hub write dirty: $namespace/$relativePath", e)
                    }
                }
            }
        } else {
            if (!localFile.exists()) {
                throw FileNotFoundException("File not found in Hub vault: $namespace/$relativePath")
            }
        }

        return ParcelFileDescriptor.open(localFile, modeFlag)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        enforceSecurity(isWrite = method != R2HubContract.METHOD_GET_STATUS)
        val ctx = context ?: return null
        val response = Bundle()

        when (method) {
            R2HubContract.METHOD_TRIGGER_SYNC -> {
                val immediate = extras?.getBoolean(R2HubContract.EXTRA_FORCE_IMMEDIATE, true) ?: true
                R2SyncWorkScheduler.scheduleImmediateDirtySync(ctx)
                response.putBoolean("success", true)
                response.putString("message", "Dirty sync work enqueued via WorkManager")
            }

            R2HubContract.METHOD_GET_STATUS -> {
                val dirtyCount = runBlocking { repository.getDirtyFiles().size }
                val totalBytes = vaultManager.getVaultTotalSizeBytes()
                val totalFiles = vaultManager.getVaultTotalFileCount()
                response.putInt(R2HubContract.Status.COLUMN_DIRTY_COUNT, dirtyCount)
                response.putInt(R2HubContract.Status.COLUMN_TOTAL_FILES, totalFiles)
                response.putLong(R2HubContract.Status.COLUMN_TOTAL_BYTES, totalBytes)
                response.putString(R2HubContract.Status.COLUMN_STATUS, if (dirtyCount > 0) "DIRTY_SYNC_PENDING" else "IDLE")
            }

            R2HubContract.METHOD_REGISTER_CLIENT -> {
                val pkg = extras?.getString(R2HubContract.EXTRA_PACKAGE_NAME) ?: callingPackage ?: "unknown.client"
                val name = extras?.getString(R2HubContract.EXTRA_APP_NAME) ?: pkg.substringAfterLast('.')
                val ns = extras?.getString(R2HubContract.EXTRA_NAMESPACE) ?: "default"

                runBlocking {
                    repository.upsertClient(
                        ClientAppEntity(
                            packageId = pkg,
                            appName = name,
                            namespace = ns
                        )
                    )
                }
                response.putBoolean("success", true)
            }

            R2HubContract.METHOD_MARK_DIRTY -> {
                val ns = extras?.getString(R2HubContract.EXTRA_NAMESPACE) ?: "default"
                val path = extras?.getString(R2HubContract.EXTRA_RELATIVE_PATH) ?: ""
                if (path.isNotEmpty()) {
                    runBlocking {
                        val fileMeta = repository.getFileByNamespaceAndPath(ns, path)
                        if (fileMeta != null) {
                            val f = vaultManager.getLocalFile(ns, path)
                            repository.markFileDirty(
                                id = fileMeta.id,
                                timestamp = System.currentTimeMillis(),
                                size = f.length(),
                                hash = vaultManager.computeFileSha256(f)
                            )
                        }
                    }
                    R2SyncWorkScheduler.scheduleImmediateDirtySync(ctx)
                    response.putBoolean("success", true)
                }
            }
        }

        return response
    }

    private fun resolveNamespaceAndPath(uri: Uri): Pair<String, String>? {
        return when (uriMatcher.match(uri)) {
            CODE_FILE_PATH -> {
                val segments = uri.pathSegments
                if (segments.size >= 3) {
                    val ns = segments[1]
                    val path = segments.drop(2).joinToString("/")
                    Pair(ns, path)
                } else null
            }
            CODE_FILE_ID -> {
                val id = ContentUris.parseId(uri)
                val meta = runBlocking { repository.getFileById(id) }
                if (meta != null) Pair(meta.namespace, meta.relativePath) else null
            }
            else -> {
                val ns = uri.getQueryParameter("namespace") ?: "default"
                val path = uri.getQueryParameter("path")
                if (path != null) Pair(ns, path) else null
            }
        }
    }

    private fun enforceSecurity(isWrite: Boolean) {
        val ctx = context ?: return
        val caller = callingPackage

        // Allow internal calls from own process without throwing
        val callingUid = Binder.getCallingUid()
        val myUid = android.os.Process.myUid()
        if (callingUid == myUid || caller == ctx.packageName) {
            return
        }
        // A null callingPackage must NOT bypass the check — anonymous callers
        // get the same permission enforcement as everyone else.

        // For external companion apps, enforce custom signature-level permission
        val requiredPerm = if (isWrite) {
            R2HubContract.PERMISSION_WRITE_HUB
        } else {
            R2HubContract.PERMISSION_ACCESS_HUB
        }

        val check = ctx.checkCallingOrSelfPermission(requiredPerm)
        if (check != PackageManager.PERMISSION_GRANTED) {
            val message = "SecurityException: Package $caller lacks required signature permission: $requiredPerm"
            Log.e(TAG, message)
            throw SecurityException(message)
        }
    }

    private fun parseFileMode(mode: String): Int {
        return when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
    }
}
