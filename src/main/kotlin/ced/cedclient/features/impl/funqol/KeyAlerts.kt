package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft

/**
 * Key Alerts: a popup HUD card (its own spot in the HUD editor, via ClientTitles) when a Blood or Wither key is picked
 * up: "[MVP++] CedNath has obtained Blood Key!" / "... Wither Key! (x2)".
 */
object KeyAlerts : Module(
    "Key Alerts",
    Category.Dungeons,
    "A popup HUD card when a Blood or Wither key is picked up in a dungeon."
) {
    private val blood = BooleanSetting("Blood Key", true, "Alert when the Blood Key is picked up.")
    private val wither = BooleanSetting("Wither Key", true, "Alert when a Wither Key is picked up.")
    private val onlyMine = BooleanSetting("Only My Keys", false, "Only alert for keys you pick up yourself, not your party's.")
    private val sound = BooleanSetting("Sound", true, "A ding with the alert.")

    // The name sits after an optional [rank]; a player's own chat line ("Name: ...") never matches because of the anchor.
    private val KEY = Regex("""^(?:\[[^\]]+]\s*)?(\w{1,16}) has obtained (Wither|Blood) Key!(?: \(x(\d+)\))?""")
    private val CODES = Regex("§.")

    init {
        addSettings(blood, wither, onlyMine, sound)
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (overlay || !isEnabled) return@register
            val m = KEY.find(message.string.replace(CODES, "").trim()) ?: return@register
            val (who, kind) = m.destructured
            val count = m.groupValues[3].toIntOrNull()
            val isBlood = kind == "Blood"
            if (if (isBlood) !blood.value else !wither.value) return@register
            val mine = who.equals(Minecraft.getInstance().user.name, ignoreCase = true)
            if (onlyMine.value && !mine) return@register
            ced.cedclient.utils.Debug.line(name, "$who obtained $kind Key (x${count ?: 1}), mine=$mine")
            Alerts.title(name, "$kind Key", if (count != null) "$who (x$count)" else who, if (isBlood) 0xFF5555 else 0xAAAAAA, sound.value, 1.2f)
        }
    }
}
