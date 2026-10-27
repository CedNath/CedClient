package ced.cedclient.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType

/**
 * Draws custom geometry for [type] through the level's submit collector. The block runs later, when the frame's
 * features are rendered, with a copy of the pose stack's current pose, so nothing in it may touch the pose stack.
 */
fun LevelRenderContext.geometry(type: RenderType, draw: (PoseStack.Pose, VertexConsumer) -> Unit) =
    submitNodeCollector().submitCustomGeometry(poseStack(), type, SubmitNodeCollector.CustomGeometryRenderer { pose, buffer -> draw(pose, buffer) })
