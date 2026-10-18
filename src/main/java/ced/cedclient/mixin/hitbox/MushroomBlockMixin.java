//#if CHEAT
package ced.cedclient.mixin.hitbox;

import ced.cedclient.features.impl.funqol.SecretHitboxes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Secret Hitboxes: full-block mushroom outline / click shape (mushrooms have no collision). */
@Mixin(MushroomBlock.class)
public class MushroomBlockMixin {
    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void cedclient$secretHitbox(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
                                        CallbackInfoReturnable<VoxelShape> cir) {
        if (!SecretHitboxes.isEntityQuery(context)) return;
        VoxelShape shape = SecretHitboxes.mushroomShape();
        if (shape != null) cir.setReturnValue(shape);
    }
}
//#endif
