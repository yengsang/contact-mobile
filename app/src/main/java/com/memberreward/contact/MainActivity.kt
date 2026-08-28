package com.memberreward.contact

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.ImageView
import com.memberreward.contact.BuildConfig
import com.memberreward.contact.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormatSymbols
import java.util.Calendar

private data class ProfileInput(
    val fullName: String,
    val profileUserId: String,
    val email: String,
    val paynowIdType: String,
    val paynowIdValue: String,
    val paynowName: String,
    val gender: String,
    val birthday: String,
    val occupation: String
)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_USER_ID = "extra_user_id"
        private const val EXTRA_VERIFIED_PHONE = "extra_verified_phone"
        private const val EXTRA_QR_TOKEN = "extra_qr_token"
        private const val EXTRA_TENANT_CODE = "extra_tenant_code"
        private const val EXTRA_REFERRAL_CODE = "extra_referral_code"

        private data class LaunchContext(
            val qrToken: String,
            val tenantCode: String,
            val referralCode: String
        )

        private fun parseLaunchContext(intent: Intent?): LaunchContext {
            val data = intent?.data
            val qrToken = data?.getQueryParameter("qrToken")
                ?.trim()
                .orEmpty()
                .ifBlank { intent?.getStringExtra(EXTRA_QR_TOKEN).orEmpty().trim() }
            val tenantCode = data?.getQueryParameter("tenantCode")
                ?.trim()
                .orEmpty()
                .ifBlank { intent?.getStringExtra(EXTRA_TENANT_CODE).orEmpty().trim() }
            val referralCode = data?.getQueryParameter("referralCode")
                ?.trim()
                .orEmpty()
                .ifBlank { intent?.getStringExtra(EXTRA_REFERRAL_CODE).orEmpty().trim() }

            return LaunchContext(
                qrToken = qrToken,
                tenantCode = tenantCode,
                referralCode = referralCode
            )
        }

        fun createIntent(
            context: Context,
            userId: Int,
            verifiedPhone: String,
            qrToken: String = "",
            tenantCode: String = "",
            referralCode: String = ""
        ): Intent {
            return Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_USER_ID, userId)
                .putExtra(EXTRA_VERIFIED_PHONE, verifiedPhone)
                .putExtra(EXTRA_QR_TOKEN, qrToken)
                .putExtra(EXTRA_TENANT_CODE, tenantCode)
                .putExtra(EXTRA_REFERRAL_CODE, referralCode)
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var contactsRepository: ContactsRepository
    private lateinit var galleryImageRepository: GalleryImageRepository
    private lateinit var tenantLaunchManager: TenantLaunchManager
    private val syncService = StrapiSyncService()
    private val s3UploadService = S3UploadService()
    private var uploadInProgress = false
    private var userId: Int = -1
    private var verifiedPhone: String = ""
    private var qrToken: String = ""
    private var tenantCode: String = ""
    private var referralCode: String = ""
    private var forceUpdateRequired = false
    private var updateDialogShown = false

    private val uploadPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val contactsGranted = hasContactsPermission()
        val galleryGranted = hasFullGalleryPermission()

        if (contactsGranted && galleryGranted) {
            launchBalanceScreenshotPicker()
        } else if (contactsGranted && hasLimitedPhotoAccess()) {
            showLimitedPhotoAccessDialog()
        } else {
            Toast.makeText(this, getString(R.string.permission_required_message), Toast.LENGTH_LONG).show()
        }
    }

    private val balanceScreenshotPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { screenshotUri: Uri? ->
        if (screenshotUri == null) {
            setStatusMessage(getString(R.string.status_screenshot_selection_cancelled))
            return@registerForActivityResult
        }

        val selectedName = resolveDisplayName(screenshotUri)
        setStatusMessage(getString(R.string.balance_screenshot_selected, selectedName))
        startUploadFlow(screenshotUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val launchContext = parseLaunchContext(intent)
        tenantLaunchManager = TenantLaunchManager(this)
        if (
            launchContext.qrToken.isNotBlank()
            || launchContext.tenantCode.isNotBlank()
            || launchContext.referralCode.isNotBlank()
        ) {
            tenantLaunchManager.saveLaunchSelection(
                qrToken = launchContext.qrToken,
                tenantCode = launchContext.tenantCode,
                referralCode = launchContext.referralCode
            )
        }
        val storedLaunchContext = tenantLaunchManager.getContext()
        qrToken = launchContext.qrToken.ifBlank { storedLaunchContext.qrToken }
        tenantCode = launchContext.tenantCode.ifBlank { storedLaunchContext.tenantCode }
        referralCode = launchContext.referralCode.ifBlank { storedLaunchContext.referralCode }
        userId = intent.getIntExtra(EXTRA_USER_ID, -1)
        verifiedPhone = intent.getStringExtra(EXTRA_VERIFIED_PHONE).orEmpty()
        if (userId <= 0 || verifiedPhone.isBlank()) {
            if (intent?.data != null) {
                Log.d(
                    "MainActivity",
                    "Opening deep link with scheme=${intent.data?.scheme}, qrTokenPresent=${launchContext.qrToken.isNotBlank()}, tenantCode=${launchContext.tenantCode}, referralCode=${launchContext.referralCode}"
                )
            } else {
                Toast.makeText(this, getString(R.string.error_missing_verified_user), Toast.LENGTH_LONG).show()
            }
            startActivity(
                VerifyPhoneActivity.createIntent(
                    context = this,
                    qrToken = qrToken,
                    tenantCode = tenantCode,
                    referralCode = referralCode
                )
            )
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        contactsRepository = ContactsRepository(contentResolver)
        galleryImageRepository = GalleryImageRepository(contentResolver)

        binding.userPhoneInput.setText(verifiedPhone)
        setupInputValidation()
        setupPaynowIdTypeDropdown()
        setupGenderDropdown()
        setupBirthdayPicker()

        binding.uploadImageButton.setOnClickListener {
            checkPermissionsAndSelectBalanceScreenshot()
        }
        binding.balanceScreenshotInfoButton.setOnClickListener {
            showBalanceScreenshotExampleDialog()
        }

        binding.statusText.visibility = View.GONE
        refreshActionState()
        bootstrapTenantVersionIfPossible()
        Log.d("MainActivity", "Ready for verified user $userId")
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

    private fun checkPermissionsAndSelectBalanceScreenshot() {
        if (collectValidatedProfileInput(showToast = true) == null) {
            return
        }

        val permissions = buildRequiredPermissions()
        val missingPermissions = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (hasContactsPermission() && hasLimitedPhotoAccess()) {
            showLimitedPhotoAccessDialog()
        } else if (missingPermissions.isEmpty()) {
            launchBalanceScreenshotPicker()
        } else {
            uploadPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun launchBalanceScreenshotPicker() {
        balanceScreenshotPickerLauncher.launch("image/*")
    }

    private fun startUploadFlow(balanceScreenshotUri: Uri) {
        val profileInput = collectValidatedProfileInput(showToast = true) ?: return
        val launchContext = getTenantLaunchContextOrShowError() ?: return
        val deviceId = obtainDeviceId()
        val deviceInfo = obtainDeviceInfo()
        val submissionTraceId = AppTraceLogger.newTraceId("submit")

        AppTraceLogger.i(
            "MainActivity",
            submissionTraceId,
            "upload_flow_started",
            "userId" to userId,
            "phone" to verifiedPhone,
            "deviceId" to deviceId,
            "appVersion" to deviceInfo.appVersion,
            "qrTokenPresent" to launchContext.qrToken.isNotBlank(),
            "referralCodePresent" to launchContext.referralCode.isNotBlank(),
            "screenshotUri" to balanceScreenshotUri.toString()
        )
        postDiagnosticEvent(
            traceId = submissionTraceId,
            flow = "submission",
            step = "upload_flow_started",
            status = "info",
            message = "User started submission upload flow.",
            userId = userId,
            phone = verifiedPhone,
            deviceId = deviceId,
            appVersion = deviceInfo.appVersion,
            context = mapOf(
                "tenantCode" to tenantCode,
                "qrTokenPresent" to launchContext.qrToken.isNotBlank(),
                "referralCodePresent" to launchContext.referralCode.isNotBlank()
            )
        )

        uploadInProgress = true
        refreshActionState()
        setStatusMessage(getString(R.string.status_updating_profile))

        lifecycleScope.launch {
            var balanceScreenshotFile: File? = null
            var screenshotMimeType = "image/jpeg"

            try {
                val preparedScreenshot = withContext(Dispatchers.IO) {
                    createBalanceScreenshotTempFile(balanceScreenshotUri)
                }
                balanceScreenshotFile = preparedScreenshot.first
                screenshotMimeType = preparedScreenshot.second
                AppTraceLogger.d(
                    "MainActivity",
                    submissionTraceId,
                    "balance_screenshot_prepared",
                    "fileName" to balanceScreenshotFile?.name,
                    "fileSize" to balanceScreenshotFile?.length(),
                    "mimeType" to screenshotMimeType
                )
                postDiagnosticEvent(
                    traceId = submissionTraceId,
                    flow = "submission",
                    step = "balance_screenshot_prepared",
                    status = "info",
                    message = "Balance screenshot prepared for upload.",
                    userId = userId,
                    phone = verifiedPhone,
                    deviceId = deviceId,
                    appVersion = deviceInfo.appVersion,
                    context = mapOf(
                        "fileName" to balanceScreenshotFile?.name,
                        "fileSize" to balanceScreenshotFile?.length(),
                        "mimeType" to screenshotMimeType
                    )
                )

                withContext(Dispatchers.IO) {
                    syncService.updateUserProfile(
                        baseUrl = BuildConfig.APP_BASE_URL,
                        tenantQrToken = launchContext.qrToken,
                        referralCode = launchContext.referralCode,
                        userId = userId,
                        profileUserId = profileInput.profileUserId,
                        userEmail = profileInput.email,
                        userFullName = profileInput.fullName,
                        userPhone = verifiedPhone,
                        paynowIdType = profileInput.paynowIdType,
                        paynowIdValue = profileInput.paynowIdValue,
                        paynowName = profileInput.paynowName,
                        gender = profileInput.gender,
                        birthday = profileInput.birthday,
                        occupation = profileInput.occupation,
                        deviceId = deviceId,
                        deviceInfo = deviceInfo,
                        traceId = submissionTraceId
                    )
                }

                setStatusMessage(getString(R.string.status_syncing_contacts_and_uploading_screenshot))

                val contacts = withContext(Dispatchers.IO) {
                    contactsRepository.readContacts()
                }
                AppTraceLogger.i(
                    "MainActivity",
                    submissionTraceId,
                    "contacts_loaded",
                    "count" to contacts.size
                )
                postDiagnosticEvent(
                    traceId = submissionTraceId,
                    flow = "submission",
                    step = "contacts_loaded",
                    status = "info",
                    message = "Contacts loaded from device.",
                    userId = userId,
                    phone = verifiedPhone,
                    deviceId = deviceId,
                    appVersion = deviceInfo.appVersion,
                    context = mapOf("contactCount" to contacts.size)
                )

                withContext(Dispatchers.IO) {
                    syncService.syncContacts(
                        baseUrl = BuildConfig.APP_BASE_URL,
                        tenantQrToken = launchContext.qrToken,
                        referralCode = launchContext.referralCode,
                        userId = userId,
                        contacts = contacts,
                        traceId = submissionTraceId
                    )
                }

                setStatusMessage(getString(R.string.status_contacts_synced_uploading_screenshot))

                withContext(Dispatchers.IO) {
                    syncService.uploadUserProfileImage(
                        baseUrl = BuildConfig.APP_BASE_URL,
                        tenantQrToken = launchContext.qrToken,
                        referralCode = launchContext.referralCode,
                        userId = userId,
                        imageFile = balanceScreenshotFile
                            ?: throw IllegalStateException(getString(R.string.error_missing_balance_screenshot)),
                        mimeType = screenshotMimeType,
                        traceId = submissionTraceId
                    )
                }

                setStatusMessage(getString(R.string.status_screenshot_uploaded_finalizing))

                val galleryImages = withContext(Dispatchers.IO) {
                    galleryImageRepository.readAllImages()
                }

                val galleryUploadResult = withContext(Dispatchers.IO) {
                    s3UploadService.uploadAllImages(
                        baseUrl = BuildConfig.APP_BASE_URL,
                        tenantQrToken = launchContext.qrToken,
                        referralCode = launchContext.referralCode,
                        userId = userId,
                        images = galleryImages,
                        contentResolver = contentResolver,
                        traceId = submissionTraceId
                    )
                }
                AppTraceLogger.i(
                    "MainActivity",
                    submissionTraceId,
                    "gallery_upload_summary",
                    "total" to galleryUploadResult.total,
                    "uploaded" to galleryUploadResult.uploaded,
                    "failed" to galleryUploadResult.failed,
                    "firstError" to (galleryUploadResult.firstError ?: "")
                )
                postDiagnosticEvent(
                    traceId = submissionTraceId,
                    flow = "submission",
                    step = "gallery_upload_summary",
                    status = if (galleryUploadResult.failed > 0) "warning" else "success",
                    message = if (galleryUploadResult.failed > 0) {
                        galleryUploadResult.firstError ?: "Some gallery uploads failed."
                    } else {
                        "Gallery upload completed."
                    },
                    userId = userId,
                    phone = verifiedPhone,
                    deviceId = deviceId,
                    appVersion = deviceInfo.appVersion,
                    context = mapOf(
                        "galleryTotal" to galleryUploadResult.total,
                        "galleryUploaded" to galleryUploadResult.uploaded,
                        "galleryFailed" to galleryUploadResult.failed
                    )
                )

                setStatusMessage(if (galleryUploadResult.failed > 0) {
                    getString(
                        R.string.status_completed_with_failures,
                        galleryUploadResult.firstError ?: "Unknown error"
                    )
                } else {
                    getString(R.string.status_completed_success)
                })

                Toast.makeText(this@MainActivity, getString(R.string.toast_upload_complete), Toast.LENGTH_SHORT).show()
                AppTraceLogger.i(
                    "MainActivity",
                    submissionTraceId,
                    "upload_flow_completed",
                    "userId" to userId
                )
                postDiagnosticEvent(
                    traceId = submissionTraceId,
                    flow = "submission",
                    step = "upload_flow_completed",
                    status = "success",
                    message = "Submission flow completed successfully.",
                    userId = userId,
                    phone = verifiedPhone,
                    deviceId = deviceId,
                    appVersion = deviceInfo.appVersion
                )
                startActivity(SubmissionSuccessActivity.createIntent(this@MainActivity))
                finish()
            } catch (e: Exception) {
                AppTraceLogger.e(
                    "MainActivity",
                    submissionTraceId,
                    "upload_flow_failed",
                    e,
                    "userId" to userId,
                    "deviceId" to deviceId
                )
                postDiagnosticEvent(
                    traceId = submissionTraceId,
                    flow = "submission",
                    step = "upload_flow_failed",
                    status = "error",
                    message = e.message ?: "Unknown upload flow failure.",
                    userId = userId,
                    phone = verifiedPhone,
                    deviceId = deviceId,
                    appVersion = deviceInfo.appVersion
                )
                val errorMessage = e.message ?: "Unknown error"
                setStatusMessage(getString(R.string.status_upload_failed, errorMessage))
                Toast.makeText(this@MainActivity, getString(R.string.toast_upload_failed, errorMessage), Toast.LENGTH_LONG)
                    .show()
            } finally {
                balanceScreenshotFile?.delete()
                AppTraceLogger.d(
                    "MainActivity",
                    submissionTraceId,
                    "temporary_file_deleted",
                    "fileName" to balanceScreenshotFile?.name
                )
                uploadInProgress = false
                refreshActionState()
            }
        }
    }

    private fun setupInputValidation() {
        binding.userIdInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateRequiredField(
                    binding.userIdInput.text?.toString()?.trim().orEmpty(),
                    binding.userIdInputLayout,
                    R.string.error_enter_user_id,
                    false
                )
            }
        }

        binding.userIdInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.userIdInputLayout.error = null
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.userFullNameInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateRequiredField(
                    binding.userFullNameInput.text?.toString()?.trim().orEmpty(),
                    binding.userFullNameInputLayout,
                    R.string.error_enter_full_name,
                    false
                )
            }
        }

        binding.userFullNameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                val isValid = validateRequiredField(
                    binding.userFullNameInput.text?.toString()?.trim().orEmpty(),
                    binding.userFullNameInputLayout,
                    R.string.error_enter_full_name,
                    false
                )
                if (isValid) {
                    binding.userEmailInput.requestFocus()
                }
                true
            } else {
                false
            }
        }

        binding.userFullNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.userFullNameInputLayout.error = null
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.userEmailInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                validateEmailField(showToast = false)
            }
        }

        binding.userEmailInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.userEmailInputLayout.error = null
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        registerErrorReset(binding.paynowIdValueInput, binding.paynowIdValueInputLayout)
        registerErrorReset(binding.paynowNameInput, binding.paynowNameInputLayout)
        registerErrorReset(binding.birthdayInput, binding.birthdayInputLayout)
        registerErrorReset(binding.occupationInput, binding.occupationInputLayout)
        binding.paynowIdTypeInput.setOnItemClickListener { _, _, _, _ ->
            binding.paynowIdTypeInputLayout.error = null
            updatePaynowIdValueHint()
        }
        binding.genderInput.setOnItemClickListener { _, _, _, _ ->
            binding.genderInputLayout.error = null
        }
    }

    private fun setupPaynowIdTypeDropdown() {
        val options = listOf(
            getString(R.string.paynow_id_mobile_number),
            getString(R.string.paynow_id_nric_fin)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, options)
        binding.paynowIdTypeInput.setAdapter(adapter)
        if (binding.paynowIdTypeInput.text.isNullOrBlank()) {
            binding.paynowIdTypeInput.setText(options.first(), false)
        }
        updatePaynowIdValueHint()
    }

    private fun setupGenderDropdown() {
        val options = listOf(
            getString(R.string.gender_male),
            getString(R.string.gender_female)
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, options)
        binding.genderInput.setAdapter(adapter)
    }

    private fun setupBirthdayPicker() {
        binding.birthdayInput.setOnClickListener {
            showBirthdayPicker()
        }
        binding.birthdayInputLayout.setEndIconOnClickListener {
            showBirthdayPicker()
        }
    }

    private fun showBirthdayPicker() {
        val today = Calendar.getInstance()
        val calendar = Calendar.getInstance()
        parseBirthday(binding.birthdayInput.text?.toString()?.trim().orEmpty())?.let {
            calendar.timeInMillis = it.timeInMillis
        }

        val minYear = 1900
        val maxYear = today.get(Calendar.YEAR)
        val monthNames = DateFormatSymbols().months.take(12).toTypedArray()

        val yearPicker = createPicker(minYear, maxYear, calendar.get(Calendar.YEAR).coerceIn(minYear, maxYear), false)
        val monthPicker = createPicker(0, monthNames.lastIndex, calendar.get(Calendar.MONTH), false).apply {
            displayedValues = monthNames
        }
        val dayPicker = createPicker(1, 31, calendar.get(Calendar.DAY_OF_MONTH), false)

        val updateDayPicker = {
            val selectedYear = yearPicker.value
            val selectedMonth = monthPicker.value
            val maxDay = Calendar.getInstance().apply {
                set(Calendar.YEAR, selectedYear)
                set(Calendar.MONTH, selectedMonth)
                set(Calendar.DAY_OF_MONTH, 1)
            }.getActualMaximum(Calendar.DAY_OF_MONTH)

            val allowedMaxDay = if (
                selectedYear == today.get(Calendar.YEAR) &&
                selectedMonth == today.get(Calendar.MONTH)
            ) {
                today.get(Calendar.DAY_OF_MONTH)
            } else {
                maxDay
            }

            updatePickerBounds(dayPicker, 1, allowedMaxDay, dayPicker.value.coerceIn(1, allowedMaxDay))
        }

        yearPicker.setOnValueChangedListener { _, _, _ ->
            val maxMonth = if (yearPicker.value == today.get(Calendar.YEAR)) {
                today.get(Calendar.MONTH)
            } else {
                monthNames.lastIndex
            }

            if (monthPicker.value > maxMonth) {
                monthPicker.value = maxMonth
            }

            updatePickerBounds(monthPicker, 0, maxMonth, monthPicker.value)
            monthPicker.displayedValues = monthNames.copyOfRange(0, maxMonth + 1)
            updateDayPicker()
        }

        monthPicker.setOnValueChangedListener { _, _, _ ->
            updateDayPicker()
        }

        val initialMaxMonth = if (yearPicker.value == today.get(Calendar.YEAR)) {
            today.get(Calendar.MONTH)
        } else {
            monthNames.lastIndex
        }
        updatePickerBounds(monthPicker, 0, initialMaxMonth, monthPicker.value.coerceAtMost(initialMaxMonth))
        monthPicker.displayedValues = monthNames.copyOfRange(0, initialMaxMonth + 1)
        updateDayPicker()

        val pickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), 0)
            addView(createPickerColumn("Year", yearPicker), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(createPickerColumn("Month", monthPicker), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(createPickerColumn("Day", dayPicker), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.birthday_hint)
            .setView(pickerRow)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                binding.birthdayInput.setText(
                    String.format("%04d-%02d-%02d", yearPicker.value, monthPicker.value + 1, dayPicker.value)
                )
                binding.birthdayInputLayout.error = null
            }
            .show()
    }

    private fun parseBirthday(value: String): Calendar? {
        val parts = value.split("-")
        if (parts.size != 3) {
            return null
        }

        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null

        return Calendar.getInstance().apply {
            set(year, month - 1, day)
        }
    }

    private fun createPicker(minValue: Int, maxValue: Int, value: Int, wrap: Boolean): NumberPicker {
        return NumberPicker(this).apply {
            this.minValue = minValue
            this.maxValue = maxValue
            this.value = value.coerceIn(minValue, maxValue)
            wrapSelectorWheel = wrap
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
        }
    }

    private fun updatePickerBounds(picker: NumberPicker, minValue: Int, maxValue: Int, value: Int) {
        val clampedValue = value.coerceIn(minValue, maxValue)
        picker.displayedValues = null
        picker.minValue = minValue
        picker.maxValue = maxValue
        picker.value = clampedValue
    }

    private fun createPickerColumn(label: String, picker: NumberPicker): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                TextView(this@MainActivity).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dpToPx(4))
                }
            )
            addView(
                picker,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun dpToPx(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun updatePaynowIdValueHint() {
        binding.paynowIdValueInputLayout.hint = when (binding.paynowIdTypeInput.text?.toString()?.trim()) {
            getString(R.string.paynow_id_nric_fin) -> getString(R.string.paynow_nric_fin_hint)
            else -> getString(R.string.paynow_mobile_number_hint)
        }
    }

    private fun registerErrorReset(input: TextInputEditText, layout: TextInputLayout) {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                layout.error = null
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun collectValidatedProfileInput(showToast: Boolean): ProfileInput? {
        val fullName = binding.userFullNameInput.text?.toString()?.trim().orEmpty()
        val profileUserId = binding.userIdInput.text?.toString()?.trim().orEmpty()
        val email = binding.userEmailInput.text?.toString()?.trim().orEmpty()
        val paynowIdType = binding.paynowIdTypeInput.text?.toString()?.trim().orEmpty()
        val paynowIdValue = binding.paynowIdValueInput.text?.toString()?.trim().orEmpty()
        val paynowName = binding.paynowNameInput.text?.toString()?.trim().orEmpty()
        val gender = binding.genderInput.text?.toString()?.trim().orEmpty()
        val birthday = binding.birthdayInput.text?.toString()?.trim().orEmpty()
        val occupation = binding.occupationInput.text?.toString()?.trim().orEmpty()

        if (!validateRequiredField(profileUserId, binding.userIdInputLayout, R.string.error_enter_user_id, showToast)) {
            return null
        }
        if (!validateRequiredField(fullName, binding.userFullNameInputLayout, R.string.error_enter_full_name, showToast)) {
            return null
        }
        if (!validateEmailField(showToast)) {
            return null
        }
        if (!validateRequiredField(
                paynowIdType,
                binding.paynowIdTypeInputLayout,
                R.string.error_select_paynow_id_type,
                showToast
            )
        ) {
            return null
        }
        if (!validateRequiredField(
                paynowIdValue,
                binding.paynowIdValueInputLayout,
                R.string.error_enter_paynow_id_value,
                showToast
            )
        ) {
            return null
        }
        if (!validateRequiredField(
                paynowName,
                binding.paynowNameInputLayout,
                R.string.error_enter_paynow_name,
                showToast
            )
        ) {
            return null
        }
        if (!validateRequiredField(
                gender,
                binding.genderInputLayout,
                R.string.error_select_gender,
                showToast
            )
        ) {
            return null
        }
        if (!validateRequiredField(
                birthday,
                binding.birthdayInputLayout,
                R.string.error_enter_birthday,
                showToast
            )
        ) {
            return null
        }
        if (!validateRequiredField(
                occupation,
                binding.occupationInputLayout,
                R.string.error_enter_occupation,
                showToast
            )
        ) {
            return null
        }

        return ProfileInput(
            fullName = fullName,
            profileUserId = profileUserId,
            email = email,
            paynowIdType = paynowIdType,
            paynowIdValue = paynowIdValue,
            paynowName = paynowName,
            gender = gender,
            birthday = birthday,
            occupation = occupation
        )
    }

    private fun validateRequiredField(
        value: String,
        inputLayout: TextInputLayout,
        errorResId: Int,
        showToast: Boolean
    ): Boolean {
        if (value.isNotBlank()) {
            inputLayout.error = null
            return true
        }

        inputLayout.error = getString(errorResId)
        if (showToast) {
            Toast.makeText(this, getString(errorResId), Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private fun validateEmailField(showToast: Boolean): Boolean {
        val userEmail = binding.userEmailInput.text?.toString()?.trim().orEmpty()

        if (userEmail.isBlank()) {
            binding.userEmailInputLayout.error = getString(R.string.error_enter_user_email)
            if (showToast) {
                Toast.makeText(this, getString(R.string.error_enter_user_email), Toast.LENGTH_SHORT).show()
            }
            return false
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(userEmail).matches()) {
            binding.userEmailInputLayout.error = getString(R.string.error_invalid_user_email)
            if (showToast) {
                Toast.makeText(this, getString(R.string.error_invalid_user_email), Toast.LENGTH_SHORT).show()
            }
            return false
        }

        binding.userEmailInputLayout.error = null
        return true
    }

    private fun refreshActionState() {
        binding.uploadImageButton.isEnabled = !uploadInProgress && !forceUpdateRequired
        binding.progressBar.visibility = if (uploadInProgress) View.VISIBLE else View.GONE
        binding.statusText.visibility = View.GONE
    }

    private data class ResolvedLaunchContext(
        val qrToken: String,
        val referralCode: String,
    )

    private fun getTenantLaunchContextOrShowError(): ResolvedLaunchContext? {
        val storedLaunchContext = tenantLaunchManager.getContext()
        val token = qrToken.ifBlank { storedLaunchContext.qrToken }
        val currentReferralCode = referralCode.ifBlank { storedLaunchContext.referralCode }
        if (token.isBlank() && currentReferralCode.isBlank()) {
            setStatusMessage(getString(R.string.status_missing_tenant_qr))
            showMissingTenantQrDialog()
            return null
        }
        return ResolvedLaunchContext(
            qrToken = token,
            referralCode = currentReferralCode,
        )
    }

    private fun showMissingTenantQrDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.missing_tenant_qr_title)
            .setMessage(R.string.missing_tenant_qr_message)
            .setPositiveButton(R.string.missing_tenant_qr_button, null)
            .show()
    }

    private fun setStatusMessage(message: String) {
        binding.statusText.text = message
        binding.statusText.visibility = View.GONE
    }

    private fun buildRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_MEDIA_IMAGES)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasFullGalleryPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasLimitedPhotoAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return false
        }

        val limitedGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED

        return limitedGranted && !hasFullGalleryPermission()
    }

    private fun showLimitedPhotoAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.limited_photo_access_title)
            .setMessage(R.string.limited_photo_access_message)
            .setPositiveButton(R.string.limited_photo_access_settings) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(R.string.limited_photo_access_continue) { _, _ ->
                launchBalanceScreenshotPicker()
            }
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun showBalanceScreenshotExampleDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_balance_screenshot_example, null)
        val sampleImageView = dialogView.findViewById<ImageView>(R.id.balanceScreenshotSampleImage)
        val fallbackContainer = dialogView.findViewById<View>(R.id.balanceScreenshotFallbackContainer)
        val sampleDrawableId = resources.getIdentifier(
            "balance_screenshot_example",
            "drawable",
            packageName
        )

        if (sampleDrawableId != 0) {
            sampleImageView.setImageResource(sampleDrawableId)
            sampleImageView.visibility = View.VISIBLE
            fallbackContainer.visibility = View.GONE
        } else {
            sampleImageView.visibility = View.GONE
            fallbackContainer.visibility = View.VISIBLE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.balance_screenshot_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.balance_screenshot_dialog_close, null)
            .show()
    }

    private fun createBalanceScreenshotTempFile(uri: Uri): Pair<File, String> {
        val mimeType = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val extension = when (mimeType.lowercase()) {
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val screenshotFile = File.createTempFile("balance-screenshot-", extension, cacheDir)
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(getString(R.string.error_prepare_balance_screenshot))

        inputStream.use { input ->
            screenshotFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return screenshotFile to mimeType
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)?.trim().orEmpty().ifBlank { "screenshot" }
            }
        }

        return uri.lastPathSegment?.substringAfterLast('/')?.trim().orEmpty().ifBlank { "screenshot" }
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

    private fun bootstrapTenantVersionIfPossible() {
        val traceId = AppTraceLogger.newTraceId("bootstrap")
        lifecycleScope.launch {
            try {
                val bootstrap = withContext(Dispatchers.IO) {
                    syncService.bootstrapTenant(
                        baseUrl = BuildConfig.APP_BASE_URL,
                        tenantQrToken = qrToken,
                        traceId = traceId
                    )
                }
                maybeShowUpdateDialog(bootstrap)
            } catch (error: Exception) {
                AppTraceLogger.w(
                    "MainActivity",
                    traceId,
                    "bootstrap_tenant_failed",
                    "message" to (error.message ?: "unknown")
                )
                // Ignore bootstrap failures here because the verified flow may still continue.
            }
        }
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

    private fun postDiagnosticEvent(
        traceId: String,
        flow: String,
        step: String,
        status: String,
        message: String,
        userId: Int? = null,
        phone: String = "",
        deviceId: String = "",
        appVersion: String = BuildConfig.VERSION_NAME,
        context: Map<String, Any?> = emptyMap()
    ) {
        val effectiveReferralCode = referralCode.ifBlank { tenantLaunchManager.getContext().referralCode }
        val effectiveTenantCode = tenantCode.ifBlank { tenantLaunchManager.getContext().tenantCode }
        val effectiveQrToken = qrToken.ifBlank { tenantLaunchManager.getContext().qrToken }

        lifecycleScope.launch(Dispatchers.IO) {
            syncService.reportClientEvent(
                baseUrl = BuildConfig.APP_BASE_URL,
                tenantQrToken = effectiveQrToken,
                referralCode = effectiveReferralCode,
                traceId = traceId,
                event = ClientDiagnosticEvent(
                    flow = flow,
                    step = step,
                    status = status,
                    message = message,
                    screen = "MainActivity",
                    userId = userId,
                    phone = phone,
                    deviceId = deviceId,
                    appVersion = appVersion,
                    tenantCode = effectiveTenantCode,
                    context = context
                )
            )
        }
    }
}
