package io.github.outpacestream.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.License
import io.github.outpacestream.android.core.Notices

/**
 * Open-source license notices. Without [EXTRA_NOTICE] it lists every component; with it, it shows
 * that component's notice and full license text, so back returns to the list with its focus intact.
 */
class LicensesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)
        val list = findViewById<LinearLayout>(R.id.list)
        val notice = Notices.ALL.getOrNull(intent.getIntExtra(EXTRA_NOTICE, -1))
        if (notice == null) {
            setTitle(R.string.licenses_title)
            findViewById<TextView>(R.id.intro).visibility = View.VISIBLE
            Notices.ALL.forEachIndexed { i, n ->
                list.addRow(getString(R.string.licenses_row, n.name, n.license.title, n.copyright)) {
                    startActivity(Intent(this, LicensesActivity::class.java).putExtra(EXTRA_NOTICE, i))
                }
            }
            return
        }
        title = notice.name
        val text = resources.openRawResource(rawOf(notice.license)).bufferedReader().use { it.readText() }
        findViewById<TextView>(R.id.detail).apply {
            this.text = getString(R.string.licenses_detail, notice.copyright, notice.detail, notice.url, text)
            visibility = View.VISIBLE
        }
        list.visibility = View.GONE
        // A TV remote scrolls the text only when the scroll view holds focus.
        findViewById<ScrollView>(R.id.scroll).apply { isFocusable = true; requestFocus() }
    }

    private fun rawOf(license: License): Int = when (license) {
        License.AGPL_3_0 -> R.raw.license_agpl_3_0
        License.APACHE_2_0 -> R.raw.license_apache_2_0
    }

    private companion object {
        const val EXTRA_NOTICE = "notice"
    }
}
