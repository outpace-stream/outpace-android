package io.github.outpacestream.android.ui

import android.app.AlertDialog
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.outpacestream.android.Device
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.ContentId
import io.github.outpacestream.android.core.Endpoint
import io.github.outpacestream.android.core.EngineChoice
import io.github.outpacestream.android.core.RecentEntry
import io.github.outpacestream.android.core.StreamInfo
import io.github.outpacestream.android.core.StreamRef
import io.github.outpacestream.android.engine.EngineController
import io.github.outpacestream.android.engine.EngineSelection
import io.github.outpacestream.android.engine.EngineState

/** Engine status and controls, the id box, what is playing now and what played recently. */
class HomeScreen(host: MainActivity, parent: ViewGroup) : Screen(host, parent, R.layout.screen_home) {
    private val dot = find<ImageView>(R.id.state_dot)
    private val state = find<TextView>(R.id.state)
    private val detail = find<TextView>(R.id.state_detail)
    private val url = find<TextView>(R.id.url)
    private val startStop = find<Button>(R.id.start_stop)
    private val idInput = find<EditText>(R.id.id_input)
    private val streamsTitle = find<View>(R.id.streams_title)
    private val streams = find<LinearLayout>(R.id.streams)
    private val recentHeader = find<View>(R.id.recent_header)
    private val recent = find<LinearLayout>(R.id.recent)
    private val empty = find<View>(R.id.empty)

    init {
        startStop.setOnClickListener { onAction() }
        find<Button>(R.id.play).setOnClickListener { playInput() }
        find<Button>(R.id.go_search).setOnClickListener { host.select(MainActivity.TAB_SEARCH, focus = true) }
        find<Button>(R.id.paste).apply {
            // TV remotes have no clipboard worth reading.
            visibility = if (Device.isTv(host)) View.GONE else View.VISIBLE
            setOnClickListener {
                val text = host.clipboardText()
                if (text == null) host.toast(host.getString(R.string.paste_empty)) else idInput.setText(text)
            }
        }
        find<Button>(R.id.clear_recent).setOnClickListener { prefs.clearRecent(); render() }
        idInput.setOnEditorActionListener { _, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (action == EditorInfo.IME_ACTION_GO || enter) { playInput(); true } else false
        }
    }

    override fun focusTarget(): View = startStop

    /** Configure with nothing selected; otherwise start or stop the engine, or connect or disconnect. */
    private fun onAction() {
        val s = EngineController.state
        when {
            EngineSelection.current(host) == null -> host.select(MainActivity.TAB_ENGINES, focus = true)
            s == EngineState.Stopping -> Unit
            s == EngineState.Stopped || s is EngineState.Crashed -> host.startEngine()
            else -> EngineController.stop(host)
        }
    }

    private fun playInput() {
        val text = idInput.text.toString().trim()
        if (text.isEmpty()) return
        when (val r = ContentId.extract(text)) {
            is ContentId.Result.Id -> {
                idInput.error = null
                idInput.hideKeyboard()
                host.play(StreamRef.contentId(r.hex), null)
            }
            ContentId.Result.Infohash -> idInput.error = host.getString(R.string.play_infohash)
            ContentId.Result.None -> idInput.error = host.getString(R.string.play_input_invalid)
        }
    }

    override fun render() {
        renderEngine()
        renderStreams()
        renderRecent()
        empty.visibility = if (EngineController.streams.isEmpty() && prefs.recent.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderEngine() {
        val s = EngineController.state
        val choice = EngineSelection.current(host)
        val remote = if (choice is EngineChoice.Remote) EngineSelection.remote(host) else null
        val active = s != EngineState.Stopped && s !is EngineState.Crashed
        state.text = host.getString(
            when {
                choice == null -> R.string.home_none
                remote != null -> when (s) {
                    EngineState.Starting -> R.string.home_connecting
                    EngineState.Running -> R.string.home_connected
                    is EngineState.Unreachable -> R.string.home_unreachable
                    else -> R.string.home_not_connected
                }
                else -> when (s) {
                    EngineState.Starting -> R.string.state_starting
                    EngineState.Running -> R.string.state_running
                    EngineState.Stopping -> R.string.state_stopping
                    is EngineState.Crashed, is EngineState.Unreachable -> R.string.state_error
                    EngineState.Stopped -> R.string.state_stopped
                }
            },
        )
        val color = when {
            choice == null -> R.color.text_muted
            else -> when (s) {
                EngineState.Running -> R.color.ok
                EngineState.Starting, EngineState.Stopping -> R.color.warn
                is EngineState.Crashed, is EngineState.Unreachable -> R.color.err
                EngineState.Stopped -> R.color.text_muted
            }
        }
        @Suppress("DEPRECATION") // Context.getColor needs API 23; colors here do not depend on the theme.
        dot.setColorFilter(host.resources.getColor(color))
        detail.text = when {
            choice == null -> host.getString(R.string.home_none_detail)
            s is EngineState.Crashed -> s.reason
            s is EngineState.Unreachable -> s.reason
            remote != null -> host.getString(
                R.string.engine_detail_named, remote.name,
                if (s == EngineState.Running) EngineController.remoteVersion?.let { "v$it" } ?: remote.endpoint.display else remote.endpoint.display,
            )
            else -> host.getString(
                R.string.engine_detail_named, host.getString(R.string.engine_local_name),
                "v${EngineController.runningVersion ?: (choice as EngineChoice.Local).version}",
            )
        }
        url.text = when {
            remote != null -> remote.endpoint.base
            else -> {
                val settings = EngineController.effectiveSettings(prefs.settings)
                val urlHost = if (settings.lan) Device.lanAddress() ?: Endpoint.LOOPBACK_HOST else Endpoint.LOOPBACK_HOST
                Endpoint(urlHost, settings.port).base
            }
        }
        url.visibility = if (choice != null && s == EngineState.Running) View.VISIBLE else View.GONE
        startStop.text = host.getString(
            when {
                choice == null -> R.string.action_configure
                remote != null -> if (active) R.string.action_disconnect else R.string.action_connect
                active -> R.string.action_stop
                else -> R.string.action_start
            },
        )
        startStop.isEnabled = s != EngineState.Stopping
    }

    private fun renderStreams() {
        val list = EngineController.streams
        streamsTitle.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        streams.syncRows(list, { it.id }, ::newRow) { row, st -> bindStream(row, st) }
    }

    private fun bindStream(row: View, st: StreamInfo) {
        val title = st.title ?: host.getString(R.string.untitled)
        row.findViewById<TextView>(R.id.title).text = title
        row.findViewById<TextView>(R.id.meta).text = host.joinMeta(
            listOf(
                host.resources.getQuantityString(R.plurals.stream_clients, st.clients, st.clients),
                st.peers?.let { host.resources.getQuantityString(R.plurals.stream_peers, it, it) },
                st.bufferMs?.let { host.getString(R.string.stream_buffer, it / 1000.0) },
            ),
        )
        val play = row.findViewById<Button>(R.id.play)
        val ref = st.ref
        play.visibility = if (ref == null) View.GONE else View.VISIBLE
        val action = ref?.let { r -> View.OnClickListener { host.play(r, st.title) } }
        play.setOnClickListener(action)
        row.setOnClickListener(action)
    }

    private fun renderRecent() {
        val list = prefs.recent
        recentHeader.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        val titles = EngineController.streams.associate { it.id to it.title }
        recent.syncRows(list, { it.ref }, ::newRow) { row, entry ->
            val name = entry.name ?: titles[entry.ref.selector]
            row.findViewById<TextView>(R.id.title).text = name ?: host.getString(R.string.untitled)
            row.findViewById<TextView>(R.id.meta).text = host.joinMeta(listOf(host.kindLabel(entry.ref), entry.ref.short))
            val play = row.findViewById<Button>(R.id.play)
            play.setOnClickListener { host.play(entry.ref, name) }
            row.setOnClickListener { host.play(entry.ref, name) }
            val menu = View.OnLongClickListener { showRecentMenu(entry, name); true }
            play.setOnLongClickListener(menu)
            row.setOnLongClickListener(menu)
        }
    }

    private fun showRecentMenu(entry: RecentEntry, name: String?) {
        val options = listOf(
            host.getString(R.string.recent_menu_play) to { host.play(entry.ref, name) },
            host.getString(R.string.recent_menu_copy) to {
                host.copyText("id", entry.ref.hex)
                host.toast(host.getString(R.string.id_copied))
            },
            host.getString(R.string.recent_menu_remove) to { prefs.removeRecent(entry); render() },
        )
        AlertDialog.Builder(host)
            .setTitle(name ?: entry.ref.short)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun newRow(): View = LayoutInflater.from(host).inflate(R.layout.item_stream, recent, false)
}
