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
    defaultEnabled = false
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

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val isMac = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

    init {
        addSettings(hideMessage, copyToClipboard, showConfirmation)
        // The game runs with java.awt.headless=true, which locks AWT out of the system clipboard.
        // AWT reads the flag lazily the first time it's used, so clearing it here (at mod init,
        // before anything touches AWT) is enough. Not on macOS: AWT and GLFW both want the main
        // thread there, which is exactly why the flag gets set.
        if (!isMac) System.setProperty("java.awt.headless", "false")
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
                val file = dir.listFiles { f -> f.isFile && f.name.endsWith(".png", ignoreCase = true) }
                    ?.maxByOrNull { it.lastModified() }
                    ?: return@Thread

                if (GraphicsEnvironment.isHeadless()) {
                    // AWT got initialized headless before our init could clear the flag.
                    if (!isWindows || !copyViaPowerShell(file)) {
                        println("[ScreenshotTweaks] AWT is headless, can't access the clipboard")
                        return@Thread
                    }
                } else {
                    val source = ImageIO.read(file) ?: return@Thread
                    // Screenshots are opaque; flatten to plain RGB so every app pastes it correctly.
                    val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
                    val g = image.createGraphics()
                    g.drawImage(source, 0, 0, null)
                    g.dispose()

                    Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageSelection(image), null)
                }

                // Only announce once the copy has really gone through; chat must be touched on the main thread.
                if (showConfirmation.value) {
                    Minecraft.getInstance().execute { announceCopied() }
                }
            } catch (t: Throwable) {
                println("[ScreenshotTweaks] Failed to copy screenshot: ${t.message}")
            }
        }, "CedClient-ScreenshotCopy").apply { isDaemon = true }.start()
    }

    /**
     * Windows fallback that doesn't need AWT: let .NET put the image on the clipboard.
     * The path goes in through an env var so no quoting/escaping of the filename is needed.
     */
    private fun copyViaPowerShell(file: File): Boolean {
        val script = "Add-Type -AssemblyName System.Windows.Forms,System.Drawing; " +
            "\$img = [System.Drawing.Image]::FromFile(\$env:CED_SCREENSHOT); " +
            "[System.Windows.Forms.Clipboard]::SetImage(\$img); \$img.Dispose()"
        // Full path: the game's PATH isn't guaranteed to include PowerShell.
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val powershell = File(systemRoot, "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
            .takeIf { it.isFile }?.path ?: "powershell"
        val process = ProcessBuilder(powershell,"-NoProfile", "-NonInteractive", "-STA", "-Command", script)
            .redirectErrorStream(true)
            .apply { environment()["CED_SCREENSHOT"] = file.absolutePath }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val ok = process.waitFor() == 0
        if (!ok) println("[ScreenshotTweaks] PowerShell clipboard copy failed: $output")
        return ok
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