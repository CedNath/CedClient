package ced.cedclient.features.impl.render

import ced.cedclient.events.ParticleEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.IslandState
import ced.cedclient.utils.Color
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Display
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Highlights the "forest node" floor drops (string items lying on the ground) on the
 * foraging islands -- ported from Skyblocker's FloorDrops.
 *
 * How detection works (same as Skyblocker): the server plays a HAPPY_VILLAGER particle on
 * the block above a node, and a real node has exactly three ItemDisplay entities showing
 * minecraft:string inside that block. Particle alone isn't enough (other things play the
 * same particle), the 3-string check is what makes it reliable. Nodes are re-verified every
 * second or so and dropped if they stop being confirmed for 5 seconds, or when you break /
 * use the block.
 *
 * Differences from Skyblocker:
 *  - the highlight color is a ColorSetting instead of the fixed Skyblocker config color
 *  - "See Through Walls" picks between CustomRenderType.LINES_ESP (no depth test, shows
 *    through blocks) and LINES_DEPTH (normal, hidden behind blocks)
 *  - drawn as an outline box like BlockESP/StarredMobESP, not a filled box
 */
object FloorDrops : Module(
    "FloorDrops",
    Category.Render,
    "Highlights forest node string drops on the foraging islands"
) {
    private val mc: Minecraft = Minecraft.getInstance()

    // --- settings ---
    private val highlightColor = ColorSetting(
        "Highlight Color",
        Color(85, 255, 85),
        "Color of the box drawn around floor drops."
    )
    private val seeThroughWalls = BooleanSetting(
        "See Through Walls",
        true,
        "Draw the highlight through blocks. Turn off to have terrain hide it."
    )
    private val lineWidth = NumberSetting("Line Width", 2.0, 1.0, 6.0, 0.5)
    private val onlyOnForagingIslands = BooleanSetting(
        "Only On Foraging Islands",
        true,
        "Only run on Moonglade Marsh, Torrhus Canyon and the Critter Safari. Turn off to test elsewhere."
    )

    // --- state (all touched from the main thread only) ---
    private class ForestNode(val pos: BlockPos) {
        var lastConfirmed: Long = System.currentTimeMillis()
    }

    private val forestNodes = HashMap<BlockPos, ForestNode>()
    private var tickCounter = 0

    private val FORAGING_ISLANDS = setOf(
        IslandState.Island.GALATEA,
        IslandState.Island.TORRHUS_CANYON,
        IslandState.Island.SAFARI
    )

    private const val STRINGS_PER_NODE = 3
    private const val RECHECK_AFTER_MS = 2000L
    private const val EXPIRE_AFTER_MS = 5000L

    init {
        lineWidth.advanced = true
        addSettings(highlightColor, seeThroughWalls, lineWidth, onlyOnForagingIslands)

        on<ParticleEvent> { onParticle(it.packet) }

        // Every second: re-confirm nodes that haven't been confirmed recently, drop stale ones.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (!shouldProcess()) {
                if (forestNodes.isNotEmpty()) forestNodes.clear()
                return@register
            }
            tickCounter++
            if (tickCounter % 20 == 0) update()
        }

        // Breaking or using the block removes the node right away.
        AttackBlockCallback.EVENT.register(AttackBlockCallback { _, _, _, pos, _ ->
            if (shouldProcess()) forestNodes.remove(pos)
            InteractionResult.PASS
        })
        UseBlockCallback.EVENT.register(UseBlockCallback { _, _, _, hitResult ->
            if (shouldProcess()) forestNodes.remove(hitResult.blockPos)
            InteractionResult.PASS
        })

        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forestNodes.clear() }

        // 26.2: MultiBufferSource/BufferSource are gone, so the lines are now submitted
        // through the SubmitNodeCollector (submitCustomGeometry) instead of written into a
        // buffer we flush ourselves. That has to happen in COLLECT_SUBMITS: it runs before
        // the feature passes are drawn, whereas AFTER_SOLID_FEATURES fires once the solid
        // pass is already finished, so anything submitted there would never be drawn.
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            if (!shouldProcess() || forestNodes.isEmpty()) return@register

            val poseStack = context.poseStack()
            val cameraPos = context.gameRenderer().mainCamera().position()
            val submitNodeCollector = context.submitNodeCollector()

            // Read the settings once per frame, not once per node.
            val renderType = if (seeThroughWalls.value) CustomRenderType.LINES_ESP else CustomRenderType.LINES_DEPTH
            val c = highlightColor.value
            val width = lineWidth.value.toFloat()
            val now = System.currentTimeMillis()

            // The geometry callback below runs later, during the draw pass -- iterate a snapshot.
            val nodes = forestNodes.values.toList()

            // The callback hands back a PoseStack.Pose (one frozen transform), not a PoseStack.
            submitNodeCollector.submitCustomGeometry(poseStack, renderType) { pose, lineBuffer ->
                for (node in nodes) {
                    if (node.lastConfirmed + EXPIRE_AFTER_MS <= now) continue
                    val box: AABB = AABB(node.pos).inflate(0.02).move(-cameraPos.x, -cameraPos.y, -cameraPos.z)
                    drawLineBox(pose, lineBuffer, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat, width)
                }
            }
        }
    }

    override fun onDisable() {
        forestNodes.clear()
    }

    private fun shouldProcess(): Boolean {
        if (!isEnabled) return false
        if (!onlyOnForagingIslands.value) return true
        return IslandState.island in FORAGING_ISLANDS
    }

    private fun onParticle(packet: ClientboundLevelParticlesPacket) {
        if (!shouldProcess()) return
        if (packet.particle.type != ParticleTypes.HAPPY_VILLAGER.type) return

        // The particle is played one block above the node.
        val pos = BlockPos.containing(packet.x, packet.y - 1, packet.z)
        if (forestNodes.containsKey(pos)) return

        if (countStringItemDisplays(pos) == STRINGS_PER_NODE) {
            forestNodes[pos] = ForestNode(pos)
        }
    }

    private fun update() {
        val now = System.currentTimeMillis()
        val iterator = forestNodes.values.iterator()
        while (iterator.hasNext()) {
            val node = iterator.next()
            if (node.lastConfirmed + RECHECK_AFTER_MS <= now && countStringItemDisplays(node.pos) == STRINGS_PER_NODE) {
                node.lastConfirmed = now
            }
            if (node.lastConfirmed + EXPIRE_AFTER_MS <= now) iterator.remove()
        }
    }

    /** Number of ItemDisplay entities showing minecraft:string inside the given block. */
    private fun countStringItemDisplays(pos: BlockPos): Int {
        val level = mc.level ?: return 0
        val displays = level.getEntitiesOfClass(
            Display.ItemDisplay::class.java,
            AABB.ofSize(Vec3.atCenterOf(pos), 1.0, 1.0, 1.0)
        ) { true }

        return displays.count { display ->
            val stack = display.itemRenderState()?.itemStack()
            stack != null && !stack.isEmpty && stack.item == Items.STRING
        }
    }

    // ------------------------------------------------------------------
    // Rendering helpers (same vertex pattern as BlockESPRenderer)
    // ------------------------------------------------------------------
    private fun drawLineBox(
        pose: PoseStack.Pose, buffer: VertexConsumer, box: AABB,
        r: Float, g: Float, b: Float, a: Float, w: Float
    ) {
        val x0 = box.minX; val y0 = box.minY; val z0 = box.minZ
        val x1 = box.maxX; val y1 = box.maxY; val z1 = box.maxZ

        // bottom
        edge(pose, buffer, x0, y0, z0, x1, y0, z0, r, g, b, a, w)
        edge(pose, buffer, x1, y0, z0, x1, y0, z1, r, g, b, a, w)
        edge(pose, buffer, x1, y0, z1, x0, y0, z1, r, g, b, a, w)
        edge(pose, buffer, x0, y0, z1, x0, y0, z0, r, g, b, a, w)
        // top
        edge(pose, buffer, x0, y1, z0, x1, y1, z0, r, g, b, a, w)
        edge(pose, buffer, x1, y1, z0, x1, y1, z1, r, g, b, a, w)
        edge(pose, buffer, x1, y1, z1, x0, y1, z1, r, g, b, a, w)
        edge(pose, buffer, x0, y1, z1, x0, y1, z0, r, g, b, a, w)
        // verticals
        edge(pose, buffer, x0, y0, z0, x0, y1, z0, r, g, b, a, w)
        edge(pose, buffer, x1, y0, z0, x1, y1, z0, r, g, b, a, w)
        edge(pose, buffer, x1, y0, z1, x1, y1, z1, r, g, b, a, w)
        edge(pose, buffer, x0, y0, z1, x0, y1, z1, r, g, b, a, w)
    }

    private fun edge(
        pose: PoseStack.Pose, buffer: VertexConsumer,
        x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float, a: Float, w: Float
    ) {
        val matrix = pose.pose()
        val n = Vec3(x2 - x1, y2 - y1, z2 - z1).normalize()

        buffer.addVertex(matrix, x1.toFloat(), y1.toFloat(), z1.toFloat())
            .setColor(r, g, b, a)
            .setNormal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
            .setLineWidth(w)

        buffer.addVertex(matrix, x2.toFloat(), y2.toFloat(), z2.toFloat())
            .setColor(r, g, b, a)
            .setNormal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
            .setLineWidth(w)
    }
}