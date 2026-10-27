package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.ui.inventory.InventoryButtonManager
import ced.cedclient.ui.inventory.InventoryButtonPresets
import ced.cedclient.ui.inventory.editor.InvButtonEditorScreen
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Debug
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

object InventoryButtons : Module(
    "InventoryButtons",
    Category.Misc,
    description = "Adds customizable NEU-style buttons to your inventory."
) {

    var snapEnabled = true

    // Text rendering behavior
    var showTextOnHover = true
    var hideBlankName = true

    // Icon rendering
    var enableIcons = true

    // Default button size for new buttons
    var defaultWidth = 20
    var defaultHeight = 20

    override fun onEnable() {
        InventoryButtonManager.ensureLoaded()
    }

    // GUI actions
    private val openEditor = ActionSetting("Open Editor") {
        InventoryButtonManager.ensureLoaded()
        Debug.log("InventoryButtons: opening InvButtonEditorScreen -- buttons = ${InventoryButtonManager.buttons.size}")

        Minecraft.getInstance().gui.setScreen(InvButtonEditorScreen())
    }

    // Presets: the dropdown lists built-in and saved ones (it is refreshed when you save / delete).
    private val presetNames = InventoryButtonPresets.names().toMutableList()
    private val preset = DropdownSetting("Preset", presetNames, presetNames.first(), "The preset Load / Delete act on. Built-in ones can't be deleted.")
    private val loadPreset = ActionSetting("Load Preset", "Replaces your buttons with the chosen preset.") { loadSelected() }
    private val newName = TextSetting("Preset Name", "", "Name for Save Preset. An existing preset of yours with that name is replaced.", 24)
    private val savePreset = ActionSetting("Save Current As Preset", "Saves your buttons under the name above, so you can switch back to them any time.") { saveCurrent() }
    private val deletePreset = ActionSetting("Delete Preset", "Deletes the chosen preset (one you saved).") { deleteSelected() }
    private val nextKey = KeybindSetting("Next Preset Key", GLFW.GLFW_KEY_UNKNOWN, "Switches to the next preset (outside of menus).")
    private var keyHeld = false

    private fun say(text: String) = Minecraft.getInstance().player?.sendSystemMessage(ChatPrefix.msgFor("InventoryButtons", text))

    private fun refreshNames() {
        presetNames.clear()
        presetNames.addAll(InventoryButtonPresets.names())
        if (preset.value !in presetNames) preset.value = presetNames.first()
    }

    private fun loadSelected() {
        if (InventoryButtonPresets.apply(preset.value)) say("§7Loaded preset §f${preset.value}") else say("§cNo preset called ${preset.value}")
    }

    private fun saveCurrent() {
        val name = newName.value.trim()
        if (!InventoryButtonPresets.saveCurrent(name)) { say("§cPick a name that isn't empty or one of the built-in presets"); return }
        refreshNames()
        preset.value = name
        say("§7Saved your buttons as §f$name")
    }

    private fun deleteSelected() {
        val name = preset.value
        if (InventoryButtonPresets.isBuiltIn(name) || !InventoryButtonPresets.delete(name)) { say("§cOnly presets you saved can be deleted"); return }
        refreshNames()
        say("§7Deleted preset §f$name")
    }

    /** Switches to the next preset in the list; also used by the editor's preset row and the key. Returns its name. */
    fun cyclePreset(): String {
        refreshNames()
        val next = presetNames[(presetNames.indexOf(preset.value) + 1) % presetNames.size]
        preset.value = next
        loadSelected()
        return next
    }

    /** The preset the dropdown is on (shown in the editor). */
    fun currentPreset(): String = preset.value

    init {
        addSettings(openEditor, preset, loadPreset, newName, savePreset, deletePreset, nextKey)
        ClientTickEvents.END_CLIENT_TICK.register {
            val mc = Minecraft.getInstance()
            val down = isEnabled && mc.gui.screen() == null && nextKey.value > 0 && InputConstants.isKeyDown(mc.window, nextKey.value)
            if (down && !keyHeld) cyclePreset()
            keyHeld = down
        }
    }
}
