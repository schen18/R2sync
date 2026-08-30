package com.dissonance.r2sync.model

enum class SyncDirection(val label: String, val description: String) {
    TWO_WAY("Two-Way Sync", "Synchronize changes both ways (bidirectional)"),
    UPLOAD_ONLY("Backup (Upload Only)", "Upload local changes to Cloudflare R2"),
    DOWNLOAD_ONLY("Mirror (Download Only)", "Download changes from Cloudflare R2")
}

enum class ConflictStrategy(val label: String, val description: String) {
    MANUAL_REVIEW("Ask User (Manual Review)", "Pause and let you review file differences"),
    KEEP_NEWEST("Keep Newest", "The file with the latest modification timestamp wins"),
    KEEP_LOCAL("Keep Local Always", "Local device file overwrites Cloudflare R2"),
    KEEP_REMOTE("Keep Remote Always", "Cloudflare R2 file overwrites local device"),
    KEEP_BOTH("Keep Both Versions", "Save both versions with timestamp suffixes")
}

enum class SyncEngineStatus {
    IDLE,
    SCANNING,
    SYNCING,
    PAUSED_ENERGY,
    PAUSED_MANUAL,
    ERROR,
    SUCCESS
}

enum class ActionType {
    UPLOAD,
    DOWNLOAD,
    DELETE,
    CONFLICT_RESOLVED,
    SKIPPED
}

enum class LogStatus {
    SUCCESS,
    FAILED,
    CONFLICT,
    WARNING
}

data class SyncProgress(
    val status: SyncEngineStatus = SyncEngineStatus.IDLE,
    val currentFolder: String = "",
    val currentFile: String = "",
    val filesProcessed: Int = 0,
    val totalFiles: Int = 0,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val transferSpeedKbps: Double = 0.0,
    val errorMessage: String? = null
)
