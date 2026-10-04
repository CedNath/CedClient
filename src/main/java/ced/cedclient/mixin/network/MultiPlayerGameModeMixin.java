package ced.cedclient.mixin.network;

import ced.cedclient.data.ClickType;
import ced.cedclient.events.ItemClickEvent;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
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

    // ============================================================
    // ITEM RIGHT-CLICK HANDLER (posts ItemClickEvent)
    // ============================================================
    // Hooks useItem() (mc.gameMode.useItem(player, hand)) so every real
    // player right-click is posted as an ItemClickEvent.
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