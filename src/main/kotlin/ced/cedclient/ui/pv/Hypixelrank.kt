package ced.cedclient.ui.pv

import com.google.gson.JsonObject

/**
 * Best-effort resolver for a player's Hypixel network rank tag + color, for
 * the PV header ("[VIP+] username" style, section-sign-colored). Two
 * strategies, tried in order:
 *
 *  1. The raw `prefix` field Hypixel's /player payload sometimes still
 *     returns -- already a fully section-sign-formatted string
 *     ("\u00A7b[VIP+]"), so if it's present this is exact and needs no
 *     further logic.
 *  2. Otherwise, derive it from rank/newPackageRank/monthlyPackageRank/
 *     rankPlusColor, the same way most third-party Hypixel rank displays
 *     do. This table is reconstructed from public documentation of
 *     Hypixel's rank system, not verified against a live payload from this
 *     specific API version -- if a rank renders wrong or missing, check
 *     the raw lastPlayerJson (ProfileViewer already prints it with
 *     Debug.enabled) for the actual field values/names and adjust the
 *     `when` below.
 */
object HypixelRank {

    private val plusColorCodes = mapOf(
        "BLACK" to "\u00A70", "DARK_BLUE" to "\u00A71", "DARK_GREEN" to "\u00A72", "DARK_AQUA" to "\u00A73",
        "DARK_RED" to "\u00A74", "DARK_PURPLE" to "\u00A75", "GOLD" to "\u00A76", "GRAY" to "\u00A77",
        "DARK_GRAY" to "\u00A78", "BLUE" to "\u00A79", "GREEN" to "\u00A7a", "AQUA" to "\u00A7b",
        "RED" to "\u00A7c", "LIGHT_PURPLE" to "\u00A7d", "YELLOW" to "\u00A7e", "WHITE" to "\u00A7f"
    )

    /** Returns a section-sign-formatted rank tag ("\u00A7b[VIP+]") or null for a default/no-rank player. */
    fun tagFor(player: JsonObject?): String? {
        if (player == null) return null

        player.get("prefix")?.asString?.let { return it }

        val rank = player.get("rank")?.asString?.takeIf { it != "NORMAL" && it != "NONE" }
        val monthly = player.get("monthlyPackageRank")?.asString?.takeIf { it != "NONE" }
        val packageRank = player.get("newPackageRank")?.asString?.takeIf { it != "NONE" }
            ?: player.get("packageRank")?.asString?.takeIf { it != "NONE" }
        val plusColor = plusColorCodes[player.get("rankPlusColor")?.asString] ?: "\u00A7c"

        return when {
            rank == "ADMIN" -> "\u00A7c[ADMIN]"
            rank == "GAME_MASTER" || rank == "MODERATOR" -> "\u00A72[MOD]"
            rank == "HELPER" -> "\u00A79[HELPER]"
            rank == "YOUTUBER" -> "\u00A7c[\u00A7fYOUTUBE\u00A7c]"
            monthly == "SUPERSTAR" -> "\u00A76[MVP$plusColor++\u00A76]"
            packageRank == "MVP_PLUS" -> "\u00A7b[MVP$plusColor+\u00A7b]"
            packageRank == "MVP" -> "\u00A7b[MVP]"
            packageRank == "VIP_PLUS" -> "\u00A7a[VIP\u00A76+\u00A7a]"
            packageRank == "VIP" -> "\u00A7a[VIP]"
            else -> null
        }
    }
}