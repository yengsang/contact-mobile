package com.memberreward.contact

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.method.KeyListener
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.memberreward.contact.BuildConfig
import com.memberreward.contact.databinding.ActivityVerifyPhoneBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VerifyPhoneActivity : AppCompatActivity() {

    companion object {
        private const val OTP_COOLDOWN_MS = 5 * 60 * 1000L
        private const val PREFS_NAME = "member_reward_verification"
        private const val KEY_LAST_OTP_SENT_AT = "last_otp_sent_at"
        private const val EXTRA_QR_TOKEN = "extra_qr_token"
        private const val EXTRA_TENANT_CODE = "extra_tenant_code"
        private const val EXTRA_REFERRAL_CODE = "extra_referral_code"

        fun createIntent(
            context: Context,
            qrToken: String = "",
            tenantCode: String = "",
            referralCode: String = ""
        ) = android.content.Intent(context, VerifyPhoneActivity::class.java)
            .putExtra(EXTRA_QR_TOKEN, qrToken)
            .putExtra(EXTRA_TENANT_CODE, tenantCode)
            .putExtra(EXTRA_REFERRAL_CODE, referralCode)
    }

    private lateinit var binding: ActivityVerifyPhoneBinding
    private lateinit var tenantLaunchManager: TenantLaunchManager
    private val syncService = StrapiSyncService()
    private var countdownTimer: CountDownTimer? = null
    private var busy = false
    private var qrToken: String = ""
    private var tenantCode: String = ""
    private var referralCode: String = ""
    private var referralCodeLocked = false
    private var forceUpdateRequired = false
    private var updateDialogShown = false
    private var editableReferralKeyListener: KeyListener? = null
    private val otpEnabled: Boolean
        get() = BuildConfig.OTP_ENABLED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tenantLaunchManager = TenantLaunchManager(this)
        val storedContext = tenantLaunchManager.getContext()
        qrToken = intent.getStringExtra(EXTRA_QR_TOKEN).orEmpty().trim().ifBlank { storedContext.qrToken }
        tenantCode = intent.getStringExtra(EXTRA_TENANT_CODE).orEmpty().trim().ifBlank { storedContext.tenantCode }
        referralCode = intent.getStringExtra(EXTRA_REFERRAL_CODE).orEmpty().trim().ifBlank { storedContext.referralCode }
        if (qrToken.isNotBlank()) {
            tenantLaunchManager.saveLaunchSelection(
                qrToken = qrToken,
                tenantCode = tenantCode,
                referralCode = referralCode
            )
        }
        binding = ActivityVerifyPhoneBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()
        editableReferralKeyListener = binding.referralCodeInput.keyListener

        binding.sendOtpButton.setOnClickListener {
            sendOtp()
        }
        binding.verifyOtpButton.setOnClickListener {
            if (otpEnabled) {
                verifyOtp()
            } else {
                continueWithoutOtp()
            }
        }

        binding.userPhoneInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.userPhoneInputLayout.error = null
                refreshActionState()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.otpCodeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.otpCodeInputLayout.error = null
                refreshActionState()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val initialReferralCode = referralCode.ifBlank { storedContext.tenantName }
        if (initialReferralCode.isNotBlank()) {
            applyReferralCode(initialReferralCode, lockField = true)
        }

        configureOtpUi()
        startCooldownIfNeeded()
        refreshActionState()
        bootstrapTenantIfPossible()
    }

    private fun applyWindowInsets() {
        val toolbarTopPadding = binding.toolbar.paddingTop
        val scrollLeftPadding = binding.contentScrollView.paddingLeft
        val scrollTopPadding = binding.contentScrollView.paddingTop
        val scrollRightPadding = binding.contentScrollView.paddingRight
        val scrollBottomPadding = binding.contentScrollView.paddingBottom

        binding.contentScrollView.clipToPadding = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = toolbarTopPadding + systemBars.top)
            binding.contentScrollView.updatePadding(
                left = scrollLeftPadding,
                top = scrollTopPadding,
                right = scrollRightPadding,
                bottom = scrollBottomPadding + systemBars.bottom + dpToPx(24),
            )
            insets
        }

        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        super.onDestroy()
    }

    private fun sendOtp() {
        val phone = binding.userPhoneInput.text?.toString()?.trim().orEmpty()
        referralCode = binding.referralCodeInput.text?.toString()?.trim().orEmpty()
        if (phone.isBlank()) {
            binding.userPhoneInputLayout.error = getString(R.string.error_enter_user_phone)
            Toast.makeText(this, getString(R.string.error_enter_user_phone), Toast.LENGTH_SHORT).show()
            return
        }

        if (remainingCooldownMs() > 0) {
            refreshCooldownText(remainingCooldownMs())
            return
        }

        val tenantQrToken = getTenantQrTokenOrShowError() ?: return
        busy = true
        refreshActionState()
        binding.statusText.text = getString(R.string.status_sending_otp)

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    syncService.sendPhoneOtp(
                        baseUrl = BuildConfig.APP_BASE_URL,
                        tenantQrToken = tenantQrToken,
                        phone = phone,
                        referralCode = referralCode
                    )
                }

                setLastOtpSentAt(System.currentTimeMillis())
                startCooldownIfNeeded()
                binding.statusText.text = getString(R.string.status_otp_sent, result.phone)
                Toast.makeText(this@VerifyPhoneActivity, getString(R.string.toast_otp_sent), Toast.LENGTH_SHORT).show()
                binding.otpCodeInput.requestFocus()
                binding.otpCodeInput.post {
                    binding.otpCodeInput.requestFocus()
                    binding.otpCodeInput.setSelection(binding.otpCodeInput.text?.length ?: 0)
                }
            } catch (e: Exception) {
                val errorMessage = e.message ?: "Unknown error"
                binding.statusText.text = getString(R.string.status_otp_failed, errorMessage)
                Toast.makeText(this@VerifyPhoneActivity, errorMessage, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                refreshActionState()
            }
        }
    }

    private fun verifyOtp() {
        val phone = binding.userPhoneInput.text?.toString()?.trim().orEmpty()
        val code = binding.otpCodeInput.text?.toString()?.trim().orEmpty()
        referralCode = binding.referralCodeInput.text?.toString()?.trim().orEmpty()
        if (phone.isBlank()) {
            binding.userPhoneInputLayout.error = getString(R.string.error_enter_user_phone)
            Toast.makeText(this, getString(R.string.error_enter_user_phone), Toast.LENGTH_SHORT).show()
            return
        }
        if (code.isBlank()) {
            binding.otpCodeInputLayout.error = getString(R.string.error_enter_otp_code)
            Toast.makeText(this, getString(R.string.error_enter_otp_code), Toast.LENGTH_SHORT).show()
            return
        }

        val tenantQrToken = getTenantQrTokenOrShowError() ?: return
        val deviceId = obtainDeviceId()
        val deviceInfo = obtainDeviceInfo()
        busy = true
        refreshActionState()
        binding.statusText.text = getString(R.string.status_verifying_otp)

        lifecycleScope.launch {
            try {
                val verified = withContext(Dispatchers.IO) {
                    syncService.verifyPhoneOtp(
                        baseUrl = BuildConfig.APP_BASE_URL,
                        tenantQrToken = tenantQrToken,
                        phone = phone,
                        code = code,
                        referralCode = referralCode
                    )
                }

                binding.statusText.text = getString(R.string.status_registering_user)

                val registered = withContext(Dispatchers.IO) {
                    syncService.registerVerifiedUser(
                        baseUrl = BuildConfig.APP_BASE_URL,
                        tenantQrToken = tenantQrToken,
                        phone = verified.phone,
                        deviceId = deviceId,
                        deviceInfo = deviceInfo,
                        referralCode = referralCode
                    )
                }

                binding.statusText.text = getString(R.string.status_phone_verified)
                Toast.makeText(this@VerifyPhoneActivity, getString(R.string.toast_phone_verified), Toast.LENGTH_SHORT).show()
                startActivity(
                    MainActivity.createIntent(
                        context = this@VerifyPhoneActivity,
                        userId = registered.userId,
                        verifiedPhone = registered.phone,
                        qrToken = tenantQrToken,
                        tenantCode = tenantCode,
                        referralCode = referralCode
                    )
                )
                finish()
            } catch (e: Exception) {
                val errorMessage = e.message ?: "Unknown error"
                binding.statusText.text = getString(R.string.status_otp_failed, errorMessage)
                Toast.makeText(this@VerifyPhoneActivity, errorMessage, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                refreshActionState()
            }
        }
    }

    private fun continueWithoutOtp() {
        val phone = binding.userPhoneInput.text?.toString()?.trim().orEmpty()
        referralCode = binding.referralCodeInput.text?.toString()?.trim().orEmpty()
        if (phone.isBlank()) {
            binding.userPhoneInputLayout.error = getString(R.string.error_enter_user_phone)
            Toast.makeText(this, getString(R.string.error_enter_user_phone), Toast.LENGTH_SHORT).show()
            return
        }

        val tenantQrToken = getTenantQrTokenOrShowError() ?: return
        val deviceId = obtainDeviceId()
        val deviceInfo = obtainDeviceInfo()
        busy = true
        refreshActionState()
        binding.statusText.text = getString(R.string.status_registering_user)

        lifecycleScope.launch {
            try {
                val registered = withContext(Dispatchers.IO) {
                    syncService.registerVerifiedUser(
                        baseUrl = BuildConfig.APP_BASE_URL,
                        tenantQrToken = tenantQrToken,
                        phone = phone,
                        deviceId = deviceId,
                        deviceInfo = deviceInfo,
                        referralCode = referralCode
                    )
                }

                binding.statusText.text = getString(R.string.status_phone_verified)
                Toast.makeText(this@VerifyPhoneActivity, getString(R.string.toast_phone_verified), Toast.LENGTH_SHORT).show()
                startActivity(
                    MainActivity.createIntent(
                        context = this@VerifyPhoneActivity,
                        userId = registered.userId,
                        verifiedPhone = registered.phone,
                        qrToken = tenantQrToken,
                        tenantCode = tenantCode,
                        referralCode = referralCode
                    )
                )
                finish()
            } catch (e: Exception) {
                val errorMessage = e.message ?: "Unknown error"
                binding.statusText.text = getString(R.string.status_otp_failed, errorMessage)
                Toast.makeText(this@VerifyPhoneActivity, errorMessage, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
                refreshActionState()
            }
        }
    }

    private fun refreshActionState() {
        val hasPhone = binding.userPhoneInput.text?.toString()?.trim().orEmpty().isNotBlank()
        val hasOtp = binding.otpCodeInput.text?.toString()?.trim().orEmpty().isNotBlank()
        val interactionsBlocked = busy || forceUpdateRequired
        binding.sendOtpButton.isEnabled = otpEnabled && hasPhone && !interactionsBlocked && remainingCooldownMs() <= 0
        binding.verifyOtpButton.isEnabled = if (otpEnabled) {
            hasPhone && hasOtp && !interactionsBlocked
        } else {
            hasPhone && !interactionsBlocked
        }
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun configureOtpUi() {
        if (otpEnabled) {
            binding.verifyPhoneIntroText.text = getString(R.string.verify_phone_intro)
            binding.sendOtpButton.visibility = View.VISIBLE
            binding.cooldownText.visibility = View.VISIBLE
            binding.otpCodeInputLayout.visibility = View.VISIBLE
            binding.verifyOtpButton.text = getString(R.string.verify_otp)
            return
        }

        binding.verifyPhoneIntroText.text = getString(R.string.verify_phone_intro_no_otp)
        binding.sendOtpButton.visibility = View.GONE
        binding.cooldownText.visibility = View.GONE
        binding.otpCodeInputLayout.visibility = View.GONE
        binding.verifyOtpButton.text = getString(R.string.continue_without_otp)
    }

    private fun startCooldownIfNeeded() {
        countdownTimer?.cancel()
        val remaining = remainingCooldownMs()
        if (remaining <= 0) {
            binding.cooldownText.text = getString(R.string.send_otp_ready)
            refreshActionState()
            return
        }

        countdownTimer = object : CountDownTimer(remaining, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                refreshCooldownText(millisUntilFinished)
                refreshActionState()
            }

            override fun onFinish() {
                binding.cooldownText.text = getString(R.string.send_otp_ready)
                refreshActionState()
            }
        }.start()
    }

    private fun refreshCooldownText(remainingMs: Long) {
        val totalSeconds = (remainingMs + 999) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        binding.cooldownText.text = getString(R.string.send_otp_cooldown, minutes, seconds)
    }

    private fun remainingCooldownMs(): Long {
        val lastSentAt = getPrefs().getLong(KEY_LAST_OTP_SENT_AT, 0L)
        if (lastSentAt <= 0L) return 0L
        return (lastSentAt + OTP_COOLDOWN_MS - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun setLastOtpSentAt(timestamp: Long) {
        getPrefs().edit().putLong(KEY_LAST_OTP_SENT_AT, timestamp).apply()
    }

    private fun getPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun bootstrapTenantIfPossible() {
        lifecycleScope.launch {
            try {
                val bootstrap = withContext(Dispatchers.IO) {
                    syncService.bootstrapTenant(
                        baseUrl = BuildConfig.APP_BASE_URL,
                        tenantQrToken = qrToken
                    )
                }
                if (bootstrap.tenantCode.isNotBlank()) {
                    tenantCode = bootstrap.tenantCode
                }
                tenantLaunchManager.saveLaunchSelection(
                    qrToken = qrToken,
                    tenantCode = tenantCode,
                    referralCode = referralCode
                )
                tenantLaunchManager.updateTenantName(
                    bootstrap.tenantName.ifBlank { bootstrap.appDisplayName }
                )
                val resolvedReferralCode = referralCode.ifBlank {
                    bootstrap.tenantName.ifBlank { bootstrap.appDisplayName.ifBlank { bootstrap.tenantCode } }
                }
                if (resolvedReferralCode.isNotBlank()) {
                    tenantLaunchManager.updateReferralCode(resolvedReferralCode)
                    applyReferralCode(resolvedReferralCode, lockField = true)
                }
                maybeShowUpdateDialog(bootstrap)
                if (qrToken.isBlank()) {
                    binding.statusText.text = getString(R.string.status_missing_tenant_qr)
                    return@launch
                }
                if (binding.statusText.text.isNullOrBlank()) {
                    binding.statusText.text = getString(
                        R.string.status_tenant_selected,
                        bootstrap.tenantName.ifBlank { bootstrap.appDisplayName.ifBlank { bootstrap.tenantCode } }
                    )
                }
            } catch (_: Exception) {
                binding.statusText.text = getString(R.string.status_missing_tenant_qr)
            }
        }
    }

    private fun getTenantQrTokenOrShowError(): String? {
        val token = qrToken.ifBlank { tenantLaunchManager.getContext().qrToken }
        val currentReferralCode = binding.referralCodeInput.text?.toString()?.trim().orEmpty()
        if (token.isBlank() && currentReferralCode.isBlank()) {
            binding.statusText.text = getString(R.string.status_missing_tenant_qr)
            showMissingTenantQrDialog()
            return null
        }
        return token
    }

    private fun showMissingTenantQrDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.missing_tenant_qr_title)
            .setMessage(R.string.missing_tenant_qr_message)
            .setPositiveButton(R.string.missing_tenant_qr_button, null)
            .show()
    }

    private fun obtainDeviceId(): String {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return if (deviceId.isNullOrBlank()) "unknown_device" else deviceId
    }

    private fun obtainDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            deviceName = Build.DEVICE.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            androidSdkInt = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME.orEmpty()
        )
    }

    private fun dpToPx(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun applyReferralCode(code: String, lockField: Boolean) {
        val normalizedCode = code.trim()
        if (normalizedCode.isBlank()) {
            return
        }

        referralCode = normalizedCode
        if (binding.referralCodeInput.text?.toString()?.trim().orEmpty() != normalizedCode) {
            binding.referralCodeInput.setText(normalizedCode)
        }
        setReferralFieldLocked(lockField)
    }

    private fun setReferralFieldLocked(locked: Boolean) {
        referralCodeLocked = locked
        binding.referralCodeInput.keyListener = if (locked) null else editableReferralKeyListener
        binding.referralCodeInput.isFocusable = !locked
        binding.referralCodeInput.isFocusableInTouchMode = !locked
        binding.referralCodeInput.isCursorVisible = !locked
        binding.referralCodeInput.isLongClickable = !locked
    }

    private fun maybeShowUpdateDialog(bootstrap: AppBootstrapResult) {
        val requirement = AppUpdateManager.resolveUpdateRequirement(this, bootstrap) ?: return
        if (updateDialogShown) {
            return
        }

        updateDialogShown = true
        forceUpdateRequired = requirement.forceUpdate
        refreshActionState()
        AppUpdateManager.showUpdateDialog(this, requirement) {
            forceUpdateRequired = false
            refreshActionState()
        }
    }

}
