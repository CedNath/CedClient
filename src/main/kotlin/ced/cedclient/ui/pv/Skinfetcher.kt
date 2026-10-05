package ced.cedclient.ui.pv

import ced.cedclient.render.nvg.NVGRenderer
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches and caches a player's skin (as a NanoVG texture handle) for the PV
 * Home tab header face.
 *
 * Deliberately independent of any Minecraft skin-manager class -- this mod's
 * Minecraft build is a heavily modified fork and none of that API surface
 * has been decompiled/confirmed against it yet (per the mod's usual
 * "share decompiled sources rather than guess signatures" rule). So this
 * goes around it entirely: plain Mojang session-server HTTP -> raw PNG
 * bytes -> NanoVG image via NVGRenderer.createImageFromMemory, the exact
 * same STB decode path NVGRenderer already uses for classpath images. If a
 * real skin-manager hook turns out to be preferable later (e.g. to reuse
 * Minecraft's own skin cache instead of a second HTTP round trip), only
 * this file needs to change -- HomeTab just asks [faceFor] for a handle and
 * draws it via NVGRenderer.image(...).
 *
 * The returned handle draws the FULL skin texture (64x64, "face" region at
 * (8,8)-(16,16)) -- callers crop with NVGRenderer.image's subX/subY/subW/subH
 * rather than this fetching/storing a pre-cropped image.
 */
object SkinFetcher {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private sealed class Entry {
        object Loading : Entry()
        object Failed : Entry()
        data class Ready(val handle: Int, val texW: Int, val texH: Int) : Entry()
    }

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Returns the current texture handle + dimensions for [uuid]'s skin, or
     * null if it isn't ready yet (still fetching, or the fetch failed).
     * Safe to call every draw call -- only kicks off real work the first
     * time a given uuid is asked about.
     *
     * Call from the render thread only (same rule as every other
     * NVGRenderer-touching call) -- the actual texture upload happens
     * synchronously the first frame the bytes arrive, via
     * Minecraft.getInstance().execute inside the fetch chain below.
     */
    fun faceFor(uuid: String): NVGRenderer.RawImage? {
        val entry = cache[uuid]
        if (entry == null) {
            cache[uuid] = Entry.Loading
            fetch(uuid)
            return null
        }
        return (entry as? Entry.Ready)?.let { NVGRenderer.RawImage(it.handle, it.texW, it.texH) }
    }

    /** Drops a cached entry and frees its GPU texture, if any. Not required for normal use -- the cache is small (one entry per viewed uuid) -- but available for a "close PV screen" cleanup hook if one gets added later. */
    fun evict(uuid: String) {
        (cache.remove(uuid) as? Entry.Ready)?.let { NVGRenderer.deleteRawImage(it.handle) }
    }

    private fun fetch(uuid: String) {
        val dashless = uuid.replace("-", "")
        val profileRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/$dashless"))
            .GET()
            .build()

        client.sendAsync(profileRequest, HttpResponse.BodyHandlers.ofString())
            .thenApply<String?> { response ->
                if (response.statusCode() != 200) null else extractSkinUrl(response.body())
            }
            .exceptionally { null }
            .thenCompose { skinUrl: String? ->
                if (skinUrl == null) {
                    CompletableFuture.completedFuture<ByteArray?>(null)
                } else {
                    val skinRequest = HttpRequest.newBuilder().uri(URI.create(skinUrl)).GET().build()
                    client.sendAsync(skinRequest, HttpResponse.BodyHandlers.ofByteArray())
                        .thenApply<ByteArray?> { it.body() }
                        .exceptionally { null }
                }
            }
            .thenAccept { bytes: ByteArray? ->
                Minecraft.getInstance().execute {
                    cache[uuid] = if (bytes != null) {
                        val raw = NVGRenderer.createImageFromMemory(bytes)
                        if (raw != null) Entry.Ready(raw.handle, raw.width, raw.height) else Entry.Failed
                    } else {
                        Entry.Failed
                    }
                }
            }
    }

    /**
     * Mojang's session-server profile response nests the skin URL under
     * base64-encoded JSON: properties[] -> {name: "textures", value: <b64>}
     * -> decoded -> textures.SKIN.url.
     */
    private fun extractSkinUrl(profileJson: String): String? {
        return try {
            val json = JsonParser.parseString(profileJson).asJsonObject
            val properties = json.getAsJsonArray("properties") ?: return null
            val texturesValue = properties
                .map { it.asJsonObject }
                .firstOrNull { it.get("name")?.asString == "textures" }
                ?.get("value")?.asString ?: return null

            val decoded = String(Base64.getDecoder().decode(texturesValue))
            JsonParser.parseString(decoded).asJsonObject
                .getAsJsonObject("textures")
                ?.getAsJsonObject("SKIN")
                ?.get("url")?.asString
        } catch (t: Throwable) {
            null
        }
    }
}