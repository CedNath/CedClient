package ced.cedclient.utils

/** Real TPS from timing the server's per-tick ping packet, same idea as Hypixel client mods that
 *  can't read TPS directly: the server ticks once per packet, so the real-world ms between
 *  consecutive receipts (averaged) gives ms-per-tick, and 1000/that is TPS (capped at 20). Fed by
 *  recordTick() -- call that from wherever the ping packet is intercepted (see PingTracker). */
object TpsTracker {

    private val TICK_TIMES = LongArray(20)
    private var tickIdx = 0
    private var lastTickMs = -1L

    /** Server ticks seen since launch (one per server ping packet); never reset, so differences time things in ticks. */
    @Volatile
    @JvmStatic
    var serverTicks = 0L
        private set

    /** [now] = when the packet came off the wire. */
    @JvmStatic
    fun recordTick(now: Long) {
        serverTicks++
        if (lastTickMs > 0) {
            TICK_TIMES[tickIdx % TICK_TIMES.size] = now - lastTickMs
            tickIdx++
        }
        lastTickMs = now
    }

    /** Ms since the last server tick packet came in (0 before the first one): a big number = the server (or link) is stalling. */
    @JvmStatic
    fun msSinceTick(): Long = if (lastTickMs < 0) 0L else System.currentTimeMillis() - lastTickMs

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