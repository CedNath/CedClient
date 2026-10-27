//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.state.DungeonState
import net.minecraft.client.Minecraft

/**
 * Puzzle fail helper: when a teammate (or you) fails a puzzle, the party is told, and the Architect's First Draft can be
 * pulled from your sacks with /gfs. Unique: a short delay before the draft is pulled (so it is not instant), a cooldown
 * so one fail only pulls one draft, and the message names the player who failed.
 */
object ArchitectDraft : Module(
    "Architect Draft",
    Category.Dungeons,
    "Announces puzzle fails in party chat and can pull an Architect's First Draft from your sacks."
) {
    private val announce = BooleanSetting("Announce Fail", true, "Tell the party who failed a puzzle.")
    private val autoDraft = BooleanSetting("Auto Draft", false, "Pull an Architect's First Draft with /gfs after a puzzle fail.")
    private val delay = NumberSetting("Draft Delay (ms)", 400.0, 0.0, 3000.0, 50.0, "Wait this long before pulling the draft.")
    private val announceUse = BooleanSetting("Announce Draft Use", true, "Tell the party when a draft was used to reset a puzzle.")

    private val FAIL = Regex("""^PUZZLE FAIL! (\w{1,16}) .+$""")
    private val QUIZ_FAIL = Regex("""^\[STATUE] Oruo the Omniscient: (\w{1,16}) chose the wrong answer! I shall never forget this moment of misrememberance\.$""")
    private val USED = Regex("""^You used the Architect's First Draft to reset (.+)!$""")

    private var lastDraft = 0L

    init {
        addSettings(announce, autoDraft, delay, announceUse)
        on<ChatMessageEvent> {
            if (!isEnabled || !DungeonState.inDungeon) return@on
            val mc = Minecraft.getInstance()
            val text = it.unformattedText.trim()
            val failer = FAIL.find(text)?.groupValues?.get(1) ?: QUIZ_FAIL.find(text)?.groupValues?.get(1)
            if (failer != null) {
                if (announce.value) mc.player?.connection?.sendCommand("pc $failer failed a puzzle")
                val now = System.currentTimeMillis()
                if (autoDraft.value && now - lastDraft > 8000) {
                    lastDraft = now
                    Thread {
                        Thread.sleep(delay.value.toLong())
                        mc.execute { mc.player?.connection?.sendCommand("gfs ARCHITECT_FIRST_DRAFT 1") }
                    }.also { t -> t.isDaemon = true }.start()
                }
            }
            USED.find(text)?.let { m -> if (announceUse.value) mc.player?.connection?.sendCommand("pc Architect's Draft used on ${m.groupValues[1]}") }
        }
    }
}
//#endif
