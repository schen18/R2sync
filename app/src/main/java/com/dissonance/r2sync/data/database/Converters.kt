package com.dissonance.r2sync.data.database

import androidx.room.TypeConverter
import com.dissonance.r2sync.model.ConflictStrategy
import com.dissonance.r2sync.model.SyncDirection

class Converters {
    @TypeConverter
    fun fromSyncDirection(value: SyncDirection?): String {
        return value?.name ?: SyncDirection.TWO_WAY.name
    }

    @TypeConverter
    fun toSyncDirection(value: String?): SyncDirection {
        return try {
            if (value != null) SyncDirection.valueOf(value) else SyncDirection.TWO_WAY
        } catch (e: Exception) {
            SyncDirection.TWO_WAY
        }
    }

    @TypeConverter
    fun fromConflictStrategy(value: ConflictStrategy?): String {
        return value?.name ?: ConflictStrategy.MANUAL_REVIEW.name
    }

    @TypeConverter
    fun toConflictStrategy(value: String?): ConflictStrategy {
        return try {
            if (value != null) ConflictStrategy.valueOf(value) else ConflictStrategy.MANUAL_REVIEW
        } catch (e: Exception) {
            ConflictStrategy.MANUAL_REVIEW
        }
    }
}
