package com.memberreward.contact

import android.content.Context

data class TenantLaunchContext(
    val qrToken: String,
    val tenantCode: String,
    val referralCode: String,
    val tenantName: String,
)

class TenantLaunchManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "tenant_launch_context"
        private const val KEY_QR_TOKEN = "qr_token"
        private const val KEY_TENANT_CODE = "tenant_code"
        private const val KEY_REFERRAL_CODE = "referral_code"
        private const val KEY_TENANT_NAME = "tenant_name"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getContext(): TenantLaunchContext {
        return TenantLaunchContext(
            qrToken = prefs.getString(KEY_QR_TOKEN, "").orEmpty().trim(),
            tenantCode = prefs.getString(KEY_TENANT_CODE, "").orEmpty().trim(),
            referralCode = prefs.getString(KEY_REFERRAL_CODE, "").orEmpty().trim(),
            tenantName = prefs.getString(KEY_TENANT_NAME, "").orEmpty().trim(),
        )
    }

    fun saveLaunchSelection(
        qrToken: String,
        tenantCode: String,
        referralCode: String,
    ) {
        prefs.edit()
            .putString(KEY_QR_TOKEN, qrToken.trim())
            .putString(KEY_TENANT_CODE, tenantCode.trim())
            .putString(KEY_REFERRAL_CODE, referralCode.trim())
            .apply()
    }

    fun updateTenantName(tenantName: String) {
        prefs.edit()
            .putString(KEY_TENANT_NAME, tenantName.trim())
            .apply()
    }
}
