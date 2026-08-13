package ced.cedclient.features.impl.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import ced.cedclient.utils.render.CustomRenderType
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object EntityESPRenderer {

    fun register() {
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (!EntityESP.isEnabled) return@AfterSolidFeatures
            if (!EntityESP.boxesEnabled && !EntityESP.tracersEnabled && !EntityESP.labelsEnabled) return@AfterSolidFeatures

            val poseStack = context.poseStack()
            val camera = context.gameRenderer().mainCamera
            val cameraPos = camera.position()
            val bufferSource = context.bufferSource()
            val submitNodeCollector = context.submitNodeCollector()
            val cameraRenderState = context.levelState().cameraRenderState

            val lineBuffer = bufferSource.getBuffer(CustomRenderType.LINES_ESP)

            for (scanned in EntityESP.scannedEntities) {
                val entity = scanned.entity
                val (r, g, b) = colorFor(scanned.category)

                if (EntityESP.boxesEnabled) {
                    val box: AABB = entity.boundingBox.inflate(0.05).move(-cameraPos.x, -cameraPos.y, -cameraPos.z)
                    drawLineBox(poseStack, lineBuffer, box, r, g, b)
                }

                if (EntityESP.tracersEnabled) {
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

                if (EntityESP.labelsEnabled) {
                    submitLabel(poseStack, submitNodeCollector, entity, scanned, cameraPos, cameraRenderState)
                }
            }

            bufferSource.endBatch()
        })
    }
    private fun submitLabel(
        poseStack: PoseStack,
        submitNodeCollector: net.minecraft.client.renderer.SubmitNodeCollector,
        entity: net.minecraft.world.entity.LivingEntity,
        scanned: ScannedEntity,
        cameraPos: Vec3,
        cameraRenderState: net.minecraft.client.renderer.state.level.CameraRenderState
    ) {
        // Name color by category, bold for visibility
        val nameFormatting = when (scanned.category) {
            MobCategoryType.HOSTILE -> ChatFormatting.RED
            MobCategoryType.PASSIVE -> ChatFormatting.GREEN
            MobCategoryType.PLAYER -> ChatFormatting.AQUA
        }

        // Health color based on percent: green -> yellow -> red
        val hpPercent = if (entity.maxHealth > 0f) (entity.health / entity.maxHealth) else 0f
        val hpFormatting = when {
            hpPercent >= 0.66f -> ChatFormatting.GREEN
            hpPercent >= 0.33f -> ChatFormatting.GOLD
            else -> ChatFormatting.RED
        }

        // Distance color: bright yellow for visibility
        val distFormatting = ChatFormatting.YELLOW

        // Build components with bright colors and bold name
        val namePart = Component.literal(scanned.name).withStyle(nameFormatting, ChatFormatting.BOLD)
        val hpPart = Component.literal(" %.0f/%.0fHP".format(entity.health, entity.maxHealth)).withStyle(hpFormatting)
        val distPart = Component.literal(" %.0fm".format(scanned.distance)).withStyle(distFormatting)

        val label: Component = namePart.append(hpPart).append(distPart)

        // Position the label above the entity, relative to camera
        poseStack.pushPose()
        poseStack.translate(
            (entity.position().x - cameraPos.x).toFloat(),
            (entity.position().y - cameraPos.y).toFloat(),
            (entity.position().z - cameraPos.z).toFloat()
        )

        val attachment = Vec3(0.0, entity.bbHeight + 0.5, 0.0)
        val lightCoords = 0xF000F0 // fullbright so labels are always legible

        // Increase max distance so labels remain visible from farther away.
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
