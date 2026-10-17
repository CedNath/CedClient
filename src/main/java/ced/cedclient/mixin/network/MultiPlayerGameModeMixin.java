package ced.cedclient.mixin.network;

import ced.cedclient.farmhelper.failsafe.impl.DirtFailsafe;
import ced.cedclient.farmhelper.failsafe.impl.LowerAvgBpsFailsafe;
import ced.cedclient.data.ClickType;
import ced.cedclient.events.ItemClickEvent;
import ced.cedclient.features.impl.funqol.BreakerHelper;
import ced.cedclient.render.cosmetic.CosmeticHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void cedclient$onBlockHit(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        BreakerHelper.onHitBlock(pos);
    }

    // FarmHelper BPS: every block the client breaks (state read before it turns to air).
    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void cedclient$onDestroyBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (Minecraft.getInstance().level == null) return;
        LowerAvgBpsFailsafe.getInstance().onPlayerBreak(Minecraft.getInstance().level.getBlockState(pos));
        DirtFailsafe.getInstance().onPlayerBreak(pos); // our own break coming back is not "someone placed a block"
        ced.cedclient.features.impl.funqol.FarmHelper.INSTANCE.learnFrom(Minecraft.getInstance().level.getBlockState(pos).getBlock());
    }

    // ============================================================
    // ITEM RIGHT-CLICK HANDLER (posts ItemClickEvent)
    // ============================================================
    // useItem() is what FishingHelper already calls to trigger a synthetic
    // right-click (mc.gameMode.useItem(player, hand)), so hooking it here
    // covers both real player right-clicks and any helper-triggered ones.
    // NOTE: verify "useItem(Player, InteractionHand)" is still the exact
    // signature for your MC version -- Navigate > Declaration on
    // MultiPlayerGameMode if this fails to match.
    @Inject(method = "useItem", at = @At("HEAD"))
    private void cedclient$onUseItem(Player player, InteractionHand hand,
                                     CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack stack = player.getItemInHand(hand);
        new ItemClickEvent(stack, ClickType.RIGHT_CLICK).post();
    }

    // Cosmetic Blocks: the "hitting a block" sound matches what the block looks like.
    @Redirect(method = "continueDestroyBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType()Lnet/minecraft/world/level/block/SoundType;"))
    private SoundType cedclient$cosmeticHitSound(BlockState state, BlockPos pos, Direction side) {
        BlockState r = CosmeticHooks.effectReplacement(pos, state);
        return r != null ? r.getSoundType() : state.getSoundType();
    }
}
