package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.Debug
import com.google.common.collect.MapMaker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.chat.GuiMessage

// ------------------------------------------------------------------------------------------------ Chat Tabs

/**
 * Tabs above the chat input that show one channel at a time (All, Party, Guild, Private) and send to it: with a tab
 * selected, a line that isn't a command is sent with /pc, /gc or /r in front. Filtering uses the chat's own
 * visible-message filter and a rebuild (ChatComponent.rescaleChat), so no message is changed or lost.
 * Hooked from ChatTabsMixin (ChatScreen): draw, click and send.
 */
object ChatTabs : Module(
    "Chat Tabs", Category.Chat,
    "Tabs above the chat input that show one channel at a time and send to it."
) {
    private enum class Tab(val label: String, val sendPrefix: String?) {
        ALL("All", null), PARTY("Party", "/pc "), GUILD("Guild", "/gc "), PRIVATE("Private", "/r ")
    }

    private var selected = Tab.ALL

    // A leading [12:30] style timestamp may have been added by Chat Features; skip it.
    private val channelRe = Regex("^(?:\\[[\\d:APMapm .]+]\\s*)?(Party >|Guild >|Officer >|From |To )")

    private fun classify(text: String): Tab {
        val m = channelRe.find(text) ?: return Tab.ALL
        return when (m.groupValues[1]) {
            "Party >" -> Tab.PARTY
            "Guild >", "Officer >" -> Tab.GUILD
            else -> if (text.contains(": ")) Tab.PRIVATE else Tab.ALL
        }
    }

    private fun select(tab: Tab) {
        selected = tab
        val chat = Minecraft.getInstance().gui.hud.chat
        chat.setVisibleMessageFilter { msg: GuiMessage -> tab == Tab.ALL || classify(msg.content().string) == tab }
        chat.rescaleChat()
        Debug.line(name, "tab ${tab.label}")
    }

    override fun onDisable() { if (selected != Tab.ALL) select(Tab.ALL) }

    private const val TAB_H = 12
    private const val PAD = 5

    private fun tabX(font: net.minecraft.client.gui.Font): List<Pair<Tab, IntRange>> {
        var x = 2
        return Tab.entries.map { t ->
            val w = font.width(t.label) + PAD * 2
            (t to (x until x + w)).also { x += w + 2 }
        }
    }

    private fun top() = Minecraft.getInstance().window.guiScaledHeight - 28

    /** Mixin (ChatScreen.extractRenderState, tail). */
    @JvmStatic
    fun render(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!isEnabled) return
        val font = Minecraft.getInstance().font
        val y = top()
        for ((tab, xs) in tabX(font)) {
            val hover = mouseX in xs && mouseY in y until y + TAB_H
            val bg = when {
                tab == selected -> (0xCC000000.toInt() or (Theme.hudBorderRgba and 0xFFFFFF))
                hover -> 0x99303040.toInt()
                else -> 0x88101018.toInt()
            }
            g.fill(xs.first, y, xs.last + 1, y + TAB_H, bg)
            g.text(font, tab.label, xs.first + PAD, y + 2, -1)
        }
    }

    /** Mixin (ChatScreen.mouseClicked, head). True = the click was on a tab. */
    @JvmStatic
    fun onClick(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!isEnabled || button != 0) return false
        val y = top()
        if (mouseY < y || mouseY >= y + TAB_H) return false
        val hit = tabX(Minecraft.getInstance().font).firstOrNull { mouseX >= it.second.first && mouseX < it.second.last + 1 } ?: return false
        select(hit.first)
        return true
    }

    /** Mixin (ChatScreen.handleChatInput): the text about to be sent, with the selected channel's command in front. */
    @JvmStatic
    fun rewrite(text: String): String {
        val prefix = selected.sendPrefix
        if (!isEnabled || prefix == null || text.isBlank() || text.startsWith("/")) return text
        return prefix + text
    }
}

// ------------------------------------------------------------------------------------------------ Smooth Chat

/**
 * New chat lines fade in instead of popping in. A line counts as new when it is first drawn within two ticks of
 * arriving, so reopening chat, scrolling or switching a Chat Tab never fades old lines. Hooked from SmoothChatMixin
 * (the per-line alpha in ChatComponent.forEachLine).
 */
object SmoothChat : Module(
    "Smooth Chat", Category.Chat,
    "New chat lines fade in instead of appearing instantly."
) {
    private val fadeMs = NumberSetting("Fade-In Time (ms)", 250.0, 50.0, 1000.0, 25.0)

    // Weak identity keys: a Line is a record, equal copies are different lines.
    private val firstSeen: MutableMap<GuiMessage.Line, Long> = MapMaker().weakKeys().makeMap()

    init { addSettings(fadeMs) }

    /** Multiplier (0..1) for the line's alpha. */
    @JvmStatic
    fun fadeIn(line: GuiMessage.Line): Float {
        if (!isEnabled) return 1f
        val now = System.nanoTime()
        val first = firstSeen.getOrPut(line) {
            val ageTicks = Minecraft.getInstance().gui.hud.guiTicks - line.addedTime()
            if (ageTicks <= 2) now else 0L
        }
        if (first == 0L) return 1f
        val t = ((now - first) / 1_000_000.0 / fadeMs.value).toFloat().coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
