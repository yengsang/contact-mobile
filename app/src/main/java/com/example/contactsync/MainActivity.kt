package com.example.contactsync

import android.Manifest
import android.content.pm.PackageManager
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
        private const val DEFAULT_BASE_URL = "https://api.yengsang.com"
        private const val DEFAULT_API_TOKEN = "0a0c504133edf5d46a8a6299a58dfb7bc99579885452f6242a2c4a78843abf8603988c46d98bd32880d59ebebd35554d7d3643d151ed739aaa32d3e1f9981d56162d48345a26e069e0157093bb2c5e1b15a3b93f25c85d0f72a5eda50d70479f83b9a03727ed73c6d17bae273ea3c76c458ebcb6c49adce2f03eb36d8954f125"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var contactsRepository: ContactsRepository
    private val syncService = StrapiSyncService()
    private val contactAdapter = PhoneContactAdapter()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startSync()
        } else {
            Toast.makeText(this, "Contacts permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate started")

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactsRepository = ContactsRepository(contentResolver)

        binding.contactsList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = contactAdapter
        }

        if (binding.baseUrlInput.text.isNullOrBlank()) {
            binding.baseUrlInput.setText(DEFAULT_BASE_URL)
        }

        binding.syncButton.setOnClickListener {
            checkPermissionAndSync()
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
                requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    private fun startSync() {
        val baseUrl = binding.baseUrlInput.text?.toString()?.trim()?.trimEnd('/')
        if (baseUrl.isNullOrBlank()) {
            Toast.makeText(this, "Please enter Base URL", Toast.LENGTH_SHORT).show()
            return
        }

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

    private fun obtainDeviceId(): String {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return if (deviceId.isNullOrBlank()) "unknown_device" else deviceId
    }
}
