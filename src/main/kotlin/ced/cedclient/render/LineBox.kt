package ced.cedclient.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Outlined box from 12 line segments, same manual AABB-edge approach as BlockESPRenderer / EntityESPRenderer.
 * [box] is already camera-relative. Pair with CustomRenderType.LINES_ESP (through walls) or LINES_DEPTH.
 */
object LineBox {

    fun draw(poseStack: PoseStack, buffer: VertexConsumer, box: AABB, r: Float, g: Float, b: Float, a: Float) {
        val minX = box.minX; val minY = box.minY; val minZ = box.minZ
        val maxX = box.maxX; val maxY = box.maxY; val maxZ = box.maxZ

        // Bottom face
        line(poseStack, buffer, minX, minY, minZ, maxX, minY, minZ, r, g, b, a)
        line(poseStack, buffer, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a)
        line(poseStack, buffer, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a)
        line(poseStack, buffer, minX, minY, maxZ, minX, minY, minZ, r, g, b, a)

        // Top face
        line(poseStack, buffer, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a)
        line(poseStack, buffer, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a)
        line(poseStack, buffer, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a)
        line(poseStack, buffer, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a)

        // Vertical edges
        line(poseStack, buffer, minX, minY, minZ, minX, maxY, minZ, r, g, b, a)
        line(poseStack, buffer, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a)
        line(poseStack, buffer, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a)
        line(poseStack, buffer, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a)
    }

    /** Connected line through [points] (camera-relative), e.g. a walk path. */
    fun polyline(poseStack: PoseStack, buffer: VertexConsumer, points: List<Vec3>, r: Float, g: Float, b: Float, a: Float) {
        for (i in 0 until points.size - 1) {
            val p = points[i]; val q = points[i + 1]
            line(poseStack, buffer, p.x, p.y, p.z, q.x, q.y, q.z, r, g, b, a)
        }
    }

    private fun line(
        poseStack: PoseStack, buffer: VertexConsumer,
        x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        val pose = poseStack.last().pose()
        val normal = Vec3(x2 - x1, y2 - y1, z2 - z1).normalize()
        buffer.addVertex(pose, x1.toFloat(), y1.toFloat(), z1.toFloat())
            .setColor(r, g, b, a)
            .setNormal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .setLineWidth(2.0f)
        buffer.addVertex(pose, x2.toFloat(), y2.toFloat(), z2.toFloat())
            .setColor(r, g, b, a)
            .setNormal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .setLineWidth(2.0f)
    }
}
