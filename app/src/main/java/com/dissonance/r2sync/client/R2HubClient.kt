package com.dissonance.r2sync.client

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dissonance.r2sync.provider.R2HubContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * REFERENCE CLIENT LIBRARY — not used by the R2sync helper itself.
 *
 * Companion apps that want to talk to the Hub provider should copy this file
 * and [R2HubModels] into their own codebase, along with the authority /
 * permission constants from [R2HubContract] (see this folder's README).
 * This copy is kept here so R2sync is a self-contained integration reference
 * that always matches the provider it ships with.
 *
 * Kotlin client library for companion apps interfacing with the centralized
 * Cloudflare R2 Sync Hub Provider (the R2sync helper app).
 *
 * Requirements:
 *  - The helper app must be installed and BOTH apps must be signed with the
 *    same certificate (the hub's custom permissions are signature-level).
 *  - The authority/permission constants in [R2HubContract] must match the
 *    helper's build exactly.
 *
 * Semantics (as implemented by the current helper):
 *  - Writes through [openOutputStream]/[writeFile] are cached locally and
 *    marked dirty for upload when the stream is CLOSED — always close what
 *    you open (the helpers here use `use`, which is sufficient).
 *  - [deleteFile] removes the cached copy and queues a remote deletion that
 *    the helper's worker propagates to R2.
 *  - Use [writeFileStreaming]/[readFileTo] for large payloads; the ByteArray
 *    variants buffer the whole file in memory (fine for small JSON docs).
 */
object R2HubClient {

    private const val TAG = "R2HubClient"

    /**
     * Registers the client application with the R2 Sync Hub.
     */
    suspend fun registerClient(
        context: Context,
        namespace: String,
        appName: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val finalAppName = appName ?: try {
                context.applicationInfo.loadLabel(context.packageManager).toString()
            } catch (e: Exception) {
                "GeoNotes"
            }

            val extras = Bundle().apply {
                putString(R2HubContract.EXTRA_PACKAGE_NAME, context.packageName)
                putString(R2HubContract.EXTRA_APP_NAME, finalAppName)
                putString(R2HubContract.EXTRA_NAMESPACE, namespace)
            }
            val result = context.contentResolver.call(
                R2HubContract.BASE_CONTENT_URI,
                R2HubContract.METHOD_REGISTER_CLIENT,
                null,
                extras
            )
            result?.getBoolean("success", false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register client with Hub", e)
            false
        }
    }

    /**
     * Writes binary data to the local cached file via ContentResolver.
     * The file is flagged dirty and an upload is scheduled when the stream
     * closes. Buffers the whole payload — use [writeFileStreaming] for large
     * files.
     */
    suspend fun writeFile(
        context: Context,
        namespace: String,
        relativePath: String,
        data: ByteArray,
        mimeType: String = "application/octet-stream"
    ): Uri = withContext(Dispatchers.IO) {
        val fileUri = R2HubContract.Files.buildFileUri(namespace, relativePath)

        // Open OutputStream via ContentResolver
        val outputStream: OutputStream = context.contentResolver.openOutputStream(fileUri, "wt")
            ?: throw IllegalStateException("Could not open OutputStream for $fileUri")

        outputStream.use { out ->
            out.write(data)
            out.flush()
        }

        Log.d(TAG, "Wrote ${data.size} bytes to Hub file: $namespace/$relativePath")
        fileUri
    }

    /**
     * Opens an OutputStream to write streaming data to the Hub vault.
     * The file is marked dirty and an upload is scheduled when the stream is
     * CLOSED — callers must close the stream or the write will not sync.
     */
    fun openOutputStream(
        context: Context,
        namespace: String,
        relativePath: String,
        mode: String = "wt"
    ): OutputStream {
        val fileUri = R2HubContract.Files.buildFileUri(namespace, relativePath)
        return context.contentResolver.openOutputStream(fileUri, mode)
            ?: throw IllegalStateException("Could not open OutputStream for $fileUri")
    }

    /**
     * Streams data from [input] into the hub vault without buffering the whole
     * payload in memory (large videos would otherwise OOM the sync worker —
     * an Error that no `catch (Exception)` survives). [input] is closed.
     */
    suspend fun writeFileStreaming(
        context: Context,
        namespace: String,
        relativePath: String,
        input: InputStream,
        mimeType: String = "application/octet-stream"
    ): Uri = withContext(Dispatchers.IO) {
        val fileUri = R2HubContract.Files.buildFileUri(namespace, relativePath)
        input.use { stream ->
            openOutputStream(context, namespace, relativePath, "wt").use { out ->
                stream.copyTo(out)
            }
        }
        Log.d(TAG, "Streamed upload to Hub file: $namespace/$relativePath")
        fileUri
    }

    /**
     * Streams a hub file directly into [target] without buffering it in
     * memory. Returns false when the file is not available in the vault.
     */
    suspend fun readFileTo(
        context: Context,
        namespace: String,
        relativePath: String,
        target: java.io.File
    ): Boolean = withContext(Dispatchers.IO) {
        val input = try {
            openInputStream(context, namespace, relativePath)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read file: $namespace/$relativePath", e)
            null
        } ?: return@withContext false
        input.use { stream ->
            target.parentFile?.mkdirs()
            java.io.FileOutputStream(target).use { out ->
                stream.copyTo(out)
            }
        }
        true
    }

    /**
     * Reads binary data from the local cached file in the Hub vault.
     * Buffers the whole file into memory — use [readFileTo] (or
     * [openInputStream]) for large payloads.
     */
    suspend fun readFile(
        context: Context,
        namespace: String,
        relativePath: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val fileUri = R2HubContract.Files.buildFileUri(namespace, relativePath)
            val inputStream: InputStream = context.contentResolver.openInputStream(fileUri)
                ?: return@withContext null
            inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read file: $namespace/$relativePath", e)
            null
        }
    }

    /**
     * Opens an InputStream to read streaming data from the Hub vault.
     */
    fun openInputStream(
        context: Context,
        namespace: String,
        relativePath: String
    ): InputStream? {
        val fileUri = R2HubContract.Files.buildFileUri(namespace, relativePath)
        return context.contentResolver.openInputStream(fileUri)
    }

    /**
     * Queries files stored under a specific namespace.
     */
    suspend fun listFiles(
        context: Context,
        namespace: String? = null,
        isDirtyOnly: Boolean = false
    ): List<R2HubFileEntry> = withContext(Dispatchers.IO) {
        val uriBuilder = R2HubContract.Files.CONTENT_URI.buildUpon()
        if (!namespace.isNullOrBlank()) {
            uriBuilder.appendQueryParameter("namespace", namespace)
        }
        if (isDirtyOnly) {
            uriBuilder.appendQueryParameter("is_dirty", "true")
        }

        val results = mutableListOf<R2HubFileEntry>()
        try {
            val cursor = context.contentResolver.query(
                uriBuilder.build(),
                R2HubContract.Files.ALL_COLUMNS,
                null,
                null,
                null
            )

            cursor?.use { c ->
                val idIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_ID)
                val nsIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_NAMESPACE)
                val pathIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_RELATIVE_PATH)
                val mimeIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_SIZE_BYTES)
                val modifiedIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_LAST_MODIFIED)
                val hashIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_HASH)
                val stateIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_STATE)
                val dirtyIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_IS_DIRTY)
                val dirtyTimeIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_DIRTY_TIMESTAMP)
                val clientIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_CLIENT_PACKAGE)
                val etagIdx = c.getColumnIndex(R2HubContract.Files.COLUMN_REMOTE_ETAG)

                while (c.moveToNext()) {
                    results.add(
                        R2HubFileEntry(
                            id = if (idIdx >= 0) c.getLong(idIdx) else 0L,
                            namespace = if (nsIdx >= 0) c.getString(nsIdx) ?: "default" else "default",
                            relativePath = if (pathIdx >= 0) c.getString(pathIdx) ?: "" else "",
                            mimeType = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "application/octet-stream" else "application/octet-stream",
                            sizeBytes = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L,
                            lastModified = if (modifiedIdx >= 0) c.getLong(modifiedIdx) else 0L,
                            hash = if (hashIdx >= 0) c.getString(hashIdx) ?: "" else "",
                            state = if (stateIdx >= 0) c.getString(stateIdx) ?: "SYNCED" else "SYNCED",
                            isDirty = if (dirtyIdx >= 0) c.getInt(dirtyIdx) == 1 else false,
                            dirtyTimestamp = if (dirtyTimeIdx >= 0) c.getLong(dirtyTimeIdx) else 0L,
                            clientPackage = if (clientIdx >= 0) c.getString(clientIdx) ?: "" else "",
                            remoteEtag = if (etagIdx >= 0) c.getString(etagIdx) ?: "" else ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query files from Hub", e)
        }
        results
    }

    /**
     * Deletes a file in the Hub vault and queues a remote deletion: the
     * helper's worker propagates it to R2 (DELETE_PENDING). Returns true when
     * the cached copy was removed.
     */
    suspend fun deleteFile(
        context: Context,
        namespace: String,
        relativePath: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val fileUri = R2HubContract.Files.buildFileUri(namespace, relativePath)
            val count = context.contentResolver.delete(fileUri, null, null)
            count > 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file: $namespace/$relativePath", e)
            false
        }
    }

    /**
     * Queries the synchronization status of the Hub.
     */
    suspend fun getSyncStatus(context: Context): R2HubSyncStatus = withContext(Dispatchers.IO) {
        var status = R2HubSyncStatus(
            status = "UNKNOWN",
            dirtyCount = 0,
            totalFiles = 0,
            totalBytes = 0L,
            isCharging = false,
            isWifi = false,
            batteryPct = 100,
            lastSyncTime = 0L,
            bucketName = ""
        )

        try {
            val cursor = context.contentResolver.query(
                R2HubContract.Status.CONTENT_URI,
                null,
                null,
                null,
                null
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val statusIdx = c.getColumnIndex(R2HubContract.Status.COLUMN_STATUS)
                    val dirtyIdx = c.getColumnIndex(R2HubContract.Status.COLUMN_DIRTY_COUNT)
                    val totalIdx = c.getColumnIndex(R2HubContract.Status.COLUMN_TOTAL_FILES)
                    val bytesIdx = c.getColumnIndex(R2HubContract.Status.COLUMN_TOTAL_BYTES)
                    val chargingIdx = c.getColumnIndex(R2HubContract.Status.COLUMN_IS_CHARGING)
                    val wifiIdx = c.getColumnIndex(R2HubContract.Status.COLUMN_IS_WIFI)
                    val battIdx = c.getColumnIndex(R2HubContract.Status.COLUMN_BATTERY_PCT)
                    val lastSyncIdx = c.getColumnIndex(R2HubContract.Status.COLUMN_LAST_SYNC_TIME)
                    val bucketIdx = c.getColumnIndex(R2HubContract.Status.COLUMN_BUCKET_NAME)

                    status = R2HubSyncStatus(
                        status = if (statusIdx >= 0) c.getString(statusIdx) ?: "IDLE" else "IDLE",
                        dirtyCount = if (dirtyIdx >= 0) c.getInt(dirtyIdx) else 0,
                        totalFiles = if (totalIdx >= 0) c.getInt(totalIdx) else 0,
                        totalBytes = if (bytesIdx >= 0) c.getLong(bytesIdx) else 0L,
                        isCharging = if (chargingIdx >= 0) c.getInt(chargingIdx) == 1 else false,
                        isWifi = if (wifiIdx >= 0) c.getInt(wifiIdx) == 1 else false,
                        batteryPct = if (battIdx >= 0) c.getInt(battIdx) else 100,
                        lastSyncTime = if (lastSyncIdx >= 0) c.getLong(lastSyncIdx) else 0L,
                        bucketName = if (bucketIdx >= 0) c.getString(bucketIdx) ?: "" else ""
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query Hub status", e)
        }
        status
    }

    /**
     * Explicitly triggers an immediate background upload of dirty files via WorkManager.
     */
    suspend fun triggerSync(context: Context, immediate: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            val extras = Bundle().apply {
                putBoolean(R2HubContract.EXTRA_FORCE_IMMEDIATE, immediate)
            }
            val result = context.contentResolver.call(
                R2HubContract.BASE_CONTENT_URI,
                R2HubContract.METHOD_TRIGGER_SYNC,
                null,
                extras
            )
            result?.getBoolean("success", false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger sync via Hub", e)
            false
        }
    }

    /**
     * Observes file updates for a namespace as a Kotlin Flow. Every provider
     * change triggers a fresh query, so each emission reflects real state.
     */
    fun observeFiles(context: Context, namespace: String? = null): Flow<List<R2HubFileEntry>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                launch { trySend(listFiles(context, namespace)) }
            }
        }

        try {
            context.contentResolver.registerContentObserver(
                R2HubContract.Files.CONTENT_URI,
                true,
                observer
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register content observer for files", e)
        }

        // Initial emission
        val initialFiles = listFiles(context, namespace)
        trySend(initialFiles)

        awaitClose {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Observes Hub sync status updates as a Kotlin Flow. Every provider
     * change triggers a fresh query, so each emission reflects real state.
     */
    fun observeSyncStatus(context: Context): Flow<R2HubSyncStatus> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                launch { trySend(getSyncStatus(context)) }
            }
        }

        try {
            context.contentResolver.registerContentObserver(
                R2HubContract.Status.CONTENT_URI,
                true,
                observer
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register content observer for status", e)
        }

        val initialStatus = getSyncStatus(context)
        trySend(initialStatus)

        awaitClose {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }.flowOn(Dispatchers.IO)
}
