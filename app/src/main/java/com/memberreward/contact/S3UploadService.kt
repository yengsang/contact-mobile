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
        userId: Int,
        images: List<GalleryImage>,
        contentResolver: ContentResolver
    ): ImageUploadResult {
        var successCount = 0
        var firstError: String? = null

        images.forEach { image ->
            runCatching {
                val presigned = requestPresignedUpload(baseUrl, tenantQrToken, userId, image)
                uploadImageToS3(presigned, image, contentResolver)
            }.onSuccess {
                successCount += 1
            }.onFailure { error ->
                if (firstError == null) {
                    firstError = "${image.fileName}: ${error.message ?: "Unknown upload error"}"
                }
            }
        }

        val failedCount = images.size - successCount
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
        userId: Int,
        image: GalleryImage
    ): PresignedUpload {
        val requestPayload = JSONObject()
            .put("fileName", image.fileName)
            .put("contentType", image.mimeType)
            .put("userId", userId)

        val endpoint = buildApiUrl(baseUrl, "api/s3/presign")
        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .applyTenantQrToken(tenantQrToken)
            .post(
                requestPayload.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            )
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
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

            return PresignedUpload(
                uploadUrl = uploadUrl,
                headers = headers
            )
        }
    }

    private fun uploadImageToS3(
        presignedUpload: PresignedUpload,
        image: GalleryImage,
        contentResolver: ContentResolver
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

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw IllegalStateException("S3 upload failed (${response.code}): $body")
            }
        }
    }

    private fun buildApiUrl(baseUrl: String, path: String): String {
        val builder = baseUrl.toHttpUrl().newBuilder()
        builder.addEncodedPathSegments(path.trimStart('/'))
        return builder.build().toString()
    }

    private fun Request.Builder.applyTenantQrToken(tenantQrToken: String): Request.Builder {
        header("x-tenant-qr-token", tenantQrToken)
        return this
    }
}
