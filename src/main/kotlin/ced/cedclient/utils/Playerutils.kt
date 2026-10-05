package ced.cedclient.utils

import net.minecraft.client.Minecraft

object PlayerUtils {
    /** The local player's username, or null before the client has a session. */
    fun getName(): String? = Minecraft.getInstance().user.name
}

private val colorCodeRegex = Regex("§[0-9a-fk-or]")

/**
 * "[MVP+] Throwpo" / "⚔ [MVP++] Throwpo ●" -> "Throwpo".
 * Strips color codes and the trailing party-list dot, then keeps the last
 * whitespace-separated token (ranks and symbols always come before the name).
 */
fun String.cleanPlayerName(): String =
    colorCodeRegex.replace(this, "").replace("●", "").trim().split(' ').last()