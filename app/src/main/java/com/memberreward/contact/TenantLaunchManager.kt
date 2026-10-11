package com.memberreward.contact

import android.content.Context

data class TenantLaunchContext(
    val qrToken: String,
    val tenantCode: String,
    val referralCode: String,
    val tenantName: String,
    val experience: TenantExperience,
)

class TenantLaunchManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "tenant_launch_context"
        private const val KEY_QR_TOKEN = "qr_token"
        private const val KEY_TENANT_CODE = "tenant_code"
        private const val KEY_REFERRAL_CODE = "referral_code"
        private const val KEY_TENANT_NAME = "tenant_name"
        private const val KEY_EXPERIENCE_TEMPLATE = "experience_template"
        private const val KEY_EXPERIENCE_EYEBROW = "experience_eyebrow"
        private const val KEY_EXPERIENCE_HEADLINE = "experience_headline"
        private const val KEY_EXPERIENCE_DESCRIPTION = "experience_description"
        private const val KEY_EXPERIENCE_CTA_LABEL = "experience_cta_label"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getContext(): TenantLaunchContext {
        return TenantLaunchContext(
            qrToken = prefs.getString(KEY_QR_TOKEN, "").orEmpty().trim(),
            tenantCode = prefs.getString(KEY_TENANT_CODE, "").orEmpty().trim(),
            referralCode = prefs.getString(KEY_REFERRAL_CODE, "").orEmpty().trim(),
            tenantName = prefs.getString(KEY_TENANT_NAME, "").orEmpty().trim(),
            experience = TenantExperience(
                template = prefs.getString(KEY_EXPERIENCE_TEMPLATE, "classic").orEmpty().trim(),
                eyebrow = prefs.getString(KEY_EXPERIENCE_EYEBROW, "").orEmpty().trim(),
                headline = prefs.getString(KEY_EXPERIENCE_HEADLINE, "").orEmpty().trim(),
                description = prefs.getString(KEY_EXPERIENCE_DESCRIPTION, "").orEmpty().trim(),
                ctaLabel = prefs.getString(KEY_EXPERIENCE_CTA_LABEL, "").orEmpty().trim(),
            ),
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

    fun updateReferralCode(referralCode: String) {
        prefs.edit()
            .putString(KEY_REFERRAL_CODE, referralCode.trim())
            .apply()
    }

    fun updateTenantName(tenantName: String) {
        prefs.edit()
            .putString(KEY_TENANT_NAME, tenantName.trim())
            .apply()
    }

    fun updateExperience(experience: TenantExperience) {
        prefs.edit()
            .putString(KEY_EXPERIENCE_TEMPLATE, experience.template.trim())
            .putString(KEY_EXPERIENCE_EYEBROW, experience.eyebrow.trim())
            .putString(KEY_EXPERIENCE_HEADLINE, experience.headline.trim())
            .putString(KEY_EXPERIENCE_DESCRIPTION, experience.description.trim())
            .putString(KEY_EXPERIENCE_CTA_LABEL, experience.ctaLabel.trim())
            .apply()
    }
}
