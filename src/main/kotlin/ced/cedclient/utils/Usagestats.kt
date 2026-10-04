package ced.cedclient.utils

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.Properties

/**
 * Sends ONE small ping per game launch to the CedClient stats Worker so the dev
 * can see who is using the mod.
 *
 * What is sent: Minecraft username, UUID, CedClient version, Minecraft version.
 * What is NEVER sent: session/access token, IP is only what any web request has,
 * nothing from chat, inventory, servers, or any other game data.
 *
 * Opt-out: set shareUsage=false in config/cedclient-usage.properties
 * (the file is created automatically on first launch).
 *
 * Runs on a background daemon thread with short timeouts and swallows every
 * error, so a dead server can never lag or crash the game.
 */
object UsageStats {

    private const val ENDPOINT = "https://cedclient.kasajonny420-dab.workers.dev/ping"
    private const val CONFIG_NAME = "cedclient-usage.properties"
    private const val KEY = "shareUsage"

    private var pinged = false

    fun ping() {
        if (pinged) return
        pinged = true

        try {
            if (!isEnabled()) return

            // Read everything on the calling (main) thread, send on a background thread.
            val user = Minecraft.getInstance().user
            val name = user.name
            val uuid = user.profileId.toString()

            val loader = FabricLoader.getInstance()
            val modVersion = loader.getModContainer("cedclient")
                .map { it.metadata.version.friendlyString }
                .orElse("unknown")
            val mcVersion = loader.getModContainer("minecraft")
                .map { it.metadata.version.friendlyString }
                .orElse("unknown")

            val json = "{" +
                    "\"uuid\":\"${esc(uuid)}\"," +
                    "\"name\":\"${esc(name)}\"," +
                    "\"version\":\"${esc(modVersion)}\"," +
                    "\"mc\":\"${esc(mcVersion)}\"" +
                    "}"

            val thread = Thread({
                try {
                    val client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build()
                    val request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "CedClient/$modVersion")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build()
                    client.send(request, HttpResponse.BodyHandlers.discarding())
                } catch (_: Throwable) {
                    // Never let stats affect the game.
                }
            }, "CedClient-UsageStats")
            thread.isDaemon = true
            thread.start()
        } catch (_: Throwable) {
            // Never let stats affect the game.
        }
    }

    private fun isEnabled(): Boolean {
        return try {
            val path = FabricLoader.getInstance().configDir.resolve(CONFIG_NAME)
            if (!Files.exists(path)) {
                Files.writeString(
                    path,
                    "# CedClient usage stats\n" +
                            "# When true, CedClient sends your Minecraft username, UUID, mod version and\n" +
                            "# Minecraft version to the developer once per launch.\n" +
                            "# Set to false to turn it off.\n" +
                            "$KEY=true\n"
                )
                true
            } else {
                val props = Properties()
                Files.newBufferedReader(path).use { props.load(it) }
                props.getProperty(KEY, "true").trim().equals("true", ignoreCase = true)
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c < ' ' -> {} // drop control characters
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}