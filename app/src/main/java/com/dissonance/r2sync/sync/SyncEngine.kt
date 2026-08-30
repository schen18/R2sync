package com.dissonance.r2sync.sync

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dissonance.r2sync.data.entity.ConflictEntity
import com.dissonance.r2sync.data.entity.FileMetadataEntity
import com.dissonance.r2sync.data.entity.SyncHistoryEntity
import com.dissonance.r2sync.data.entity.SyncedFolderEntity
import com.dissonance.r2sync.data.repository.SyncRepository
import com.dissonance.r2sync.energy.EnergyManager
import com.dissonance.r2sync.model.ActionType
import com.dissonance.r2sync.model.ConflictStrategy
import com.dissonance.r2sync.model.R2Config
import com.dissonance.r2sync.model.SyncDirection
import com.dissonance.r2sync.model.SyncEngineStatus
import com.dissonance.r2sync.model.SyncProgress
import com.dissonance.r2sync.r2.R2Client
import com.dissonance.r2sync.r2.R2Object
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalScannedFile(
    val relativePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val uri: Uri?,
    val file: File?
)

class SyncEngine(
    private val context: Context,
    private val repository: SyncRepository,
    private val energyManager: EnergyManager,
    private val scope: CoroutineScope
) {
    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    // Serializes all sync entry points: a manual sync firing while the
    // periodic pass is mid-run used to interleave uploads/downloads on the
    // same keys and corrupt the progress state.
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

    private var activeSyncJob: Job? = null

    fun syncAllFolders() {
        activeSyncJob?.cancel()
        activeSyncJob = scope.launch(Dispatchers.IO) {
            syncAllFoldersOnce(isManual = true)
        }
    }

    fun syncFolder(folderId: Long) {
        activeSyncJob?.cancel()
        activeSyncJob = scope.launch(Dispatchers.IO) {
            val folder = repository.getFolderById(folderId) ?: return@launch
            val r2Config = repository.r2ConfigFlow.first()
            val r2Client = R2Client(r2Config)
            syncSingleFolder(folder, r2Client, isManual = true)
        }
    }

    /**
     * One full pass over all auto-sync-enabled folders. Public so the
     * WorkManager FolderSyncWorker can drive real background syncing with an
     * application-scoped lifetime (the old in-ViewModel loop died with the
     * activity). Returns false when any folder had errors.
     */
    suspend fun syncAllFoldersOnce(isManual: Boolean): Boolean = syncMutex.withLock {
        val energyState = energyManager.energyState.value
        if (!isManual && !energyState.canSync) {
            _syncProgress.value = SyncProgress(
                status = SyncEngineStatus.PAUSED_ENERGY,
                errorMessage = energyState.restrictionReason ?: "Sync paused due to energy policy"
            )
            return true // skipped, not failed
        }

        val folders = repository.getAllFolders()
        if (folders.isEmpty()) {
            _syncProgress.value = SyncProgress(status = SyncEngineStatus.IDLE)
            return true
        }

        val r2Config = repository.r2ConfigFlow.first()
        val r2Client = R2Client(r2Config)

        _syncProgress.value = SyncProgress(status = SyncEngineStatus.SCANNING, totalFiles = 0)

        var anyError = false
        for (folder in folders) {
            if (!folder.autoSyncEnabled && !isManual) continue
            if (!syncSingleFolder(folder, r2Client, isManual)) {
                anyError = true
            }
        }

        _syncProgress.value = SyncProgress(
            status = SyncEngineStatus.SUCCESS,
            currentFolder = "All synced",
            currentFile = "Completed"
        )
        delay(2500)
        _syncProgress.value = SyncProgress(status = SyncEngineStatus.IDLE)
        return !anyError
    }

    private suspend fun syncSingleFolder(
        folder: SyncedFolderEntity,
        r2Client: R2Client,
        isManual: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val folderStartTime = System.currentTimeMillis()
        var folderHadError = false
        try {
            _syncProgress.value = SyncProgress(
                status = SyncEngineStatus.SCANNING,
                currentFolder = folder.displayName,
                currentFile = "Indexing local & remote files..."
            )

            val localFiles = scanLocalFiles(folder)
            val remotePrefix = folder.remotePrefix.trimStart('/')
            val remoteResult = r2Client.listObjects(remotePrefix)
            val remoteObjects = remoteResult.getOrDefault(emptyList())

            val remoteMap = mutableMapOf<String, R2Object>()
            for (obj in remoteObjects) {
                val rel = if (obj.key.startsWith(remotePrefix)) {
                    obj.key.removePrefix(remotePrefix).trimStart('/')
                } else {
                    obj.key
                }
                if (rel.isNotBlank()) {
                    remoteMap[rel] = obj
                }
            }

            val cachedMetadataList = repository.getFilesForFolder(folder.id)
            val cachedMap = cachedMetadataList.associateBy { it.relativePath }

            var processedCount = 0
            val totalItems = localFiles.size + remoteMap.size
            var folderBytes = 0L

            _syncProgress.value = SyncProgress(
                status = SyncEngineStatus.SYNCING,
                currentFolder = folder.displayName,
                filesProcessed = 0,
                totalFiles = totalItems
            )

            // 1. Process Local Files
            for (local in localFiles) {
                folderBytes += local.sizeBytes
                val cached = cachedMap[local.relativePath]
                val remote = remoteMap[local.relativePath]

                // Energy Optimization: Fast check if local timestamp & size are identical to cached
                val isLocalUnchanged = cached != null &&
                        cached.localSizeBytes == local.sizeBytes &&
                        cached.localLastModified == local.lastModified

                val localHash = if (isLocalUnchanged) {
                    cached!!.localHash
                } else {
                    computeLocalFileHash(local)
                }

                // Deduplication: nothing to do when the local file is untouched
                // since the last sync AND the remote object still matches what
                // we last synced (etag) or the local content (MD5 embedded in
                // the etag). Size equality is NOT evidence of equality —
                // same-size remote edits must never be skipped.
                if (remote != null && isLocalUnchanged &&
                    (remote.etag == cached!!.remoteEtag || remote.etag.contains(localHash))
                ) {
                    energyManager.recordEnergySaving(local.sizeBytes)
                    processedCount++
                    continue
                }

                // Remote changed while local is untouched: download it. This
                // is the ONLY remote->local path for files that exist on both
                // sides (DOWNLOAD_ONLY included) — previously such edits were
                // skipped forever (same size) or clobbered by a stale local
                // upload (different size).
                if (remote != null && isLocalUnchanged && cached != null && cached.remoteEtag != remote.etag) {
                    if (folder.syncDirection != SyncDirection.UPLOAD_ONLY) {
                        if (!downloadRemoteFile(folder, local.relativePath, remote, r2Client)) {
                            folderHadError = true
                        }
                        processedCount++
                        _syncProgress.value = _syncProgress.value.copy(
                            filesProcessed = processedCount,
                            currentFile = local.relativePath
                        )
                    }
                    continue
                }

                // Conflict Detection
                val conflictDetected = detectConflict(local, remote, cached, localHash)
                if (conflictDetected) {
                    handleConflict(folder, local, remote!!, localHash, r2Client)
                    processedCount++
                    continue
                }

                // Regular Sync Actions based on Direction
                when (folder.syncDirection) {
                    SyncDirection.TWO_WAY, SyncDirection.UPLOAD_ONLY -> {
                        // Only genuinely local changes are pushed — never a
                        // stale local copy over a changed remote (that case is
                        // handled above or by conflict detection).
                        if (remote == null || !isLocalUnchanged) {
                            if (!uploadLocalFile(folder, local, r2Client, localHash)) {
                                folderHadError = true
                            }
                        }
                    }
                    SyncDirection.DOWNLOAD_ONLY -> {
                        // In download only, local changes are not pushed
                    }
                }

                processedCount++
                _syncProgress.value = _syncProgress.value.copy(
                    filesProcessed = processedCount,
                    currentFile = local.relativePath,
                    bytesTransferred = folderBytes
                )
            }

            // 2. Process Remote-Only Files (Downloads in Two-Way or Download-Only)
            if (folder.syncDirection == SyncDirection.TWO_WAY || folder.syncDirection == SyncDirection.DOWNLOAD_ONLY) {
                val localMap = localFiles.associateBy { it.relativePath }
                for ((relPath, remoteObj) in remoteMap) {
                    if (!localMap.containsKey(relPath)) {
                        if (!downloadRemoteFile(folder, relPath, remoteObj, r2Client)) {
                            folderHadError = true
                        }
                        processedCount++
                        _syncProgress.value = _syncProgress.value.copy(
                            filesProcessed = processedCount,
                            currentFile = relPath
                        )
                    }
                }
            }

            // Update Folder stats
            repository.updateFolderStats(
                id = folder.id,
                timestamp = System.currentTimeMillis(),
                status = if (folderHadError) "COMPLETED WITH ERRORS" else "SUCCESS",
                fileCount = localFiles.size,
                totalSize = folderBytes
            )
            !folderHadError
        } catch (e: Exception) {
            repository.updateFolderStats(
                id = folder.id,
                timestamp = System.currentTimeMillis(),
                status = "ERROR: ${e.message}",
                fileCount = folder.fileCount,
                totalSize = folder.totalSizeBytes
            )
            repository.insertHistoryLog(
                SyncHistoryEntity(
                    folderId = folder.id,
                    folderName = folder.displayName,
                    relativePath = "",
                    action = ActionType.SKIPPED.name,
                    fileSizeBytes = 0L,
                    status = "FAILED",
                    details = "Sync failed: ${e.localizedMessage}",
                    durationMs = System.currentTimeMillis() - folderStartTime
                )
            )
            false
        }
    }

    private fun detectConflict(
        local: LocalScannedFile,
        remote: R2Object?,
        cached: FileMetadataEntity?,
        localHash: String
    ): Boolean {
        if (remote == null || cached == null) return false
        // If local changed since cache AND remote changed since cache
        val localChanged = cached.localLastModified != local.lastModified || cached.localSizeBytes != local.sizeBytes
        val remoteChanged = cached.remoteSizeBytes != remote.sizeBytes || (cached.remoteEtag.isNotBlank() && cached.remoteEtag != remote.etag)
        return localChanged && remoteChanged && cached.localHash != localHash
    }

    private suspend fun handleConflict(
        folder: SyncedFolderEntity,
        local: LocalScannedFile,
        remote: R2Object,
        localHash: String,
        r2Client: R2Client
    ) {
        val strategy = folder.conflictStrategy

        when (strategy) {
            ConflictStrategy.MANUAL_REVIEW -> {
                // Record pending conflict in database — one pending row per
                // file, otherwise every sync pass piles up duplicates.
                val alreadyPending = repository.getPendingConflictForFile(folder.id, local.relativePath)
                if (alreadyPending == null) {
                    repository.insertConflict(
                        ConflictEntity(
                            folderId = folder.id,
                            folderName = folder.displayName,
                            relativePath = local.relativePath,
                            localSizeBytes = local.sizeBytes,
                            localLastModified = local.lastModified,
                            localHash = localHash,
                            remoteSizeBytes = remote.sizeBytes,
                            remoteLastModified = remote.lastModified,
                            remoteEtag = remote.etag
                        )
                    )
                }
                repository.insertHistoryLog(
                    SyncHistoryEntity(
                        folderId = folder.id,
                        folderName = folder.displayName,
                        relativePath = local.relativePath,
                        action = ActionType.CONFLICT_RESOLVED.name,
                        fileSizeBytes = local.sizeBytes,
                        status = "CONFLICT",
                        details = "Version divergence detected. Queued for manual resolution.",
                        durationMs = 50L
                    )
                )
            }
            ConflictStrategy.KEEP_NEWEST -> {
                if (local.lastModified >= remote.lastModified) {
                    uploadLocalFile(folder, local, r2Client, localHash)
                } else {
                    downloadRemoteFile(folder, local.relativePath, remote, r2Client)
                }
            }
            ConflictStrategy.KEEP_LOCAL -> {
                uploadLocalFile(folder, local, r2Client, localHash)
            }
            ConflictStrategy.KEEP_REMOTE -> {
                downloadRemoteFile(folder, local.relativePath, remote, r2Client)
            }
            ConflictStrategy.KEEP_BOTH -> {
                val timeSuffix = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val extIdx = local.relativePath.lastIndexOf('.')
                val branchedRelPath = if (extIdx != -1) {
                    local.relativePath.substring(0, extIdx) + "_conflict_$timeSuffix" + local.relativePath.substring(extIdx)
                } else {
                    local.relativePath + "_conflict_$timeSuffix"
                }
                val r2Key = "${folder.remotePrefix.trimEnd('/')}/$branchedRelPath"
                val data = readLocalBytes(local)
                if (data == null) {
                    repository.insertHistoryLog(
                        SyncHistoryEntity(
                            folderId = folder.id,
                            folderName = folder.displayName,
                            relativePath = local.relativePath,
                            action = ActionType.CONFLICT_RESOLVED.name,
                            fileSizeBytes = local.sizeBytes,
                            status = "FAILED",
                            details = "Local file unreadable (permission lost?) — skipped conflict branch",
                            durationMs = 50L
                        )
                    )
                    return
                }
                r2Client.putObject(r2Key, data)
                // Convergence: record the acknowledged divergence (local keeps
                // its version, remote key keeps its version) so the next pass
                // doesn't re-detect the conflict and mint ANOTHER branched
                // copy on every sync cycle.
                repository.saveFileMetadata(
                    FileMetadataEntity(
                        folderId = folder.id,
                        relativePath = local.relativePath,
                        localSizeBytes = local.sizeBytes,
                        localLastModified = local.lastModified,
                        localHash = localHash,
                        remoteSizeBytes = remote.sizeBytes,
                        remoteLastModified = remote.lastModified,
                        remoteEtag = remote.etag,
                        state = "SYNCED"
                    )
                )
                repository.insertHistoryLog(
                    SyncHistoryEntity(
                        folderId = folder.id,
                        folderName = folder.displayName,
                        relativePath = branchedRelPath,
                        action = ActionType.CONFLICT_RESOLVED.name,
                        fileSizeBytes = local.sizeBytes,
                        status = "SUCCESS",
                        details = "Kept both: Saved versioned remote copy as $branchedRelPath",
                        durationMs = 120L
                    )
                )
            }
        }
    }

    suspend fun resolveConflictExplicitly(
        conflict: ConflictEntity,
        resolution: ConflictStrategy
    ) = withContext(Dispatchers.IO) {
        val folder = repository.getFolderById(conflict.folderId) ?: return@withContext
        val r2Config = repository.r2ConfigFlow.first()
        val r2Client = R2Client(r2Config)
        val r2Key = "${folder.remotePrefix.trimEnd('/')}/${conflict.relativePath}"

        // The conflict is marked resolved ONLY when the chosen transfer
        // actually completed — previously failures fell through to a SUCCESS
        // log and silently discarded the user's choice.
        val ok = when (resolution) {
            ConflictStrategy.KEEP_LOCAL -> resolveKeepLocal(folder, r2Client, conflict, r2Key)
            ConflictStrategy.KEEP_REMOTE -> resolveKeepRemote(folder, r2Client, conflict, r2Key)
            ConflictStrategy.KEEP_NEWEST ->
                if (conflict.localLastModified >= conflict.remoteLastModified) {
                    resolveKeepLocal(folder, r2Client, conflict, r2Key)
                } else {
                    resolveKeepRemote(folder, r2Client, conflict, r2Key)
                }
            ConflictStrategy.KEEP_BOTH -> resolveKeepBoth(folder, r2Client, conflict)
            else -> true
        }

        if (ok) {
            repository.markConflictResolved(conflict.id, resolution.name)
            repository.insertHistoryLog(
                SyncHistoryEntity(
                    folderId = folder.id,
                    folderName = folder.displayName,
                    relativePath = conflict.relativePath,
                    action = ActionType.CONFLICT_RESOLVED.name,
                    fileSizeBytes = conflict.localSizeBytes,
                    status = "SUCCESS",
                    details = "Manually resolved using ${resolution.label}",
                    durationMs = 80L
                )
            )
        } else {
            repository.insertHistoryLog(
                SyncHistoryEntity(
                    folderId = folder.id,
                    folderName = folder.displayName,
                    relativePath = conflict.relativePath,
                    action = ActionType.CONFLICT_RESOLVED.name,
                    fileSizeBytes = conflict.localSizeBytes,
                    status = "FAILED",
                    details = "Resolution using ${resolution.label} failed — conflict stays pending for retry",
                    durationMs = 80L
                )
            )
        }
    }

    private suspend fun resolveKeepLocal(
        folder: SyncedFolderEntity,
        r2Client: R2Client,
        conflict: ConflictEntity,
        r2Key: String
    ): Boolean {
        // statLocalFile resolves both SAF documents and plain paths, so the
        // local bytes are readable for either folder type.
        val stat = statLocalFile(folder, conflict.relativePath) ?: return false
        val data = readLocalBytes(stat) ?: return false
        val result = r2Client.putObject(r2Key, data)
        if (result.isFailure) return false
        repository.saveFileMetadata(
            FileMetadataEntity(
                folderId = folder.id,
                relativePath = conflict.relativePath,
                localSizeBytes = stat.sizeBytes,
                localLastModified = stat.lastModified,
                localHash = computeMd5(data),
                remoteSizeBytes = stat.sizeBytes,
                remoteLastModified = System.currentTimeMillis(),
                remoteEtag = result.getOrDefault(""),
                state = "SYNCED"
            )
        )
        return true
    }

    private suspend fun resolveKeepRemote(
        folder: SyncedFolderEntity,
        r2Client: R2Client,
        conflict: ConflictEntity,
        r2Key: String
    ): Boolean {
        val remoteBytes = r2Client.getObject(r2Key).getOrNull() ?: return false
        if (!saveLocalBytes(folder, conflict.relativePath, remoteBytes)) return false
        // Convergent metadata: real post-write mtime + real content hash, so
        // the next pass doesn't re-flag or re-upload.
        val stat = statLocalFile(folder, conflict.relativePath)
        repository.saveFileMetadata(
            FileMetadataEntity(
                folderId = folder.id,
                relativePath = conflict.relativePath,
                localSizeBytes = stat?.sizeBytes ?: remoteBytes.size.toLong(),
                localLastModified = stat?.lastModified ?: System.currentTimeMillis(),
                localHash = computeMd5(remoteBytes),
                remoteSizeBytes = conflict.remoteSizeBytes,
                remoteLastModified = conflict.remoteLastModified,
                remoteEtag = conflict.remoteEtag,
                state = "SYNCED"
            )
        )
        return true
    }

    private suspend fun resolveKeepBoth(
        folder: SyncedFolderEntity,
        r2Client: R2Client,
        conflict: ConflictEntity
    ): Boolean {
        val timeSuffix = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val extIdx = conflict.relativePath.lastIndexOf('.')
        val branchedRelPath = if (extIdx != -1) {
            conflict.relativePath.substring(0, extIdx) + "_conflict_$timeSuffix" + conflict.relativePath.substring(extIdx)
        } else {
            conflict.relativePath + "_conflict_$timeSuffix"
        }
        val branchedKey = "${folder.remotePrefix.trimEnd('/')}/$branchedRelPath"
        val stat = statLocalFile(folder, conflict.relativePath) ?: return false
        val data = readLocalBytes(stat) ?: return false
        if (r2Client.putObject(branchedKey, data).isFailure) return false
        // Convergence for the original path: acknowledge the divergence
        // instead of letting the next pass re-detect it.
        repository.saveFileMetadata(
            FileMetadataEntity(
                folderId = folder.id,
                relativePath = conflict.relativePath,
                localSizeBytes = stat.sizeBytes,
                localLastModified = stat.lastModified,
                localHash = computeMd5(data),
                remoteSizeBytes = conflict.remoteSizeBytes,
                remoteLastModified = conflict.remoteLastModified,
                remoteEtag = conflict.remoteEtag,
                state = "SYNCED"
            )
        )
        return true
    }

    private suspend fun uploadLocalFile(
        folder: SyncedFolderEntity,
        local: LocalScannedFile,
        r2Client: R2Client,
        localHash: String
    ): Boolean {
        val startTime = System.currentTimeMillis()
        val r2Key = "${folder.remotePrefix.trimEnd('/')}/${local.relativePath}"
        // Stream the upload — never hold the whole file in the heap. Plain
        // files go straight from disk; SAF documents stream from the
        // resolver with their known length.
        val result: Result<String> = try {
            when {
                local.file != null && local.file.exists() ->
                    r2Client.putObjectFromFile(r2Key, local.file)
                local.uri != null -> {
                    val stream = context.contentResolver.openInputStream(local.uri)
                        ?: throw java.io.FileNotFoundException("Local file unreadable (folder access revoked)")
                    r2Client.putObjectFromInputStream(r2Key, stream, local.sizeBytes)
                }
                else -> throw java.io.FileNotFoundException("Local file unreadable (folder access revoked or file missing)")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }

        if (result.isFailure) {
            // Never upload synthetic content — an unreadable local file means
            // the SAF grant was lost or the file vanished. Skip and surface it.
            repository.insertHistoryLog(
                SyncHistoryEntity(
                    folderId = folder.id,
                    folderName = folder.displayName,
                    relativePath = local.relativePath,
                    action = ActionType.UPLOAD.name,
                    fileSizeBytes = local.sizeBytes,
                    status = "FAILED",
                    details = "Upload error: ${result.exceptionOrNull()?.localizedMessage}",
                    durationMs = System.currentTimeMillis() - startTime
                )
            )
            return false
        }

        if (result.isSuccess) {
            val etag = result.getOrDefault("")
            repository.saveFileMetadata(
                FileMetadataEntity(
                    folderId = folder.id,
                    relativePath = local.relativePath,
                    localSizeBytes = local.sizeBytes,
                    localLastModified = local.lastModified,
                    localHash = localHash,
                    remoteSizeBytes = local.sizeBytes,
                    remoteLastModified = System.currentTimeMillis(),
                    remoteEtag = etag,
                    state = "SYNCED"
                )
            )
            repository.insertHistoryLog(
                SyncHistoryEntity(
                    folderId = folder.id,
                    folderName = folder.displayName,
                    relativePath = local.relativePath,
                    action = ActionType.UPLOAD.name,
                    fileSizeBytes = local.sizeBytes,
                    status = "SUCCESS",
                    details = "Uploaded to Cloudflare R2 ($r2Key)",
                    durationMs = System.currentTimeMillis() - startTime
                )
            )
            return true
        } else {
            repository.insertHistoryLog(
                SyncHistoryEntity(
                    folderId = folder.id,
                    folderName = folder.displayName,
                    relativePath = local.relativePath,
                    action = ActionType.UPLOAD.name,
                    fileSizeBytes = local.sizeBytes,
                    status = "FAILED",
                    details = "Upload error: ${result.exceptionOrNull()?.localizedMessage}",
                    durationMs = System.currentTimeMillis() - startTime
                )
            )
            return false
        }
    }

    private suspend fun downloadRemoteFile(
        folder: SyncedFolderEntity,
        relPath: String,
        remoteObj: R2Object,
        r2Client: R2Client
    ): Boolean {
        val startTime = System.currentTimeMillis()
        // Stream via a temp file: the remote object never sits whole in the
        // heap, and the MD5 is computed while copying into the target.
        val tmp = File(context.cacheDir, "r2sync_dl_${System.currentTimeMillis()}")
        try {
            val download = r2Client.getObjectToFile(remoteObj.key, tmp)
            if (download.isFailure) {
                repository.insertHistoryLog(
                    SyncHistoryEntity(
                        folderId = folder.id,
                        folderName = folder.displayName,
                        relativePath = relPath,
                        action = ActionType.DOWNLOAD.name,
                        fileSizeBytes = remoteObj.sizeBytes,
                        status = "FAILED",
                        details = "Download error: ${download.exceptionOrNull()?.localizedMessage}",
                        durationMs = System.currentTimeMillis() - startTime
                    )
                )
                return false
            }
            val hash = writeLocalFileFromSource(folder, relPath, tmp)
            if (hash == null) {
                repository.insertHistoryLog(
                    SyncHistoryEntity(
                        folderId = folder.id,
                        folderName = folder.displayName,
                        relativePath = relPath,
                        action = ActionType.DOWNLOAD.name,
                        fileSizeBytes = tmp.length(),
                        status = "FAILED",
                        details = "Local write failed (folder access revoked or storage full) — remote version kept",
                        durationMs = System.currentTimeMillis() - startTime
                    )
                )
                return false
            }
            // Re-stat the written file: the metadata must match what the next
            // scan sees (real mtime, real content hash) — otherwise every
            // downloaded file looks locally-changed on the next pass and gets
            // re-uploaded, rotating the etag and clobbering concurrent edits.
            val stat = statLocalFile(folder, relPath)
            repository.saveFileMetadata(
                FileMetadataEntity(
                    folderId = folder.id,
                    relativePath = relPath,
                    localSizeBytes = stat?.sizeBytes ?: tmp.length(),
                    localLastModified = stat?.lastModified ?: System.currentTimeMillis(),
                    localHash = hash,
                    remoteSizeBytes = remoteObj.sizeBytes,
                    remoteLastModified = remoteObj.lastModified,
                    remoteEtag = remoteObj.etag,
                    state = "SYNCED"
                )
            )
            repository.insertHistoryLog(
                SyncHistoryEntity(
                    folderId = folder.id,
                    folderName = folder.displayName,
                    relativePath = relPath,
                    action = ActionType.DOWNLOAD.name,
                    fileSizeBytes = tmp.length(),
                    status = "SUCCESS",
                    details = "Downloaded from Cloudflare R2",
                    durationMs = System.currentTimeMillis() - startTime
                )
            )
            return true
        } finally {
            tmp.delete()
        }
    }

    /**
     * Streams [source] into the folder target (SAF tree or plain path),
     * computing the MD5 on the fly. Returns null on write failure.
     */
    private fun writeLocalFileFromSource(folder: SyncedFolderEntity, relPath: String, source: File): String? {
        return try {
            val md = MessageDigest.getInstance("MD5")
            if (folder.localUri.startsWith("content://")) {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.localUri)) ?: return null
                val segments = relPath.split('/')
                var dir = root
                segments.dropLast(1).forEach { segment ->
                    dir = dir.findFile(segment)?.takeIf { it.isDirectory }
                        ?: dir.createDirectory(segment)
                        ?: return null
                }
                val target = dir.findFile(segments.last())
                    ?: dir.createFile("application/octet-stream", segments.last())
                    ?: return null
                val out = context.contentResolver.openOutputStream(target.uri, "wt") ?: return null
                out.use { outputStream ->
                    java.security.DigestInputStream(source.inputStream(), md).use { digestIn ->
                        digestIn.copyTo(outputStream)
                    }
                }
            } else {
                val targetFile = File(folder.localPath, relPath)
                targetFile.parentFile?.mkdirs()
                java.io.FileOutputStream(targetFile).use { out ->
                    java.security.DigestInputStream(source.inputStream(), md).use { digestIn ->
                        digestIn.copyTo(out)
                    }
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun scanLocalFiles(folder: SyncedFolderEntity): List<LocalScannedFile> {
        val results = mutableListOf<LocalScannedFile>()
        val extFilters = folder.filterExtensions
            .split(",")
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }

        // Attempt SAF DocumentFile traversal if content uri
        if (folder.localUri.startsWith("content://")) {
            try {
                val treeUri = Uri.parse(folder.localUri)
                val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                if (rootDoc != null && rootDoc.exists()) {
                    traverseDocumentFile(rootDoc, "", folder.excludeHidden, extFilters, results)
                }
            } catch (e: Exception) {
                // fallback
            }
        }

        // Fallback or augment with File system scan if path exists
        if (results.isEmpty() && folder.localPath.isNotBlank()) {
            val fileDir = File(folder.localPath)
            if (fileDir.exists() && fileDir.isDirectory) {
                traverseFileDir(fileDir, fileDir, folder.excludeHidden, extFilters, results)
            }
        }

        return results
    }

    private fun traverseDocumentFile(
        dir: DocumentFile,
        currentPath: String,
        excludeHidden: Boolean,
        extFilters: List<String>,
        results: MutableList<LocalScannedFile>
    ) {
        val files = dir.listFiles()
        for (doc in files) {
            val name = doc.name ?: continue
            if (excludeHidden && name.startsWith(".")) continue

            val rel = if (currentPath.isEmpty()) name else "$currentPath/$name"
            if (doc.isDirectory) {
                traverseDocumentFile(doc, rel, excludeHidden, extFilters, results)
            } else if (doc.isFile) {
                if (matchesExtensionFilter(name, extFilters)) {
                    results.add(
                        LocalScannedFile(
                            relativePath = rel,
                            sizeBytes = doc.length(),
                            lastModified = doc.lastModified(),
                            uri = doc.uri,
                            file = null
                        )
                    )
                }
            }
        }
    }

    private fun traverseFileDir(
        root: File,
        current: File,
        excludeHidden: Boolean,
        extFilters: List<String>,
        results: MutableList<LocalScannedFile>
    ) {
        val files = current.listFiles() ?: return
        for (f in files) {
            if (excludeHidden && f.name.startsWith(".")) continue
            if (f.isDirectory) {
                traverseFileDir(root, f, excludeHidden, extFilters, results)
            } else if (f.isFile) {
                if (matchesExtensionFilter(f.name, extFilters)) {
                    val rel = f.relativeTo(root).path.replace('\\', '/')
                    results.add(
                        LocalScannedFile(
                            relativePath = rel,
                            sizeBytes = f.length(),
                            lastModified = f.lastModified(),
                            uri = null,
                            file = f
                        )
                    )
                }
            }
        }
    }

    private fun matchesExtensionFilter(fileName: String, filters: List<String>): Boolean {
        if (filters.isEmpty()) return true
        val lower = fileName.lowercase(Locale.ROOT)
        return filters.any { filter ->
            val cleanExt = if (filter.startsWith(".")) filter else ".$filter"
            lower.endsWith(cleanExt)
        }
    }

    private fun computeLocalFileHash(local: LocalScannedFile): String {
        return try {
            // Streaming digest — readLocalBytes would materialize the whole
            // file (videos/photos) in the heap just to hash it.
            val md = MessageDigest.getInstance("MD5")
            val input = when {
                local.uri != null -> try {
                    context.contentResolver.openInputStream(local.uri)
                } catch (e: Exception) {
                    null
                }
                local.file != null && local.file.exists() -> local.file.inputStream()
                else -> null
            } ?: return "${local.sizeBytes}_${local.lastModified}"
            input.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "${local.sizeBytes}_${local.lastModified}"
        }
    }

    private fun computeMd5(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    /**
     * Re-stats a local file after a SAF/File write so stored metadata matches
     * what the next scan will see.
     */
    private fun statLocalFile(folder: SyncedFolderEntity, relPath: String): LocalScannedFile? {
        return try {
            if (folder.localUri.startsWith("content://")) {
                var dir = DocumentFile.fromTreeUri(context, Uri.parse(folder.localUri)) ?: return null
                val segments = relPath.split('/')
                segments.dropLast(1).forEach { segment ->
                    dir = dir.findFile(segment) ?: return null
                }
                val doc = dir.findFile(segments.last()) ?: return null
                LocalScannedFile(relPath, doc.length(), doc.lastModified(), doc.uri, null)
            } else {
                val f = File(folder.localPath, relPath)
                if (f.exists()) LocalScannedFile(relPath, f.length(), f.lastModified(), null, f) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns null when the file cannot be read (lost SAF grant, scoped
     * storage, deleted file). Callers must skip — synthetic content must
     * never be uploaded to R2.
     */
    private fun readLocalBytes(local: LocalScannedFile): ByteArray? {
        if (local.uri != null) {
            try {
                context.contentResolver.openInputStream(local.uri)?.use { stream ->
                    return stream.readBytes()
                }
            } catch (e: Exception) {
                // fall through to the plain file path
            }
        }
        if (local.file != null && local.file.exists()) {
            return try {
                local.file.readBytes()
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    /**
     * Writes downloaded bytes into the local folder. Returns false when the
     * write failed — callers must NOT record SYNCED metadata or SUCCESS
     * history in that case.
     */
    private fun saveLocalBytes(folder: SyncedFolderEntity, relPath: String, data: ByteArray): Boolean {
        return try {
            if (folder.localUri.startsWith("content://")) {
                // SAF-backed folder: write through the granted tree, the only
                // reliable path under scoped storage.
                val root = DocumentFile.fromTreeUri(context, Uri.parse(folder.localUri)) ?: return false
                val segments = relPath.split('/')
                var dir = root
                segments.dropLast(1).forEach { segment ->
                    dir = dir.findFile(segment)?.takeIf { it.isDirectory }
                        ?: dir.createDirectory(segment)
                        ?: return false
                }
                val fileName = segments.last()
                val target = dir.findFile(fileName)
                    ?: dir.createFile("application/octet-stream", fileName)
                    ?: return false
                val written = context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                    out.write(data)
                    out.flush()
                    true
                } ?: return false
                written
            } else {
                val targetFile = File(folder.localPath, relPath)
                targetFile.parentFile?.mkdirs()
                targetFile.writeBytes(data)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

}
