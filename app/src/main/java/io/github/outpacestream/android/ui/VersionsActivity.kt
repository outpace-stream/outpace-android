package io.github.outpacestream.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.outpacestream.android.Device
import io.github.outpacestream.android.EngineFiles
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.EngineChoice
import io.github.outpacestream.android.core.EngineStore
import io.github.outpacestream.android.core.Release
import io.github.outpacestream.android.core.Releases
import io.github.outpacestream.android.engine.EngineController
import io.github.outpacestream.android.engine.EngineSelection
import io.github.outpacestream.android.net.EngineReleases
import java.io.InterruptedIOException

/**
 * Engine versions for this device: each release with its own Install, Use and Remove buttons, and
 * the download in progress with Cancel. Nothing installs on its own; the user picks the version.
 */
class VersionsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var releases: EngineReleases
    private lateinit var running: TextView
    private lateinit var latest: TextView
    private lateinit var note: TextView
    private lateinit var list: LinearLayout
    private lateinit var download: View
    private lateinit var downloadText: TextView
    private lateinit var downloadProgress: ProgressBar
    private var result: EngineReleases.ListResult? = null
    @Volatile private var cancel = false
    private var busy = false
    private val listener: () -> Unit = { renderHeader(); renderList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.versions_title)
        setContentView(R.layout.activity_versions)
        prefs = Prefs(this)
        releases = EngineReleases(this)
        running = findViewById(R.id.running)
        latest = findViewById(R.id.latest)
        note = findViewById(R.id.note)
        list = findViewById(R.id.list)
        download = findViewById(R.id.download)
        downloadText = findViewById(R.id.download_text)
        downloadProgress = findViewById(R.id.download_progress)
        findViewById<Button>(R.id.refresh).setOnClickListener { load(force = true) }
        findViewById<Button>(R.id.download_cancel).setOnClickListener { cancel = true }
        findViewById<CheckBox>(R.id.show_pre).apply {
            isChecked = prefs.showPrereleases
            setOnCheckedChangeListener { _, checked -> prefs.showPrereleases = checked; renderList() }
        }
        load(force = false)
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_REINSTALL)?.let { reinstallNow(it) }
    }

    override fun onStart() {
        super.onStart()
        EngineController.addListener(listener)
        renderHeader()
    }

    override fun onStop() {
        EngineController.removeListener(listener)
        super.onStop()
    }

    override fun onDestroy() {
        cancel = true
        super.onDestroy()
    }

    private fun load(force: Boolean) {
        note.visibility = View.GONE
        background.execute {
            val r = releases.list(force)
            if (force || prefs.latestVersion == null) runCatching { releases.latest(force = true) }
            onMain {
                result = r
                if (r.error != null) {
                    note.visibility = View.VISIBLE
                    note.text = if (r.fromApi) getString(R.string.versions_error, r.error) else getString(R.string.versions_partial, r.error)
                }
                renderHeader()
                renderList()
            }
        }
    }

    private fun renderHeader() {
        running.text = getString(R.string.versions_running, EngineController.runningVersion?.let { "v$it" } ?: getString(R.string.versions_none_running))
        latest.text = getString(R.string.versions_latest, prefs.latestVersion?.let { "v$it" } ?: getString(R.string.versions_unknown))
    }

    private fun visibleReleases(): List<Release> {
        val r = result ?: return emptyList()
        val installed = EngineFiles.store(this).installed()
        val all = r.releases.filter { prefs.showPrereleases || !it.prerelease || it.version in installed }
        // Installed versions that have left the list (for example deleted releases) stay manageable.
        val extra = installed.filter { v -> all.none { it.version == v } }.map { Release("v$it", it, "", false, emptySet()) }
        return (all + extra).sortedWith { a, b -> Releases.compareVersions(b.version, a.version) }
    }

    private fun renderList() {
        list.removeAllViews()
        val store = EngineFiles.store(this)
        val active = EngineSelection.localVersion(this)
        val targets = Device.targets()
        val fromApi = result?.fromApi == true
        for (rel in visibleReleases()) {
            val badges = ArrayList<String>()
            val installed = store.isInstalled(rel.version)
            if (rel.version == EngineController.runningVersion) badges += getString(R.string.badge_running)
            else if (rel.version == active) badges += getString(R.string.badge_active)
            if (installed) badges += getString(R.string.badge_installed)
            if (rel.version == prefs.latestVersion) badges += getString(R.string.badge_latest)
            if (rel.prerelease) badges += getString(R.string.badge_prerelease)
            // Only API entries carry asset names; entries from the latest redirect are probed on install.
            val fromList = fromApi && result?.releases?.contains(rel) == true
            val unavailable = !installed && fromList && Releases.pickTarget(rel, targets) == null
            if (unavailable) badges += getString(R.string.badge_unavailable)
            val row = LayoutInflater.from(this).inflate(R.layout.item_version, list, false)
            row.findViewById<TextView>(R.id.title).text = getString(R.string.version_label, rel.version)
            val meta = (listOf(rel.publishedAt.take(10)) + badges).filter { it.isNotEmpty() }.joinToString(getString(R.string.meta_separator))
            row.findViewById<TextView>(R.id.meta).apply {
                text = meta
                visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
            }
            val action = row.findViewById<Button>(R.id.action)
            val remove = row.findViewById<Button>(R.id.remove)
            when {
                // The badge already says why; a dimmed button would still read as an action.
                unavailable -> action.visibility = View.GONE
                !installed -> {
                    action.setText(R.string.version_install)
                    action.isEnabled = !busy
                    action.setOnClickListener { install(rel, replace = false) }
                }
                rel.version != active -> {
                    action.setText(R.string.version_use)
                    action.isEnabled = !busy
                    action.setOnClickListener { activate(rel.version) }
                }
                else -> action.visibility = View.GONE
            }
            remove.visibility = if (installed) View.VISIBLE else View.GONE
            remove.isEnabled = !busy
            // The button backgrounds have no disabled state; dim them while a download runs.
            for (b in listOf(action, remove)) b.alpha = if (b.isEnabled) 1f else DISABLED_ALPHA
            remove.setOnClickListener { remove(rel.version) }
            list.addView(row)
        }
    }

    private fun activate(version: String) {
        EngineSelection.select(this, EngineChoice.Local(version))
        renderList()
    }

    private fun remove(version: String) {
        if (!EngineSelection.removeLocal(this, version)) {
            toast(getString(R.string.version_remove_running))
            return
        }
        renderList()
    }

    /** Downloads [rel]; with [replace], over the installed copy of the same version. */
    private fun install(rel: Release, replace: Boolean) {
        if (busy) return
        val store = EngineFiles.store(this)
        if (!replace && store.installed().size >= EngineStore.MAX_INSTALLED) {
            toast(resources.getQuantityString(R.plurals.version_full, EngineStore.MAX_INSTALLED, EngineStore.MAX_INSTALLED))
            return
        }
        busy = true
        cancel = false
        renderList()
        download.visibility = View.VISIBLE
        downloadProgress.isIndeterminate = true
        downloadText.text = getString(R.string.version_downloading, rel.version, "")
        background.execute {
            val outcome = try {
                releases.install(rel, cancelled = { cancel }) { done, total ->
                    onMain {
                        val mb = Formatter.formatShortFileSize(this@VersionsActivity, done)
                        downloadText.text = getString(R.string.version_downloading, rel.version, mb)
                        if (total > 0) {
                            downloadProgress.isIndeterminate = false
                            downloadProgress.progress = (done * 1000 / total).toInt()
                        }
                    }
                }
                null
            } catch (_: InterruptedIOException) {
                ""
            } catch (e: Exception) {
                EngineReleases.describe(e)
            }
            onMain {
                busy = false
                download.visibility = View.GONE
                when (outcome) {
                    null -> {
                        toast(getString(if (replace) R.string.version_reinstalled else R.string.version_installed, rel.version))
                        EngineSelection.installed(this, rel.version)
                        // The running engine still holds the old binary; restart it onto the new one.
                        if (replace && EngineController.runningVersion == rel.version) EngineController.restart(this)
                    }
                    "" -> Unit
                    else -> toast(getString(R.string.version_failed, outcome))
                }
                renderList()
            }
        }
    }

    /** Downloads [version] again over the installed copy, for an install that has gone bad. */
    private fun reinstallNow(version: String) {
        if (!EngineFiles.store(this).isInstalled(version)) return
        install(Release("v$version", version, "", false, emptySet()), replace = true)
    }

    companion object {
        private const val EXTRA_REINSTALL = "io.github.outpacestream.android.REINSTALL"
        private const val DISABLED_ALPHA = 0.4f

        /** Opens the versions screen and reinstalls [version] there, with its progress and Cancel. */
        fun reinstall(context: Context, version: String) {
            context.startActivity(Intent(context, VersionsActivity::class.java).putExtra(EXTRA_REINSTALL, version))
        }
    }
}
