package ced.cedclient.features.impl.render

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.utils.Color
import ced.cedclient.utils.Colors
import com.google.gson.Gson
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import java.io.File

private data class ChatChannelHudPosition(val x: Int, val y: Int, val scale: Float = 1.0f)


object ChatChannelHud : Module(
    "Chat Channel HUD",
    Category.Render,
    "Shows which Hypixel chat channel you're currently in (All/Party/Guild/Officer/Co-op/DM)."
), HudElement {

    private enum class Channel(val label: String, val color: Color) {
        ALL("All", Colors.MINECRAFT_YELLOW),
        PARTY("Party", Colors.MINECRAFT_BLUE),
        GUILD("Guild", Colors.MINECRAFT_DARK_GREEN),
        OFFICER("Officer", Colors.MINECRAFT_DARK_AQUA),
        COOP("Co-op", Colors.MINECRAFT_AQUA),
        PRIVATE("DM", Colors.MINECRAFT_LIGHT_PURPLE)
    }

    // Hypixel's raw channel name (from "You are now in the X channel") ->
    // our enum. "SKYBLOCK CO-OP" is the exact wording that message uses for
    // co-op chat.
    private val channelNames = mapOf(
        "ALL" to Channel.ALL,
        "PARTY" to Channel.PARTY,
        "GUILD" to Channel.GUILD,
        "OFFICER" to Channel.OFFICER,
        "SKYBLOCK CO-OP" to Channel.COOP
    )

    private val changedChannelRegex = Regex("""You are now in the (?<chat>.+) channel""")
    private val movedToAllRegex = Regex(
        """You are not in a party and were moved to the ALL channel\.""" +
                """|The conversation you were in expired and you have been moved back to the ALL channel\."""
    )
    private val openPrivateMessageRegex = Regex(
        """Opened a chat conversation with (?:\[\S+]\s*)?(?<player>\S+) for the next 5 minutes\. Use /chat a to leave"""
    )

    private var currentChannel: Channel = Channel.ALL
    private var privateMessagePlayer: String? = null

    init {
        on<ChatMessageEvent> { event ->
            val text = event.unformattedText

            changedChannelRegex.find(text)?.let { match ->
                val name = match.groups["chat"]!!.value.trim().uppercase()
                channelNames[name]?.let { currentChannel = it }
                return@on
            }

            if (movedToAllRegex.containsMatchIn(text)) {
                currentChannel = Channel.ALL
                privateMessagePlayer = null
                return@on
            }

            openPrivateMessageRegex.find(text)?.let { match ->
                currentChannel = Channel.PRIVATE
                privateMessagePlayer = match.groups["player"]!!.value
            }
        }
    }

    // --- HudElement: position persistence, mirrors TimeHud's pattern exactly ---

    override val label: String = "Chat Channel HUD"

    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 3.0f

    private val fontId: Identifier = Identifier.fromNamespaceAndPath("cedclient", "font")
    private val fontStyle: Style = Style.EMPTY.withFont(FontDescription.Resource(fontId))

    override var panelX: Int = 6
    override var panelY: Int = 46 // just below TimeHud's default position
    override var panelScale: Float = 1.0f

    override var lastWidth: Int = 60
        private set
    override var lastHeight: Int = 16
        private set

    private val showBackground = BooleanSetting("Show Background", true)

    private val gson = Gson()
    private val saveFile: File by lazy {
        File(Minecraft.getInstance().gameDirectory, "cedclient/chat_channel_hud.json")
    }

    private var loadedOnce = false

    fun ensureLoaded() {
        if (!loadedOnce) {
            load()
            loadedOnce = true
        }
    }

    private fun defaultPosition(): ChatChannelHudPosition {
        val mc = Minecraft.getInstance()
        val screenHeight = mc.window.guiScaledHeight
        // Vanilla ChatScreen input box: x=4, y=height-12, height=12.
        val chatBoxTop = screenHeight - 12
        val gap = 4
        // Estimated panel height before the first real render (font.lineHeight
        // + paddingY*2, matching renderInternal()'s own math) so the very first
        // frame is already positioned correctly instead of jumping once measured.
        val estimatedHeight = mc.font.lineHeight + 12
        return ChatChannelHudPosition(x = 4, y = chatBoxTop - gap - estimatedHeight)
    }

    fun load() {
        try {
            if (saveFile.exists()) {
                val pos = gson.fromJson(saveFile.readText(), ChatChannelHudPosition::class.java)
                if (pos != null) {
                    panelX = pos.x
                    panelY = pos.y
                    panelScale = pos.scale.coerceIn(MIN_SCALE, MAX_SCALE)
                    return
                }
            }
            val default = defaultPosition()
            panelX = default.x
            panelY = default.y
        } catch (e: Exception) {
            println("[ChatChannelHud] Failed to load position: ${e.message}")
        }
    }

    override fun save() {
        try {
            saveFile.parentFile?.mkdirs()
            saveFile.writeText(gson.toJson(ChatChannelHudPosition(panelX, panelY, panelScale)))
        } catch (e: Exception) {
            println("[ChatChannelHud] Failed to save position: ${e.message}")
        }
    }

    override fun adjustScale(delta: Float) {
        panelScale = (panelScale + delta).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    private fun currentText(): String {
        val channel = currentChannel
        return if (channel == Channel.PRIVATE) "Chat: DM (${privateMessagePlayer ?: "?"})" else "Chat: ${channel.label}"
    }

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (!isEnabled) return
        if (Minecraft.getInstance().screen !is net.minecraft.client.gui.screens.ChatScreen) return
        renderInternal(g)
    }

    override fun renderInternal(g: GuiGraphicsExtractor) {
        ensureLoaded()

        val font = Minecraft.getInstance().font
        val styledText: Component = Component.literal(currentText()).withStyle(fontStyle)
        val textWidth = font.width(styledText)

        val paddingX = 8
        val paddingY = 6
        val width = textWidth + paddingX * 2
        val height = font.lineHeight + paddingY * 2

        lastWidth = width
        lastHeight = height

        g.pose().pushMatrix()
        g.pose().translate(panelX.toFloat(), panelY.toFloat())
        g.pose().scale(panelScale, panelScale)

        if (showBackground.value) {
            g.fill(0, 0, width, height, 0xCC10101A.toInt())
            g.outline(0, 0, width, height, 0xFF5A4FCF.toInt())
        }

        val textX = (width - textWidth) / 2
        val textY = (height - font.lineHeight) / 2
        g.text(font, styledText, textX, textY, currentChannel.color.rgba)

        g.pose().popMatrix()
    }

    init {
        load()
        addSettings(showBackground)
    }
}