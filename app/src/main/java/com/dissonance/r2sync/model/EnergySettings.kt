package com.dissonance.r2sync.model

data class EnergySettings(
    val backgroundSyncEnabled: Boolean = true,
    val syncOnlyOnWifi: Boolean = true,
    val syncOnlyWhileCharging: Boolean = false,
    val pauseOnLowBattery: Boolean = true,
    val lowBatteryThreshold: Int = 20, // percentage
    val globalIntervalMinutes: Int = 15,
    val globalConflictStrategy: ConflictStrategy = ConflictStrategy.MANUAL_REVIEW
)

data class EnergyState(
    val isWifi: Boolean = true,
    val isCellular: Boolean = false,
    val isCharging: Boolean = true,
    val batteryPercent: Int = 85,
    val isPowerSaveMode: Boolean = false,
    val canSync: Boolean = true,
    val restrictionReason: String? = null,
    // Accumulators — start at zero; only real recorded savings count.
    val bytesSavedByDedup: Long = 0L,
    val syncsAvoided: Int = 0
)
