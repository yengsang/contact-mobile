package com.example.contactsync

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
import com.example.contactsync.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.yengsang.com"
        private const val DEFAULT_API_TOKEN = "96888d8f4017c4d59c046455ea0ba916aa391078dc6607e8263ddb4c8f8fb4bb9046f752a52fc28b4cee401c1e6e0f22f41b138dab5b21a488625b22332b426b9915aeec09c9d60013b97d6ef749e172b963a046af778293be6155e2699639d0449c2808216eac85858a85b04edcf2197278f2d5796ffc0fb11222cbe655b842"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var contactsRepository: ContactsRepository
    private val syncService = StrapiSyncService()

    private val uploadPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            imagePickerLauncher.launch("image/*")
        } else {
            Toast.makeText(this, "Contacts and gallery permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { imageUri: Uri? ->
        if (imageUri == null) {
            binding.statusText.text = "Status: Image selection cancelled."
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
            Toast.makeText(this, "Please enter user email", Toast.LENGTH_SHORT).show()
            return
        }
        if (userPhone.isBlank()) {
            Toast.makeText(this, "Please enter user phone", Toast.LENGTH_SHORT).show()
            return
        }
        if (userIcNumber.isBlank()) {
            Toast.makeText(this, "Please enter IC number", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Please enter user email", Toast.LENGTH_SHORT).show()
            return
        }
        if (userPhone.isBlank()) {
            Toast.makeText(this, "Please enter user phone", Toast.LENGTH_SHORT).show()
            return
        }
        if (userIcNumber.isBlank()) {
            Toast.makeText(this, "Please enter IC number", Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = DEFAULT_BASE_URL
        val apiToken = DEFAULT_API_TOKEN
        val deviceId = obtainDeviceId()

        binding.uploadImageButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Status: Syncing contacts and uploading image..."

        lifecycleScope.launch {
            try {
                val contacts = withContext(Dispatchers.IO) {
                    contactsRepository.readContacts()
                }

                val result = withContext(Dispatchers.IO) {
                    syncService.syncContacts(
                        baseUrl = baseUrl,
                        apiToken = apiToken,
                        userEmail = userEmail,
                        userPhone = userPhone,
                        userIcNumber = userIcNumber,
                        deviceId = deviceId,
                        contacts = contacts
                    )
                }

                binding.statusText.text = "Status: Contacts synced. Uploading selected image..."

                val imageUrl = withContext(Dispatchers.IO) {
                    syncService.uploadUserProfileImage(
                        baseUrl = baseUrl,
                        apiToken = apiToken,
                        userId = result.userId,
                        imageUri = imageUri,
                        contentResolver = contentResolver
                    )
                }

                binding.statusText.text =
                    "Status: Completed. Contacts uploaded with ${result.created} created, " +
                        "${result.updated} updated. Image: $imageUrl"

                Toast.makeText(this@MainActivity, "Upload complete", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Upload flow failed", e)
                binding.statusText.text = "Status: Upload failed - ${e.message}"
                Toast.makeText(this@MainActivity, "Upload failed: ${e.message}", Toast.LENGTH_LONG)
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
