package ced.cedclient.features.impl.render

import ced.cedclient.render.geometry
import ced.cedclient.utils.Keys
import ced.cedclient.config.CedPaths
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.render.FilledBox
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.IslandState
import ced.cedclient.utils.Color
import ced.cedclient.utils.Debug
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/** One saved waypoint: where, what it is called, and how it is drawn (each waypoint has its own look). */
class Waypoint(
    var name: String,
    var x: Int,
    var y: Int,
    var z: Int,
    var color: Int = 0xFFFFAA00.toInt(),
    var outline: Boolean = true,
    var fill: Boolean = true,
    var beam: Boolean = false,
    var text: Boolean = true,
    var tracer: Boolean = false,
    var island: String? = null,
    var enabled: Boolean = true,
)

/**
 * Waypoints, Odin style: mark a spot and it is drawn in the world as an outlined / filled box, a beam into the sky,
 * a name with the distance, and a line to it. Everything can be changed: the defaults for new waypoints in this module's
 * settings, and each waypoint's own name, position, colour and parts in the editor (`/cc waypoint edit`).
 * Coordinates sent in chat ("x: 1, y: 2, z: 3", e.g. from a party member) show up as temporary waypoints.
 *
 * Commands: /cc waypoint add [name] | addat <x> <y> <z> [name] | remove <name> | list | clear | edit
 */
object Waypoints : Module("Waypoints", Category.Skyblock, "Mark places and see them in the world: box, fill, beam, name, distance, tracer. Edit each one with /cc waypoint edit.") {
    private val mc get() = Minecraft.getInstance()

    // ---- defaults for new waypoints
    private val defaultColor = ColorSetting("Default Color", Color(255, 170, 0), "Colour new waypoints start with.")
    private val defOutline = BooleanSetting("Outline", true, "New waypoints: outlined box.")
    private val defFill = BooleanSetting("Fill", true, "New waypoints: filled box.")
    private val defBeam = BooleanSetting("Beam", false, "New waypoints: beam into the sky.")
    private val defText = BooleanSetting("Name", true, "New waypoints: name above the box.")
    private val defTracer = BooleanSetting("Tracer", false, "New waypoints: line from your crosshair.")

    // ---- how everything is drawn
    private val showDistance = BooleanSetting("Show Distance", true, "Blocks away, after the name.")
    private val throughWalls = BooleanSetting("Through Walls", true, "Draw them through blocks.")
    private val fillOpacity = NumberSetting("Fill Opacity", 30.0, 5.0, 80.0, 5.0, "How solid the fill is, in percent.")
    private val boxSize = NumberSetting("Box Size", 1.0, 0.3, 3.0, 0.1, "Size of the box in blocks.")
    private val beamWidth = NumberSetting("Beam Width", 0.2, 0.05, 0.6, 0.05, "Half-width of the beam.")
    private val hideWithin = NumberSetting("Hide Within", 0.0, 0.0, 20.0, 1.0, "Hide a waypoint when you are closer than this many blocks (0 = never).")
    private val maxDistance = NumberSetting("Max Distance", 0.0, 0.0, 1000.0, 10.0, "Hide waypoints further than this (0 = always show).")
    private val onlyIsland = BooleanSetting("Only On Its Island", true, "A waypoint added on an island is only shown there.")
    private val removeReached = BooleanSetting("Remove When Reached", false, "Delete a waypoint when you get within 2 blocks of it.")

    // ---- chat coordinates
    private val chatWaypoints = BooleanSetting("Chat Waypoints", true, "Coordinates like \"x: 1, y: 2, z: 3\" in chat become a temporary waypoint.")
    private val chatSeconds = NumberSetting("Chat Lifetime (s)", 60.0, 5.0, 600.0, 5.0, "How long a chat waypoint stays.")
    private val chatColor = ColorSetting("Chat Color", Color(85, 255, 255), "Colour of chat waypoints.")

    private val addKey = KeybindSetting("Add Key", Keys.UNKNOWN, "Adds a waypoint where you look (or at your feet) named \"Waypoint N\".")

    private val gson = Gson()
    private val file by lazy { CedPaths.file("waypoints.json") }
    val points = ArrayList<Waypoint>()

    private class Temp(val wp: Waypoint, val until: Long)
    private val temps = ArrayList<Temp>()

    private var addDown = false
    private var tick = 0
    @Volatile private var pendingEdit = false

    fun save() { runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(points)) } }

    private fun load() {
        runCatching {
            val list: List<Waypoint> = gson.fromJson(file.readText(), object : TypeToken<List<Waypoint>>() {}.type)
            points.addAll(list)
        }
    }

    private fun island() = IslandState.island?.name

    private fun fresh(name: String, pos: BlockPos) = Waypoint(
        name, pos.x, pos.y, pos.z, defaultColor.value.rgba, defOutline.value, defFill.value, defBeam.value, defText.value, defTracer.value, island(),
    )

    /** The block you look at (within reach), else your feet. */
    private fun targetPos(): BlockPos? {
        val hit = mc.hitResult as? BlockHitResult
        if (hit != null && hit.type == net.minecraft.world.phys.HitResult.Type.BLOCK) return hit.blockPos.above()
        return mc.player?.blockPosition()
    }

    fun add(name: String, pos: BlockPos): Waypoint {
        val wp = fresh(name, pos)
        points += wp
        save()
        Debug.line("Waypoints", "added ${wp.name} at ${pos.x} ${pos.y} ${pos.z}")
        return wp
    }

    private fun nextName() = "Waypoint ${points.size + 1}"

    private fun shown(wp: Waypoint) = wp.enabled && (!onlyIsland.value || wp.island == null || wp.island == island())

    private fun all(): List<Waypoint> = (points + temps.map { it.wp }).filter { shown(it) }

    init {
        addSettings(defaultColor, defOutline, defFill, defBeam, defText, defTracer, showDistance, throughWalls, fillOpacity, boxSize, beamWidth,
            hideWithin, maxDistance, onlyIsland, removeReached, chatWaypoints, chatSeconds, chatColor, addKey,
            ActionSetting("Edit Waypoints", "Rename, move, recolour and choose how each waypoint is drawn.") { mc.gui.setScreen(WaypointScreen()) },
            ActionSetting("Clear All", "Delete every waypoint.") { points.clear(); temps.clear(); save() },
        )
        load()

        ClientTickEvents.END_CLIENT_TICK.register {
            // A command runs while chat is open; the editor opens on the next tick, after chat has closed.
            if (pendingEdit) { pendingEdit = false; mc.gui.setScreen(WaypointScreen()) }
            if (!isEnabled) return@register
            val now = System.currentTimeMillis()
            temps.removeAll { it.until < now }
            val down = mc.gui.screen() == null && addKey.value > 0 && Keys.isDown(addKey.value)
            if (down && !addDown) targetPos()?.let { add(nextName(), it) }
            addDown = down
            if (++tick % 10 == 0) {
                val p = mc.player ?: return@register
                // Chat waypoints are "go there" markers: always gone once you are within 3 blocks (Remove When Reached is for your own).
                temps.removeAll { shown(it.wp) && p.distanceToSqr(it.wp.x + 0.5, it.wp.y + 0.5, it.wp.z + 0.5) < 9.0 }
                if (removeReached.value && points.removeAll { shown(it) && p.distanceToSqr(it.x + 0.5, it.y + 0.5, it.z + 0.5) < 4.0 }) save()
            }
        }

        on<ChatMessageEvent> {
            if (!isEnabled || !chatWaypoints.value) return@on
            val m = COORDS.find(it.unformattedText) ?: return@on
            val who = SENDER.find(it.unformattedText.trim())?.groupValues?.get(1) ?: "Chat"
            val (x, y, z) = m.destructured
            val wp = Waypoint(who, x.toInt(), y.toInt(), z.toInt(), chatColor.value.rgba, defOutline.value, defFill.value, defBeam.value, true, false, island())
            temps.removeAll { t -> t.wp.name == who } // a new one from the same player replaces the old
            temps += Temp(wp, System.currentTimeMillis() + (chatSeconds.value * 1000).toLong())
            Debug.line("Waypoints", "chat waypoint from $who at $x $y $z")
        }

        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!isEnabled) return@CollectSubmits
            val player = mc.player ?: return@CollectSubmits
            val camera = context.gameRenderer().mainCamera()
            val cam = camera.position()
            val list = all().filter { wp ->
                val d = Math.sqrt(player.distanceToSqr(wp.x + 0.5, wp.y + 0.5, wp.z + 0.5))
                d >= hideWithin.value && (maxDistance.value <= 0.0 || d <= maxDistance.value)
            }
            if (list.isEmpty()) return@CollectSubmits
            val poseStack = context.poseStack()
            val size = boxSize.value
            val half = size / 2
            val bw = beamWidth.value
            val fillA = (fillOpacity.value / 100.0).toFloat()

            fun boxOf(wp: Waypoint) = AABB(wp.x + 0.5 - half, wp.y.toDouble(), wp.z + 0.5 - half, wp.x + 0.5 + half, wp.y + size, wp.z + 0.5 + half).move(-cam.x, -cam.y, -cam.z)

            // fills, then the beams
            val fills = list.filter { it.fill || it.beam }
            if (fills.isNotEmpty()) {
                context.geometry(if (throughWalls.value) CustomRenderType.FILLED_ESP else CustomRenderType.FILLED) { pose, buf ->
                    for (wp in fills) {
                        val c = Color(wp.color)
                        if (wp.fill) FilledBox.draw(pose, buf, boxOf(wp), c.redFloat, c.greenFloat, c.blueFloat, fillA)
                        if (wp.beam) FilledBox.draw(pose, buf, AABB(wp.x + 0.5 - bw, wp.y + size, wp.z + 0.5 - bw, wp.x + 0.5 + bw, wp.y + 320.0, wp.z + 0.5 + bw).move(-cam.x, -cam.y, -cam.z), c.redFloat, c.greenFloat, c.blueFloat, fillA + 0.1f)
                    }
                }
            }
            // outlines and tracers
            if (list.any { it.outline || it.tracer }) {
                context.geometry(if (throughWalls.value) CustomRenderType.LINES_ESP else CustomRenderType.LINES_DEPTH) { pose, buf ->
                    val look = Vec3.directionFromRotation(camera.xRot(), camera.yRot())
                    for (wp in list) {
                        val c = Color(wp.color)
                        if (wp.outline) LineBox.draw(pose, buf, boxOf(wp), c.redFloat, c.greenFloat, c.blueFloat, 1f)
                        if (wp.tracer) LineBox.polyline(pose, buf, listOf(look, Vec3(wp.x + 0.5 - cam.x, wp.y + half - cam.y, wp.z + 0.5 - cam.z)), c.redFloat, c.greenFloat, c.blueFloat, 1f)
                    }
                }
            }
            // names
            for (wp in list) {
                if (!wp.text) continue
                val dist = Math.sqrt(player.distanceToSqr(wp.x + 0.5, wp.y + 0.5, wp.z + 0.5))
                val label = Component.literal(wp.name).withColor(wp.color and 0xFFFFFF).withStyle(ChatFormatting.BOLD)
                if (showDistance.value) label.append(Component.literal(" ${dist.toInt()}m").withStyle(ChatFormatting.YELLOW))
                poseStack.pushPose()
                poseStack.translate((wp.x + 0.5 - cam.x).toFloat(), (wp.y + size + 0.6 - cam.y).toFloat(), (wp.z + 0.5 - cam.z).toFloat())
                context.submitNodeCollector().submitNameTag(poseStack, Vec3.ZERO, 0, label, true, 0xF000F0, context.levelState().cameraRenderState)
                poseStack.popPose()
            }
        })
    }

    private val COORDS = Regex("""x:\s*(-?\d+)[,\s]+y:\s*(-?\d+)[,\s]+z:\s*(-?\d+)""", RegexOption.IGNORE_CASE)
    private val SENDER = Regex("""^(?:Party|Co-op|Guild|Friend|From) > (?:\[[^\]]+] )?(\w+)""")

    fun commandTree(): LiteralArgumentBuilder<FabricClientCommandSource> {
        fun say(ctx: com.mojang.brigadier.context.CommandContext<FabricClientCommandSource>, msg: String) = ctx.source.sendFeedback(Component.literal("§8[§dCC§8] §7$msg"))
        fun find(name: String) = points.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        return ClientCommands.literal("waypoint")
            .executes { ctx -> targetPos()?.let { add(nextName(), it); say(ctx, "Added §f${points.last().name}§7.") }; 1 }
            .then(ClientCommands.literal("add")
                .executes { ctx -> targetPos()?.let { add(nextName(), it); say(ctx, "Added §f${points.last().name}§7.") }; 1 }
                .then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes { ctx ->
                    val name = StringArgumentType.getString(ctx, "name")
                    targetPos()?.let { add(name, it); say(ctx, "Added §f$name§7.") }
                    1
                }))
            .then(ClientCommands.literal("addat")
                .then(ClientCommands.argument("x", DoubleArgumentType.doubleArg())
                    .then(ClientCommands.argument("y", DoubleArgumentType.doubleArg())
                        .then(ClientCommands.argument("z", DoubleArgumentType.doubleArg())
                            .executes { ctx -> add(nextName(), BlockPos.containing(DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z"))); say(ctx, "Added."); 1 }
                            .then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes { ctx ->
                                val name = StringArgumentType.getString(ctx, "name")
                                add(name, BlockPos.containing(DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z")))
                                say(ctx, "Added §f$name§7.")
                                1
                            })))))
            .then(ClientCommands.literal("remove").then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes { ctx ->
                val wp = find(StringArgumentType.getString(ctx, "name"))
                if (wp == null) say(ctx, "No waypoint with that name.") else { points.remove(wp); save(); say(ctx, "Removed §f${wp.name}§7.") }
                1
            }))
            .then(ClientCommands.literal("list").executes { ctx ->
                if (points.isEmpty()) say(ctx, "No waypoints.")
                points.forEach { say(ctx, "§f${it.name} §7(${it.x}, ${it.y}, ${it.z})" + (it.island?.let { i -> " §8$i" } ?: "")) }
                1
            })
            .then(ClientCommands.literal("clear").executes { ctx -> points.clear(); temps.clear(); save(); say(ctx, "Cleared."); 1 })
            .then(ClientCommands.literal("edit").executes { ctx -> pendingEdit = true; say(ctx, "Opening the editor..."); 1 })
    }
}

/** The waypoint editor: one row per waypoint with its name, position, colour (hex) and which parts are drawn. */
class WaypointScreen : Screen(Component.literal("Waypoints")) {
    private var page = 0
    private val perPage = 7

    override fun init() {
        val pts = Waypoints.points
        val pages = maxOf(1, (pts.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        val left = width / 2 - 200
        var y = 44
        val first = page * perPage
        for (i in first until minOf(pts.size, first + perPage)) {
            val wp = pts[i]
            var x = left
            fun box(w: Int, value: String, max: Int, apply: (String) -> Unit) {
                val e = EditBox(font, x, y, w, 16, Component.empty())
                e.setMaxLength(max)
                e.setValue(value)
                e.setResponder { s -> apply(s); Waypoints.save() }
                addRenderableWidget(e)
                x += w + 3
            }
            box(96, wp.name, 40) { wp.name = it }
            box(34, wp.x.toString(), 7) { it.toIntOrNull()?.let { v -> wp.x = v } }
            box(34, wp.y.toString(), 7) { it.toIntOrNull()?.let { v -> wp.y = v } }
            box(34, wp.z.toString(), 7) { it.toIntOrNull()?.let { v -> wp.z = v } }
            box(52, "%06X".format(wp.color and 0xFFFFFF), 6) { s -> if (s.length == 6) s.toIntOrNull(16)?.let { wp.color = 0xFF000000.toInt() or it } }
            fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
                val b = Button.builder(Component.literal(label).withStyle(if (get()) ChatFormatting.GREEN else ChatFormatting.DARK_GRAY)) {
                    set(!get()); Waypoints.save(); rebuildWidgets()
                }.bounds(x, y, 16, 16).build()
                addRenderableWidget(b)
                x += 17
            }
            toggle("O", { wp.outline }, { wp.outline = it })
            toggle("F", { wp.fill }, { wp.fill = it })
            toggle("B", { wp.beam }, { wp.beam = it })
            toggle("T", { wp.text }, { wp.text = it })
            toggle("L", { wp.tracer }, { wp.tracer = it })
            toggle("\u2713", { wp.enabled }, { wp.enabled = it })
            addRenderableWidget(Button.builder(Component.literal("X").withStyle(ChatFormatting.RED)) { Waypoints.points.remove(wp); Waypoints.save(); rebuildWidgets() }.bounds(x, y, 16, 16).build())
            y += 21
        }
        val by = 44 + perPage * 21 + 6
        addRenderableWidget(Button.builder(Component.literal("Add At My Position")) {
            Minecraft.getInstance().player?.let { Waypoints.add("Waypoint ${Waypoints.points.size + 1}", it.blockPosition()); page = (Waypoints.points.size - 1) / perPage; rebuildWidgets() }
        }.bounds(left, by, 120, 18).build())
        addRenderableWidget(Button.builder(Component.literal("<")) { page--; rebuildWidgets() }.bounds(left + 130, by, 20, 18).build().also { it.active = page > 0 })
        addRenderableWidget(Button.builder(Component.literal(">")) { page++; rebuildWidgets() }.bounds(left + 152, by, 20, 18).build().also { it.active = page < pages - 1 })
        addRenderableWidget(Button.builder(Component.literal("Done")) { onClose() }.bounds(left + 340, by, 60, 18).build())
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        g.fill(0, 0, width, height, 0xAA000000.toInt())
        val left = width / 2 - 200
        g.text(font, "Waypoints", left, 12, 0xFFFFFFFF.toInt())
        g.text(font, "Name", left, 31, 0xFFAAAAAA.toInt())
        g.text(font, "X", left + 99 + 12, 31, 0xFFAAAAAA.toInt()); g.text(font, "Y", left + 99 + 37 + 12, 31, 0xFFAAAAAA.toInt()); g.text(font, "Z", left + 99 + 74 + 12, 31, 0xFFAAAAAA.toInt())
        g.text(font, "Color", left + 99 + 111 + 12, 31, 0xFFAAAAAA.toInt())
        g.text(font, "O F B T L = outline, fill, beam, name, tracer; tick = shown", left, 44 + perPage * 21 + 28, 0xFF888888.toInt())
        if (Waypoints.points.isEmpty()) g.text(font, "No waypoints yet: press Add At My Position, or use /cc waypoint add", left, 60, 0xFFAAAAAA.toInt())
        super.extractRenderState(g, mouseX, mouseY, deltaTicks)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}

    override fun removed() { Waypoints.save() }
}
