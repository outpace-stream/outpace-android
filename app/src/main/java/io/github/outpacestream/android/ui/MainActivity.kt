package io.github.outpacestream.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.outpacestream.android.BuildConfig
import io.github.outpacestream.android.Prefs
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.EngineTarget
import io.github.outpacestream.android.core.StreamRef
import io.github.outpacestream.android.core.UpdatePolicy
import io.github.outpacestream.android.core.WhatsNew
import io.github.outpacestream.android.engine.EngineController
import io.github.outpacestream.android.engine.EngineSelection
import io.github.outpacestream.android.engine.EngineState
import io.github.outpacestream.android.net.AppUpdates
import io.github.outpacestream.android.net.EngineReleases

/** One tab of the main screen. Its view is inflated once and kept while the activity lives. */
abstract class Screen(protected val host: MainActivity, parent: ViewGroup, layout: Int) {
    val view: View = LayoutInflater.from(host).inflate(layout, parent, false)
    protected val prefs: Prefs get() = host.prefs

    protected fun <T : View> find(id: Int): T = view.findViewById(id)

    /** Called with fresh engine state while the tab is visible. */
    abstract fun render()

    open fun onShown() = Unit

    /** The tab became visible on screen (selected while the activity is started). */
    open fun onVisible() = Unit

    /** The tab left the screen: another tab was selected, or the activity stopped. */
    open fun onInvisible() = Unit

    /** The view that should take D-pad focus when the tab is opened from the navigation. */
    open fun focusTarget(): View? = null
}

/**
 * The app's home: a navigation bar (bottom on portrait phones, a side rail on landscape screens
 * and TV) switching between Home, Search and Settings. Versions, Logs and the play hand-off stay
 * separate screens.
 */
class MainActivity : Activity() {
    lateinit var prefs: Prefs
        private set
    private lateinit var screens: List<Screen>
    private val navItems = ArrayList<View>()
    private var current = TAB_HOME
    private var backCallback: Any? = null
    private var started = false
    private val listener: () -> Unit = { screens[current].render() }
    private val exitListener: () -> Unit = { finishAndRemoveTask() }

    /** App update dialogs, shared by the automatic prompt and the Settings screen. */
    val updateFlow by lazy { UpdateFlow(this) }

    /** Set when another one-time prompt showed during this activity, so the update dialog waits. */
    private var otherPromptShown = false
    /** A release check finished while stopped; prompt on the next resume instead. */
    private var promptPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        EngineController.addExitListener(exitListener)
        prefs = Prefs(this)
        updateFlow.restoreState(savedInstanceState)
        val content = findViewById<FrameLayout>(R.id.content)
        screens = listOf(HomeScreen(this, content), SearchScreen(this, content), SettingsScreen(this, content))
        for (s in screens) {
            s.view.visibility = View.GONE
            content.addView(s.view)
        }
        buildNav()
        select(savedInstanceState?.getInt(STATE_TAB) ?: tabFrom(intent) ?: TAB_HOME, focus = false)
        navItems[current].requestFocus()
        if (savedInstanceState == null) maybeShowWhatsNew() else if (updateFlow.resumeWhatsNew()) otherPromptShown = true
    }

    override fun onDestroy() {
        EngineController.removeExitListener(exitListener)
        updateFlow.destroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabFrom(intent)?.let { select(it, focus = true) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, current)
        updateFlow.saveState(outState)
    }

    override fun onStart() {
        super.onStart()
        started = true
        EngineController.addListener(listener)
        EngineController.screenShown()
        EngineSelection.followRemote(this)
        screens[current].onVisible()
        screens[current].render()
        updateFlow.attach()
        checkLatest()
    }

    override fun onResume() {
        super.onResume()
        updateFlow.onResumed()
        if (promptPending) {
            promptPending = false
            maybePromptUpdate()
        }
    }

    override fun onStop() {
        started = false
        updateFlow.detach()
        screens[current].onInvisible()
        EngineController.removeListener(listener)
        EngineController.screenHidden()
        super.onStop()
    }

    // API 33+ goes through the OnBackInvokedCallback in updateBackHandling(); this covers older devices.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Kept for API levels without OnBackInvokedDispatcher")
    override fun onBackPressed() = onBack()

    /** Back leads to Home, and from Home to exiting; Home or recents leave the app running instead. */
    private fun onBack() {
        if (current != TAB_HOME) {
            select(TAB_HOME, focus = false)
            navItems[TAB_HOME].requestFocus()
        } else {
            confirmExit()
        }
    }

    /**
     * Exiting stops whatever runs, so it asks first while an engine runs or a remote is connected,
     * and offers to keep the app in the background instead. With nothing running it just closes.
     */
    private fun confirmExit() {
        val s = EngineController.sessionState
        if (s == EngineState.Stopped || s is EngineState.Crashed) {
            finishAndRemoveTask()
            return
        }
        val local = EngineController.sessionTarget is EngineTarget.Local
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(if (local) R.string.exit_local else R.string.exit_message)
            .setPositiveButton(R.string.action_exit) { _, _ -> EngineController.exitApp(this) }
            .setNeutralButton(R.string.action_keep_background) { _, _ -> moveTaskToBack(true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun tabFrom(intent: Intent?): Int? = intent?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it in 0..TAB_SETTINGS }

    private fun buildNav() {
        val nav = findViewById<LinearLayout>(R.id.nav)
        val vertical = nav.orientation == LinearLayout.VERTICAL
        val tabs = listOf(
            R.drawable.ic_home to R.string.tab_home,
            R.drawable.ic_search to R.string.tab_search,
            R.drawable.ic_tune to R.string.tab_settings,
        )
        tabs.forEachIndexed { i, (icon, label) ->
            val item = LayoutInflater.from(this).inflate(R.layout.nav_item, nav, false)
            item.findViewById<ImageView>(R.id.nav_icon).setImageResource(icon)
            item.findViewById<TextView>(R.id.nav_label).setText(label)
            item.contentDescription = getString(label)
            item.setOnClickListener { select(i, focus = false) }
            if (vertical) {
                item.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)).apply {
                    bottomMargin = dp(6)
                }
            }
            nav.addView(item)
            navItems += item
        }
    }

    /** Shows [tab]; with [focus], moves D-pad focus into its content. */
    fun select(tab: Int, focus: Boolean) {
        if (started && tab != current) screens[current].onInvisible()
        val changed = tab != current
        current = tab
        screens.forEachIndexed { i, s -> s.view.visibility = if (i == tab) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { i, v -> v.isSelected = i == tab }
        val screen = screens[tab]
        screen.onShown()
        if (started && changed) screen.onVisible()
        screen.render()
        if (focus) screen.focusTarget()?.requestFocus()
        updateBackHandling()
    }

    /**
     * On API 33+ (and always for target 36), back is routed through the dispatcher. The callback
     * stays registered on Home too, since back from Home asks before exiting.
     */
    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33 || backCallback != null) return
        val cb = OnBackInvokedCallback { onBack() }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        backCallback = cb
    }

    fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    /** Redraws the visible tab, for changes made outside its own render cycle. */
    fun renderCurrent() = screens[current].render()

    fun play(ref: StreamRef, name: String?) = startActivity(playIntent(ref, name))

    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** Starts the engine from a screen, with the one-time prompts that a first start needs. */
    fun startEngine() {
        askNotificationPermission()
        if (BatteryPrompt.maybeShow(this)) otherPromptShown = true
        EngineController.start(this)
    }

    private fun checkLatest() {
        background.execute {
            runCatching { EngineReleases(this).latest() }
            val app = AppUpdates(this)
            runCatching { app.cleanUp() }
            app.latest(force = false)
            onMain {
                screens[current].render()
                // A dialog shown while stopped could be lost and still use up the day's prompt.
                if (started) maybePromptUpdate() else promptPending = true
            }
        }
    }

    /** Once per new version: record this build, and after an update show what it brought. */
    private fun maybeShowWhatsNew() {
        val seen = prefs.whatsNewSeen
        val code = BuildConfig.VERSION_CODE
        val name = BuildConfig.VERSION_NAME
        if (seen?.code == code) return
        val info = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val updated = info != null && info.lastUpdateTime > info.firstInstallTime
        val decision = WhatsNew.decide(seen, code, name, updated)
        // Record first, so a failed fetch or a quick restart never shows it twice.
        prefs.whatsNewSeen = WhatsNew.Seen(code, name)
        if (decision is WhatsNew.Decision.Show) {
            otherPromptShown = true
            updateFlow.showWhatsNew(decision.previous, name, quiet = true)
        }
    }

    private fun maybePromptUpdate() {
        val now = System.currentTimeMillis()
        val latest = prefs.appLatestVersion ?: return
        val inputs = UpdatePolicy.PromptInputs(
            installed = BuildConfig.VERSION_NAME,
            latest = latest,
            notify = prefs.appUpdateNotify,
            streamActive = EngineController.streams.isNotEmpty(),
            otherPromptShown = otherPromptShown,
            promptedAt = prefs.appPromptedAt,
            now = now,
        )
        if (!UpdatePolicy.shouldPrompt(inputs) || updateFlow.isPromptShowing() || updateFlow.busy) return
        // Record first, so a rotation or quick return to the screen never shows a second dialog.
        prefs.appPromptedAt = now
        updateFlow.showPrompt(BuildConfig.VERSION_NAME, latest)
    }

    companion object {
        const val TAB_HOME = 0
        const val TAB_SEARCH = 1
        const val TAB_SETTINGS = 2
        const val EXTRA_TAB = "io.github.outpacestream.android.TAB"
        private const val STATE_TAB = "tab"
    }
}
