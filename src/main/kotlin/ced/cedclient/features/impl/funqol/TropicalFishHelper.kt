package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.utils.HumanLook
import ced.cedclient.utils.isLookingAt
import ced.cedclient.utils.lookAngles
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.animal.fish.TropicalFish
import org.lwjgl.glfw.GLFW
import kotlin.random.Random

/**
 * CoralotHelper for tropical fish. Fish have no warning icon, so there's nothing to wait for: the nearest fish
 * in range is aimed at (same HumanLook turning), netted after a random delay, and the next one is picked.
 * A fish that's still there after a throw is retried after [RETRY_MS]. Uses CoralotHelper's net item name.
 *
 * With CoralotHelper also on, the two work as one: fish are only caught while no axolotl is in Coralot's range,
 * and CoralotHelper's Catch Key (Require Keybind) switches both on / off.
 *
 * With CoralotHelper also on, the two work as one: fish are only caught while no axolotl is in Coralot's range,
 * and CoralotHelper's Catch Key (Require Keybind) switches both on / off.
 */
object TropicalFishHelper : Module(
    "TropicalFishHelper(!!!)",
    Category.Cheats,
    "Nets every tropical fish in range, one after another"
) {
    private val mc = Minecraft.getInstance()

    private val detectRange = NumberSetting("Detect Range (blocks)", 14.5, 1.0, 25.0, 0.5)
    private val delayMinMs = NumberSetting("Catch Delay Min (ms)", 400.0, 0.0, 3000.0, 10.0, "Random wait before each throw (the turn happens meanwhile).")
    private val delayMaxMs = NumberSetting("Catch Delay Max (ms)", 500.0, 0.0, 3000.0, 10.0)
    private val smoothnessMs = NumberSetting("Look Smoothness ms", 180.0, 50.0, 500.0, 10.0)
    private val maxTurnSpeed = NumberSetting("Max Turn Speed (deg/s)", 900.0, 100.0, 3000.0, 50.0)
    private val aimJitter = NumberSetting("Aim Jitter (deg)", 0.6, 0.0, 3.0, 0.1)
    private val aimToleranceDeg = NumberSetting("Aim Tolerance (deg)", 3.0, 0.5, 15.0, 0.5)
    private val maxAimWaitMs = NumberSetting("Max Aim Wait (ms)", 1500.0, 200.0, 5000.0, 100.0)
    private val restorePreviousItem = BooleanSetting("Restore Previous Item", true, "Switch back once no fish are left in range.")
    private val requireKeybind = BooleanSetting(
        "Require Keybind", true,
        "Off: catches on its own. On: only looks / catches while toggled on with the Catch Key. Ignored while CoralotHelper is on: its key controls both."
    )
    private val catchKey = KeybindSetting("Catch Key", description = "Toggles catching on / off (with Require Keybind on).")
        .withDependency { requireKeybind.value }

    private const val RETRY_MS = 2000L

    private var armed = false
    private var keyWas = false
    // With CoralotHelper on, both run as one combo: its Catch Key toggles both, and axolotls go first.
    private val combo get() = CoralotHelper.isEnabled
    private val canCatch get() = if (combo) CoralotHelper.canCatch else !requireKeybind.value || armed

    private var target: TropicalFish? = null
    private var humanLook: HumanLook? = null
    private var catchDeadline = 0L
    private var aimHardDeadline = 0L
    private var lastFrameNanos = 0L
    private var previousSlot: Int? = null
    /** Entity id -> when we last threw at it, so a miss isn't retried every tick. */
    private val tried = HashMap<Int, Long>()

    init {
        listOf(smoothnessMs, maxTurnSpeed, aimJitter, aimToleranceDeg, maxAimWaitMs, restorePreviousItem).forEach { it.advanced = true }
        addSettings(
            detectRange, delayMinMs, delayMaxMs, smoothnessMs, maxTurnSpeed, aimJitter,
            aimToleranceDeg, maxAimWaitMs, restorePreviousItem, requireKeybind, catchKey
        )

        ClientTickEvents.END_CLIENT_TICK.register {
            if (isEnabled) tick()
        }

        // Camera movement every rendered frame, so turns don't step at 20 Hz.
        LevelRenderEvents.START_MAIN.register {
            if (!isEnabled) return@register
            val player = mc.player ?: return@register
            val fish = target?.takeIf { it.isAlive } ?: return@register
            smoothLook(player, fish)
        }
    }

    override fun onDisable() = finish()

    private fun tick() {
        val player = mc.player ?: return finish()
        if (!combo) pollCatchKey()
        if (combo && CoralotHelper.hasWork(player, detectRange.value + 3.0)) {
            target = null
            return
        }
        val now = System.currentTimeMillis()
        tried.values.removeAll { now - it > RETRY_MS }

        val fish = target
        if (fish == null) {
            if (!canCatch) return
            val next = mc.level?.entitiesForRendering()?.filterIsInstance<TropicalFish>()
                ?.filter { it.isAlive && it.id !in tried && player.distanceTo(it) <= detectRange.value }
                ?.minByOrNull { player.distanceTo(it) }
            if (next == null) return restoreSlot()
            startTarget(next)
            return
        }

        if (!fish.isAlive || player.distanceTo(fish) > detectRange.value || !canCatch) {
            target = null
            return
        }
        if (now >= aimHardDeadline || (now >= catchDeadline && isLookingAt(player, fish, aimToleranceDeg.value.toFloat()))) {
            throwNet(player, fish)
        }
    }

    private fun startTarget(fish: TropicalFish) {
        target = fish
        humanLook = HumanLook(
            smoothTimeSeconds = (smoothnessMs.value / 1000.0).toFloat(),
            maxDegreesPerSecond = maxTurnSpeed.value.toFloat(),
            jitterDegrees = aimJitter.value.toFloat()
        )
        lastFrameNanos = 0L
        val lo = minOf(delayMinMs.value, delayMaxMs.value).toLong()
        val hi = maxOf(delayMinMs.value, delayMaxMs.value).toLong()
        catchDeadline = System.currentTimeMillis() + Random.nextLong(lo, hi + 1)
        aimHardDeadline = catchDeadline + maxAimWaitMs.value.toLong()
    }

    private fun throwNet(player: LocalPlayer, fish: TropicalFish) {
        val before = player.inventory.selectedSlot
        if (!switchToNet(player)) {
            mc.gui.setOverlayMessage(Component.literal("§cNo \"${CoralotHelper.netItemName}\" in your hotbar"), false)
            tried[fish.id] = System.currentTimeMillis()
            target = null
            return
        }
        if (restorePreviousItem.value && previousSlot == null && before != player.inventory.selectedSlot) previousSlot = before
        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        player.swing(InteractionHand.MAIN_HAND)
        tried[fish.id] = System.currentTimeMillis()
        target = null
    }

    private fun switchToNet(player: LocalPlayer): Boolean {
        val slot = (0..8).firstOrNull {
            val stack = player.inventory.getItem(it)
            !stack.isEmpty && stack.hoverName.string.contains(CoralotHelper.netItemName, ignoreCase = true)
        } ?: return false
        player.inventory.setSelectedSlot(slot)
        return true
    }

    private fun smoothLook(player: LocalPlayer, fish: TropicalFish) {
        val look = humanLook ?: return
        val now = System.nanoTime()
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now
            return
        }
        var dt = (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        if (dt <= 0f || dt > 0.1f) dt = 1f / 60f
        val (yaw, pitch) = lookAngles(player, fish)
        val (newYaw, newPitch) = look.step(player.yRot, player.xRot, yaw, pitch, dt)
        player.yRot = newYaw
        player.xRot = newPitch
    }

    private fun pollCatchKey() {
        val code = catchKey.value
        val window = GLFW.glfwGetCurrentContext()
        val down = requireKeybind.value && code > 0 && window != 0L && mc.screen == null &&
                GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS
        if (down && !keyWas) {
            armed = !armed
            if (!armed) target = null
            mc.gui.setOverlayMessage(Component.literal(if (armed) "§aFish catching ON" else "§cFish catching OFF"), false)
        }
        keyWas = down
    }

    private fun restoreSlot() {
        previousSlot?.let { mc.player?.inventory?.setSelectedSlot(it) }
        previousSlot = null
    }

    private fun finish() {
        target = null
        humanLook = null
        restoreSlot()
    }
}
