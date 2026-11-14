package io.github.outpacestream.android.core

/** Whether a `/healthz` body comes from an Outpace engine. */
object RemoteHealth {
    fun isOk(body: String): Boolean = body.trim() == "ok"
}
