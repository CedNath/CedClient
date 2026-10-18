//#if CHEAT
package ced.cedclient.mixin.hitbox;

import ced.cedclient.features.impl.funqol.SecretHitboxes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks "this getShape call is really a collision lookup" for skulls, so Secret Hitboxes' full-block skull
 * shape only affects the outline / clicking and you never bump into or stand on a bigger skull.
 */
@Mixin(BlockBehaviour.class)
public class SkullCollisionMixin {

    @Inject(method = "getCollisionShape", at = @At("HEAD"))
    private void cedclient$enter(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
                                 CallbackInfoReturnable<VoxelShape> cir) {
        if ((Object) this instanceof SkullBlock) SecretHitboxes.SKULL_COLLISION.set(true);
    }

    @Inject(method = "getCollisionShape", at = @At("RETURN"))
    private void cedclient$exit(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
                                CallbackInfoReturnable<VoxelShape> cir) {
        if ((Object) this instanceof SkullBlock) SecretHitboxes.SKULL_COLLISION.set(false);
    }
}
//#endif
