package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.render.LineBox
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.render.nvg.NVGSpecialRenderer
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.utils.Color
import com.google.gson.GsonBuilder
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import org.joml.Matrix4f
import org.joml.Vector4f
import java.io.File
import kotlin.math.floor

/**
 * Positional messages: party-chat a message when you walk into a spot ("At SS!", "At Core!"...). Each spot is
 * drawn as an outlined box (through walls by default) with its message floating in the middle.
 *
 * Two kinds of spot:
 *  - F7 / M7 presets (boxes from blade-addons' PositionMessages), only live in the Goldor phase of the boss.
 *  - Custom spots added with `/cc posmsg add <radius> <message>` where you stand; saved per floor.
 */
object PositionalMessages : Module(
    "Positional Messages",
    Category.Skyblock,
    "Party-chat a message when you enter a spot (F7 presets + your own). /cc posmsg"
) {

    // ---- settings ----
    private val sendMode = DropdownSetting(
        "Send Mode", listOf("Every Time I Enter", "Once Per Run"), "Every Time I Enter",
        "Every Time I Enter: each time you walk into a spot (with the cooldown below). Once Per Run: each spot only once per run."
    )
    private val cooldown = NumberSetting("Cooldown", 5.0, 1.0, 30.0, 1.0, "Seconds before the same spot can send again (Every Time I Enter).")
    private val onlyDungeons = BooleanSetting("Only In Dungeons", true, "Custom spots only work inside a dungeon.")
    private val testMode = BooleanSetting(
        "Test Mode", false,
        "For copied maps with the same coords: acts as if you're in the F7 boss (presets + all custom spots live) and shows messages only to you instead of party chat."
    )

    private val showBoxes = BooleanSetting("Show Boxes", true, "Outline each active spot.")
    private val seeThrough = BooleanSetting("See Through Walls", true)
    private val showText = BooleanSetting("Show Message", true, "Draw the spot's message in the middle of its box.")
    private val textSize = NumberSetting("Message Size", 10.0, 4.0, 20.0, 0.5)
    private val textBackground = BooleanSetting("Message Background", true, "A dark card behind the message so it stays readable.")
    private val boxColor = ColorSetting("Box Color", Color(85, 255, 255))
    private val textColor = ColorSetting("Message Color", Color(255, 255, 255))
    private val textBgColor = ColorSetting("Background Color", Color(0, 0, 0, 0.55f))

    // ---- F7 / M7 presets ----
    private class Preset(name: String, val message: String, val box: AABB, default: Boolean) {
        val toggle = BooleanSetting(name, default, "Sends \"$message\"")
    }

    private val presets = listOf(
        Preset("At SS", "At SS!", AABB(107.0, 120.0, 93.0, 110.0, 121.0, 95.0), true),
        Preset("At EE2", "At EE2!", AABB(57.0, 109.0, 130.0, 59.0, 110.0, 132.0), true),
        Preset("At High EE2", "At High EE2!", AABB(59.0, 132.0, 138.0, 62.0, 133.0, 140.0), true),
        Preset("At EE3", "At EE3!", AABB(1.0, 109.0, 103.0, 3.0, 110.0, 106.0), true),
        Preset("At Core", "At Core!", AABB(53.0, 115.0, 51.0, 56.0, 116.0, 54.0), true),
        Preset("Inside Goldor Tunnel", "Inside Goldor Tunnel!", AABB(52.0, 114.0, 55.0, 57.0, 116.0, 58.0), true),
        Preset("At 2 Safespot", "At 2 Safespot!", AABB(46.0, 109.0, 121.987, 49.0, 110.0, 121.988), false),
        Preset("At High 2 Safespot", "At High 2 Safespot!", AABB(57.0, 127.0, 143.7, 70.0, 133.0, 146.7), false),
        Preset("At 3 Safespot", "At 3 Safespot!", AABB(18.0, 121.0, 91.0, 19.0, 126.0, 99.0), false),
        Preset("At Split EE2", "At Split EE2!", AABB(58.0, 119.0, 124.0, 60.0, 123.0, 126.0), false),
    )

    private val customize = ActionSetting("Customize", "F7 presets and how spots are drawn.") {
        (mc.screen as? ClickGUI)?.openPopup(
            SettingsPopup(
                "Positional Messages",
                columns = listOf(
                    listOf(SettingsPopup.Section("F7 / M7 Presets", presets.map { it.toggle })),
                    listOf(SettingsPopup.Section("Look", listOf(showBoxes, seeThrough, showText, textSize, textBackground, boxColor, textColor, textBgColor))),
                ),
            )
        )
    }

    private val popupOnly: List<RenderableSetting<*>> =
        presets.map { it.toggle } + listOf(showBoxes, seeThrough, showText, textSize, textBackground, boxColor, textColor, textBgColor)

    // ---- custom spots (saved) ----
    private class CustomSpot(
        val message: String,
        val minX: Double, val minY: Double, val minZ: Double,
        val maxX: Double, val maxY: Double, val maxZ: Double,
        /** Floor number (7 for F7 and M7) it was added on, or null if added outside a dungeon (works anywhere). */
        val floor: Int?,
    ) {
        @Transient private var cached: AABB? = null
        val box: AABB get() = cached ?: AABB(minX, minY, minZ, maxX, maxY, maxZ).also { cached = it }
    }

    private class SaveData(val spots: MutableList<CustomSpot> = mutableListOf())

    private var data = SaveData()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile: File by lazy { FabricLoader.getInstance().configDir.resolve("cedclient/positional_messages.json").toFile() }

    // ---- runtime state, keyed by the spot's message + box ----
    private class SpotState(var inside: Boolean = false, var lastSent: Long = 0L, var sentThisRun: Boolean = false)

    private val states = HashMap<Any, SpotState>()

    /** A spot that can fire right now. */
    private class Active(val key: Any, val message: String, val box: AABB)

    @Volatile
    private var active: List<Active> = emptyList()

    private val mc get() = Minecraft.getInstance()

    /** Corners for /cc posmsg addbox (the block your feet were in). */
    @Volatile private var pos1: BlockPos? = null
    @Volatile private var pos2: BlockPos? = null

    init {
        addSettings(sendMode, cooldown, onlyDungeons, testMode, customize)
        addSettings(*popupOnly.toTypedArray())
        popupOnly.forEach { it.inPopup = true }
        load()
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            renderWorld(context.poseStack(), context.bufferSource(), context.levelState().cameraRenderState)
        })
    }

    private fun floorNumber(): Int? = DungeonState.floor?.drop(1)?.toIntOrNull()

    /** Which spots apply right now: presets in the F7 / M7 Goldor phase, custom spots on their floor. */
    private fun collectActive(): List<Active> {
        val out = ArrayList<Active>()
        if (testMode.value) {
            for (p in presets) if (p.toggle.value) out += Active(p, p.message, p.box)
            for (s in data.spots) out += Active(s, s.message, s.box)
            return out
        }
        val floorNum = floorNumber()
        if (DungeonState.inDungeon && DungeonState.inBoss && floorNum == 7) {
            for (p in presets) if (p.toggle.value) out += Active(p, p.message, p.box)
        }
        if (!onlyDungeons.value || DungeonState.inDungeon) {
            for (s in data.spots) {
                if (s.floor != null && (!DungeonState.inDungeon || s.floor != floorNum)) continue
                out += Active(s, s.message, s.box)
            }
        }
        return out
    }

    private fun tick() {
        if (!DungeonState.inDungeon && !testMode.value) {
            // Out of the run: "Once Per Run" starts over next time.
            if (states.isNotEmpty()) states.clear()
        }
        val player = mc.player
        if (!isEnabled || player == null) {
            active = emptyList()
            return
        }
        val list = collectActive()
        active = list

        val now = System.currentTimeMillis()
        val pos = player.position()
        for (a in list) {
            val st = states.getOrPut(a.key) { SpotState() }
            val inside = a.box.contains(pos)
            val entered = inside && !st.inside
            st.inside = inside
            if (!entered) continue
            val canSend = when (sendMode.value) {
                "Once Per Run" -> !st.sentThisRun
                else -> now - st.lastSent >= (cooldown.value * 1000).toLong()
            }
            if (!canSend) continue
            st.sentThisRun = true
            st.lastSent = now
            if (testMode.value) player.sendSystemMessage(Component.literal("§b[CC] §7(test) §9Party > §f${player.name.string}: ${a.message}"))
            else player.connection.sendCommand("pc ${a.message}")
        }
    }

    // ------------------------------------------------------------------
    // Rendering: outlines in the world pass, message text on the HUD (like EntityESPLabels)
    // ------------------------------------------------------------------

    private class Label(val nx: Float, val ny: Float, val text: String)

    @Volatile
    private var labels: List<Label> = emptyList()
    private val viewProj = Matrix4f()
    private val clip = Vector4f()

    private fun renderWorld(poseStack: PoseStack, bufferSource: MultiBufferSource.BufferSource, camera: CameraRenderState) {
        val list = active
        val selection = selectionBox()
        if (!isEnabled || (list.isEmpty() && selection == null)) {
            labels = emptyList()
            return
        }
        val cam = camera.pos

        if (showBoxes.value || selection != null) {
            val buffer = bufferSource.getBuffer(if (seeThrough.value) CustomRenderType.LINES_ESP else CustomRenderType.LINES_DEPTH)
            if (showBoxes.value) {
                val c = boxColor.value
                for (a in list) {
                    LineBox.draw(
                        poseStack, buffer, a.box.move(-cam.x, -cam.y, -cam.z),
                        c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat
                    )
                }
            }
            // Corners being picked for /cc posmsg addbox: yellow preview.
            selection?.let { LineBox.draw(poseStack, buffer, it.move(-cam.x, -cam.y, -cam.z), 1f, 0.85f, 0.2f, 1f) }
            bufferSource.endBatch()
        }

        if (!showText.value) {
            labels = emptyList()
            return
        }
        viewProj.set(camera.projectionMatrix).mul(camera.viewRotationMatrix)
        val out = ArrayList<Label>(list.size)
        val level = mc.level
        val player = mc.player
        for (a in list) {
            val center = a.box.center
            // The text is drawn on the HUD, so walls don't hide it by themselves: ray-check it when not seeing through.
            if (!seeThrough.value && level != null && player != null) {
                val hit = level.clip(ClipContext(cam, center, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
                if (hit.type == HitResult.Type.BLOCK && hit.location.distanceTo(cam) < cam.distanceTo(center) - 1.0) continue
            }
            clip.set((center.x - cam.x).toFloat(), (center.y - cam.y).toFloat(), (center.z - cam.z).toFloat(), 1f)
            viewProj.transform(clip)
            if (clip.w <= 0.05f) continue // behind the camera
            val nx = clip.x / clip.w
            val ny = clip.y / clip.w
            if (nx < -1.2f || nx > 1.2f || ny < -1.2f || ny > 1.2f) continue
            out += Label(nx * 0.5f + 0.5f, 0.5f - ny * 0.5f, a.message)
        }
        labels = out
    }

    /** What addbox would make from the corners so far (pos2 falls back to where you stand now). */
    private fun selectionBox(): AABB? {
        val a = pos1 ?: return null
        val b = pos2 ?: mc.player?.blockPosition() ?: return null
        return AABB(
            minOf(a.x, b.x).toDouble(), minOf(a.y, b.y).toDouble(), minOf(a.z, b.z).toDouble(),
            maxOf(a.x, b.x) + 1.0, maxOf(a.y, b.y) + 2.0, maxOf(a.z, b.z) + 1.0,
        )
    }

    /** HUD pass. */
    fun render(g: GuiGraphicsExtractor, @Suppress("UNUSED_PARAMETER") tickCounter: DeltaTracker) {
        val list = labels
        if (list.isEmpty() || !isEnabled || !showText.value) return
        val guiW = g.guiWidth().toFloat()
        val guiH = g.guiHeight().toFloat()
        val font = NVGRenderer.defaultFont
        val size = textSize.value.toFloat()
        val color = textColor.value.rgba
        val bg = textBackground.value
        val bgColor = textBgColor.value.rgba
        val padX = size * 0.4f
        val padY = size * 0.25f
        NVGSpecialRenderer.draw(g, 0, 0, g.guiWidth(), g.guiHeight(), renderContent = {
            val ratio = NVGRenderer.canvasWidth / guiW
            NVGRenderer.scale(ratio, ratio)
            for (l in list) {
                val w = NVGRenderer.textWidth(l.text, size, font)
                val x = l.nx * guiW - w / 2f
                val y = l.ny * guiH - size / 2f
                if (bg) NVGRenderer.rect(x - padX, y - padY, w + padX * 2, size + padY * 2, bgColor, size * 0.3f)
                NVGRenderer.textShadow(l.text, x, y, size, color, font)
            }
        })
    }

    // ------------------------------------------------------------------
    // /cc posmsg
    // ------------------------------------------------------------------

    fun commandTree(): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommands.literal("posmsg")
            .executes { ctx -> listSpots(ctx.source); 1 }
            .then(
                ClientCommands.literal("add")
                    .then(
                        ClientCommands.argument("radius", DoubleArgumentType.doubleArg(0.5, 10.0))
                            .then(
                                ClientCommands.argument("message", StringArgumentType.greedyString())
                                    .executes { ctx ->
                                        addSpot(
                                            ctx.source,
                                            DoubleArgumentType.getDouble(ctx, "radius"),
                                            StringArgumentType.getString(ctx, "message"),
                                        )
                                        1
                                    }
                            )
                    )
            )
            .then(ClientCommands.literal("pos1").executes { ctx -> setCorner(ctx.source, true); 1 })
            .then(ClientCommands.literal("pos2").executes { ctx -> setCorner(ctx.source, false); 1 })
            .then(
                ClientCommands.literal("addbox")
                    .then(
                        ClientCommands.argument("message", StringArgumentType.greedyString())
                            .executes { ctx -> addBox(ctx.source, StringArgumentType.getString(ctx, "message")); 1 }
                    )
            )
            .then(ClientCommands.literal("list").executes { ctx -> listSpots(ctx.source); 1 })
            .then(
                ClientCommands.literal("remove")
                    .then(
                        ClientCommands.argument("index", IntegerArgumentType.integer(1))
                            .executes { ctx ->
                                val i = IntegerArgumentType.getInteger(ctx, "index")
                                if (i > data.spots.size) {
                                    ctx.source.sendFeedback(Component.literal("§cNo spot #$i (see /cc posmsg list)"))
                                } else {
                                    val s = data.spots.removeAt(i - 1)
                                    persist()
                                    ctx.source.sendFeedback(Component.literal("Removed spot #$i: ${s.message}"))
                                }
                                1
                            }
                    )
            )
            .then(
                ClientCommands.literal("clear").executes { ctx ->
                    data.spots.clear()
                    persist()
                    ctx.source.sendFeedback(Component.literal("Removed all custom spots"))
                    1
                }
            )

    /** A box [radius] blocks out from the centre of the block you're standing on, 2 blocks tall. */
    private fun addSpot(source: FabricClientCommandSource, radius: Double, message: String) {
        val p = mc.player ?: return
        val cx = floor(p.x) + 0.5
        val cz = floor(p.z) + 0.5
        val y = floor(p.y)
        saveSpot(source, message, AABB(cx - radius, y, cz - radius, cx + radius, y + 2.0, cz + radius))
    }

    /** Box between the two corners you stood in ([pos1] / [pos2]), block-aligned, 2 blocks tall above the higher one. */
    private fun addBox(source: FabricClientCommandSource, message: String) {
        val a = pos1
        val b = pos2
        if (a == null || b == null) {
            source.sendFeedback(Component.literal("§cSet both corners first: stand in one and /cc posmsg pos1, the other and /cc posmsg pos2"))
            return
        }
        saveSpot(
            source, message,
            AABB(
                minOf(a.x, b.x).toDouble(), minOf(a.y, b.y).toDouble(), minOf(a.z, b.z).toDouble(),
                maxOf(a.x, b.x) + 1.0, maxOf(a.y, b.y) + 2.0, maxOf(a.z, b.z) + 1.0,
            )
        )
        pos1 = null
        pos2 = null
    }

    private fun saveSpot(source: FabricClientCommandSource, message: String, box: AABB) {
        // In a run: the run's floor. On a copied map: the sidebar's "The Catacombs (F7)".
        val floorNum = if (DungeonState.inDungeon) floorNumber() else DungeonState.sidebarFloor()?.drop(1)?.toIntOrNull()
        data.spots += CustomSpot(message, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, floorNum)
        persist()
        val where = floorNum?.let { "floor $it (F$it and M$it)" } ?: "anywhere"
        source.sendFeedback(Component.literal("Added spot #${data.spots.size}: \"$message\" ($where)"))
    }

    private fun setCorner(source: FabricClientCommandSource, first: Boolean) {
        val p = mc.player ?: return
        val pos = p.blockPosition()
        if (first) pos1 = pos else pos2 = pos
        source.sendFeedback(Component.literal("Corner ${if (first) 1 else 2} set to ${pos.x} ${pos.y} ${pos.z}"))
    }

    private fun listSpots(source: FabricClientCommandSource) {
        if (data.spots.isEmpty()) {
            source.sendFeedback(Component.literal("No custom spots. Stand somewhere and use /cc posmsg add <radius> <message>"))
            return
        }
        data.spots.forEachIndexed { i, s ->
            val c = s.box.center
            val where = s.floor?.let { " §7[floor $it]" } ?: ""
            source.sendFeedback(Component.literal("§b#${i + 1} §f${s.message} §7at %.0f %.0f %.0f$where".format(c.x, s.minY, c.z)))
        }
    }

    // ------------------------------------------------------------------
    // Save / load
    // ------------------------------------------------------------------

    private fun load() {
        try {
            if (saveFile.exists()) gson.fromJson(saveFile.readText(), SaveData::class.java)?.let { data = it }
        } catch (e: Exception) {
            println("[PositionalMessages] Failed to load: ${e.message}")
        }
    }

    private fun persist() {
        try {
            saveFile.parentFile?.mkdirs()
            saveFile.writeText(gson.toJson(data))
        } catch (e: Exception) {
            println("[PositionalMessages] Failed to save: ${e.message}")
        }
    }
}
