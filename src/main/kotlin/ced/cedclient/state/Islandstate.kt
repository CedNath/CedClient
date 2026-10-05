package ced.cedclient.state

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.IslandChangeEvent
import ced.cedclient.events.core.on
import ced.cedclient.utils.Debug
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel

/**
 * SkyBlock island/area detection.
 *
 * REVISION NOTE: the first version of this file polled the tab list for an
 * "Area: <name>"/"Dungeon: <name>" fake-player entry (the approach current
 * NoammAddons source uses) gated behind an inSkyBlock check that looked for
 * a scoreboard objective internally named "SBScoreboard" (the approach
 * NoammAddons also uses for that part). A full logged play session --
 * hub, warping between mini-lobbies, and entering an F7 Catacombs run --
 * produced zero "[IslandState]" log lines despite Debug being on the whole
 * time and DungeonState logging normally throughout, which only makes
 * sense if that SBScoreboard objective never existed under that exact name
 * on this Hypixel build (0.27.1 as of the session), which then also
 * prevented the tab-list read from ever running since it was gated behind
 * inSkyBlock.
 *
 * This version instead uses the old, boring, extremely-well-established
 * method every earlier SkyBlock mod (NEU, SkyblockAddons, and SkyHanni's
 * own explicitly-kept-as-fallback path) has relied on: sending the
 * server-side `/locraw` command and parsing its JSON chat response, e.g.
 * `{"server":"mini147DN","gametype":"SKYBLOCK","mode":"dungeon","map":"..."}`.
 * `mode` maps 1:1 to Island.apiName below -- that mapping is copied
 * verbatim from SkyHanni's IslandType.kt (apiNameFallback per entry), not
 * guessed, since it's the same string Hypixel's own location API/locraw
 * output uses.
 *
 * One known side effect: the raw `{...}` JSON response prints as an
 * ordinary chat line since ChatMessageEvent is a read-only, non-cancellable
 * tap (see its kdoc) -- there's currently nothing in this file suppressing
 * it from the chat HUD. Cosmetic only; flagging in case that's worth a
 * follow-up (e.g. a ChatFilter rule elsewhere in the codebase for lines
 * matching `^\{"server":`).
 */
object IslandState {
    private val mc get() = Minecraft.getInstance()
    private val gson = Gson()

    enum class Island(val displayName: String, val apiName: String?) {
        PRIVATE_ISLAND("Private Island", "dynamic"),
        HUB("Hub", "hub"),
        DARK_AUCTION("Dark Auction", "dark_auction"),
        WINTER_ISLAND("Jerry's Workshop", "winter"),

        THE_FARMING_ISLANDS("The Farming Islands", "farming_1"),
        GARDEN("Garden", "garden"),

        GOLD_MINE("Gold Mine", "mining_1"),
        DEEP_CAVERNS("Deep Caverns", "mining_2"),
        DWARVEN_MINES("Dwarven Mines", "mining_3"),
        CRYSTAL_HOLLOWS("Crystal Hollows", "crystal_hollows"),
        MINESHAFT("Mineshaft", "mineshaft"),

        BACKWATER_BAYOU("Backwater Bayou", "fishing_1"),
        LOTUS_ATOLL("Lotus Atoll", "lotus_atoll"),

        THE_PARK("The Park", "foraging_1"),
        GALATEA("Moonglade Marsh", "foraging_2"),
        TORRHUS_CANYON("Torrhus Canyon", "foraging_3"),

        SPIDER_DEN("Spider's Den", "combat_1"),
        THE_END("The End", "combat_3"),
        CRIMSON_ISLE("Crimson Isle", "crimson_isle"),

        DUNGEON_HUB("Dungeon Hub", "dungeon_hub"),
        CATACOMBS("Catacombs", "dungeon"),
        KUUDRA_ARENA("Kuudra", "kuudra"),

        THE_RIFT("The Rift", "rift"),
        SAFARI("Critter Safari", "safari");

        companion object {
            fun fromApiName(name: String?): Island? = name?.let { n -> entries.find { it.apiName == n } }
        }
    }

    var inSkyBlock: Boolean = false
        private set

    var island: Island? = null
        private set

    private var lastLevel: ClientLevel? = null

    // Whether the current connection is Hypixel at all -- recomputed once
    // per level change (see tick()), not every tick. Deliberately the
    // "boring", well-established check rather than hooking the
    // "minecraft:brand" plugin-channel packet (what NoammAddons/SkyHanni
    // use): that requires a new mixin on whichever packet-listener class
    // handles ClientboundCustomPayloadPacket in this MC version (moved
    // around between ClientPacketListener/ClientCommonPacketListenerImpl
    // across 1.20.2+), which is exactly the kind of version-fragile target
    // this file's own history (see REVISION NOTE above) has already been
    // burned by once. mc.isLocalServer() + the server address covers both
    // cases that actually matter here (singleplayer/LAN worlds, and
    // non-Hypixel multiplayer servers) with stable, long-standing API.
    //
    // Known limitation: if the address doesn't literally contain
    // "hypixel.net" (e.g. someone connects via a raw IP instead of the
    // hostname), this will wrongly report false and /locraw won't be
    // sent. Fine for now -- flagging in case that's ever reported.
    private var isOnHypixel: Boolean = false

    private fun detectHypixel(): Boolean {
        if (mc.isLocalServer) return false // singleplayer / Open-to-LAN
        val ip = mc.currentServer?.ip ?: return false
        return ip.contains("hypixel.net", ignoreCase = true)
    }

    // Countdown-timer retry, reset any time the level changes. Kept as a
    // simple "ticks remaining" countdown rather than the earlier
    // ticks-elapsed-since-reset version -- that version tangled the
    // level-change reset and the per-tick increment together in a way
    // that's easy to get wrong; this one only ever does one thing per tick
    // (count down, or fire when it hits zero), which is easier to verify by
    // reading straight through tick() below.
    private var ticksUntilNextRequest = 0

    // ~1s grace period after a level change before the first request --
    // right at the swap the player entity may not exist yet. No cap on
    // total retries: every RETRY_INTERVAL_TICKS while island is still
    // unknown is cheap and self-heals from a dropped/rate-limited response
    // without needing separate cap-tracking state.
    private const val INITIAL_DELAY_TICKS = 20
    private const val RETRY_INTERVAL_TICKS = 140 // ~7s between attempts

    // Suppression window for the mixin: only the response that arrives
    // shortly after WE sent /locraw should be hidden from chat/logs. If the
    // user types /locraw themselves, this stays at 0 and the mixin lets
    // their response through normally. Tick-based rather than wall-clock --
    // ties it to the same clock as the retry countdown above, so it doesn't
    // drift if the client stutters/lags between the request and the reply.
    // 100 ticks (~5s at 20 tps) is generous round-trip slack -- covers a
    // laggy/slow server response with real margin, but still short enough
    // that it's long expired well before the ~7s (140-tick) retry could
    // send another. Widened from an earlier 40-tick (~2s) window after
    // the response was still occasionally slipping past it and showing up
    // in chat -- RETRY_INTERVAL_TICKS was bumped alongside it to preserve
    // the same safety gap between the window closing and the next retry.
    private const val EXPECTED_RESPONSE_WINDOW_TICKS = 100
    private var expectedResponseTicksRemaining: Int = 0

    private data class LocrawResponse(
        val server: String? = null,
        val gametype: String? = null,
        val mode: String? = null,
        val map: String? = null
    )

    fun init() {
        on<ChatMessageEvent> { event ->
            handleChat(event.unformattedText.trim())
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            tick()
        }
    }

    private fun tick() {
        // Decremented unconditionally, every tick, regardless of the early
        // returns below -- it has to keep counting down even on ticks where
        // we skip straight past the retry logic (e.g. level == null,
        // island already known), or the window would outlive its intended
        // ~2s and could wrongly swallow a later manually-typed /locraw.
        if (expectedResponseTicksRemaining > 0) {
            expectedResponseTicksRemaining--
        }

        val currentLevel = mc.level

        if (currentLevel !== lastLevel) {
            lastLevel = currentLevel
            isOnHypixel = detectHypixel()
            if (inSkyBlock || island != null) {
                inSkyBlock = false
                setIsland(null)
            }
            ticksUntilNextRequest = INITIAL_DELAY_TICKS
            if (Debug.enabled) {
                Debug.log(
                    if (isOnHypixel) "[IslandState] level changed -- will query /locraw in ~1s"
                    else "[IslandState] level changed -- not on Hypixel, skipping /locraw",
                    interval = 1
                )
            }
        }

        if (currentLevel == null || mc.player == null) return
        if (!isOnHypixel) return
        if (island != null) return

        if (ticksUntilNextRequest > 0) {
            ticksUntilNextRequest--
            return
        }

        requestLocraw()
        ticksUntilNextRequest = RETRY_INTERVAL_TICKS
    }

    private fun requestLocraw() {
        // NOTE: if this never actually reaches the server (no "[IslandState]
        // sent /locraw" log even with Debug on, and no raw {"server":...}
        // line ever shows in chat), the likely culprit is
        // ClientPacketListener#sendCommand itself -- worth trying
        // `mc.player?.chatSigner?...` or a Brigadier-dispatch alternative,
        // or simply typing /locraw by hand while Debug is on to confirm the
        // chat-parsing half of this file works independently of the
        // sending half.
        mc.player?.connection?.sendCommand("locraw")
        expectedResponseTicksRemaining = EXPECTED_RESPONSE_WINDOW_TICKS
        if (Debug.enabled) Debug.log("[IslandState] sent /locraw", interval = 1)
    }

    /**
     * Called from ClientPacketListenerMixin for every chat line that looks
     * like a /locraw response, BEFORE it decides whether to cancel the
     * packet. Returns true (and consumes the window) only for the response
     * to our own request above -- a manually-typed /locraw falls outside
     * the window and this returns false, so the mixin lets it through.
     *
     * Consuming (zeroing) on the first hit rather than just checking the
     * remaining ticks matters here: it stops a single response from being
     * eligible twice, and stops a stale window from lingering past its real
     * reply in the (unlikely) case the response never actually arrives.
     */
    fun consumeExpectedLocrawResponse(): Boolean {
        if (expectedResponseTicksRemaining <= 0) return false
        expectedResponseTicksRemaining = 0
        return true
    }

    private fun handleChat(text: String) {
        if (!text.startsWith("{") || !text.endsWith("}")) return

        val response = try {
            gson.fromJson(text, LocrawResponse::class.java)
        } catch (e: JsonSyntaxException) {
            null
        } ?: return

        // Cheap sanity check so we don't mistake an unrelated JSON-looking
        // chat line (some other mod/feature) for our own locraw response --
        // every real locraw reply has at least a "server" field.
        if (response.server == null) return

        val nowInSkyBlock = response.gametype == "SKYBLOCK"
        if (nowInSkyBlock != inSkyBlock) {
            inSkyBlock = nowInSkyBlock
            if (Debug.enabled) Debug.log("[IslandState] inSkyBlock -> $inSkyBlock (from /locraw)", interval = 1)
        }

        val detected = if (inSkyBlock) Island.fromApiName(response.mode) else null
        if (inSkyBlock && detected == null && response.mode != null) {
            if (Debug.enabled) Debug.log("[IslandState] unrecognized locraw mode: '${response.mode}' -- please report this", interval = 1)
        }
        setIsland(detected)
    }

    private fun setIsland(newIsland: Island?) {
        if (newIsland == island) return
        val previous = island
        island = newIsland
        if (Debug.enabled) Debug.log("[IslandState] island -> $island", interval = 1)
        IslandChangeEvent(newIsland, previous).post()
    }
}