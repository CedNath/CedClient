package ced.cedclient.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.world.phys.AABB

/**
 * Solid box: six faces as quads. [box] is already camera-relative. Pair with CustomRenderType.FILLED
 * (depth-tested, translucent, both sides drawn), and use a see-through alpha so whatever is inside stays visible.
 */
object FilledBox {

    fun draw(pose: PoseStack.Pose, buffer: VertexConsumer, box: AABB, r: Float, g: Float, b: Float, a: Float) {
        val m = pose.pose()
        val x0 = box.minX.toFloat(); val y0 = box.minY.toFloat(); val z0 = box.minZ.toFloat()
        val x1 = box.maxX.toFloat(); val y1 = box.maxY.toFloat(); val z1 = box.maxZ.toFloat()
        fun v(x: Float, y: Float, z: Float) { buffer.addVertex(m, x, y, z).setColor(r, g, b, a) }
        // bottom, top
        v(x0, y0, z0); v(x1, y0, z0); v(x1, y0, z1); v(x0, y0, z1)
        v(x0, y1, z0); v(x0, y1, z1); v(x1, y1, z1); v(x1, y1, z0)
        // north, south
        v(x0, y0, z0); v(x0, y1, z0); v(x1, y1, z0); v(x1, y0, z0)
        v(x0, y0, z1); v(x1, y0, z1); v(x1, y1, z1); v(x0, y1, z1)
        // west, east
        v(x0, y0, z0); v(x0, y0, z1); v(x0, y1, z1); v(x0, y1, z0)
        v(x1, y0, z0); v(x1, y1, z0); v(x1, y1, z1); v(x1, y0, z1)
    }
}
