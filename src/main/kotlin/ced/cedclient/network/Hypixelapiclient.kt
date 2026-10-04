package ced.cedclient.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture

/**
 * Result of an API call. Kept as a plain sealed class (not a raw
 * JsonObject?/null pair) so callers can tell "player doesn't exist" apart
 * from "network/parse error" apart from "success" without re-deriving that
 * from a null check.
 */
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Failure(val message: String) : ApiResult<Nothing>()
}

/**
 * Thin async wrapper around the two APIs the Profile Viewer needs:
 *  - Mojang's username -> UUID lookup (no auth required)
 *  - Hypixel's /player and /skyblock/profiles endpoints (needs an API key,
 *    sent via the "API-Key" header -- Hypixel does NOT use ?key=... anymore)
 *
 * Phase 1 deliberately does NOT model the SkyBlock profile response as typed
 * data classes -- it's enormous and mostly unused until we build real tabs.
 * Instead this returns the raw JsonObject so ProfileViewer can stash it and
 * any future tab can pull the fields it needs out of it directly.
 *
 * All requests are async (CompletableFuture, off the client's HTTP executor
 * thread) -- callers MUST hop back to the main thread before touching any
 * Minecraft/game state or firing chat feedback from a callback.
 */
object HypixelApiClient {

    private const val MOJANG_BASE = "https://api.mojang.com/users/profiles/minecraft"
    private const val HYPIXEL_BASE = "https://api.hypixel.net/v2"

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build()

    /**
     * Resolves a Minecraft username to a (dashless) UUID via Mojang.
     * Returns Failure with a user-facing message if the name doesn't exist
     * or the lookup otherwise fails.
     */
    fun resolveUuid(username: String): CompletableFuture<ApiResult<String>> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$MOJANG_BASE/$username"))
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                when (response.statusCode()) {
                    200 -> {
                        try {
                            val json = JsonParser.parseString(response.body()).asJsonObject
                            val uuid = json.get("id")?.asString
                            if (uuid.isNullOrBlank()) {
                                ApiResult.Failure("Mojang returned no UUID for '$username'.")
                            } else {
                                ApiResult.Success(uuid)
                            }
                        } catch (t: Throwable) {
                            ApiResult.Failure("Couldn't parse Mojang's response for '$username'.")
                        }
                    }
                    404 -> ApiResult.Failure("No Minecraft account named '$username' exists.")
                    else -> ApiResult.Failure("Mojang API returned HTTP ${response.statusCode()} for '$username'.")
                }
            }
            .exceptionally { t -> ApiResult.Failure("Network error resolving '$username': ${t.message}") }
    }

    /**
     * Fetches the raw Hypixel /player payload (network level, achievements,
     * social media, etc. -- NOT SkyBlock-specific).
     */
    fun fetchPlayer(uuid: String, apiKey: String): CompletableFuture<ApiResult<JsonObject>> =
        get("$HYPIXEL_BASE/player?uuid=$uuid", apiKey, "player")

    /**
     * Fetches every SkyBlock profile (island) the player has, raw. This is
     * the "pull everything the API gives" payload -- members' full nested
     * data (skills, slayers, dungeons, inventories as base64 NBT, etc.) is
     * all in here under profiles[].members[uuid].
     */
    fun fetchSkyblockProfiles(uuid: String, apiKey: String): CompletableFuture<ApiResult<JsonObject>> =
        get("$HYPIXEL_BASE/skyblock/profiles?uuid=$uuid", apiKey, "skyblock/profiles")

    private fun get(url: String, apiKey: String, label: String): CompletableFuture<ApiResult<JsonObject>> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("API-Key", apiKey)
            .GET()
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                try {
                    val json = JsonParser.parseString(response.body()).asJsonObject
                    val success = json.get("success")?.asBoolean ?: false
                    if (response.statusCode() == 200 && success) {
                        ApiResult.Success(json)
                    } else {
                        val cause = json.get("cause")?.asString
                        ApiResult.Failure(
                            cause ?: "Hypixel $label request failed (HTTP ${response.statusCode()})."
                        )
                    }
                } catch (t: Throwable) {
                    ApiResult.Failure("Couldn't parse Hypixel's $label response (HTTP ${response.statusCode()}).")
                }
            }
            .exceptionally { t -> ApiResult.Failure("Network error during $label request: ${t.message}") }
    }
}