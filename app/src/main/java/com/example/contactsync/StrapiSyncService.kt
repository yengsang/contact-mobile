package com.example.contactsync

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject

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
        apiToken: String?,
        deviceId: String,
        contacts: List<PhoneContact>
    ): SyncResult {
        val userId = findOrCreateUser(baseUrl, apiToken, deviceId)
        var createdCount = 0
        var updatedCount = 0

        contacts.forEach { contact ->
            val existingContactId = findExistingContactId(
                baseUrl = baseUrl,
                apiToken = apiToken,
                userId = userId,
                phone = contact.phone
            )

            if (existingContactId == null) {
                createContact(baseUrl, apiToken, userId, contact)
                createdCount += 1
            } else {
                updateContact(baseUrl, apiToken, existingContactId, userId, contact)
                updatedCount += 1
            }
        }

        return SyncResult(userId, createdCount, updatedCount)
    }

    private fun findOrCreateUser(baseUrl: String, apiToken: String?, deviceId: String): Int {
        val findUrl = buildUrl(baseUrl, "api/app-users") {
            addQueryParameter("filters[device_id][\$eq]", deviceId)
            addQueryParameter("pagination[pageSize]", "1")
        }

        val response = executeJsonRequest(
            Request.Builder()
                .url(findUrl)
                .applyAuthorization(apiToken)
                .get()
                .build()
        )

        val existingItems = response.getJSONArray("data")
        if (existingItems.length() > 0) {
            return existingItems.getJSONObject(0).getInt("id")
        }

        val payload = JSONObject().put(
            "data",
            JSONObject()
                .put("email", "$deviceId@example.local")
                .put("device_id", deviceId)
        )

        val createResponse = executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/app-users"))
                .applyAuthorization(apiToken)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        )

        return createResponse.getJSONObject("data").getInt("id")
    }

    private fun findExistingContactId(
        baseUrl: String,
        apiToken: String?,
        userId: Int,
        phone: String
    ): Int? {
        val url = buildUrl(baseUrl, "api/contacts") {
            addQueryParameter("filters[user][id][\$eq]", userId.toString())
            addQueryParameter("filters[phone][\$eq]", phone)
            addQueryParameter("pagination[pageSize]", "1")
        }

        val response = executeJsonRequest(
            Request.Builder()
                .url(url)
                .applyAuthorization(apiToken)
                .get()
                .build()
        )

        val items: JSONArray = response.getJSONArray("data")
        return if (items.length() > 0) items.getJSONObject(0).getInt("id") else null
    }

    private fun createContact(
        baseUrl: String,
        apiToken: String?,
        userId: Int,
        contact: PhoneContact
    ) {
        val payload = JSONObject().put("data", buildContactJson(userId, contact))

        executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/contacts"))
                .applyAuthorization(apiToken)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        )
    }

    private fun updateContact(
        baseUrl: String,
        apiToken: String?,
        contactId: Int,
        userId: Int,
        contact: PhoneContact
    ) {
        val payload = JSONObject().put("data", buildContactJson(userId, contact))

        executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/contacts/$contactId"))
                .applyAuthorization(apiToken)
                .put(payload.toString().toRequestBody(jsonMediaType))
                .build()
        )
    }

    private fun buildContactJson(userId: Int, contact: PhoneContact): JSONObject {
        return JSONObject()
            .put("name", contact.name)
            .put("phone", contact.phone)
            .put("user", userId)
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

    private fun Request.Builder.applyAuthorization(apiToken: String?): Request.Builder {
        if (!apiToken.isNullOrBlank()) {
            header("Authorization", "Bearer $apiToken")
        }
        header("Content-Type", "application/json")
        return this
    }
}
