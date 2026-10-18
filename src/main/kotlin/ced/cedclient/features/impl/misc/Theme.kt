package ced.cedclient.features.impl.misc

import ced.cedclient.utils.ChatPrefix
import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.ModuleManager
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.ui.clickgui.ClickGUI
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.Color
import ced.cedclient.utils.Color.Companion.darker

/**
 * Client Settings (object still called Theme): how CedClient itself looks and behaves. Client-wide accent colours. Always on (it only holds settings): clicking it in the ClickGUI opens its settings
 * instead of toggling it.
 *
 *  - GUI Accent: ClickGUI.clickGUIColor -- enabled modules, popups, the search bar, the preset / button editors.
 *  - HUD Accent: the badges, dividers and bullets on the HUD panels (Daily Reset, Entity ESP HUD), plus a darker
 *    shade of it for the panel outlines (Time HUD, Chat Channel HUD).
 *  - Open / Popup Animation + Animation Speed: how the ClickGUI panels and its popups appear (see ClickGUI).
 *  - Reset Everything: every module, setting, HUD panel and ClickGUI panel back to its default.
 */
object Theme : Module(
    "Mod Settings",
    Category.Misc,
    "How CedClient looks and behaves: accent colours, GUI animations, and a full reset. Always on -- click to edit.",
    defaultEnabled = true
) {
    override val alwaysEnabled = true

    val guiAccent = ColorSetting("GUI Accent", Color(0x69, 0x00, 0x00), "Enabled modules, popups and other ClickGUI highlights.")
    val hudAccent = ColorSetting("HUD Accent", Color(0xFF, 0x80, 0x80), "Badges, dividers and outlines on the HUD panels.")

    val openAnimation = DropdownSetting(
        "Open Animation",
        listOf(
            "Slide Up", "Slide Down", "Slide Left", "Slide Right", "Fade", "Zoom In", "Zoom Out", "Pop",
            "Bounce", "Elastic", "Drop", "Flip", "Swing", "Spin", "Cascade", "None",
        ),
        "Swing",
        "How the ClickGUI panels appear when it opens (one after another, left to right)."
    )
    val popupAnimation = DropdownSetting(
        "Popup Animation",
        listOf("Pop", "Fade", "Zoom", "Slide Up", "Slide Down", "Bounce", "None"),
        "Pop",
        "How popups (filters, block picker, settings) appear."
    )
    val animationSpeed = NumberSetting("Animation Speed", 0.6, 0.25, 3.0, 0.05, "Higher is faster. Applies to both animations.")
    val hudOutlines = BooleanSetting("HUD Outlines", false, "A thin outline in the HUD accent around Compact Tab and Compact Scoreboard.")
    val menuBlur = BooleanSetting("Menu Blur", true, "Blur the game behind the CedClient menu.")
    val blurStrength = NumberSetting("Blur Strength", 3.0, 1.0, 10.0, 1.0, "How strong the menu blur is.").withDependency { menuBlur.value }

    private val resetColors = ActionSetting("Reset Colors", "Back to the default purple.") {
        guiAccent.value = guiAccent.default.copy()
        hudAccent.value = hudAccent.default.copy()
        ConfigManager.saveModulesOnly()
    }

    private var resetArmedAt = 0L
    private val resetEverything = ActionSetting(
        "Reset Everything",
        "Every module, setting, HUD position and ClickGUI panel back to its default. Click twice."
    ) {
        if (System.currentTimeMillis() - resetArmedAt > 3000L) {
            resetArmedAt = System.currentTimeMillis()
            Minecraft.getInstance().player?.sendSystemMessage(ChatPrefix.msg("§cClick Reset Everything again to reset all of CedClient"))
            return@ActionSetting
        }
        resetArmedAt = 0L
        resetAll()
    }

    private val resetModule = ActionSetting("Reset a Module", "Pick one module to put back to its defaults.") {
        (Minecraft.getInstance().gui.screen() as? ced.cedclient.ui.clickgui.ClickGUI)?.openPopup(ced.cedclient.ui.clickgui.ResetModulePopup())
    }

    init {
        addSettings(guiAccent, hudAccent, resetColors, openAnimation, popupAnimation, animationSpeed, hudOutlines, menuBlur, blurStrength, resetModule, resetEverything)
        // Animation tuning only shows with Advanced Mode on.
        listOf(openAnimation, popupAnimation, animationSpeed).forEach { it.advanced = true }
    }

    private fun resetAll() {
        for (module in ModuleManager.modules) {
            module.settings.values.forEach { it.reset() }
            if (!module.isAction) module.setEnabled(module.defaultEnabled)
        }
        HudElement.all.forEach { it.resetPosition(); it.save() }
        HudElement.layoutEdited()
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.let { ConfigManager.resetPanelsToDefaults(it.getPanels()) }
        ConfigManager.saveModulesOnly()
        Minecraft.getInstance().player?.sendSystemMessage(ChatPrefix.msg("§fEverything reset to defaults"))
    }

    /** HUD accent as ARGB. */
    val hudAccentRgba: Int get() = hudAccent.value.rgba

    /** Darker shade of the HUD accent for panel outlines (the default gives the old 0x5A4FCF-ish border). */
    val hudBorderRgba: Int get() = hudAccent.value.darker(0.75f).rgba
}
