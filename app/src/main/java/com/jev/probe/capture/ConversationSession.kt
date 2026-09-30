package com.jev.probe.capture

/** Main-thread session state. A return to the same chat never revives an old request. */
internal class ConversationSession {
    data class Target(
        val pkg: String,
        val windowId: Int,
        val title: String?,
        val messagesSignature: String? = null
    )
    data class Token(val target: Target, val revision: Long)

    enum class Check { CURRENT, UNAVAILABLE, CHANGED, STALE }

    /** A missing node tree is incomplete evidence, never proof of a chat change.
     * This read is pure: callers can retry without invalidating a live request. */
    fun check(token: Token, pkg: String?, windowId: Int?, live: Target?): Check {
        if (!accepts(token)) return Check.STALE
        if (pkg == null || windowId == null) return Check.UNAVAILABLE
        if (pkg != token.target.pkg || windowId != token.target.windowId) return Check.CHANGED
        if (live == null) return Check.UNAVAILABLE
        return if (live == token.target) Check.CURRENT else Check.CHANGED
    }

    fun sameWindow(pkg: String?, windowId: Int?): Boolean =
        target?.let { it.pkg == pkg && it.windowId == windowId } == true

    var target: Target? = null
        private set
    private var revision = 0L

    fun observe(next: Target?): Boolean {
        if (target == next) return false
        target = next
        invalidate()
        return true
    }

    fun invalidate() { revision++ }

    fun token(): Token? = target?.let { Token(it, revision) }

    fun begin(): Token? {
        invalidate()
        return token()
    }

    fun accepts(token: Token): Boolean =
        token.target == target && token.revision == revision
}
