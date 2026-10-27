package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.HubMap;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hub Map: the hub's map tiles draw our own image instead. */
@Mixin(MapRenderer.class)
public abstract class MapRendererHubMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void cedclient$hubMap(MapId mapId, MapItemSavedData mapData, MapRenderState state, CallbackInfo ci) {
        HubMap.applyRenderState(mapId, state, ci);
    }
}
