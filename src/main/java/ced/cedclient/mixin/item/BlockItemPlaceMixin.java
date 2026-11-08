package ced.cedclient.mixin.item;

//#if CHEAT
import ced.cedclient.features.impl.funqol.NoItemPlace;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//#endif
import net.minecraft.world.item.BlockItem;
import org.spongepowered.asm.mixin.Mixin;

/**
 * No Item Place: the block is not placed on the client, but the click still goes to the server (like NoammAddons does it),
 * so abilities and server-side interactions (relics into cauldrons, Spirit Leap) keep working.
 */
@Mixin(BlockItem.class)
public class BlockItemPlaceMixin {
//#if CHEAT
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void cedclient$noItemPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (NoItemPlace.shouldBlock(context)) cir.setReturnValue(InteractionResult.SUCCESS);
    }
//#endif
}
