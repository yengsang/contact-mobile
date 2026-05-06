package com.memberreward.contact

import android.content.Context
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.memberreward.contact.BuildConfig
import com.memberreward.contact.databinding.ActivityVerifyPhoneBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VerifyPhoneActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.yengsang.com"
        private const val OTP_COOLDOWN_MS = 5 * 60 * 1000L
        private const val PREFS_NAME = "member_reward_verification"
        private const val KEY_LAST_OTP_SENT_AT = "last_otp_sent_at"
    }

    private lateinit var binding: ActivityVerifyPhoneBinding
    private val syncService = StrapiSyncService()
    private var countdownTimer: CountDownTimer? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVerifyPhoneBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.sendOtpButton.setOnClickListener {
            sendOtp()
        }
        binding.verifyOtpButton.setOnClickListener {
            verifyOtp()
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

        startCooldownIfNeeded()
        refreshActionState()
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        super.onDestroy()
    }

    private fun sendOtp() {
        val phone = binding.userPhoneInput.text?.toString()?.trim().orEmpty()
        if (phone.isBlank()) {
            binding.userPhoneInputLayout.error = getString(R.string.error_enter_user_phone)
            Toast.makeText(this, getString(R.string.error_enter_user_phone), Toast.LENGTH_SHORT).show()
            return
        }

        if (remainingCooldownMs() > 0) {
            refreshCooldownText(remainingCooldownMs())
            return
        }

        val appApiKey = getAppApiKeyOrShowError() ?: return
        busy = true
        refreshActionState()
        binding.statusText.text = getString(R.string.status_sending_otp)

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    syncService.sendPhoneOtp(
                        baseUrl = DEFAULT_BASE_URL,
                        appApiKey = appApiKey,
                        phone = phone
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

        val appApiKey = getAppApiKeyOrShowError() ?: return
        val deviceId = obtainDeviceId()
        busy = true
        refreshActionState()
        binding.statusText.text = getString(R.string.status_verifying_otp)

        lifecycleScope.launch {
            try {
                val verified = withContext(Dispatchers.IO) {
                    syncService.verifyPhoneOtp(
                        baseUrl = DEFAULT_BASE_URL,
                        appApiKey = appApiKey,
                        phone = phone,
                        code = code
                    )
                }

                binding.statusText.text = getString(R.string.status_registering_user)

                val registered = withContext(Dispatchers.IO) {
                    syncService.registerVerifiedUser(
                        baseUrl = DEFAULT_BASE_URL,
                        appApiKey = appApiKey,
                        phone = verified.phone,
                        deviceId = deviceId
                    )
                }

                binding.statusText.text = getString(R.string.status_phone_verified)
                Toast.makeText(this@VerifyPhoneActivity, getString(R.string.toast_phone_verified), Toast.LENGTH_SHORT).show()
                startActivity(MainActivity.createIntent(this@VerifyPhoneActivity, registered.userId, registered.phone))
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
        binding.sendOtpButton.isEnabled = hasPhone && !busy && remainingCooldownMs() <= 0
        binding.verifyOtpButton.isEnabled = hasPhone && hasOtp && !busy
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
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

    private fun getAppApiKeyOrShowError(): String? {
        val appApiKey = BuildConfig.APP_API_KEY
        if (appApiKey.isBlank()) {
            binding.statusText.text = getString(R.string.status_missing_app_api_key)
            Toast.makeText(this, getString(R.string.toast_missing_app_api_key), Toast.LENGTH_LONG).show()
            return null
        }
        return appApiKey
    }

    private fun obtainDeviceId(): String {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return if (deviceId.isNullOrBlank()) "unknown_device" else deviceId
    }

}
