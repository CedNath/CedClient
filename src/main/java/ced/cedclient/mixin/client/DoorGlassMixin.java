package ced.cedclient.mixin.client;

//#if CHEAT
import ced.cedclient.features.impl.funqol.IHateDoors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//#endif
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;

/** I Hate Doors: a door block the server (re)sends, e.g. after mining or clicking the glass, arrives as glass, never as the door. */
@Mixin(ClientLevel.class)
public class DoorGlassMixin {
//#if CHEAT
    @ModifyVariable(method = "setServerVerifiedBlockState", at = @At("HEAD"), argsOnly = true)
    private BlockState cedclient$doorGlass(BlockState state, BlockPos pos) {
        return IHateDoors.swap(pos, state);
    }
//#endif
}
