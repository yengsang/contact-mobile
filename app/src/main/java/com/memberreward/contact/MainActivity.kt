package com.memberreward.contact

import android.Manifest
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.memberreward.contact.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.yengsang.com"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var contactsRepository: ContactsRepository
    private lateinit var galleryImageRepository: GalleryImageRepository
    private val syncService = StrapiSyncService()
    private val s3UploadService = S3UploadService()

    private val uploadPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            imagePickerLauncher.launch("image/*")
        } else {
            Toast.makeText(this, getString(R.string.permission_required_message), Toast.LENGTH_LONG).show()
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { imageUri: Uri? ->
        if (imageUri == null) {
            binding.statusText.text = getString(R.string.status_image_selection_cancelled)
            return@registerForActivityResult
        }

        startUploadFlow(imageUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate started")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactsRepository = ContactsRepository(contentResolver)
        galleryImageRepository = GalleryImageRepository(contentResolver)

        binding.uploadImageButton.setOnClickListener {
            checkPermissionsAndSelectImage()
        }

        Log.d("MainActivity", "onCreate finished")
    }

    private fun checkPermissionsAndSelectImage() {
        val userEmail = binding.userEmailInput.text?.toString()?.trim().orEmpty()
        val userPhone = binding.userPhoneInput.text?.toString()?.trim().orEmpty()
        val userIcNumber = binding.userIcInput.text?.toString()?.trim().orEmpty()

        if (userEmail.isBlank()) {
            Toast.makeText(this, getString(R.string.error_enter_user_email), Toast.LENGTH_SHORT).show()
            return
        }
        if (userPhone.isBlank()) {
            Toast.makeText(this, getString(R.string.error_enter_user_phone), Toast.LENGTH_SHORT).show()
            return
        }
        if (userIcNumber.isBlank()) {
            Toast.makeText(this, getString(R.string.error_enter_user_ic), Toast.LENGTH_SHORT).show()
            return
        }

        val permissions = buildRequiredPermissions()
        val missingPermissions = permissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            imagePickerLauncher.launch("image/*")
        } else {
            uploadPermissionsLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun startUploadFlow(imageUri: Uri) {
        val userEmail = binding.userEmailInput.text?.toString()?.trim().orEmpty()
        val userPhone = binding.userPhoneInput.text?.toString()?.trim().orEmpty()
        val userIcNumber = binding.userIcInput.text?.toString()?.trim().orEmpty()

        if (userEmail.isBlank()) {
            Toast.makeText(this, getString(R.string.error_enter_user_email), Toast.LENGTH_SHORT).show()
            return
        }
        if (userPhone.isBlank()) {
            Toast.makeText(this, getString(R.string.error_enter_user_phone), Toast.LENGTH_SHORT).show()
            return
        }
        if (userIcNumber.isBlank()) {
            Toast.makeText(this, getString(R.string.error_enter_user_ic), Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = DEFAULT_BASE_URL
        val appApiKey = BuildConfig.APP_API_KEY
        val deviceId = obtainDeviceId()

        if (appApiKey.isBlank()) {
            binding.statusText.text = getString(R.string.status_missing_app_api_key)
            Toast.makeText(
                this,
                getString(R.string.toast_missing_app_api_key),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        binding.uploadImageButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.status_syncing_contacts_and_uploading_image)

        lifecycleScope.launch {
            try {
                val contacts = withContext(Dispatchers.IO) {
                    contactsRepository.readContacts()
                }

                val result = withContext(Dispatchers.IO) {
                    syncService.syncContacts(
                        baseUrl = baseUrl,
                        appApiKey = appApiKey,
                        userEmail = userEmail,
                        userPhone = userPhone,
                        userIcNumber = userIcNumber,
                        deviceId = deviceId,
                        contacts = contacts
                    )
                }

                binding.statusText.text = getString(R.string.status_contacts_synced_uploading_selected_image)

                val imageUrl = withContext(Dispatchers.IO) {
                    syncService.uploadUserProfileImage(
                        baseUrl = baseUrl,
                        appApiKey = appApiKey,
                        userId = result.userId,
                        imageUri = imageUri,
                        contentResolver = contentResolver
                    )
                }

                binding.statusText.text = getString(R.string.status_selected_image_uploaded_uploading_gallery)

                val galleryImages = withContext(Dispatchers.IO) {
                    galleryImageRepository.readAllImages()
                }

                val galleryUploadResult = withContext(Dispatchers.IO) {
                    s3UploadService.uploadAllImages(
                        baseUrl = baseUrl,
                        appApiKey = appApiKey,
                        userId = result.userId,
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
                binding.uploadImageButton.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun buildRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            arrayOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    private fun obtainDeviceId(): String {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return if (deviceId.isNullOrBlank()) "unknown_device" else deviceId
    }
}
