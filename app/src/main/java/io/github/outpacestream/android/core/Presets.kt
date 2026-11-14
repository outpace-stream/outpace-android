package io.github.outpacestream.android.core

/**
 * A named group of engine variables. Presets are stored by [key], never by position, and each one
 * sets only what differs from the engine's defaults, so the default emits nothing.
 */
interface EnginePreset {
    val key: String
    val env: Map<String, String>
}

/** How much media the engine collects before a stream starts playing. */
enum class StartupBuffer(override val key: String, override val env: Map<String, String>) : EnginePreset {
    SMOOTH("smooth", emptyMap()),
    BALANCED("balanced", mapOf("OUTPACE_PREBUFFER_MS" to "15000")),
    FAST("fast", mapOf("OUTPACE_PREBUFFER_MS" to "5000")),
    OFF("off", mapOf("OUTPACE_PREBUFFER_MS" to "0")),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SMOOTH
    }
}

/**
 * How many HLS segments are ready before the first playlist. Fast is the engine's documented
 * fast-start profile: 2 startup segments stay within the 8-segment window, and the 45 s startup
 * timeout stays above the 4 s segment.
 */
enum class HlsStart(override val key: String, override val env: Map<String, String>) : EnginePreset {
    DEFAULT("default", emptyMap()),
    FAST("fast", mapOf("OUTPACE_HLS_STARTUP_SEGMENTS" to "2", "OUTPACE_HLS_SEGMENT_DURATION_MS" to "4000")),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
