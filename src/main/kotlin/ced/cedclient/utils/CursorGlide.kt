package ced.cedclient.utils

import ced.cedclient.mixin.accessor.AbstractContainerScreenAccessor
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.Slot
import org.lwjgl.glfw.GLFW
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * Moves the real mouse cursor to a container slot along a slightly curved, eased path, then runs a click there.
 * Used by menu automation (Anvil Helper, Auto Fusion) so the cursor visibly travels to what gets clicked.
 * One glide at a time; [busy] while it runs. Updated every frame from the open screen's render.
 */
object CursorGlide {
    private val mc get() = Minecraft.getInstance()

    private var screen: Screen? = null
    private var hooked: Screen? = null
    private var fromX = 0.0
    private var fromY = 0.0
    private var toX = 0.0
    private var toY = 0.0
    private var bendX = 0.0
    private var bendY = 0.0
    private var startAt = 0L
    private var duration = 0L
    private var clickAt = 0L
    private var onArrive: (() -> Unit)? = null

    val busy get() = onArrive != null

    /**
     * Glide to [slot] in [container] and run [click] once there (skipped if that screen closed meanwhile).
     * Glide plus the pause before the click fits in [budgetMs] (the caller's click delay), so a lower delay moves faster.
     */
    fun moveTo(container: AbstractContainerScreen<*>, slot: Slot, budgetMs: Long, click: () -> Unit) {
        if (busy) return
        val acc = container as AbstractContainerScreenAccessor
        val w = mc.window
        // GUI pixels -> window pixels (inverse of MouseHandler.getScaledXPos); aim somewhere inside the slot, not dead centre.
        val sx = w.screenWidth.toDouble() / w.guiScaledWidth
        val sy = w.screenHeight.toDouble() / w.guiScaledHeight
        fromX = mc.mouseHandler.xpos()
        fromY = mc.mouseHandler.ypos()
        toX = (acc.`ced$getLeftPos`() + slot.x + 3 + Random.nextDouble(10.0)) * sx
        toY = (acc.`ced$getTopPos`() + slot.y + 3 + Random.nextDouble(10.0)) * sy
        val dist = hypot(toX - fromX, toY - fromY)
        // Curve: push the midpoint sideways by up to ~15% of the distance.
        val bend = (Random.nextDouble() - 0.5) * 0.3 * dist
        bendX = (fromX + toX) / 2 - (toY - fromY) / max(dist, 1.0) * bend
        bendY = (fromY + toY) / 2 + (toX - fromX) / max(dist, 1.0) * bend
        startAt = System.currentTimeMillis()
        val glide = (110 + dist * 0.35 + Random.nextInt(90)).coerceAtMost(650.0)
        val pause = 40.0 + Random.nextInt(70)
        val scale = (budgetMs / (glide + pause)).coerceIn(0.0, 1.0)
        duration = max((glide * scale).toLong(), 1L) // never 0: update() divides by it
        clickAt = startAt + duration + (pause * scale).toLong()
        screen = container
        onArrive = click
        if (hooked !== container) {
            hooked = container
            ScreenEvents.afterExtract(container).register { s, _, _, _, _ -> if (s === screen) update() }
        }
    }

    private fun update() {
        val click = onArrive ?: return
        if (mc.screen !== screen) {
            onArrive = null // menu changed under us; the caller re-plans
            return
        }
        val now = System.currentTimeMillis()
        val t = ((now - startAt).toDouble() / duration).coerceIn(0.0, 1.0)
        val e = if (t < 0.5) 4 * t * t * t else 1 - Math.pow(-2 * t + 2, 3.0) / 2 // ease in-out cubic
        val u = 1 - e
        val x = u * u * fromX + 2 * u * e * bendX + e * e * toX
        val y = u * u * fromY + 2 * u * e * bendY + e * e * toY
        GLFW.glfwSetCursorPos(mc.window.handle(), x, y)
        if (now >= clickAt) {
            onArrive = null
            click()
        }
    }

    /** Drop any glide in progress (module stopped). */
    fun cancel() {
        onArrive = null
    }
}
