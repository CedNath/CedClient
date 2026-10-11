package ced.cedclient.features.impl.misc

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.Color
import ced.cedclient.utils.Color.Companion.darker

/**
 * Client-wide accent colours. Always on (it only holds settings): clicking it in the ClickGUI opens its settings
 * instead of toggling it.
 *
 *  - GUI Accent: ClickGUI.clickGUIColor -- enabled modules, popups, the search bar, the preset / button editors.
 *  - HUD Accent: the badges, dividers and bullets on the HUD panels (Daily Reset, Entity ESP HUD), plus a darker
 *    shade of it for the panel outlines (Time HUD, Chat Channel HUD).
 *  - Open / Popup Animation + Animation Speed: how the ClickGUI panels and its popups appear (see ClickGUI).
 */
object Theme : Module(
    "Theme",
    Category.Misc,
    "Accent colours for the ClickGUI and the HUD. Always on -- click to edit.",
    defaultEnabled = true
) {
    override val alwaysEnabled = true

    val guiAccent = ColorSetting("GUI Accent", Color(110, 50, 200), "Enabled modules, popups and other ClickGUI highlights.")
    val hudAccent = ColorSetting("HUD Accent", Color(0x8A, 0x7F, 0xFF), "Badges, dividers and outlines on the HUD panels.")

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

    private val resetColors = ActionSetting("Reset Colors", "Back to the default purple.") {
        guiAccent.value = guiAccent.default.copy()
        hudAccent.value = hudAccent.default.copy()
        ConfigManager.saveModulesOnly()
    }

    init {
        addSettings(guiAccent, hudAccent, resetColors, openAnimation, popupAnimation, animationSpeed)
    }

    /** HUD accent as ARGB. */
    val hudAccentRgba: Int get() = hudAccent.value.rgba

    /** Darker shade of the HUD accent for panel outlines (the default gives the old 0x5A4FCF-ish border). */
    val hudBorderRgba: Int get() = hudAccent.value.darker(0.75f).rgba
}
