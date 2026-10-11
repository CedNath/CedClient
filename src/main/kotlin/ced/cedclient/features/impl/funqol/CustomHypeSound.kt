package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ItemClickEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.utils.PingTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import kotlin.math.abs

/**
 * Same idea as Devonian's Custom Hype Sound: right after you right-click a wither blade, Wither Impact's
 * explosion sound is swapped for a chosen one, and the zombie-villager "cure" sound it also plays is muted.
 * Default sound matches Devonian (iron xylophone). Hooked from SoundEngineMixin.
 */
object CustomHypeSound : Module(
    "CustomHypeSound",
    Category.Skyblock,
    "Replaces the Wither Impact explosion (Hyperion etc.) with a sound of your choice"
) {
    private val mc = Minecraft.getInstance()

    // Devonian's sound list, default first; "Custom" reads the ID field below.
    private val presets = linkedMapOf(
        "Iron Xylophone" to "minecraft:block.note_block.iron_xylophone",
        "Blaze Hurt" to "minecraft:entity.blaze.hurt",
        "XP Orb" to "minecraft:entity.experience_orb.pickup",
        "Vault Break" to "minecraft:block.vault.break",
        "Elder Guardian Land" to "minecraft:entity.elder_guardian.hurt_land",
        "Totem" to "minecraft:item.totem.use",
        "Sculk Catalyst" to "minecraft:block.sculk_catalyst.hit",
        "Ender Chest Close" to "minecraft:block.ender_chest.close",
        "None" to "",
    )

    private val sound = DropdownSetting("Sound", presets.keys.toList() + "Custom", "Iron Xylophone", "None mutes the explosion.")
    private val customId = TextSetting("Custom Sound ID", "minecraft:entity.player.levelup", "Any sound ID, e.g. minecraft:block.bell.use", maxLength = 64)
        .withDependency { sound.value == "Custom" }
    private val volume = NumberSetting("Volume", 1.0, 0.0, 1.0, 0.05)
    private val pitch = NumberSetting("Pitch", 1.0, 0.5, 2.0, 0.05)
    private val test = ActionSetting("Play Test Sound") { play() }

    private val witherBlades = setOf("HYPERION", "VALKYRIE", "SCYLLA", "ASTRAEA")
    private const val EXPLODE = "minecraft:entity.generic.explode"
    private const val CURE = "minecraft:entity.zombie_villager.cure"
    private const val CURE_PITCH = 0.6984127f

    private var lastClick = 0L
    private var replaying = false

    init {
        addSettings(sound, customId, volume, pitch, test)
        on<ItemClickEvent> { event ->
            val id = event.itemInHand?.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")
            if (id in witherBlades) lastClick = System.currentTimeMillis()
        }
    }

    /** True if this sound should not play. The explosion is swapped for ours. */
    @JvmStatic
    fun shouldCancel(instance: SoundInstance): Boolean {
        if (!isEnabled || replaying || lastClick == 0L) return false
        // Devonian allows 3 server ticks after the click; the sound also has to travel back, so add the ping.
        if (System.currentTimeMillis() - lastClick > 150 + PingTracker.latest().coerceAtLeast(100)) return false
        val id = instance.identifier.toString()
        if (instance.volume != 1f) return false
        return when {
            id == CURE && abs(instance.pitch - CURE_PITCH) < 0.001f -> true
            id == EXPLODE && instance.pitch == 1f -> { play(); true }
            else -> false
        }
    }

    private fun play() {
        val id = if (sound.value == "Custom") customId.value else presets[sound.value].orEmpty()
        val event = Identifier.tryParse(id)?.let { BuiltInRegistries.SOUND_EVENT.getValue(it) } ?: return
        replaying = true
        try {
            mc.soundManager.play(SimpleSoundInstance.forUI(event, pitch.value.toFloat(), volume.value.toFloat()))
        } finally {
            replaying = false
        }
    }
}
