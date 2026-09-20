package ced.cedclient.utils

/** Real TPS from timing the server's per-tick ping packet, same idea as Hypixel client mods that
 *  can't read TPS directly: the server ticks once per packet, so the real-world ms between
 *  consecutive receipts (averaged) gives ms-per-tick, and 1000/that is TPS (capped at 20). Fed by
 *  recordTick() -- call that from wherever the ping packet is intercepted (see PingTracker). */
object TpsTracker {

    private val TICK_TIMES = LongArray(20)
    private var tickIdx = 0
    private var lastTickMs = -1L

    @JvmStatic
    fun recordTick() {
        val now = System.currentTimeMillis()
        if (lastTickMs > 0) {
            TICK_TIMES[tickIdx % TICK_TIMES.size] = now - lastTickMs
            tickIdx++
        }
        lastTickMs = now
    }

    @JvmStatic
    fun reset() {
        tickIdx = 0
        lastTickMs = -1
    }

    /** 0..20, or -1 if not enough samples yet. */
    @JvmStatic
    fun currentTps(): Double {
        val filled = minOf(tickIdx, TICK_TIMES.size)
        if (filled == 0) return -1.0
        var sum = 0L
        for (i in 0 until filled) sum += TICK_TIMES[i]
        val avgMs = sum.toDouble() / filled
        return minOf(20.0, 1000.0 / avgMs)
    }
}