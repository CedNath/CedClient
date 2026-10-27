package ced.cedclient.features.impl.misc

import ced.cedclient.config.CedPaths
import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Debug
import net.minecraft.client.Minecraft

/**
 * Share your setup: every module's on / off and settings as one "CC1:..." code, copied to the clipboard or saved to
 * config/cedclient/share-code.txt, and the reverse. Panel and HUD positions are not included.
 */
object ConfigShare : Module(
    "Config Share",
    Category.Misc,
    "Copy your whole setup as a code, or import someone else's."
) {
    override val alwaysEnabled = true

    private val mc get() = Minecraft.getInstance()
    private val file get() = CedPaths.file("share-code.txt")

    private fun say(text: String) = mc.player?.sendSystemMessage(ChatPrefix.msg(text))

    private fun copy() {
        val code = ConfigManager.exportCode()
        mc.keyboardHandler.clipboard = code
        Debug.line("ConfigShare", "exported ${code.length} chars to the clipboard")
        say("§aCopied your setup §7(${code.length} characters). Paste it anywhere to share.")
    }

    private fun save() {
        val code = ConfigManager.exportCode()
        val ok = runCatching { file.writeText(code) }.isSuccess
        Debug.line("ConfigShare", if (ok) "saved ${file.name}" else "could not write ${file.name}")
        say(if (ok) "§aSaved your setup to §fconfig/cedclient/${file.name}" else "§cCould not write ${file.name}.")
    }

    private fun import(code: String, from: String) {
        val count = ConfigManager.importCode(code)
        Debug.line("ConfigShare", "import from $from -> ${count ?: "invalid code"}")
        say(if (count == null) "§cThat isn't a CedClient code (it starts with CC1:)." else "§aImported §f$count §amodules from $from. §7Some changes need a restart.")
    }

    init {
        addSettings(
            ActionSetting("Copy Setup Code", "Puts your whole setup on the clipboard as one code.") { copy() },
            ActionSetting("Import From Clipboard", "Applies a code you copied. Replaces the settings of every module it covers.") { import(mc.keyboardHandler.clipboard, "the clipboard") },
            ActionSetting("Save Setup To File", "Writes the code to config/cedclient/share-code.txt.") { save() },
            ActionSetting("Import From File", "Reads config/cedclient/share-code.txt.") {
                val text = runCatching { file.readText() }.getOrNull()
                if (text == null) say("§cNo share-code.txt yet.") else import(text, "share-code.txt")
            },
        )
    }
}
