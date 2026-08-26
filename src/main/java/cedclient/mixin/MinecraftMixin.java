package cedclient.mixin;

import ced.cedclient.events.core.EventBus;
import ced.cedclient.events.core.TickEvent;
import ced.cedclient.input.CedMouseState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void cedclient$onTickStart(CallbackInfo ci) {
        EventBus.INSTANCE.post(TickEvent.Start.INSTANCE);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void cedclient$onTickEnd(CallbackInfo ci) {
        EventBus.INSTANCE.post(TickEvent.End.INSTANCE);
    }

    // setScreenAndShow stayed on Minecraft in 26.2 — only setScreen moved to Gui.
    @Inject(method = "setScreenAndShow", at = @At("HEAD"))
    private void cedclient$resetMouseState2(Screen screen, CallbackInfo ci) {
        CedMouseState.INSTANCE.reset();
    }

}