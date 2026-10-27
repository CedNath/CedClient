package ced.cedclient.mixin.entity;

import ced.cedclient.features.impl.misc.ArrowFix;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Arrow Fix: a SkyBlock shortbow fires instantly, so the vanilla pull-back animation is cancelled. */
@Mixin(Player.class)
public abstract class PlayerBowMixin extends LivingEntity {
    protected PlayerBowMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void cedclient$noShortbowPull(CallbackInfo ci) {
        if (!ArrowFix.INSTANCE.isEnabled()) return;
        if ((Player) (Object) this != Minecraft.getInstance().player) return;
        if (ArrowFix.isShortbow(this.useItem)) {
            this.useItem = ItemStack.EMPTY;
            this.useItemRemaining = 0;
        }
    }
}
