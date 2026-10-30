package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The game's own cherry blossom petals and tinted leaves falling from a ring around you. They are made straight in the
 * particle engine, not through the level's addParticle: that path throws particles away under the Particles setting
 * (Decreased / Minimal), which is why petals went missing for people who play with it lowered.
 */
object FallingPetals : Module(
    "Falling Petals",
    Category.Cosmetic,
    "Cherry petals and leaves falling in a circle around you."
) {
    private val kind = DropdownSetting("Kind", listOf("Both", "Petals", "Leaves"), "Both", "What falls.")
    private val amount = NumberSetting("Amount", 12.0, 1.0, 100.0, 1.0, "How many spawn per second.")
    private val radius = NumberSetting("Radius", 3.0, 0.5, 10.0, 0.5, "Radius of the circle around you, in blocks.")
    private val height = NumberSetting("Height", 4.0, 1.0, 10.0, 0.5, "How far above you they spawn.")

    private val leafColors = intArrayOf(0x7CB342, 0x9CCC65, 0x558B2F, 0xC0CA33, 0xE0A030, 0xD2691E)
    private var carry = 0.0

    init {
        addSettings(kind, amount, radius, height)
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    private fun options(): ParticleOptions {
        val leaf = when (kind.value) { "Leaves" -> true; "Petals" -> false; else -> Random.nextBoolean() }
        return if (leaf) ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, leafColors.random()) else ParticleTypes.CHERRY_LEAVES
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (mc.level == null || !isEnabled || mc.isPaused) return
        carry += amount.value / 20.0
        while (carry >= 1.0) {
            carry -= 1.0
            val angle = Random.nextDouble() * 2 * PI
            val r = radius.value * (0.85 + Random.nextDouble() * 0.3)
            // A gentle push along the circle, so they drift around you as they fall.
            val orbit = 0.015
            mc.particleEngine.createParticle(
                options(),
                player.x + cos(angle) * r, player.y + height.value * (0.7 + Random.nextDouble() * 0.3), player.z + sin(angle) * r,
                -sin(angle) * orbit, 0.0, cos(angle) * orbit,
            )
        }
    }

    override fun onDisable() { carry = 0.0 }
}
