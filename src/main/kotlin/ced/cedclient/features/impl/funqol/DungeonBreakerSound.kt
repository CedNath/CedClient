package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.skyblockId
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/** Swaps the block-break sound for a sound of your choice while you hold a Dungeonbreaker (hook: SoundEngineMixin). */
object DungeonBreakerSound : Module(
    "Dungeonbreaker Sound",
    Category.Skyblock,
    "Replaces the block break sounds of the Dungeonbreaker with a custom sound."
) {
    private val presets = linkedMapOf(
        "XP Orb" to "minecraft:entity.experience_orb.pickup",
        "Iron Xylophone" to "minecraft:block.note_block.iron_xylophone",
        "Bell" to "minecraft:block.bell.use",
        "Chime" to "minecraft:block.amethyst_block.chime",
        "Anvil Land" to "minecraft:block.anvil.land",
        "None" to "",
    )
    private val sound = DropdownSetting("Sound", presets.keys.toList() + "Custom", "XP Orb", "None mutes the break sound.")
    private val customId = TextSetting("Custom Sound ID", "minecraft:entity.player.levelup", "Any sound ID, e.g. minecraft:block.bell.use", maxLength = 64)
        .withDependency { sound.value == "Custom" }
    private val volume = NumberSetting("Volume", 1.0, 0.0, 1.0, 0.05)
    private val test = ActionSetting("Play Test Sound") { play() }

    private var replaying = false

    init { addSettings(sound, customId, volume, test) }

    @JvmStatic fun shouldCancel(instance: SoundInstance): Boolean {
        if (!isEnabled || replaying) return false
        val mc = Minecraft.getInstance()
        val me = mc.player ?: return false
        if (me.mainHandItem.skyblockId != "DUNGEONBREAKER") return false
        if (!instance.identifier.path.endsWith(".break")) return false
        if (me.distanceToSqr(instance.x, instance.y, instance.z) > 12 * 12) return false
        play()
        return true
    }

    private fun play() {
        val id = (if (sound.value == "Custom") customId.value else presets[sound.value]).orEmpty().trim()
        if (id.isEmpty()) return
        val event = runCatching { BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.parse(id)).orElse(null) }.getOrNull() ?: return
        replaying = true
        try { Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(event, 1f, volume.value.toFloat())) } finally { replaying = false }
    }
}
