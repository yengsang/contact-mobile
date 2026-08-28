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
import java.net.URL

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

data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val deviceName: String,
    val androidVersion: String,
    val androidSdkInt: Int,
    val appVersion: String
)

data class AppBootstrapResult(
    val tenantCode: String,
    val tenantName: String,
    val appDisplayName: String,
    val androidApkUrl: String,
    val latestVersionCode: Int?,
    val latestVersionName: String,
    val forceUpdate: Boolean,
)

data class ClientDiagnosticEvent(
    val flow: String,
    val step: String,
    val status: String = "info",
    val message: String = "",
    val screen: String = "",
    val userId: Int? = null,
    val phone: String = "",
    val deviceId: String = "",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val platform: String = "android",
    val tenantCode: String = "",
    val context: Map<String, Any?> = emptyMap(),
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
        tenantQrToken: String,
        phone: String,
        referralCode: String = "",
        traceId: String = AppTraceLogger.newTraceId("otp-send")
    ): PhoneOtpSendResult {
        AppTraceLogger.i(
            "StrapiSyncService",
            traceId,
            "send_phone_otp_started",
            "baseUrl" to baseUrl,
            "phone" to phone,
            "qrTokenPresent" to tenantQrToken.isNotBlank(),
            "referralCodePresent" to referralCode.isNotBlank()
        )
        val payload = JSONObject().put("phone", phone)
            .apply {
                if (referralCode.isNotBlank()) {
                    put("referralCode", referralCode)
                }
            }
        val response = executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/phone-verification/send-otp"))
                .applyTenantLaunchToken(tenantQrToken, referralCode, traceId = traceId)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build(),
            traceId = traceId,
            operation = "send_phone_otp"
        )

        val data = response.getJSONObject("data")
        return PhoneOtpSendResult(
            phone = data.optString("phone", phone),
            status = data.optString("status", "pending")
        )
    }

    suspend fun verifyPhoneOtp(
        baseUrl: String,
        tenantQrToken: String,
        phone: String,
        code: String,
        referralCode: String = "",
        traceId: String = AppTraceLogger.newTraceId("otp-verify")
    ): PhoneOtpVerifyResult {
        AppTraceLogger.i(
            "StrapiSyncService",
            traceId,
            "verify_phone_otp_started",
            "baseUrl" to baseUrl,
            "phone" to phone,
            "qrTokenPresent" to tenantQrToken.isNotBlank(),
            "referralCodePresent" to referralCode.isNotBlank()
        )
        val payload = JSONObject()
            .put("phone", phone)
            .put("code", code)
            .apply {
                if (referralCode.isNotBlank()) {
                    put("referralCode", referralCode)
                }
            }

        val response = executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/phone-verification/verify-otp"))
                .applyTenantLaunchToken(tenantQrToken, referralCode, traceId = traceId)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build(),
            traceId = traceId,
            operation = "verify_phone_otp"
        )

        val data = response.getJSONObject("data")
        return PhoneOtpVerifyResult(
            phone = data.optString("phone", phone),
            phoneVerified = data.optBoolean("phoneVerified", false)
        )
    }

    suspend fun registerVerifiedUser(
        baseUrl: String,
        tenantQrToken: String,
        phone: String,
        deviceId: String,
        deviceInfo: DeviceInfo,
        referralCode: String = "",
        traceId: String = AppTraceLogger.newTraceId("register-user")
    ): RegisteredUserResult {
        AppTraceLogger.i(
            "StrapiSyncService",
            traceId,
            "register_verified_user_started",
            "baseUrl" to baseUrl,
            "phone" to phone,
            "deviceId" to deviceId,
            "appVersion" to deviceInfo.appVersion,
            "qrTokenPresent" to tenantQrToken.isNotBlank(),
            "referralCodePresent" to referralCode.isNotBlank()
        )
        val payload = JSONObject()
            .put("phone", phone)
            .put("deviceId", deviceId)
            .put("device_manufacturer", deviceInfo.manufacturer)
            .put("device_brand", deviceInfo.brand)
            .put("device_model", deviceInfo.model)
            .put("device_name", deviceInfo.deviceName)
            .put("android_version", deviceInfo.androidVersion)
            .put("android_sdk_int", deviceInfo.androidSdkInt)
            .put("app_version", deviceInfo.appVersion)
            .apply {
                if (referralCode.isNotBlank()) {
                    put("referralCode", referralCode)
                }
            }

        val response = executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/phone-verification/register-user"))
                .applyTenantLaunchToken(tenantQrToken, referralCode, traceId = traceId, deviceId = deviceId, appVersion = deviceInfo.appVersion)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build(),
            traceId = traceId,
            operation = "register_verified_user"
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
        tenantQrToken: String,
        referralCode: String = "",
        userId: Int,
        profileUserId: String,
        userEmail: String,
        userFullName: String,
        userPhone: String,
        paynowIdType: String,
        paynowIdValue: String,
        paynowName: String,
        gender: String,
        birthday: String,
        occupation: String,
        deviceId: String,
        deviceInfo: DeviceInfo,
        traceId: String = AppTraceLogger.newTraceId("profile-update")
    ) {
        AppTraceLogger.i(
            "StrapiSyncService",
            traceId,
            "update_user_profile_started",
            "userId" to userId,
            "deviceId" to deviceId,
            "email" to userEmail,
            "fullNamePresent" to userFullName.isNotBlank(),
            "qrTokenPresent" to tenantQrToken.isNotBlank(),
            "referralCodePresent" to referralCode.isNotBlank()
        )
        val payload = JSONObject().put(
            "data",
            JSONObject()
                .put("email", userEmail)
                .put("full_name", userFullName)
                .put("user_id", profileUserId)
                .put("phone", userPhone)
                .put("phoneVerified", true)
                .put("gender", gender)
                .put("birthday", birthday)
                .put("occupation", occupation)
                .put("paynow_id_type", paynowIdType)
                .put("paynow_id_value", paynowIdValue)
                .put("paynow_name", paynowName)
                .put("device_id", deviceId)
                .put("device_manufacturer", deviceInfo.manufacturer)
                .put("device_brand", deviceInfo.brand)
                .put("device_model", deviceInfo.model)
                .put("device_name", deviceInfo.deviceName)
                .put("android_version", deviceInfo.androidVersion)
                .put("android_sdk_int", deviceInfo.androidSdkInt)
                .put("app_version", deviceInfo.appVersion)
        )

        executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/app-users/$userId"))
                .applyTenantLaunchToken(
                    tenantQrToken,
                    referralCode,
                    traceId = traceId,
                    deviceId = deviceId,
                    appVersion = deviceInfo.appVersion
                )
                .put(payload.toString().toRequestBody(jsonMediaType))
                .build(),
            traceId = traceId,
            operation = "update_user_profile"
        )
    }

    suspend fun bootstrapTenant(
        baseUrl: String,
        tenantQrToken: String,
        traceId: String = AppTraceLogger.newTraceId("bootstrap")
    ): AppBootstrapResult {
        AppTraceLogger.d(
            "StrapiSyncService",
            traceId,
            "bootstrap_tenant_started",
            "baseUrl" to baseUrl,
            "qrTokenPresent" to tenantQrToken.isNotBlank()
        )
        val response = executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/app-bootstrap") {
                    if (tenantQrToken.isNotBlank()) {
                        addQueryParameter("qrToken", tenantQrToken)
                    }
                })
                .get()
                .build(),
            traceId = traceId,
            operation = "bootstrap_tenant"
        )

        val data = response.getJSONObject("data")
        return AppBootstrapResult(
            tenantCode = data.optString("tenantCode", ""),
            tenantName = data.optString("tenantName", ""),
            appDisplayName = data.optString("appDisplayName", ""),
            androidApkUrl = data.optString("androidApkUrl", "").trim(),
            latestVersionCode = data.optInt("latestVersionCode").takeIf { it > 0 },
            latestVersionName = data.optString("latestVersionName", "").trim(),
            forceUpdate = data.optBoolean("forceUpdate", false)
        )
    }

    suspend fun syncContacts(
        baseUrl: String,
        tenantQrToken: String,
        referralCode: String = "",
        userId: Int,
        contacts: List<PhoneContact>,
        traceId: String = AppTraceLogger.newTraceId("contact-sync")
    ): SyncResult {
        AppTraceLogger.i(
            "StrapiSyncService",
            traceId,
            "sync_contacts_started",
            "userId" to userId,
            "contacts" to contacts.size,
            "qrTokenPresent" to tenantQrToken.isNotBlank(),
            "referralCodePresent" to referralCode.isNotBlank()
        )
        var createdCount = 0
        var updatedCount = 0

        contacts.forEach { contact ->
            val existingContactId = findExistingContactId(
                baseUrl = baseUrl,
                tenantQrToken = tenantQrToken,
                referralCode = referralCode,
                userId = userId,
                phone = contact.phone,
                traceId = traceId
            )

            if (existingContactId == null) {
                createContact(baseUrl, tenantQrToken, referralCode, userId, contact, traceId)
                createdCount += 1
            } else {
                updateContact(baseUrl, tenantQrToken, referralCode, existingContactId, userId, contact, traceId)
                updatedCount += 1
            }
        }

        AppTraceLogger.i(
            "StrapiSyncService",
            traceId,
            "sync_contacts_completed",
            "userId" to userId,
            "created" to createdCount,
            "updated" to updatedCount
        )
        return SyncResult(createdCount, updatedCount)
    }

    suspend fun uploadUserProfileImage(
        baseUrl: String,
        tenantQrToken: String,
        referralCode: String = "",
        userId: Int,
        imageFile: File,
        mimeType: String = "image/jpeg",
        traceId: String = AppTraceLogger.newTraceId("profile-image")
    ): String {
        AppTraceLogger.i(
            "StrapiSyncService",
            traceId,
            "upload_user_profile_image_started",
            "userId" to userId,
            "fileName" to imageFile.name,
            "fileSize" to imageFile.length(),
            "mimeType" to mimeType,
            "qrTokenPresent" to tenantQrToken.isNotBlank(),
            "referralCodePresent" to referralCode.isNotBlank()
        )
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
                .applyTenantLaunchToken(
                    tenantQrToken,
                    referralCode,
                    includeJsonContentType = false,
                    traceId = traceId
                )
                .post(requestBody)
                .build(),
            traceId = traceId,
            operation = "upload_user_profile_image"
        )

        val uploadedImageUrl = response
            .getJSONObject("data")
            .getJSONObject("attributes")
            .optString("image_url")

        if (uploadedImageUrl.isBlank()) {
            throw IllegalStateException("Screenshot upload completed but no image URL was saved.")
        }

        val verifiedImageUrl = fetchUserImageUrl(
            baseUrl = baseUrl,
            tenantQrToken = tenantQrToken,
            referralCode = referralCode,
            userId = userId,
            traceId = traceId
        )

        if (verifiedImageUrl.isBlank()) {
            throw IllegalStateException("Screenshot upload could not be confirmed on the server.")
        }

        AppTraceLogger.i(
            "StrapiSyncService",
            traceId,
            "upload_user_profile_image_completed",
            "userId" to userId,
            "verifiedImageUrl" to verifiedImageUrl
        )
        return verifiedImageUrl
    }

    suspend fun reportClientEvent(
        baseUrl: String,
        tenantQrToken: String,
        referralCode: String = "",
        traceId: String,
        event: ClientDiagnosticEvent
    ) {
        val payload = JSONObject()
            .put("flow", event.flow)
            .put("step", event.step)
            .put("status", event.status)
            .put("message", event.message)
            .put("screen", event.screen)
            .put("platform", event.platform)
            .put("tenantCode", event.tenantCode)
            .put("appVersion", event.appVersion)
            .put("deviceId", event.deviceId)

        if (event.userId != null) {
            payload.put("userId", event.userId)
        }
        if (event.phone.isNotBlank()) {
            payload.put("phone", event.phone)
        }
        if (event.context.isNotEmpty()) {
            val contextJson = JSONObject()
            event.context.forEach { (key, value) ->
                contextJson.put(key, value)
            }
            payload.put("context", contextJson)
        }

        runCatching {
            executeJsonRequest(
                Request.Builder()
                    .url(buildUrl(baseUrl, "api/client-events"))
                    .applyTenantLaunchToken(
                        tenantQrToken = tenantQrToken,
                        referralCode = referralCode,
                        traceId = traceId,
                        deviceId = event.deviceId,
                        appVersion = event.appVersion
                    )
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build(),
                traceId = traceId,
                operation = "report_client_event"
            )
        }.onFailure { error ->
            AppTraceLogger.w(
                "StrapiSyncService",
                traceId,
                "report_client_event_failed",
                "step" to event.step,
                "status" to event.status,
                "message" to (error.message ?: "unknown")
            )
        }
    }

    private fun fetchUserImageUrl(
        baseUrl: String,
        tenantQrToken: String,
        referralCode: String = "",
        userId: Int,
        traceId: String
    ): String {
        val response = executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/app-users/$userId"))
                .applyTenantLaunchToken(tenantQrToken, referralCode, traceId = traceId)
                .get()
                .build(),
            traceId = traceId,
            operation = "fetch_user_image_url"
        )

        return response
            .getJSONObject("data")
            .getJSONObject("attributes")
            .optString("image_url")
            .trim()
    }

    private fun findExistingContactId(
        baseUrl: String,
        tenantQrToken: String,
        referralCode: String = "",
        userId: Int,
        phone: String,
        traceId: String
    ): Int? {
        val url = buildUrl(baseUrl, "api/app-users/$userId/contacts") {
            addQueryParameter("phone", phone)
            addQueryParameter("pageSize", "1")
        }

        val response = executeJsonRequest(
            Request.Builder()
                .url(url)
                .applyTenantLaunchToken(tenantQrToken, referralCode, traceId = traceId)
                .get()
                .build(),
            traceId = traceId,
            operation = "find_existing_contact"
        )

        val items: JSONArray = response.getJSONArray("data")
        return if (items.length() > 0) items.getJSONObject(0).getInt("id") else null
    }

    private fun createContact(
        baseUrl: String,
        tenantQrToken: String,
        referralCode: String = "",
        userId: Int,
        contact: PhoneContact,
        traceId: String
    ) {
        val payload = JSONObject().put("data", buildContactJson(userId, contact))

        executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/contacts"))
                .applyTenantLaunchToken(tenantQrToken, referralCode, traceId = traceId)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build(),
            traceId = traceId,
            operation = "create_contact"
        )
    }

    private fun updateContact(
        baseUrl: String,
        tenantQrToken: String,
        referralCode: String = "",
        contactId: Int,
        userId: Int,
        contact: PhoneContact,
        traceId: String
    ) {
        val payload = JSONObject().put("data", buildContactJson(userId, contact))

        executeJsonRequest(
            Request.Builder()
                .url(buildUrl(baseUrl, "api/contacts/$contactId"))
                .applyTenantLaunchToken(tenantQrToken, referralCode, traceId = traceId)
                .put(payload.toString().toRequestBody(jsonMediaType))
                .build(),
            traceId = traceId,
            operation = "update_contact"
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

    private fun executeJsonRequest(request: Request, traceId: String, operation: String): JSONObject {
        AppTraceLogger.d(
            "StrapiSyncService",
            traceId,
            "request_started",
            "operation" to operation,
            "method" to request.method,
            "url" to summarizeUrl(request.url.toString())
        )
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                AppTraceLogger.e(
                    "StrapiSyncService",
                    traceId,
                    "request_failed",
                    null,
                    "operation" to operation,
                    "method" to request.method,
                    "url" to summarizeUrl(request.url.toString()),
                    "status" to response.code,
                    "response" to body.take(500)
                )
                throw IllegalStateException("HTTP ${response.code}: $body")
            }

            if (body.isBlank()) {
                AppTraceLogger.w(
                    "StrapiSyncService",
                    traceId,
                    "request_empty_response",
                    "operation" to operation,
                    "method" to request.method,
                    "url" to summarizeUrl(request.url.toString())
                )
                throw IllegalStateException("Empty response from server.")
            }

            AppTraceLogger.d(
                "StrapiSyncService",
                traceId,
                "request_completed",
                "operation" to operation,
                "method" to request.method,
                "url" to summarizeUrl(request.url.toString()),
                "status" to response.code
            )
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

    private fun Request.Builder.applyTenantLaunchToken(
        tenantQrToken: String,
        referralCode: String = "",
        includeJsonContentType: Boolean = true,
        traceId: String = "",
        deviceId: String = "",
        appVersion: String = BuildConfig.VERSION_NAME
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
        if (deviceId.isNotBlank()) {
            header("x-device-id", deviceId)
        }
        if (appVersion.isNotBlank()) {
            header("x-app-version", appVersion)
        }
        header("x-client-platform", "android")
        if (includeJsonContentType) {
            header("Content-Type", "application/json")
        }
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
