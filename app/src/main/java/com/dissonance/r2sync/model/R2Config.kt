package com.dissonance.r2sync.model

data class R2Config(
    val accountId: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucketName: String = "",
    val customEndpoint: String = "",
    val region: String = "auto"
) {
    val endpointUrl: String
        get() {
            return if (customEndpoint.isNotBlank()) {
                customEndpoint.trimEnd('/')
            } else if (accountId.isNotBlank()) {
                "https://${accountId.trim()}.r2.cloudflarestorage.com"
            } else {
                ""
            }
        }

    // SigV4 signing always requires credentials, endpoint or not — an
    // endpoint+bucket without keys just produces opaque 403s.
    val isConfigured: Boolean
        get() = (accountId.isNotBlank() || customEndpoint.isNotBlank()) &&
                accessKeyId.isNotBlank() &&
                secretAccessKey.isNotBlank() &&
                bucketName.isNotBlank()
}
