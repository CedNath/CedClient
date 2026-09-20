package ced.cedclient.features.impl.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import ced.cedclient.render.pipeline.CustomRenderType
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object BlockESPRenderer {

    fun register() {
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (!BlockESP.isEnabled) return@AfterSolidFeatures
            if (!BlockESP.boxesEnabled && !BlockESP.tracersEnabled && !BlockESP.labelsEnabled) return@AfterSolidFeatures

            val poseStack = context.poseStack()
            val camera = context.gameRenderer().mainCamera()
            val cameraPos = camera.position()
            val submitNodeCollector = context.submitNodeCollector()
            val cameraRenderState = context.levelState().cameraRenderState

            val boxColor = BlockESP.boxColorValue
            val tracerColor = BlockESP.tracerColorValue
            // TODO: adjust field names (red/green/blue) if Color exposes them differently.
            val br = boxColor.red / 255f; val bg = boxColor.green / 255f; val bb = boxColor.blue / 255f
            val tr = tracerColor.red / 255f; val tg = tracerColor.green / 255f; val tb = tracerColor.blue / 255f

            submitNodeCollector.submitCustomGeometry(poseStack, CustomRenderType.LINES_ESP) { pose, lineBuffer ->
                for (scanned in BlockESP.scannedBlocks) {
                    val pos = scanned.pos

                    if (BlockESP.boxesEnabled) {
                        val box = AABB(
                            pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(),
                            pos.x + 1.0, pos.y + 1.0, pos.z + 1.0
                        ).move(-cameraPos.x, -cameraPos.y, -cameraPos.z)
                        drawLineBox(pose, lineBuffer, box, br, bg, bb)
                    }

                    if (BlockESP.tracersEnabled) {
                        val direction = Vec3.directionFromRotation(camera.xRot(), camera.yRot())
                        val targetPos = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
                        drawLine(pose, lineBuffer, direction, targetPos.subtract(cameraPos), tr, tg, tb)
                    }
                }
            }

            if (BlockESP.labelsEnabled) {
                for (scanned in BlockESP.scannedBlocks) {
                    submitLabel(poseStack, submitNodeCollector, scanned, cameraPos, cameraRenderState)
                }
            }
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
        val lightCoords = 0xF000F0

        submitNodeCollector.submitNameTag(
            poseStack, attachment, 0, label, true, lightCoords, cameraRenderState
        )

        poseStack.popPose()
    }

    private fun drawLineBox(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        box: AABB,
        r: Float, g: Float, b: Float, a: Float = 1.0f
    ) {
        val minX = box.minX; val minY = box.minY; val minZ = box.minZ
        val maxX = box.maxX; val maxY = box.maxY; val maxZ = box.maxZ

        edge(pose, buffer, minX, minY, minZ, maxX, minY, minZ, r, g, b, a)
        edge(pose, buffer, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a)
        edge(pose, buffer, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a)
        edge(pose, buffer, minX, minY, maxZ, minX, minY, minZ, r, g, b, a)

        edge(pose, buffer, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a)
        edge(pose, buffer, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a)
        edge(pose, buffer, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a)
        edge(pose, buffer, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a)

        edge(pose, buffer, minX, minY, minZ, minX, maxY, minZ, r, g, b, a)
        edge(pose, buffer, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a)
        edge(pose, buffer, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a)
        edge(pose, buffer, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a)
    }

    private fun edge(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float, a: Float = 1.0f
    ) {
        drawLine(pose, buffer, Vec3(x1, y1, z1), Vec3(x2, y2, z2), r, g, b, a)
    }

    private fun drawLine(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        from: Vec3,
        to: Vec3,
        r: Float, g: Float, b: Float, a: Float = 1.0f
    ) {
        val matrix = pose.pose()
        val normal = to.subtract(from).normalize()

        buffer.addVertex(matrix, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
            .setColor(r, g, b, a)
            .setNormal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .setLineWidth(2.0f)

        buffer.addVertex(matrix, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
            .setColor(r, g, b, a)
            .setNormal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .setLineWidth(2.0f)
    }
}