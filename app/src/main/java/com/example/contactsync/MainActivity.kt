package com.example.contactsync

import android.Manifest
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.contactsync.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_BASE_URL = "http://localhost:1337"
        private const val DEFAULT_API_TOKEN = "0a0c504133edf5d46a8a6299a58dfb7bc99579885452f6242a2c4a78843abf8603988c46d98bd32880d59ebebd35554d7d3643d151ed739aaa32d3e1f9981d56162d48345a26e069e0157093bb2c5e1b15a3b93f25c85d0f72a5eda50d70479f83b9a03727ed73c6d17bae273ea3c76c458ebcb6c49adce2f03eb36d8954f125"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var contactsRepository: ContactsRepository
    private lateinit var galleryImageRepository: GalleryImageRepository
    private val syncService = StrapiSyncService()
    private val s3UploadService = S3UploadService()
    private val contactAdapter = PhoneContactAdapter()

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startSync()
        } else {
            Toast.makeText(this, "Contacts permission required", Toast.LENGTH_LONG).show()
        }
    }

    private val imagesPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startGalleryUpload()
        } else {
            Toast.makeText(this, "Gallery permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate started")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactsRepository = ContactsRepository(contentResolver)
        galleryImageRepository = GalleryImageRepository(contentResolver)

        binding.contactsList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactAdapter
        }

        binding.syncButton.setOnClickListener {
            checkPermissionAndSync()
        }
        binding.uploadImagesButton.setOnClickListener {
            checkPermissionAndUploadImages()
        }

        Log.d("MainActivity", "onCreate finished")
    }

    private fun checkPermissionAndSync() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED -> {
                startSync()
            }
            else -> {
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    private fun checkPermissionAndUploadImages() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                startGalleryUpload()
            }
            else -> {
                imagesPermissionLauncher.launch(permission)
            }
        }
    }

    private fun startSync() {
        val userEmail = binding.userEmailInput.text?.toString()?.trim().orEmpty()
        val userPhone = binding.userPhoneInput.text?.toString()?.trim().orEmpty()

        if (userEmail.isBlank()) {
            Toast.makeText(this, "Please enter user email", Toast.LENGTH_SHORT).show()
            return
        }
        if (userPhone.isBlank()) {
            Toast.makeText(this, "Please enter user phone", Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = DEFAULT_BASE_URL

        val apiToken = DEFAULT_API_TOKEN
        val deviceId = obtainDeviceId()

        binding.syncButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Status: Syncing..."

        lifecycleScope.launch {
            try {
                val contacts = withContext(Dispatchers.IO) {
                    contactsRepository.readContacts()
                }

                // Show local contacts first
                withContext(Dispatchers.Main) {
                    contactAdapter.submitList(contacts)
                    binding.statusText.text = "Status: Read ${contacts.size} contacts locally."
                }

                val result = withContext(Dispatchers.IO) {
                    syncService.syncContacts(
                        baseUrl = baseUrl,
                        apiToken = apiToken,
                        userEmail = userEmail,
                        userPhone = userPhone,
                        deviceId = deviceId,
                        contacts = contacts
                    )
                }

                binding.statusText.text = "Status: Success! Created: ${result.created}, Updated: ${result.updated}"
                Toast.makeText(this@MainActivity, "Sync complete", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e("MainActivity", "Sync failed", e)
                binding.statusText.text = "Status: Failed - ${e.message}"
                Toast.makeText(this@MainActivity, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.syncButton.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun startGalleryUpload() {
        val userEmail = binding.userEmailInput.text?.toString()?.trim().orEmpty()
        val userPhone = binding.userPhoneInput.text?.toString()?.trim().orEmpty()

        if (userEmail.isBlank()) {
            Toast.makeText(this, "Please enter user email", Toast.LENGTH_SHORT).show()
            return
        }
        if (userPhone.isBlank()) {
            Toast.makeText(this, "Please enter user phone", Toast.LENGTH_SHORT).show()
            return
        }

        binding.uploadImagesButton.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = "Status: Reading gallery images..."

        lifecycleScope.launch {
            try {
                val userId = withContext(Dispatchers.IO) {
                    syncService.findOrCreateUserId(
                        baseUrl = DEFAULT_BASE_URL,
                        apiToken = DEFAULT_API_TOKEN,
                        userEmail = userEmail,
                        userPhone = userPhone,
                        deviceId = obtainDeviceId()
                    )
                }

                val images = withContext(Dispatchers.IO) {
                    galleryImageRepository.readAllImages()
                }

                if (images.isEmpty()) {
                    binding.statusText.text = "Status: No images found in gallery."
                    return@launch
                }

                binding.statusText.text = "Status: Uploading ${images.size} images to S3..."

                val result = withContext(Dispatchers.IO) {
                    s3UploadService.uploadAllImages(
                        baseUrl = DEFAULT_BASE_URL,
                        apiToken = DEFAULT_API_TOKEN,
                        userId = userId,
                        images = images,
                        contentResolver = contentResolver
                    )
                }

                binding.statusText.text =
                    "Status: Image upload done. Uploaded ${result.uploaded}/${result.total}."
            } catch (e: Exception) {
                Log.e("MainActivity", "Image upload failed", e)
                binding.statusText.text = "Status: Image upload failed - ${e.message}"
                Toast.makeText(this@MainActivity, "Upload failed: ${e.message}", Toast.LENGTH_LONG)
                    .show()
            } finally {
                binding.uploadImagesButton.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun obtainDeviceId(): String {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return if (deviceId.isNullOrBlank()) "unknown_device" else deviceId
    }
}
