package ced.cedclient.features.impl.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import ced.cedclient.utils.render.CustomRenderType
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object AxolotlStandPointRenderer {

    // gold-ish beacon color, distinct from the ESP box colors (red/green/blue)
    private val BEACON_COLOR = Triple(1.0f, 0.85f, 0.2f)
    // dimmer gold for the guide line, so it reads as a path rather than another beacon
    private val GUIDE_LINE_COLOR = Triple(1.0f, 0.85f, 0.2f)

    fun register() {
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (!AxolotlStandPoint.isEnabled) return@AfterSolidFeatures
            val standPoint = AxolotlStandPoint.currentStandPoint ?: return@AfterSolidFeatures

            val mc = net.minecraft.client.Minecraft.getInstance()
            val poseStack = context.poseStack()
            val camera = context.gameRenderer().mainCamera
            val cameraPos = camera.position()
            val bufferSource = context.bufferSource()
            val submitNodeCollector = context.submitNodeCollector()
            val cameraRenderState = context.levelState().cameraRenderState

            val lineBuffer = bufferSource.getBuffer(CustomRenderType.LINES_ESP)
            val (r, g, b) = BEACON_COLOR

            if (AxolotlStandPoint.showGuideLineEnabled) {
                val player = mc.player
                if (player != null) {
                    val (gr, gg, gb) = GUIDE_LINE_COLOR
                    val path = AxolotlStandPoint.currentPath

                    if (path.size >= 2) {
                        // Draw the real route, segment by segment, raised slightly off
                        // the floor so it reads clearly instead of hugging the ground.
                        for (i in 0 until path.size - 1) {
                            val from = path[i].add(0.0, 0.5, 0.0).subtract(cameraPos)
                            val to = path[i + 1].add(0.0, 0.5, 0.0).subtract(cameraPos)
                            drawLine(poseStack, lineBuffer, from, to, gr, gg, gb)
                        }
                    } else {
                        // No route computed (pathfinding off, or none found) — fall
                        // back to a plain straight line so you still get a direction.
                        val from = player.position().add(0.0, player.bbHeight / 2.0, 0.0).subtract(cameraPos)
                        val to = standPoint.add(0.0, 0.5, 0.0).subtract(cameraPos)
                        drawLine(poseStack, lineBuffer, from, to, gr, gg, gb)
                    }
                }
            }

            if (AxolotlStandPoint.showBeaconEnabled) {
                // Simple outline around the actual block you'd stand on — snapped to
                // block coordinates so it lines up with the real block grid.
                val blockX = kotlin.math.floor(standPoint.x)
                val blockY = kotlin.math.floor(standPoint.y)
                val blockZ = kotlin.math.floor(standPoint.z)
                val box = AABB(
                    blockX, blockY, blockZ,
                    blockX + 1.0, blockY + 1.0, blockZ + 1.0
                ).move(-cameraPos.x, -cameraPos.y, -cameraPos.z)
                drawLineBox(poseStack, lineBuffer, box, r, g, b)
            }

            if (AxolotlStandPoint.showHudTextEnabled) {
                submitLabel(poseStack, submitNodeCollector, standPoint, cameraPos, cameraRenderState)
            }

            bufferSource.endBatch()
        })
    }

    private fun submitLabel(
        poseStack: PoseStack,
        submitNodeCollector: net.minecraft.client.renderer.SubmitNodeCollector,
        standPoint: Vec3,
        cameraPos: Vec3,
        cameraRenderState: net.minecraft.client.renderer.state.level.CameraRenderState
    ) {
        val covered = AxolotlStandPoint.coveredCount
        val total = AxolotlStandPoint.totalCount

        val coverageFormatting = when {
            total == 0 -> ChatFormatting.GRAY
            covered == total -> ChatFormatting.GREEN
            covered >= (total * 0.6) -> ChatFormatting.GOLD
            else -> ChatFormatting.RED
        }

        val label: Component = Component.literal("Stand Here").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
            .append(Component.literal(" ($covered/$total)").withStyle(coverageFormatting))

        poseStack.pushPose()
        poseStack.translate(
            (standPoint.x - cameraPos.x).toFloat(),
            (standPoint.y - cameraPos.y).toFloat(),
            (standPoint.z - cameraPos.z).toFloat()
        )

        val attachment = Vec3(0.0, 1.5, 0.0) // just above the block outline
        val lightCoords = 0xF000F0
        val maxDistanceSq = 64.0 * 64.0

        submitNodeCollector.submitNameTag(
            poseStack,
            attachment,
            0,
            label,
            true,
            lightCoords,
            maxDistanceSq,
            cameraRenderState
        )

        poseStack.popPose()
    }

    private fun drawLineBox(
        poseStack: PoseStack,
        buffer: VertexConsumer,
        box: AABB,
        r: Float, g: Float, b: Float
    ) {
        val minX = box.minX; val minY = box.minY; val minZ = box.minZ
        val maxX = box.maxX; val maxY = box.maxY; val maxZ = box.maxZ

        edge(poseStack, buffer, minX, minY, minZ, maxX, minY, minZ, r, g, b)
        edge(poseStack, buffer, maxX, minY, minZ, maxX, minY, maxZ, r, g, b)
        edge(poseStack, buffer, maxX, minY, maxZ, minX, minY, maxZ, r, g, b)
        edge(poseStack, buffer, minX, minY, maxZ, minX, minY, minZ, r, g, b)

        edge(poseStack, buffer, minX, maxY, minZ, maxX, maxY, minZ, r, g, b)
        edge(poseStack, buffer, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b)
        edge(poseStack, buffer, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b)
        edge(poseStack, buffer, minX, maxY, maxZ, minX, maxY, minZ, r, g, b)

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
}