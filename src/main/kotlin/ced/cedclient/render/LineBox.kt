package ced.cedclient.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Outlined box from 12 line segments, same manual AABB-edge approach as BlockESPRenderer / EntityESPRenderer.
 * [box] is already camera-relative. 26.2: draw inside SubmitNodeCollector.submitCustomGeometry, which hands over a Pose. Pair with CustomRenderType.LINES_ESP (through walls) or LINES_DEPTH.
 */
object LineBox {

    fun draw(pose: PoseStack.Pose, buffer: VertexConsumer, box: AABB, r: Float, g: Float, b: Float, a: Float) {
        val minX = box.minX; val minY = box.minY; val minZ = box.minZ
        val maxX = box.maxX; val maxY = box.maxY; val maxZ = box.maxZ

        // Bottom face
        line(pose, buffer, minX, minY, minZ, maxX, minY, minZ, r, g, b, a)
        line(pose, buffer, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a)
        line(pose, buffer, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a)
        line(pose, buffer, minX, minY, maxZ, minX, minY, minZ, r, g, b, a)

        // Top face
        line(pose, buffer, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a)
        line(pose, buffer, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a)
        line(pose, buffer, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a)
        line(pose, buffer, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a)

        // Vertical edges
        line(pose, buffer, minX, minY, minZ, minX, maxY, minZ, r, g, b, a)
        line(pose, buffer, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a)
        line(pose, buffer, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a)
        line(pose, buffer, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a)
    }

    private fun line(
        pose: PoseStack.Pose, buffer: VertexConsumer,
        x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        val matrix = pose.pose()
        val normal = Vec3(x2 - x1, y2 - y1, z2 - z1).normalize()
        buffer.addVertex(matrix, x1.toFloat(), y1.toFloat(), z1.toFloat())
            .setColor(r, g, b, a)
            .setNormal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .setLineWidth(2.0f)
        buffer.addVertex(matrix, x2.toFloat(), y2.toFloat(), z2.toFloat())
            .setColor(r, g, b, a)
            .setNormal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .setLineWidth(2.0f)
    }
}
