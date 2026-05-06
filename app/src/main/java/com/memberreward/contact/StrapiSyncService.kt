package com.memberreward.contact

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
    val created: Int,
    val updated: Int
)

data class PhoneOtpSendResult(
    val phone: String,
    val status: String
)

data class PhoneOtpVerifyResult(
    val phone: String,
    val phoneVerified: Boolean
)

data class RegisteredUserResult(
    val userId: Int,
    val phone: String
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

    suspend fun sendPhoneOtp(
        baseUrl: String,
        appApiKey: String,
        phone: String
    ): PhoneOtpSendResult {
        val payload = JSONObject().put("phone", phone)
        val response = executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/phone-verification/send-otp"))
                .applyAppApiKey(appApiKey)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        )

        val data = response.getJSONObject("data")
        return PhoneOtpSendResult(
            phone = data.optString("phone", phone),
            status = data.optString("status", "pending")
        )
    }

    suspend fun verifyPhoneOtp(
        baseUrl: String,
        appApiKey: String,
        phone: String,
        code: String
    ): PhoneOtpVerifyResult {
        val payload = JSONObject()
            .put("phone", phone)
            .put("code", code)

        val response = executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/phone-verification/verify-otp"))
                .applyAppApiKey(appApiKey)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        )

        val data = response.getJSONObject("data")
        return PhoneOtpVerifyResult(
            phone = data.optString("phone", phone),
            phoneVerified = data.optBoolean("phoneVerified", false)
        )
    }

    suspend fun registerVerifiedUser(
        baseUrl: String,
        appApiKey: String,
        phone: String,
        deviceId: String
    ): RegisteredUserResult {
        val payload = JSONObject()
            .put("phone", phone)
            .put("deviceId", deviceId)

        val response = executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/phone-verification/register-user"))
                .applyAppApiKey(appApiKey)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
        )

        val data = response.getJSONObject("data")
        val attributes = data.getJSONObject("attributes")
        return RegisteredUserResult(
            userId = data.getInt("id"),
            phone = attributes.optString("phone", phone)
        )
    }

    suspend fun updateUserProfile(
        baseUrl: String,
        appApiKey: String,
        userId: Int,
        userEmail: String,
        userFullName: String,
        userPhone: String,
        paynowIdType: String,
        paynowIdValue: String,
        paynowName: String,
        gender: String,
        birthday: String,
        occupation: String,
        deviceId: String
    ) {
        val payload = JSONObject().put(
            "data",
            JSONObject()
                .put("email", userEmail)
                .put("full_name", userFullName)
                .put("phone", userPhone)
                .put("phoneVerified", true)
                .put("gender", gender)
                .put("birthday", birthday)
                .put("occupation", occupation)
                .put("paynow_id_type", paynowIdType)
                .put("paynow_id_value", paynowIdValue)
                .put("paynow_name", paynowName)
                .put("paynow_number", paynowIdValue)
                .put("paynow_nickname", paynowName)
                .put("device_id", deviceId)
        )

        executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/app-users/$userId"))
                .applyAppApiKey(appApiKey)
                .put(payload.toString().toRequestBody(jsonMediaType))
                .build()
        )
    }

    suspend fun syncContacts(
        baseUrl: String,
        appApiKey: String,
        userId: Int,
        contacts: List<PhoneContact>
    ): SyncResult {
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

        return SyncResult(createdCount, updatedCount)
    }

    suspend fun uploadUserProfileImage(
        baseUrl: String,
        appApiKey: String,
        userId: Int,
        imageFile: File,
        mimeType: String = "image/jpeg"
    ): String {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                imageFile.name,
                imageFile.asRequestBody(mimeType.toMediaTypeOrNull())
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
