package io.github.outpacestream.android.core

/** Pulls an AceStream content id out of a link, a shared message or a pasted string. */
object ContentId {
    sealed interface Result {
        data class Id(val hex: String) : Result

        /** The text names an infohash, which the app does not hand off. */
        data object Infohash : Result

        data object None : Result
    }

    private const val HEX = "[0-9a-fA-F]"
    private val queryId = Regex("(?i)[?&](?:content_id|id)=($HEX{40})(?!$HEX)")
    private val schemeId = Regex("(?i)acestream://(?:cid:)?($HEX{40})(?!$HEX)")
    private val explicitInfohash =
        Regex("(?i)([?&]infohash=|magnet:\\?\\S*btih:|acestream://ih:|(?<![0-9a-z])ih:$HEX{40})")
    private val whitespace = Regex("\\s+")
    private val percentEscape = Regex("%($HEX{2})")
    private val bareId = Regex("(?<!$HEX)($HEX{40})(?!$HEX)")

    fun extract(text: String): Result {
        // A getstream link reads as it does when opened; any id in the text still wins over an infohash.
        var getstreamInfohash = false
        for (token in text.split(whitespace)) {
            when (val r = GetStreamLink.parse(token)) {
                is Result.Id -> return r
                Result.Infohash -> getstreamInfohash = true
                else -> Unit
            }
        }
        val decoded = percentDecode(text)
        queryId.find(decoded)?.let { return Result.Id(it.groupValues[1].lowercase()) }
        schemeId.find(decoded)?.let { return Result.Id(it.groupValues[1].lowercase()) }
        if (getstreamInfohash || explicitInfohash.containsMatchIn(decoded)) return Result.Infohash
        bareId.find(text)?.let { return Result.Id(it.groupValues[1].lowercase()) }
        return Result.None
    }

    /** Decodes `%XX` escapes so encoded parameter names classify like plain ones; other text is kept. */
    private fun percentDecode(text: String): String {
        if ('%' !in text) return text
        return percentEscape.replace(text) { it.groupValues[1].toInt(16).toChar().toString() }
    }

    fun isValid(hex: String): Boolean = hex.length == 40 && hex.all { it in '0'..'9' || it in 'a'..'f' }
}
