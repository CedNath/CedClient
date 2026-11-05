package ced.cedclient.features.impl.funqol

import ced.cedclient.utils.Keys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ced.cedclient.config.CedPaths
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.mixin.accessor.AbstractContainerScreenAccessor
import ced.cedclient.utils.Color
import ced.cedclient.utils.Debug
import ced.cedclient.utils.skyblockId
import ced.cedclient.utils.skyblockUuid
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

internal val mc get() = Minecraft.getInstance()

internal fun say(msg: String) = mc.player?.sendSystemMessage(Component.literal("§8[§dCC§8] §7$msg"))

internal fun hovered(screen: Screen): Slot? = (screen as? AbstractContainerScreen<*>)?.let { (it as AbstractContainerScreenAccessor).`ced$getHoveredSlot`() }

internal fun keyDown(key: Int) = key > 0 && Keys.isDown(key)

/** The slot of the open menu that shows player-inventory slot [index] (0-8 hotbar, 9-35 main), or null. */
internal fun menuSlot(screen: AbstractContainerScreen<*>, index: Int): Slot? =
    screen.menu.slots.firstOrNull { it.container is Inventory && it.containerSlot == index }

internal fun ItemStack.protectKey(): String? = skyblockUuid ?: skyblockId?.let { "id:$it" }

// ------------------------------------------------------------------------------------------------ Enchant Colors


// ------------------------------------------------------------------------------------------------ Protect Item


// ------------------------------------------------------------------------------------------------ Slot Binding


// ------------------------------------------------------------------------------------------------ Wardrobe Keys


// ------------------------------------------------------------------------------------------------ SB Kick Timer

/** Countdown after "kicked while joining" / "problem joining SkyBlock" so you know when to try again. */
object SbKickTimer : RowHud("SB Kick Timer", "HUD counting down until you can rejoin SkyBlock after a failed join.", "sb_kick_timer", 3, 500) {
    private val cooldown = NumberSetting("Cooldown (s)", 60.0, 10.0, 300.0, 5.0, "How long to wait after a failed join.")
    private val color = ColorSetting("Color", Color(255, 170, 0), "Text colour.")
    private val readyColor = ColorSetting("Ready Color", Color(85, 255, 85), "Shown briefly when the wait is over.")
    private val sound = BooleanSetting("Sound", true, "Ping when you can rejoin.")

    private val KICK = Regex("""kicked while joining|problem joining SkyBlock|Couldn't warp you|try again in a moment""", RegexOption.IGNORE_CASE)
    private var until = 0L
    private var readyUntil = 0L

    override fun rows(): List<Triple<String, String, Int>>? {
        val now = System.currentTimeMillis()
        if (until != 0L) {
            if (now < until) return listOf(Triple("Rejoin in", "${(until - now + 999) / 1000}s", color.value.rgba))
            until = 0L; readyUntil = now + 5000
            Alerts.title("SB Kick Timer", "Rejoin SkyBlock!", "", 0x55FF55, sound.value, 1.3f)
        }
        return if (now < readyUntil) listOf(Triple("Rejoin", "Ready", readyColor.value.rgba)) else null
    }

    override fun preview() = listOf(Triple("Rejoin in", "42s", color.value.rgba))

    init {
        addSettings(cooldown, color, readyColor, sound)
        loadPosition()
        on<ChatMessageEvent> {
            if (!isEnabled || !KICK.containsMatchIn(it.unformattedText)) return@on
            until = System.currentTimeMillis() + (cooldown.value * 1000).toLong()
            Debug.line("SB Kick Timer", "started: ${it.unformattedText}")
        }
    }
}
