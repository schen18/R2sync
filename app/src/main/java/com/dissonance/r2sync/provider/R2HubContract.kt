package com.dissonance.r2sync.provider

import android.net.Uri
import android.provider.BaseColumns

object R2HubContract {
    const val AUTHORITY = "com.dissonance.r2sync.provider.r2hub"
    val BASE_CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY")

    const val PERMISSION_ACCESS_HUB = "com.dissonance.r2sync.permission.ACCESS_R2_HUB"
    const val PERMISSION_WRITE_HUB = "com.dissonance.r2sync.permission.WRITE_R2_HUB"

    const val PATH_FILES = "files"
    const val PATH_FILE = "file"
    const val PATH_SYNC = "sync"
    const val PATH_STATUS = "status"
    const val PATH_CLIENTS = "clients"

    // Call Methods
    const val METHOD_TRIGGER_SYNC = "trigger_sync"
    const val METHOD_GET_STATUS = "get_status"
    const val METHOD_REGISTER_CLIENT = "register_client"
    const val METHOD_MARK_DIRTY = "mark_dirty"

    // Call Argument Keys
    const val EXTRA_NAMESPACE = "extra_namespace"
    const val EXTRA_RELATIVE_PATH = "extra_relative_path"
    const val EXTRA_PACKAGE_NAME = "extra_package_name"
    const val EXTRA_APP_NAME = "extra_app_name"
    const val EXTRA_FORCE_IMMEDIATE = "extra_force_immediate"

    object Files : BaseColumns {
        val CONTENT_URI: Uri = BASE_CONTENT_URI.buildUpon().appendPath(PATH_FILES).build()
        const val CONTENT_TYPE = "vnd.android.cursor.dir/vnd.com.dissonance.r2sync.r2hub.file"
        const val CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.com.dissonance.r2sync.r2hub.file"

        const val COLUMN_ID = "_id"
        const val COLUMN_NAMESPACE = "namespace"
        const val COLUMN_RELATIVE_PATH = "relative_path"
        const val COLUMN_MIME_TYPE = "mime_type"
        const val COLUMN_SIZE_BYTES = "size_bytes"
        const val COLUMN_LAST_MODIFIED = "last_modified"
        const val COLUMN_HASH = "hash"
        const val COLUMN_STATE = "state"
        const val COLUMN_IS_DIRTY = "is_dirty"
        const val COLUMN_DIRTY_TIMESTAMP = "dirty_timestamp"
        const val COLUMN_CLIENT_PACKAGE = "client_package"
        const val COLUMN_REMOTE_ETAG = "remote_etag"

        val ALL_COLUMNS = arrayOf(
            COLUMN_ID,
            COLUMN_NAMESPACE,
            COLUMN_RELATIVE_PATH,
            COLUMN_MIME_TYPE,
            COLUMN_SIZE_BYTES,
            COLUMN_LAST_MODIFIED,
            COLUMN_HASH,
            COLUMN_STATE,
            COLUMN_IS_DIRTY,
            COLUMN_DIRTY_TIMESTAMP,
            COLUMN_CLIENT_PACKAGE,
            COLUMN_REMOTE_ETAG
        )

        fun buildFileUri(namespace: String, relativePath: String): Uri {
            return BASE_CONTENT_URI.buildUpon()
                .appendPath(PATH_FILE)
                .appendPath(namespace)
                .appendEncodedPath(relativePath.trimStart('/'))
                .build()
        }

        fun buildFileIdUri(id: Long): Uri {
            return CONTENT_URI.buildUpon().appendPath(id.toString()).build()
        }
    }

    object Status {
        val CONTENT_URI: Uri = BASE_CONTENT_URI.buildUpon().appendPath(PATH_STATUS).build()
        const val COLUMN_STATUS = "engine_status"
        const val COLUMN_DIRTY_COUNT = "dirty_count"
        const val COLUMN_TOTAL_FILES = "total_files"
        const val COLUMN_TOTAL_BYTES = "total_bytes"
        const val COLUMN_IS_CHARGING = "is_charging"
        const val COLUMN_IS_WIFI = "is_wifi"
        const val COLUMN_BATTERY_PCT = "battery_pct"
        const val COLUMN_LAST_SYNC_TIME = "last_sync_time"
        const val COLUMN_BUCKET_NAME = "bucket_name"
    }

    object Clients : BaseColumns {
        val CONTENT_URI: Uri = BASE_CONTENT_URI.buildUpon().appendPath(PATH_CLIENTS).build()
        const val COLUMN_PACKAGE_ID = "package_id"
        const val COLUMN_APP_NAME = "app_name"
        const val COLUMN_NAMESPACE = "namespace"
        const val COLUMN_TOTAL_FILES = "total_files"
        const val COLUMN_TOTAL_BYTES = "total_bytes"
        const val COLUMN_DIRTY_COUNT = "dirty_count"
        const val COLUMN_LAST_SYNC = "last_sync"
        const val COLUMN_IS_AUTHORIZED = "is_authorized"
    }
}
