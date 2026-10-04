package ced.cedclient.utils

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.PlayerInfo
import java.util.regex.Pattern

/** Shared scan of the Hypixel tab list. Several features (CompactTab, CustomScoreboard's Pet
 *  section, DungeonScore, FishPuzzleDisplay, SoulflowHud, PetHud) each used to walk
 *  `connection.onlinePlayers` and strip color codes independently on their own tick cadence — same
 *  packet, parsed 5+ times. This scans + strips ONCE on a short tick interval and publishes the
 *  result; per-feature regex parsing (floor score, puzzle status, soulflow %, pet info, ...) still
 *  lives in each feature, only the raw scan + strip moved here.
 *
 *  [version] increments only when the scanned content actually changed, so a consumer that only
 *  needs to react to real changes (e.g. CompactTab's rebuilt/measured column model) can cheaply
 *  skip re-parsing via `if (lastSeenVersion == TabListCache.version) return cached`. */
object TabListCache {

    private val COLOR_STRIP: Pattern = Pattern.compile("§.")

    private const val SCAN_INTERVAL_TICKS = 5
    private var tickCounter = 0

    /** One tab-list row: the raw entry plus its once-computed color-stripped display string
     *  (not trimmed — most consumers trim/parse further themselves). */
    class Entry(@JvmField val info: PlayerInfo, @JvmField val stripped: String)

    /** Monotonically increasing; bumps only when a scan finds different content than last time. */
    @JvmStatic
    var version: Int = 0
        private set

    /** Latest scanned entries, in `onlinePlayers` order. Empty when disconnected. */
    @JvmStatic
    var entries: List<Entry> = emptyList()
        private set

    private var lastSig = 0

    @JvmStatic
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { mc -> tick(mc) })
    }

    private fun tick(mc: Minecraft) {
        if (mc.player == null || mc.connection == null) {
            if (entries.isNotEmpty()) {
                entries = emptyList()
                version++
            }
            return
        }
        if (tickCounter++ % SCAN_INTERVAL_TICKS != 0) return
        scan(mc)
    }

    /** Force an immediate rescan outside the normal 5-tick cadence — e.g. PetHud's short burst
     *  window right after an equip/summon, where the extra latency of waiting for the next
     *  scheduled scan would read a stale pet. Cheap: same scan the tick loop would've done anyway. */
    @JvmStatic
    fun forceScan() {
        val mc = Minecraft.getInstance()
        if (mc.player == null || mc.connection == null) return
        scan(mc)
    }

    private fun scan(mc: Minecraft) {
        val online = mc.connection!!.onlinePlayers
        var sig = online.size
        val built = ArrayList<Entry>(online.size)
        for (info in online) {
            val dn = info.tabListDisplayName
            val raw = dn?.string ?: ""
            sig = sig * 31 + raw.hashCode()
            val stripped = if (raw.isEmpty()) "" else COLOR_STRIP.matcher(raw).replaceAll("")
            built.add(Entry(info, stripped))
        }
        if (sig == lastSig) return
        lastSig = sig
        entries = built
        version++
    }
}