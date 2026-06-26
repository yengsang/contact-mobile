package com.memberreward.contact

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

data class AppUpdateRequirement(
    val apkUrl: String,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val forceUpdate: Boolean,
)

object AppUpdateManager {

    fun resolveUpdateRequirement(
        context: Context,
        bootstrap: AppBootstrapResult
    ): AppUpdateRequirement? {
        val apkUrl = bootstrap.androidApkUrl.trim()
        val latestVersionCode = bootstrap.latestVersionCode ?: return null
        if (apkUrl.isBlank()) {
            return null
        }

        val installedVersionCode = getInstalledVersionCode(context)
        if (latestVersionCode <= installedVersionCode) {
            return null
        }

        return AppUpdateRequirement(
            apkUrl = apkUrl,
            latestVersionCode = latestVersionCode,
            latestVersionName = bootstrap.latestVersionName,
            forceUpdate = bootstrap.forceUpdate
        )
    }

    fun getInstalledVersionCode(context: Context): Int {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return packageInfo.longVersionCode.toInt()
    }

    fun showUpdateDialog(
        activity: AppCompatActivity,
        requirement: AppUpdateRequirement,
        onContinue: (() -> Unit)? = null
    ) {
        val versionSuffix = requirement.latestVersionName
            .takeIf { it.isNotBlank() }
            ?.let { " ($it)" }
            .orEmpty()

        val message = if (requirement.forceUpdate) {
            activity.getString(R.string.update_required_message, versionSuffix)
        } else {
            activity.getString(R.string.update_available_message, versionSuffix)
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(
                if (requirement.forceUpdate) {
                    R.string.update_required_title
                } else {
                    R.string.update_available_title
                }
            )
            .setMessage(message)
            .setPositiveButton(R.string.update_now_button) { _, _ ->
                openApkUrl(activity, requirement.apkUrl)
            }

        if (requirement.forceUpdate) {
            builder.setCancelable(false)
            builder.setNegativeButton(R.string.close_app_button) { _, _ ->
                activity.finishAffinity()
            }
        } else {
            builder.setNegativeButton(R.string.update_later_button) { _, _ ->
                onContinue?.invoke()
            }
        }

        builder.show().setCanceledOnTouchOutside(false)
    }

    private fun openApkUrl(context: Context, apkUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.update_open_failed), Toast.LENGTH_LONG).show()
        }
    }
}
