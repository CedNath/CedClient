package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.funqol.PlayerScale;
import ced.cedclient.features.impl.render.HardcodedCosmetics;
import ced.cedclient.features.impl.render.nametag.CustomNametag;
import ced.cedclient.features.impl.render.nametag.CustomNametagText;
import ced.cedclient.state.CosmeticOverride;
import ced.cedclient.state.CosmeticsSync;
import ced.cedclient.accessor.CedClientPlayerRenderStateAccessor;
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

    // How much extra height (in blocks) to lift the nametag per whole unit
    // of scaleY above 1.0 -- e.g. 0.15 means a 2x-scaled player's tag gets
    // an extra 0.15 blocks on top of the linear v.y * scaleY term. Start
    // small and bump it up if big players' tags still sit too low.
    private static final double EXTRA_LIFT_PER_SCALE = 0.25;

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
            // Plain v.y * scaleY tracks the real head-top height correctly
            // for scaleY < 1 (shrunk players place the tag exactly right),
            // but for scaleY > 1 it still lands slightly low -- the model
            // apparently isn't scaling purely around the feet the way pure
            // linear scaling assumes, so a bigger player needs a bit more
            // lift than the linear term alone gives it. Small additive
            // correction, only for scaleY > 1 (zero at 1.0, growing with the
            // excess scale) -- doesn't touch the already-correct shrink case.
            // Tune EXTRA_LIFT_PER_SCALE below if it's still off in-game.
            double extraLift = scaleY > 1.0F ? (scaleY - 1.0F) * EXTRA_LIFT_PER_SCALE : 0.0;
            state.nameTagAttachment = new net.minecraft.world.phys.Vec3(v.x, v.y * scaleY + extraLift, v.z);
        }

        /*
         * Fallback splice for the in-world floating nametag. The primary
         * path is EntityMixin's @Redirect on the this.getName() call
         * inside Entity#getDisplayName() (see cedclient$overrideNameForDisplayName /
         * CustomNametagText.overrideBareName()), which runs upstream of
         * team prefix/suffix wrapping -- by the time super's (vanilla)
         * extractRenderState() builds state.nameTag below, that override
         * is normally already baked in and nothing further is needed.
         *
         * CONFIRMED GAP: overrideBareName identifies other players by
         * player.gameProfile.name, and SkyBlock fakes that for entities
         * the same way it fakes it for tab-list rows -- confirmed in-game
         * (synced tag showed correctly in tab list but not on the floating
         * nametag for the same player). When that happens the real IGN
         * team-wraps through untouched into state.nameTag, still present
         * there as literal text. CustomNametagText.transformNameTagFallback
         * reuses the same whole-line splice transformTabListName's own
         * fallback uses to catch that case; it's a no-op if
         * overrideBareName already handled it (the real IGN text is gone
         * by then, so nothing matches).
         */
        if (entity instanceof Player player && state.nameTag != null) {
            // TEMP DEBUG -- gated behind CustomNametag's "Log Nametag Debug"
            // setting; toggle it in the mod's ClickGUI (Render category).
            // Prints the render-state nameTag's literal text BEFORE the
            // fallback splice (this is what EntityMixin's getDisplayName()
            // redirect already produced -- if the real IGN isn't in here as
            // text, there's nothing for the fallback to find no matter what
            // CosmeticsSync contains), the set of IGNs CosmeticsSync
            // currently has loaded (confirms sync data actually arrived),
            // and gameProfile.name() for comparison against EntityMixin's
            // own debug line for the same entity.
            boolean debugOn = CustomNametag.INSTANCE.getLogNametagDebug().getValue();
            if (debugOn) {
                System.out.println(
                        "[CedClient NameTag DEBUG] renderState: gameProfileName=\"" + player.getGameProfile().name()
                                + "\" nameTagBefore=\"" + state.nameTag.getString()
                                + "\" syncedIgns=" + CosmeticsSync.INSTANCE.allTags().keySet()
                );
            }
            net.minecraft.network.chat.Component transformed =
                    CustomNametagText.INSTANCE.transformNameTagFallback(state.nameTag);
            if (debugOn) {
                System.out.println(
                        "[CedClient NameTag DEBUG] renderState: fallbackMatched=" + (transformed != null)
                                + " nameTagAfter=\"" + (transformed != null ? transformed.getString() : state.nameTag.getString()) + "\""
                );
            }
            if (transformed != null) {
                state.nameTag = transformed;
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