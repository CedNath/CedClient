package cedclient.mixin;

import cedclient.accessor.CedClientPlayerRenderStateAccessor;
import ced.cedclient.features.impl.funqol.PlayerScale;
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

// Confirmed from decompiled source (26.1.2 / NoRiskClient's custom
// "Avatar" rendering fork -- NOT vanilla's PlayerRenderer/PlayerRenderState,
// which don't exist in this build). AvatarRenderer<AvatarlikeEntity extends
// Avatar & ClientAvatarEntity> erases to Avatar for the generic parameter,
// so that's the bytecode-level type Mixin needs to match -- hence the full
// method descriptors below instead of bare method names (extractRenderState
// has several overloads/bridge methods on this class; the descriptor picks
// out the real one, not a bridge).
@Mixin(AvatarRenderer.class)
public abstract class PlayerRendererMixin {

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("TAIL")
    )
    private void cedclient$tagTargetPlayer(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        CedClientPlayerRenderStateAccessor accessor = (CedClientPlayerRenderStateAccessor) state;

        boolean isHardcodedTarget = entity instanceof Player player
                && PlayerScale.HARDCODED_USERNAME.equalsIgnoreCase(player.getGameProfile().name());


        boolean isSelf = !isHardcodedTarget
                && Minecraft.getInstance().player != null
                && entity == Minecraft.getInstance().player;

        accessor.cedclient$setHardcodedTarget(isHardcodedTarget);
        accessor.cedclient$setSelf(isSelf);
    }

    @Inject(
            method = "scale(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
            at = @At("TAIL")
    )
    private void cedclient$applyPlayerScale(AvatarRenderState state, PoseStack poseStack, CallbackInfo ci) {
        CedClientPlayerRenderStateAccessor accessor = (CedClientPlayerRenderStateAccessor) state;

        if (accessor.cedclient$isHardcodedTarget()) {
            poseStack.scale(PlayerScale.HARDCODED_SCALE_X, PlayerScale.HARDCODED_SCALE_Y, PlayerScale.HARDCODED_SCALE_Z);
            return;
        }

        if (!PlayerScale.INSTANCE.isEnabled() || !accessor.cedclient$isSelf()) return;

        float scaleX = PlayerScale.INSTANCE.getScaleFactorX();
        float scaleY = PlayerScale.INSTANCE.getScaleFactorY();
        float scaleZ = PlayerScale.INSTANCE.getScaleFactorZ();
        if (scaleX != 1.0f || scaleY != 1.0f || scaleZ != 1.0f) {
            poseStack.scale(scaleX, scaleY, scaleZ);
        }
    }
}