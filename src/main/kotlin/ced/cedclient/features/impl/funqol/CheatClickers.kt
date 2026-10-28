//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.mixin.accessor.MinecraftAccessor
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Debug
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/*
 * Cheat-jar clickers: Auto Clicker, Arrow Align Triggerbot, Lights Device Triggerbot. They click on their own, which
 * Hypixel does not allow: use at your own risk. All three act at most once per client tick and only with no screen open.
 * Not testable outside Hypixel (the triggerbots need an F7 run).
 */

/** Clicks the left and / or right mouse button for you at a random speed between a minimum and a maximum. */
object AutoClicker : Module(
    "Auto Clicker", Category.Cheats,
    "Clicks left / right for you at a random speed. At most one click per tick, so 20 clicks per second is the top."
) {
    private val left = BooleanSetting("Left Click", true)
    private val leftMin = NumberSetting("Left Min CPS", 8.0, 1.0, 20.0, 0.5)
    private val leftMax = NumberSetting("Left Max CPS", 12.0, 1.0, 20.0, 0.5)
    private val right = BooleanSetting("Right Click", false)
    private val rightMin = NumberSetting("Right Min CPS", 8.0, 1.0, 20.0, 0.5)
    private val rightMax = NumberSetting("Right Max CPS", 12.0, 1.0, 20.0, 0.5)
    private val whileHeld = BooleanSetting("Only While Held", true, "Clicks only while you hold that mouse button. Off: clicks all the time.")
    private val lookAt = DropdownSetting("Only When Looking At", listOf("Anything", "Entity", "Block"), "Anything")
    private val itemFilter = TextSetting("Held Item Contains", "", "Comma-separated words. Only clicks while the item in your hand has one of them in its name. Empty = any item.", maxLength = 64)

    private var nextLeft = 0L
    private var nextRight = 0L

    init {
        addSettings(left, leftMin, leftMax, right, rightMin, rightMax, whileHeld, lookAt, itemFilter)
        ClientTickEvents.END_CLIENT_TICK.register { mc -> tick(mc) }
    }

    override fun onDisable() { nextLeft = 0L; nextRight = 0L }

    private fun delayNanos(min: Double, max: Double): Long {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        val cps = if (hi > lo) lo + Math.random() * (hi - lo) else lo
        return (1_000_000_000.0 / cps).toLong()
    }

    private fun allowed(mc: Minecraft): Boolean {
        val player = mc.player ?: return false
        if (mc.gui.screen() != null) return false
        when (lookAt.value) {
            "Entity" -> if (mc.hitResult?.type != HitResult.Type.ENTITY) return false
            "Block" -> if (mc.hitResult?.type != HitResult.Type.BLOCK) return false
        }
        val words = itemFilter.value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (words.isNotEmpty()) {
            val held = player.mainHandItem.hoverName.string.lowercase()
            if (words.none { it in held }) return false
        }
        return true
    }

    private fun tick(mc: Minecraft) {
        if (!isEnabled || !allowed(mc)) return
        val now = System.nanoTime()
        val accessor = mc as MinecraftAccessor
        if (left.value && (!whileHeld.value || mc.mouseHandler.isLeftPressed) && now >= nextLeft) {
            nextLeft = now + delayNanos(leftMin.value, leftMax.value)
            accessor.cedStartAttack()
            Debug.line(name, "left click")
        }
        if (right.value && (!whileHeld.value || mc.mouseHandler.isRightPressed) && now >= nextRight) {
            nextRight = now + delayNanos(rightMin.value, rightMax.value)
            accessor.cedStartUseItem()
            Debug.line(name, "right click")
        }
    }
}

/** F7 phase 3 Arrow Align: right-clicks the frame under your crosshair when Arrow Align says it still needs clicks. */
object ArrowAlignTriggerbot : Module(
    "Arrow Align Triggerbot", Category.Cheats,
    "Turns the Arrow Align frame under your crosshair into place. Needs the Arrow Align module on (it works out the clicks)."
) {
    private val clickDelay = NumberSetting("Click Delay (ms)", 150.0, 50.0, 1000.0, 10.0)
    private var lastClick = 0L

    init {
        addSettings(clickDelay)
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (!isEnabled || mc.gui.screen() != null) return@register
            val player = mc.player ?: return@register
            val hit = mc.hitResult as? EntityHitResult ?: return@register
            val frame = hit.entity as? ItemFrame ?: return@register
            val now = System.currentTimeMillis()
            if (now - lastClick < clickDelay.value || ArrowAlign.clicksNeeded(frame) <= 0) return@register
            lastClick = now
            mc.gameMode?.interact(player, frame, hit, InteractionHand.MAIN_HAND)
            Debug.line(name, "clicked frame at ${frame.blockPosition()}")
        }
    }
}

/** F7 phase 3: right-clicks a lever under your crosshair that is still off, so the lights device ends with every lever on. */
object LightsDeviceTriggerbot : Module(
    "Lights Device Triggerbot", Category.Cheats,
    "Flips an off lever under your crosshair in F7 / M7 phase 3. Levers that are already on are left alone."
) {
    private val clickDelay = NumberSetting("Click Delay (ms)", 150.0, 50.0, 1000.0, 10.0)
    private var lastClick = 0L

    init {
        addSettings(clickDelay)
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (!isEnabled || mc.gui.screen() != null || !DungeonState.inDungeon || DungeonState.f7Phase != 3) return@register
            val level = mc.level ?: return@register
            val player = mc.player ?: return@register
            val hit = mc.hitResult as? BlockHitResult ?: return@register
            if (hit.type != HitResult.Type.BLOCK) return@register
            val state = level.getBlockState(hit.blockPos)
            if (state.block != Blocks.LEVER || state.getValue(BlockStateProperties.POWERED)) return@register
            val now = System.currentTimeMillis()
            if (now - lastClick < clickDelay.value) return@register
            lastClick = now
            mc.gameMode?.useItemOn(player, InteractionHand.MAIN_HAND, hit)
            Debug.line(name, "flipped lever at ${hit.blockPos}")
        }
    }
}
//#endif
