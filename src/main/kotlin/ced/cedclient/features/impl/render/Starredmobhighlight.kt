package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Highlights (boxes) the dungeon mobs that still need to die for the key to drop -- the ones whose floating
 * nametag (an invisible ArmorStand above/on the mob) contains the star character.
 *
 * Why this re-scans instead of reacting to entity-data packets: when a mob's nametag stand first
 * arrives, the mob it belongs to may not be loaded or positioned yet, so any one-shot
 * "stand -> mob" lookup done at that instant misses mobs randomly. Here every scan re-pairs each
 * starred stand with the nearest mob underneath it from scratch, so a mob that shows up (or
 * moves) a tick later is simply picked up on the next scan.
 *
 * The box is depth-tested, so walls and terrain hide it -- there is no see-through-walls mode.
 *
 * Shadow Assassins are boxed too (optional, own color). They're NPC "players" whose profile name
 * contains "Shadow Assassin", so they're matched by that name instead of by a star -- the same
 * way Odin's Highlight finds them -- and they keep showing even while their nametag is hidden.
 */
object StarredMobHighlight : Module(
    "StarredMobHighlight",
    Category.Render,
    "Highlights dungeon mobs with a \u272F in their nametag (the ones that must die for the key). Only visible when the mob is in line of sight."
) {
    private const val STAR = '\u272F'
    private const val ASSASSIN_NAME = "Shadow Assassin"

    // How far (horizontally) a mob may be from its nametag stand and still count as its owner.
    private const val MATCH_RADIUS_SQ = 1.5 * 1.5

    private val mc: Minecraft = Minecraft.getInstance()

    private val onlyInDungeon = BooleanSetting(
        "Only In Dungeons", true,
        "Only scan while CedClient thinks you're in a dungeon run. Turn off to test elsewhere."
    )
    private val boxColor = ColorSetting("Box Color", Color(255, 255, 255), "Color of the box around starred mobs.")
    private val lineWidth = NumberSetting("Line Width", 2.0, 1.0, 6.0, 0.5)
    private val highlightAssassins = BooleanSetting("Highlight Shadow Assassins", true, "Also box Shadow Assassins, even without a star nametag.")
    private val assassinColor = ColorSetting("Shadow Assassin Color", Color(170, 85, 255), "Color of the box around Shadow Assassins.")
    private val scanIntervalTicks = NumberSetting("Scan Interval ticks", 2.0, 1.0, 10.0, 1.0)

    private class Target(val entity: LivingEntity, val assassin: Boolean)

    @Volatile
    private var targets: List<Target> = emptyList()

    private var tickCounter = 0

    init {
        scanIntervalTicks.advanced = true
        addSettings(onlyInDungeon, boxColor, highlightAssassins, assassinColor, lineWidth, scanIntervalTicks)

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!isEnabled) {
                if (targets.isNotEmpty()) targets = emptyList()
                return@register
            }
            tickCounter++
            if (tickCounter % scanIntervalTicks.value.toInt().coerceAtLeast(1) == 0) scan(client)
        }

        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (!isEnabled) return@AfterSolidFeatures
            val current = targets
            if (current.isEmpty()) return@AfterSolidFeatures

            val poseStack = context.poseStack()
            val camera = context.gameRenderer().mainCamera
            val cameraPos = camera.position()
            val bufferSource = context.bufferSource()
            val lineBuffer = bufferSource.getBuffer(CustomRenderType.LINES_DEPTH)

            val width = lineWidth.value.toFloat()
            val pt = partialTick()

            for (target in current) {
                val mob = target.entity
                if (!mob.isAlive || mob.isRemoved) continue

                val c = if (target.assassin) assassinColor.value else boxColor.value
                val r = c.redFloat
                val g = c.greenFloat
                val b = c.blueFloat
                val a = c.alphaFloat

                // Interpolate between last and current tick so the box glides instead of stepping.
                val ix = Mth.lerp(pt, mob.xOld, mob.x)
                val iy = Mth.lerp(pt, mob.yOld, mob.y)
                val iz = Mth.lerp(pt, mob.zOld, mob.z)
                val box: AABB = mob.boundingBox
                    .move(ix - mob.x, iy - mob.y, iz - mob.z)
                    .inflate(0.05)
                    .move(-cameraPos.x, -cameraPos.y, -cameraPos.z)
                drawLineBox(poseStack, lineBuffer, box, r, g, b, a, width)
            }

            bufferSource.endBatch()
        })
    }

    /** Single place to adjust if the DeltaTracker API differs on your mappings. */
    private fun partialTick(): Double = mc.deltaTracker.getGameTimeDeltaPartialTick(false).toDouble()

    private fun scan(client: Minecraft) {
        val level = client.level
        val player = client.player
        if (level == null || player == null || (onlyInDungeon.value && !DungeonState.inDungeon)) {
            if (targets.isNotEmpty()) targets = emptyList()
            return
        }

        val stands = ArrayList<ArmorStand>()
        val mobs = ArrayList<LivingEntity>()
        val assassins = ArrayList<LivingEntity>()
        val wantAssassins = highlightAssassins.value
        for (e in level.entitiesForRendering()) {
            when {
                e is ArmorStand -> {
                    val name = e.customName
                    if (name != null && name.string.indexOf(STAR) >= 0) stands.add(e)
                }
                // Shadow Assassins are NPC players named after the mob; no star needed.
                wantAssassins && e is Player && e !== player && e.isAlive &&
                        e.gameProfile.name.contains(ASSASSIN_NAME) -> assassins.add(e)
                // Dungeon mobs that are "players" are NPCs (version-2 UUIDs); real players are v4.
                e is LivingEntity && e !== player && e.isAlive && (e !is Player || e.uuid.version() == 2) -> mobs.add(e)
            }
        }

        if (stands.isEmpty() && assassins.isEmpty()) {
            if (targets.isNotEmpty()) targets = emptyList()
            return
        }

        // entity -> isAssassin. Assassins go in first so they keep their own color even when a
        // star stand also matches them.
        val found = LinkedHashMap<LivingEntity, Boolean>()
        for (a in assassins) found[a] = true
        for (stand in stands) {
            var best: LivingEntity? = null
            var bestScore = Double.MAX_VALUE
            for (mob in mobs) {
                val dx = mob.x - stand.x
                val dz = mob.z - stand.z
                val distSq = dx * dx + dz * dz
                if (distSq > MATCH_RADIUS_SQ) continue

                // The nametag stand sits somewhere around the mob's head, never far below its feet.
                val dy = stand.y - mob.y
                if (dy < -1.0 || dy > mob.bbHeight + 2.5) continue

                // The stand spawns right after its mob, so id - 1 is the best possible match.
                val score = if (mob.id == stand.id - 1) -1.0 else distSq
                if (score < bestScore) {
                    bestScore = score
                    best = mob
                }
            }
            if (best != null) found.putIfAbsent(best, false)
        }
        targets = found.map { (entity, assassin) -> Target(entity, assassin) }
    }

    // ------------------------------------------------------------------
    // Rendering helpers (plain line-box vertex pattern)
    // ------------------------------------------------------------------
    private fun drawLineBox(
        poseStack: PoseStack, buffer: VertexConsumer, box: AABB,
        r: Float, g: Float, b: Float, a: Float, w: Float
    ) {
        val x0 = box.minX; val y0 = box.minY; val z0 = box.minZ
        val x1 = box.maxX; val y1 = box.maxY; val z1 = box.maxZ

        // bottom
        edge(poseStack, buffer, x0, y0, z0, x1, y0, z0, r, g, b, a, w)
        edge(poseStack, buffer, x1, y0, z0, x1, y0, z1, r, g, b, a, w)
        edge(poseStack, buffer, x1, y0, z1, x0, y0, z1, r, g, b, a, w)
        edge(poseStack, buffer, x0, y0, z1, x0, y0, z0, r, g, b, a, w)
        // top
        edge(poseStack, buffer, x0, y1, z0, x1, y1, z0, r, g, b, a, w)
        edge(poseStack, buffer, x1, y1, z0, x1, y1, z1, r, g, b, a, w)
        edge(poseStack, buffer, x1, y1, z1, x0, y1, z1, r, g, b, a, w)
        edge(poseStack, buffer, x0, y1, z1, x0, y1, z0, r, g, b, a, w)
        // verticals
        edge(poseStack, buffer, x0, y0, z0, x0, y1, z0, r, g, b, a, w)
        edge(poseStack, buffer, x1, y0, z0, x1, y1, z0, r, g, b, a, w)
        edge(poseStack, buffer, x1, y0, z1, x1, y1, z1, r, g, b, a, w)
        edge(poseStack, buffer, x0, y0, z1, x0, y1, z1, r, g, b, a, w)
    }

    private fun edge(
        poseStack: PoseStack, buffer: VertexConsumer,
        x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float, a: Float, w: Float
    ) = drawLine(poseStack, buffer, Vec3(x1, y1, z1), Vec3(x2, y2, z2), r, g, b, a, w)

    private fun drawLine(
        poseStack: PoseStack, buffer: VertexConsumer, from: Vec3, to: Vec3,
        r: Float, g: Float, b: Float, a: Float, w: Float
    ) {
        val pose = poseStack.last().pose()
        val n = to.subtract(from).normalize()

        buffer.addVertex(pose, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
            .setColor(r, g, b, a)
            .setNormal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
            .setLineWidth(w)

        buffer.addVertex(pose, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
            .setColor(r, g, b, a)
            .setNormal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat())
            .setLineWidth(w)
    }
}