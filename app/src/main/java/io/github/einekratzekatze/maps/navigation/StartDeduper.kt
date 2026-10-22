package io.github.einekratzekatze.maps.navigation

/**
 * One Start press fires two navigations (https, then intent://; spike N0 #1) that carry the same
 * destination. Allows the first, suppresses the same target again within [windowMs].
 */
class StartDeduper(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val now: () -> Long,
) {
    private var lastTarget: NavTarget? = null
    private var lastAt = 0L

    @Synchronized
    fun shouldLaunch(target: NavTarget): Boolean {
        val time = now()
        if (target == lastTarget && time - lastAt in 0 until windowMs) return false
        lastTarget = target
        lastAt = time
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 2_000L
    }
}
