package ced.cedclient.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * 26.2 has no BufferSource: geometry has to go through the SubmitNodeCollector. This keeps the old call shape
 * (`context.bufferSource().getBuffer(type)`, `LineBox.draw(poseStack, buffer, ...)`, `endBatch()`): the draws are
 * recorded and handed to submitCustomGeometry, one call per render type, when [endBatch] runs. Use it from COLLECT_SUBMITS.
 */
class DeferredBuffer(val type: RenderType) {
    val ops = ArrayList<(PoseStack.Pose, VertexConsumer) -> Unit>()
}

class DeferredBatch(private val context: LevelRenderContext) {
    private val buffers = LinkedHashMap<RenderType, DeferredBuffer>()

    fun getBuffer(type: RenderType): DeferredBuffer = buffers.getOrPut(type) { DeferredBuffer(type) }

    fun endBatch() {
        for (buffer in buffers.values) {
            if (buffer.ops.isEmpty()) continue
            val ops = buffer.ops.toList()
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), buffer.type) { pose, consumer -> ops.forEach { it(pose, consumer) } }
        }
        buffers.clear()
    }
}

fun LevelRenderContext.bufferSource() = DeferredBatch(this)

@Suppress("UNUSED_PARAMETER")
fun LineBox.draw(poseStack: PoseStack, buffer: DeferredBuffer, box: AABB, r: Float, g: Float, b: Float, a: Float) {
    buffer.ops += { pose, consumer -> draw(pose, consumer, box, r, g, b, a) }
}

@Suppress("UNUSED_PARAMETER")
fun LineBox.polyline(poseStack: PoseStack, buffer: DeferredBuffer, points: List<Vec3>, r: Float, g: Float, b: Float, a: Float) {
    buffer.ops += { pose, consumer -> polyline(pose, consumer, points, r, g, b, a) }
}

@Suppress("UNUSED_PARAMETER")
fun FilledBox.draw(poseStack: PoseStack, buffer: DeferredBuffer, box: AABB, r: Float, g: Float, b: Float, a: Float) {
    buffer.ops += { pose, consumer -> draw(pose, consumer, box, r, g, b, a) }
}
