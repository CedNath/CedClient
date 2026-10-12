package ced.cedclient.mixin.hitbox;

import ced.cedclient.features.impl.funqol.SecretHitboxes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Secret Hitboxes: full-block skull outline / click shape. Skulls DO have collision, and vanilla's
 * collision shape is just getShape -- so SkullCollisionMixin flags collision lookups and they're skipped here.
 */
@Mixin(SkullBlock.class)
public class SkullBlockMixin {
    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true)
    private void cedclient$secretHitbox(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
                                        CallbackInfoReturnable<VoxelShape> cir) {
        if (SecretHitboxes.SKULL_COLLISION.get() || !SecretHitboxes.isEntityQuery(context)) return;
        VoxelShape shape = SecretHitboxes.skullShape(state);
        if (shape != null) cir.setReturnValue(shape);
    }
}
