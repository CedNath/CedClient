//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.state.Sidebar
import ced.cedclient.utils.HumanLook
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Crimson Isle Dojo (sidebar "Challenge: ...").
 *  - Test of Discipline: the zombie nearest your crosshair decides the sword -- helmet Leather / Iron / Gold / Diamond
 *    -> Wooden / Iron / Golden / Diamond Sword from your hotbar. Only switches; you still hit.
 *  - Test of Control: keeps your aim on the Wither Skeleton (plain skeletons are decoys), leading its movement a few
 *    ticks, so when it jumps to a new spot the aim follows straight there.
 */
object DojoHelper : Module(
    "Dojo Helper",
    Category.Cheats,
    "Discipline: switches to the right sword for the zombie you look at. Control: aims at the Wither Skeleton."
) {
    private val mc get() = Minecraft.getInstance()

    private val discipline = BooleanSetting("Discipline Sword Swap", true, "Pick the sword that matches the helmet of the zombie you look at.")
    private val control = BooleanSetting("Control Aim", true, "Keep your aim on the Wither Skeleton, not the decoy skeletons.")
    private val prediction = NumberSetting("Prediction Ticks", 2.0, 0.0, 6.0, 0.5, "Aim this many ticks ahead of the skeleton's movement.")
    private val smoothnessMs = NumberSetting("Aim Smoothness ms", 60.0, 10.0, 300.0, 10.0, "Lower snaps faster.")

    private val swordFor = mapOf<Item, Item>(
        Items.LEATHER_HELMET to Items.WOODEN_SWORD,
        Items.IRON_HELMET to Items.IRON_SWORD,
        Items.GOLDEN_HELMET to Items.GOLDEN_SWORD,
        Items.DIAMOND_HELMET to Items.DIAMOND_SWORD,
    )

    private var look: HumanLook? = null
    private var lastFrameNanos = 0L
    private var aimTarget: WitherSkeleton? = null

    init {
        smoothnessMs.advanced = true
        addSettings(discipline, control, prediction, smoothnessMs)

        ClientTickEvents.END_CLIENT_TICK.register { if (isEnabled) tick() }
        // Aim every rendered frame, so it doesn't step at 20 Hz.
        LevelRenderEvents.START_MAIN.register { if (isEnabled) aim() }
    }

    override fun onDisable() {
        aimTarget = null
        look = null
    }

    private fun challenge(name: String) = Sidebar.lines.any { it.contains("Challenge: $name") }

    private fun tick() {
        val player = mc.player ?: return
        aimTarget = if (control.value && challenge("Control")) {
            mc.level?.entitiesForRendering()?.filterIsInstance<WitherSkeleton>()
                ?.filter { it.isAlive }?.minByOrNull { player.distanceTo(it) }
        } else null
        if (aimTarget == null) look = null

        if (discipline.value && challenge("Discipline")) {
            val zombie = mc.level?.entitiesForRendering()?.filterIsInstance<Zombie>()
                ?.filter { it.isAlive && player.distanceTo(it) <= 32f }
                ?.map { it to angleTo(player, it.position().add(0.0, it.bbHeight / 2.0, 0.0)) }
                ?.filter { it.second <= 8f }?.minByOrNull { it.second }?.first ?: return
            val sword = swordFor[zombie.getItemBySlot(EquipmentSlot.HEAD).item] ?: return
            val slot = (0..8).firstOrNull { player.inventory.getItem(it).item == sword } ?: return
            if (player.inventory.selectedSlot != slot) player.inventory.setSelectedSlot(slot)
        }
    }

    private fun aim() {
        val player = mc.player ?: return
        val target = aimTarget?.takeIf { it.isAlive } ?: return
        val l = look ?: HumanLook((smoothnessMs.value / 1000.0).toFloat(), 2000f, 0.2f).also { look = it; lastFrameNanos = 0L }
        val now = System.nanoTime()
        var dt = if (lastFrameNanos == 0L) 1f / 60f else (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        if (dt <= 0f || dt > 0.1f) dt = 1f / 60f
        val (yaw, pitch) = anglesTo(player, predicted(target))
        val (newYaw, newPitch) = l.step(player.yRot, player.xRot, yaw, pitch, dt)
        player.yRot = newYaw
        player.xRot = newPitch
    }

    /** Middle of [e], [prediction] ticks ahead along its last tick's movement. */
    private fun predicted(e: Entity): Vec3 {
        val moved = e.position().subtract(e.xOld, e.yOld, e.zOld)
        return e.position().add(moved.scale(prediction.value)).add(0.0, e.bbHeight / 2.0, 0.0)
    }

    private fun anglesTo(player: LocalPlayer, p: Vec3): Pair<Float, Float> {
        val dx = p.x - player.x
        val dy = p.y - (player.y + player.eyeHeight)
        val dz = p.z - player.z
        val yaw = Math.toDegrees(atan2(dz, dx)).toFloat() - 90f
        val pitch = -Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz))).toFloat()
        return yaw to pitch
    }

    /** How far (degrees, yaw + pitch combined) [p] is from where the player looks. */
    private fun angleTo(player: LocalPlayer, p: Vec3): Float {
        val (yaw, pitch) = anglesTo(player, p)
        var dYaw = (yaw - player.yRot) % 360f
        if (dYaw > 180f) dYaw -= 360f
        if (dYaw < -180f) dYaw += 360f
        return abs(dYaw) + abs(pitch - player.xRot)
    }
}
//#endif
