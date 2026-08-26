package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.HumanLook
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.animal.axolotl.Axolotl
import net.minecraft.world.entity.decoration.ArmorStand
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

private enum class CoralotState { IDLE, CATCH_DELAY, CONFIRMING }

object CoralotHelper : Module(
    "CoralotHelper(!!!)",

    Category.Funqol,
    description = "Auto-catches Axolotls with a net once the warning icon clears"
) {
    private val mc = Minecraft.getInstance()

    // --- settings ---
    private val detectRange = NumberSetting("Detect Range (blocks)", 6.0, 1.0, 16.0, 0.5)
    private val smoothnessMs = NumberSetting("Look Smoothness ms", 180.0, 50.0, 500.0, 10.0)
    private val maxTurnSpeed = NumberSetting("Max Turn Speed (deg/s)", 900.0, 100.0, 3000.0, 50.0)
    private val aimJitter = NumberSetting("Aim Jitter (deg)", 0.6, 0.0, 3.0, 0.1)
    private val playSound = BooleanSetting("Play Sound", true)
    private val showTitle = BooleanSetting("Show Title", true)
    private val confirmTimeoutMs = NumberSetting("Confirm Timeout ms", 3000.0, 500.0, 10000.0, 250.0)
    private val catchDelayEnabled = BooleanSetting("Enable Catch Delay", true)
    private val catchDelayMinMs = NumberSetting("Catch Delay Min (ms)", 150.0, 0.0, 3000.0, 10.0)
    private val catchDelayMaxMs = NumberSetting("Catch Delay Max (ms)", 350.0, 0.0, 3000.0, 10.0)
    private val aimToleranceDeg = NumberSetting("Aim Tolerance (deg)", 2.0, 0.5, 15.0, 0.5)
    private val maxAimWaitMs = NumberSetting("Max Aim Wait (ms)", 1500.0, 200.0, 5000.0, 100.0)
    private val restorePreviousItem = BooleanSetting("Restore Previous Item", true)
    private val catchMultipleTargets = BooleanSetting("Catch Multiple", true)

    // --- customizable via chat commands (see CedClientCommand) ---
    var netItemName: String = "Net"
    var catchKeywords: List<String> = listOf("caught", "coralot")
    var soundId: String = "minecraft:entity.player.levelup"
    var titleText: String = "&aAxolotl Caught!"

    private var state = CoralotState.IDLE
    private var target: Axolotl? = null
    private var humanLook: HumanLook? = null
    private var confirmDeadline = 0L
    private var catchDeadline = 0L
    private var aimHardDeadline = 0L

    // per-frame timing for smooth look updates (independent of the 20/s tick rate)
    private var lastFrameNanos = 0L

    // slot the player was on right before we swapped to the net, restored once this catch cycle ends
    private var previousSlot: Int? = null

    // tracks, per nearby axolotl, whether it currently has a warning hologram —
    // lets us detect the "warning just disappeared" transition for EVERY axolotl,
    // not just the one we're actively looking at.
    private val hologramTracked = HashMap<Axolotl, Boolean>()
    private val readyQueue = ArrayDeque<Axolotl>()

    init {
        listOf(
            smoothnessMs, maxTurnSpeed, aimJitter, confirmTimeoutMs,
            catchDelayMinMs, catchDelayMaxMs, aimToleranceDeg, maxAimWaitMs,
            restorePreviousItem
        ).forEach { it.advanced = true }

        addSettings(
            detectRange,
            smoothnessMs,
            maxTurnSpeed,
            aimJitter,
            playSound,
            showTitle,
            confirmTimeoutMs,
            catchDelayEnabled,
            catchDelayMinMs,
            catchDelayMaxMs,
            aimToleranceDeg,
            maxAimWaitMs,
            restorePreviousItem,
            catchMultipleTargets
        )
        // State machine / timers / hologram scanning: doesn't need frame-rate precision, tick rate is fine.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) return@register
            tick()
        }

        // Actual camera movement: needs to run every rendered frame, not every tick,
        // or fast turns visibly stutter (the angle only updates 20x/sec otherwise
        // regardless of your FPS).
        LevelRenderEvents.START_MAIN.register {
            if (!isEnabled) return@register
            if (state != CoralotState.CATCH_DELAY) return@register

            val player = mc.player ?: return@register
            val axo = target ?: return@register
            if (!axo.isAlive) return@register

            smoothLookAtFrame(player, axo)
        }

        ClientReceiveMessageEvents.GAME.register { message, _ ->
            if (!isEnabled) return@register
            if (state != CoralotState.CONFIRMING) return@register

            val msg = message.string.lowercase()
            if (catchKeywords.any { msg.contains(it.lowercase()) }) {
                onCatchConfirmed()
            }
        }
    }

    private fun tick() {
        val player = mc.player ?: return

        updateHologramTracking(player)
        readyQueue.removeAll { !it.isAlive }

        if (state == CoralotState.CONFIRMING && System.currentTimeMillis() > confirmDeadline) {
            // never heard a confirmation message back — bail out safely rather than getting stuck
            reset()
        }

        when (state) {
            CoralotState.IDLE -> {
                val next = readyQueue.removeFirstOrNull() ?: return
                if (!next.isAlive) return

                target = next
                humanLook = HumanLook(
                    smoothTimeSeconds = (smoothnessMs.value / 1000.0).toFloat(),
                    maxDegreesPerSecond = maxTurnSpeed.value.toFloat(),
                    jitterDegrees = aimJitter.value.toFloat()
                )
                lastFrameNanos = 0L
                beginCatchDelay()
            }

            CoralotState.CATCH_DELAY -> {
                val axo = target ?: return reset()
                if (!axo.isAlive) return reset()

                val now = System.currentTimeMillis()
                val delayElapsed = now >= catchDeadline
                val forcedByTimeout = now >= aimHardDeadline

                if (forcedByTimeout || (delayElapsed && isAimedAt(player, axo))) {
                    attemptCatch(player, axo)
                }
            }

            CoralotState.CONFIRMING -> { /* waiting on chat */ }
        }
    }

    /**
     * Watches every nearby axolotl's warning hologram, not just the current target.
     * The moment any axolotl's hologram goes from present to absent, it's queued up
     * to be caught — this is what lets multiple axolotls that clear around the same
     * time all get caught in turn, instead of only the one we happened to be looking at.
     */
    private fun updateHologramTracking(player: LocalPlayer) {
        val level = mc.level ?: return

        val nearby = level.entitiesForRendering()
            .filterIsInstance<Axolotl>()
            .filter { it.isAlive && player.distanceTo(it) <= detectRange.value }
            .toSet()

        // stop tracking anything that's no longer nearby/alive, so it doesn't
        // falsely trigger "ready" if it wanders back into range later
        hologramTracked.keys.retainAll { it in nearby }

        for (axo in nearby) {
            val holoPresent = findHologramNear(axo) != null
            val hadHolo = hologramTracked[axo] ?: false

            if (holoPresent) {
                hologramTracked[axo] = true
            } else if (hadHolo) {
                hologramTracked.remove(axo)
                if (axo !== target && axo !in readyQueue) {
                    val slotFree = target == null && readyQueue.isEmpty()
                    if (catchMultipleTargets.value || slotFree) {
                        readyQueue.addLast(axo)
                    }
                }
            }
        }
    }

    private fun findHologramNear(axo: Axolotl): ArmorStand? {
        val level = mc.level ?: return null
        val box = axo.boundingBox.inflate(0.6, 2.0, 0.6)

        return level.getEntitiesOfClass(ArmorStand::class.java, box) { it.customName != null }
            .firstOrNull()
    }

    /** The real yaw/pitch (no jitter) the player needs to face to look directly at the axolotl. */
    private fun targetAngles(player: LocalPlayer, axo: Axolotl): Pair<Float, Float> {
        val dx = axo.x - player.x
        val dy = (axo.y + axo.bbHeight / 2.0) - (player.y + player.eyeHeight)
        val dz = axo.z - player.z

        val dist = sqrt(dx * dx + dz * dz)
        val targetYaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val targetPitch = -Math.toDegrees(atan2(dy, dist)).toFloat()
        return targetYaw to targetPitch
    }

    private fun angleDiff(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    /** True once the player's actual look direction is within tolerance of the axolotl. */
    private fun isAimedAt(player: LocalPlayer, axo: Axolotl): Boolean {
        val (targetYaw, targetPitch) = targetAngles(player, axo)
        val yawDiff = abs(angleDiff(player.yRot, targetYaw))
        val pitchDiff = abs(targetPitch - player.xRot)
        val tolerance = aimToleranceDeg.value.toFloat()
        return yawDiff <= tolerance && pitchDiff <= tolerance
    }

    /** Runs every rendered frame while CATCH_DELAY, using real elapsed time. */
    private fun smoothLookAtFrame(player: LocalPlayer, axo: Axolotl) {
        val look = humanLook ?: return

        val now = System.nanoTime()
        if (lastFrameNanos == 0L) {
            // first frame after acquiring a target: skip movement, just start the clock
            lastFrameNanos = now
            return
        }

        var deltaTime = (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now

        // guard against huge deltas from lag spikes / alt-tab so we don't get a giant snap
        if (deltaTime <= 0f || deltaTime > 0.1f) deltaTime = 1f / 60f

        val (targetYaw, targetPitch) = targetAngles(player, axo)

        val (newYaw, newPitch) = look.step(player.yRot, player.xRot, targetYaw, targetPitch, deltaTime)
        player.yRot = newYaw
        player.xRot = newPitch
    }

    private fun beginCatchDelay() {
        if (!catchDelayEnabled.value) {
            catchDeadline = System.currentTimeMillis()
        } else {
            val lo = catchDelayMinMs.value
            val hi = catchDelayMaxMs.value
            val (min, max) = if (lo <= hi) lo to hi else hi to lo // guard against min>max from UI sliders

            val delay = if (max <= 0.0) 0L else Random.nextLong(min.toLong(), (max.toLong() + 1L).coerceAtLeast(min.toLong() + 1L))
            catchDeadline = System.currentTimeMillis() + delay
        }

        // aim must converge by (delay + extra buffer) or we throw anyway regardless of aim
        aimHardDeadline = catchDeadline + maxAimWaitMs.value.toLong()
        state = CoralotState.CATCH_DELAY
    }

    private fun attemptCatch(player: LocalPlayer, axo: Axolotl) {
        val slotBeforeSwitch = player.inventory.selectedSlot

        if (!switchToNet(player)) {
            reset()
            return
        }
        if (restorePreviousItem.value) {
            previousSlot = slotBeforeSwitch
        }

        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        player.swing(InteractionHand.MAIN_HAND)

        state = CoralotState.CONFIRMING
        confirmDeadline = System.currentTimeMillis() + confirmTimeoutMs.value.toLong()
    }

    private fun switchToNet(player: LocalPlayer): Boolean {
        for (slot in 0..8) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue
            if (stack.hoverName.string.contains(netItemName, ignoreCase = true)) {
                player.inventory.setSelectedSlot(slot)
                return true
            }
        }
        return false
    }

    private fun onCatchConfirmed() {
        if (playSound.value) {
            val id = Identifier.tryParse(soundId)
            val sound: SoundEvent? = id?.let { BuiltInRegistries.SOUND_EVENT.get(it).orElse(null)?.value() }
            if (sound != null) {
                mc.soundManager.play(SimpleSoundInstance.forUI(sound, 1.0f))
            }
        }

        if (showTitle.value) {
            mc.gui.hud.setTitle(Component.literal(titleText.replace('&', '\u00A7')))
            mc.gui.hud.setTimes(5, 40, 10)
        }

        reset()
    }

    private fun reset() {
        previousSlot?.let { slot ->
            mc.player?.inventory?.setSelectedSlot(slot)
        }
        previousSlot = null

        state = CoralotState.IDLE
        target = null
        humanLook = null
        lastFrameNanos = 0L
    }
}