package io.github.outpacestream.android.core

/**
 * Hands values to whichever receiver is attached now. With none attached it keeps only the latest
 * value and delivers it once on the next attach, so a result that arrives while a screen is
 * stopped or being recreated is shown later instead of lost. Holds at most one value.
 */
class Mailbox<T : Any> {
    private var receiver: ((T) -> Unit)? = null
    private var kept: T? = null

    fun post(value: T) {
        val r = synchronized(this) {
            receiver ?: run { kept = value; null }
        }
        r?.invoke(value)
    }

    fun attach(r: (T) -> Unit) {
        val pending = synchronized(this) {
            receiver = r
            kept.also { kept = null }
        }
        pending?.let(r)
    }

    /** Detaches [r] only if it is still the attached receiver, so a stale screen can't detach a new one. */
    fun detach(r: (T) -> Unit) = synchronized(this) {
        if (receiver === r) receiver = null
    }

    fun clear() = synchronized(this) { kept = null }
}
