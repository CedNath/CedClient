package ced.cedclient.mixin.chat;

import ced.cedclient.commands.ClientCommandTreeRepair;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Restores tab-complete for client commands that share a name with a server command -- see ClientCommandTreeRepair.
 * Runs after the server's command tree has been received (and after Fabric merged our client commands into it).
 */
@Mixin(value = ClientPacketListener.class, priority = 1500)
public class ClientCommandTreeMixin {

    @Shadow
    private CommandDispatcher<SharedSuggestionProvider> commands;

    @Inject(method = "handleCommands", at = @At("TAIL"))
    private void cedclient$repairClientCommands(ClientboundCommandsPacket packet, CallbackInfo ci) {
        ClientCommandTreeRepair.repair(this.commands.getRoot());
    }
}
