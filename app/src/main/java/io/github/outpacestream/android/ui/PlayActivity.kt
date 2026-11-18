package io.github.outpacestream.android.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import io.github.outpacestream.android.Device
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.ContentId
import io.github.outpacestream.android.core.Endpoint
import io.github.outpacestream.android.core.GetStreamLink
import io.github.outpacestream.android.core.RecentEntry
import io.github.outpacestream.android.core.RecommendedPlayer
import io.github.outpacestream.android.core.StreamRef
import io.github.outpacestream.android.core.StreamUrls
import io.github.outpacestream.android.engine.EngineController
import io.github.outpacestream.android.engine.EngineSelection
import io.github.outpacestream.android.engine.EngineService
import io.github.outpacestream.android.engine.EngineState

/**
 * Turns a link, shared text, pasted id or an in-app stream (search result, recent, active) into a
 * hand-off to an external player. Links (including other apps' `http://127.0.0.1/ace/getstream`
 * links) and shared text are only read for content ids. The app's own screens open it through
 * the non-exported [IN_APP_ALIAS] and pass an explicit [EXTRA_REF], which may be an infohash from
 * search; those extras are ignored on any other entry.
 */
class PlayActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var nameView: TextView
    private lateinit var refView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var urlView: TextView
    private lateinit var actions: View
    private lateinit var openButton: Button
    private lateinit var vlcButton: Button
    private lateinit var vlcHint: View
    /** VLC's activity for the MPEG-TS hand-off, when VLC can play it; see [refreshPlayers]. */
    private var vlc: ActivityInfo? = null
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

        openButton = findViewById(R.id.open)
        vlcButton = findViewById(R.id.open_vlc)
        vlcHint = findViewById(R.id.vlc_hint)
        openButton.setOnClickListener { openPlayer(hls = false) }
        vlcButton.setOnClickListener { openVlc() }
        findViewById<Button>(R.id.get_vlc).setOnClickListener { getVlc() }
        findViewById<Button>(R.id.vlc_hint_dismiss).setOnClickListener { dismissVlcHint() }
        findViewById<Button>(R.id.open_hls).setOnClickListener { openPlayer(hls = true) }
        findViewById<Button>(R.id.copy).setOnClickListener { copyUrl() }
        findViewById<Button>(R.id.share).setOnClickListener { shareUrl() }
        findViewById<Button>(R.id.cast).setOnClickListener { ref?.let { startActivity(CastActivity.intent(this, it, name ?: activeTitle())) } }

        nameView.visibility = View.GONE
        // Only trust typed extras when launched through the alias other apps cannot start.
        val inApp = intent.component?.className == IN_APP_ALIAS
        val explicit = if (inApp) intent.getStringExtra(EXTRA_REF)?.let(StreamRef::parse) else null
        if (explicit != null) {
            prepare(explicit, intent.getStringExtra(EXTRA_NAME))
            return
        }
        when (val r = linkResult() ?: ContentId.extract(inputText())) {
            is ContentId.Result.Id -> prepare(StreamRef.contentId(r.hex), null)
            ContentId.Result.Infohash -> fail(getString(R.string.play_infohash))
            ContentId.Result.None -> fail(getString(R.string.play_no_id))
        }
    }

    override fun onStart() {
        super.onStart()
        EngineController.addListener(onEngine)
        // Back from the store or a player: VLC may have been installed or removed meanwhile.
        if (actions.visibility == View.VISIBLE) refreshPlayers()
        showStart()
    }

    override fun onStop() {
        EngineController.removeListener(onEngine)
        super.onStop()
    }

    private val onEngine: () -> Unit = { showStart() }

    /** Once handed off, the progress or failure of this stream's start, as the service follows it. */
    private fun showStart() {
        if (actions.visibility != View.VISIBLE) return
        val p = EngineController.startProgress
        // No watch, or one for another stream (a newer hand-off, or the engine stopped): back to ready.
        val view = p?.takeIf { it.ref == ref }?.view
        status.text = if (view == null) getString(R.string.play_ready) else startText(view)
        progress.visibility = if (view != null && p?.done == false && !view.message.failure) View.VISIBLE else View.GONE
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

    /** A getstream link from another app; null for every other entry, which [ContentId.extract] reads. */
    private fun linkResult(): ContentId.Result? {
        if (intent.action != Intent.ACTION_VIEW || intent.scheme != "http") return null
        return GetStreamLink.parse(intent.dataString.orEmpty()) ?: ContentId.Result.None
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
        if (EngineSelection.current(this) == null) {
            fail(getString(R.string.no_engine_selected))
            return
        }
        status.text = getString(if (EngineController.isRemote(this)) R.string.play_connecting else R.string.play_preparing)
        if (EngineController.state != EngineState.Running) EngineController.start(this)
        background.execute {
            val ok = EngineController.awaitRunning(EngineService.START_TIMEOUT_MS + 5_000)
            onMain {
                if (ok) ready() else {
                    val reason = EngineController.failureReason() ?: getString(R.string.state_stopped)
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
        findViewById<Button>(R.id.cast).visibility = if (CastActivity.offered(this)) View.VISIBLE else View.GONE
        refreshPlayers()
        primaryButton().requestFocus()
        if (!launched) {
            launched = true
            openPlayer(hls = false)
        }
        showStart()
    }

    private fun settings() = EngineController.effectiveSettings(Prefs(this).settings)

    private fun port() = settings().port

    private fun activeTitle(): String? = ref?.let { r -> EngineController.streams.firstOrNull { it.id == r.selector }?.title }

    /** For a player on this device: loopback for the local engine, the remote engine's address otherwise. */
    private fun localUrl(hls: Boolean): String {
        val r = ref ?: return ""
        val e = EngineController.endpointFor(this) ?: Endpoint.loopback(port())
        return if (hls) StreamUrls.hls(e, r) else StreamUrls.live(e, r)
    }

    /** For other devices: a remote engine's address, else the LAN address when LAN access is on, else loopback with a hint. */
    private fun shareableUrl(): String {
        val r = ref ?: return ""
        if (EngineController.isRemote(this)) return localUrl(hls = false)
        val settings = settings()
        val host = if (settings.lan) Device.lanAddress() else null
        if (host == null) toast(getString(R.string.play_lan_hint))
        return StreamUrls.live(Endpoint(host ?: Endpoint.LOOPBACK_HOST, settings.port), r)
    }

    /** The hand-off intent; every player, VLC included, gets this same URL and type. */
    private fun viewIntent(hls: Boolean): Intent {
        val type = if (hls) "application/vnd.apple.mpegurl" else "video/mp2t"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(localUrl(hls)), type)
        val title = name ?: activeTitle()
        if (title != null) view.putExtra("title", title)
        return view
    }

    /** Other apps' activities that an implicit startActivity (and so the chooser) can reach for [view]. */
    private fun players(view: Intent): List<ActivityInfo> =
        packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }
            .filter { it.packageName != packageName && it.exported }

    /**
     * Shows "Open in VLC" when VLC can play the stream, else the dismissible "install VLC" hint
     * (with a store button only when a store handles the link). Never blocks the other players.
     */
    private fun refreshPlayers() {
        val handlers = players(viewIntent(hls = false))
        val prefs = Prefs(this)
        val store = Intent(Intent.ACTION_VIEW, Uri.parse(RecommendedPlayer.STORE_URI)).resolveActivity(packageManager) != null
        val offer = RecommendedPlayer.offer(handlers.map { it.packageName }, prefs.vlcHintDismissed, store)
        vlc = offer.vlc?.let(handlers::get)
        val hadFocus = currentFocus
        vlcButton.visibility = if (vlc != null) View.VISIBLE else View.GONE
        vlcHint.visibility = if (offer.hint) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.get_vlc).visibility = if (offer.storeLink) View.VISIBLE else View.GONE
        // A hidden view keeps no focus, which would leave a D-pad user with nothing selected.
        if (hadFocus != null && !hadFocus.isShown) primaryButton().requestFocus()
        setPrimary(openButton, vlc == null)
    }

    /** "Open in VLC" when it is offered, else the chooser. */
    private fun primaryButton(): Button = if (vlcButton.visibility == View.VISIBLE) vlcButton else openButton

    /** Only one primary-styled button: the chooser button steps down while "Open in VLC" is shown. */
    private fun setPrimary(button: Button, primary: Boolean) {
        button.setBackgroundResource(if (primary) R.drawable.bg_button_primary else R.drawable.bg_button_secondary)
        val color = if (primary) R.color.button_primary_text else R.color.button_secondary_text
        @Suppress("DEPRECATION")
        button.setTextColor(if (Build.VERSION.SDK_INT >= 23) getColorStateList(color) else resources.getColorStateList(color))
    }

    /** The same hand-off as [openPlayer], straight to VLC; falls back to the chooser if VLC is gone. */
    private fun openVlc() {
        val target = vlc ?: return openPlayer(hls = false)
        try {
            startActivity(viewIntent(hls = false).setComponent(ComponentName(target.packageName, target.name)))
            ref?.let(EngineController::watchStart)
        } catch (_: ActivityNotFoundException) {
            refreshPlayers()
            openPlayer(hls = false)
        }
    }

    private fun getVlc() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RecommendedPlayer.STORE_URI)))
        } catch (_: ActivityNotFoundException) {
            refreshPlayers()
        }
    }

    private fun dismissVlcHint() {
        Prefs(this).vlcHintDismissed = true
        refreshPlayers()
    }

    private fun openPlayer(hls: Boolean) {
        val chooser = playerChooser(viewIntent(hls))
        if (chooser == null) {
            toast(getString(R.string.play_no_player))
            return
        }
        try {
            startActivity(chooser)
            ref?.let(EngineController::watchStart)
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.play_no_player))
        }
    }

    /**
     * A chooser that never offers Outpace itself, since this activity also handles typed video
     * getstream links. API 24+ excludes our components; below that the chooser is built from the
     * other apps' activities, VLC first. Null when no other app can play [view].
     */
    private fun playerChooser(view: Intent): Intent? {
        val found = players(view)
        if (found.isEmpty()) return null
        val title = getString(R.string.play_choose)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val own = arrayOf(ComponentName(this, PlayActivity::class.java), ComponentName(packageName, IN_APP_ALIAS))
            return Intent.createChooser(view, title).putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, own)
        }
        val others = RecommendedPlayer.vlcFirst(found.map { it.packageName }).map(found::get)
        val targets = others.map { Intent(view).setComponent(ComponentName(it.packageName, it.name)) }
        // The chooser lists initial intents above the base intent's match, so the last target is the
        // base and the order, VLC first, is kept.
        val extra: Array<Parcelable> = targets.dropLast(1).toTypedArray()
        return Intent.createChooser(targets.last(), title).putExtra(Intent.EXTRA_INITIAL_INTENTS, extra)
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
        const val EXTRA_TEXT = "io.github.outpacestream.android.TEXT"

        /** The non-exported alias the app's own screens use; see the manifest. */
        const val IN_APP_ALIAS = "io.github.outpacestream.android.ui.InAppPlayActivity"

        /** A prefixed selector (`cid:` or `ih:`), honoured only through [IN_APP_ALIAS]. */
        const val EXTRA_REF = "io.github.outpacestream.android.REF"
        const val EXTRA_NAME = "io.github.outpacestream.android.NAME"
    }
}
