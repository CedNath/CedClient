package cedclient.mixin;

import ced.cedclient.data.ClickType;
import ced.cedclient.events.ItemClickEvent;
import ced.cedclient.events.core.EventBus;
import ced.cedclient.events.core.TickEvent;
import ced.cedclient.input.CedMouseState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    // ============================================================
    // ITEM LEFT-CLICK HANDLER (posts ItemClickEvent)
    // ============================================================
    // startAttack() is what handles every left-click regardless of what's
    // aimed at -- an entity, a block, or empty air -- before Minecraft
    // dispatches to gameMode.attack(...)/startDestroyBlock(...)/nothing. That
    // makes it the one hook that covers left-click abilities like the
    // Gyrokinetic Wand's "Gravity Storm" (aimed at a location, not
    // necessarily a block or entity), instead of needing separate hooks for
    // each possible target type.
    // NOTE: same caveat as useItem() in MultiPlayerGameModeMixin -- verify
    // "startAttack()" (no-arg, returns boolean) is still the exact method for
    // your MC version if this fails to match.
    // NOTE: this fires on every left-click attempt, even ones Minecraft ends
    // up rejecting internally (e.g. attack still on cooldown) -- same
    // approximation useItem() already makes for right-clicks. Acceptable
    // since the chat fallback still corrects the real remaining time.
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void cedclient$onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        ItemStack stack = player.getMainHandItem();
        new ItemClickEvent(stack, ClickType.LEFT_CLICK).post();
    }
}