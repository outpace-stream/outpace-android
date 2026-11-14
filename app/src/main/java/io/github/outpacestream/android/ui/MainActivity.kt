package io.github.outpacestream.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
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
import io.github.outpacestream.android.core.StreamRef
import io.github.outpacestream.android.core.UpdatePolicy
import io.github.outpacestream.android.engine.EngineController
import io.github.outpacestream.android.engine.EngineSelection
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
    private val listener: () -> Unit = { screens[current].render() }

    /** App update dialogs, shared by the automatic prompt and the Settings screen. */
    val updateFlow by lazy { UpdateFlow(this) }

    /** Set when another one-time prompt showed during this activity, so the update dialog waits. */
    private var otherPromptShown = false
    private var started = false
    /** A release check finished while stopped; prompt on the next resume instead. */
    private var promptPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
        EngineSelection.resumeRemote(this)
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
        EngineController.removeListener(listener)
        EngineController.screenHidden()
        super.onStop()
    }

    // API 33+ goes through the OnBackInvokedCallback in updateBackHandling(); this covers older devices.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Kept for API levels without OnBackInvokedDispatcher")
    override fun onBackPressed() {
        if (current != TAB_HOME) select(TAB_HOME, focus = false) else super.onBackPressed()
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
        current = tab
        screens.forEachIndexed { i, s -> s.view.visibility = if (i == tab) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { i, v -> v.isSelected = i == tab }
        val screen = screens[tab]
        screen.onShown()
        screen.render()
        if (focus) screen.focusTarget()?.requestFocus()
        updateBackHandling()
    }

    /** On API 33+ (and always for target 36), back is routed through the dispatcher. */
    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = onBackInvokedDispatcher
        val registered = backCallback as OnBackInvokedCallback?
        if (current != TAB_HOME && registered == null) {
            val cb = OnBackInvokedCallback { select(TAB_HOME, focus = false); navItems[TAB_HOME].requestFocus() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (current == TAB_HOME && registered != null) {
            dispatcher.unregisterOnBackInvokedCallback(registered)
            backCallback = null
        }
    }

    fun open(cls: Class<*>) = startActivity(Intent(this, cls))

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
