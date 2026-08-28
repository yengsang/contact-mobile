package com.memberreward.contact

import android.content.ContentResolver
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.source
import org.json.JSONObject
import java.net.URL

data class ImageUploadResult(
    val uploaded: Int,
    val total: Int,
    val failed: Int,
    val firstError: String? = null
)

private data class PresignedUpload(
    val uploadUrl: String,
    val headers: Map<String, String>
)

class S3UploadService {
    private val client = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    suspend fun uploadAllImages(
        baseUrl: String,
        tenantQrToken: String,
        referralCode: String = "",
        userId: Int,
        images: List<GalleryImage>,
        contentResolver: ContentResolver,
        traceId: String = AppTraceLogger.newTraceId("gallery-upload")
    ): ImageUploadResult {
        AppTraceLogger.i(
            "S3UploadService",
            traceId,
            "upload_all_images_started",
            "userId" to userId,
            "images" to images.size,
            "qrTokenPresent" to tenantQrToken.isNotBlank(),
            "referralCodePresent" to referralCode.isNotBlank()
        )
        var successCount = 0
        var firstError: String? = null

        images.forEachIndexed { index, image ->
            runCatching {
                AppTraceLogger.d(
                    "S3UploadService",
                    traceId,
                    "upload_image_started",
                    "userId" to userId,
                    "index" to (index + 1),
                    "total" to images.size,
                    "fileName" to image.fileName,
                    "mimeType" to image.mimeType,
                    "sizeBytes" to image.sizeBytes
                )
                val presigned = requestPresignedUpload(baseUrl, tenantQrToken, referralCode, userId, image, traceId)
                uploadImageToS3(presigned, image, contentResolver, traceId)
            }.onSuccess {
                successCount += 1
                AppTraceLogger.d(
                    "S3UploadService",
                    traceId,
                    "upload_image_completed",
                    "userId" to userId,
                    "index" to (index + 1),
                    "total" to images.size,
                    "fileName" to image.fileName
                )
            }.onFailure { error ->
                if (firstError == null) {
                    firstError = "${image.fileName}: ${error.message ?: "Unknown upload error"}"
                }
                AppTraceLogger.e(
                    "S3UploadService",
                    traceId,
                    "upload_image_failed",
                    error,
                    "userId" to userId,
                    "index" to (index + 1),
                    "total" to images.size,
                    "fileName" to image.fileName
                )
            }
        }

        val failedCount = images.size - successCount
        AppTraceLogger.i(
            "S3UploadService",
            traceId,
            "upload_all_images_completed",
            "userId" to userId,
            "uploaded" to successCount,
            "failed" to failedCount,
            "total" to images.size,
            "firstError" to (firstError ?: "")
        )
        return ImageUploadResult(
            uploaded = successCount,
            total = images.size,
            failed = failedCount,
            firstError = firstError
        )
    }

    private fun requestPresignedUpload(
        baseUrl: String,
        tenantQrToken: String,
        referralCode: String = "",
        userId: Int,
        image: GalleryImage,
        traceId: String
    ): PresignedUpload {
        val requestPayload = JSONObject()
            .put("fileName", image.fileName)
            .put("contentType", image.mimeType)
            .put("userId", userId)

        val endpoint = buildApiUrl(baseUrl, "api/s3/presign")
        AppTraceLogger.d(
            "S3UploadService",
            traceId,
            "presign_request_started",
            "userId" to userId,
            "fileName" to image.fileName,
            "contentType" to image.mimeType,
            "url" to summarizeUrl(endpoint)
        )
        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .applyTenantQrToken(tenantQrToken, referralCode, traceId)
            .post(
                requestPayload.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            )
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                AppTraceLogger.e(
                    "S3UploadService",
                    traceId,
                    "presign_request_failed",
                    null,
                    "userId" to userId,
                    "fileName" to image.fileName,
                    "status" to response.code,
                    "response" to body.take(500)
                )
                throw IllegalStateException("Presign request failed (${response.code}): $body")
            }

            val json = JSONObject(body)
            val uploadUrl = json.optString("uploadUrl")
            if (uploadUrl.isBlank()) {
                throw IllegalStateException("Presign response missing uploadUrl.")
            }

            val headersJson = json.optJSONObject("headers") ?: JSONObject()
            val headers = mutableMapOf<String, String>()
            headersJson.keys().forEach { key ->
                val value = headersJson.optString(key)
                if (value.isNotBlank()) {
                    headers[key] = value
                }
            }

            AppTraceLogger.d(
                "S3UploadService",
                traceId,
                "presign_request_completed",
                "userId" to userId,
                "fileName" to image.fileName,
                "status" to response.code,
                "objectUrl" to json.optString("fileUrl")
            )
            return PresignedUpload(
                uploadUrl = uploadUrl,
                headers = headers
            )
        }
    }

    private fun uploadImageToS3(
        presignedUpload: PresignedUpload,
        image: GalleryImage,
        contentResolver: ContentResolver,
        traceId: String
    ) {
        if (image.sizeBytes <= 0L) {
            throw IllegalStateException("Unable to determine image size for ${image.fileName}")
        }

        val requestBody = object : RequestBody() {
            override fun contentType() = image.mimeType.toMediaTypeOrNull()

            override fun contentLength() = image.sizeBytes

            override fun writeTo(sink: okio.BufferedSink) {
                contentResolver.openInputStream(image.uri)?.use { inputStream ->
                    inputStream.source().use { source ->
                        sink.writeAll(source)
                    }
                } ?: throw IllegalStateException("Unable to open image stream: ${image.uri}")
            }
        }

        val requestBuilder = Request.Builder()
            .url(presignedUpload.uploadUrl)
            .put(requestBody)

        presignedUpload.headers.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }

        val request = requestBuilder.build()
        AppTraceLogger.d(
            "S3UploadService",
            traceId,
            "s3_put_started",
            "fileName" to image.fileName,
            "sizeBytes" to image.sizeBytes,
            "url" to summarizeUrl(presignedUpload.uploadUrl)
        )

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                AppTraceLogger.e(
                    "S3UploadService",
                    traceId,
                    "s3_put_failed",
                    null,
                    "fileName" to image.fileName,
                    "status" to response.code,
                    "response" to body.take(500)
                )
                throw IllegalStateException("S3 upload failed (${response.code}): $body")
            }
            AppTraceLogger.d(
                "S3UploadService",
                traceId,
                "s3_put_completed",
                "fileName" to image.fileName,
                "status" to response.code
            )
        }
    }

    private fun buildApiUrl(baseUrl: String, path: String): String {
        val builder = baseUrl.toHttpUrl().newBuilder()
        builder.addEncodedPathSegments(path.trimStart('/'))
        return builder.build().toString()
    }

    private fun Request.Builder.applyTenantQrToken(
        tenantQrToken: String,
        referralCode: String = "",
        traceId: String = "",
    ): Request.Builder {
        if (tenantQrToken.isNotBlank()) {
            header("x-tenant-qr-token", tenantQrToken)
        }
        if (referralCode.isNotBlank()) {
            header("x-referral-code", referralCode)
        }
        if (traceId.isNotBlank()) {
            header("x-trace-id", traceId)
        }
        header("x-client-platform", "android")
        return this
    }

    private fun summarizeUrl(url: String): String {
        return runCatching {
            val parsed = URL(url)
            buildString {
                append(parsed.protocol)
                append("://")
                append(parsed.host)
                append(parsed.path)
                if (!parsed.query.isNullOrBlank()) {
                    append('?')
                    append(parsed.query)
                }
            }
        }.getOrDefault(url)
    }
}
