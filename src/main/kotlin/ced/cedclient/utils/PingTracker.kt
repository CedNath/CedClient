package ced.cedclient.utils

import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket

/**
 * Measures real round-trip time by sending vanilla's own ping request packet and timing the
 * pong that comes back (see PingPongMixin). Needed on Hypixel because the tab-list latency
 * there is stuck at ~1ms, so PlayerInfo#getLatency() can't be used.
 *
 * tick() must be called once per client tick while the ping display is wanted (CompactTab
 * does this while enabled); PingPongMixin calls onPong() when the response arrives.
 */
object PingTracker {

    private const val PROBE_INTERVAL_TICKS = 40 // one probe every ~2s
    private const val PROBE_TIMEOUT_MS = 5_000L

    @Volatile
    private var pendingSentAt = 0L
    private var ticksSinceProbe = 0

    @Volatile
    private var latestMs: Int = -1
    @Volatile
    private var updatedAt: Long = 0

    @JvmStatic
    fun pushRtt(rttMs: Long) {
        if (rttMs < 0 || rttMs > 5_000) return // implausible — ignore
        val rtt = rttMs.toInt()
        val prev = latestMs
        latestMs = if (prev > 0) (rtt + prev) / 2 else rtt // light EMA so it doesn't jitter
        updatedAt = System.currentTimeMillis()
    }

    @JvmStatic
    fun latest(): Int {
        if (latestMs < 0) return -1
        if (System.currentTimeMillis() - updatedAt > 60_000) return -1
        return latestMs
    }

    /** Sends a ping request every [PROBE_INTERVAL_TICKS] ticks, one in flight at a time. */
    @JvmStatic
    fun tick(mc: Minecraft) {
        val conn = mc.connection
        if (conn == null || mc.player == null) return

        val now = System.currentTimeMillis()
        if (pendingSentAt > 0 && now - pendingSentAt > PROBE_TIMEOUT_MS) pendingSentAt = 0 // lost, try again
        if (pendingSentAt > 0) return
        if (++ticksSinceProbe < PROBE_INTERVAL_TICKS) return

        ticksSinceProbe = 0
        pendingSentAt = now
        conn.send(ServerboundPingRequestPacket(now))
    }

    /** Called from PingPongMixin when a ClientboundPongResponsePacket arrives. */
    @JvmStatic
    fun onPong() {
        val sent = pendingSentAt
        if (sent <= 0) return // not ours (e.g. vanilla's F3 ping chart)
        pendingSentAt = 0
        pushRtt(System.currentTimeMillis() - sent)
    }

    @JvmStatic
    fun reset() {
        latestMs = -1
        updatedAt = 0
        pendingSentAt = 0
        ticksSinceProbe = 0
    }
}