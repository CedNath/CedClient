package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.Saturation;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.PostPass;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Post chains bake their uniforms from the json; Saturation's slider swaps the buffer of its first pass when the value changes. */
@Mixin(PostPass.class)
public abstract class PostPassSaturationMixin {
    @Shadow @Final private String name;
    @Shadow @Final private Map<String, GpuBuffer> customUniforms;
    @Unique private float cedclient$written = Float.NaN;

    @Inject(method = "addToFrame", at = @At("HEAD"))
    private void cedclient$saturation(CallbackInfo ci) {
        if (!name.equals("cedclient:saturation/0")) return;
        float value = Saturation.INSTANCE.value();
        if (value == cedclient$written) return;
        GpuBuffer old = customUniforms.get("SaturationConfig");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int size = new Std140SizeCalculator().putFloat().get();
            Std140Builder builder = Std140Builder.onStack(stack, size).putFloat(value);
            customUniforms.put("SaturationConfig", RenderSystem.getDevice().createBuffer(() -> "cedclient saturation", 128, builder.get()));
        }
        if (old != null) old.close();
        cedclient$written = value;
    }
}
