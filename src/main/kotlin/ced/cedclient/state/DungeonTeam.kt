package ced.cedclient.state

import ced.cedclient.state.DungeonState.DungeonClass
import ced.cedclient.utils.TabListCache
import net.minecraft.client.Minecraft

/** Your dungeon teammates (never yourself) from the tab list, for the Leap Menu and the Dungeon Map. */
object DungeonTeam {
    class Member(val name: String, val cls: DungeonClass?, val dead: Boolean)

    /** Dungeon tab row: "[312] Name ♲ (Mage XXV)" -> name, class ("DEAD" / "EMPTY" when not playing). */
    private val TAB_ROW = Regex("""^\[\d+] (\w{1,16})\b.*\((\w+)(?: [IVXLCDM]+)?\)$""")

    fun members(): List<Member> {
        val self = Minecraft.getInstance().player?.gameProfile?.name
        return TabListCache.entries.mapNotNull { e ->
            val m = TAB_ROW.find(e.stripped.trim()) ?: return@mapNotNull null
            val name = m.groupValues[1]
            if (name.equals(self, ignoreCase = true)) return@mapNotNull null
            val word = m.groupValues[2]
            Member(name, DungeonClass.entries.firstOrNull { it.name.equals(word, ignoreCase = true) }, word.equals("DEAD", ignoreCase = true))
        }.distinctBy { it.name.lowercase() }
    }

    fun classColor(cls: DungeonClass?): Int = when (cls) {
        DungeonClass.ARCHER -> 0xFFFFAA00.toInt()
        DungeonClass.BERSERK -> 0xFFFF5555.toInt()
        DungeonClass.HEALER -> 0xFFFF55FF.toInt()
        DungeonClass.MAGE -> 0xFF55FFFF.toInt()
        DungeonClass.TANK -> 0xFF55FF55.toInt()
        null -> 0xFFBBBBBB.toInt()
    }
}
