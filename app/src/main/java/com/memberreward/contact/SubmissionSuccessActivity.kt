package com.memberreward.contact

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.memberreward.contact.databinding.ActivitySubmissionSuccessBinding

class SubmissionSuccessActivity : AppCompatActivity() {

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, SubmissionSuccessActivity::class.java)
    }

    private lateinit var binding: ActivitySubmissionSuccessBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubmissionSuccessBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.doneButton.setOnClickListener {
            finishAffinity()
        }
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
                bottom = scrollBottomPadding + systemBars.bottom,
            )
            insets
        }

        ViewCompat.requestApplyInsets(binding.root)
    }
}
