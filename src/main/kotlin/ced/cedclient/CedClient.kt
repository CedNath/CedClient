package ced.cedclient

import ced.cedclient.commands.CedClientCommand
import ced.cedclient.config.ConfigManager
import ced.cedclient.features.ModuleManager
import ced.cedclient.features.impl.funqol.CoralotHelper
import ced.cedclient.features.impl.funqol.FishingHelper
import ced.cedclient.features.impl.funqol.LassoHelper
import ced.cedclient.features.impl.funqol.PangolinCatcher
import ced.cedclient.features.impl.funqol.PlayerScale
import ced.cedclient.features.impl.funqol.TinyDancerHelper
import ced.cedclient.features.impl.loot.LootSummaryCommand
import ced.cedclient.features.impl.loot.LootTracker
import ced.cedclient.features.impl.misc.AdvancedMode
import ced.cedclient.features.impl.misc.AutoSprint
import ced.cedclient.chat.PlayerChatManager
import ced.cedclient.features.impl.misc.ChatFilter
import ced.cedclient.features.impl.misc.DailyReset
import ced.cedclient.features.impl.misc.HideRecipeBook
import ced.cedclient.features.impl.misc.InventoryButtons
import ced.cedclient.features.impl.misc.ProfileViewer
import ced.cedclient.features.impl.misc.ResetPanels
import ced.cedclient.features.impl.misc.ScreenshotTweaks
import ced.cedclient.features.impl.misc.WarpShortcuts
import ced.cedclient.features.impl.render.BlockESP
import ced.cedclient.features.impl.render.StarredMobESP
import ced.cedclient.features.impl.render.BlockESPRenderer
import ced.cedclient.features.impl.render.ChatChannelHud
import ced.cedclient.features.impl.render.CompactScoreboard
import ced.cedclient.features.impl.render.CompactTab
import ced.cedclient.features.impl.render.EntityESP
import ced.cedclient.features.impl.render.EntityESPHud
import ced.cedclient.features.impl.render.EntityESPRenderer
import ced.cedclient.features.impl.render.Freecam
import ced.cedclient.features.impl.render.FloorDrops
import ced.cedclient.features.impl.render.Fullbright
import ced.cedclient.features.impl.render.HardcodedCosmetics
import ced.cedclient.features.impl.render.ItemCooldowns
import ced.cedclient.features.impl.render.TimeHud
import ced.cedclient.features.impl.render.Zoom
import ced.cedclient.features.impl.render.nametag.CustomNametag
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.state.CosmeticsSync
import ced.cedclient.state.DungeonState
import ced.cedclient.state.PartyApi
import ced.cedclient.state.IslandState
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.inventory.InventoryButtonManager
import ced.cedclient.utils.PingTracker
import ced.cedclient.utils.TabListCache
import ced.cedclient.utils.UsageStats
import ced.cedclient.utils.debug.MouseLookDebugger
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

class CedClient : ClientModInitializer {

    private lateinit var openGuiKey: KeyMapping
    private lateinit var freecamToggleKey: KeyMapping
    private lateinit var zoomKey: KeyMapping

    private val cedclientCategory: KeyMapping.Category by lazy {
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("cedclient", "cedclient"))
    }

    override fun onInitializeClient() {
        println("CedClient initialized (client)")

        registerAlwaysOnState()
        registerModules()
        registerHudElements()
        registerKeybinds()
        loadConfig()

        CedClientCommand.register()
        LootSummaryCommand.register()
        TabListCache.register()

        // One small launch ping (username, UUID, mod + MC version) so the dev can see
        // who uses the mod. Opt out via config/cedclient-usage.properties (shareUsage=false).
        UsageStats.ping()

    }

    /**
     * State trackers that run regardless of any Module's enabled state --
     * DungeonState needs to track floor/in-dungeon status, CosmeticsSync and
     * LootTracker need to keep listening, and InventoryButtonManager needs
     * its buttons loaded, no matter what's toggled on in the ClickGUI.
     */
    private fun registerAlwaysOnState() {
        DungeonState.init()
        CosmeticsSync.init()
        LootTracker.init()
        IslandState.init()
        PlayerChatManager.init()
        PartyApi.init()

        ClientTickEvents.END_CLIENT_TICK.register {
            InventoryButtonManager.ensureLoaded()
        }
        InventoryButtonManager.ensureLoaded()

        PictureInPictureRendererRegistry.register {
            NVGSpecialRenderer()
        }

        MouseLookDebugger.register()
    }

    private fun registerModules() {
        EntityESPRenderer.register()
        EntityESPHud.load()
        BlockESPRenderer.register()

        ModuleManager.register(PangolinCatcher)
        ModuleManager.register(LassoHelper)
        ModuleManager.register(ChatChannelHud)
        ModuleManager.register(CustomNametag)
        ModuleManager.register(HardcodedCosmetics)
        ModuleManager.register(ResetPanels)
        ModuleManager.register(DailyReset)
        ModuleManager.register(AdvancedMode)
        ModuleManager.register(TimeHud)
        ModuleManager.register(ChatFilter)
        ModuleManager.register(Freecam)
        ModuleManager.register(AutoSprint)
        ModuleManager.register(CoralotHelper)
        ModuleManager.register(EntityESP)
        ModuleManager.register(BlockESP)
        ModuleManager.register(StarredMobESP)
        ModuleManager.register(FloorDrops)
        ModuleManager.register(Fullbright)
        ModuleManager.register(ScreenshotTweaks)
        ModuleManager.register(HideRecipeBook)
        ModuleManager.register(FishingHelper)
        ModuleManager.register(InventoryButtons)
        ModuleManager.register(ItemCooldowns)
        ModuleManager.register(PlayerScale)
        ModuleManager.register(WarpShortcuts)
        ModuleManager.register(Zoom)
        ModuleManager.register(CompactTab)
        ModuleManager.register(CompactScoreboard)
        ModuleManager.register(ProfileViewer)
        ModuleManager.register(TinyDancerHelper)

        // Defensive: touch ModuleManager.modules to force initialization (if it's lazily initialized)
        try {
            ModuleManager.modules
        } catch (t: Throwable) {
            // ignore; we'll still attempt to load config below and catch any errors
        }
    }

    private fun registerHudElements() {
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("cedclient", "entity_esp_hud")
        ) { graphics, tickCounter ->
            EntityESPHud.render(graphics, tickCounter)
        }
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("cedclient", "time_hud")
        ) { graphics, tickCounter ->
            TimeHud.render(graphics, tickCounter)
        }
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("cedclient", "chat_channel_hud")
        ) { graphics, tickCounter ->
            ChatChannelHud.render(graphics, tickCounter)
        }
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("cedclient", "item_cooldowns")
        ) { graphics, tickCounter ->
            ItemCooldowns.render(graphics, tickCounter)
        }
        HudElementRegistry.addLast(
            Identifier.fromNamespaceAndPath("cedclient", "daily_reset")
        ) { graphics, tickCounter ->
            DailyReset.render(graphics, tickCounter)
        }
    }

    private fun registerKeybinds() {
        openGuiKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("CedClient Gui", GLFW.GLFW_KEY_P, cedclientCategory)
        )
        freecamToggleKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.cedclient.freecam_toggle", GLFW.GLFW_KEY_B, cedclientCategory)
        )
        zoomKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.cedclient.zoom", GLFW.GLFW_KEY_C, cedclientCategory)
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (openGuiKey.consumeClick()) {
                client.gui.setScreen(ClickGUI())
            }
            if (freecamToggleKey.consumeClick()) {
                Freecam.toggleFreecam()
            }

            // Zoom is a hold key, not a toggle -- use isDown (the raw
            // "currently pressed" state) every tick rather than
            // consumeClick() (which only fires once per press and would
            // make Zoom.setHeld() see a single true tick instead of a
            // sustained hold).
            Zoom.setHeld(zoomKey.isDown)
        }
    }

    private fun loadConfig() {
        // Load module config safely. If something inside loadModulesOnly throws (NPE etc.),
        // catch it and log the stacktrace instead of crashing the client.
        try {
            ConfigManager.loadModulesOnly()
        } catch (t: Throwable) {
            t.printStackTrace()
            println("Warning: ConfigManager.loadModulesOnly() failed during startup. Continuing without module config load.")
        }
    }
}