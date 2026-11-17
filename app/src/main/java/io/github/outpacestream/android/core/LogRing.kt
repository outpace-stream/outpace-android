package io.github.outpacestream.android.core

/** The last [capacity] engine log lines. Thread-safe; old lines are dropped, never buffered. */
class LogRing(private val capacity: Int) {
    private val lines = ArrayDeque<String>(capacity)
    private var revision = 0L

    @Synchronized
    fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(if (line.length > MAX_LINE) line.substring(0, MAX_LINE) else line)
        revision++
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    /**
     * Advances on every change, so a reader can tell its copy is stale even when a full ring
     * still has the same size and last line.
     */
    @Synchronized
    fun revision(): Long = revision

    @Synchronized
    fun clear() {
        lines.clear()
        revision++
    }

    companion object {
        const val MAX_LINE = 2000
    }
}
