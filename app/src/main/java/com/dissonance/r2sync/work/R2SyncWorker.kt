package com.dissonance.r2sync.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dissonance.r2sync.data.database.AppDatabase
import com.dissonance.r2sync.data.entity.FileMetadataEntity
import com.dissonance.r2sync.data.entity.SyncHistoryEntity
import com.dissonance.r2sync.data.repository.SyncRepository
import com.dissonance.r2sync.provider.R2HubContract
import com.dissonance.r2sync.provider.vault.HubVaultManager
import com.dissonance.r2sync.r2.R2Client
import com.dissonance.r2sync.r2.R2Object
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class R2SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "R2SyncWorker"
        const val UNIQUE_DIRTY_SYNC_WORK = "r2_hub_dirty_sync_work"
        const val UNIQUE_PERIODIC_SYNC_WORK = "r2_hub_periodic_sync_work"
        const val HUB_KEY_PREFIX = "hub/"
        const val STATE_DELETE_PENDING = "DELETE_PENDING"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = SyncRepository(applicationContext, database)

        if (!repository.loadEnergySettings().backgroundSyncEnabled) {
            Log.d(TAG, "Background sync disabled — skipping hub sync run")
            return@withContext Result.success()
        }

        val vaultManager = HubVaultManager(applicationContext)
        val r2Config = repository.loadR2Config()
        val r2Client = R2Client(r2Config)

        Log.d(TAG, "R2SyncWorker starting hub sync run... Attempt: $runAttemptCount")

        // Phase 1: propagate client-requested deletions to R2.
        processPendingDeletes(r2Client, repository, vaultManager)

        // Phase 2: push locally dirty vault files upstream.
        val anyUploadFailed = uploadDirtyFiles(r2Client, repository, vaultManager)

        // Phase 3: pull remote hub changes into the vault so companion apps
        // (and the Cloudflare worker's edits) reach them on their next sync.
        downloadRemoteHubChanges(r2Client, repository, vaultManager)

        // Notify content observers
        try {
            applicationContext.contentResolver.notifyChange(R2HubContract.Files.CONTENT_URI, null)
            applicationContext.contentResolver.notifyChange(R2HubContract.Status.CONTENT_URI, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify ContentResolver", e)
        }

        Log.d(TAG, "R2SyncWorker finished. AnyUploadFailed: $anyUploadFailed")

        if (anyUploadFailed && runAttemptCount < 3) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private fun hubKey(namespace: String, relativePath: String): String =
        "$HUB_KEY_PREFIX${namespace.trim('/')}/${relativePath.trimStart('/')}"

    private suspend fun processPendingDeletes(
        r2Client: R2Client,
        repository: SyncRepository,
        vaultManager: HubVaultManager
    ) {
        val pending = try {
            repository.getDeletePendingFiles()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query delete-pending files", e)
            return
        }
        if (pending.isEmpty()) return

        for (fileMeta in pending) {
            val r2Key = hubKey(fileMeta.namespace, fileMeta.relativePath)
            val result = r2Client.deleteObject(r2Key)
            if (result.isSuccess) {
                // Vault file was already removed by the provider; row goes away
                // only after the remote object is gone (S3 DELETE of a missing
                // key still returns success, so this is idempotent).
                vaultManager.deleteLocalFile(fileMeta.namespace, fileMeta.relativePath)
                repository.deleteFileById(fileMeta.id)
                repository.insertHistoryLog(
                    SyncHistoryEntity(
                        timestamp = System.currentTimeMillis(),
                        folderId = fileMeta.folderId,
                        folderName = "Hub: ${fileMeta.namespace}",
                        relativePath = fileMeta.relativePath,
                        action = "DELETE",
                        status = "SUCCESS",
                        fileSizeBytes = 0L,
                        details = "Deleted R2 object $r2Key (client-requested)"
                    )
                )
                Log.d(TAG, "Propagated deletion to R2: $r2Key")
            } else {
                Log.e(TAG, "Failed to delete R2 object $r2Key: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private suspend fun uploadDirtyFiles(
        r2Client: R2Client,
        repository: SyncRepository,
        vaultManager: HubVaultManager
    ): Boolean {
        val dirtyFiles = try {
            repository.getDirtyFiles()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query dirty files", e)
            return true // treat as failure so the worker retries
        }

        if (dirtyFiles.isEmpty()) {
            Log.d(TAG, "No dirty files found.")
            return false
        }

        var anyFailed = false
        var uploadedCount = 0

        for (fileMeta in dirtyFiles) {
            try {
                val localFile = vaultManager.getLocalFile(fileMeta.namespace, fileMeta.relativePath)
                if (!localFile.exists() || !localFile.isFile) {
                    Log.w(TAG, "Local file missing for dirty entry: ${fileMeta.namespace}/${fileMeta.relativePath}")
                    // Clean up orphan entry or unmark dirty
                    continue
                }

                val fileSize = localFile.length()
                val r2Key = hubKey(fileMeta.namespace, fileMeta.relativePath)

                Log.d(TAG, "Uploading dirty file to R2: $r2Key ($fileSize bytes)")
                val uploadResult = r2Client.putObjectFromFile(
                    key = r2Key,
                    file = localFile,
                    contentType = fileMeta.mimeType.ifEmpty { vaultManager.detectMimeType(localFile.name) }
                )

                if (uploadResult.isSuccess) {
                    val etag = uploadResult.getOrNull() ?: "etag_${System.currentTimeMillis()}"
                    val now = System.currentTimeMillis()

                    // Mark clean in DB
                    repository.markFileClean(
                        id = fileMeta.id,
                        etag = etag,
                        remoteModified = now
                    )

                    // Log history
                    repository.insertHistoryLog(
                        SyncHistoryEntity(
                            timestamp = now,
                            folderId = fileMeta.folderId,
                            folderName = "Hub: ${fileMeta.namespace}",
                            relativePath = fileMeta.relativePath,
                            action = "UPLOAD",
                            status = "SUCCESS",
                            fileSizeBytes = fileSize,
                            details = "Uploaded to R2 from Hub provider (${fileMeta.clientPackage.ifEmpty { "companion app" }})"
                        )
                    )

                    // Update client app stats
                    val client = repository.getClient(fileMeta.clientPackage)
                    if (client != null) {
                        val nsFiles = repository.getFilesForNamespace(fileMeta.namespace)
                        val dirtyCount = nsFiles.count { it.isDirty }
                        val totalBytes = nsFiles.sumOf { it.localSizeBytes }
                        repository.updateClientStats(
                            packageId = client.packageId,
                            files = nsFiles.size,
                            bytes = totalBytes,
                            dirty = dirtyCount,
                            lastSync = now
                        )
                    }

                    uploadedCount++
                    Log.d(TAG, "Successfully synced dirty file: $r2Key")
                } else {
                    anyFailed = true
                    val err = uploadResult.exceptionOrNull()?.message ?: "Upload failed"
                    Log.e(TAG, "Failed to upload dirty file: $r2Key: $err")
                    repository.insertHistoryLog(
                        SyncHistoryEntity(
                            timestamp = System.currentTimeMillis(),
                            folderId = fileMeta.folderId,
                            folderName = "Hub: ${fileMeta.namespace}",
                            relativePath = fileMeta.relativePath,
                            action = "UPLOAD",
                            status = "ERROR",
                            fileSizeBytes = fileSize,
                            details = "R2 upload error: $err"
                        )
                    )
                }
            } catch (e: Exception) {
                anyFailed = true
                Log.e(TAG, "Exception syncing file: ${fileMeta.relativePath}", e)
            }
        }

        Log.d(TAG, "Upload phase done. Uploaded: $uploadedCount")
        return anyFailed
    }

    /**
     * Pulls remote hub objects into the local vault:
     *  - remote-only objects (never seen on this device) are downloaded and registered;
     *  - clean rows whose remote etag changed are refreshed;
     *  - dirty rows lose only when the remote revision is newer than the pending
     *    local write (file-level last-writer-wins; companion apps treat the vault
     *    as a cache and re-assert their state on their next sync if it differs);
     *  - clean rows whose remote object vanished are dropped from the cache.
     *
     * Rows in DELETE_PENDING are skipped (phase 1 owns them), and folder-sync
     * metadata (folderId != 0) is not touched.
     */
    private suspend fun downloadRemoteHubChanges(
        r2Client: R2Client,
        repository: SyncRepository,
        vaultManager: HubVaultManager
    ) {
        val hubRows = try {
            repository.getHubFiles()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query hub files", e)
            return
        }

        val listing = r2Client.listObjects(HUB_KEY_PREFIX)
        if (listing.isFailure) {
            Log.w(TAG, "Hub listing failed, skipping download phase: ${listing.exceptionOrNull()?.message}")
            return
        }

        val rowByNsPath = hubRows.associateBy { "${it.namespace}/${it.relativePath}" }
        val remoteByNsPath = mutableMapOf<String, R2Object>()
        for (obj in listing.getOrDefault(emptyList())) {
            if (!obj.key.startsWith(HUB_KEY_PREFIX)) continue
            val nsAndPath = obj.key.removePrefix(HUB_KEY_PREFIX)
            val slash = nsAndPath.indexOf('/')
            if (slash <= 0) continue
            val ns = nsAndPath.substring(0, slash)
            val rel = nsAndPath.substring(slash + 1)
            if (rel.isBlank()) continue
            remoteByNsPath["$ns/$rel"] = obj
        }

        var downloaded = 0
        for ((nsPath, obj) in remoteByNsPath) {
            val row = rowByNsPath[nsPath]
            if (row != null && row.state == STATE_DELETE_PENDING) continue

            // etags are compared unquoted: older rows may hold the quoted form.
            val cachedEtag = row?.remoteEtag?.trim('"').orEmpty()
            val shouldDownload = when {
                row == null -> true // remote-only object
                row.isDirty -> {
                    // Both sides changed since the last common state: keep the local
                    // pending write only when it is the newer revision.
                    cachedEtag.isNotBlank() && cachedEtag != obj.etag && row.dirtyTimestamp < obj.lastModified
                }
                else -> cachedEtag != obj.etag
            }

            if (shouldDownload) {
                if (downloadIntoVault(r2Client, repository, vaultManager, obj, row)) {
                    downloaded++
                }
            }
        }

        // Drop cached files whose remote object was deleted elsewhere (e.g. by the
        // worker). Dirty rows are left alone — their pending upload re-creates the
        // object intentionally.
        var dropped = 0
        for (row in hubRows) {
            if (row.isDirty || row.state == STATE_DELETE_PENDING) continue
            if (!remoteByNsPath.containsKey("${row.namespace}/${row.relativePath}")) {
                vaultManager.deleteLocalFile(row.namespace, row.relativePath)
                repository.deleteFileById(row.id)
                dropped++
            }
        }

        if (downloaded > 0 || dropped > 0) {
            Log.d(TAG, "Download phase: $downloaded pulled, $dropped dropped from cache")
        }
    }

    private suspend fun downloadIntoVault(
        r2Client: R2Client,
        repository: SyncRepository,
        vaultManager: HubVaultManager,
        obj: R2Object,
        existing: FileMetadataEntity?
    ): Boolean {
        val key = obj.key.removePrefix(HUB_KEY_PREFIX)
        val slash = key.indexOf('/')
        if (slash <= 0) return false
        val ns = key.substring(0, slash)
        val rel = key.substring(slash + 1)

        val target = vaultManager.getLocalFile(ns, rel)
        val result = r2Client.getObjectToFile(obj.key, target)
        val bytesWritten = result.getOrNull()
        if (bytesWritten == null) {
            Log.w(TAG, "Failed to download hub object ${obj.key}: ${result.exceptionOrNull()?.message}")
            return false
        }

        val file = target
        val entity = (existing ?: FileMetadataEntity(
            folderId = 0L,
            relativePath = rel,
            namespace = ns,
            clientPackage = "remote",
            mimeType = vaultManager.detectMimeType(rel)
        )).copy(
            localSizeBytes = bytesWritten,
            localLastModified = obj.lastModified,
            localHash = vaultManager.computeFileSha256(file),
            remoteSizeBytes = obj.sizeBytes,
            remoteLastModified = obj.lastModified,
            remoteEtag = obj.etag,
            state = "SYNCED",
            isDirty = false,
            dirtyTimestamp = 0L,
            cachedLocalPath = file.absolutePath,
            lastChecked = System.currentTimeMillis()
        )
        repository.saveFileMetadata(entity)
        repository.insertHistoryLog(
            SyncHistoryEntity(
                timestamp = System.currentTimeMillis(),
                folderId = 0L,
                folderName = "Hub: $ns",
                relativePath = rel,
                action = "DOWNLOAD",
                status = "SUCCESS",
                fileSizeBytes = bytesWritten,
                details = "Downloaded from R2 into hub vault"
            )
        )
        return true
    }
}
