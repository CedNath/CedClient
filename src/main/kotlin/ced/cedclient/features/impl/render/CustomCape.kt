package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.utils.Debug
import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.entity.state.AvatarRenderState
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.ClientAsset
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Avatar
import net.minecraft.world.entity.player.PlayerSkin
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.function.Supplier
import javax.imageio.ImageIO

/**
 * Wear any picture as a cape: put a png / jpg in config/cedclient/capes and pick it here. The picture is stretched
 * over the cape's back (cover-cropped to the cape's 10 x 16 shape) and swapped into your own render state by
 * PlayerRendererMixin, so only you see it, and it replaces whatever cape you have. The cape itself still follows
 * the vanilla rules (shown when your skin settings have Cape on, hidden under a chestplate with an elytra, etc.).
 */
object CustomCape : Module(
    "Custom Cape", Category.Cosmetic,
    "Wear any image as a cape"
) {
    // Declared before [options]: that list is built in the object's initialiser by list(), which reads this (it was still null there).
    private val EXTENSIONS = setOf("png", "jpg", "jpeg")

    private val folder: File get() = CedPaths.dir.resolve("capes")

    /** Names (with extension) of the pictures in the folder. */
    private fun list(): List<String> =
        folder.listFiles { f -> f.extension.lowercase() in EXTENSIONS }?.map { it.name }?.sorted() ?: emptyList()

    private val options = ArrayList(listOf("None") + list())
    private val image = DropdownSetting("Image", options, "None", "A picture from the capes folder (config/cedclient/capes). None: your normal cape.")
    private val reload = ActionSetting("Reload Images", "Looks in the capes folder again (config/cedclient/capes).") {
        options.clear(); options += "None"; options += list()
        if (image.value !in options) image.value = "None"
        loaded = null; failed = null
        Debug.line("CustomCape", "images: ${options.drop(1)}")
    }

    private var loaded: String? = null
    private var failed: String? = null
    private var texture: DynamicTexture? = null
    private var textureId: Identifier? = null
    private var asset: ClientAsset.ResourceTexture? = null
    private var counter = 0

    init {
        folder.mkdirs()
        addSettings(image, reload)

        // Textures are created on the client thread, never from the render hook.
        ClientTickEvents.END_CLIENT_TICK.register {
            val name = if (isEnabled && image.value != "None") image.value else null
            if (name == null) {
                if (loaded != null) release()
                return@register
            }
            if (name != loaded && name != failed) load(name)
        }
    }

    private fun release() {
        textureId?.let { Minecraft.getInstance().textureManager.release(it) }
        texture = null; textureId = null; asset = null; loaded = null
    }

    private fun load(name: String) {
        release()
        try {
            val src = ImageIO.read(File(folder, name)) ?: throw IllegalArgumentException("not a picture")
            val png = ByteArrayOutputStream().also { ImageIO.write(compose(src), "png", it) }.toByteArray()
            val native = NativeImage.read(ByteArrayInputStream(png))
            val tex = DynamicTexture(Supplier { "CedClient cape $name" }, native)
            val newAsset = ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("cedclient", "custom_cape/${counter++}"))
            Minecraft.getInstance().textureManager.register(newAsset.texturePath(), tex)
            texture = tex; textureId = newAsset.texturePath(); asset = newAsset; loaded = name; failed = null
            Debug.line("CustomCape", "loaded $name (${src.width}x${src.height})")
        } catch (e: Exception) {
            failed = name
            Debug.line("CustomCape", "could not load $name: ${e.message}")
        }
    }

    /**
     * Lays [src] out as a cape texture (64 x 32 scaled by [SCALE]): the outer face at (1,1) 10 x 16, cover-cropped
     * so the picture isn't squashed, plus the inner face and the edge strips filled with the same picture so no
     * seam shows.
     */
    private fun compose(src: BufferedImage): BufferedImage {
        val out = BufferedImage(64 * SCALE, 32 * SCALE, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Source rectangle with the cape's 10:16 shape, centred.
        val targetRatio = 10.0 / 16.0
        var sw = src.width.toDouble(); var sh = src.height.toDouble()
        if (sw / sh > targetRatio) sw = sh * targetRatio else sh = sw / targetRatio
        val sx = ((src.width - sw) / 2).toInt(); val sy = ((src.height - sh) / 2).toInt()

        fun paint(x: Int, y: Int, w: Int, h: Int) =
            g.drawImage(src, x * SCALE, y * SCALE, (x + w) * SCALE, (y + h) * SCALE, sx, sy, sx + sw.toInt(), sy + sh.toInt(), null)

        paint(1, 1, 10, 16)   // outer face
        paint(12, 1, 10, 16)  // inner face
        paint(0, 1, 1, 16); paint(11, 1, 1, 16) // sides
        paint(1, 0, 10, 1); paint(11, 0, 10, 1) // top, bottom
        g.dispose()
        return out
    }

    /** From PlayerRendererMixin: puts the picture on your own cape. */
    @JvmStatic
    fun apply(entity: Avatar, state: AvatarRenderState) {
        if (!isEnabled || entity !== Minecraft.getInstance().player) return
        val cape = asset ?: return
        val skin = state.skin
        state.skin = PlayerSkin(skin.body(), cape, skin.elytra(), skin.model(), skin.secure())
    }

    private const val SCALE = 8
}
