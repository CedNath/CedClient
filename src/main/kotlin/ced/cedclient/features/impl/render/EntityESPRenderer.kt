package ced.cedclient.features.impl.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import ced.cedclient.render.pipeline.CustomRenderType
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

object EntityESPRenderer {

    fun register() {
        // 26.2: MultiBufferSource/BufferSource are gone, so the lines are now submitted
        // through the SubmitNodeCollector (submitCustomGeometry) instead of written into a
        // buffer we flush ourselves. That has to happen in COLLECT_SUBMITS: it runs before
        // the feature passes are drawn, whereas AFTER_SOLID_FEATURES fires once the solid
        // pass is already finished, so anything submitted there would never be drawn.
        LevelRenderEvents.COLLECT_SUBMITS.register { context ->
            if (!EntityESP.isEnabled) return@register
            if (!EntityESP.boxesEnabled && !EntityESP.tracersEnabled && !EntityESP.labelsEnabled) return@register

            val poseStack = context.poseStack()
            val camera = context.gameRenderer().mainCamera()
            val cameraPos = camera.position()
            val submitNodeCollector = context.submitNodeCollector()
            val cameraRenderState = context.levelState().cameraRenderState

            // The geometry callback below runs later, during the draw pass -- iterate a snapshot.
            val entities = EntityESP.scannedEntities.toList()

            if (EntityESP.boxesEnabled || EntityESP.tracersEnabled) {
                // The callback hands back a PoseStack.Pose (one frozen transform), not a PoseStack.
                submitNodeCollector.submitCustomGeometry(poseStack, CustomRenderType.LINES_ESP) { pose, lineBuffer ->
                    for (scanned in entities) {
                        val entity = scanned.entity
                        val (r, g, b) = colorFor(scanned.category)

                        if (EntityESP.boxesEnabled) {
                            val box: AABB = entity.boundingBox.inflate(0.05).move(-cameraPos.x, -cameraPos.y, -cameraPos.z)
                            drawLineBox(pose, lineBuffer, box, r, g, b)
                        }

                        if (EntityESP.tracersEnabled) {
                            val direction = Vec3.directionFromRotation(camera.xRot(), camera.yRot())
                            val targetPos: Vec3 = entity.position().add(0.0, entity.bbHeight / 2.0, 0.0)
                            drawLine(
                                pose,
                                lineBuffer,
                                direction,
                                targetPos.subtract(cameraPos),
                                r, g, b
                            )
                        }
                    }
                }
            }

            if (EntityESP.labelsEnabled) {
                for (scanned in entities) {
                    submitLabel(poseStack, submitNodeCollector, scanned.entity, scanned, cameraPos, cameraRenderState)
                }
            }
        }
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

        // 26.2: submitNameTag no longer takes a max-distance argument (the old
        // "labels stay visible from farther away" squared-distance tweak is gone with it).
        submitNodeCollector.submitNameTag(
            poseStack,
            attachment,
            0,
            label,
            true, // seeThrough - show through walls
            lightCoords,
            cameraRenderState
        )

        poseStack.popPose()
    }


    /**
     * Manual replacement for the now-removed LevelRenderer.renderLineBox.
     * Draws the 12 edges of an AABB using the same buffer/vertex pattern as drawLine.
     */
    private fun drawLineBox(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        box: AABB,
        r: Float, g: Float, b: Float
    ) {
        val minX = box.minX; val minY = box.minY; val minZ = box.minZ
        val maxX = box.maxX; val maxY = box.maxY; val maxZ = box.maxZ

        // Bottom face
        edge(pose, buffer, minX, minY, minZ, maxX, minY, minZ, r, g, b)
        edge(pose, buffer, maxX, minY, minZ, maxX, minY, maxZ, r, g, b)
        edge(pose, buffer, maxX, minY, maxZ, minX, minY, maxZ, r, g, b)
        edge(pose, buffer, minX, minY, maxZ, minX, minY, minZ, r, g, b)

        // Top face
        edge(pose, buffer, minX, maxY, minZ, maxX, maxY, minZ, r, g, b)
        edge(pose, buffer, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b)
        edge(pose, buffer, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b)
        edge(pose, buffer, minX, maxY, maxZ, minX, maxY, minZ, r, g, b)

        // Vertical edges
        edge(pose, buffer, minX, minY, minZ, minX, maxY, minZ, r, g, b)
        edge(pose, buffer, maxX, minY, minZ, maxX, maxY, minZ, r, g, b)
        edge(pose, buffer, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b)
        edge(pose, buffer, minX, minY, maxZ, minX, maxY, maxZ, r, g, b)
    }

    private fun edge(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double,
        r: Float, g: Float, b: Float
    ) {
        drawLine(pose, buffer, Vec3(x1, y1, z1), Vec3(x2, y2, z2), r, g, b)
    }

    private fun drawLine(
        pose: PoseStack.Pose,
        buffer: VertexConsumer,
        from: Vec3,
        to: Vec3,
        r: Float, g: Float, b: Float
    ) {
        val matrix = pose.pose()
        val normal = to.subtract(from).normalize()

        buffer.addVertex(matrix, from.x.toFloat(), from.y.toFloat(), from.z.toFloat())
            .setColor(r, g, b, 1.0f)
            .setNormal(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())
            .setLineWidth(2.0f)

        buffer.addVertex(matrix, to.x.toFloat(), to.y.toFloat(), to.z.toFloat())
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
