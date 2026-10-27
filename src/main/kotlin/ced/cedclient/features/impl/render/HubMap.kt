package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.state.IslandState
import ced.cedclient.utils.Debug
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.FilterMode
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.state.MapRenderState
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.saveddata.maps.MapId
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * The SkyBlock hub's world map is a 13x7 wall of 128x128 map tiles. Each tile is redrawn from one slice of a single
 * image (a bundled one, or any image link), so the whole wall shows that picture. Ported from NoammAddons; unlike it, a
 * GIF only shows its first frame. See MapRendererHubMixin.
 */
object HubMap : Module(
    "Hub Map",
    Category.Visuals,
    "Replaces the SkyBlock Hub world map with a custom image."
) {
    private const val COLUMNS = 13
    private const val ROWS = 7
    private const val TILE_COUNT = COLUMNS * ROWS
    private const val TILE_SIZE = MapItem.IMAGE_WIDTH

    private val image = DropdownSetting("Image", listOf("Default", "URL"), "Default", "The bundled picture, or a link to your own.")
    private val fitMode = DropdownSetting("Image Fit Mode", listOf("Crop", "Fit"), "Crop", "Crop fills the wall; Fit shows the whole image with empty bars.")
    private val imageUrl = TextSetting("Image URL", "", "Direct link to an image. Best at ${COLUMNS * TILE_SIZE}x${ROWS * TILE_SIZE}.", 400).withDependency { image.value == "URL" }
    private val reloadButton = ActionSetting("Reload Image", "Load the image again (downloads the link again).") { reload(force = true) }

    private val mc get() = Minecraft.getInstance()
    private val locations = Array(TILE_COUNT) { Identifier.fromNamespaceAndPath("cedclient", "hub_map/$it") }
    private val cacheDir by lazy { File(CedPaths.dir, "hub-map").also { it.mkdirs() } }
    private val http by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build() }

    @Volatile private var started = false
    @Volatile private var loaded = false
    @Volatile private var generation = 0

    // Hub map ids never change; sorted in grid order (row by row).
    private val mapIds = intArrayOf(
        30662, 30655, 30648, 30641, 30634, 30627, 30620, 30613, 30606, 30599, 30592, 30585, 30578,
        30661, 30654, 30647, 30640, 30633, 30626, 30619, 30612, 30605, 30598, 30591, 30584, 30577,
        30660, 30653, 30646, 30639, 30632, 30625, 30618, 30611, 30604, 30597, 30590, 30583, 30576,
        30659, 30652, 30645, 30638, 30631, 30624, 30617, 30610, 30603, 30596, 30589, 30582, 30575,
        30658, 30651, 30644, 30637, 30630, 30623, 30616, 30609, 30602, 30595, 30588, 30581, 30574,
        30657, 30650, 30643, 30636, 30629, 30622, 30615, 30608, 30601, 30594, 30587, 30580, 30573,
        30656, 30649, 30642, 30635, 30628, 30621, 30614, 30607, 30600, 30593, 30586, 30579, 30572
    )

    init { addSettings(image, fitMode, imageUrl, reloadButton) }

    override fun onEnable() { if (started) reload(force = false) }

    /** Called by the map renderer mixin for every map; swaps a hub tile's texture for our slice and skips vanilla. */
    @JvmStatic
    fun applyRenderState(mapId: MapId, state: MapRenderState, ci: CallbackInfo) {
        if (!isEnabled) return
        if (!started) { started = true; reload(force = false); return }
        if (!loaded || IslandState.island != IslandState.Island.HUB) return
        val index = mapIds.indexOf(mapId.id).takeUnless { it == -1 } ?: return
        state.texture = locations[index]
        state.decorations.clear()
        ci.cancel()
    }

    private fun reload(force: Boolean) {
        val mine = ++generation
        Thread({
            try {
                val bytes = if (image.value == "URL") fromUrl(imageUrl.value, force) else HubMap::class.java.getResourceAsStream("/assets/cedclient/hub-map.png")?.readBytes()
                if (bytes != null && mine == generation) mc.execute { if (mine == generation) bind(bytes) }
            } catch (e: Exception) {
                msg("Failed to load the image: ${e.message}")
            }
        }, "CedClient-HubMap").apply { isDaemon = true }.start()
    }

    private fun fromUrl(url: String, force: Boolean): ByteArray? {
        if (url.isBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if ((uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrBlank()) return null
        val cache = File(cacheDir, url.hashCode().toString())
        if (!force && cache.isFile) return cache.readBytes()
        val response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) { msg("Download failed (HTTP ${response.statusCode()})"); return null }
        val type = response.headers().firstValue("Content-Type").orElse(null)
        if (type != null && !type.startsWith("image/") && type != "application/octet-stream") {
            msg("The link did not return an image (got '$type'). Use the direct file link.")
            return null
        }
        return response.body().also { cache.writeBytes(it) }
    }

    /** Render thread: cuts the picture into tiles and registers them as textures. */
    private fun bind(bytes: ByteArray) {
        val source = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return msg("Could not decode the image (${bytes.size} bytes)")
        val png = ByteArrayOutputStream().also { ImageIO.write(source, "png", it) }.toByteArray()
        val wall = NativeImage.read(png.inputStream()).use(::fit)
        locations.forEach(mc.textureManager::release)
        for (i in 0 until TILE_COUNT) {
            val tile = NativeImage(TILE_SIZE, TILE_SIZE, false)
            wall.copyRect(tile, i % COLUMNS * TILE_SIZE, i / COLUMNS * TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE, false, false)
            val texture = object : DynamicTexture({ "Hub map tile $i" }, tile) {
                init { sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR) }
            }
            mc.textureManager.register(locations[i], texture)
        }
        wall.close()
        loaded = true
        Debug.line("Hub Map", "image loaded (${source.width}x${source.height}, ${fitMode.value})")
    }

    /** The image on a COLUMNS x ROWS tile canvas: cropped from the middle, or shrunk to fit with empty bars. */
    private fun fit(source: NativeImage): NativeImage {
        val canvasWidth = COLUMNS * TILE_SIZE
        val canvasHeight = ROWS * TILE_SIZE
        val ratio = canvasWidth.toDouble() / canvasHeight

        if (fitMode.value == "Crop") {
            val cropWidth = minOf(source.width, (source.height * ratio).roundToInt())
            val cropHeight = minOf(source.height, (source.width / ratio).roundToInt())
            return NativeImage(canvasWidth, canvasHeight, false).also {
                source.resizeSubRectTo((source.width - cropWidth) / 2, (source.height - cropHeight) / 2, cropWidth, cropHeight, it)
            }
        }

        val scale = minOf(canvasWidth.toDouble() / source.width, canvasHeight.toDouble() / source.height)
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = NativeImage(w, h, false).also { source.resizeSubRectTo(0, 0, source.width, source.height, it) }
        return scaled.use {
            NativeImage(canvasWidth, canvasHeight, false).also { wall ->
                wall.fillRect(0, 0, canvasWidth, canvasHeight, 0)
                it.copyRect(wall, 0, 0, (canvasWidth - w) / 2, (canvasHeight - h) / 2, w, h, false, false)
            }
        }
    }

    private fun msg(text: String) {
        Debug.line("Hub Map", text)
        mc.execute { mc.player?.sendSystemMessage(Component.literal("§8[§dCC§8] §7Hub Map: $text")) }
    }
}
