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

/** How many peers may download from this device. Seeding off overrides all of these. */
enum class UploadLimit(override val key: String, override val env: Map<String, String>) : EnginePreset {
    NORMAL("normal", emptyMap()),
    LOW(
        "low",
        mapOf("OUTPACE_MAX_INBOUND" to "16", "OUTPACE_MAX_INBOUND_PER_STREAM" to "4", "OUTPACE_MAX_UNCHOKED" to "2"),
    ),
    /** `0` unchoked still leaves the engine's single rotating optimistic slot. */
    MINIMAL(
        "minimal",
        mapOf("OUTPACE_MAX_INBOUND" to "4", "OUTPACE_MAX_INBOUND_PER_STREAM" to "2", "OUTPACE_MAX_UNCHOKED" to "0"),
    ),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

/**
 * Low-memory mode: seeding pieces go to the app's cache directory instead of RAM, and the
 * per-stream startup queue, seed store and per-viewer queue shrink from 128 MiB, 128 MiB and 256.
 */
object LowMemory {
    const val BYTES = 32L * 1024 * 1024

    val env: Map<String, String> = mapOf(
        "OUTPACE_CACHE_TYPE" to "disk",
        "OUTPACE_SEED_STORE_BYTES" to BYTES.toString(),
        "OUTPACE_PREBUFFER_BYTES" to BYTES.toString(),
        "OUTPACE_SESSION_BUFFER" to "64",
    )
}
