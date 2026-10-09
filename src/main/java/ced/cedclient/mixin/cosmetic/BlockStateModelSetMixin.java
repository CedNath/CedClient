package ced.cedclient.mixin.cosmetic;

import ced.cedclient.render.CosmeticModelWrapper;
import ced.cedclient.render.cosmetic.CosmeticHooks;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Hands the mesher a re-texturing wrapper while Keep Block Size / brush shapes are in use. */
@Mixin(BlockStateModelSet.class)
public class BlockStateModelSetMixin {

    @Unique
    private final Map<BlockStateModel, CosmeticModelWrapper> cedclient$wrappers = new ConcurrentHashMap<>();

    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private void cedclient$wrapModel(BlockState state, CallbackInfoReturnable<BlockStateModel> cir) {
        if (!CosmeticHooks.needsModelWrapper()) return;
        BlockStateModel original = cir.getReturnValue();
        if (original == null || original instanceof CosmeticModelWrapper) return;
        if (cedclient$wrappers.size() > 60000) cedclient$wrappers.clear();
        cir.setReturnValue(cedclient$wrappers.computeIfAbsent(original, CosmeticModelWrapper::new));
    }
}
