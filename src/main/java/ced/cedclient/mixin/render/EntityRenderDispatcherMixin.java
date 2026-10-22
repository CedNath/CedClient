package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.CleanView;
import ced.cedclient.features.impl.render.StarredMobHighlight;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Clean View: entities next to the camera are culled before they're extracted for rendering. Starred Mob Highlight: non-starred mob nametags. Leap Menu: Hide Players after a leap. */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void cedclient$cleanView(Entity entity, Frustum culler, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        if (CleanView.hideEntity(entity, camX, camY, camZ) || StarredMobHighlight.hideNametag(entity) || ced.cedclient.features.impl.funqol.LeapMenu.hideEntity(entity)
                || ced.cedclient.features.impl.render.Hiders.hidePlayer(entity)) cir.setReturnValue(false);
    }
}
