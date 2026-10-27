package ced.cedclient.mixin.accessor;

import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PictureInPictureRenderer.class)
public interface PictureInPictureRendererAccessor {
    /** The colour texture the renderer's output is blitted from; NVG draws straight into it. */
    @Accessor("texture")
    GpuTexture ced$getTexture();

    @Accessor("depthTexture")
    GpuTexture ced$getDepthTexture();
}
