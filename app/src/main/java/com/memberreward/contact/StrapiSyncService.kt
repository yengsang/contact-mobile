package com.memberreward.contact

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SyncResult(
    val userId: Int,
    val created: Int,
    val updated: Int
)

class StrapiSyncService {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    suspend fun syncContacts(
        baseUrl: String,
        appApiKey: String,
        userEmail: String,
        userPhone: String,
        userIcNumber: String,
        deviceId: String,
        contacts: List<PhoneContact>
    ): SyncResult {
        val userId = findOrCreateUser(
            baseUrl = baseUrl,
            appApiKey = appApiKey,
            userEmail = userEmail,
            userPhone = userPhone,
            userIcNumber = userIcNumber,
            deviceId = deviceId
        )
        var createdCount = 0
        var updatedCount = 0

        contacts.forEach { contact ->
            val existingContactId = findExistingContactId(
                baseUrl = baseUrl,
                appApiKey = appApiKey,
                userId = userId,
                phone = contact.phone
            )

            if (existingContactId == null) {
                createContact(baseUrl, appApiKey, userId, contact)
                createdCount += 1
            } else {
                updateContact(baseUrl, appApiKey, existingContactId, userId, contact)
                updatedCount += 1
            }
        }

        return SyncResult(userId, createdCount, updatedCount)
    }

    suspend fun findOrCreateUserId(
        baseUrl: String,
        appApiKey: String,
        userEmail: String,
        userPhone: String,
        userIcNumber: String,
        deviceId: String
    ): Int {
        return findOrCreateUser(
            baseUrl = baseUrl,
            appApiKey = appApiKey,
            userEmail = userEmail,
            userPhone = userPhone,
            userIcNumber = userIcNumber,
            deviceId = deviceId
        )
    }

    suspend fun uploadUserProfileImage(
        baseUrl: String,
        appApiKey: String,
        userId: Int,
        imageUri: Uri,
        contentResolver: ContentResolver
    ): String {
        val mimeType = contentResolver.getType(imageUri) ?: "image/jpeg"
        val fileName = resolveDisplayName(contentResolver, imageUri)
        val tempFile = copyUriToTempFile(contentResolver, imageUri, fileName)

        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
                )
                .build()

            val response = executeJsonRequest(
                Request.Builder()
                    .url(buildUrl(baseUrl, "api/app-users/$userId/profile-image"))
                    .applyAppApiKey(appApiKey, includeJsonContentType = false)
                    .post(requestBody)
                    .build()
            )

            return response
                .getJSONObject("data")
                .getJSONObject("attributes")
                .optString("image_url")
        } finally {
            tempFile.delete()
        }
    }

    private fun findOrCreateUser(
        baseUrl: String,
        appApiKey: String,
        userEmail: String,
        userPhone: String,
        userIcNumber: String,
        deviceId: String
    ): Int {
        val findUrl = buildUrl(baseUrl, "api/app-users") {
            addQueryParameter("filters[email][\$eq]", userEmail)
            addQueryParameter("pagination[pageSize]", "1")
        }

        val response = executeJsonRequest(
            Request.Builder()
                .url(findUrl)
                .applyAppApiKey(appApiKey)
                .get()
                .build()
        )

        val existingItems = response.getJSONArray("data")
        if (existingItems.length() > 0) {
            val existingUserId = existingItems.getJSONObject(0).getInt("id")

            val updatePayload = JSONObject().put(
                "data",
                JSONObject()
                    .put("phone", userPhone)
                    .put("ic_number", userIcNumber)
                    .put("device_id", deviceId)
            )

            executeJsonRequest(
                Request.Builder()
                    .url(buildUrl(baseUrl, "api/app-users/$existingUserId"))
                    .applyAppApiKey(appApiKey)
                    .put(updatePayload.toString().toRequestBody(jsonMediaType))
                    .build()
            )

            return existingUserId
        }

        val payload = JSONObject().put(
            "data",
            JSONObject()
                .put("email", userEmail)
                .put("phone", userPhone)
                .put("ic_number", userIcNumber)
                .put("device_id", deviceId)
        )

        val createResponse = executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/app-users"))
                .applyAppApiKey(appApiKey)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        )

        return createResponse.getJSONObject("data").getInt("id")
    }

    private fun findExistingContactId(
        baseUrl: String,
        appApiKey: String,
        userId: Int,
        phone: String
    ): Int? {
        val url = buildUrl(baseUrl, "api/app-users/$userId/contacts") {
            addQueryParameter("phone", phone)
            addQueryParameter("pageSize", "1")
        }

        val response = executeJsonRequest(
            Request.Builder()
                .url(url)
                .applyAppApiKey(appApiKey)
                .get()
                .build()
        )

        val items: JSONArray = response.getJSONArray("data")
        return if (items.length() > 0) items.getJSONObject(0).getInt("id") else null
    }

    private fun createContact(
        baseUrl: String,
        appApiKey: String,
        userId: Int,
        contact: PhoneContact
    ) {
        val payload = JSONObject().put("data", buildContactJson(userId, contact))

        executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/contacts"))
                .applyAppApiKey(appApiKey)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        )
    }

    private fun updateContact(
        baseUrl: String,
        appApiKey: String,
        contactId: Int,
        userId: Int,
        contact: PhoneContact
    ) {
        val payload = JSONObject().put("data", buildContactJson(userId, contact))

        executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/contacts/$contactId"))
                .applyAppApiKey(appApiKey)
                .put(payload.toString().toRequestBody(jsonMediaType))
                .build()
        )
    }

    private fun buildContactJson(userId: Int, contact: PhoneContact): JSONObject {
        val relationPayload = JSONObject()
            .put("connect", JSONArray().put(userId))

        return JSONObject()
            .put("name", contact.name)
            .put("phone", contact.phone)
            .put("user", relationPayload)
            .apply {
                if (!contact.email.isNullOrBlank()) {
                    put("email", contact.email)
                }
            }
    }

    private fun executeJsonRequest(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $body")
            }

            if (body.isBlank()) {
                throw IllegalStateException("Empty response from server.")
            }

            return JSONObject(body)
        }
    }

    private fun buildUrl(baseUrl: String, path: String, configure: (okhttp3.HttpUrl.Builder.() -> Unit)? = null): String {
        val builder = baseUrl.toHttpUrl().newBuilder()
        val normalizedPath = path.trimStart('/')
        builder.addEncodedPathSegments(normalizedPath)
        configure?.invoke(builder)
        return builder.build().toString()
    }

    private fun resolveDisplayName(contentResolver: ContentResolver, imageUri: Uri): String {
        contentResolver.query(imageUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                val displayName = cursor.getString(nameIndex)?.trim().orEmpty()
                if (displayName.isNotBlank()) {
                    return displayName
                }
            }
        }

        return "selected-image-${System.currentTimeMillis()}.jpg"
    }

    private fun copyUriToTempFile(
        contentResolver: ContentResolver,
        imageUri: Uri,
        fileName: String
    ): File {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val tempFile = File.createTempFile("upload-", "-$safeName")

        contentResolver.openInputStream(imageUri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Unable to open selected image.")

        return tempFile
    }

    private fun Request.Builder.applyAppApiKey(
        appApiKey: String,
        includeJsonContentType: Boolean = true
    ): Request.Builder {
        header("x-app-api-key", appApiKey)
        if (includeJsonContentType) {
            header("Content-Type", "application/json")
        }
        return this
    }
}
