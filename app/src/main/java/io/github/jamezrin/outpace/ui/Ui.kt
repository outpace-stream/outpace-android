package io.github.jamezrin.outpace.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import io.github.jamezrin.outpace.R
import io.github.jamezrin.outpace.core.StreamRef
import java.util.concurrent.Executors

/** One shared background thread for screen work (network, disk). */
internal val background = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-background") }

/**
 * Search gets its own thread: a search can take up to ten seconds, and it must neither wait
 * behind an engine start on [background] nor hold that thread up.
 */
internal val searchExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-search") }

internal val mainHandler = Handler(Looper.getMainLooper())

internal fun Activity.onMain(block: () -> Unit) = runOnUiThread { if (!isFinishing) block() }

internal fun Context.toast(text: CharSequence) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

internal fun Context.copyText(label: String, text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun Context.clipboardText(): String? {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return try {
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}

internal fun View.hideKeyboard() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(windowToken, 0)
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/** Opens the play hand-off for a stream the app already knows the kind of. */
internal fun Context.playIntent(ref: StreamRef, name: String?): Intent =
    Intent().setClassName(this, PlayActivity.IN_APP_ALIAS)
        .putExtra(PlayActivity.EXTRA_REF, ref.selector)
        .apply { if (!name.isNullOrBlank()) putExtra(PlayActivity.EXTRA_NAME, name) }

internal fun Context.kindLabel(ref: StreamRef): String =
    getString(if (ref.kind == StreamRef.Kind.Infohash) R.string.kind_ih else R.string.kind_cid)

internal fun Context.joinMeta(parts: List<String?>): String =
    parts.filterNot { it.isNullOrBlank() }.joinToString(getString(R.string.meta_separator))

/** A full-width focusable row; plain buttons keep D-pad navigation predictable. */
internal fun LinearLayout.addRow(text: CharSequence, onClick: (() -> Unit)?): Button {
    val b = Button(context)
    b.text = text
    b.isAllCaps = false
    b.textAlignment = Button.TEXT_ALIGNMENT_VIEW_START
    b.setBackgroundResource(R.drawable.bg_card_focusable)
    b.minHeight = context.dp(56)
    b.isEnabled = onClick != null
    onClick?.let { action -> b.setOnClickListener { action() } }
    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    lp.bottomMargin = context.dp(8)
    addView(b, lp)
    return b
}

/**
 * Rebuilds [container] only when the row keys change, otherwise rebinds the existing rows in
 * place. Polling refreshes the screen every few seconds, and rebuilding would steal D-pad focus.
 */
internal fun <T> ViewGroup.syncRows(items: List<T>, key: (T) -> Any, create: () -> View, bind: (View, T) -> Unit) {
    val keys = items.map(key)
    @Suppress("UNCHECKED_CAST")
    val shown = getTag(R.id.row_keys) as? List<Any>
    if (shown != keys) {
        removeAllViews()
        repeat(items.size) { addView(create()) }
        setTag(R.id.row_keys, keys)
    }
    items.forEachIndexed { i, item -> bind(getChildAt(i), item) }
}
