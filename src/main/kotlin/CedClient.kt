package ced.cedclient

import ced.cedclient.commands.CedClientCommand
import ced.cedclient.config.ConfigManager
import ced.cedclient.features.ModuleManager
import ced.cedclient.features.impl.funqol.CoralotHelper
import ced.cedclient.features.impl.funqol.FishingHelper
import ced.cedclient.features.impl.render.HudEditScreen
import ced.cedclient.features.impl.misc.AdvancedMode

import ced.cedclient.features.impl.misc.ResetPanels

import ced.cedclient.features.impl.misc.InventoryButtons
import ced.cedclient.features.impl.funqol.LassoHelper
import ced.cedclient.features.impl.funqol.PangolinCatcher


import ced.cedclient.features.impl.render.EntityESP
import ced.cedclient.features.impl.render.EntityESPHud
import ced.cedclient.features.impl.render.EntityESPRenderer
import ced.cedclient.features.impl.render.Freecam
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.inventory.InventoryButtonManager
import ced.cedclient.ui.nvg.NVGSpecialRenderer
import ced.cedclient.utils.Debug
import ced.cedclient.utils.MouseLookDebugger
import kotlinx.coroutines.Job
import kotlinx.datetime.Month
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import java.awt.Component

class CedClient : ClientModInitializer {

    private lateinit var openGuiKey: KeyMapping

    override fun onInitializeClient() {

        println("CedClient initialized (client)")

        // Load inventory buttons on the first tick (safe filesystem)
        ClientTickEvents.END_CLIENT_TICK.register {
            InventoryButtonManager.ensureLoaded()
        }

        // Register NVG renderer
        PictureInPictureRendererRegistry.register { context ->
            NVGSpecialRenderer(context.bufferSource())
        }

        // Register EntityESP HUD overlay
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("cedclient", "entity_esp_hud")
        ) { graphics, tickCounter ->
            EntityESPHud.render(graphics, tickCounter)
        }

        // Register modules and renderers
        EntityESPRenderer.register()

        EntityESPHud.load()
        MouseLookDebugger.register()


        ModuleManager.register(PangolinCatcher)
        ModuleManager.register(LassoHelper)

        ModuleManager.register(ResetPanels)
        ModuleManager.register(AdvancedMode)

        ModuleManager.register(Freecam)
        ModuleManager.register(CoralotHelper)
        ModuleManager.register(EntityESP)
        ModuleManager.register(FishingHelper)
        ModuleManager.register(InventoryButtons)

        // Defensive: touch ModuleManager.modules to force initialization (if it's lazily initialized)
        // Force ModuleManager initialization safely
        try {
            ModuleManager.modules
        } catch (t: Throwable) {
            // ignore; we'll still attempt to load config below and catch any errors
        }


        // Load module config safely. If something inside loadModulesOnly throws (NPE etc.),
        // catch it and log the stacktrace instead of crashing the client.
        try {
            ConfigManager.loadModulesOnly()
        } catch (t: Throwable) {
            t.printStackTrace()
            // Optionally log a friendly message so you can find this in logs
            println("Warning: ConfigManager.loadModulesOnly() failed during startup. Continuing without module config load.")
        }

        InventoryButtonManager.ensureLoaded()

        CedClientCommand.register()

        val CEDCLIENT_CATEGORY = KeyMapping.Category.register(
            Identifier.withDefaultNamespace("cedclient")
        )

        val editHudKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "Edit ESP HUD",
                GLFW.GLFW_KEY_H,
                CEDCLIENT_CATEGORY
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (editHudKey.consumeClick()) {
                client.setScreen(HudEditScreen())
            }
        }

        openGuiKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "CedClient Gui",
                GLFW.GLFW_KEY_P,
                CEDCLIENT_CATEGORY
            )
        )
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (openGuiKey.consumeClick()){
                client.setScreen(ClickGUI())
            }

        }

        val freecamToggleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.cedclient.freecam_toggle",
                GLFW.GLFW_KEY_B,
                CEDCLIENT_CATEGORY
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (freecamToggleKey.consumeClick()) {
                Freecam.toggle()
            }
        }
    }

}