package io.github.outpacestream.android.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import io.github.outpacestream.android.Device
import io.github.outpacestream.android.EngineFiles
import io.github.outpacestream.android.R
import io.github.outpacestream.android.core.EngineSettings
import io.github.outpacestream.android.core.RecentList
import io.github.outpacestream.android.core.Releases
import io.github.outpacestream.android.core.SearchFailure
import io.github.outpacestream.android.core.SearchGroup
import io.github.outpacestream.android.core.SearchItem
import io.github.outpacestream.android.core.SearchJson
import io.github.outpacestream.android.core.SearchPage
import io.github.outpacestream.android.core.StreamUrls
import io.github.outpacestream.android.engine.EngineController
import io.github.outpacestream.android.engine.EngineService
import io.github.outpacestream.android.engine.EngineState
import io.github.outpacestream.android.net.Http
import io.github.outpacestream.android.net.HttpStatusException
import kotlin.math.roundToInt

/**
 * Stream-name search through the engine's opt-in `GET /search`. Results carry infohashes, and
 * each one has a Play button that hands it to [PlayActivity] as an explicit `ih:` selector.
 */
class SearchScreen(host: MainActivity, parent: ViewGroup) : Screen(host, parent, R.layout.screen_search) {
    private val scroll = find<ScrollView>(R.id.search_scroll)
    private val query = find<EditText>(R.id.query)
    private val searchButton = find<Button>(R.id.search)
    private val notice = find<View>(R.id.notice)
    private val noticeTitle = find<TextView>(R.id.notice_title)
    private val noticeText = find<TextView>(R.id.notice_text)
    private val noticeAction = find<Button>(R.id.notice_action)
    private val progress = find<ProgressBar>(R.id.progress)
    private val status = find<TextView>(R.id.status)
    private val queriesTitle = find<View>(R.id.queries_title)
    private val queriesScroll = find<View>(R.id.queries_scroll)
    private val queries = find<LinearLayout>(R.id.queries)
    private val results = find<LinearLayout>(R.id.results)
    private val more = find<Button>(R.id.more)

    private var shownQuery: String? = null
    private var nextPage = 0
    private var total = 0
    private var groupsShown = 0
    private var groupsSeen = 0
    private var inFlight = false

    /** Bumped per request, so a late answer for an older query is dropped. */
    private var generation = 0

    init {
        searchButton.setOnClickListener { submit(query.text.toString()) }
        query.setOnEditorActionListener { _, action, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (action == EditorInfo.IME_ACTION_SEARCH || enter) { submit(query.text.toString()); true } else false
        }
        more.setOnClickListener { loadMore() }
    }

    override fun focusTarget(): View = query

    override fun render() {
        renderNotice()
        renderQueries()
        searchButton.isEnabled = !inFlight
    }

    private sealed interface Block {
        data object NoEngine : Block
        data object Off : Block
        data class Old(val version: String) : Block
        data object NeedsRestart : Block
    }

    /** What stops a search from working, decided from local facts before any request. */
    private fun block(): Block? {
        val active = EngineFiles.activeVersion(host) ?: return Block.NoEngine
        if (!prefs.settings.search) return Block.Off
        val version = EngineController.runningVersion ?: active
        if (Releases.compareVersions(version, EngineSettings.SEARCH_MIN_VERSION) < 0) return Block.Old(version)
        val running = EngineController.runningSettings
        if (EngineController.state == EngineState.Running && running != null && !running.search) return Block.NeedsRestart
        return null
    }

    private fun renderNotice() {
        val b = block()
        notice.visibility = if (b == null) View.GONE else View.VISIBLE
        when (b) {
            null -> Unit
            Block.NoEngine -> showNotice(R.string.notice_none_title, host.getString(R.string.notice_none_text), R.string.action_download_engine_short) {
                host.open(VersionsActivity::class.java)
            }
            Block.Off -> showNotice(R.string.notice_off_title, host.getString(R.string.notice_off_text), R.string.notice_off_action) {
                prefs.settings = prefs.settings.copy(search = true)
                val s = EngineController.state
                if (s == EngineState.Running || s == EngineState.Starting) EngineController.restart(host)
                render()
                query.requestFocus()
            }
            is Block.Old -> showNotice(
                R.string.notice_old_title,
                host.getString(R.string.notice_old_text, EngineSettings.SEARCH_MIN_VERSION, b.version),
                R.string.notice_old_action,
            ) { host.open(VersionsActivity::class.java) }
            Block.NeedsRestart -> showNotice(R.string.notice_restart_title, host.getString(R.string.notice_restart_text), R.string.action_restart_short) {
                EngineController.restart(host)
            }
        }
    }

    private fun showNotice(title: Int, text: String, action: Int, onAction: () -> Unit) {
        noticeTitle.setText(title)
        noticeText.text = text
        noticeAction.setText(action)
        noticeAction.setOnClickListener { onAction() }
    }

    private fun renderQueries() {
        val list = prefs.recentQueries
        val show = list.isNotEmpty() && !inFlight
        queriesTitle.visibility = if (show) View.VISIBLE else View.GONE
        queriesScroll.visibility = queriesTitle.visibility
        if (!show) return
        queries.syncRows(list, { it }, { LayoutInflater.from(host).inflate(R.layout.item_chip, queries, false) }) { chip, q ->
            (chip as Button).text = q
            chip.setOnClickListener {
                query.setText(q)
                query.setSelection(q.length)
                submit(q)
            }
        }
    }

    private fun submit(raw: String) {
        val q = raw.trim()
        if (q.isEmpty() || inFlight) return
        query.hideKeyboard()
        if (block() != null) {
            renderNotice()
            noticeAction.requestFocus()
            return
        }
        prefs.recentQueries = RecentList.addQuery(prefs.recentQueries, q)
        shownQuery = q
        nextPage = 0
        total = 0
        groupsShown = 0
        groupsSeen = 0
        results.removeAllViews()
        more.visibility = View.GONE
        run(q, 0)
    }

    /** Next page, under the same checks as a new search: search may have been turned off since. */
    private fun loadMore() {
        val q = shownQuery ?: return
        if (block() != null) {
            renderNotice()
            noticeAction.requestFocus()
            return
        }
        run(q, nextPage)
    }

    private fun run(q: String, page: Int) {
        if (inFlight) return
        inFlight = true
        val gen = ++generation
        progress.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.setText(R.string.search_searching)
        more.isEnabled = false
        render()
        searchExecutor.execute {
            val outcome = fetch(q, page)
            host.onMain {
                if (gen != generation) return@onMain
                inFlight = false
                progress.visibility = View.GONE
                more.isEnabled = true
                when (outcome) {
                    is Outcome.Page -> showPage(q, page, outcome.page)
                    is Outcome.Failed -> status.text = outcome.message
                }
                render()
            }
        }
    }

    private sealed interface Outcome {
        data class Page(val page: SearchPage) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Runs on [searchExecutor]: starts the engine if needed, then asks it for one page. */
    private fun fetch(q: String, page: Int): Outcome {
        if (EngineController.state != EngineState.Running) {
            host.onMain {
                status.setText(R.string.search_starting_engine)
                host.askNotificationPermission()
            }
            // Started from this thread so the Starting state is set before the wait begins.
            EngineController.start(host)
            if (!EngineController.awaitRunning(EngineService.START_TIMEOUT_MS + 5_000)) {
                val reason = (EngineController.state as? EngineState.Crashed)?.reason ?: host.getString(R.string.state_stopped)
                return Outcome.Failed(host.getString(R.string.search_err_engine, reason))
            }
            host.onMain { status.setText(R.string.search_searching) }
        }
        val port = EngineController.effectiveSettings(prefs.settings).port
        return try {
            Outcome.Page(SearchJson.parse(Http.get(StreamUrls.search(port, q, page, PAGE_SIZE), readTimeout = READ_TIMEOUT_MS)))
        } catch (e: HttpStatusException) {
            Outcome.Failed(failureText(SearchFailure.fromStatus(e.code), e.message))
        } catch (e: Exception) {
            Outcome.Failed(failureText(SearchFailure.Other, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun failureText(f: SearchFailure, detail: String?): String = when (f) {
        SearchFailure.Unavailable -> host.getString(R.string.search_err_unavailable)
        SearchFailure.Invalid -> host.getString(R.string.search_err_invalid)
        SearchFailure.RateLimited -> host.getString(R.string.search_err_rate)
        SearchFailure.Busy -> host.getString(R.string.search_err_busy)
        SearchFailure.Upstream -> host.getString(R.string.search_err_upstream)
        SearchFailure.Timeout -> host.getString(R.string.search_err_timeout)
        SearchFailure.Other -> host.getString(R.string.search_err_other, detail.orEmpty())
    }

    private fun showPage(q: String, page: Int, result: SearchPage) {
        total = result.total
        nextPage = page + 1
        val firstNew = results.childCount
        for (g in result.groups) results.addView(groupView(g))
        groupsShown += result.groups.size
        // A full page from the engine means there may be more, even if some groups were dropped.
        groupsSeen += result.rawGroups
        val hasMore = result.rawGroups >= PAGE_SIZE && groupsSeen < total
        more.visibility = if (hasMore) View.VISIBLE else View.GONE
        status.text = when {
            groupsShown == 0 -> host.getString(R.string.search_empty, q)
            else -> {
                val count = host.resources.getQuantityString(R.plurals.search_count, total, total)
                if (hasMore) host.getString(R.string.search_count_more, count, groupsShown) else count
            }
        }
        // On a remote, land on the first new result instead of making the user walk down to it.
        if (Device.isTv(host) || page > 0) {
            results.getChildAt(firstNew)?.findViewById<View>(R.id.play)?.let { first ->
                first.requestFocus()
                if (page > 0) scroll.post { scroll.smoothScrollTo(0, results.top + results.getChildAt(firstNew).top) }
            }
        }
    }

    private fun groupView(g: SearchGroup): View {
        val inflater = LayoutInflater.from(host)
        val card = inflater.inflate(R.layout.item_search_group, results, false)
        card.findViewById<TextView>(R.id.group_name).text = g.name.ifEmpty { host.getString(R.string.untitled) }
        val items = card.findViewById<LinearLayout>(R.id.items)
        for (item in g.items) {
            val row = inflater.inflate(R.layout.item_search_result, items, false)
            val title = row.findViewById<TextView>(R.id.title)
            val sameAsGroup = item.name.isEmpty() || (g.items.size == 1 && item.name.equals(g.name, ignoreCase = true))
            title.visibility = if (sameAsGroup) View.GONE else View.VISIBLE
            title.text = item.name
            row.findViewById<TextView>(R.id.meta).text = meta(item)
            if (item.disabled) row.alpha = 0.6f
            val name = item.name.ifEmpty { g.name }.ifEmpty { null }
            val play = row.findViewById<Button>(R.id.play)
            play.contentDescription = host.getString(R.string.action_play) + " " + (name ?: "")
            play.setOnClickListener { host.play(item.ref, name) }
            play.setOnLongClickListener {
                host.copyText("id", item.ref.hex)
                host.toast(host.getString(R.string.id_copied))
                true
            }
            items.addView(row)
        }
        return card
    }

    private fun meta(item: SearchItem): String = host.joinMeta(
        listOf(
            item.categories.joinToString(", ").ifEmpty { null },
            item.languages.joinToString(", ") { it.uppercase() }.ifEmpty { null },
            item.availability?.let { host.getString(R.string.search_availability, (it * 100).roundToInt()) },
            if (item.disabled) host.getString(R.string.search_disabled_badge) else null,
            item.ref.short,
        ),
    )

    private companion object {
        /**
         * Channels per page. The engine caps a page at 200 streams in total and answers 502 above
         * that, then cools down; its default of 10 keeps broad queries under the cap.
         */
        const val PAGE_SIZE = 10

        /** The engine gives up on a search after ten seconds; leave room for its answer. */
        const val READ_TIMEOUT_MS = 15_000
    }
}
