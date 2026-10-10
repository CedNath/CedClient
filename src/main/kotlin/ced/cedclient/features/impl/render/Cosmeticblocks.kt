package ced.cedclient.features.impl.render

import ced.cedclient.config.ConfigManager
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.features.settings.Saving
import ced.cedclient.render.cosmetic.CosmeticBackup
import ced.cedclient.render.cosmetic.CosmeticChat
import ced.cedclient.render.cosmetic.CosmeticCodec
import ced.cedclient.render.cosmetic.CosmeticConfig
import ced.cedclient.render.cosmetic.CosmeticFavorites
import ced.cedclient.render.cosmetic.CosmeticProps
import ced.cedclient.render.cosmetic.CosmeticRemap
import ced.cedclient.render.cosmetic.CosmeticRooms
import ced.cedclient.render.cosmetic.CosmeticStore
import ced.cedclient.render.cosmetic.CosmeticTools
import ced.cedclient.ui.clickgui.BlockPickerPopup
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.utils.Color
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW

/**
 * Cosmetic Blocks -- client-only block replacements (the cb mod's feature set), organised per SkyBlock island and
 * preset, plus room-relative edits in dungeons.
 *
 * Edit mode (no screen open):   R-click place/replace   L-click remove   M-click pick
 *   + Place Mode   R-click adds a phantom block on the face (also onto other phantom blocks)
 *   + Fill Mode    R-click flood-fills the connected same-type blocks
 *   + Shape        fence / wall / pane: draw the brush's texture on that model
 * World Edit mode:  L-click = Pos1, R-click = Pos2, then /cc cosmetic set|replace|remove
 * Ruler:            R-click start, R-click end (places the line), L-click cancel, scroll = reach into the air
 *
 * Everything is also reachable through /cc cosmetic ...  (see /cc cosmetic help)
 */
object CosmeticBlocks : Module(
    "Cosmetic Blocks",
    Category.Cosmetic,
    "Client-side only: change how blocks look. Presets per SkyBlock island, room-relative in dungeons. /cc cosmetic help"
) {
    // ---- modes ----
    val placeMode = BooleanSetting("Place Mode", false, "Right-click places a phantom block on the face (works on air) instead of replacing.")
    private val fillMode = BooleanSetting("Fill Mode", false, "Right-click flood-fills every connected block of the same type.")
    private val fillLimit = NumberSetting("Fill Limit", 2000.0, 50.0, 20000.0, 50.0, "Max blocks one flood fill / fill can change.")
    private val shapeSetting = DropdownSetting("Brush Shape", listOf("None", "Fence", "Wall", "Pane"), "None",
        "Draw the brush's texture on a fence / wall / pane model.")
    private val weFillOnClick = BooleanSetting("WE Fill on Click", false, "World Edit: fill the selection as soon as Pos2 is set.")

    // ---- rendering ----
    private val keepBlockSize = BooleanSetting("Keep Block Size", true, "Replacing stairs / slabs / fences keeps their shape.")
    private val lavaAsWater = BooleanSetting("Lava as Water", false, "Lava renders as water.")
    private val customWater = BooleanSetting("Custom Water Color", false, "Tint all water with the colour below.")
    private val waterColor = ColorSetting("Water Color", Color(0x3F, 0x76, 0xE4), "Water tint.")
    private val onlyInDungeons = BooleanSetting("Only in Dungeons", false, "Only show cosmetic blocks in dungeon runs.")
    private val roomScanning = BooleanSetting("Room Scanning", true, "In dungeons, save edits per room so they show up every run.")
    private val remaps = BooleanSetting("Block Remaps", true, "Render every block of a type as another (/cc cosmetic remap).")
    private val remapKeepShape = BooleanSetting("Remap Keep Shape", false, "Remapped stairs / slabs keep their shape.")

    // ---- overlay ----
    val showReplaced = BooleanSetting("Show Replaced Blocks", false, "Wireframe over every cosmetic block nearby.")
    val overlayRange = NumberSetting("Overlay Range", 32.0, 4.0, 128.0, 1.0, "How far the wireframe reaches.")
    val overlayColor = ColorSetting("Overlay Color", Color(0x55, 0xFF, 0xFF), "Wireframe colour.")

    // ---- keybinds ----
    private val editKey = KeybindSetting("Edit Mode Key", description = "Toggles edit mode.")
    private val placeKey = KeybindSetting("Place Mode Key", description = "Toggles Place Mode.")
    private val worldEditKey = KeybindSetting("World Edit Key", description = "Toggles World Edit (region) mode.")
    private val rulerKey = KeybindSetting("Ruler Key", description = "Toggles the ruler.")
    private val selectorKey = KeybindSetting("Block Selector Key", description = "Opens the block picker.")
    private val favoriteKey = KeybindSetting("Cycle Favorite Key", description = "Switches the brush to the next favourite block.")
    private val presetMenuKey = KeybindSetting("Preset Switcher Key", description = "Opens the quick preset switcher.")
    private val nextPresetKey = KeybindSetting("Next Preset Key", description = "Switches to the next preset on this island.")

    private val selectAction = ActionSetting("Select Block") { openSelectPopup() }
    private val editAction = ActionSetting("Toggle Edit Mode") { toggleEdit() }
    private val undoAction = ActionSetting("Undo") { CosmeticChat.say(undoText(CosmeticStore.undo())) }
    private val presetsAction = ActionSetting("Preset Switcher") { pendingPresetScreen = true }
    private val exportAction = ActionSetting("Export Config", "Saves every preset, room, remap, favourite and setting to config/cedclient/cosmetic_exports.") {
        CosmeticChat.say(exportConfigToFile(null))
    }
    private val copyAction = ActionSetting("Copy Config", "Copies the whole Cosmetic Blocks config to the clipboard.") {
        CosmeticChat.say(copyConfig())
    }
    private val pasteAction = ActionSetting("Import Config", "Merges a config from the clipboard (a backup of the current one is saved first).") {
        CosmeticChat.say(pasteConfig(replace = false))
    }
    private val settingsAction = ActionSetting("Open Settings", "All Cosmetic Blocks settings in one window.") {
        (mc.screen as? ClickGUI)?.openPopup(settingsPopup())
    }

    var editMode = false
        private set
    var worldEditMode = false
        private set
    var selectedBlock = "minecraft:stone"
        private set

    private val currentShape: String?
        get() = shapeSetting.value.lowercase().takeIf { it != "none" }

    private var pendingSelect = false
    /** Opened on the next tick, so it is not closed again by the chat / ClickGUI screen it was requested from. */
    private var pendingPresetScreen = false
    private var pendingSettings = false
    private var leftWas = false
    private var rightWas = false
    private var middleWas = false
    private val keyWas = HashMap<KeybindSetting, Boolean>()

    // render settings snapshot, to re-mesh when one changes
    private var lastRenderKey = ""
    private var renderChangeDelay = 0

    private val mc get() = Minecraft.getInstance()

    init {
        addSettings(
            placeMode, fillMode, fillLimit, shapeSetting, weFillOnClick,
            keepBlockSize, lavaAsWater, customWater, waterColor, onlyInDungeons, roomScanning, remaps, remapKeepShape,
            showReplaced, overlayRange, overlayColor,
            editKey, placeKey, worldEditKey, rulerKey, selectorKey, favoriteKey, presetMenuKey, nextPresetKey,
            selectAction, editAction, undoAction, presetsAction, exportAction, copyAction, pasteAction,
            settingsAction
        )
        // Everything but these two lives in the settings popup -- the panel list was far too long.
        for (s in settings.values) if (s is RenderableSetting<*> && s !== settingsAction && s !== presetsAction) s.inPopup = true
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // Brigadier merges same-named roots, so this adds "cosmetic" under the existing /cc and /cedclient.
            dispatcher.register(commandTree("cedclient"))
            dispatcher.register(commandTree("cc"))
        }
        CosmeticBlocksRenderer.register()
    }

    override fun onEnable() {
        syncConfig()
        CosmeticRemap.load()
        CosmeticStore.setEnabled(true)
        // Also called while the config loads at startup, before there is a level renderer.
        if (mc.level != null) mc.levelRenderer.allChanged()
    }

    override fun onDisable() {
        editMode = false
        worldEditMode = false
        if (CosmeticTools.rulerActive) CosmeticTools.toggleRuler()
        CosmeticStore.setEnabled(false)
        CosmeticConfig.enabled = false
        if (mc.level != null) mc.levelRenderer.allChanged()
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    private fun syncConfig() {
        CosmeticConfig.enabled = isEnabled
        CosmeticConfig.keepSize = keepBlockSize.value
        CosmeticConfig.lavaAsWater = lavaAsWater.value
        CosmeticConfig.customWater = customWater.value
        CosmeticConfig.waterRgb = waterColor.value.rgba and 0xFFFFFF
        CosmeticConfig.onlyInDungeons = onlyInDungeons.value
        CosmeticConfig.roomScanning = roomScanning.value
        CosmeticConfig.remapsEnabled = remaps.value
        CosmeticConfig.remapKeepShape = remapKeepShape.value
        CosmeticConfig.fillLimit = fillLimit.value.toInt()
    }

    private fun renderKey(): String =
        "${keepBlockSize.value}|${lavaAsWater.value}|${customWater.value}|${waterColor.value.rgba}|" +
                "${onlyInDungeons.value}|${remaps.value}|${remapKeepShape.value}|${CosmeticConfig.gateOpen()}"

    private fun tick() {
        syncConfig()
        // The saved enabled state can be restored without onEnable() firing -- keep the store in step either way.
        if (isEnabled != CosmeticStore.enabled) {
            if (isEnabled) CosmeticRemap.load()
            CosmeticStore.setEnabled(isEnabled)
        }
        CosmeticStore.tick()
        CosmeticRooms.tick()

        // A setting that changes how things look -> re-mesh once it has settled (colour drags change it every frame).
        val key = renderKey()
        if (key != lastRenderKey) {
            val first = lastRenderKey.isEmpty()
            lastRenderKey = key
            if (!first) renderChangeDelay = 8
        }
        if (renderChangeDelay > 0 && --renderChangeDelay == 0 && mc.level != null) {
            CosmeticStore.clearKeepSizeCaches()
            mc.levelRenderer.allChanged()
        }

        if (pendingSelect) {
            pendingSelect = false
            mc.setScreen(ClickGUI())
            showSelectPopup()
        }
        if (pendingSettings) {
            pendingSettings = false
            val gui = ClickGUI()
            mc.setScreen(gui)
            gui.openPopup(settingsPopup())
        }
        if (pendingPresetScreen) {
            pendingPresetScreen = false
            mc.setScreen(CosmeticPresetScreen())
        }
        if (!isEnabled) return

        pollKeys()
        CosmeticTools.tickRuler()
        if (mc.screen != null || mc.level == null) {
            leftWas = false; rightWas = false; middleWas = false
            return
        }
        tickMouse()
    }

    private fun pressedOnce(setting: KeybindSetting, window: Long): Boolean {
        val code = setting.value
        val down = code > 0 && mc.screen == null && GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS
        val was = keyWas[setting] ?: false
        keyWas[setting] = down
        return down && !was
    }

    private fun pollKeys() {
        val window = GLFW.glfwGetCurrentContext()
        if (window == 0L) return
        if (pressedOnce(editKey, window)) toggleEdit()
        if (pressedOnce(placeKey, window)) {
            placeMode.value = !placeMode.value
            CosmeticChat.say("Place mode ${onOff(placeMode.value)}")
            ConfigManager.saveModulesOnly()
        }
        if (pressedOnce(worldEditKey, window)) toggleWorldEdit()
        if (pressedOnce(rulerKey, window)) CosmeticTools.toggleRuler()
        if (pressedOnce(selectorKey, window)) openSelectPopup()
        if (pressedOnce(favoriteKey, window)) {
            val next = CosmeticFavorites.next(selectedBlock)
            if (next == null) CosmeticChat.say("No favourites yet (/cc cosmetic fav add)") else selectBrush(next)
        }
        if (pressedOnce(presetMenuKey, window)) mc.setScreen(CosmeticPresetScreen())
        if (pressedOnce(nextPresetKey, window)) {
            val next = CosmeticStore.cyclePreset()
            CosmeticChat.say(if (next == null) "Only one preset on ${CosmeticStore.islandKey}" else "Now using '$next' on ${CosmeticStore.islandKey}")
        }
    }

    private fun onOff(b: Boolean) = if (b) "§aON§f" else "§cOFF§f"

    private fun toggleEdit() {
        if (!isEnabled) {
            CosmeticChat.say("Enable the Cosmetic Blocks module first")
            return
        }
        editMode = !editMode
        if (editMode) worldEditMode = false
        CosmeticStore.flush()
        CosmeticChat.say("Edit mode ${onOff(editMode)} - brush: ${brushLabel()}")
        if (editMode) CosmeticChat.say("§7R-click: place, L-click: remove, M-click: pick")
    }

    private fun toggleWorldEdit() {
        if (!isEnabled) {
            CosmeticChat.say("Enable the Cosmetic Blocks module first")
            return
        }
        worldEditMode = !worldEditMode
        if (worldEditMode) editMode = false
        CosmeticChat.say("World edit mode ${onOff(worldEditMode)}")
        if (worldEditMode) CosmeticChat.say("§7L-click: Pos1, R-click: Pos2, then /cc cosmetic set|replace|remove")
    }

    private fun brushLabel(): String =
        selectedBlock.removePrefix("minecraft:") + (currentShape?.let { " §7(as $it)" } ?: "")

    private fun tickMouse() {
        val window = GLFW.glfwGetCurrentContext()
        val left = GLFW.glfwGetMouseButton(window, 0) == 1
        val right = GLFW.glfwGetMouseButton(window, 1) == 1
        val middle = GLFW.glfwGetMouseButton(window, 2) == 1
        try {
            val hit = (mc.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }
            when {
                CosmeticTools.rulerActive -> {
                    if (right && !rightWas) CosmeticTools.rulerClick(brushRaw(), placeMode.value)
                    if (left && !leftWas) CosmeticTools.cancelRuler()
                }
                worldEditMode -> {
                    if (left && !leftWas && hit != null) CosmeticTools.setPos1(hit.blockPos)
                    if (right && !rightWas && hit != null) {
                        CosmeticTools.setPos2(hit.blockPos)
                        if (weFillOnClick.value && CosmeticTools.pos1 != null) CosmeticChat.say(CosmeticTools.fill(selectedBlock, currentShape))
                    }
                }
                editMode -> {
                    if (right && !rightWas) onRight(hit)
                    if (left && !leftWas) onLeft(hit)
                    if (middle && !middleWas) onMiddle(hit)
                }
            }
        } catch (t: Throwable) {
            // Report instead of crashing the game, and leave the modes so it can't spam.
            t.printStackTrace()
            editMode = false
            worldEditMode = false
            val top = t.stackTrace.take(3).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            CosmeticChat.say("§cError: ${t.javaClass.simpleName}: ${t.message} @ $top")
        }
        leftWas = left
        rightWas = right
        middleWas = middle
    }

    /** The brush as a raw entry (block + optional shape, no phantom flag). */
    private fun brushRaw(): String = CosmeticCodec.buildRaw(selectedBlock, false, currentShape)

    private fun place(target: BlockPos, hit: BlockHitResult, phantom: Boolean) {
        if (selectedBlock.contains('[') || currentShape != null) {
            CosmeticStore.set(target, brushRaw(), phantom)
        } else {
            val state = CosmeticCodec.placementState(selectedBlock, hit)
            if (state != null) CosmeticStore.setState(target, state, phantom) else CosmeticStore.set(target, selectedBlock, phantom)
        }
        mc.gui.setOverlayMessage(Component.literal("§b${target.x},${target.y},${target.z} §f-> ${brushLabel()}"), false)
    }

    private fun onRight(hit: BlockHitResult?) {
        val level = mc.level ?: return
        if (hit != null) {
            val pos = hit.blockPos
            when {
                placeMode.value -> {
                    val full = level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)
                    val target = if (full) pos.relative(hit.direction) else pos
                    val free = level.getBlockState(target).isAir || (!full && target == pos)
                    if (free && !CosmeticStore.has(target)) place(target, hit, true)
                }
                fillMode.value -> CosmeticChat.say(CosmeticTools.floodFill(pos, selectedBlock, currentShape))
                else -> place(pos, hit, false)
            }
            return
        }
        // Looking at nothing solid: in Place Mode you can still build onto phantom blocks.
        if (!placeMode.value) return
        val (phantomPos, face) = CosmeticTools.phantomHit() ?: return
        val target = phantomPos.relative(face)
        if (!level.getBlockState(target).isAir || CosmeticStore.has(target)) return
        val synthetic = BlockHitResult(Vec3(phantomPos.x + 0.5, phantomPos.y + 0.5, phantomPos.z + 0.5), face, phantomPos, false)
        place(target, synthetic, true)
    }

    private fun onLeft(hit: BlockHitResult?) {
        var removedAt: BlockPos? = null
        if (hit != null) {
            val pos = hit.blockPos
            removedAt = when {
                CosmeticStore.remove(pos) -> pos
                else -> pos.relative(hit.direction).takeIf { CosmeticStore.remove(it) }
            }
        }
        if (removedAt == null) {
            CosmeticTools.phantomHit()?.first?.let { if (CosmeticStore.remove(it)) removedAt = it }
        }
        removedAt?.let { mc.gui.setOverlayMessage(Component.literal("§cRemoved §f${it.x},${it.y},${it.z}"), false) }
    }

    private fun onMiddle(hit: BlockHitResult?) {
        tryPick(hit)
    }

    /** Picks the brush from what you look at (the cosmetic block if there is one). Also used by the pick-block mixin. */
    private fun tryPick(hit: BlockHitResult?): Boolean {
        val level = mc.level ?: return false
        if (hit != null) {
            val key = hit.blockPos.asLong()
            val state = CosmeticStore.retexture[key] ?: CosmeticStore.cache[key] ?: level.getBlockState(hit.blockPos)
            if (!state.isAir) {
                selectBrush(CosmeticCodec.serialize(state))
                return true
            }
        }
        if (placeMode.value) {
            val p = CosmeticTools.phantomHit()?.first ?: return false
            val state = CosmeticStore.retexture[p.asLong()] ?: CosmeticStore.cache[p.asLong()] ?: return false
            selectBrush(CosmeticCodec.serialize(state))
            return true
        }
        return false
    }

    /** Called from MinecraftPickBlockMixin: in edit mode the middle click picks the cosmetic brush instead. */
    @JvmStatic
    fun interceptPickBlock(): Boolean = isEnabled && (editMode || worldEditMode)

    private fun selectBrush(id: String) {
        selectedBlock = id
        CosmeticChat.say("Brush set to ${brushLabel()}")
    }

    // ------------------------------------------------------------------
    // Block picker
    // ------------------------------------------------------------------

    private fun openSelectPopup() {
        if (mc.screen is ClickGUI) showSelectPopup() else pendingSelect = true
    }

    private fun showSelectPopup() {
        val gui = mc.screen as? ClickGUI ?: return
        val favs = CosmeticFavorites.all().take(3)
        val buttons = favs.map { id -> id.removePrefix("minecraft:").replace('_', ' ') to { gui.closePopup(); selectBrush(id) } }
        gui.openPopup(BlockPickerPopup("Select Block", buttons) { block ->
            selectBrush(BuiltInRegistries.BLOCK.getKey(block).toString())
        })
    }

    private fun settingsPopup() = SettingsPopup(
        "Cosmetic Blocks",
        columns = listOf(
            listOf(
                SettingsPopup.Section("Editing", listOf(placeMode, fillMode, fillLimit, shapeSetting, weFillOnClick)),
                SettingsPopup.Section("Overlay", listOf(showReplaced, overlayRange, overlayColor)),
            ),
            listOf(
                SettingsPopup.Section("Rendering", listOf(
                    keepBlockSize, lavaAsWater, customWater, waterColor, onlyInDungeons, roomScanning, remaps, remapKeepShape
                )),
            ),
            listOf(
                SettingsPopup.Section("Keybinds", listOf(
                    editKey, placeKey, worldEditKey, rulerKey, selectorKey, favoriteKey, presetMenuKey, nextPresetKey
                )),
            ),
        ),
        actions = listOf(selectAction, editAction, undoAction, presetsAction, exportAction, copyAction, pasteAction),
    )

    // ------------------------------------------------------------------
    // Config import / export
    // ------------------------------------------------------------------

    private val settingsGson = Gson()

    /** This module's saved settings, minus keybinds (those are personal, not part of a shared config). */
    private fun settingsJson(): JsonObject {
        val obj = JsonObject()
        for (s in settings.values) if (s is Saving && s !is KeybindSetting) obj.add(s.name, s.write(settingsGson))
        return obj
    }

    private fun applySettings(obj: JsonObject) {
        for (s in settings.values) {
            if (s !is Saving || s is KeybindSetting) continue
            val raw = obj.get(s.name) ?: continue
            try { s.read(raw, settingsGson) } catch (t: Throwable) { t.printStackTrace() }
        }
        ConfigManager.saveModulesOnly()
    }

    fun exportConfigToFile(name: String?): String = try {
        val file = CosmeticBackup.exportToFile(name ?: "export-${System.currentTimeMillis() / 1000}", settingsJson())
        "Exported config to cosmetic_exports/${file.name}"
    } catch (e: Exception) {
        "§cExport failed: ${e.message}"
    }

    fun copyConfig(): String {
        mc.keyboardHandler.clipboard = CosmeticBackup.toText(CosmeticBackup.build(settingsJson()))
        return "Copied the Cosmetic Blocks config to the clipboard"
    }

    fun pasteConfig(replace: Boolean): String = importConfigText(mc.keyboardHandler.clipboard ?: "", replace, "Clipboard")

    private fun importConfigFile(name: String, replace: Boolean): String {
        val text = CosmeticBackup.readFile(name) ?: return "No export named '$name' (/cc cosmetic config list)"
        return importConfigText(text, replace, "'$name'")
    }

    private fun importConfigText(text: String, replace: Boolean, source: String): String {
        val bundle = CosmeticBackup.parse(text) ?: return "$source is not a Cosmetic Blocks config export"
        return importBundle(bundle, replace)
    }

    /** Applies a whole-config bundle (the old config is backed up first). Returns a status line. */
    fun importBundle(bundle: JsonObject, replace: Boolean): String {
        return try {
            val result = CosmeticBackup.import(bundle, replace, settingsJson(), ::applySettings)
            "${if (replace) "Replaced config with" else "Merged"} $result (old config backed up to cosmetic_exports)"
        } catch (e: Exception) {
            "§cImport failed: ${e.message}"
        }
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private fun feedback(src: FabricClientCommandSource, text: String) =
        src.sendFeedback(Component.literal("§b[CC] §f$text"))

    private fun word(ctx: CommandContext<FabricClientCommandSource>, name: String) =
        StringArgumentType.getString(ctx, name)

    private fun lit(name: String) = ClientCommands.literal(name)
    private fun wordArg(name: String) = ClientCommands.argument(name, StringArgumentType.word())
    private fun greedy(name: String) = ClientCommands.argument(name, StringArgumentType.greedyString())

    private fun run(block: (FabricClientCommandSource) -> Unit): Command<FabricClientCommandSource> =
        Command { ctx -> block(ctx.source); 1 }

    private fun undoText(n: Int) = if (n == 0) "Nothing to undo" else "Undid $n change(s)"

    private fun commandTree(root: String): LiteralArgumentBuilder<FabricClientCommandSource> =
        lit(root).then(
            lit("cosmetic")
                .executes { _ -> pendingSettings = true; 1 }
                .then(lit("help").executes { ctx -> help(ctx.source); 1 })
                .then(lit("status").executes { ctx -> status(ctx.source); 1 })
                .then(lit("list").executes { ctx -> status(ctx.source); 1 })
                .then(lit("edit").executes { _ -> toggleEdit(); 1 })
                .then(lit("we").executes { _ -> toggleWorldEdit(); 1 })
                .then(lit("ruler").executes { _ -> CosmeticTools.toggleRuler(); 1 })
                .then(lit("place").executes { ctx -> placeMode.value = !placeMode.value; feedback(ctx.source, "Place mode ${onOff(placeMode.value)}"); 1 })
                .then(lit("fillmode").executes { ctx ->
                    fillMode.value = !fillMode.value
                    feedback(ctx.source, "Fill mode ${onOff(fillMode.value)}" + if (fillMode.value) " - R-click flood-fills (limit ${fillLimit.value.toInt()})" else "")
                    1
                })
                .then(lit("select").executes { _ -> openSelectPopup(); 1 })
                .then(lit("block").then(greedy("block").executes { ctx ->
                    val id = CosmeticCodec.normalizeId(word(ctx, "block"))
                    if (CosmeticCodec.resolve(id) == null) feedback(ctx.source, "Unknown block or state: $id") else selectBrush(id)
                    1
                }))
                .then(lit("props").executes { ctx -> listProps(ctx.source); 1 })
                .then(lit("prop").then(wordArg("name").then(wordArg("value").executes { ctx ->
                    setProp(ctx.source, word(ctx, "name"), word(ctx, "value")); 1
                })))
                .then(lit("shape").then(wordArg("shape").executes { ctx ->
                    val s = word(ctx, "shape").lowercase()
                    val opt = shapeSetting.options.firstOrNull { it.equals(s, true) }
                    if (opt == null) feedback(ctx.source, "Shapes: none, fence, wall, pane") else {
                        shapeSetting.value = opt
                        feedback(ctx.source, "Brush shape: ${opt.lowercase()}")
                    }
                    1
                }))
                .then(lit("undo").executes(run { feedback(it, undoText(CosmeticStore.undo())) }))
                .then(lit("clear").executes(run {
                    val n = CosmeticStore.clearCurrent()
                    feedback(it, "Cleared $n block(s) from '${CosmeticStore.activePresetName}' (/cc cosmetic undo restores them)")
                }))
                // ---- world edit ----
                .then(lit("pos1").executes(run { lookPos()?.let(CosmeticTools::setPos1) ?: feedback(it, "Look at a block") }))
                .then(lit("pos2").executes(run { lookPos()?.let(CosmeticTools::setPos2) ?: feedback(it, "Look at a block") }))
                .then(lit("sel").executes(run { feedback(it, CosmeticTools.selectionInfo()) }))
                .then(lit("clearsel").executes(run { CosmeticTools.clearSelection(); feedback(it, "Selection cleared") }))
                .then(lit("set")
                    .executes(run { feedback(it, CosmeticTools.fill(selectedBlock, currentShape)) })
                    .then(greedy("blocks").executes { ctx -> feedback(ctx.source, CosmeticTools.fill(word(ctx, "blocks"))); 1 }))
                .then(lit("replace").then(wordArg("from").then(greedy("to").executes { ctx ->
                    feedback(ctx.source, CosmeticTools.replace(word(ctx, "from"), word(ctx, "to"))); 1
                })))
                .then(lit("remove")
                    .executes(run { feedback(it, CosmeticTools.removeIn(null)) })
                    .then(wordArg("filter").executes { ctx -> feedback(ctx.source, CosmeticTools.removeIn(word(ctx, "filter"))); 1 }))
                // ---- favourites ----
                .then(lit("fav")
                    .executes(run { listFavs(it) })
                    .then(lit("list").executes(run { listFavs(it) }))
                    .then(lit("add")
                        .executes(run { feedback(it, if (CosmeticFavorites.add(selectedBlock)) "Added ${brushLabel()} to favourites" else "Already a favourite") })
                        .then(greedy("block").executes { ctx ->
                            val id = CosmeticCodec.normalizeId(word(ctx, "block"))
                            feedback(ctx.source, when {
                                CosmeticCodec.resolve(id) == null -> "Unknown block: $id"
                                CosmeticFavorites.add(id) -> "Added ${id.removePrefix("minecraft:")} to favourites"
                                else -> "Already a favourite"
                            })
                            1
                        }))
                    .then(lit("remove").then(greedy("block").executes { ctx ->
                        val id = CosmeticCodec.normalizeId(word(ctx, "block"))
                        feedback(ctx.source, if (CosmeticFavorites.remove(id)) "Removed $id" else "Not a favourite: $id"); 1
                    })))
                // ---- global remaps ----
                .then(lit("remap")
                    .executes(run { listRemaps(it) })
                    .then(lit("list").executes(run { listRemaps(it) }))
                    .then(lit("add").then(wordArg("source").then(wordArg("target")
                        .executes { ctx -> addRemap(ctx.source, word(ctx, "source"), word(ctx, "target"), 1); 1 }
                        .then(ClientCommands.argument("weight", IntegerArgumentType.integer(1, 999)).executes { ctx ->
                            addRemap(ctx.source, word(ctx, "source"), word(ctx, "target"), IntegerArgumentType.getInteger(ctx, "weight")); 1
                        }))))
                    .then(lit("remove").then(wordArg("source")
                        .executes { ctx ->
                            feedback(ctx.source, if (CosmeticRemap.removeSource(word(ctx, "source"))) "Removed remap" else "No remap for that block"); 1
                        }
                        .then(ClientCommands.argument("index", IntegerArgumentType.integer(1)).executes { ctx ->
                            CosmeticRemap.removeTarget(word(ctx, "source"), IntegerArgumentType.getInteger(ctx, "index") - 1)
                            listRemaps(ctx.source); 1
                        })))
                    .then(lit("clear").executes(run { CosmeticRemap.clear(); feedback(it, "Cleared all remaps") }))
                    .then(lit("export").executes(run {
                        mc.keyboardHandler.clipboard = CosmeticRemap.exportJson()
                        feedback(it, "Copied ${CosmeticRemap.sources().size} remap(s) to the clipboard")
                    }))
                    .then(lit("import").executes(run {
                        val ok = CosmeticRemap.importJson(mc.keyboardHandler.clipboard ?: "")
                        feedback(it, if (ok) "Imported ${CosmeticRemap.sources().size} remap(s)" else "Clipboard is not a valid remap export")
                    })))
                // ---- dungeon rooms ----
                .then(lit("rooms")
                    .executes(run { listRooms(it) })
                    .then(lit("list").executes(run { listRooms(it) }))
                    .then(lit("current").executes(run {
                        val r = CosmeticRooms.currentRoom
                        feedback(it, if (r == null) "Not in a scanned room" else "In '${r.data.name}' (${r.rotation.name.lowercase()}, ${r.components.size} tile(s))")
                    }))
                    .then(lit("clear").then(greedy("name").executes { ctx ->
                        val n = CosmeticRooms.clearRoom(word(ctx, "name"))
                        feedback(ctx.source, if (n == 0) "No blocks saved for that room" else "Cleared $n block(s)"); 1
                    }))
                    .then(lit("clearall").executes(run { feedback(it, "Cleared ${CosmeticRooms.clearAllRooms()} room block(s)") }))
                    .then(lit("debug").executes(run { src ->
                        feedback(src, "Room scanner:")
                        CosmeticRooms.debugInfo().forEach { src.sendFeedback(Component.literal("  §7$it")) }
                    })))
                // ---- share ----
                .then(lit("export").executes(run {
                    mc.keyboardHandler.clipboard = CosmeticStore.exportCurrent()
                    feedback(it, "Copied ${CosmeticStore.entryCount()} block(s) of '${CosmeticStore.activePresetName}' to the clipboard")
                }).then(lit("cb").executes(run {
                    // Active preset as a cb mod config: saved to config/cb/cb and copied to the clipboard.
                    val scope = CosmeticStore.islandKey
                    val name = CosmeticStore.activePresetName
                    val entries = CosmeticStore.presetEntries(scope, name)
                    if (entries.isNullOrEmpty()) {
                        feedback(it, "'$name' has no blocks to export")
                    } else {
                        val file = CosmeticBackup.exportToCb("${scope}_$name", entries)
                        mc.keyboardHandler.clipboard = CosmeticBackup.toCbJson(entries).toString()
                        feedback(it, "Exported ${entries.size} block(s) as cb config config/cb/cb/${file.name} (also copied)")
                    }
                })))
                .then(lit("import").executes(run {
                    val n = CosmeticStore.importIntoCurrent(mc.keyboardHandler.clipboard ?: "")
                    feedback(it, if (n < 0) "Clipboard is not a Cosmetic Blocks export" else "Imported $n block(s) into '${CosmeticStore.activePresetName}' (undo with /cc cosmetic undo)")
                }))
                .then(configTree())
                // ---- presets ----
                .then(lit("presets").executes { _ -> pendingPresetScreen = true; 1 })
                .then(presetTree())
        )

    private fun configTree() = lit("config")
        .executes(run { listExports(it) })
        .then(lit("list").executes(run { listExports(it) }))
        .then(lit("export")
            .executes(run { feedback(it, exportConfigToFile(null)) })
            .then(wordArg("name").executes { ctx -> feedback(ctx.source, exportConfigToFile(word(ctx, "name"))); 1 }))
        .then(lit("import").then(wordArg("name")
            .executes { ctx -> feedback(ctx.source, importConfigFile(word(ctx, "name"), replace = false)); 1 }
            .then(lit("replace").executes { ctx -> feedback(ctx.source, importConfigFile(word(ctx, "name"), replace = true)); 1 })))
        .then(lit("copy").executes(run { feedback(it, copyConfig()) }))
        .then(lit("paste")
            .executes(run { feedback(it, pasteConfig(replace = false)) })
            .then(lit("replace").executes(run { feedback(it, pasteConfig(replace = true)) })))
        .then(lit("folder").executes(run {
            CosmeticBackup.dir.mkdirs()
            net.minecraft.util.Util.getPlatform().openFile(CosmeticBackup.dir)
            feedback(it, "Opened cosmetic_exports")
        }))

    private fun listExports(src: FabricClientCommandSource) {
        val names = CosmeticBackup.listExports()
        if (names.isEmpty()) { feedback(src, "No exports yet (/cc cosmetic config export [name])"); return }
        feedback(src, "Exports in cosmetic_exports:")
        names.forEach { src.sendFeedback(Component.literal("  §e$it")) }
    }

    private fun presetTree() = lit("preset")
        .executes { ctx -> presetList(ctx.source); 1 }
        .then(lit("list").executes { ctx -> presetList(ctx.source); 1 })
        .then(lit("island").executes { ctx ->
            feedback(ctx.source, "Island: ${CosmeticStore.islandKey}, preset: ${CosmeticStore.activePresetName}"); 1
        })
        .then(lit("new").then(wordArg("name").executes { ctx ->
            val name = CosmeticStore.sanitize(word(ctx, "name"))
            if (!CosmeticStore.createPreset(name)) {
                feedback(ctx.source, "Preset '$name' already exists")
            } else {
                CosmeticStore.usePreset(name)
                feedback(ctx.source, "Created and switched to '$name' on ${CosmeticStore.islandKey}")
            }
            1
        }))
        .then(lit("use").then(wordArg("name").executes { ctx ->
            val name = CosmeticStore.sanitize(word(ctx, "name"))
            feedback(
                ctx.source,
                if (CosmeticStore.usePreset(name)) "Now using '$name' on ${CosmeticStore.islandKey}"
                else "No preset '$name' on ${CosmeticStore.islandKey}"
            )
            1
        }))
        .then(lit("delete").then(wordArg("name").executes { ctx ->
            val name = CosmeticStore.sanitize(word(ctx, "name"))
            feedback(ctx.source, if (CosmeticStore.deletePreset(name)) "Deleted '$name'" else "No preset '$name'")
            1
        }))
        .then(lit("copy").then(wordArg("island").then(wordArg("preset").then(wordArg("newname").executes { ctx ->
            val island = word(ctx, "island").lowercase()
            val preset = CosmeticStore.sanitize(word(ctx, "preset"))
            val newName = CosmeticStore.sanitize(word(ctx, "newname"))
            feedback(
                ctx.source,
                if (CosmeticStore.copyPreset(island, preset, newName))
                    "Copied $island/$preset to ${CosmeticStore.islandKey}/$newName (/cc cosmetic preset use $newName)"
                else "Could not copy (missing source, or '$newName' already exists)"
            )
            1
        }))))

    private fun lookPos(): BlockPos? = (mc.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos

    private fun status(src: FabricClientCommandSource) {
        val room = CosmeticRooms.currentRoom?.data?.name
        feedback(
            src,
            "Island §e${CosmeticStore.islandKey}§f, preset §e${CosmeticStore.activePresetName}§f, " +
                    "${CosmeticStore.entryCount()} block(s)" + (room?.let { ", room §e$it" } ?: "") +
                    ". Module ${if (isEnabled) "on" else "OFF"}, edit ${onOff(editMode)}, WE ${onOff(worldEditMode)}, brush ${brushLabel()}"
        )
    }

    private fun help(src: FabricClientCommandSource) {
        val lines = listOf(
            "§e/cc cosmetic§f (opens settings) | status | edit | we | ruler | place | fillmode | select | undo | clear",
            "§ebrush:§f block <id[props]> | props | prop <name> <value> | shape <none|fence|wall|pane>",
            "§eregion:§f pos1 | pos2 | sel | clearsel | set [blocks] | replace <from> <to> | remove [block]",
            "§7  weighted blocks: set stone:3 andesite:1 cobblestone",
            "§efavourites:§f fav [add [id] | remove <id> | list]",
            "§eremaps:§f remap add <from> <to> [weight] | remove <from> [index] | list | clear | export | import",
            "§erooms:§f rooms [list | current | debug | clear <name> | clearall]",
            "§epresets:§f presets (quick switcher) | preset [list | island | new | use | delete | copy <island> <preset> <newname>]",
            "§eshare:§f export [cb] | import  (active preset via clipboard; cb = save as a cb mod config)",
            "§econfig:§f config [list | export [name] | import <name> [replace] | copy | paste [replace] | folder]",
            "§7  whole config: every preset, rooms, remaps, favourites, settings"
        )
        lines.forEach { src.sendFeedback(Component.literal(it)) }
    }

    private fun listProps(src: FabricClientCommandSource) {
        val base = selectedBlock.substringBefore('[')
        val block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(base)).orElse(null)
        if (block == null) { feedback(src, "Current brush is invalid"); return }
        val props = block.stateDefinition.properties
        if (props.isEmpty()) { feedback(src, "${base.removePrefix("minecraft:")} has no properties"); return }
        val state = CosmeticCodec.resolve(selectedBlock) ?: block.defaultBlockState()
        feedback(src, "Properties for ${base.removePrefix("minecraft:")}:")
        for (p in props) {
            src.sendFeedback(Component.literal("  ${p.name}=${CosmeticProps.valueName(state, p)} §8[${CosmeticProps.possibleValueNames(p).joinToString(", ")}]"))
        }
    }

    private fun setProp(src: FabricClientCommandSource, name: String, value: String) {
        val base = selectedBlock.substringBefore('[')
        val block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(base)).orElse(null)
        if (block == null) { feedback(src, "Current brush is invalid"); return }
        val prop = block.stateDefinition.getProperty(name)
        if (prop == null) {
            feedback(src, "'$name' is not a property. Available: ${block.stateDefinition.properties.joinToString(", ") { it.name }}")
            return
        }
        val state = CosmeticCodec.resolve(selectedBlock) ?: block.defaultBlockState()
        val updated = CosmeticProps.setByName(state, prop, value)
        if (updated == null) {
            feedback(src, "'$value' is not valid for '$name'. Options: ${CosmeticProps.possibleValueNames(prop).joinToString(", ")}")
            return
        }
        selectBrush(CosmeticCodec.serialize(updated))
    }

    private fun listFavs(src: FabricClientCommandSource) {
        val favs = CosmeticFavorites.all()
        if (favs.isEmpty()) { feedback(src, "No favourites (/cc cosmetic fav add)"); return }
        feedback(src, "Favourites:")
        favs.forEach { src.sendFeedback(Component.literal("  ${if (it == selectedBlock) "§a> " else "§7  "}${it.removePrefix("minecraft:")}")) }
    }

    private fun addRemap(src: FabricClientCommandSource, from: String, to: String, weight: Int) {
        val f = CosmeticCodec.normalizeId(from)
        val t = CosmeticCodec.normalizeId(to)
        if (CosmeticCodec.resolve(f) == null) { feedback(src, "Unknown block: $f"); return }
        if (CosmeticCodec.resolve(t) == null) { feedback(src, "Unknown block: $t"); return }
        CosmeticRemap.addTarget(f, t, weight)
        feedback(src, "Remap ${f.removePrefix("minecraft:")} -> ${t.removePrefix("minecraft:")} (weight $weight)")
    }

    private fun listRemaps(src: FabricClientCommandSource) {
        val sources = CosmeticRemap.sources()
        if (sources.isEmpty()) { feedback(src, "No remaps (/cc cosmetic remap add <from> <to> [weight])"); return }
        feedback(src, "Remaps${if (!remaps.value) " §c(disabled in settings)" else ""}:")
        for (s in sources) {
            val targets = CosmeticRemap.targetsOf(s).mapIndexed { i, t -> "§8${i + 1}.§f${t.id.removePrefix("minecraft:")}§7x${t.weight}" }
            src.sendFeedback(Component.literal("  §e${s.removePrefix("minecraft:")}§f -> ${targets.joinToString(" ")}"))
        }
    }

    private fun listRooms(src: FabricClientCommandSource) {
        val rooms = CosmeticRooms.listRooms()
        if (rooms.isEmpty()) { feedback(src, "No room blocks saved yet"); return }
        feedback(src, "Rooms with cosmetic blocks:")
        rooms.sortedBy { it.first }.forEach { (n, c) -> src.sendFeedback(Component.literal("  §e$n§f: $c")) }
    }

    private fun presetList(src: FabricClientCommandSource) {
        val active = CosmeticStore.activePresetName
        feedback(src, "Presets on ${CosmeticStore.islandKey}:")
        for (name in CosmeticStore.listPresets()) {
            src.sendFeedback(Component.literal("  ${if (name == active) "§a> " else "§7  "}$name"))
        }
    }
}
