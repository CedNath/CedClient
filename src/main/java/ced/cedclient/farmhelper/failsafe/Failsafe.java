/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; detection only (no scripted reactions). See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.block.state.BlockState;

public abstract class Failsafe {
    public final Minecraft mc = Minecraft.getInstance();

    /** Lower = more important when several fire at once. */
    public abstract int getPriority();

    public abstract FailsafeManager.EmergencyType getType();

    public void onBlockChange(BlockPos pos, BlockState old, BlockState update) {}

    public void onReceivedPacketDetection(Packet<?> packet) {}

    public void onTickDetection() {}

    public void onChatDetection(String message) {}

    public void onWorldUnloadDetection() {}

    public void onDisconnectDetection() {}

    public void resetStates() {}
}
