/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient. A triggered failsafe stops the macro and alerts
 * the player (sound + HUD + chat); the original's scripted reactions (fake chat replies to staff, recorded movement
 * playback, auto-restart) are not ported. See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe;

import ced.cedclient.farmhelper.FarmConfig;
import ced.cedclient.farmhelper.failsafe.impl.*;
import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.farmhelper.handler.RotationHandler;
import ced.cedclient.farmhelper.macro.AbstractMacro;
import ced.cedclient.farmhelper.util.Clock;
import ced.cedclient.farmhelper.util.KeyBindUtils;
import ced.cedclient.farmhelper.util.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class FailsafeManager {
    private static FailsafeManager instance;
    private final Minecraft mc = Minecraft.getInstance();

    public final List<Failsafe> failsafes = new ArrayList<>();
    public Optional<Failsafe> triggeredFailsafe = Optional.empty();
    public final ArrayList<Failsafe> emergencyQueue = new ArrayList<>();
    private final Clock chooseEmergencyDelay = new Clock();
    private final Clock alarmRepeat = new Clock();
    private int alarmsLeft = 0;
    private boolean hadEmergency = false;

    public static FailsafeManager getInstance() {
        if (instance == null) instance = new FailsafeManager();
        return instance;
    }

    public FailsafeManager() {
        failsafes.addAll(Arrays.asList(
                BadEffectsFailsafe.getInstance(),
                BedrockCageFailsafe.getInstance(),
                CobwebFailsafe.getInstance(),
                DirtFailsafe.getInstance(),
                DisconnectFailsafe.getInstance(),
                EvacuateFailsafe.getInstance(),
                FullInventoryFailsafe.getInstance(),
                ItemChangeFailsafe.getInstance(),
                KnockbackFailsafe.getInstance(),
                LowerAvgBpsFailsafe.getInstance(),
                RotationFailsafe.getInstance(),
                TeleportFailsafe.getInstance(),
                WorldChangeFailsafe.getInstance()
        ));
    }

    public Clock getChooseEmergencyDelay() { return chooseEmergencyDelay; }
    public ArrayList<Failsafe> getEmergencyQueue() { return emergencyQueue; }
    public boolean isHadEmergency() { return hadEmergency; }
    public void setHadEmergency(boolean value) { hadEmergency = value; }

    public void stopFailsafes() {
        triggeredFailsafe = Optional.empty();
        emergencyQueue.clear();
        chooseEmergencyDelay.reset();
        alarmsLeft = 0;
        failsafes.forEach(Failsafe::resetStates);
    }

    public void resetAfterMacroDisable() {
        stopFailsafes();
    }

    /** True when detection should not run (not farming, or a failsafe already fired). */
    public boolean firstCheckReturn() {
        if (mc.player == null || mc.level == null) return true;
        if (!MacroHandler.getInstance().isMacroToggled()) return true;
        return triggeredFailsafe.isPresent();
    }

    // ---- event fan-out (FarmHelperCore)

    public void onBlockChange(BlockPos pos, BlockState old, BlockState update) {
        if (firstCheckReturn()) return;
        failsafes.forEach(f -> f.onBlockChange(pos, old, update));
    }

    public void onPacket(Packet<?> packet) {
        if (firstCheckReturn()) return;
        failsafes.forEach(f -> f.onReceivedPacketDetection(packet));
    }

    public void onChat(String message) {
        if (firstCheckReturn()) return;
        failsafes.forEach(f -> f.onChatDetection(message));
    }

    public void onWorldUnload() {
        if (firstCheckReturn()) return;
        failsafes.forEach(Failsafe::onWorldUnloadDetection);
    }

    public void onDisconnect() {
        if (!MacroHandler.getInstance().isMacroToggled() || triggeredFailsafe.isPresent()) return;
        failsafes.forEach(Failsafe::onDisconnectDetection);
    }

    public void onTick() {
        if (mc.player == null || mc.level == null) return;
        if (!firstCheckReturn()) failsafes.forEach(Failsafe::onTickDetection);
        chooseEmergency();
        if (alarmsLeft > 0 && alarmRepeat.passed()) {
            alarmsLeft--;
            playAlarm();
            alarmRepeat.schedule(600);
        }
    }

    public void possibleDetection(Failsafe failsafe) {
        if (emergencyQueue.contains(failsafe) || triggeredFailsafe.isPresent()) return;
        MacroHandler.getInstance().getCurrentMacro().ifPresent(AbstractMacro::saveState);
        emergencyQueue.add(failsafe);
        if (!chooseEmergencyDelay.isScheduled()) {
            chooseEmergencyDelay.schedule(FarmConfig.failsafeStopDelay() + (long) (Math.random() * 500));
        }
        LogUtils.sendWarning("[Failsafe] Probability of emergency: " + failsafe.getType().name().replace('_', ' '));
    }

    private Failsafe getHighestPriorityEmergency() {
        Failsafe highest = emergencyQueue.get(0);
        for (Failsafe f : emergencyQueue) {
            if (f.getPriority() < highest.getPriority()) highest = f;
        }
        return highest;
    }

    private void chooseEmergency() {
        if (!MacroHandler.getInstance().isMacroToggled() || triggeredFailsafe.isPresent()) return;
        if (!chooseEmergencyDelay.isScheduled() || !chooseEmergencyDelay.passed()) return;
        if (emergencyQueue.isEmpty()) {
            chooseEmergencyDelay.reset();
            return;
        }
        Failsafe chosen = getHighestPriorityEmergency();
        triggeredFailsafe = Optional.of(chosen);
        emergencyQueue.clear();
        chooseEmergencyDelay.reset();
        hadEmergency = true;

        // Stop everything and hand control back to the player.
        KeyBindUtils.stopMovement();
        RotationHandler.getInstance().reset();
        MacroHandler.getInstance().pauseMacro();
        LogUtils.sendFailsafeMessage(chosen.getType().label);
        LogUtils.sendError("Macro stopped. Take over yourself; press the Toggle Key (or /farmhelper) to clear this.");
        if (FarmConfig.enableFailsafeSound()) {
            alarmsLeft = 5;
            alarmRepeat.reset();
        }
    }

    private void playAlarm() {
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ANVIL_LAND, 1f, 1f));
    }

    public enum EmergencyType {
        NONE(""),
        ROTATION_CHECK("You've been ROTATED by a staff member!"),
        TELEPORT_CHECK("You've been TELEPORTED by a staff member!"),
        KNOCKBACK_CHECK("You've been KNOCKED OUT OF THE FARM by a staff member!"),
        DIRT_CHECK("You've been DIRT CHECKED by a staff member!"),
        COBWEB_CHECK("You've been COBWEB CHECKED by a staff member!"),
        BAD_EFFECTS_CHECK("You've had BAD EFFECTS APPLIED by a staff member!"),
        FULL_INVENTORY("Your inventory is full. It might be a staff check or just an accident."),
        ITEM_CHANGE_CHECK("Your ITEM HAS CHANGED!"),
        WORLD_CHANGE_CHECK("Your WORLD HAS CHANGED!"),
        BEDROCK_CAGE_CHECK("You've been BEDROCK CAGED by a staff member!"),
        EVACUATE("Server is restarting! Evacuate!"),
        DISCONNECT("You've been DISCONNECTED from the server!"),
        LOWER_AVERAGE_BPS("Your BPS is lower than average!");

        public final String label;

        EmergencyType(String label) {
            this.label = label;
        }
    }
}
