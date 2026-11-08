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
 * Client Settings (object still called Theme): how CedClient itself looks and behaves. Always on (it only holds settings): clicking it in the ClickGUI opens its settings
 * instead of toggling it.
 *
 *  - Accent: the one colour the player picks (default none = white). ClickGUI.clickGUIColor and hudAccentRgba both
 *    read it: the dot on enabled modules, sliders and toggles, HUD headline numbers, the [CC] chat prefix.
 *    Everything else uses ced.cedclient.ui.Palette.
 *  - Accent Chosen / Look Version: hidden markers for the first-open accent picker and the one-time look reset.
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

    val accent = ColorSetting("Accent", Color(0xF2, 0xF3, 0xF5), "Headline numbers, the dot on enabled modules, sliders and the chat prefix. White = no accent.")
    /** Set once the first-open accent picker was answered (Done, Skip or Esc). */
    val accentChosen = BooleanSetting("Accent Chosen", false, "").also { it.hide() }
    /** Which one-time look reset (LookMigration) this config has had. */
    val lookVersion = NumberSetting("Look Version", 0.0, 0.0, 10.0, 1.0, "").also { it.hide() }

    val fixedHudSize = BooleanSetting("Fixed HUD Size", true, "HUD cards keep the widest size they've had, so they don't jump around as numbers change. Starts over after 30 s without the HUD showing, or when you rescale it.")
    val menuLayout = DropdownSetting(
        "Menu Layout",
        listOf("Compact", "Classic"),
        "Classic",
        "Compact: a category list, the module list and the module's settings as three panels. Classic: one panel per category."
    )
//#if CHEAT
    val separateCheats = BooleanSetting("Separate Cheats Panel", true, "Moves the cheat modules (ESP, triggerbots, auto helpers ...) out of their panels into one Cheats panel. Takes effect the next time the menu opens.")
//#endif
    val menuOpacity = NumberSetting("Menu Opacity", 100.0, 30.0, 100.0, 5.0, "How solid the Compact menu panels are, in percent. Lower is more see-through.").withDependency { menuLayout.value == "Compact" }
    val uiFont = DropdownSetting("Font", listOf("Inter", "Minecraft"), "Inter", "Text font of the menus and the HUD cards. Minecraft is the game's own pixel font, taken from your install.")
    val menuSize = NumberSetting("Menu Size", 135.0, 70.0, 200.0, 5.0, "How big the Compact menu is, in percent.").withDependency { menuLayout.value == "Compact" }
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

    private val pickAccent = ActionSetting("Pick Accent", "Opens the accent picker again.") {
        (Minecraft.getInstance().gui.screen() as? ced.cedclient.ui.clickgui.ClickGUI)?.openPopup(ced.cedclient.ui.clickgui.AccentPickerPopup())
    }

    private val resetColors = ActionSetting("Reset Colors", "Back to no accent.") {
        accent.value = accent.default.copy()
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

    private val resetPanels = ActionSetting("Reset Panels", "Moves every ClickGUI panel back to its default position.") {
        (Minecraft.getInstance().gui.screen() as? ced.cedclient.ui.clickgui.ClickGUI)?.resetPanelPositions()
    }
    private val resetModule = ActionSetting("Reset a Module", "Pick one module to put back to its defaults.") {
        (Minecraft.getInstance().gui.screen() as? ced.cedclient.ui.clickgui.ClickGUI)?.openPopup(ced.cedclient.ui.clickgui.ResetModulePopup())
    }

    init {
        addSettings(accent, pickAccent, accentChosen, lookVersion, fixedHudSize, uiFont, menuLayout, menuOpacity, menuSize, menuBlur, blurStrength, openAnimation, popupAnimation, animationSpeed, hudOutlines, resetColors, resetPanels, resetModule, resetEverything)
//#if CHEAT
        addSettings(separateCheats)
        separateCheats.section = "Menu"
//#endif
        listOf(accent, pickAccent).forEach { it.section = "Colors" }
        listOf(menuLayout, menuOpacity, menuSize, menuBlur, blurStrength, openAnimation, popupAnimation, animationSpeed).forEach { it.section = "Menu" }
        uiFont.section = "Text"
        hudOutlines.section = "HUD"
        listOf(resetColors, resetPanels, resetModule, resetEverything).forEach { it.section = "Reset" }
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
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.resetPanelPositions()
        ConfigManager.saveModulesOnly()
        Minecraft.getInstance().player?.sendSystemMessage(ChatPrefix.msg("§fEverything reset to defaults"))
    }

    /** The accent as ARGB (HUD headline numbers, badges, the [CC] chat prefix). */
    val hudAccentRgba: Int get() = accent.value.rgba

    /** Panel outlines: the faint white border every card and panel shares (no longer a shade of the accent). */
    val hudBorderRgba: Int get() = ced.cedclient.ui.Palette.EDGE
}
