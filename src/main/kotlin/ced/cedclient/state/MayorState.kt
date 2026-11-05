package ced.cedclient.state

import ced.cedclient.utils.Debug
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Whether Paul's EZPZ perk (+10 dungeon score) is active, as mayor or as minister. Read from Hypixel's public election
 * endpoint (no API key), once per [REFRESH_MS] and only when a dungeon asks, off the game thread.
 *
 * shortcut: on a failed fetch it keeps the last answer (false at first); the "Force Paul Perk" setting covers that.
 */
object MayorState {
    private const val URL = "https://api.hypixel.net/v2/resources/skyblock/election"
    private const val REFRESH_MS = 30 * 60 * 1000L
    private const val PERK = "EZPZ"

    @Volatile var ezpz = false; private set
    @Volatile private var lastTry = 0L

    /** Call from a tick: starts a background refresh when the last one is old. */
    fun ensureFresh() {
        val now = System.currentTimeMillis()
        if (now - lastTry < REFRESH_MS) return
        lastTry = now
        Thread({ refresh() }, "CedClient-MayorState").apply { isDaemon = true }.start()
    }

    private fun refresh() {
        try {
            val request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(8)).GET().build()
            val body = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString()).body()
            val mayor = JsonParser.parseString(body).asJsonObject.getAsJsonObject("mayor")
            val perks = mayor.getAsJsonArray("perks")?.mapNotNull { it.asJsonObject.get("name")?.asString } ?: emptyList()
            val ministerPerk = mayor.getAsJsonObject("minister")?.getAsJsonObject("perk")?.get("name")?.asString
            ezpz = PERK in perks || ministerPerk == PERK
            Debug.log("[MayorState] mayor=${mayor.get("name")?.asString} perks=$perks minister perk=$ministerPerk -> ezpz=$ezpz")
        } catch (e: Exception) {
            Debug.log("[MayorState] fetch failed: ${e.message}")
        }
    }
}
