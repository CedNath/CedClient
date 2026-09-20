package ced.cedclient.features.impl.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import ced.cedclient.render.pipeline.CustomRenderType
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Same structure as EntityESPRenderer (see that file), except box and
 * tracer color are independently user-configurable via BlockESP's
 * boxColor/tracerColor ColorSettings instead of one fixed accent -- blocks
 * don't have hostile/passive/player categories to color by, so there was
 * nothing else worth keying color off of.
 */
object BlockESPRenderer {

    fun register() {
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (!BlockESP.isEnabled) return@AfterSolidFeatures
            if (!BlockESP.boxesEnabled && !BlockESP.tracersEnabled && !BlockESP.labelsEnabled) return@AfterSolidFeatures

            val poseStack = context.poseStack()
            val camera = context.gameRenderer().mainCamera
            val cameraPos = camera.position()
            val bufferSource = context.bufferSource()
            val submitNodeCollector = context.submitNodeCollector()
            val cameraRenderState = context.levelState().cameraRenderState

            val lineBuffer = bufferSource.getBuffer(CustomRenderType.LINES_ESP)

            for (scanned in BlockESP.scannedBlocks) {
                val pos = scanned.pos

                if (BlockESP.boxesEnabled) {
                    val boxColor = BlockESP.boxColorValue
                    val box: AABB = AABB(pos).inflate(0.02).move(-cameraPos.x, -cameraPos.y, -cameraPos.z)
                    drawLineBox(
                        poseStack, lineBuffer, box,
                        boxColor.redFloat, boxColor.greenFloat, boxColor.blueFloat, boxColor.alphaFloat
                    )
                }

                if (BlockESP.tracersEnabled) {
                    val tracerColor = BlockESP.tracerColorValue
                    val direction = Vec3.directionFromRotation(camera.xRot(), camera.yRot())
                    val targetPos = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                    drawLine(
                        poseStack,
                        lineBuffer,
                        direction,
                        targetPos.subtract(cameraPos),
                        tracerColor.redFloat, tracerColor.greenFloat, tracerColor.blueFloat, tracerColor.alphaFloat
                    )
                }

                if (BlockESP.labelsEnabled) {
                    submitLabel(poseStack, submitNodeCollector, scanned, cameraPos, cameraRenderState)
                }
            }

            bufferSource.endBatch()
        })
    }

    private fun submitLabel(
        poseStack: PoseStack,
        submitNodeCollector: net.minecraft.client.renderer.SubmitNodeCollector,
        scanned: ScannedBlock,
        cameraPos: Vec3,
        cameraRenderState: net.minecraft.client.renderer.state.level.CameraRenderState
    ) {
        val namePart = Component.literal(scanned.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
        val distPart = Component.literal(" %.0fm".format(scanned.distance)).withStyle(ChatFormatting.YELLOW)
        val label: Component = namePart.append(distPart)

        val pos = scanned.pos
        poseStack.pushPose()
        poseStack.translate(
            (pos.x + 0.5 - cameraPos.x).toFloat(),
            (pos.y + 1.2 - cameraPos.y).toFloat(),
            (pos.z + 0.5 - cameraPos.z).toFloat()
        )

        val attachment = Vec3.ZERO
        val lightCoords = 0xF000F0 // fullbright so labels are always legible
        val maxDistanceSq = maxOf(64.0, scanned.distance * scanned.distance * 4.0)

        submitNodeCollector.submitNameTag(
            poseStack,
            attachment,
            0,
            label,
            true, // seeThrough - show through walls
            lightCoords,
            maxDistanceSq,
            cameraRenderState
        )

        poseStack.popPose()
    }

    /** Same manual AABB-edge-drawing approach as EntityESPRenderer.drawLineBox. */
    private fun drawLineBox(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        box: AABB,
        r: Float, g: Float, b: Float, a: Float
    ) {
        val minX = box.minX; val minY = box.minY; val minZ = box.minZ
        val maxX = box.maxX; val maxY = box.maxY; val maxZ = box.maxZ

        // Bottom face
        edge(poseStack, buffer, minX, minY, minZ, maxX, minY, minZ, r, g, b, a)
        edge(poseStack, buffer, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a)
        edge(poseStack, buffer, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a)
        edge(poseStack, buffer, minX, minY, maxZ, minX, minY, minZ, r, g, b, a)

        // Top face
        edge(poseStack, buffer, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a)
        edge(poseStack, buffer, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a)
        edge(poseStack, buffer, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a)
        edge(poseStack, buffer, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a)

        // Vertical edges
        edge(poseStack, buffer, minX, minY, minZ, minX, maxY, minZ, r, g, b, a)
        edge(poseStack, buffer, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a)
        edge(poseStack, buffer, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a)
        edge(poseStack, buffer, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a)
    }

    private fun edge(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float, a: Float
    ) {
        drawLine(poseStack, buffer, Vec3(x1, y1, z1), Vec3(x2, y2, z2), r, g, b, a)
    }

    private fun drawLine(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        from: Vec3,
        to: Vec3,
        r: Float, g: Float, b: Float, a: Float = 1.0f
    ) {
        val pose = poseStack.last().pose()
        val normal = to.subtract(from).normalize()

        buffer.addVertex(pose, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
            .setColor(r, g, b, a)
            .setNormal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .setLineWidth(2.0f)

        buffer.addVertex(pose, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
            .setColor(r, g, b, a)
            .setNormal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .setLineWidth(2.0f)
    }
}