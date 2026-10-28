package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Debug
import ced.cedclient.utils.Keys
import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.io.File
import java.io.FileInputStream

/**
 * Screenshot Preview: after a screenshot a small thumbnail shows in the bottom right corner for a few seconds, with keys
 * to open the picture or delete it. It reacts to vanilla's "screenshot.success" chat line (ScreenshotTweaks.handleChatMessage
 * calls [onScreenshot]), which is only sent after the PNG is written, then reads the newest PNG in the screenshots folder.
 */
object ScreenshotPreview : Module(
    "Screenshot Preview", Category.Misc,
    "Shows a small preview of each screenshot you take, with keys to open or delete it."
) {
    private val seconds = NumberSetting("Show For (s)", 6.0, 2.0, 20.0, 1.0)
    private val previewWidth = NumberSetting("Preview Width", 160.0, 80.0, 400.0, 10.0)
    private val hideChat = BooleanSetting("Hide Chat Line", true, "Removes the \"Saved screenshot as ...\" chat line.")
    private val openKey = KeybindSetting("Open Key", 18, "Opens the previewed screenshot while its preview shows. (default: O)")
    private val deleteKey = KeybindSetting("Delete Key", Keys.DELETE, "Deletes the previewed screenshot while its preview shows.")

    private class Shot(val file: File, val id: Identifier, val width: Int, val height: Int, val shownAt: Long) {
        @Volatile var deleted = false
    }

    private var current: Shot? = null
    private var lastPath: String? = null
    private var counter = 0
    private var openWas = false
    private var deleteWas = false

    private const val FRESH_MS = 10_000L

    init {
        addSettings(seconds, previewWidth, hideChat, openKey, deleteKey)
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("cedclient", "screenshot_preview")) { g, _ -> draw(g) }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val open = client.gui.screen() == null && Keys.isDown(openKey.value)
            val delete = client.gui.screen() == null && Keys.isDown(deleteKey.value)
            val shot = current
            if (shot != null && !shot.deleted) {
                if (open && !openWas) openFile(shot.file)
                if (delete && !deleteWas) {
                    shot.deleted = shot.file.delete()
                    Debug.line(name, "deleted ${shot.file.name}: ${shot.deleted}")
                    client.player?.sendSystemMessage(ChatPrefix.msgFor(name, if (shot.deleted) "§cScreenshot deleted" else "§cCouldn't delete the screenshot"))
                }
            }
            openWas = open
            deleteWas = delete
        }
    }

    /** Called on the main thread for each "Saved screenshot" line. Returns true if the chat line should be hidden. */
    fun onScreenshot(): Boolean {
        val dir = File(Minecraft.getInstance().gameDirectory, "screenshots")
        // Off the main thread: finding the file and decoding the PNG shouldn't hitch a frame.
        Thread({
            runCatching {
                val file = dir.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }
                    ?.maxByOrNull { it.lastModified() } ?: return@runCatching
                // Chat Patches replays old "Saved screenshot" lines on join: only a file written just now counts.
                if (System.currentTimeMillis() - file.lastModified() > FRESH_MS || file.path == lastPath) return@runCatching
                lastPath = file.path
                val image = FileInputStream(file).use { NativeImage.read(it) }
                Minecraft.getInstance().execute { show(file, image) }
            }.onFailure { Debug.line(name, "couldn't load the screenshot: ${it.message}") }
        }, "CedClient-ScreenshotPreview").apply { isDaemon = true }.start()
        return hideChat.value
    }

    private fun show(file: File, image: NativeImage) {
        val mc = Minecraft.getInstance()
        release()
        val id = Identifier.fromNamespaceAndPath("cedclient", "screenshot_preview/${counter++}")
        val w = image.width
        val h = image.height
        mc.textureManager.register(id, DynamicTexture({ "cedclient screenshot preview" }, image))
        current = Shot(file, id, w, h, System.currentTimeMillis())
        Debug.line(name, "showing ${file.name} (${w}x$h)")
    }

    private fun release() {
        current?.let { Minecraft.getInstance().textureManager.release(it.id) }
        current = null
    }

    private fun draw(g: net.minecraft.client.gui.GuiGraphicsExtractor) {
        val shot = current ?: return
        if (!isEnabled || System.currentTimeMillis() - shot.shownAt > seconds.value * 1000) { release(); return }
        val font = Minecraft.getInstance().font
        val w = previewWidth.value.toInt()
        val h = (w * shot.height.toFloat() / shot.width).toInt().coerceAtLeast(1)
        val x = g.guiWidth() - w - 10
        val y = g.guiHeight() - h - 44
        g.fill(x - 3, y - 3, x + w + 3, y + h + 24, 0xE0101018.toInt())
        if (shot.deleted) {
            g.fill(x, y, x + w, y + h, 0xFF202028.toInt())
            g.text(font, "Deleted", x + 4, y + 4, 0xFFFF5555.toInt())
        } else {
            g.blit(RenderPipelines.GUI_TEXTURED, shot.id, x, y, 0f, 0f, w, h, shot.width, shot.height, shot.width, shot.height)
            g.text(font, "[${Keys.name(openKey.value)}] Open   [${Keys.name(deleteKey.value)}] Delete", x, y + h + 4, 0xFFAAAAAA.toInt())
        }
        g.text(font, shot.file.name, x, y + h + 14, 0xFF777777.toInt())
    }

    private fun openFile(file: File) {
        runCatching {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val path = file.absolutePath
            val command = when {
                "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", path)
                "mac" in os -> listOf("open", path)
                else -> listOf("xdg-open", path)
            }
            ProcessBuilder(command).start()
        }.onFailure { Debug.line(name, "couldn't open the screenshot: ${it.message}") }
    }
}
