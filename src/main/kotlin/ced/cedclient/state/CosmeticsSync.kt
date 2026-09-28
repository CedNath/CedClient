package ced.cedclient.state

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
 * in a public GitHub repo, and keeps it applied live.
 *
 * Replaces the old ntfy.sh long-lived stream. That design's weak point was
 * write access: the ntfy topic was just a shared string baked into the jar,
 * so anyone who extracted it could push arbitrary overrides to every client
 * running the mod, forever, with no way to revoke just their access.
 *
 * This version polls raw.githubusercontent.com for the file every
 * POLL_INTERVAL. The cosmetics-data repo (REPO below) is PUBLIC and this
 * is an unauthenticated read of a public raw file -- no token involved.
 *
 * That's a deliberate choice, not an oversight: the file is just cosmetic
 * overrides (nametags/scale), nothing sensitive, so there's no reason to
 * gate reads behind auth. Going back to a private repo + token would
 * reintroduce the problem this replaced -- GitHub auto-revokes any PAT it
 * finds in a public push (even one manually allowed through push
 * protection), so a hardcoded token here would just die again the next
 * time this file is committed. WRITE access to the repo is still fully
 * gated behind normal GitHub push permissions -- this only affects reads.
 *
 * State survives restarts and being offline: every applied update is
 * written to cosmetics_cache.json in the config folder and reloaded on
 * startup, so overrides keep applying with no connection at all.
 */
object CosmeticsSync {

    // --- Fill these in for your repo, then rebuild the mod. ---
    // REPO must be a PUBLIC repository -- reads here are unauthenticated.
    private const val OWNER = "CedNath"
    private const val REPO = "cedclient-cosmetics"
    private const val BRANCH = "main"
    private const val FILE_PATH = "cosmetics.json"

    // GitHub's contents API: always the current file (its own cache is 60s).
    // raw.githubusercontent.com is only used as a fallback -- it sits behind a
    // ~5 min CDN cache that IGNORES query strings, so it can serve (and flip
    // between) stale copies of the file.
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/contents/$FILE_PATH?ref=$BRANCH"
    private const val RAW_URL = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE_PATH"

    private const val POLL_INTERVAL_SECONDS = 60L

    private val gson = Gson()

    // Replaced wholesale (never clear()+putAll()) so the render thread can
    // never observe a half-empty map in the middle of an update.
    @Volatile
    private var overridesMap: Map<String, CosmeticOverride> = emptyMap()

    private val configDir: File
        get() = File(Minecraft.getInstance().gameDirectory, "cedclient")

    private val cacheFile: File
        get() = File(configDir, "cosmetics_cache.json")

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Volatile
    private var started = false

    // Body of the last payload applied from the network, so an unchanged
    // file isn't re-applied on every poll.
    @Volatile
    private var lastBody: String? = null

    // ETag of the last API response. Conditional requests answered with 304
    // don't count against GitHub's unauthenticated rate limit (60/h).
    @Volatile
    private var lastEtag: String? = null

    // Serialises polls so the background loop and a forced sync can't race.
    private val pollLock = Any()

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

    /**
     * Re-downloads cosmetics.json right now on a short-lived background
     * thread (never blocks the render/client thread). Hooked to the
     * Cosmetics module's onEnable, so toggling it off/on in the GUI forces
     * a sync. No-op before init() has run.
     */
    fun forceSync() {
        if (!started) return
        val thread = Thread({
            try {
                pollOnce(force = true)
            } catch (e: Throwable) {
                println("CedClient cosmetics sync: forced sync failed (${e.message})")
            }
        }, "cedclient-cosmetics-force-sync")
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
                pollOnce(force = false)
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

    private fun pollOnce(force: Boolean) {
        synchronized(pollLock) { pollOnceLocked(force) }
    }

    private fun pollOnceLocked(force: Boolean) {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/vnd.github.raw+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "CedClient")
            .GET()

        // A forced sync skips the conditional header so it always downloads.
        if (!force) lastEtag?.let { builder.header("If-None-Match", it) }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

        when (response.statusCode()) {
            200 -> {
                val body = response.body()
                val etag = response.headers().firstValue("ETag").orElse(null)
                if (force || body != lastBody) {
                    if (applyPayload(body, "github api")) {
                        lastBody = body
                        lastEtag = etag
                    }
                } else {
                    lastEtag = etag
                }
            }
            304 -> {
                // Unchanged since the last poll -- nothing to do.
            }
            else -> {
                println("CedClient cosmetics sync: GitHub API returned HTTP ${response.statusCode()}, falling back to raw.githubusercontent.com (may be up to ~5 min stale)")
                pollRawFallback(force)
            }
        }
    }

    private fun pollRawFallback(force: Boolean) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(RAW_URL))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        when (response.statusCode()) {
            200 -> {
                val body = response.body()
                if (force || body != lastBody) {
                    if (applyPayload(body, "raw fallback")) {
                        lastBody = body
                        lastEtag = null // next API poll re-downloads and takes over again
                    }
                }
            }
            404 -> println("CedClient cosmetics sync: file not found -- check OWNER/REPO/BRANCH/FILE_PATH in CosmeticsSync.kt, and that the repo is public")
            else -> println("CedClient cosmetics sync: raw fallback returned HTTP ${response.statusCode()}")
        }
    }

    /** Returns true if the payload was valid and applied. */
    private fun applyPayload(payload: String, source: String): Boolean {
        val parsed = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (t: Throwable) {
            println("CedClient cosmetics sync: received something that wasn't valid JSON, ignoring it")
            return false
        }

        val newMap = HashMap<String, CosmeticOverride>()
        for ((ign, value) in parsed.entrySet()) {
            try {
                val obj = value.asJsonObject
                newMap[ign.lowercase()] = CosmeticOverride(
                    tag = obj.get("tag")?.takeIf { !it.isJsonNull }?.asString,
                    scaleX = obj.get("scaleX")?.asFloat ?: 1f,
                    scaleY = obj.get("scaleY")?.asFloat ?: 1f,
                    scaleZ = obj.get("scaleZ")?.asFloat ?: 1f
                )
            } catch (t: Throwable) {
                // One malformed entry shouldn't throw away everyone else's.
                println("CedClient cosmetics sync: skipping malformed entry for \"$ign\" (${t.message})")
            }
        }

        overridesMap = newMap // atomic swap
        saveCache()
        println("CedClient cosmetics sync: applied update for ${newMap.size} name(s) (${source})")
        return true
    }

    private fun loadCache() {
        val file = cacheFile
        if (!file.exists()) return
        try {
            applyPayload(file.readText(), "local cache")
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