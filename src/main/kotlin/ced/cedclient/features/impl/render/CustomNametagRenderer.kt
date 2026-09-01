package ced.cedclient.features.impl.render

import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * Draws CustomNametag's floating tags in world space -- ported straight
 * from EntityESPRenderer.submitLabel's pattern (poseStack translated to the
 * entity, then submitNodeCollector.submitNameTag draws billboarded text
 * above it), just with different source data per player:
 *
 *  - the real CedNath (matched by GameProfile name, same check
 *    PlayerRendererMixin uses for its hardcoded scale target) always gets
 *    CustomNametag.HARDCODED_TAG, regardless of the module toggle.
 *  - the local player gets CustomNametag.tagText.value, only while the
 *    module is enabled and the field isn't blank.
 *
 * Both can be true for the same render pass without conflict, since they
 * key off different entities (unless CedNath IS the local player, in which
 * case the hardcoded tag wins -- see the `when` order below).
 */
object CustomNametagRenderer {

    fun register() {
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            val mc = Minecraft.getInstance()
            val level = mc.level ?: return@AfterSolidFeatures
            val localPlayer = mc.player

            val moduleEnabled = CustomNametag.isEnabled
            val ownTag = CustomNametag.tagText.value

            val poseStack = context.poseStack()
            val camera = context.gameRenderer().mainCamera
            val cameraPos = camera.position()
            val submitNodeCollector = context.submitNodeCollector()
            val cameraRenderState = context.levelState().cameraRenderState

            for (entity in level.entitiesForRendering()) {
                val player = entity as? Player ?: continue

                val label: Component = when {
                    player.gameProfile.name.equals(CustomNametag.HARDCODED_USERNAME, ignoreCase = true) ->
                        Component.literal(CustomNametag.HARDCODED_TAG)

                    player === localPlayer && moduleEnabled && ownTag.isNotBlank() ->
                        Component.literal(ownTag)

                    else -> continue
                }

                submitTag(poseStack, submitNodeCollector, player, label, cameraPos, cameraRenderState)
            }
        })
    }

    private fun submitTag(
        poseStack: PoseStack,
        submitNodeCollector: SubmitNodeCollector,
        entity: Player,
        label: Component,
        cameraPos: Vec3,
        cameraRenderState: CameraRenderState
    ) {
        poseStack.pushPose()
        poseStack.translate(
            (entity.position().x - cameraPos.x).toFloat(),
            (entity.position().y - cameraPos.y).toFloat(),
            (entity.position().z - cameraPos.z).toFloat()
        )

        // A bit above the vanilla nametag height so it doesn't overlap the
        // real one when the player also has a visible name above their head.
        val attachment = Vec3(0.0, entity.bbHeight + 0.7, 0.0)
        val lightCoords = 0xF000F0 // fullbright, same as EntityESPRenderer's labels

        submitNodeCollector.submitNameTag(
            poseStack,
            attachment,
            0,
            label,
            true, // seeThrough
            lightCoords,
            4096.0, // maxDistanceSq -- generous flat cap, this isn't distance-scanned like EntityESP
            cameraRenderState
        )

        poseStack.popPose()
    }
}