//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; also flags the server changing your held slot;
 * detection only. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.farmhelper.util.PlayerUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;

public class ItemChangeFailsafe extends Failsafe {
    private static ItemChangeFailsafe instance;

    public static ItemChangeFailsafe getInstance() {
        if (instance == null) instance = new ItemChangeFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 3; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.ITEM_CHANGE_CHECK; }

    @Override
    public void onReceivedPacketDetection(Packet<?> packet) {
        if (MacroHandler.getInstance().isTeleporting()) return;
        if (packet instanceof ClientboundSetHeldSlotPacket held) {
            if (held.slot() != mc.player.getInventory().getSelectedSlot()) {
                PlayerUtils.itemChangedByStaff = true;
                FailsafeManager.getInstance().possibleDetection(this);
            }
            return;
        }
        if (!(packet instanceof ClientboundContainerSetSlotPacket slotPacket) || slotPacket.getContainerId() != 0) return;
        int toolSlot = PlayerUtils.getFarmingTool(MacroHandler.getInstance().getCrop(), true, false);
        if (toolSlot == -1) return;
        ItemStack newItem = slotPacket.getItem();
        // Player inventory menu: hotbar slot i is menu slot 36 + i.
        if (slotPacket.getSlot() == 36 + toolSlot && !newItem.isEmpty()
                && !(newItem.getItem() instanceof HoeItem) && !(newItem.getItem() instanceof AxeItem)) {
            FailsafeManager.getInstance().possibleDetection(this);
        }
    }
}
//#endif
