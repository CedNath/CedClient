package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import net.minecraft.client.Minecraft

/** All the dungeon secret helpers in one module: Secret Clicked boxes, Left Click Secrets and the Secret HUD, each with its own options popup. */
object Secrets : Module(
    "Secrets",
    Category.Skyblock,
    "Dungeon secret helpers: boxes on the secrets you take, left-click secrets, and a HUD with the secrets done in your room."
) {
    init {
        feature(SecretHighlight.on, "Secret Clicked", "Box color and style, through walls, triggers, sound, locked chest title.", SecretHighlight.popupColumns, SecretHighlight.popupActions)
        feature(LeftClickSecrets.on, "Left Click Secrets", "Water Board levers.", LeftClickSecrets.popupColumns)
        feature(SecretHud.on, "Secret HUD", "Count format, room name, colors, background, size.", SecretHud.popupColumns)
    }

    /** A feature's on / off switch plus one "<title> Options" button that opens its settings in a popup. */
    private fun feature(toggle: BooleanSetting, title: String, desc: String, columns: List<List<SettingsPopup.Section>>, actions: List<ActionSetting> = emptyList()) {
        addSettings(toggle, ActionSetting("$title Options", desc) {
            (Minecraft.getInstance().screen as? ClickGUI)?.openPopup(SettingsPopup(title, columns, actions))
        })
        val all = columns.flatten().flatMap { it.settings }
        addSettings(*all.toTypedArray())
        all.forEach { it.inPopup = true }
    }

    override fun onDisable() = SecretHighlight.clear()
}
