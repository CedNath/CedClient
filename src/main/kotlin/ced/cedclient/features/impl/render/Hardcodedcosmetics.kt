package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.state.CosmeticsSync
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import kotlin.math.cos
import kotlin.math.sin


object HardcodedCosmetics : Module(
    "Cosmetics",
    Category.Cosmetic,
    "Shows Custom built-in cosmetic tag on your client.",
    defaultEnabled = true
) {

    // Chat extras (ChatEmotes). These work whether or not the cosmetic tags themselves are on.
    val chatEmotes = BooleanSetting("Chat Emotes", true, "Show Discord emotes in chat: :sob: (or the emoji itself) becomes the emote image. Also plays meme sounds for :moyai: :white_check_mark: :x: :spider: :scream: :fah: :sus: :dictator:.")
    val vineBoom = BooleanSetting("Vine Boom on 🗿", true, "Play the vine boom when someone sends 🗿 or :moyai: in chat.")
    val vineBoomVolume = NumberSetting("Vine Boom Volume", 1.0, 0.1, 1.0, 0.05, "Ignores the UI / Players sliders; only Master Volume also applies.")
    /** Emote sound volume, used instead of the Master slider. Set with /emotevolume, so hidden from the ClickGUI. */
    val emoteVolume = NumberSetting("Emote Volume", 0.1, 0.0, 1.0, 0.01, "Set with /emotevolume <0-100>.").apply { hide() }

    val haloParticles = BooleanSetting("Halo Particles", true, "Show the particles players picked for their halo on the site.")

    init {
        // Vine boom + its volume are deliberately not registered: hidden from the ClickGUI and not saved, so they
        // always run at their defaults (on, full volume).
        addSettings(chatEmotes, haloParticles, emoteVolume)
        ChatEmotes // load the emote table
        ClientTickEvents.END_CLIENT_TICK.register { tickHaloParticles(it) }
    }

    /** Particles around synced halos (flags in CosmeticsSync HALO_PARTICLES order: sparkle, heart, flame, dust). */
    private fun tickHaloParticles(mc: Minecraft) {
        if (!isEnabled || !haloParticles.value || mc.isPaused) return
        val level = mc.level ?: return
        val self = mc.player ?: return
        for (player in level.players()) {
            if (player.isInvisible || player.distanceToSqr(self) > 48 * 48) continue
            if (player === self && mc.options.cameraType.isFirstPerson) continue
            val o = CosmeticsSync.getOverride(player.gameProfile.name) ?: continue
            val color = o.halo ?: continue
            if (o.haloParticles == 0) continue
            for (bit in 0 until 4) {
                if (o.haloParticles shr bit and 1 == 0) continue
                // Hearts and flames are loud; sparkles and dust are subtle, so they come more often.
                if (level.random.nextFloat() > (if (bit == 1) 0.05f else if (bit == 2) 0.15f else 0.3f)) continue
                val particle: ParticleOptions = when (bit) {
                    0 -> ParticleTypes.END_ROD
                    1 -> ParticleTypes.HEART
                    2 -> ParticleTypes.SMALL_FLAME
                    else -> DustParticleOptions(color, 0.8f)
                }
                val a = level.random.nextDouble() * Math.PI * 2
                val y = player.y + player.bbHeight * player.scale + 0.25
                level.addParticle(particle, player.x + cos(a) * 0.32, y, player.z + sin(a) * 0.32, 0.0, if (bit == 0) 0.01 else 0.0, 0.0)
            }
        }
    }

    /**
     * Toggling the Cosmetics button off and on again forces an immediate
     * re-download of cosmetics.json instead of waiting for the next poll.
     */
    override fun onEnable() {
        CosmeticsSync.forceSync()
    }
}