package com.jev.probe.capture

/** Select from live windows only; the previous adapter is never a routing hint. */
internal object ForegroundWindowSelector {
    data class Window(val id: Int, val pkg: String, val application: Boolean,
                      val active: Boolean, val focused: Boolean, val layer: Int)

    fun select(active: Window?, windows: List<Window>, ownPkg: String): Window? {
        fun eligible(w: Window) = w.application && w.pkg != ownPkg
        // Own application screens (settings) must not fall through to an old chat.
        if (active?.application == true && active.pkg == ownPkg) return null
        if (active != null && eligible(active)) return active
        // An overlay/keyboard can own the active root; use the visible application
        // beneath it, ordered by current focus/activity and stacking order.
        return windows.filter { eligible(it) }
            .maxWithOrNull(compareBy<Window> { it.active }.thenBy { it.focused }.thenBy { it.layer })
    }
}
