package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.chat.contents.TranslatableContents
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Two small F2-screenshot tweaks:
 *  - Hide Message: drops vanilla's "Saved screenshot as ..." chat line.
 *  - Copy To Clipboard: puts the screenshot on the clipboard so you can Ctrl+V it straight
 *    into Discord etc.
 *  - Show Confirmation: a small colored "[CedClient] Screenshot copied to clipboard" line in
 *    chat once the copy has actually succeeded (so the vanilla line can be hidden without
 *    wondering whether it worked).
 *
 * Instead of hooking vanilla's screenshot code (which moves around between versions), this
 * reacts to the chat line itself: vanilla only sends "screenshot.success" AFTER the PNG has
 * been written to disk, so by then the file is guaranteed to exist and we just read the
 * newest PNG in the screenshots folder. ChatComponentMixin calls [handleChatMessage] for
 * every line that goes to the chat window.
 */
object ScreenshotTweaks : Module(
    "ScreenshotTweaks",
    Category.Misc,
    "Hides the screenshot chat message and copies screenshots to your clipboard",
    defaultEnabled = true
) {
    private const val SUCCESS_KEY = "screenshot.success"

    private val hideMessage = BooleanSetting("Hide Message", true, "Removes the \"Saved screenshot as ...\" chat line.")
    private val copyToClipboard = BooleanSetting("Copy To Clipboard", true, "Copies every screenshot you take to the clipboard.")
    private val showConfirmation = BooleanSetting("Show Confirmation", true, "Shows a [CedClient] chat line once the screenshot has been copied.")

    private val PREFIX_COLOR = TextColor.fromRgb(0x8A7FFF) // same purple as the other [CedClient] messages
    private val CHECK_COLOR = TextColor.fromRgb(0x55FF55)
    private val BODY_COLOR = TextColor.fromRgb(0xDDDDDD)
    private val HIGHLIGHT_COLOR = TextColor.fromRgb(0x55FFFF)

    // Guards against the same screenshot being handled twice (if the line ever passes through
    // addMessage more than once).
    private var lastHandledAt = 0L

    init {
        addSettings(hideMessage, copyToClipboard, showConfirmation)
    }

    /**
     * Called from ChatComponentMixin on the main thread for each chat line.
     * @return true if the line should be hidden.
     */
    fun handleChatMessage(message: Component): Boolean {
        if (!isEnabled) return false
        val contents = message.contents as? TranslatableContents ?: return false
        if (contents.key != SUCCESS_KEY) return false

        val now = System.currentTimeMillis()
        if (copyToClipboard.value && now - lastHandledAt > 1000L) {
            lastHandledAt = now
            copyNewestScreenshot()
        }
        return hideMessage.value
    }

    private fun copyNewestScreenshot() {
        val dir = File(Minecraft.getInstance().gameDirectory, "screenshots")
        // Off the main thread: decoding a PNG and talking to the OS clipboard shouldn't hitch a frame.
        Thread({
            try {
                if (GraphicsEnvironment.isHeadless()) {
                    println("[ScreenshotTweaks] AWT is headless, can't access the clipboard")
                    return@Thread
                }
                val file = dir.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }
                    ?.maxByOrNull { it.lastModified() }
                    ?: return@Thread

                val source = ImageIO.read(file) ?: return@Thread
                // Screenshots are opaque; flatten to plain RGB so every app pastes it correctly.
                val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                g.drawImage(source, 0, 0, null)
                g.dispose()

                Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageSelection(image), null)

                // Only announce once the copy has really gone through; chat must be touched on the main thread.
                if (showConfirmation.value) {
                    Minecraft.getInstance().execute { announceCopied() }
                }
            } catch (t: Throwable) {
                println("[ScreenshotTweaks] Failed to copy screenshot: ${t.message}")
            }
        }, "CedClient-ScreenshotCopy").apply { isDaemon = true }.start()
    }

    private fun announceCopied() {
        val chat = Minecraft.getInstance().gui?.chat ?: return
        val message = Component.literal("[CedClient] ")
            .withStyle(Style.EMPTY.withColor(PREFIX_COLOR).withBold(true))
            .append(Component.literal("\u2714 ").withStyle(Style.EMPTY.withColor(CHECK_COLOR).withBold(true)))
            .append(Component.literal("Screenshot copied to ").withStyle(Style.EMPTY.withColor(BODY_COLOR)))
            .append(Component.literal("clipboard").withStyle(Style.EMPTY.withColor(HIGHLIGHT_COLOR).withBold(true)))
        chat.addClientSystemMessage(message)
    }

    private class ImageSelection(private val image: BufferedImage) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return image
        }
    }
}