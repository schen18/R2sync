package com.dissonance.r2sync.r2

import com.dissonance.r2sync.model.R2Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class R2Object(
    val key: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val etag: String
)

data class R2ObjectMetadata(
    val key: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val etag: String,
    val contentType: String
)

class R2Client(private val config: R2Config) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun testConnection(): Result<Pair<Boolean, Long>> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalStateException("R2 not configured — enter credentials in Settings"))
        }

        try {
            val endpoint = config.endpointUrl
            val bucket = config.bucketName
            val dateStr = getIso8601Date()
            val dateStamp = getDateStamp()
            val host = java.net.URI(endpoint).host ?: "$bucket.r2.cloudflarestorage.com"
            val path = "/$bucket"
            val query = "list-type=2&max-keys=1"

            val headers = mutableMapOf(
                "Host" to host,
                "x-amz-date" to dateStr,
                "x-amz-content-sha256" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" // empty payload sha
            )

            val authHeader = calculateSigV4(
                method = "GET",
                path = path,
                queryParams = query,
                headers = headers,
                payloadSha256 = headers["x-amz-content-sha256"]!!,
                dateStamp = dateStamp,
                dateStr = dateStr
            )

            val request = Request.Builder()
                .url("$endpoint/$bucket?$query")
                .header("Host", host)
                .header("x-amz-date", dateStr)
                .header("x-amz-content-sha256", headers["x-amz-content-sha256"]!!)
                .header("Authorization", authHeader)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    Result.success(Pair(true, latency))
                } else {
                    val errBody = response.body?.string()?.take(300) ?: ""
                    val cleanBody = errBody.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
                    Result.failure(Exception("HTTP ${response.code} ${response.message}${if (cleanBody.isNotBlank()) ": $cleanBody" else ""}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listObjects(prefix: String = ""): Result<List<R2Object>> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalStateException("R2 not configured — enter credentials in Settings"))
        }

        try {
            val endpoint = config.endpointUrl
            val bucket = config.bucketName
            val allObjects = mutableListOf<R2Object>()
            var continuationToken: String? = null

            do {
                // SigV4 requires the signed query string to match the request exactly,
                // with parameters in canonical (sorted) order: continuation-token < list-type < prefix.
                val query = buildString {
                    if (continuationToken != null) {
                        append("continuation-token=").append(URLEncoder.encode(continuationToken, "UTF-8")).append("&")
                    }
                    append("list-type=2")
                    if (prefix.isNotBlank()) {
                        append("&prefix=").append(URLEncoder.encode(prefix, "UTF-8"))
                    }
                }

                val dateStr = getIso8601Date()
                val dateStamp = getDateStamp()
                val host = java.net.URI(endpoint).host ?: "$bucket.r2.cloudflarestorage.com"
                val path = "/$bucket"

                val headers = mutableMapOf(
                    "Host" to host,
                    "x-amz-date" to dateStr,
                    "x-amz-content-sha256" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                )

                val authHeader = calculateSigV4(
                    method = "GET",
                    path = path,
                    queryParams = query,
                    headers = headers,
                    payloadSha256 = headers["x-amz-content-sha256"]!!,
                    dateStamp = dateStamp,
                    dateStr = dateStr
                )

                val request = Request.Builder()
                    .url("$endpoint/$bucket?$query")
                    .header("Host", host)
                    .header("x-amz-date", dateStr)
                    .header("x-amz-content-sha256", headers["x-amz-content-sha256"]!!)
                    .header("Authorization", authHeader)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("List failed (${response.code}): ${response.message}"))
                    }

                    val body = response.body?.string() ?: ""
                    val (pageObjects, nextToken) = parseS3ListObjectsXml(body)
                    allObjects.addAll(pageObjects)
                    continuationToken = nextToken
                }
            } while (continuationToken != null)

            Result.success(allObjects)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun putObject(key: String, data: ByteArray, contentType: String = "application/octet-stream"): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalStateException("R2 not configured — enter credentials in Settings"))
        }

        try {
            val endpoint = config.endpointUrl
            val bucket = config.bucketName
            val dateStr = getIso8601Date()
            val dateStamp = getDateStamp()
            val host = java.net.URI(endpoint).host ?: "$bucket.r2.cloudflarestorage.com"
            val encodedKey = encodePath(key)
            val path = "/$bucket/$encodedKey"
            val payloadSha256 = sha256Hex(data)

            val headers = mutableMapOf(
                "Content-Type" to contentType,
                "Host" to host,
                "x-amz-date" to dateStr,
                "x-amz-content-sha256" to payloadSha256
            )

            val authHeader = calculateSigV4(
                method = "PUT",
                path = path,
                queryParams = "",
                headers = headers,
                payloadSha256 = payloadSha256,
                dateStamp = dateStamp,
                dateStr = dateStr
            )

            val requestBody = data.toRequestBody(contentType.toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$endpoint/$bucket/$encodedKey")
                .header("Content-Type", contentType)
                .header("Host", host)
                .header("x-amz-date", dateStr)
                .header("x-amz-content-sha256", payloadSha256)
                .header("Authorization", authHeader)
                .put(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    // Store unquoted to match the etag form returned by listObjects.
                    val etag = (response.header("ETag") ?: "\"${data.size}\"").trim('"')
                    Result.success(etag)
                } else {
                    Result.failure(Exception("Upload failed (${response.code}): ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams a file into R2 without buffering it in memory (large media
     * would otherwise OOM the helper). Uses UNSIGNED-PAYLOAD SigV4 signing,
     * which R2 accepts over HTTPS.
     */
    suspend fun putObjectFromFile(
        key: String,
        file: java.io.File,
        contentType: String = "application/octet-stream"
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalStateException("R2 not configured — enter credentials in Settings"))
        }

        try {
            val endpoint = config.endpointUrl
            val bucket = config.bucketName
            val dateStr = getIso8601Date()
            val dateStamp = getDateStamp()
            val host = java.net.URI(endpoint).host ?: "$bucket.r2.cloudflarestorage.com"
            val encodedKey = encodePath(key)
            val path = "/$bucket/$encodedKey"
            val payloadSha256 = "UNSIGNED-PAYLOAD"

            val headers = mutableMapOf(
                "Content-Type" to contentType,
                "Host" to host,
                "x-amz-date" to dateStr,
                "x-amz-content-sha256" to payloadSha256
            )

            val authHeader = calculateSigV4(
                method = "PUT",
                path = path,
                queryParams = "",
                headers = headers,
                payloadSha256 = payloadSha256,
                dateStamp = dateStamp,
                dateStr = dateStr
            )

            val requestBody = file.asRequestBody(contentType.toMediaTypeOrNull())
            val request = Request.Builder()
                .url("$endpoint/$bucket/$encodedKey")
                .header("Content-Type", contentType)
                .header("Host", host)
                .header("x-amz-date", dateStr)
                .header("x-amz-content-sha256", payloadSha256)
                .header("Authorization", authHeader)
                .put(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success((response.header("ETag") ?: "\"${file.length()}\"").trim('"'))
                } else {
                    Result.failure(Exception("Upload failed (${response.code}): ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams arbitrary content (e.g. a ContentResolver stream for a SAF
     * document) into R2 without buffering it in memory. Requires a known
     * content length (OkHttp needs it up front).
     */
    suspend fun putObjectFromInputStream(
        key: String,
        input: InputStream,
        contentLength: Long,
        contentType: String = "application/octet-stream"
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalStateException("R2 not configured — enter credentials in Settings"))
        }

        try {
            val endpoint = config.endpointUrl
            val bucket = config.bucketName
            val dateStr = getIso8601Date()
            val dateStamp = getDateStamp()
            val host = java.net.URI(endpoint).host ?: "$bucket.r2.cloudflarestorage.com"
            val encodedKey = encodePath(key)
            val path = "/$bucket/$encodedKey"
            val payloadSha256 = "UNSIGNED-PAYLOAD"

            val headers = mutableMapOf(
                "Content-Type" to contentType,
                "Host" to host,
                "x-amz-date" to dateStr,
                "x-amz-content-sha256" to payloadSha256
            )

            val authHeader = calculateSigV4(
                method = "PUT",
                path = path,
                queryParams = "",
                headers = headers,
                payloadSha256 = payloadSha256,
                dateStamp = dateStamp,
                dateStr = dateStr
            )

            val mediaType = contentType.toMediaTypeOrNull()
            val requestBody = object : RequestBody() {
                override fun contentType() = mediaType
                override fun contentLength() = contentLength
                override fun writeTo(sink: okio.BufferedSink) {
                    input.use { src -> src.copyTo(sink.outputStream()) }
                }
            }

            val request = Request.Builder()
                .url("$endpoint/$bucket/$encodedKey")
                .header("Content-Type", contentType)
                .header("Host", host)
                .header("x-amz-date", dateStr)
                .header("x-amz-content-sha256", payloadSha256)
                .header("Authorization", authHeader)
                .put(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success((response.header("ETag") ?: "\"$contentLength\"").trim('"'))
                } else {
                    Result.failure(Exception("Upload failed (${response.code}): ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams an R2 object directly into [target] without buffering it in
     * memory. Returns the written byte count.
     */
    suspend fun getObjectToFile(key: String, target: java.io.File): Result<Long> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalStateException("R2 not configured — enter credentials in Settings"))
        }

        try {
            val endpoint = config.endpointUrl
            val bucket = config.bucketName
            val dateStr = getIso8601Date()
            val dateStamp = getDateStamp()
            val host = java.net.URI(endpoint).host ?: "$bucket.r2.cloudflarestorage.com"
            val encodedKey = encodePath(key)
            val path = "/$bucket/$encodedKey"
            val payloadSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

            val headers = mutableMapOf(
                "Host" to host,
                "x-amz-date" to dateStr,
                "x-amz-content-sha256" to payloadSha256
            )

            val authHeader = calculateSigV4(
                method = "GET",
                path = path,
                queryParams = "",
                headers = headers,
                payloadSha256 = payloadSha256,
                dateStamp = dateStamp,
                dateStr = dateStr
            )

            val request = Request.Builder()
                .url("$endpoint/$bucket/$encodedKey")
                .header("Host", host)
                .header("x-amz-date", dateStr)
                .header("x-amz-content-sha256", payloadSha256)
                .header("Authorization", authHeader)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    target.parentFile?.mkdirs()
                    response.body!!.byteStream().use { input ->
                        java.io.FileOutputStream(target).use { out ->
                            input.copyTo(out)
                        }
                    }
                    Result.success(target.length())
                } else {
                    Result.failure(Exception("Download failed (${response.code}): ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getObject(key: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalStateException("R2 not configured — enter credentials in Settings"))
        }

        try {
            val endpoint = config.endpointUrl
            val bucket = config.bucketName
            val dateStr = getIso8601Date()
            val dateStamp = getDateStamp()
            val host = java.net.URI(endpoint).host ?: "$bucket.r2.cloudflarestorage.com"
            val encodedKey = encodePath(key)
            val path = "/$bucket/$encodedKey"
            val payloadSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

            val headers = mutableMapOf(
                "Host" to host,
                "x-amz-date" to dateStr,
                "x-amz-content-sha256" to payloadSha256
            )

            val authHeader = calculateSigV4(
                method = "GET",
                path = path,
                queryParams = "",
                headers = headers,
                payloadSha256 = payloadSha256,
                dateStamp = dateStamp,
                dateStr = dateStr
            )

            val request = Request.Builder()
                .url("$endpoint/$bucket/$encodedKey")
                .header("Host", host)
                .header("x-amz-date", dateStr)
                .header("x-amz-content-sha256", payloadSha256)
                .header("Authorization", authHeader)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    Result.success(response.body!!.bytes())
                } else {
                    Result.failure(Exception("Download failed (${response.code}): ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteObject(key: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalStateException("R2 not configured — enter credentials in Settings"))
        }

        try {
            val endpoint = config.endpointUrl
            val bucket = config.bucketName
            val dateStr = getIso8601Date()
            val dateStamp = getDateStamp()
            val host = java.net.URI(endpoint).host ?: "$bucket.r2.cloudflarestorage.com"
            val encodedKey = encodePath(key)
            val path = "/$bucket/$encodedKey"
            val payloadSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

            val headers = mutableMapOf(
                "Host" to host,
                "x-amz-date" to dateStr,
                "x-amz-content-sha256" to payloadSha256
            )

            val authHeader = calculateSigV4(
                method = "DELETE",
                path = path,
                queryParams = "",
                headers = headers,
                payloadSha256 = payloadSha256,
                dateStamp = dateStamp,
                dateStr = dateStr
            )

            val request = Request.Builder()
                .url("$endpoint/$bucket/$encodedKey")
                .header("Host", host)
                .header("x-amz-date", dateStr)
                .header("x-amz-content-sha256", payloadSha256)
                .header("Authorization", authHeader)
                .delete()
                .build()

            httpClient.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful || response.code == 204)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- AWS SigV4 Signer Helpers ---
    private fun calculateSigV4(
        method: String,
        path: String,
        queryParams: String,
        headers: Map<String, String>,
        payloadSha256: String,
        dateStamp: String,
        dateStr: String
    ): String {
        val sortedHeaders = headers.mapKeys { it.key.lowercase(Locale.ROOT) }.toSortedMap()
        val signedHeaders = sortedHeaders.keys.joinToString(";")
        val canonicalHeaders = sortedHeaders.entries.joinToString("\n") { "${it.key}:${it.value.trim()}" } + "\n"

        val canonicalRequest = "$method\n$path\n$queryParams\n$canonicalHeaders\n$signedHeaders\n$payloadSha256"
        val canonicalRequestHash = sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))

        val service = "s3"
        val region = config.region.ifBlank { "auto" }
        val credentialScope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$dateStr\n$credentialScope\n$canonicalRequestHash"

        val signingKey = getSignatureKey(config.secretAccessKey, dateStamp, region, service)
        val signature = hmacSha256Hex(signingKey, stringToSign)

        return "AWS4-HMAC-SHA256 Credential=${config.accessKeyId}/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun getSignatureKey(key: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
        val kSecret = ("AWS4" + key).toByteArray(StandardCharsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, regionName)
        val kService = hmacSha256(kRegion, serviceName)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return bytesToHex(hmacSha256(key, data))
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return bytesToHex(md.digest(data))
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun getIso8601Date(): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun getDateStamp(): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun encodePath(path: String): String {
        return path.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    /** Returns the page's objects plus the NextContinuationToken when more pages remain. */
    private fun parseS3ListObjectsXml(xml: String): Pair<List<R2Object>, String?> {
        val results = mutableListOf<R2Object>()
        var nextToken: String? = null
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), "UTF-8")

            var eventType = parser.eventType
            var currentKey = ""
            var currentSize = 0L
            var currentModified = 0L
            var currentEtag = ""
            var inContents = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tag.equals("Contents", ignoreCase = true)) {
                            inContents = true
                            currentKey = ""
                            currentSize = 0L
                            currentModified = 0L
                            currentEtag = ""
                        } else if (inContents) {
                            when {
                                tag.equals("Key", ignoreCase = true) -> currentKey = parser.nextText()
                                tag.equals("Size", ignoreCase = true) -> currentSize = parser.nextText().toLongOrNull() ?: 0L
                                tag.equals("LastModified", ignoreCase = true) -> {
                                    val dateStr = parser.nextText()
                                    currentModified = parseIsoDate(dateStr)
                                }
                                tag.equals("ETag", ignoreCase = true) -> currentEtag = parser.nextText().trim('"')
                            }
                        } else if (tag.equals("NextContinuationToken", ignoreCase = true)) {
                            nextToken = parser.nextText()
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tag.equals("Contents", ignoreCase = true)) {
                            if (currentKey.isNotBlank()) {
                                results.add(R2Object(currentKey, currentSize, currentModified, currentEtag))
                            }
                            inContents = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            // Propagate: a mid-page parse error must not masquerade as a
            // complete-but-shorter listing (the sync engine would then treat
            // missing remote objects as deleted).
            throw IllegalStateException("Failed to parse list-objects XML", e)
        }
        return Pair(results, nextToken)
    }

    private fun parseIsoDate(dateStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(dateStr)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            try {
                val sdf2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                sdf2.timeZone = TimeZone.getTimeZone("UTC")
                sdf2.parse(dateStr)?.time ?: System.currentTimeMillis()
            } catch (e2: Exception) {
                System.currentTimeMillis()
            }
        }
    }
}
