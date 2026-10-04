package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.components.ImageButton
import net.minecraft.client.gui.screens.inventory.CraftingScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen

/**
 * Removes the recipe book button from your inventory (and optionally the crafting table).
 *
 * Done after the screen has finished initializing: every ImageButton on the screen gets
 * hidden and deactivated (an invisible, inactive widget neither draws nor takes clicks).
 * The recipe book button is the only ImageButton on these screens. The hook re-runs on every
 * init, so it also survives window resizes.
 */
object HideRecipeBook : Module(
    "HideRecipeBook",
    Category.Misc,
    "Hides the recipe book button in your inventory",
    defaultEnabled = true
) {
    private val alsoCraftingTable = BooleanSetting(
        "Also Crafting Table", true,
        "Hide the recipe book button on the crafting table screen too."
    )

    init {
        addSettings(alsoCraftingTable)

        ScreenEvents.AFTER_INIT.register(ScreenEvents.AfterInit { _, screen, _, _ ->
            if (!isEnabled) return@AfterInit

            val matches = screen is InventoryScreen || (alsoCraftingTable.value && screen is CraftingScreen)
            if (!matches) return@AfterInit

            for (child in screen.children()) {
                if (child is ImageButton) {
                    child.visible = false
                    child.active = false
                }
            }
        })
    }
}