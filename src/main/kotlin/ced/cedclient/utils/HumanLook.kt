package ced.cedclient.utils

import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.reflect.KMutableProperty0

/**
 * High‑FPS version of HumanLook.
 *
 * Designed for render‑tick updates (deltaTime = tickDelta),
 * giving ultra‑smooth camera motion at 120–300 FPS.
 *
 * Key changes:
 * - Stable smoothing at tiny deltaTime values
 * - Jitter scaled by real time instead of ticks
 * - Velocity stored per‑axis without floatArray hacks
 * - No overshoot when deltaTime < smoothTime
 */
class HumanLook(
    var smoothTimeSeconds: Float = 0.18f,
    var maxDegreesPerSecond: Float = 900f,
    var jitterDegrees: Float = 0.6f
) {
    private var yawVel = 0f
    private var pitchVel = 0f

    private var time = 0f
    private val jitterPhaseYaw = Random.nextFloat() * 1000f
    private val jitterPhasePitch = Random.nextFloat() * 1000f

    /**
     * Call every render frame (tickDelta).
     * Returns new (yaw, pitch).
     */
    fun step(
        currentYaw: Float,
        currentPitch: Float,
        targetYaw: Float,
        targetPitch: Float,
        deltaTime: Float
    ): Pair<Float, Float> {

        // accumulate real time for jitter
        time += deltaTime

        val angleToTarget = abs(shortestAngleDiff(currentYaw, targetYaw))
        val jitterScale = (angleToTarget / 15f).coerceIn(0f, 1f)

        val jitterYaw = jitter(time, jitterPhaseYaw) * jitterDegrees * jitterScale
        val jitterPitch = jitter(time, jitterPhasePitch) * jitterDegrees * jitterScale

        val wrappedTargetYaw = currentYaw + shortestAngleDiff(currentYaw, targetYaw + jitterYaw)

        val newYaw = smoothDampHF(currentYaw, wrappedTargetYaw, ::yawVel, smoothTimeSeconds, maxDegreesPerSecond, deltaTime)
        val newPitch = smoothDampHF(currentPitch, targetPitch + jitterPitch, ::pitchVel, smoothTimeSeconds, maxDegreesPerSecond, deltaTime)

        return newYaw to newPitch
    }

    private fun jitter(t: Float, phase: Float): Float {
        val a = sin(t * 3.1f + phase)          // faster jitter at high FPS
        val b = sin(t * 0.9f + phase * 1.7f) * 0.5f
        return (a + b) / 1.5f
    }

    private fun shortestAngleDiff(from: Float, to: Float): Float {
        var diff = (to - from) % 360f
        if (diff > 180f) diff -= 360f
        if (diff < -180f) diff += 360f
        return diff
    }

    /**
     * High‑FPS stable smoothDamp.
     *
     * Unity's original formula breaks at tiny deltaTime values.
     * This version uses exponential decay based on real time.
     */
    private fun smoothDampHF(
        current: Float,
        target: Float,
        velocityRef: KMutableProperty0<Float>,
        smoothTime: Float,
        maxSpeed: Float,
        deltaTime: Float
    ): Float {
        val v = velocityRef.get()

        val st = smoothTime.coerceAtLeast(0.0001f)
        val omega = 2f / st

        val change = (current - target).coerceIn(-maxSpeed * st, maxSpeed * st)
        val adjustedTarget = current - change

        val exp = kotlin.math.exp(-omega * deltaTime)

        val temp = (v + omega * change) * deltaTime
        val newVel = (v - omega * temp) * exp
        velocityRef.set(newVel)

        val output = adjustedTarget + (change + temp) * exp

        return output
    }
}
