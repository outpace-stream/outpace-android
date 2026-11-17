package io.github.outpacestream.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import io.github.outpacestream.android.R
import io.github.outpacestream.android.engine.EngineController

class LogsActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView
    private var shown = -1L
    private val tick = object : Runnable {
        override fun run() {
            render()
            mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.logs_title)
        setContentView(R.layout.activity_logs)
        text = findViewById(R.id.text)
        scroll = findViewById(R.id.scroll)
        findViewById<Button>(R.id.copy).setOnClickListener {
            copyText("outpace logs", logText())
            toast(getString(R.string.play_copied))
        }
        findViewById<Button>(R.id.share).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, logText())
            startActivity(Intent.createChooser(send, getString(R.string.logs_share)))
        }
    }

    override fun onStart() {
        super.onStart()
        mainHandler.post(tick)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(tick)
        super.onStop()
    }

    private fun logText() = EngineController.log.snapshot().joinToString("\n")

    /**
     * Follows new lines only while the view sits at the bottom. Scrolled up, or with text
     * selected, the shown text is left alone: the ring drops its oldest lines once full, so
     * replacing the text would shift what the user is reading, and the next tick after they
     * return to the bottom catches up.
     */
    private fun render() {
        val revision = EngineController.log.revision()
        if (revision == shown) return
        if (shown != -1L && (!atBottom() || text.hasSelection())) return
        shown = revision
        val lines = EngineController.log.snapshot()
        text.text = if (lines.isEmpty()) getString(R.string.logs_empty) else lines.joinToString("\n")
        // Replacing the text puts a selectable TextView's cursor at offset 0, and its own pre-draw
        // pass, which runs before this listener, may start an animated scroll toward it. Jump to
        // the end, then replace that animation with a zero-velocity fling that stops in place.
        val observer = scroll.viewTreeObserver
        observer.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    scroll.scrollTo(0, text.bottom)
                    scroll.fling(0)
                    return true
                }
            },
        )
    }

    /** Within a line of the end, so a fling that stops just short still counts as following. */
    private fun atBottom(): Boolean = scroll.scrollY + scroll.height - scroll.paddingBottom >= text.bottom - text.lineHeight

    private companion object {
        const val REFRESH_MS = 1_000L
    }
}
