package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.render.cosmetic.CosmeticCodec
import ced.cedclient.render.cosmetic.CosmeticStore
import ced.cedclient.ui.clickgui.BlockPickerPopup
import ced.cedclient.ui.clickgui.ClickGUI
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
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import org.lwjgl.glfw.GLFW

/**
 * Cosmetic Blocks -- client-only, per-position block replacements, organised per SkyBlock island and preset.
 * Same editing flow as the cb mod. In edit mode (no screen open):
 *   right click = replace the block you look at with the selected block (Place Mode: add a phantom block on the face)
 *   left click  = remove the replacement there      middle click = pick the block / its replacement
 *
 * Commands (/cc cosmetic ...): edit | select | block <id[props]> | undo | clear | list
 *   preset list | island | new <name> | use <name> | delete <name> | copy <island> <preset> <newname>
 */
object CosmeticBlocks : Module(
    "Cosmetic Blocks",
    Category.Render,
    "Client-side only: change how individual blocks look. Presets are saved per SkyBlock island. /cc cosmetic"
) {
    private val placeMode = BooleanSetting(
        "Place Mode", false,
        "Right-click adds a phantom block next to the face you look at, instead of replacing the block itself."
    )
    private val selectAction = ActionSetting("Select Block") { openSelectPopup() }
    private val editAction = ActionSetting("Toggle Edit Mode") { toggleEdit() }

    var editMode = false
        private set
    var selectedBlock = "minecraft:stone"
        private set

    private var pendingSelect = false
    private var leftWas = false
    private var rightWas = false
    private var middleWas = false

    private val mc get() = Minecraft.getInstance()

    init {
        addSettings(placeMode, selectAction, editAction)
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // Brigadier merges same-named roots, so this adds "cosmetic" under the existing /cc and /cedclient.
            dispatcher.register(commandTree("cedclient"))
            dispatcher.register(commandTree("cc"))
        }
    }

    override fun onEnable() = CosmeticStore.setEnabled(true)

    override fun onDisable() {
        editMode = false
        CosmeticStore.setEnabled(false)
    }

    // ------------------------------------------------------------------
    // Tick / edit mode
    // ------------------------------------------------------------------

    private fun say(text: String) {
        mc.player?.sendSystemMessage(Component.literal("\u00a7b[CC] \u00a7f$text"))
    }

    private fun tick() {
        CosmeticStore.tick()

        if (pendingSelect) {
            pendingSelect = false
            mc.setScreen(ClickGUI())
            showSelectPopup()
        }
        if (editMode && isEnabled) tickEdit()
    }

    private fun toggleEdit() {
        if (!isEnabled) {
            say("Enable the Cosmetic Blocks module first")
            return
        }
        editMode = !editMode
        say("Edit mode ${if (editMode) "\u00a7aON" else "\u00a7cOFF"}\u00a7f - selected: ${selectedBlock.removePrefix("minecraft:")}")
    }

    private fun tickEdit() {
        if (mc.screen != null || mc.level == null) return

        val window = GLFW.glfwGetCurrentContext()
        val left = GLFW.glfwGetMouseButton(window, 0) == 1
        val right = GLFW.glfwGetMouseButton(window, 1) == 1
        val middle = GLFW.glfwGetMouseButton(window, 2) == 1

        val hit = (mc.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }
        if (hit != null) {
            try {
                if (right && !rightWas) onRight(hit)
                if (left && !leftWas) onLeft(hit)
                if (middle && !middleWas) onMiddle(hit)
            } catch (t: Throwable) {
                // Report instead of crashing the game, and leave edit mode so it can't spam.
                t.printStackTrace()
                editMode = false
                val top = t.stackTrace.take(3).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                say("\u00a7cError: ${t.javaClass.simpleName}: ${t.message} @ $top")
            }
        }
        leftWas = left
        rightWas = right
        middleWas = middle
    }

    private fun onRight(hit: BlockHitResult) {
        val level = mc.level ?: return
        val pos = hit.blockPos
        if (placeMode.value) {
            val full = level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)
            val target = if (full) pos.relative(hit.direction) else pos
            val free = level.getBlockState(target).isAir || (!full && target == pos)
            if (free && !CosmeticStore.cache.containsKey(target.asLong())) place(target, hit, true)
        } else {
            place(pos, hit, false)
        }
    }

    private fun place(target: BlockPos, hit: BlockHitResult, phantom: Boolean) {
        if (selectedBlock.contains('[')) {
            CosmeticStore.set(target, selectedBlock, phantom)
        } else {
            val state = CosmeticCodec.placementState(selectedBlock, hit)
            if (state != null) CosmeticStore.setState(target, state, phantom) else CosmeticStore.set(target, selectedBlock, phantom)
        }
        say("${target.x},${target.y},${target.z} -> ${selectedBlock.removePrefix("minecraft:")}")
    }

    private fun onLeft(hit: BlockHitResult) {
        val pos = hit.blockPos
        val removedAt = when {
            CosmeticStore.remove(pos) -> pos
            else -> hit.blockPos.relative(hit.direction).takeIf { CosmeticStore.remove(it) }
        }
        if (removedAt != null) say("Removed at ${removedAt.x},${removedAt.y},${removedAt.z}")
    }

    private fun onMiddle(hit: BlockHitResult) {
        val level = mc.level ?: return
        val pos = hit.blockPos
        val state = CosmeticStore.cache[pos.asLong()] ?: level.getBlockState(pos)
        selectedBlock = CosmeticCodec.serialize(state)
        say("Selected ${selectedBlock.removePrefix("minecraft:")}")
    }

    // ------------------------------------------------------------------
    // Block picker
    // ------------------------------------------------------------------

    private fun openSelectPopup() {
        if (mc.screen is ClickGUI) showSelectPopup() else pendingSelect = true
    }

    private fun showSelectPopup() {
        val gui = mc.screen as? ClickGUI ?: return
        gui.openPopup(BlockPickerPopup("Select Block") { block ->
            selectedBlock = BuiltInRegistries.BLOCK.getKey(block).toString()
            say("Selected ${selectedBlock.removePrefix("minecraft:")}")
        })
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private fun feedback(src: FabricClientCommandSource, text: String) =
        src.sendFeedback(Component.literal("\u00a7b[CC] \u00a7f$text"))

    private fun word(ctx: CommandContext<FabricClientCommandSource>, name: String) =
        StringArgumentType.getString(ctx, name)

    private fun commandTree(root: String): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal(root).then(
            ClientCommands.literal("cosmetic")
                .executes { ctx -> status(ctx.source); 1 }
                .then(ClientCommands.literal("list").executes { ctx -> status(ctx.source); 1 })
                .then(ClientCommands.literal("edit").executes { _ -> toggleEdit(); 1 })
                .then(ClientCommands.literal("select").executes { _ -> openSelectPopup(); 1 })
                .then(
                    ClientCommands.literal("block").then(
                        ClientCommands.argument("block", StringArgumentType.greedyString()).executes { ctx ->
                            val id = CosmeticCodec.normalizeId(word(ctx, "block"))
                            if (CosmeticCodec.resolve(id) == null) {
                                feedback(ctx.source, "Unknown block or state: $id")
                            } else {
                                selectedBlock = id
                                feedback(ctx.source, "Selected ${id.removePrefix("minecraft:")}")
                            }
                            1
                        }
                    )
                )
                .then(
                    ClientCommands.literal("undo").executes { ctx ->
                        val n = CosmeticStore.undo()
                        feedback(ctx.source, if (n == 0) "Nothing to undo" else "Undid $n change(s)")
                        1
                    }
                )
                .then(
                    ClientCommands.literal("clear").executes { ctx ->
                        val n = CosmeticStore.clearCurrent()
                        feedback(ctx.source, "Cleared $n block(s) from '${CosmeticStore.activePresetName}' (/cc cosmetic undo restores them)")
                        1
                    }
                )
                .then(
                    ClientCommands.literal("preset")
                        .executes { ctx -> presetList(ctx.source); 1 }
                        .then(ClientCommands.literal("list").executes { ctx -> presetList(ctx.source); 1 })
                        .then(
                            ClientCommands.literal("island").executes { ctx ->
                                feedback(ctx.source, "Island: ${CosmeticStore.islandKey}, preset: ${CosmeticStore.activePresetName}")
                                1
                            }
                        )
                        .then(
                            ClientCommands.literal("new").then(
                                ClientCommands.argument("name", StringArgumentType.word()).executes { ctx ->
                                    val name = CosmeticStore.sanitize(word(ctx, "name"))
                                    if (!CosmeticStore.createPreset(name)) {
                                        feedback(ctx.source, "Preset '$name' already exists")
                                    } else {
                                        CosmeticStore.usePreset(name)
                                        feedback(ctx.source, "Created and switched to '$name' on ${CosmeticStore.islandKey}")
                                    }
                                    1
                                }
                            )
                        )
                        .then(
                            ClientCommands.literal("use").then(
                                ClientCommands.argument("name", StringArgumentType.word()).executes { ctx ->
                                    val name = CosmeticStore.sanitize(word(ctx, "name"))
                                    feedback(
                                        ctx.source,
                                        if (CosmeticStore.usePreset(name)) "Now using '$name' on ${CosmeticStore.islandKey}"
                                        else "No preset '$name' on ${CosmeticStore.islandKey}"
                                    )
                                    1
                                }
                            )
                        )
                        .then(
                            ClientCommands.literal("delete").then(
                                ClientCommands.argument("name", StringArgumentType.word()).executes { ctx ->
                                    val name = CosmeticStore.sanitize(word(ctx, "name"))
                                    feedback(ctx.source, if (CosmeticStore.deletePreset(name)) "Deleted '$name'" else "No preset '$name'")
                                    1
                                }
                            )
                        )
                        .then(
                            ClientCommands.literal("copy").then(
                                ClientCommands.argument("island", StringArgumentType.word()).then(
                                    ClientCommands.argument("preset", StringArgumentType.word()).then(
                                        ClientCommands.argument("newname", StringArgumentType.word()).executes { ctx ->
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
                                        }
                                    )
                                )
                            )
                        )
                )
        )

    private fun status(src: FabricClientCommandSource) {
        feedback(
            src,
            "Island \u00a7e${CosmeticStore.islandKey}\u00a7f, preset \u00a7e${CosmeticStore.activePresetName}\u00a7f, " +
                    "${CosmeticStore.entryCount()} block(s). Module ${if (isEnabled) "on" else "OFF"}, edit mode ${if (editMode) "on" else "off"}, " +
                    "selected ${selectedBlock.removePrefix("minecraft:")}"
        )
    }

    private fun presetList(src: FabricClientCommandSource) {
        val active = CosmeticStore.activePresetName
        feedback(src, "Presets on ${CosmeticStore.islandKey}:")
        for (name in CosmeticStore.listPresets()) {
            src.sendFeedback(Component.literal("  ${if (name == active) "\u00a7a> " else "\u00a77  "}$name"))
        }
    }
}