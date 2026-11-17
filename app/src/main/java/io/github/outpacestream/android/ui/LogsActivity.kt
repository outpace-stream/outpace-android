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
    private var shown = -1
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
        val lines = EngineController.log.snapshot()
        val signature = lines.size * 31 + (lines.lastOrNull()?.hashCode() ?: 0)
        if (signature == shown) return
        if (shown != -1 && (!atBottom() || text.hasSelection())) return
        shown = signature
        text.text = if (lines.isEmpty()) getString(R.string.logs_empty) else lines.joinToString("\n")
        // Scroll once the new text is laid out. This runs after the TextView's own pre-draw pass,
        // which moves a selectable TextView's reset cursor (offset 0) into view, scrolling to the top.
        val observer = scroll.viewTreeObserver
        observer.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    scroll.scrollTo(0, text.bottom)
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
