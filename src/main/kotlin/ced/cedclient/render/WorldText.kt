package ced.cedclient.render

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3

/** Text floating at a world position, drawn through walls with the name tag renderer (so it always faces you). */
object WorldText {
    fun draw(context: LevelRenderContext, at: Vec3, text: Component) {
        val player = Minecraft.getInstance().player ?: return
        val cam = context.gameRenderer().mainCamera().position()
        val dist = Math.sqrt(player.distanceToSqr(at.x, at.y, at.z))
        val poseStack = context.poseStack()
        poseStack.pushPose()
        poseStack.translate((at.x - cam.x).toFloat(), (at.y - cam.y).toFloat(), (at.z - cam.z).toFloat())
        context.submitNodeCollector().submitNameTag(poseStack, Vec3.ZERO, 0, text, true, 0xF000F0, context.levelState().cameraRenderState)
        poseStack.popPose()
    }
}
