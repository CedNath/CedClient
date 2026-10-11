package ced.cedclient.features.impl.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import ced.cedclient.render.pipeline.CustomRenderType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object EntityESPRenderer {

    fun register() {
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            // Labels are drawn on the HUD later this frame; project them now, while this frame's camera is known.
            EntityESPLabels.capture(context.levelState().cameraRenderState)

            if (!EntityESP.isEnabled) return@AfterSolidFeatures
            if (!EntityESP.boxesEnabled && !EntityESP.tracersEnabled) return@AfterSolidFeatures

            val poseStack = context.poseStack()
            val camera = context.gameRenderer().mainCamera
            val cameraPos = camera.position()
            val bufferSource = context.bufferSource()

            val lineBuffer = bufferSource.getBuffer(CustomRenderType.LINES_ESP)

            for (scanned in EntityESP.scannedEntities) {
                val entity = scanned.entity
                val custom = scanned.style.color
                val (r, g, b) = if (custom != null) Triple(custom.redFloat, custom.greenFloat, custom.blueFloat) else colorFor(scanned.category)

                if (EntityESP.boxesEnabled && scanned.style.box) {
                    val box: AABB = entity.boundingBox.inflate(0.05).move(-cameraPos.x, -cameraPos.y, -cameraPos.z)
                    drawLineBox(poseStack, lineBuffer, box, r, g, b)
                }

                if (EntityESP.tracersEnabled && scanned.style.tracer) {
                    val direction = Vec3.directionFromRotation(camera.xRot(), camera.yRot())
                    val targetPos: Vec3 = entity.position().add(0.0, entity.bbHeight / 2.0, 0.0)
                    drawLine(
                        poseStack,
                        lineBuffer,
                        direction,
                        targetPos.subtract(cameraPos),
                        r, g, b
                    )
                }
            }

            bufferSource.endBatch()
        })
    }


    /**
     * Manual replacement for the now-removed LevelRenderer.renderLineBox.
     * Draws the 12 edges of an AABB using the same buffer/vertex pattern as drawLine.
     */
    private fun drawLineBox(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        box: AABB,
        r: Float, g: Float, b: Float
    ) {
        val minX = box.minX; val minY = box.minY; val minZ = box.minZ
        val maxX = box.maxX; val maxY = box.maxY; val maxZ = box.maxZ

        // Bottom face
        edge(poseStack, buffer, minX, minY, minZ, maxX, minY, minZ, r, g, b)
        edge(poseStack, buffer, maxX, minY, minZ, maxX, minY, maxZ, r, g, b)
        edge(poseStack, buffer, maxX, minY, maxZ, minX, minY, maxZ, r, g, b)
        edge(poseStack, buffer, minX, minY, maxZ, minX, minY, minZ, r, g, b)

        // Top face
        edge(poseStack, buffer, minX, maxY, minZ, maxX, maxY, minZ, r, g, b)
        edge(poseStack, buffer, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b)
        edge(poseStack, buffer, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b)
        edge(poseStack, buffer, minX, maxY, maxZ, minX, maxY, minZ, r, g, b)

        // Vertical edges
        edge(poseStack, buffer, minX, minY, minZ, minX, maxY, minZ, r, g, b)
        edge(poseStack, buffer, maxX, minY, minZ, maxX, maxY, minZ, r, g, b)
        edge(poseStack, buffer, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b)
        edge(poseStack, buffer, minX, minY, maxZ, minX, maxY, maxZ, r, g, b)
    }

    private fun edge(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float
    ) {
        drawLine(poseStack, buffer, Vec3(x1, y1, z1), Vec3(x2, y2, z2), r, g, b)
    }

    private fun drawLine(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        from: Vec3,
        to: Vec3,
        r: Float, g: Float, b: Float
    ) {
        val pose = poseStack.last().pose()
        val normal = to.subtract(from).normalize()

        buffer.addVertex(pose, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
            .setColor(r, g, b, 1.0f)
            .setNormal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .setLineWidth(2.0f)

        buffer.addVertex(pose, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
            .setColor(r, g, b, 1.0f)
            .setNormal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .setLineWidth(2.0f)
    }

    private fun colorFor(category: MobCategoryType): Triple<Float, Float, Float> = when (category) {
        MobCategoryType.HOSTILE -> Triple(1.0f, 0.35f, 0.35f)
        MobCategoryType.PASSIVE -> Triple(0.35f, 1.0f, 0.35f)
        MobCategoryType.PLAYER -> Triple(0.35f, 0.6f, 1.0f)
    }
}
