package io.github.outpacestream.android.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.outpacestream.android.core.Mailbox
import java.io.File

/** Installs a verified app update through a PackageInstaller session (API 21+, no FileProvider). */
object AppInstaller {
    /**
     * The system install confirmation, started by the visible screen. While no screen is attached
     * (the user left the app) it waits here, since Android 10+ blocks starting it from the background.
     */
    val userActions = Mailbox<Intent>()

    /** Install failures in plain words, shown by the visible screen or kept until one attaches. */
    val failures = Mailbox<String>()

    /** Whether Android lets this app install packages; below API 26 the global setting applies at install time. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens "Install unknown apps" for this app; false when the device has no such screen. */
    fun openInstallPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return try {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            true
        } catch (_: Exception) {
            // Some TV builds ship without this settings screen.
            false
        }
    }

    /** Streams [apk] into a session and commits it; the result arrives in [InstallResultReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                // The system fills the status extras in, so the intent must be mutable on API 31+.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}
