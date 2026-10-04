package ced.cedclient.mixin.accessor;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    /** Vanilla left-click handler: the same code a real mouse click runs. */
    @Invoker("startAttack")
    boolean cedStartAttack();
}