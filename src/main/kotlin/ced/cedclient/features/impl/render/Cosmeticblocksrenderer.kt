package ced.cedclient.features.impl.render

import ced.cedclient.render.cosmetic.CosmeticStore
import ced.cedclient.render.cosmetic.CosmeticTools
import ced.cedclient.render.pipeline.CustomRenderType
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/**
 * World overlays for Cosmetic Blocks (same render path as BlockESPRenderer): the World Edit selection,
 * the ruler preview / reach marker, and the optional "Show Replaced Blocks" wireframe.
 */
object CosmeticBlocksRenderer {
    private const val MAX_WIREFRAMES = 3000

    fun register() {
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (!CosmeticBlocks.isEnabled) return@AfterSolidFeatures
            val p1 = CosmeticTools.pos1
            val p2 = CosmeticTools.pos2
            val selection = CosmeticBlocks.worldEditMode && (p1 != null || p2 != null)
            val ruler = CosmeticTools.rulerActive
            val wire = CosmeticBlocks.showReplaced.value && CosmeticStore.cache.isNotEmpty()
            if (!selection && !ruler && !wire) return@AfterSolidFeatures

            val pose = context.poseStack()
            val cam = context.gameRenderer().mainCamera.position()
            val bufferSource = context.bufferSource()
            val buf = bufferSource.getBuffer(CustomRenderType.LINES_ESP)

            if (selection) {
                if (p1 != null && p2 != null) {
                    box(pose, buf, cam, minOf(p1.x, p2.x).toDouble(), minOf(p1.y, p2.y).toDouble(), minOf(p1.z, p2.z).toDouble(),
                        maxOf(p1.x, p2.x) + 1.0, maxOf(p1.y, p2.y) + 1.0, maxOf(p1.z, p2.z) + 1.0, 0.3f, 1f, 0.3f, 0.9f)
                }
                p1?.let { blockBox(pose, buf, cam, it, 1f, 0.6f, 0.1f, 0.9f) }
                p2?.let { blockBox(pose, buf, cam, it, 0.2f, 0.6f, 1f, 0.9f) }
            }

            if (ruler) {
                val start = CosmeticTools.rulerStart
                if (start == null) CosmeticTools.rulerReachTarget?.let { blockBox(pose, buf, cam, it, 0.5f, 1f, 0.5f, 0.6f) }
                else blockBox(pose, buf, cam, start, 1f, 1f, 0f, 0.9f)
                for (p in CosmeticTools.rulerPreview) blockBox(pose, buf, cam, p, 1f, 1f, 1f, 0.5f)
            }

            if (wire) {
                val c = CosmeticBlocks.overlayColor.value
                val range = CosmeticBlocks.overlayRange.value
                val rangeSq = range * range
                var n = 0
                for (key in CosmeticStore.cache.keys) {
                    val x = BlockPos.getX(key); val y = BlockPos.getY(key); val z = BlockPos.getZ(key)
                    val dx = x + 0.5 - cam.x; val dy = y + 0.5 - cam.y; val dz = z + 0.5 - cam.z
                    if (dx * dx + dy * dy + dz * dz > rangeSq) continue
                    box(pose, buf, cam, x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0,
                        c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat.coerceAtLeast(0.3f))
                    if (++n >= MAX_WIREFRAMES) break
                }
            }

            bufferSource.endBatch()
        })
    }

    private fun blockBox(pose: PoseStack, buf: VertexConsumer, cam: Vec3, p: BlockPos, r: Float, g: Float, b: Float, a: Float) =
        box(pose, buf, cam, p.x - 0.002, p.y - 0.002, p.z - 0.002, p.x + 1.002, p.y + 1.002, p.z + 1.002, r, g, b, a)

    private fun box(
        pose: PoseStack, buf: VertexConsumer, cam: Vec3,
        x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val ax = x1 - cam.x; val ay = y1 - cam.y; val az = z1 - cam.z
        val bx = x2 - cam.x; val by = y2 - cam.y; val bz = z2 - cam.z
        line(pose, buf, ax, ay, az, bx, ay, az, r, g, b, a); line(pose, buf, bx, ay, az, bx, ay, bz, r, g, b, a)
        line(pose, buf, bx, ay, bz, ax, ay, bz, r, g, b, a); line(pose, buf, ax, ay, bz, ax, ay, az, r, g, b, a)
        line(pose, buf, ax, by, az, bx, by, az, r, g, b, a); line(pose, buf, bx, by, az, bx, by, bz, r, g, b, a)
        line(pose, buf, bx, by, bz, ax, by, bz, r, g, b, a); line(pose, buf, ax, by, bz, ax, by, az, r, g, b, a)
        line(pose, buf, ax, ay, az, ax, by, az, r, g, b, a); line(pose, buf, bx, ay, az, bx, by, az, r, g, b, a)
        line(pose, buf, bx, ay, bz, bx, by, bz, r, g, b, a); line(pose, buf, ax, ay, bz, ax, by, bz, r, g, b, a)
    }

    private fun line(
        pose: PoseStack, buf: VertexConsumer,
        x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val m = pose.last().pose()
        val n = Vec3(x2 - x1, y2 - y1, z2 - z1).normalize()
        buf.addVertex(m, x1.toFloat(), y1.toFloat(), z1.toFloat()).setColor(r, g, b, a)
            .setNormal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat()).setLineWidth(2f)
        buf.addVertex(m, x2.toFloat(), y2.toFloat(), z2.toFloat()).setColor(r, g, b, a)
            .setNormal(n.x.toFloat(), n.y.toFloat(), n.z.toFloat()).setLineWidth(2f)
    }
}
