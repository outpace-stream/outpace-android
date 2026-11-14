package io.github.outpacestream.android.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.outpacestream.android.R
import io.github.outpacestream.android.net.AppUpdates

/** Receives the PackageInstaller session status for an app update. Not exported. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent) ?: return
                AppInstaller.userActions.post(confirm)
            }
            // On success the process is replaced; the next start cleans the downloaded APK up.
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                runCatching { AppUpdates(context).discard() }
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        context.getString(R.string.update_failed_signature)
                    PackageInstaller.STATUS_FAILURE_ABORTED -> context.getString(R.string.update_cancelled)
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: context.getString(R.string.update_failed_generic)
                }
                AppInstaller.failures.post(message)
            }
        }
    }

    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
