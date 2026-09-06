package ced.cedclient.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * One IGN's cosmetic override. Both parts are independently optional so a
 * push can set just a tag, just a size, or both:
 *   - tag == null          -> leave this IGN's name/nametag alone
 *   - scaleX/Y/Z all == 1f -> leave this IGN's model size alone
 */
data class CosmeticOverride(
    val tag: String? = null,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val scaleZ: Float = 1f
)

/**
 * Pulls a { ign -> CosmeticOverride } map from a cosmetics.json file living
 * in a private GitHub repo, and keeps it applied live.
 *
 * Replaces the old ntfy.sh long-lived stream. That design's weak point was
 * write access: the ntfy topic was just a shared string baked into the jar,
 * so anyone who extracted it could push arbitrary overrides to every client
 * running the mod, forever, with no way to revoke just their access.
 *
 * This version instead polls the GitHub Contents API for the file every
 * POLL_INTERVAL. Read access uses a fine-grained personal access token
 * scoped to "Contents: Read-only" on ONLY this one repo -- see SETUP.md.
 * That token is still extractable from the compiled jar (same trust model
 * as the old topic string), but scoped this way it can only ever be used
 * to *read* this one file early. It grants no write access anywhere and no
 * access to any other repo, which is the actual security upgrade: WRITING
 * now requires real GitHub auth (an account with push access to the repo),
 * not knowledge of a shared string.
 *
 * State survives restarts and being offline: every applied update is
 * written to cosmetics_cache.json in the config folder and reloaded on
 * startup, so overrides keep applying with no connection at all.
 */
object CosmeticsSync {

    // --- Fill these in for your repo, then rebuild the mod. ---
    // A fine-grained PAT with ONLY "Contents: Read-only" access to REPO
    // below, and no other repositories selected. See SETUP.md.
    private const val OWNER = "CedNath"
    private const val REPO = "cedclient-cosmetics"
    private const val BRANCH = "main"
    private const val FILE_PATH = "cosmetics.json"
    private const val TOKEN = "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"


    private const val POLL_INTERVAL_SECONDS = 45L

    private val gson = Gson()

    private val overridesMap = ConcurrentHashMap<String, CosmeticOverride>()

    private val cacheFile: File
        get() = File(File(Minecraft.getInstance().gameDirectory, "cedclient"), "cosmetics_cache.json")

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Volatile
    private var started = false

    // Avoids re-downloading/re-applying when nothing changed.
    @Volatile
    private var lastEtag: String? = null

    /** Call once during mod init. Loads the local cache immediately, then
     *  starts a background thread that polls on an interval. */
    fun init() {
        if (started) return
        started = true

        loadCache()

        val thread = Thread(::pollLoop, "cedclient-cosmetics-sync")
        thread.isDaemon = true
        thread.start()
    }

    fun getOverride(ign: String): CosmeticOverride? = overridesMap[ign.lowercase()]

    /** All IGNs that currently have a tag pushed for them (scale-only entries excluded). */
    fun allTags(): Map<String, String> =
        overridesMap.mapNotNull { (ign, override) -> override.tag?.let { ign to it } }.toMap()

    private fun pollLoop() {
        while (true) {
            try {
                pollOnce()
            } catch (t: Throwable) {
                println("CedClient cosmetics sync: poll failed (${t.message}), will retry")
            }

            try {
                Thread.sleep(POLL_INTERVAL_SECONDS * 1000)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun pollOnce() {
        val url = "https://api.github.com/repos/$OWNER/$REPO/contents/$FILE_PATH?ref=$BRANCH"
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $TOKEN")
            .header("Accept", "application/vnd.github.raw") // ask for the raw file body, not base64 JSON
            .header("X-GitHub-Api-Version", "2022-11-28")
            .timeout(Duration.ofSeconds(15))
            .GET()

        lastEtag?.let { builder.header("If-None-Match", it) }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

        when (response.statusCode()) {
            200 -> {
                response.headers().firstValue("ETag").ifPresent { lastEtag = it }
                applyPayload(response.body())
            }
            304 -> {
                // Not modified since last poll -- nothing to do.
            }
            401, 403 -> {
                println("CedClient cosmetics sync: auth rejected (HTTP ${response.statusCode()}) -- check TOKEN/repo access in CosmeticsSync.kt")
            }
            404 -> {
                println("CedClient cosmetics sync: repo/file/branch not found -- check OWNER/REPO/BRANCH/FILE_PATH in CosmeticsSync.kt")
            }
            else -> {
                println("CedClient cosmetics sync: unexpected HTTP ${response.statusCode()}")
            }
        }
    }

    private fun applyPayload(payload: String) {
        val parsed = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (t: Throwable) {
            println("CedClient cosmetics sync: received something that wasn't valid JSON, ignoring it")
            return
        }

        val newMap = ConcurrentHashMap<String, CosmeticOverride>()
        for ((ign, value) in parsed.entrySet()) {
            val obj = value.asJsonObject
            newMap[ign.lowercase()] = CosmeticOverride(
                tag = obj.get("tag")?.takeIf { !it.isJsonNull }?.asString,
                scaleX = obj.get("scaleX")?.asFloat ?: 1f,
                scaleY = obj.get("scaleY")?.asFloat ?: 1f,
                scaleZ = obj.get("scaleZ")?.asFloat ?: 1f
            )
        }

        overridesMap.clear()
        overridesMap.putAll(newMap)
        saveCache()
        println("CedClient cosmetics sync: applied update for ${newMap.size} name(s)")
    }

    private fun loadCache() {
        val file = cacheFile
        if (!file.exists()) return
        try {
            applyPayload(file.readText())
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun saveCache() {
        try {
            val dir = cacheFile.parentFile
            if (!dir.exists()) dir.mkdirs()
            val obj = JsonObject()
            for ((ign, override) in overridesMap) {
                val entry = JsonObject()
                if (override.tag != null) entry.addProperty("tag", override.tag)
                entry.addProperty("scaleX", override.scaleX)
                entry.addProperty("scaleY", override.scaleY)
                entry.addProperty("scaleZ", override.scaleZ)
                obj.add(ign, entry)
            }
            cacheFile.writeText(gson.toJson(obj))
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}