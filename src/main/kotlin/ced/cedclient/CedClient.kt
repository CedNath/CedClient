package ced.cedclient

import ced.cedclient.utils.Keys
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.commands.CedClientCommand
import ced.cedclient.config.CedPaths
import ced.cedclient.config.ConfigManager
import ced.cedclient.features.ModuleManager
//#if CHEAT
import ced.cedclient.features.impl.funqol.CoralotHelper
//#endif
import ced.cedclient.features.impl.funqol.DungeonSplits
import ced.cedclient.features.impl.funqol.PositionalMessages
import ced.cedclient.features.impl.funqol.DoorESP
//#if CHEAT
import ced.cedclient.features.impl.funqol.SecretHitboxes
import ced.cedclient.features.impl.funqol.FishingHelper
import ced.cedclient.features.impl.funqol.LassoHelper
import ced.cedclient.features.impl.funqol.PangolinCatcher
import ced.cedclient.features.impl.funqol.PlayerScale
import ced.cedclient.features.impl.funqol.TinyDancerHelper
//#endif
import ced.cedclient.features.impl.funqol.F7SoundCap
//#if CHEAT
import ced.cedclient.features.impl.funqol.TropicalFishHelper
//#endif
import ced.cedclient.features.impl.funqol.CustomHypeSound
import ced.cedclient.features.impl.misc.SearchOverlay
//#if LEGIT
//$import ced.cedclient.features.impl.funqol.PlayerScale
//#endif
import ced.cedclient.features.impl.misc.AdvancedMode
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.features.impl.misc.AutoSprint
import ced.cedclient.features.impl.misc.KeepConnection
import ced.cedclient.state.UpdateChecker
import ced.cedclient.state.Sidebar
import ced.cedclient.features.impl.funqol.CritterSafari
import ced.cedclient.features.impl.funqol.VampireSlayer
import ced.cedclient.features.impl.render.VanillaTextures
import ced.cedclient.features.impl.misc.ChatPing
import ced.cedclient.features.impl.misc.ChatFeatures
import ced.cedclient.chat.PlayerChatManager
import ced.cedclient.features.impl.misc.ChatFilter
import ced.cedclient.features.impl.misc.DailyReset
import ced.cedclient.features.impl.misc.HideRecipeBook
import ced.cedclient.features.impl.misc.InventoryButtons
import ced.cedclient.features.impl.misc.ScreenshotTweaks
import ced.cedclient.features.impl.misc.WarpShortcuts
//#if CHEAT
import ced.cedclient.features.impl.render.BlockESP
//#endif
import ced.cedclient.features.impl.render.HudElement
//#if CHEAT
import ced.cedclient.features.impl.render.StarredMobHighlight
import ced.cedclient.features.impl.render.BlockESPRenderer
//#endif
import ced.cedclient.features.impl.render.ChatChannelHud
import ced.cedclient.features.impl.render.CompactScoreboard
import ced.cedclient.features.impl.render.CompactTab
//#if CHEAT
import ced.cedclient.features.impl.render.EntityESP
import ced.cedclient.features.impl.render.EntityESPHud
import ced.cedclient.features.impl.render.EntityESPLabels
import ced.cedclient.features.impl.render.EntityESPRenderer
import ced.cedclient.features.impl.render.Freecam
//#endif
import ced.cedclient.features.impl.render.CosmeticBlocks
//#if LEGIT
//$import ced.cedclient.features.impl.render.StarredMobHighlight
//#endif
import ced.cedclient.features.impl.render.FloorDrops
import ced.cedclient.features.impl.render.PestHighlighter
import ced.cedclient.features.impl.render.Fullbright
import ced.cedclient.features.impl.render.HardcodedCosmetics
import ced.cedclient.features.impl.render.ItemCooldowns
import ced.cedclient.features.impl.render.TimeHud
import ced.cedclient.features.impl.render.RenderOptimizer
import ced.cedclient.features.impl.render.CleanView
import ced.cedclient.features.impl.render.Saturation
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
//#if CHEAT
import ced.cedclient.utils.debug.MouseLookDebugger
//#endif
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier

class CedClient : ClientModInitializer {

    private lateinit var openGuiKey: KeyMapping

    private val cedclientCategory: KeyMapping.Category by lazy {
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("cedclient", "cedclient"))
    }

    override fun onInitializeClient() {
        if (System.getProperty("cedclient.mixinAudit") != null) {
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register {
                runCatching { org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit() }
                    .onFailure { println("MIXIN AUDIT FAILED: $it") }
                println("MIXIN AUDIT DONE")
                net.minecraft.client.Minecraft.getInstance().options.keyMappings.forEach { println("KEYMAP ${it.getName()} ${it.saveString()}") }
            }
        }
        println("CedClient initialized (client)")
        // First, so errors while the rest starts up are caught too.
        ced.cedclient.utils.debug.Diag.start()

        registerAlwaysOnState()
        registerModules()
        registerHudElements()
        registerKeybinds()
        registerWelcome()
        loadConfig()

        CedClientCommand.register()
        TabListCache.register()
        ced.cedclient.state.DungeonRunInfo.register()
        ced.cedclient.state.DungeonClears.register()

        // One small launch ping (username, UUID, mod + MC version) so the dev can see
        // who uses the mod. Opt out via config/cedclient-usage.properties (shareUsage=false).
        UsageStats.ping()

    }

    /**
     * State trackers that run regardless of any Module's enabled state --
     * DungeonState needs to track floor/in-dungeon status, CosmeticsSync needs
     * to keep listening, and InventoryButtonManager needs
     * its buttons loaded, no matter what's toggled on in the ClickGUI.
     */
    private fun registerAlwaysOnState() {
        DungeonState.init()
        CosmeticsSync.init()
        UpdateChecker.start()
        Sidebar.init()
        ChatFeatures.start()
        IslandState.init()
        PlayerChatManager.init()
        PartyApi.init()
        ced.cedclient.achievements.Achievements.init()

        ClientTickEvents.END_CLIENT_TICK.register {
            InventoryButtonManager.ensureLoaded()
        }
        InventoryButtonManager.ensureLoaded()

        PictureInPictureRendererRegistry.register { context ->
            NVGSpecialRenderer()
        }
        PictureInPictureRendererRegistry.register { context ->
            NVGSpecialRenderer(NVGSpecialRenderer.TooltipState::class.java)
        }

//#if CHEAT
        MouseLookDebugger.register()
//#endif
    }

    private fun registerModules() {
//#if CHEAT
        EntityESPRenderer.register()
        EntityESPHud.load()
        BlockESPRenderer.register()
//#endif

        // Registration order = order inside each ClickGUI panel, so related modules sit together.

        // Render: highlights, then world visuals, then camera
        ModuleManager.register(StarredMobHighlight)
        ModuleManager.register(FloorDrops)
        ModuleManager.register(PestHighlighter)
        ModuleManager.register(Fullbright)
        ModuleManager.register(Saturation)
        ModuleManager.register(CleanView)
        ModuleManager.register(RenderOptimizer)
        ModuleManager.register(VanillaTextures)
        ModuleManager.register(ced.cedclient.features.impl.render.CustomTooltips)
        ModuleManager.register(ced.cedclient.features.impl.render.ScrollableTooltips)
        ModuleManager.register(Zoom)
        ModuleManager.register(ced.cedclient.features.impl.render.CrosshairTweaks)
        ModuleManager.register(ced.cedclient.features.impl.render.Hiders)
        ModuleManager.register(ced.cedclient.features.impl.render.HubMap)
        ModuleManager.register(ced.cedclient.features.impl.misc.ModHider)
        ModuleManager.register(ced.cedclient.features.impl.render.CustomSky)
        ModuleManager.register(ced.cedclient.features.impl.misc.TooltipDates)
        ModuleManager.register(ced.cedclient.features.impl.render.OldStars)

        // HUD: vanilla UI tweaks, then info overlays
        ModuleManager.register(CompactScoreboard)
        // After Compact Scoreboard: its HUD wrapper has to be the outer one to hide that panel too.
        ModuleManager.register(ced.cedclient.features.impl.misc.ScoreboardToggle)
        ModuleManager.register(ced.cedclient.features.impl.misc.TabListToggle)
        ModuleManager.register(ced.cedclient.features.impl.render.Perspective)
        ModuleManager.register(ced.cedclient.features.impl.misc.HotbarKeybinds)
        ModuleManager.register(ced.cedclient.features.impl.misc.CopyItemData)
        ModuleManager.register(ced.cedclient.features.impl.misc.CopyEntityData)
        ModuleManager.register(ced.cedclient.features.impl.misc.Dumpers)
        ModuleManager.register(ced.cedclient.features.impl.misc.StatBars)
        ModuleManager.register(ced.cedclient.features.impl.misc.SkillDisplay)
        ModuleManager.register(ced.cedclient.features.impl.misc.ChatTabs)
        ModuleManager.register(ced.cedclient.features.impl.misc.SmoothChat)
        ModuleManager.register(ced.cedclient.features.impl.misc.StorageOverlay)
        ModuleManager.register(ced.cedclient.features.impl.render.ChinaHat)
        ModuleManager.register(ced.cedclient.features.impl.render.ADHD)
        ModuleManager.register(ced.cedclient.features.impl.misc.ScreenshotPreview)
        ModuleManager.register(ced.cedclient.features.impl.misc.FreezeCapture)
        ModuleManager.register(CompactTab)
        ModuleManager.register(ChatChannelHud)
        ModuleManager.register(ced.cedclient.features.impl.render.ActionBarHud)
        ModuleManager.register(TimeHud)
        ModuleManager.register(ced.cedclient.features.impl.render.EventCountdown)
        ModuleManager.register(ced.cedclient.features.impl.render.ArmorHud)
        ModuleManager.register(ItemCooldowns)
        ModuleManager.register(DailyReset)

        // Cosmetic: world, then player
        ModuleManager.register(CosmeticBlocks)
        ModuleManager.register(CustomNametag)
        ModuleManager.register(HardcodedCosmetics)
        ModuleManager.register(PlayerScale)
        ModuleManager.register(ced.cedclient.features.impl.render.HideArmor)
        ModuleManager.register(ced.cedclient.features.impl.render.FallingPetals)

//#if CHEAT
        // Skyblock: dungeons, sounds, menus
//#else
//$        // Skyblock: dungeons
//#endif
        ModuleManager.register(ced.cedclient.features.impl.funqol.DungeonMap)
        ModuleManager.register(ced.cedclient.features.impl.funqol.LeapMenu)
        ModuleManager.register(ced.cedclient.features.impl.funqol.TerminalSolver)
        ModuleManager.register(DungeonSplits)
        ModuleManager.register(PositionalMessages)
        ModuleManager.register(F7SoundCap)
        ModuleManager.register(CustomHypeSound)
        ModuleManager.register(CritterSafari)
        ModuleManager.register(ced.cedclient.features.impl.funqol.RunProfit)
        ModuleManager.register(ced.cedclient.features.impl.funqol.DeployableTimers)
        ModuleManager.register(ced.cedclient.features.impl.funqol.RagnarockTimer)
        ModuleManager.register(ced.cedclient.features.impl.funqol.PingFpsHud)
        ModuleManager.register(ced.cedclient.features.impl.funqol.ClassColors)
        ModuleManager.register(ced.cedclient.features.impl.funqol.F7Titles)
        ModuleManager.register(ced.cedclient.features.impl.funqol.MaskTimers)
        ModuleManager.register(ced.cedclient.features.impl.funqol.BlessingDisplay)
        ModuleManager.register(ced.cedclient.features.impl.funqol.ScoreCalculator)
        ModuleManager.register(ced.cedclient.features.impl.misc.AchievementsModule)
        ModuleManager.register(ced.cedclient.features.impl.funqol.TickTimers)
        ModuleManager.register(ced.cedclient.features.impl.funqol.WarpCooldown)
        ModuleManager.register(ced.cedclient.features.impl.funqol.RoomAlerts)
        ModuleManager.register(ced.cedclient.features.impl.render.LavaToWater)
        ModuleManager.register(ced.cedclient.features.impl.misc.ArrowFix)
        ModuleManager.register(ced.cedclient.features.impl.funqol.EnchantColors)
        ModuleManager.register(ced.cedclient.features.impl.funqol.ProtectItem)
        ModuleManager.register(ced.cedclient.features.impl.funqol.SlotBinding)
        ModuleManager.register(ced.cedclient.features.impl.funqol.LoadoutKeybinds)
        ModuleManager.register(ced.cedclient.features.impl.funqol.WardrobeKeybinds)
        ModuleManager.register(ced.cedclient.features.impl.funqol.SbKickTimer)
        ModuleManager.register(ced.cedclient.features.impl.funqol.ItemInfoTooltip)
        ModuleManager.register(ced.cedclient.features.impl.funqol.TerminalSimulator)
        ModuleManager.register(ced.cedclient.features.impl.render.Waypoints)
        ModuleManager.register(ced.cedclient.features.impl.render.ItemRarity)
        ModuleManager.register(ced.cedclient.features.impl.funqol.PetDisplay)
        ModuleManager.register(VampireSlayer)
        ModuleManager.register(ced.cedclient.features.impl.funqol.RevenantSlayer)
        ModuleManager.register(ced.cedclient.features.impl.funqol.TarantulaSlayer)
        ModuleManager.register(ced.cedclient.features.impl.funqol.SvenSlayer)
        ModuleManager.register(ced.cedclient.features.impl.funqol.VoidgloomSlayer)
        ModuleManager.register(ced.cedclient.features.impl.funqol.InfernoSlayer)
        ModuleManager.register(ced.cedclient.features.impl.funqol.RiftstalkerSlayer)
        ModuleManager.register(ced.cedclient.features.impl.funqol.SunGecko)
        ModuleManager.register(ced.cedclient.features.impl.funqol.RoomTimes)
        ModuleManager.register(ced.cedclient.features.impl.funqol.LeapCounter)
        ModuleManager.register(ced.cedclient.features.impl.funqol.SecretHighlight)
        ModuleManager.register(ced.cedclient.features.impl.funqol.LeftClickSecrets)
        ModuleManager.register(ced.cedclient.features.impl.funqol.SecretHud)
        ModuleManager.register(ced.cedclient.features.impl.funqol.SimonSaysLines)
        ModuleManager.register(ced.cedclient.features.impl.funqol.DungeonBreakerSound)
        ModuleManager.register(ced.cedclient.features.impl.funqol.ExplosiveShotDamage)
        ModuleManager.register(SearchOverlay)
        ModuleManager.register(ced.cedclient.features.impl.misc.AuctionPriceInput)
        ModuleManager.register(ced.cedclient.features.impl.misc.ItemValue)

        // Misc: movement / navigation, inventory, chat, screenshots, then client settings
        ModuleManager.register(AutoSprint)
        ModuleManager.register(WarpShortcuts)
        ModuleManager.register(InventoryButtons)
        ModuleManager.register(HideRecipeBook)
        ModuleManager.register(ChatFilter)
        ModuleManager.register(ChatFeatures)
        ModuleManager.register(ced.cedclient.features.impl.misc.ChatPing)
        ModuleManager.register(ced.cedclient.features.impl.misc.PartyCommands)
        ModuleManager.register(ced.cedclient.features.impl.misc.AutoRequeue)
        ModuleManager.register(ScreenshotTweaks)
        ModuleManager.register(KeepConnection)
        ModuleManager.register(ced.cedclient.features.impl.misc.TexturePackFix)
        ModuleManager.register(ced.cedclient.features.impl.misc.Spotify)
        ModuleManager.register(ced.cedclient.features.impl.misc.MuteSounds)
        ModuleManager.register(ced.cedclient.features.impl.misc.ConfigShare)
        ModuleManager.register(ced.cedclient.features.impl.misc.DebugPanel)
        ModuleManager.register(Theme)
        ModuleManager.register(AdvancedMode)
        ModuleManager.register(DoorESP)
        ModuleManager.register(ced.cedclient.features.impl.misc.ChatPrefixSettings)

//#if CHEAT
        // Cheats: ESP, camera, then the auto-catch / auto-play helpers (left out of the Legit build)
        if (ced.cedclient.utils.DevAccess.isFriend) ModuleManager.register(EntityESP)
        ModuleManager.register(BlockESP)
        ModuleManager.register(Freecam)
        ModuleManager.register(SecretHitboxes)
        ModuleManager.register(LassoHelper)
        // Friends-only (DevAccess): not registered at all for anyone else.
        if (ced.cedclient.utils.DevAccess.isFriend) {
            ModuleManager.register(PangolinCatcher)
            ModuleManager.register(CoralotHelper)
            ModuleManager.register(TropicalFishHelper)
            ModuleManager.register(ced.cedclient.features.impl.funqol.SlayerCheats)
            ModuleManager.register(ced.cedclient.features.impl.funqol.FarmHelper)
            ModuleManager.register(ced.cedclient.features.impl.funqol.DojoHelper)
            ModuleManager.register(ced.cedclient.features.impl.render.Box3D)
            ModuleManager.register(ced.cedclient.features.impl.render.WitherESP)
            ModuleManager.register(ced.cedclient.features.impl.render.TerminalESP)
            ModuleManager.register(ced.cedclient.features.impl.render.TeammateESP)
            ModuleManager.register(ced.cedclient.features.impl.render.F4Features)
            ModuleManager.register(ced.cedclient.features.impl.funqol.EtherwarpOverlay)
            ModuleManager.register(ced.cedclient.features.impl.funqol.ArchitectDraft)
            ModuleManager.register(ced.cedclient.features.impl.funqol.NecronDrop)
            ModuleManager.register(ced.cedclient.features.impl.funqol.GateHighlight)
            ModuleManager.register(ced.cedclient.features.impl.funqol.TrevorHelper)
            ModuleManager.register(ced.cedclient.features.impl.funqol.GyroHelper)
            ModuleManager.register(ced.cedclient.features.impl.funqol.CameraTweaks)
            ModuleManager.register(ced.cedclient.features.impl.funqol.NoItemPlace)
            ModuleManager.register(ced.cedclient.features.impl.funqol.ArrowAlign)
            ModuleManager.register(ced.cedclient.features.impl.funqol.I4Helper)
            ModuleManager.register(ced.cedclient.features.impl.funqol.M7Relics)
            ModuleManager.register(ced.cedclient.features.impl.funqol.LividSolver)
            ModuleManager.register(ced.cedclient.features.impl.funqol.BloodCamp)
            ModuleManager.register(ced.cedclient.features.impl.funqol.IHateDiorite)
            ModuleManager.register(ced.cedclient.features.impl.funqol.DoorFix)
            ModuleManager.register(ced.cedclient.features.impl.funqol.IHateDoors)
            ModuleManager.register(ced.cedclient.features.impl.funqol.SpringBoots)
            ModuleManager.register(ced.cedclient.features.impl.funqol.SnappyTappy)
            ModuleManager.register(ced.cedclient.features.impl.funqol.WitherDragons)
            ModuleManager.register(ced.cedclient.features.impl.funqol.BlazeSolver)
            ModuleManager.register(ced.cedclient.features.impl.funqol.PuzzleSolvers)
            ModuleManager.register(ced.cedclient.features.impl.funqol.BeachBallHelper)
        }
        ModuleManager.register(FishingHelper)
        ModuleManager.register(TinyDancerHelper)
        ModuleManager.register(ced.cedclient.features.impl.funqol.AutoExperiments)
        ModuleManager.register(ced.cedclient.features.impl.funqol.BeaconSolver)
        ModuleManager.register(ced.cedclient.features.impl.funqol.AutoFusion)
        ModuleManager.register(ced.cedclient.features.impl.funqol.AutoTerminals)
        ModuleManager.register(ced.cedclient.features.impl.funqol.AnvilHelper)
        ModuleManager.register(ced.cedclient.features.impl.funqol.AutoClicker)
        ModuleManager.register(ced.cedclient.features.impl.funqol.ArrowAlignTriggerbot)
        ModuleManager.register(ced.cedclient.features.impl.funqol.LightsDeviceTriggerbot)

//#endif
        // Defensive: touch ModuleManager.modules to force initialization (if it's lazily initialized)
        try {
            ModuleManager.modules
        } catch (t: Throwable) {
            // ignore; we'll still attempt to load config below and catch any errors
        }
    }

    /** HudElementRegistry.addLast, except that over an inventory / container screen only Spotify (which draws itself in front) is shown. */
    private fun hudLast(id: Identifier, element: net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement) =
        HudElementRegistry.addLast(id) { graphics, tickCounter ->
            val hidden = id.path != "spotify" && id.path != "hud_keep_on_screen" &&
                net.minecraft.client.Minecraft.getInstance().gui.screen() is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>
            if (!hidden) element.extractRenderState(graphics, tickCounter)
        }

    private fun registerHudElements() {
        // First, so every panel is back on-screen before it draws.
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "hud_keep_on_screen")
        ) { graphics, _ ->
            HudElement.followScreenSize(graphics.guiWidth(), graphics.guiHeight())
            HudElement.all.forEach { it.keepOnScreen(graphics.guiWidth(), graphics.guiHeight()) }
        }
//#if CHEAT
        // Friends-only like the module itself (touching FarmHelper would set it up for everyone).
        if (ced.cedclient.utils.DevAccess.isFriend) hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "farmhelper")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.FarmHelper.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "blood_camp")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.BloodCamp.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "spring_boots")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.SpringBoots.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "wither_dragons")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.WitherDragons.render(graphics, tickCounter)
        }
//#endif
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "dungeon_map")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.DungeonMap.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "spotify")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.misc.Spotify.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "secret_hud")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.SecretHud.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "room_times")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.RoomTimes.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "leap_counter")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.LeapCounter.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "dungeon_splits")
        ) { graphics, tickCounter ->
            DungeonSplits.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "positional_messages")
        ) { graphics, tickCounter ->
            PositionalMessages.render(graphics, tickCounter)
        }
//#if CHEAT
        // First, so HUD panels draw over the floating entity labels.
//#endif
        hudLast(
//#if CHEAT
            Identifier.fromNamespaceAndPath("cedclient", "entity_esp_labels")
        ) { graphics, tickCounter ->
            EntityESPLabels.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "entity_esp_hud")
        ) { graphics, tickCounter ->
            EntityESPHud.render(graphics, tickCounter)
        }
        hudLast(
//#endif
            Identifier.fromNamespaceAndPath("cedclient", "time_hud")
        ) { graphics, tickCounter ->
            TimeHud.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "chat_channel_hud")
        ) { graphics, tickCounter ->
            ChatChannelHud.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "action_bar_hud")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.render.ActionBarHud.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "chat_alerts_hud")
        ) { graphics, tickCounter ->
            ChatPing.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "critter_progress")
        ) { graphics, tickCounter ->
            CritterSafari.ProgressHud.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "critter_missing")
        ) { graphics, tickCounter ->
            CritterSafari.MissingHud.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "critter_trades")
        ) { graphics, tickCounter ->
            CritterSafari.TradesHud.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "run_profit")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.RunProfit.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "deployable_timers")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.DeployableTimers.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "ragnarock_timer")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.RagnarockTimer.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "ping_fps")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.PingFpsHud.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "mask_timers")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.MaskTimers.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "blessing_display")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.BlessingDisplay.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "tick_timers")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.TickTimers.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "warp_cooldown")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.WarpCooldown.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "sb_kick_timer")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.SbKickTimer.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "pet_display")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.PetDisplay.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "event_countdown")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.render.EventCountdown.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "armor_hud")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.render.ArmorHud.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "slayer")
        ) { graphics, tickCounter ->
            VampireSlayer.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "sun_gecko")
        ) { graphics, tickCounter ->
            ced.cedclient.features.impl.funqol.SunGecko.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "item_cooldowns")
        ) { graphics, tickCounter ->
            ItemCooldowns.render(graphics, tickCounter)
        }
        hudLast(
            Identifier.fromNamespaceAndPath("cedclient", "daily_reset")
        ) { graphics, tickCounter ->
            DailyReset.render(graphics, tickCounter)
        }
    }

    /** One chat line the first time a world / server is ever joined (a marker file remembers it across launches). */
    private fun registerWelcome() {
        val welcomeDelayTicks = 100 // 5 s
        val marker = CedPaths.file("welcomed")
        var welcomed = marker.exists()
        var ticksLeft = -1
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            if (!welcomed) ticksLeft = welcomeDelayTicks
        }
        // Delayed: Hypixel moves you into a lobby a few seconds after connecting, and chat sent before that is lost.
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (ticksLeft < 0 || --ticksLeft > 0) return@register
            val player = client.player ?: return@register
            ticksLeft = -1
            welcomed = true
            runCatching { marker.createNewFile() }
            val version = FabricLoader.getInstance().getModContainer("cedclient")
                .map { it.metadata.version.friendlyString.substringBefore("-") }.orElse("")
            player.sendSystemMessage(ChatPrefix.msg(
                "§fWelcome to §lCedClient§f $version! Press §e${openGuiKey.translatedKeyMessage.string}§f or type §e/cc§f to open the menu."
            ))
        }
    }

    private fun registerKeybinds() {
        openGuiKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("CedClient Gui", Keys.P, cedclientCategory)
        )
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (openGuiKey.consumeClick()) {
                client.gui.setScreen(ClickGUI())
            }

//#if CHEAT
            // Freecam / Zoom keys are KeybindSettings on their modules (rebindable in the ClickGUI), polled here.
//#else
//$            // The Zoom key is a KeybindSetting on its module (rebindable in the ClickGUI), polled here.
//#endif
            // Only while no screen is open, so typing in chat doesn't trigger them.
            fun down(code: Int) = code > 0 && client.gui.screen() == null &&
                    Keys.isDown(code)

//#if CHEAT
            val freecamDown = down(Freecam.toggleKey.value)
            if (freecamDown && !freecamKeyWasDown) Freecam.toggleFreecam()
            freecamKeyWasDown = freecamDown

//#endif
            // Zoom is a hold key, not a toggle -- pass the held state every tick.
            Zoom.setHeld(down(Zoom.zoomKey.value))
        }
    }

//#if CHEAT
    private var freecamKeyWasDown = false

//#endif
    private fun loadConfig() {
        // Load module config safely. If something inside loadModulesOnly throws (NPE etc.),
        // catch it and log the stacktrace instead of crashing the client.
        try {
            ced.cedclient.features.impl.render.HudCard.attachBackgroundSettings()
            ConfigManager.loadModulesOnly()
            // Settings changed outside the ClickGUI (commands, HUD editors...) are otherwise only written the next
            // time a module is toggled or the GUI closes. Only after a good load, so a broken config file never
            // gets overwritten with defaults on the way out.
            ClientLifecycleEvents.CLIENT_STOPPING.register { ConfigManager.saveModulesOnly() }
        } catch (t: Throwable) {
            t.printStackTrace()
            println("Warning: ConfigManager.loadModulesOnly() failed during startup. Continuing without module config load.")
        }
    }
}
