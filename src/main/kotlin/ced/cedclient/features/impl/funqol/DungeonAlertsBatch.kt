package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.state.DungeonState
import ced.cedclient.state.DungeonState.DungeonClass
import ced.cedclient.state.DungeonTeam
import ced.cedclient.utils.Color
import ced.cedclient.utils.skyblockId
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

private val ROMAN = mapOf('I' to 1, 'V' to 5, 'X' to 10)

/** "V" -> 5, "XIV" -> 14; 0 for anything that is not a plain numeral. */
private fun roman(text: String): Int {
    var total = 0
    var prev = 0
    for (c in text.reversed()) {
        val v = ROMAN[c] ?: return 0
        total += if (v < prev) -v else v
        prev = maxOf(prev, v)
    }
    return total
}

/**
 * One colour per dungeon class, used by the dungeon alert modules below (F7 Titles names). Unique: besides the
 * colour there is a per-class symbol, so a Mage terminal reads differently from a Tank one at a glance.
 */
object ClassColors : Module(
    "Class Colors",
    Category.Dungeons,
    "Colours (and a small symbol) for each dungeon class, used by F7 Titles and other dungeon alerts."
) {
    private val archer = ColorSetting("Archer", Color(255, 170, 0), "Archer's colour.")
    private val berserk = ColorSetting("Berserk", Color(255, 85, 85), "Berserk's colour.")
    private val healer = ColorSetting("Healer", Color(255, 85, 255), "Healer's colour.")
    private val mage = ColorSetting("Mage", Color(85, 255, 255), "Mage's colour.")
    private val tank = ColorSetting("Tank", Color(85, 255, 85), "Tank's colour.")
    private val symbols = BooleanSetting("Class Symbols", true, "Put a small symbol in front of a name (bow, sword, cross, wand, shield).")

    init { addSettings(archer, berserk, healer, mage, tank, symbols) }

    fun color(cls: DungeonClass?): Int = if (!isEnabled) DungeonTeam.classColor(cls) else when (cls) {
        DungeonClass.ARCHER -> archer.value.rgba
        DungeonClass.BERSERK -> berserk.value.rgba
        DungeonClass.HEALER -> healer.value.rgba
        DungeonClass.MAGE -> mage.value.rgba
        DungeonClass.TANK -> tank.value.rgba
        null -> 0xFFBBBBBB.toInt()
    }

    fun symbol(cls: DungeonClass?): String = if (!isEnabled || !symbols.value) "" else when (cls) {
        DungeonClass.ARCHER -> "➶ "
        DungeonClass.BERSERK -> "⚔ "
        DungeonClass.HEALER -> "✚ "
        DungeonClass.MAGE -> "✦ "
        DungeonClass.TANK -> "⛨ "
        null -> ""
    }

    /** The class of a teammate by name (never yourself: the tab row helper skips you). */
    fun classOf(name: String): DungeonClass? = DungeonTeam.members().firstOrNull { it.name.equals(name, ignoreCase = true) }?.cls
}

/**
 * F7 chat titles: "<name> activated a terminal! (3/7)" / "completed a device!" / "activated a lever!" as a title.
 * Unique: the title carries the player's class colour and symbol, a bigger "section done" title when the last one of
 * a section is done, and a per-type sound pitch (terminal < device < lever).
 */
object F7Titles : Module(
    "F7 Titles",
    Category.Dungeons,
    "Titles for terminals, devices and levers in the Goldor fight, in the player's class colour."
) {
    private val terminals = BooleanSetting("Terminal Titles", true, "A title for every terminal activated.")
    private val devices = BooleanSetting("Device Titles", true, "A title for every device completed.")
    private val levers = BooleanSetting("Lever Titles", true, "A title for every lever activated.")
    private val sectionDone = BooleanSetting("Section Done Title", true, "A bigger title when the last terminal / device / lever of a section is done.")
    private val onlyMine = BooleanSetting("Only My Own", false, "Only show titles for what you did yourself.")
    private val sound = BooleanSetting("Sound", true, "A pling, higher for levers.")
    private val doneColor = ColorSetting("Section Done Color", Color(85, 255, 85), "Colour of the section done title.").withDependency { sectionDone.value }

    private val ACTION = Regex("""^(\w{1,16}) (activated a terminal|completed a device|activated a lever)! \((\d+)/(\d+)\)$""")

    init {
        addSettings(terminals, devices, levers, sectionDone, onlyMine, sound, doneColor)
        on<ChatMessageEvent> {
            if (!isEnabled || !DungeonState.inDungeon) return@on
            val m = ACTION.find(it.unformattedText.trim()) ?: return@on
            val name = m.groupValues[1]
            val type = m.groupValues[2]
            val done = m.groupValues[3].toInt()
            val total = m.groupValues[4].toInt()
            val self = Minecraft.getInstance().player?.gameProfile?.name
            if (onlyMine.value && !name.equals(self, ignoreCase = true)) return@on
            val (enabled, word, pitch) = when (type) {
                "activated a terminal" -> Triple(terminals.value, "terminal", 1.0f)
                "completed a device" -> Triple(devices.value, "device", 1.25f)
                else -> Triple(levers.value, "lever", 1.5f)
            }
            if (!enabled) return@on
            val cls = ClassColors.classOf(name)
            if (sectionDone.value && done == total) {
                Alerts.title("F7 Titles", "Section done!", "$word $done/$total", doneColor.value.rgba and 0xFFFFFF, sound.value, pitch + 0.25f)
            } else {
                Alerts.title("F7 Titles", "${ClassColors.symbol(cls)}$name", "$word $done/$total", ClassColors.color(cls) and 0xFFFFFF, sound.value, pitch)
            }
        }
    }
}

/**
 * The three masks with a proc cooldown, from their "saved your life" lines. Unique: a ready ping with each mask's own
 * pitch, the list sorted by who is ready next, and a countdown that turns green under 5 seconds.
 */
object MaskTimers : RowHud("Mask Timers", "Cooldowns of Bonzo's Mask, Spirit Mask and the Phoenix Pet.", "mask_timers", 3, 200) {
    private class Mask(val label: String, val cooldownMs: Long, val color: Int, val pitch: Float) {
        var readyAt = 0L
        var alerted = true
    }

    private val bonzo = Mask("Bonzo", 180_000, 0xFF5555FF.toInt(), 1.0f)
    private val spirit = Mask("Spirit", 30_000, 0xFFFFFFFF.toInt(), 1.3f)
    private val phoenix = Mask("Phoenix", 60_000, 0xFFFFAA00.toInt(), 1.6f)
    private val masks = listOf(bonzo, spirit, phoenix)

    private val dungeonsOnly = BooleanSetting("Dungeons Only", false, "Only count (and show) procs inside a dungeon.")
    private val readyPing = BooleanSetting("Ready Ping", true, "A title and pling when a mask is ready again.")
    private val showReady = BooleanSetting("Show Ready Masks", false, "Keep masks that are ready in the list.")
    private val chatMessage = BooleanSetting("Proc Message", false, "A message in chat when a mask saves you, with the cooldown.")

    private val BONZO = Regex("""Your (?:.+ )?Bonzo's Mask saved your life!""")
    private val COOLDOWN_LINE = Regex("""^Cooldown: ([\d.]+)s$""")
    private val SPIRIT = Regex("""Second Wind Activated! Your Spirit Mask saved your life!""")
    private val PHOENIX = Regex("""Your Phoenix Pet saved you from certain death!""")

    private fun active() = isEnabled && (!dungeonsOnly.value || DungeonState.inDungeon)

    private fun seconds(ms: Long) = "%.1fs".format(java.util.Locale.ROOT, ms / 1000.0)

    override fun rows(): List<Triple<String, String, Int>>? {
        if (!active()) return null
        val now = System.currentTimeMillis()
        val out = masks.filter { showReady.value || it.readyAt > now }.sortedBy { it.readyAt }.map {
            val left = it.readyAt - now
            when {
                left <= 0 -> Triple(it.label, "Ready", 0xFF55FF55.toInt())
                left < 5000 -> Triple(it.label, seconds(left), 0xFF55FF55.toInt())
                else -> Triple(it.label, seconds(left), it.color)
            }
        }
        return out.ifEmpty { null }
    }

    override fun preview() = listOf(
        Triple("Bonzo", "92.4s", bonzo.color), Triple("Spirit", "3.1s", 0xFF55FF55.toInt()), Triple("Phoenix", "Ready", 0xFF55FF55.toInt())
    )

    /** The cooldown printed on the worn helmet ("Cooldown: 150s", it shrinks with upgrades), else the mask's default. */
    private fun cooldownOf(mask: Mask): Long {
        val helmet = mc.player?.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD) ?: return mask.cooldownMs
        val id = helmet.skyblockId ?: return mask.cooldownMs
        val wanted = when (mask) { bonzo -> "BONZO_MASK"; spirit -> "SPIRIT_MASK"; else -> return mask.cooldownMs }
        if (!id.contains(wanted)) return mask.cooldownMs
        val lore = helmet.get(net.minecraft.core.component.DataComponents.LORE)?.lines() ?: return mask.cooldownMs
        for (line in lore) {
            val m = COOLDOWN_LINE.find(line.string.trim()) ?: continue
            return (m.groupValues[1].toDouble() * 1000).toLong()
        }
        return mask.cooldownMs
    }

    private fun proc(mask: Mask) {
        if (!active()) return
        mask.readyAt = System.currentTimeMillis() + cooldownOf(mask)
        mask.alerted = false
        if (chatMessage.value) mc.player?.sendSystemMessage(
            net.minecraft.network.chat.Component.literal("§b${mask.label} §7saved you, ready in ${cooldownOf(mask) / 1000}s")
        )
    }

    init {
        addSettings(dungeonsOnly, readyPing, showReady, chatMessage)
        loadPosition()
        on<ChatMessageEvent> {
            val text = it.unformattedText.trim()
            when {
                BONZO.containsMatchIn(text) -> proc(bonzo)
                SPIRIT.containsMatchIn(text) -> proc(spirit)
                PHOENIX.containsMatchIn(text) -> proc(phoenix)
            }
        }
        ClientTickEvents.END_CLIENT_TICK.register {
            if (!active() || !readyPing.value) return@register
            val now = System.currentTimeMillis()
            for (m in masks) if (!m.alerted && now >= m.readyAt) {
                m.alerted = true
                Alerts.title("Mask Timers", "${m.label} ready", "", m.color and 0xFFFFFF, true, m.pitch)
            }
        }
    }
}

/**
 * The dungeon blessings you have, read from the tab list footer ("Blessing of Power XIV"), like the game shows them.
 * Unique: a dot bar next to the level (one dot per 3 levels), an optional colour that follows the level (dim at low,
 * bright at high), and blessings you do not have are hidden instead of showing 0.
 */
object BlessingDisplay : RowHud("Blessing Display", "The dungeon blessings you have, from the tab list.", "blessing_display", 3, 260) {
    private val patterns = linkedMapOf(
        "Power" to Regex("Blessing of Power (X{0,3}(?:IX|IV|V?I{0,3}))"),
        "Time" to Regex("Blessing of Time (V)"),
        "Wisdom" to Regex("Blessing of Wisdom (X{0,3}(?:IX|IV|V?I{0,3}))"),
        "Life" to Regex("Blessing of Life (X{0,3}(?:IX|IV|V?I{0,3}))"),
        "Stone" to Regex("Blessing of Stone (X{0,3}(?:IX|IV|V?I{0,3}))")
    )
    private val colors = mapOf(
        "Power" to 0xFFFF5555.toInt(), "Time" to 0xFF55FFFF.toInt(), "Wisdom" to 0xFF5555FF.toInt(),
        "Life" to 0xFFFF55FF.toInt(), "Stone" to 0xFFAAAAAA.toInt()
    )

    private val power = BooleanSetting("Power", true, "Power blessing.")
    private val time = BooleanSetting("Time", true, "Time blessing.")
    private val wisdom = BooleanSetting("Wisdom", false, "Wisdom blessing.")
    private val life = BooleanSetting("Life", false, "Life blessing.")
    private val stone = BooleanSetting("Stone", false, "Stone blessing.")
    private val dots = BooleanSetting("Level Dots", true, "A small dot bar next to the level (one dot per 3 levels).")
    private val byLevel = BooleanSetting("Dim Low Levels", true, "Low levels are drawn dimmer than high ones.")
    private val shown = mapOf("Power" to power, "Time" to time, "Wisdom" to wisdom, "Life" to life, "Stone" to stone)

    private val levels = LinkedHashMap<String, Int>()
    private var readAt = 0L

    private fun read() {
        val now = System.currentTimeMillis()
        if (now - readAt < 500) return
        readAt = now
        levels.clear()
        val footer = (Minecraft.getInstance().gui.tabList as ced.cedclient.mixin.accessor.PlayerTabOverlayAccessor).`ced$getFooter`()?.string ?: return
        for ((name, rx) in patterns) {
            val m = rx.find(footer) ?: continue
            val level = roman(m.groupValues[1])
            if (level > 0) levels[name] = level
        }
    }

    private fun dim(color: Int, level: Int): Int {
        if (!byLevel.value) return color
        val k = (0.55f + 0.45f * (level / 20f).coerceAtMost(1f))
        val r = ((color shr 16 and 255) * k).toInt(); val g = ((color shr 8 and 255) * k).toInt(); val b = ((color and 255) * k).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    override fun rows(): List<Triple<String, String, Int>>? {
        if (!isEnabled || !DungeonState.inDungeon) return null
        read()
        val out = patterns.keys.filter { shown[it]?.value == true }.mapNotNull { n ->
            val level = levels[n] ?: return@mapNotNull null
            val bar = if (dots.value) " " + "●".repeat(((level + 2) / 3).coerceAtMost(7)) else ""
            Triple("$n +$level", bar.trim(), dim(colors.getValue(n), level))
        }
        return out.ifEmpty { null }
    }

    override fun preview() = listOf(
        Triple("Power +14", "●●●●●", colors.getValue("Power")), Triple("Time +5", "●●", colors.getValue("Time"))
    )

    init {
        addSettings(power, time, wisdom, life, stone, dots, byLevel)
        loadPosition()
    }
}
