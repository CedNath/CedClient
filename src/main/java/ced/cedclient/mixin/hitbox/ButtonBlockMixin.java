package ced.cedclient.mixin.hitbox;

import ced.cedclient.features.impl.funqol.SecretHitboxes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Secret Hitboxes: bigger button outline / click shape (buttons have no collision). */
@Mixin(ButtonBlock.class)
public class ButtonBlockMixin {
    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void cedclient$secretHitbox(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
                                        CallbackInfoReturnable<VoxelShape> cir) {
        VoxelShape shape = SecretHitboxes.buttonShape(state);
        if (shape != null) cir.setReturnValue(shape);
    }
}
