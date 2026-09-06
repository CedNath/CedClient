package cedclient.mixin;

import ced.cedclient.data.ClickType;
import ced.cedclient.events.ItemClickEvent;
import ced.cedclient.features.impl.funqol.BreakerHelper;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void cedclient$onBlockHit(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        BreakerHelper.onHitBlock(pos);
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
}