package io.github.outpacestream.android.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.CastBlock
import io.github.outpacestream.android.core.CastError
import io.github.outpacestream.android.core.CastFailure
import io.github.outpacestream.android.core.CastGate
import io.github.outpacestream.android.core.CastTarget
import io.github.outpacestream.android.core.EngineChoice
import io.github.outpacestream.android.core.EngineSettings
import io.github.outpacestream.android.core.StreamRef
import io.github.outpacestream.android.engine.CastEnd
import io.github.outpacestream.android.engine.CastSession
import io.github.outpacestream.android.engine.EngineController
import io.github.outpacestream.android.engine.EngineSelection
import io.github.outpacestream.android.engine.EngineService
import io.github.outpacestream.android.engine.EngineState
import io.github.outpacestream.android.net.CastClient
import io.github.outpacestream.android.net.CastException
import java.util.concurrent.Executors

/**
 * "Play on TV": lists the DLNA renderers the selected engine finds and tells one to play a live
 * stream. The engine casts, on this device or a remote engine; the app is only the remote control.
 * Discovery runs only when this screen opens or the user refreshes, and the active cast's status
 * is polled quickly only while it shows (the service polls slowly otherwise).
 */
class CastActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var action: Button
    private lateinit var active: View
    private lateinit var activeText: TextView
    private lateinit var stop: Button
    private lateinit var targetsTitle: View
    private lateinit var targets: LinearLayout
    private lateinit var refresh: Button
    private lateinit var ref: StreamRef

    /** Bumped per request, so a late answer for an earlier one is dropped. */
    private var generation = 0
    private var busy = false
    private var stopping = false
    private var loaded = false

    /** The cast end already shown, so each one is reported once. */
    private var shownEnd: CastEnd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.cast_title)
        setContentView(R.layout.activity_cast)
        // Not exported: only the app's own screens start it, so the typed selector can be trusted.
        ref = intent.getStringExtra(EXTRA_REF)?.let(StreamRef::parse) ?: return finish()
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        action = findViewById(R.id.action)
        active = findViewById(R.id.active)
        activeText = findViewById(R.id.active_text)
        stop = findViewById(R.id.stop)
        targetsTitle = findViewById(R.id.targets_title)
        targets = findViewById(R.id.targets)
        refresh = findViewById(R.id.refresh)
        intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }?.let {
            findViewById<TextView>(R.id.name).apply { text = it; visibility = View.VISIBLE }
        }
        findViewById<TextView>(R.id.ref).text = getString(R.string.play_ref, kindLabel(ref), ref.hex)
        refresh.setOnClickListener { load(refresh = true) }
        stop.setOnClickListener {
            stopping = true
            EngineController.stopCast()
            renderActive()
        }
        shownEnd = EngineController.castEnded
    }

    override fun onStart() {
        super.onStart()
        EngineController.addListener(onEngine)
        EngineController.castScreenShown()
        renderActive()
        if (!loaded && !busy) begin()
    }

    override fun onStop() {
        EngineController.removeListener(onEngine)
        EngineController.castScreenHidden()
        super.onStop()
    }

    private val onEngine: () -> Unit = { renderActive() }

    private fun renderActive() {
        val cast = EngineController.cast
        if (cast == null) stopping = false
        active.visibility = if (cast == null) View.GONE else View.VISIBLE
        if (cast != null) activeText.text = castText(EngineController.castMessage, cast.targetName)
        stop.isEnabled = !stopping
        val end = EngineController.castEnded
        if (end != null && end !== shownEnd) {
            shownEnd = end
            end.message?.let { setStatus(castText(it, end.session.targetName)) }
        }
    }

    private fun setStatus(text: String, working: Boolean = false) {
        status.text = text
        progress.visibility = if (working) View.VISIBLE else View.GONE
    }

    private fun showAction(label: Int, onClick: () -> Unit) {
        action.setText(label)
        action.setOnClickListener { onClick() }
        action.visibility = View.VISIBLE
        action.requestFocus()
    }

    private fun remoteName(): String? = if (EngineController.isRemote(this)) EngineSelection.remote(this)?.name else null

    /** Checks local facts, starts the engine if needed, then lists the TVs. */
    private fun begin() {
        action.visibility = View.GONE
        val choice = EngineSelection.current(this) ?: return setStatus(getString(R.string.no_engine_selected))
        if (choice is EngineChoice.Local) {
            val version = EngineController.runningVersion ?: choice.version
            val running = EngineController.runningSettings.takeIf { EngineController.state == EngineState.Running }
            when (val block = CastGate.check(version, Prefs(this).settings, running)) {
                null -> Unit
                is CastBlock.Old -> {
                    setStatus(getString(R.string.cast_old_engine, EngineSettings.CAST_MIN_VERSION, block.version))
                    return showAction(R.string.notice_old_action) { startActivity(Intent(this, VersionsActivity::class.java)) }
                }
                CastBlock.Off -> {
                    setStatus(getString(R.string.cast_off))
                    return showAction(R.string.cast_turn_on) { CastSetup.enable(this) { begin() } }
                }
                CastBlock.NeedsLan -> {
                    setStatus(getString(R.string.cast_needs_lan))
                    return showAction(R.string.cast_lan_turn_on) { CastSetup.confirmLan(this) { begin() } }
                }
                CastBlock.NeedsRestart -> {
                    setStatus(getString(R.string.cast_needs_restart))
                    return showAction(R.string.action_restart_short) { restartAndBegin() }
                }
            }
        }
        load(refresh = false)
    }

    private fun restartAndBegin() {
        action.visibility = View.GONE
        setStatus(getString(R.string.play_preparing), working = true)
        EngineController.restart(this)
        val gen = ++generation
        busy = true
        castExecutor.execute {
            // The old session still reads Running until the restart reaches the service, so wait
            // for one that started with the casting settings.
            val deadline = SystemClock.elapsedRealtime() + EngineService.START_TIMEOUT_MS + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val running = EngineController.runningSettings
                if (EngineController.state == EngineState.Running && running?.cast == true && running.lan) break
                if (EngineController.state is EngineState.Crashed) break
                Thread.sleep(200)
            }
            onMain {
                if (gen != generation) return@onMain
                busy = false
                begin()
            }
        }
    }

    /** Lists the TVs; [refresh] makes the engine search again instead of using its 60 s cache. */
    private fun load(refresh: Boolean) {
        if (busy) return
        busy = true
        val gen = ++generation
        this.refresh.isEnabled = false
        setStatus(getString(if (EngineController.state == EngineState.Running) R.string.cast_searching else R.string.play_preparing), working = true)
        if (EngineController.state != EngineState.Running) EngineController.start(this)
        castExecutor.execute {
            val result: Result<List<CastTarget>> = runCatching {
                if (!EngineController.awaitRunning(EngineService.START_TIMEOUT_MS + 5_000)) {
                    throw EngineDown(EngineController.failureReason() ?: getString(R.string.state_stopped))
                }
                onMain { if (gen == generation) setStatus(getString(R.string.cast_searching), working = true) }
                val endpoint = EngineController.endpointFor(this) ?: throw EngineDown(getString(R.string.no_engine_selected))
                CastClient.targets(endpoint, refresh).targets
            }
            onMain {
                if (gen != generation) return@onMain
                busy = false
                loaded = true
                this.refresh.isEnabled = true
                this.refresh.visibility = View.VISIBLE
                result.fold(::showTargets) { showFailure(it) }
            }
        }
    }

    private class EngineDown(message: String) : Exception(message)

    private fun showTargets(list: List<CastTarget>) {
        targets.removeAllViews()
        targetsTitle.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (list.isEmpty()) {
            setStatus(getString(R.string.cast_none_found))
            refresh.requestFocus()
            return
        }
        setStatus(getString(R.string.cast_pick))
        for (t in list) {
            val detail = listOfNotNull(t.manufacturer, t.model).joinToString(" ").ifBlank { t.address }
            targets.addRow(getString(R.string.cast_target_row, t.name, detail)) { play(t) }
        }
        if (currentFocus == null || currentFocus === refresh || currentFocus === action) targets.getChildAt(0)?.requestFocus()
    }

    private fun showFailure(e: Throwable) {
        val error = (e as? CastException)?.error
        when {
            e is EngineDown -> setStatus(getString(R.string.play_engine_failed, e.message.orEmpty()))
            error != null -> showCastError(error)
            else -> setStatus(getString(R.string.cast_err_engine, e.message ?: e.javaClass.simpleName))
        }
        if (action.visibility != View.VISIBLE) refresh.requestFocus()
    }

    private fun showCastError(error: CastError) {
        val remote = remoteName()
        setStatus(castErrorText(error, remote))
        // On this device's engine a loopback bind is the LAN setting; offer the same fix as the gate.
        if (remote == null && error.failure == CastFailure.LoopbackBind) {
            showAction(R.string.cast_lan_turn_on) { CastSetup.confirmLan(this) { begin() } }
        }
        if (error.failure == CastFailure.UnknownTarget) targets.removeAllViews()
    }

    /** Sends the stream to [target]; one TV at a time, so a cast on another TV is stopped first. */
    private fun play(target: CastTarget) {
        if (busy) return
        busy = true
        val gen = ++generation
        askNotificationPermission()
        setStatus(getString(R.string.cast_starting_on, target.name), working = true)
        castExecutor.execute {
            val result = runCatching {
                val endpoint = EngineController.endpointFor(this) ?: throw EngineDown(getString(R.string.no_engine_selected))
                EngineController.cast?.takeIf { it.targetId != target.id }?.let { previous ->
                    try { CastClient.stop(previous.endpoint, previous.targetId) } catch (_: Exception) {}
                    EngineController.endCast(previous, null)
                }
                CastClient.play(endpoint, target.id, ref)
                CastSession(target.id, target.name, ref, endpoint, SystemClock.elapsedRealtime())
            }
            onMain {
                if (gen != generation) return@onMain
                busy = false
                result.fold(
                    { session ->
                        EngineController.castStarted(session)
                        setStatus(getString(R.string.cast_sent, target.name))
                        stop.requestFocus()
                    },
                    { showFailure(it) },
                )
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    companion object {
        private const val EXTRA_REF = "io.github.outpacestream.android.CAST_REF"
        private const val EXTRA_NAME = "io.github.outpacestream.android.CAST_NAME"

        /** One thread for cast requests: a `play` can take many seconds and must not hold up other screen work. */
        private val castExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-cast").apply { isDaemon = true } }

        fun intent(context: Context, ref: StreamRef, name: String?): Intent =
            Intent(context, CastActivity::class.java)
                .putExtra(EXTRA_REF, ref.selector)
                .apply { if (!name.isNullOrBlank()) putExtra(EXTRA_NAME, name) }

        /**
         * Whether to offer "Play on TV" for the selected engine: a remote engine (its own settings
         * decide, and a refusal says so), or this device's engine from [EngineSettings.CAST_MIN_VERSION].
         */
        fun offered(context: Context): Boolean = when (val c = EngineSelection.current(context)) {
            null -> false
            is EngineChoice.Remote -> true
            is EngineChoice.Local -> CastGate.supports(EngineController.runningVersion ?: c.version)
        }
    }
}
