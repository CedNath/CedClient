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
import net.minecraft.world.entity.animal.armadillo.Armadillo
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

private enum class PangolinCatcherState { IDLE, TRACKING, CONFIRMING }

/**
 * Auto-lassos Pangolins (armadillo entity, re-skinned/renamed on this server).
 *
 * Activation is a toggle: left-click once while holding the Lasso to arm the
 * session. Left-click again at any point while armed (tracking or waiting on
 * a throw confirmation) to cancel and fully reset.
 */
object PangolinCatcher : Module(
    "PangolinCatcher(!!!)",
    Category.Funqol,
    description = "Left-click while holding the Lasso to start an auto-catch session. Left-click again to cancel."
) {
    private val mc = Minecraft.getInstance()

    // SETTINGS
    private val detectRange = NumberSetting("Detect Range (blocks)", 17.0, 5.0, 64.0, 1.0)
    private val minCatchDistance = NumberSetting("Min Catch Distance (blocks)", 9.0, 1.0, 30.0, 0.5)
    private val smoothnessMs = NumberSetting("Look Smoothness ms", 180.0, 50.0, 500.0, 10.0)
    private val maxTurnSpeed = NumberSetting("Max Turn Speed (deg/s)", 900.0, 100.0, 3000.0, 50.0)
    private val aimJitter = NumberSetting("Aim Jitter (deg)", 0.9, 0.0, 3.0, 0.1)
    private val playSound = BooleanSetting("Play Sound", true)
    private val showTitle = BooleanSetting("Show Title", true)
    private val confirmTimeoutMs = NumberSetting("Confirm Timeout ms", 15000.0, 500.0, 30000.0, 250.0)
    private val throwDelayEnabled = BooleanSetting("Enable Throw Delay", true)
    private val throwDelayMinMs = NumberSetting("Throw Delay Min (ms)", 150.0, 0.0, 3000.0, 10.0)
    private val throwDelayMaxMs = NumberSetting("Throw Delay Max (ms)", 250.0, 0.0, 3000.0, 10.0)
    private val aimToleranceBlocks = NumberSetting("Aim Tolerance (blocks)", 0.4, 0.1, 3.0, 0.1)
    private val maxAimWaitMs = NumberSetting("Max Aim Wait (ms)", 1500.0, 200.0, 5000.0, 100.0)
    private val requireClearShot = BooleanSetting("Require Clear Shot", true)
    private val restorePreviousItem = BooleanSetting("Restore Previous Item", false)
    private val aimHeightOffset = NumberSetting("Aim Height Offset (blocks)", 0.5, -1.0, 2.0, 0.1)
    private val aimSearchMaxHeight = NumberSetting("Aim Search Height (blocks)", 1.0, 0.0, 3.0, 0.1)
    private val aimSearchRadiusXZ = NumberSetting("Aim Search Radius XZ (blocks)", 0.6, 0.0, 2.0, 0.1)
    private val aimSearchStep = NumberSetting("Aim Search Step (blocks)", 1.0, 0.5, 3.0, 0.5)
    private val confirmSmoothnessMs = NumberSetting("Confirm Look Smoothness ms", 450.0, 100.0, 1000.0, 10.0)
    private val confirmMaxTurnSpeed = NumberSetting("Confirm Max Turn Speed (deg/s)", 250.0, 50.0, 1500.0, 25.0)
    private val stopTrackingDistance = NumberSetting("Stop Tracking Distance", 7.0, 0.5, 5.0, 0.5)
    private val stopTrackingSpeed = NumberSetting("Blocks per ticks traveled", 2.0, 0.1, 10.0, 0.2)
    // NEW: on a missed/failed throw, either requeue and keep trying, or stop the session entirely.
    private val retryOnMiss = BooleanSetting("Retry On Miss", true)
    // DEBUG TOGGLE
    private val debugLog = BooleanSetting("Debug Log", false)

    // CHAT KEYWORDS
    var lassoItemName: String = "Lasso"
    var successKeywords = listOf(
        "pangolin shard"
    )

    var failureKeywords = listOf(
        "bounced off",
        "reeled too early"
    )

    var sessionCompleteKeywords = listOf(
        "pangolin shard"
    )

    var soundId: String = "minecraft:entity.player.levelup"
    var titleText: String = "&aPangolin Caught!"

    private var state = PangolinCatcherState.IDLE
    private var armed = false

    private var target: Armadillo? = null
    private var humanLook: HumanLook? = null
    private var confirmHumanLook: HumanLook? = null
    private var confirmDeadline = 0L

    private var currentAimPoint: Vec3? = null
    private var lastClearOffset: Vec3? = null
    private var lastTargetPos: Vec3? = null
    private var eligibleSince: Long? = null
    private var throwDeadline = 0L
    private var aimHardDeadline = 0L
    private var lastFrameNanos = 0L
    private var previousSlot: Int? = null
    private var attackKeyWasDown = false

    // Matches legacy "§" formatting codes (color + style) so they can be
    // stripped before keyword matching. On this server, message.string
    // includes these codes as literal text instead of Style metadata, which
    // was silently breaking every msg.contains(...) check below.
    private val colorCodeRegex = Regex("§[0-9a-fk-or]", RegexOption.IGNORE_CASE)
    private fun stripFormatting(s: String): String = colorCodeRegex.replace(s, "")

    init {
        listOf(
            smoothnessMs, maxTurnSpeed, aimJitter, confirmTimeoutMs,
            throwDelayMinMs, throwDelayMaxMs, aimToleranceBlocks, maxAimWaitMs,
            requireClearShot, restorePreviousItem, aimHeightOffset,
            aimSearchMaxHeight, aimSearchRadiusXZ, aimSearchStep,
            confirmSmoothnessMs, confirmMaxTurnSpeed,
            stopTrackingDistance, stopTrackingSpeed, debugLog
        ).forEach { it.advanced = true }

        addSettings(
            detectRange,
            minCatchDistance,
            smoothnessMs,
            maxTurnSpeed,
            aimJitter,
            playSound,
            showTitle,
            confirmTimeoutMs,
            throwDelayEnabled,
            throwDelayMinMs,
            throwDelayMaxMs,
            aimToleranceBlocks,
            maxAimWaitMs,
            requireClearShot,
            aimHeightOffset,
            aimSearchMaxHeight,
            aimSearchRadiusXZ,
            aimSearchStep,
            confirmSmoothnessMs,
            confirmMaxTurnSpeed,
            stopTrackingDistance,
            stopTrackingSpeed,
            retryOnMiss,
            debugLog
        )

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!isEnabled) {
                attackKeyWasDown = false
                if (armed) stopAndReset()
                return@register
            }
            tick()
        }

        LevelRenderEvents.START_MAIN.register {
            if (!isEnabled) return@register
            if (state != PangolinCatcherState.TRACKING &&
                state != PangolinCatcherState.CONFIRMING
            ) return@register

            val player = mc.player ?: return@register
            val armadillo = target ?: return@register
            if (!armadillo.isAlive) return@register

            if (state == PangolinCatcherState.TRACKING) {
                smoothLookAtFrame(player, armadillo)
            } else {
                softLookAtFrame(player, armadillo)
            }
        }

        ClientReceiveMessageEvents.GAME.register { message, _ ->
            if (!isEnabled) return@register
            if (state != PangolinCatcherState.CONFIRMING) return@register

            // Strip formatting codes first, THEN lowercase — otherwise stray
            // "§a"/"§9"/etc. sitting inside the text can break substring
            // matches for keywords like "pangolin shard".
            val msg = stripFormatting(message.string).lowercase()

            if (debugLog.value) {
                println("[PangolinCatcher] Chat: $msg")
            }

            if (successKeywords.any { msg.contains(it) }) {
                if (debugLog.value) {
                    println("[PangolinCatcher] Catch confirmed")
                }

                onThrowResult(true)
                return@register
            }

            if (failureKeywords.any { msg.contains(it) }) {
                if (debugLog.value) {
                    println("[PangolinCatcher] Catch failed")
                }

                onThrowResult(false)
            }
        }
    }

    private fun tick() {
        val player = mc.player ?: return
        val level = mc.level ?: return

        if (state == PangolinCatcherState.CONFIRMING &&
            System.currentTimeMillis() > confirmDeadline) {

            if (debugLog.value) {
                println("[PangolinCatcher] Confirmation timed out")
            }

            stopAndReset()
            return
        }

        val attackKeyDown = mc.options.keyAttack.isDown
        val attackClicked = attackKeyDown && !attackKeyWasDown
        attackKeyWasDown = attackKeyDown

        // Toggle-off: while the session is armed (tracking OR waiting on a
        // throw confirmation), a fresh left-click cancels everything. The
        // arming click is only ever consumed below, while !armed.
        if (attackClicked && armed) {
            if (debugLog.value) {
                println("[PangolinCatcher] Cancelled by user click")
            }

            stopAndReset()
            return
        }

        when (state) {
            PangolinCatcherState.IDLE -> {
                if (!armed) {
                    if (!attackClicked || !isHoldingLasso(player)) return
                    armed = true
                    if (debugLog.value) println("[PangolinCatcher] Session armed")
                }

                val next = findNearestArmadillo(player, level) ?: return

                target = next
                lastTargetPos = next.position()

                if (debugLog.value)
                    println("[PangolinCatcher] Target acquired: ${next.uuid}")

                humanLook = HumanLook(
                    smoothTimeSeconds = (smoothnessMs.value / 1000.0).toFloat(),
                    maxDegreesPerSecond = maxTurnSpeed.value.toFloat(),
                    jitterDegrees = aimJitter.value.toFloat()
                )

                lastFrameNanos = 0L
                eligibleSince = null
                currentAimPoint = null
                lastClearOffset = null
                state = PangolinCatcherState.TRACKING
            }

            PangolinCatcherState.TRACKING -> {
                val armadillo = target ?: return requeueForNextTarget()

                // If the target suddenly jumps several blocks in one tick,
                // someone else probably caught it.
                val currentPos = armadillo.position()

                lastTargetPos?.let { previous ->
                    val moved = previous.distanceTo(currentPos)

                    if (moved > stopTrackingSpeed.value) {
                        if (debugLog.value) {
                            println("[PangolinCatcher] Target moved suddenly -> requeue")
                        }

                        requeueForNextTarget()
                        return
                    }
                }

                lastTargetPos = currentPos

                if (!armadillo.isAlive || player.distanceTo(armadillo) > detectRange.value) {
                    if (debugLog.value) println("[PangolinCatcher] Lost target → requeue")
                    requeueForNextTarget()
                    return
                }

                val distance = player.distanceTo(armadillo)
                val warningUp = findHologramNear(armadillo) != null

                val aimPoint =
                    if (!warningUp && distance >= minCatchDistance.value) {
                        if (requireClearShot.value)
                            findClearAimPoint(player, armadillo)
                        else
                            targetPos(armadillo)
                    } else null

                currentAimPoint = aimPoint ?: targetPos(armadillo)
                val eligible = aimPoint != null

                if (!eligible) {
                    eligibleSince = null
                    return
                }

                val now = System.currentTimeMillis()
                if (eligibleSince == null) {
                    eligibleSince = now
                    throwDeadline = now + computeThrowDelayMs()
                    aimHardDeadline = throwDeadline + maxAimWaitMs.value.toLong()

                    if (debugLog.value)
                        println("[PangolinCatcher] Eligible → throw scheduled")
                }

                val delayElapsed = now >= throwDeadline
                val forcedByTimeout = now >= aimHardDeadline

                if (forcedByTimeout || (delayElapsed && isAimedAt(player, aimPoint))) {
                    if (debugLog.value)
                        println("[PangolinCatcher] Throwing lasso")

                    attemptCatch(player, armadillo)
                }
            }
            PangolinCatcherState.CONFIRMING -> {
                // Soft tracking handled in render event
            }
        }
    }

    private fun isHoldingLasso(player: LocalPlayer): Boolean {
        val mainHand = player.mainHandItem
        return !mainHand.isEmpty &&
                mainHand.hoverName.string.contains(lassoItemName, ignoreCase = true)
    }

    private fun computeThrowDelayMs(): Long {
        if (!throwDelayEnabled.value) return 0L
        val lo = throwDelayMinMs.value
        val hi = throwDelayMaxMs.value
        val (min, max) = if (lo <= hi) lo to hi else hi to lo
        return if (max <= 0.0) 0L
        else Random.nextLong(min.toLong(), (max.toLong() + 1L))
    }

    private fun findNearestArmadillo(
        player: LocalPlayer,
        level: net.minecraft.client.multiplayer.ClientLevel
    ): Armadillo? {
        return level.entitiesForRendering()
            .filterIsInstance<Armadillo>()
            .filter { it.isAlive && player.distanceTo(it) <= detectRange.value }
            .minByOrNull { player.distanceTo(it) }
    }

    private fun findHologramNear(armadillo: Armadillo): ArmorStand? {
        val level = mc.level ?: return null
        val box = armadillo.boundingBox.inflate(0.6, 2.0, 0.6)
        return level.getEntitiesOfClass(ArmorStand::class.java, box) { it.customName != null }
            .firstOrNull()
    }

    private fun targetPos(armadillo: Armadillo): Vec3 =
        Vec3(
            armadillo.x,
            armadillo.y + armadillo.bbHeight / 2.0 + aimHeightOffset.value,
            armadillo.z
        )
    private fun targetAngles(player: LocalPlayer, point: Vec3): Pair<Float, Float> {
        val dx = point.x - player.x
        val dy = point.y - (player.y + player.eyeHeight)
        val dz = point.z - player.z

        val dist = sqrt(dx * dx + dz * dz)
        val targetYaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val targetPitch = -Math.toDegrees(atan2(dy, dist)).toFloat()
        return targetYaw to targetPitch
    }

    private fun aimOffsetBlocks(player: LocalPlayer, point: Vec3): Double {
        val eye = player.eyePosition
        val dir = player.lookAngle
        val toTarget = point.subtract(eye)
        val projLength = toTarget.dot(dir)
        if (projLength <= 0.0) return toTarget.length()
        val closestPointOnRay = eye.add(dir.scale(projLength))
        return closestPointOnRay.distanceTo(point)
    }

    private fun isAimedAt(player: LocalPlayer, point: Vec3): Boolean {
        return aimOffsetBlocks(player, point) <= aimToleranceBlocks.value
    }

    private fun hasClearShotTo(player: LocalPlayer, point: Vec3): Boolean {
        val level = mc.level ?: return false
        val ctx = ClipContext(
            player.eyePosition,
            point,
            ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE,
            player
        )
        val hit = level.clip(ctx)
        return hit.type == HitResult.Type.MISS
    }

    private fun findClearAimPoint(player: LocalPlayer, armadillo: Armadillo): Vec3? {
        val base = targetPos(armadillo)

        lastClearOffset?.let { offset ->
            val point = base.add(offset)
            if (hasClearShotTo(player, point)) return point
        }

        val step = aimSearchStep.value
        val maxHeight = aimSearchMaxHeight.value
        val radiusXZ = aimSearchRadiusXZ.value

        data class Candidate(val offset: Vec3, val distSq: Double)
        val candidates = mutableListOf<Candidate>()

        var dy = 0.0
        while (dy <= maxHeight) {
            var dx = -radiusXZ
            while (dx <= radiusXZ) {
                var dz = -radiusXZ
                while (dz <= radiusXZ) {
                    val offset = Vec3(dx, dy, dz)
                    candidates.add(Candidate(offset, offset.lengthSqr()))
                    dz += step
                }
                dx += step
            }
            dy += step
        }
        candidates.sortBy { it.distSq }

        for (candidate in candidates) {
            val point = base.add(candidate.offset)
            if (hasClearShotTo(player, point)) {
                lastClearOffset = candidate.offset
                return point
            }
        }
        lastClearOffset = null
        return null
    }

    private fun smoothLookAtFrame(player: LocalPlayer, armadillo: Armadillo) {
        val look = humanLook ?: return
        val aimPoint = currentAimPoint ?: targetPos(armadillo)

        val now = System.nanoTime()
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now
            return
        }

        var deltaTime = (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now

        if (deltaTime <= 0f || deltaTime > 0.1f) deltaTime = 1f / 60f

        val (targetYaw, targetPitch) = targetAngles(player, aimPoint)
        val (newYaw, newPitch) = look.step(player.yRot, player.xRot, targetYaw, targetPitch, deltaTime)
        player.yRot = newYaw
        player.xRot = newPitch
    }

    private fun softLookAtFrame(player: LocalPlayer, armadillo: Armadillo) {
        // Once the pangolin gets close to us, stop rotating the camera.
        if (player.distanceTo(armadillo) < 2.0f) {
            return
        }

        val look = confirmHumanLook ?: return

        val now = System.nanoTime()
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now
            return
        }

        var deltaTime = (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        if (deltaTime <= 0f || deltaTime > 0.1f) deltaTime = 1f / 60f

        val (targetYaw, targetPitch) = targetAngles(player, targetPos(armadillo))
        val (newYaw, newPitch) =
            look.step(player.yRot, player.xRot, targetYaw, targetPitch, deltaTime)

        player.yRot = newYaw
        player.xRot = newPitch
    }

    private fun attemptCatch(player: LocalPlayer, armadillo: Armadillo) {
        val slotBeforeSwitch = player.inventory.selectedSlot

        if (!switchToLasso(player)) {
            if (debugLog.value) println("[PangolinCatcher] Lasso missing → stop")
            stopAndReset()
            return
        }

        if (restorePreviousItem.value && previousSlot == null) {
            previousSlot = slotBeforeSwitch
        }

        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)


        confirmHumanLook = HumanLook(
            smoothTimeSeconds = (confirmSmoothnessMs.value / 1000.0).toFloat(),
            maxDegreesPerSecond = confirmMaxTurnSpeed.value.toFloat(),
            jitterDegrees = aimJitter.value.toFloat()
        )

        state = PangolinCatcherState.CONFIRMING
        confirmDeadline = System.currentTimeMillis() + confirmTimeoutMs.value.toLong()

        if (debugLog.value) println("[PangolinCatcher] Lasso thrown → waiting for chat result")
    }

    private fun switchToLasso(player: LocalPlayer): Boolean {
        for (slot in 0..8) {
            val stack = player.inventory.getItem(slot)
            if (stack.isEmpty) continue
            if (stack.hoverName.string.contains(lassoItemName, ignoreCase = true)) {
                player.inventory.setSelectedSlot(slot)
                return true
            }
        }
        return false
    }

    private fun onThrowResult(success: Boolean) {
        if (success) {
            if (playSound.value) {
                val id = Identifier.tryParse(soundId)

                val sound =
                    id?.let {
                        BuiltInRegistries.SOUND_EVENT
                            .get(it)
                            .orElse(null)
                            ?.value()
                    }

                if (sound != null) {
                    mc.soundManager.play(
                        SimpleSoundInstance.forUI(sound, 1.0f)
                    )
                }
            }

            if (showTitle.value) {
                mc.gui.setTitle(
                    Component.literal(
                        titleText.replace('&', '\u00A7')
                    )
                )
                mc.gui.setTimes(5, 40, 10)
            }

            stopAndReset()
            return
        }

        // Failure/miss: either requeue onto the same (or next) target and
        // keep the session alive, or fully stop, based on the setting.
        if (retryOnMiss.value) {
            if (debugLog.value) println("[PangolinCatcher] Miss → retrying")
            requeueForNextTarget()
        } else {
            if (debugLog.value) println("[PangolinCatcher] Miss → stopping (retry disabled)")
            stopAndReset()
        }
    }

    private fun onSessionComplete() {
        if (showTitle.value) {
            mc.gui.setTitle(Component.literal(titleText.replace('&', '\u00A7')))
            mc.gui.setTimes(5, 40, 10)
        }

        if (debugLog.value) println("[PangolinCatcher] Session-complete → stopAndReset()")
        stopAndReset()
    }

    private fun requeueForNextTarget() {
        if (debugLog.value)
            println("[PangolinCatcher] Requeue → back to IDLE")

        state = PangolinCatcherState.IDLE
        target = null
        humanLook = null
        confirmHumanLook = null
        lastTargetPos = null
        eligibleSince = null
        currentAimPoint = null
        lastClearOffset = null
        lastFrameNanos = 0L
    }

    private fun stopAndReset() {
        if (debugLog.value)
            println("[PangolinCatcher] Full reset")

        previousSlot?.let { slot ->
            mc.player?.inventory?.setSelectedSlot(slot)
        }
        previousSlot = null

        armed = false

        state = PangolinCatcherState.IDLE
        target = null
        humanLook = null
        confirmHumanLook = null
        lastTargetPos = null
        eligibleSince = null
        currentAimPoint = null
        lastClearOffset = null
        lastFrameNanos = 0L
    }
}