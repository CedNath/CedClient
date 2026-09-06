package cedclient.mixin;

import ced.cedclient.features.impl.funqol.PlayerScale;
import ced.cedclient.features.impl.render.HardcodedCosmetics;
import ced.cedclient.utils.NametagFormatting;
import ced.cedclient.utils.NametagOverride;
import ced.cedclient.sync.CosmeticOverride;
import ced.cedclient.sync.CosmeticsSync;
import cedclient.accessor.CedClientPlayerRenderStateAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class PlayerRendererMixin {

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL")
    )
    private void cedclient$tagTargetPlayer(
            Avatar entity,
            AvatarRenderState state,
            float partialTick,
            CallbackInfo ci
    ) {
        CedClientPlayerRenderStateAccessor accessor =
                (CedClientPlayerRenderStateAccessor) state;

        CosmeticOverride override = null;

        if (HardcodedCosmetics.INSTANCE.isEnabled()
                && entity instanceof Player player) {
            override = CosmeticsSync.INSTANCE.getOverride(
                    player.getGameProfile().name()
            );
        }

        boolean isHardcodedTarget = override != null;

        if (isHardcodedTarget) {
            accessor.cedclient$setSyncedScale(
                    override.getScaleX(),
                    override.getScaleY(),
                    override.getScaleZ()
            );
        }

        boolean isSelf =
                !isHardcodedTarget
                        && Minecraft.getInstance().player != null
                        && entity == Minecraft.getInstance().player;

        accessor.cedclient$setHardcodedTarget(isHardcodedTarget);
        accessor.cedclient$setSelf(isSelf);

        /*
         * Nametag position comes from state.attachments (EntityAttachments),
         * which vanilla computes from the entity's real, UNSCALED height --
         * PlayerScale/the synced cosmetic scale only ever touch the render
         * geometry via poseStack.scale() in cedclient$applyPlayerScale below,
         * never the entity's actual dimensions. Without this, a bigger model
         * leaves the nametag anchored down around its original (now much
         * lower relative) head height -- looks like it's floating at the
         * legs -- and a smaller model leaves it floating too high above the
         * now-shrunk head.
         *
         * EntityAttachments.scale(float) is the same vanilla method used to
         * reposition baby-mob nametags correctly, so this reuses real
         * vanilla behavior rather than reimplementing the math. Only the
         * Y-relevant scale matters here since attachments are a height
         * offset; per-axis X/Z scale doesn't affect nametag height.
         *
         * NOTE: verify `attachments` is still the field name on
         * AvatarRenderState / EntityRenderState for this mapping -- same
         * caveat as nameTag below.
         */
        float scaleY = 1.0F;
        if (isHardcodedTarget) {
            scaleY = override.getScaleY();
        } else if (isSelf && PlayerScale.INSTANCE.isEnabled()) {
            scaleY = PlayerScale.INSTANCE.getScaleFactorY();
        }
        if (scaleY != 1.0F && state.nameTagAttachment != null) {
            net.minecraft.world.phys.Vec3 v = state.nameTagAttachment;
            state.nameTagAttachment = new net.minecraft.world.phys.Vec3(v.x, v.y * scaleY, v.z);
        }

        /*
         * Overwrite the render state's own nametag Component when an
         * override is active, instead of suppressing vanilla's nametag and
         * submitting a parallel one. Vanilla's submitNameDisplay() then
         * renders our text through its own pipeline, so we get its
         * attachment-point math, distance culling, sneak-hide, and team
         * prefix/suffix handling for free instead of reimplementing them.
         *
         * We grab vanilla's own computed nametag text BEFORE overwriting
         * it, and pass it into NametagOverride as a fallback lookup key --
         * on servers that fake the entity's GameProfile name (Hypixel
         * SkyBlock does this; see NametagOverride's doc comment), a direct
         * name-based CosmeticsSync lookup misses, but the real IGN is
         * still present as literal text in vanilla's rendered component.
         */
        if (entity instanceof Player player) {
            String originalNameTagText = state.nameTag != null ? state.nameTag.getString() : null;
            String overrideText = NametagOverride.INSTANCE.activeTagFor(player, originalNameTagText);
            if (overrideText != null) {
                // NOTE: verify this field name against your AvatarRenderState /
                // EntityRenderState mappings -- it's the Component vanilla's
                // submitNameDisplay() reads to draw the floating tag.
                state.nameTag = NametagFormatting.INSTANCE.parse(overrideText);
            }
        }
    }

    @Inject(
            method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("TAIL")
    )
    private void cedclient$applyPlayerScale(
            AvatarRenderState state,
            PoseStack poseStack,
            CallbackInfo ci
    ) {
        CedClientPlayerRenderStateAccessor accessor =
                (CedClientPlayerRenderStateAccessor) state;

        /*
         * Synced cosmetic scale takes priority.
         */
        if (accessor.cedclient$isHardcodedTarget()) {
            poseStack.scale(
                    accessor.cedclient$getSyncedScaleX(),
                    accessor.cedclient$getSyncedScaleY(),
                    accessor.cedclient$getSyncedScaleZ()
            );
            return;
        }

        /*
         * Only scale the local player's model with PlayerScale.
         */
        if (!PlayerScale.INSTANCE.isEnabled()
                || !accessor.cedclient$isSelf()) {
            return;
        }

        float scaleX = PlayerScale.INSTANCE.getScaleFactorX();
        float scaleY = PlayerScale.INSTANCE.getScaleFactorY();
        float scaleZ = PlayerScale.INSTANCE.getScaleFactorZ();

        if (scaleX != 1.0F
                || scaleY != 1.0F
                || scaleZ != 1.0F) {
            poseStack.scale(scaleX, scaleY, scaleZ);
        }
    }
}