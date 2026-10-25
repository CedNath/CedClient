package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.utils.Countdown
import ced.cedclient.utils.Debug
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.network.chat.Component

/**
 * Timers in item tooltips ("Starts in: 3d 1h 53m 1s", "Ends in: 2h 5m", "Time left: ...") get a line under them with the
 * real date and time they land on, in your own clock: "= Sat 25 Oct 14:35". Works on any tooltip, e.g. the SkyBlock
 * calendar, events, auctions and the Bazaar. The time is counted from when the tooltip is drawn, so it can be a few
 * seconds off if the menu hasn't refreshed.
 */
object TooltipDates : Module(
    "Tooltip Dates",
    Category.Render,
    "Adds the real date and time a timer in a tooltip ends or starts (\"Starts in: 3d 1h\")."
) {
    private val format = DropdownSetting("Format", listOf("Full Date", "Smart"), "Full Date", "Full Date: always weekday, day, month and time. Smart: just the time today, the weekday this week, the full date after that.")

    // "Starts in: 3d 1h 53m 1s", "Ends in: 5m", "Time left: 2h", "Ready in: 30s", "Expires in: 1d"
    private val TIMER = Regex("""(?i)\b(starts?|begins?|ends?|ending|time left|remaining|left|ready|available|expires?|finish(?:es)?|done|resets?|until)\b[^:]*:\s*((?:\d+\s*[dhms]\s*)+)\s*$""")
    private val PART = Regex("""(\d+)\s*([dhms])""")
    private var lastLog = 0L // the tooltip callback fires every frame while hovering

    init {
        addSettings(format)
        ItemTooltipCallback.EVENT.register { _, _, _, lines ->
            if (!isEnabled) return@register
            var i = 0
            while (i < lines.size) {
                val text = lines[i].string.replace(Regex("§."), "").trim()
                val m = TIMER.find(text)
                if (m != null) {
                    val ms = PART.findAll(m.groupValues[2]).sumOf { p ->
                        p.groupValues[1].toLong() * when (p.groupValues[2]) { "d" -> 86_400_000L; "h" -> 3_600_000L; "m" -> 60_000L; else -> 1_000L }
                    }
                    if (ms > 0) {
                        val at = System.currentTimeMillis() + ms
                        val shown = if (format.value == "Full Date") Countdown.full(at) else Countdown.clock(at)
                        lines.add(i + 1, Component.literal("§7= §f$shown"))
                        val now = System.currentTimeMillis()
                        if (now - lastLog > 5000) { lastLog = now; Debug.line("TooltipDates", "\"$text\" -> $shown") }
                        i++
                    }
                }
                i++
            }
        }
    }
}
