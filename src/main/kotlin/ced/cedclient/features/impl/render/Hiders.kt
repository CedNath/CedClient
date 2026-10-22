package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.particle.Particle
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import org.lwjgl.glfw.GLFW

/**
 * Declutter: hide the chat HUD (hold a key to peek), particles and real players near you.
 * Hooks: ChatHudHideMixin (chat), ParticleEngineMixin (particles), EntityRenderDispatcherMixin + PlayerRendererMixin (players).
 */
object Hiders : Module(
    "Hiders",
    Category.Render,
    "Hides the chat, nearby particles and nearby players."
) {
    private val mc get() = Minecraft.getInstance()

    private val hideChat = BooleanSetting("Hide Chat", false, "Hides the chat HUD completely unless the peek key is held (the chat screen still works).")
    private val peekKey = KeybindSetting("Peek Chat", GLFW.GLFW_KEY_UNKNOWN, "Hold to preview chat while Hide Chat is on.")
        .withDependency { hideChat.value }
    private val hideParticles = BooleanSetting("Nearby Particles", false, "Hides all particles within a radius around you.")
    private val particleRadius = NumberSetting("Particle Radius", 4.0, 1.0, 32.0, 0.5, "Radius around you to hide particles in.")
        .withDependency { hideParticles.value }
    private val playerMode = DropdownSetting("Nearby Players", listOf("Off", "Hide", "Shrink"), "Off", "Hide or shrink real players near you. Does not affect NPCs.")
    private val playerRadius = NumberSetting("Player Radius", 4.0, 1.0, 32.0, 0.5, "Radius around you to affect players in.")
        .withDependency { playerMode.value != "Off" }
    private val scaleX = NumberSetting("Scale X", 0.3, 0.05, 1.0, 0.05, "Shrunk player width.").withDependency { playerMode.value == "Shrink" }
    private val scaleY = NumberSetting("Scale Y", 0.3, 0.05, 1.0, 0.05, "Shrunk player height.").withDependency { playerMode.value == "Shrink" }
    private val scaleZ = NumberSetting("Scale Z", 0.3, 0.05, 1.0, 0.05, "Shrunk player depth.").withDependency { playerMode.value == "Shrink" }

    init { addSettings(hideChat, peekKey, hideParticles, particleRadius, playerMode, playerRadius, scaleX, scaleY, scaleZ) }

    @JvmStatic fun hideChatHud(): Boolean {
        if (!isEnabled || !hideChat.value || mc.screen is ChatScreen) return false
        val key = peekKey.value
        return key < 0 || !InputConstants.isKeyDown(mc.window, key)
    }

    @JvmStatic fun hideParticle(p: Particle): Boolean {
        if (!isEnabled || !hideParticles.value) return false
        val me = mc.player ?: return false
        return p.boundingBox.center.distanceToSqr(me.position()) <= particleRadius.value * particleRadius.value
    }

    /** Real players only (UUID v4); Hypixel NPCs use other versions. */
    private fun affected(e: Entity): Boolean {
        if (!isEnabled || playerMode.value == "Off" || e !is Player) return false
        val me = mc.player ?: return false
        if (e === me || e.uuid.version() != 4) return false
        return e.distanceToSqr(me) <= playerRadius.value * playerRadius.value
    }

    @JvmStatic fun hidePlayer(e: Entity) = playerMode.value == "Hide" && affected(e)
    @JvmStatic fun shrinks(e: Entity) = playerMode.value == "Shrink" && affected(e)
    @JvmStatic fun shrinkX() = scaleX.value.toFloat()
    @JvmStatic fun shrinkY() = scaleY.value.toFloat()
    @JvmStatic fun shrinkZ() = scaleZ.value.toFloat()
}
