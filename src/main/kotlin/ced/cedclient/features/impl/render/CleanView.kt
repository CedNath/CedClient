package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile

/**
 * Clean View: in first person, stops rendering particles, arrows and other clutter right in front of
 * your face, so ability spam / teammates / stuck arrows can't block your view. Hooks:
 *   QuadParticleGroupMixin      -> particles (skipped like off-screen ones)
 *   EntityRenderDispatcherMixin -> entities (shouldRender)
 * Only things within [radius] blocks of the camera are hidden; everything further away renders as usual.
 */
object CleanView : Module(
    "Clean View",
    Category.Render,
    "In first person, hides particles, arrows and other clutter right in front of your face"
) {
    private val radius = NumberSetting("Radius", 2.5, 0.5, 8.0, 0.5, "Blocks around your camera that are kept clear.")
    private val particles = BooleanSetting("Particles", true, "Hide particles close to your face.")
    private val projectiles = BooleanSetting("Projectiles", true, "Hide arrows, fireballs, snowballs etc. close to your face.")
    private val players = BooleanSetting("Players", false, "Hide other players standing in your face.")
    private val armorStands = BooleanSetting("Armor Stands", true, "Hide armor stands / holograms close to your face.")
    private val items = BooleanSetting("Dropped Items", false, "Hide dropped items close to your face.")
    private val otherEntities = BooleanSetting("Other Entities", false, "Hide mobs and anything else close to your face.")

    init {
        addSettings(radius, particles, projectiles, players, armorStands, items, otherEntities)
    }

    private val mc get() = Minecraft.getInstance()

    private fun active(): Boolean = isEnabled && mc.options.cameraType.isFirstPerson

    /** Called per particle; true = skip it this frame. */
    @JvmStatic
    fun hideParticle(x: Double, y: Double, z: Double): Boolean {
        if (!particles.value || !active()) return false
        val cam = mc.gameRenderer.mainCamera.position()
        val dx = x - cam.x
        val dy = y - cam.y
        val dz = z - cam.z
        val r = radius.value
        return dx * dx + dy * dy + dz * dz < r * r
    }

    /** Called per entity with the camera position; true = don't render it. */
    @JvmStatic
    fun hideEntity(entity: Entity, camX: Double, camY: Double, camZ: Double): Boolean {
        if (!active()) return false
        val enabled = when (entity) {
            mc.player -> return false
            is Projectile -> projectiles.value
            is Player -> players.value
            is ArmorStand -> armorStands.value
            is ItemEntity -> items.value
            else -> otherEntities.value
        }
        if (!enabled) return false
        // Distance to the nearest point of the hitbox, so a big mob or a player standing inside you
        // counts even when its feet are further away than the radius.
        val box = entity.boundingBox
        val dx = maxOf(box.minX - camX, 0.0, camX - box.maxX)
        val dy = maxOf(box.minY - camY, 0.0, camY - box.maxY)
        val dz = maxOf(box.minZ - camZ, 0.0, camZ - box.maxZ)
        val r = radius.value
        return dx * dx + dy * dy + dz * dz < r * r
    }
}
