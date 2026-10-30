package ced.cedclient.mixin.client;

import ced.cedclient.features.impl.render.RenderOptimizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clean Loading Screen: start the client load like vanilla, but never open the loading screen. */
@Mixin(ClientPacketListener.class)
public abstract class ClientLoadingScreenMixin {

    @Shadow private LevelLoadTracker levelLoadTracker;

    @Inject(method = "startWaitingForNewLevel", at = @At("HEAD"), cancellable = true)
    private void ced$skipLoadingScreen(LocalPlayer player, ClientLevel level, LevelLoadingScreen.Reason reason, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!RenderOptimizer.cleanLoadingScreen() || minecraft.gui.screen() instanceof LevelLoadingScreen) return;
        // On login the connect screen is up and vanilla replaces it with the loading screen: close it ourselves.
        if (minecraft.gui.screen() != null) minecraft.gui.setScreen(null);
        if (this.levelLoadTracker == null) this.levelLoadTracker = new LevelLoadTracker();
        this.levelLoadTracker.startClientLoad(player, level);
        RenderOptimizer.loadingScreenSkipped();
        ci.cancel();
    }
}
