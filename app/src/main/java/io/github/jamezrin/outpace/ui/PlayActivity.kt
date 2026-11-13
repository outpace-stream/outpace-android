package io.github.jamezrin.outpace.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import io.github.jamezrin.outpace.Device
import io.github.jamezrin.outpace.EngineFiles
import io.github.jamezrin.outpace.Prefs
import io.github.jamezrin.outpace.R
import io.github.jamezrin.outpace.core.ContentId
import io.github.jamezrin.outpace.core.RecentEntry
import io.github.jamezrin.outpace.core.StreamRef
import io.github.jamezrin.outpace.core.StreamUrls
import io.github.jamezrin.outpace.engine.EngineController
import io.github.jamezrin.outpace.engine.EngineService
import io.github.jamezrin.outpace.engine.EngineState

/**
 * Turns a link, shared text, pasted id or an in-app stream (search result, recent, active) into a
 * hand-off to an external player. Links and shared text are only read for content ids; the app's
 * own screens pass an explicit [EXTRA_REF], which may be an infohash from search.
 */
class PlayActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var nameView: TextView
    private lateinit var refView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var urlView: TextView
    private lateinit var actions: View
    private var ref: StreamRef? = null
    private var name: String? = null
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.play_title)
        setContentView(R.layout.activity_play)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        urlView = findViewById(R.id.url)
        actions = findViewById(R.id.actions)
        nameView = findViewById(R.id.name)
        refView = findViewById(R.id.ref)
        launched = savedInstanceState?.getBoolean("launched") ?: false

        findViewById<Button>(R.id.open).setOnClickListener { openPlayer(hls = false) }
        findViewById<Button>(R.id.open_hls).setOnClickListener { openPlayer(hls = true) }
        findViewById<Button>(R.id.copy).setOnClickListener { copyUrl() }
        findViewById<Button>(R.id.share).setOnClickListener { shareUrl() }

        nameView.visibility = View.GONE
        val explicit = intent.getStringExtra(EXTRA_REF)?.let(StreamRef::parse)
        if (explicit != null) {
            prepare(explicit, intent.getStringExtra(EXTRA_NAME))
            return
        }
        when (val r = ContentId.extract(inputText())) {
            is ContentId.Result.Id -> prepare(StreamRef.contentId(r.hex), null)
            ContentId.Result.Infohash -> fail(getString(R.string.play_infohash))
            ContentId.Result.None -> fail(getString(R.string.play_no_id))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("launched", launched)
    }

    private fun inputText(): String = when (intent.action) {
        Intent.ACTION_VIEW -> intent.dataString.orEmpty()
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        else -> intent.getStringExtra(EXTRA_TEXT).orEmpty()
    }

    private fun fail(message: String) {
        progress.visibility = View.GONE
        status.text = message
    }

    private fun prepare(target: StreamRef, label: String?) {
        ref = target
        name = label ?: activeTitle()
        name?.let { nameView.text = it; nameView.visibility = View.VISIBLE }
        refView.text = getString(R.string.play_ref, kindLabel(target), target.hex)
        Prefs(this).addRecent(RecentEntry(target, name))
        if (EngineFiles.activeVersion(this) == null) {
            fail(getString(R.string.no_engine_installed))
            return
        }
        status.text = getString(R.string.play_preparing)
        if (EngineController.state != EngineState.Running) EngineController.start(this)
        background.execute {
            val ok = EngineController.awaitRunning(EngineService.START_TIMEOUT_MS + 5_000)
            onMain {
                if (ok) ready() else {
                    val reason = (EngineController.state as? EngineState.Crashed)?.reason ?: getString(R.string.state_stopped)
                    fail(getString(R.string.play_engine_failed, reason))
                }
            }
        }
    }

    private fun ready() {
        progress.visibility = View.GONE
        status.text = getString(R.string.play_ready)
        urlView.text = localUrl(hls = false)
        actions.visibility = View.VISIBLE
        findViewById<Button>(R.id.open).requestFocus()
        if (!launched) {
            launched = true
            openPlayer(hls = false)
        }
    }

    private fun settings() = EngineController.effectiveSettings(Prefs(this).settings)

    private fun port() = settings().port

    private fun activeTitle(): String? = ref?.let { r -> EngineController.streams.firstOrNull { it.id == r.selector }?.title }

    private fun localUrl(hls: Boolean): String {
        val r = ref ?: return ""
        return if (hls) StreamUrls.hls(StreamUrls.LOOPBACK, port(), r) else StreamUrls.live(StreamUrls.LOOPBACK, port(), r)
    }

    /** For other devices: the LAN address when LAN access is on, else loopback with a hint. */
    private fun shareableUrl(): String {
        val r = ref ?: return ""
        val settings = settings()
        val host = if (settings.lan) Device.lanAddress() else null
        if (host == null) toast(getString(R.string.play_lan_hint))
        return StreamUrls.live(host ?: StreamUrls.LOOPBACK, settings.port, r)
    }

    private fun openPlayer(hls: Boolean) {
        val type = if (hls) "application/vnd.apple.mpegurl" else "video/mp2t"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(localUrl(hls)), type)
        val title = name ?: activeTitle()
        if (title != null) view.putExtra("title", title)
        if (packageManager.queryIntentActivities(view, 0).isEmpty()) {
            toast(getString(R.string.play_no_player))
            return
        }
        try {
            startActivity(Intent.createChooser(view, getString(R.string.play_choose)))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.play_no_player))
        }
    }

    private fun copyUrl() {
        copyText("stream", shareableUrl())
        toast(getString(R.string.play_copied))
    }

    private fun shareUrl() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareableUrl())
        try {
            startActivity(Intent.createChooser(send, getString(R.string.play_share)))
        } catch (_: ActivityNotFoundException) {
            copyUrl()
        }
    }

    companion object {
        const val EXTRA_TEXT = "io.github.jamezrin.outpace.TEXT"

        /** A prefixed selector (`cid:` or `ih:`) from the app's own screens. Not exported text. */
        const val EXTRA_REF = "io.github.jamezrin.outpace.REF"
        const val EXTRA_NAME = "io.github.jamezrin.outpace.NAME"
    }
}
