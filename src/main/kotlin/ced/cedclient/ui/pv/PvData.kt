package ced.cedclient.ui.pv

import ced.cedclient.features.impl.misc.ProfileViewer
import com.google.gson.JsonObject

/**
 * Shared helper for pulling "the profile/member currently being viewed" out
 * of ProfileViewer's cached raw Hypixel payloads. Every tab beyond Home
 * needs this same profile -> member resolution (Collections, Inventory,
 * Mining, ... all key off member.<field>), so it lives here once instead of
 * getting copy-pasted into each tab.
 *
 * "Current" profile = whichever one Hypixel marks `selected: true` (the
 * player's active island in-game), falling back to the first profile in
 * the list if somehow none is marked selected.
 */
object PvData {

    fun currentProfile(): JsonObject? {
        val profiles = ProfileViewer.lastSkyblockJson?.getAsJsonArray("profiles") ?: return null
        for (element in profiles) {
            val profile = element.asJsonObject
            if (profile.get("selected")?.asBoolean == true) return profile
        }
        return profiles.firstOrNull()?.asJsonObject
    }

    fun currentMember(): JsonObject? {
        val uuid = ProfileViewer.lastUuid ?: return null
        return currentProfile()?.getAsJsonObject("members")?.getAsJsonObject(uuid)
    }

    /**
     * Hypixel's /player payload nests the actual player object one level
     * down under "player" (`{success, player: {...}}`) -- unlike
     * /skyblock/profiles, which puts "profiles" directly at the top level.
     * This un-nests it so callers (rank badge, last-seen) don't have to
     * remember that difference.
     */
    fun currentPlayer(): JsonObject? = ProfileViewer.lastPlayerJson?.getAsJsonObject("player")

    /**
     * Purse moved from members[uuid].coin_purse (pre-2024 API) to
     * members[uuid].currencies.coin_purse in Hypixel's newer economy
     * overhaul. Try the new path first, fall back to the old one, so this
     * keeps working across both API eras without needing a version check.
     */
    fun purse(member: JsonObject): Double? {
        member.getAsJsonObject("currencies")?.get("coin_purse")?.let { return it.asDouble }
        member.get("coin_purse")?.let { return it.asDouble }
        return null
    }

    /** Bank balance is per-profile (shared across every member/island co-op on that profile), not per-member. */
    fun bankBalance(profile: JsonObject): Double? =
        profile.getAsJsonObject("banking")?.get("balance")?.asDouble
}