package ced.cedclient.state

import ced.cedclient.state.DungeonState.DungeonClass
import ced.cedclient.state.HotbarDungeonMap.RoomState
import ced.cedclient.utils.ChatPrefix
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/**
 * Who cleared which room, for the summary printed in chat when the run ends (like NoammAddons' clear info). A room
 * counts for whoever stood in it the moment the dungeon map turned its checkmark white / green: alone = solo, with
 * others = stacked (each of them gets it). Deaths come from the "... and became a ghost" lines. Call [update] from a
 * tick; a new world starts over.
 */
object DungeonClears {
    private const val START = -185
    private val CODES = Regex("§.")
    private val DEATH = Regex("""^☠ (\w+) (.*?) ?and became a ghost\.$""")
    private val SKIPPED = setOf("FAIRY", "ENTRANCE")

    private class Stat(val name: String) {
        var cls: DungeonClass? = null
        val solo = LinkedHashSet<String>()
        val stacked = LinkedHashSet<String>()
        val deaths = ArrayList<String>()
    }

    private val stats = LinkedHashMap<String, Stat>()
    private val lastStates = HashMap<Pair<Int, Int>, RoomState>()
    private val credited = HashSet<String>()
    private var level: ClientLevel? = null
    private var mapVersion = -1
    private var printed = false
    private var teamTick = 0

    private fun stat(name: String) = stats.getOrPut(name.lowercase()) { Stat(name) }

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (overlay || !DungeonState.inDungeon) return@register
            val m = DEATH.find(message.string.replace(CODES, "").trim()) ?: return@register
            val self = Minecraft.getInstance().player?.gameProfile?.name ?: return@register
            stat(if (m.groupValues[1] == "You") self else m.groupValues[1]).deaths += m.groupValues[2].trim().ifEmpty { "died" }
        }
    }

    private fun reset() {
        stats.clear(); lastStates.clear(); credited.clear()
        mapVersion = -1; printed = false; teamTick = 0
    }

    fun update(level: ClientLevel, self: LocalPlayer) {
        if (level !== this.level) { this.level = level; reset() }
        // Teammates and classes once a second (a dead player's tab row shows "DEAD", so remember what was seen).
        if (teamTick++ % 20 == 0) {
            for (m in DungeonTeam.members()) stat(m.name).let { s -> if (m.cls != null) s.cls = m.cls }
            stat(self.gameProfile.name).let { s -> DungeonState.playerClass?.let { s.cls = it } }
        }
        if (HotbarDungeonMap.version != mapVersion) {
            mapVersion = HotbarDungeonMap.version
            checkClears(self)
        }
        if (DungeonState.runEnded && !printed) {
            printed = true
            print()
        }
    }

    private fun checkClears(self: LocalPlayer) {
        var positions: List<Pair<String, Pair<Double, Double>>>? = null
        for ((tile, state) in HotbarDungeonMap.roomStates.toList()) {
            val old = lastStates.put(tile, state)
            if (state != RoomState.CLEARED && state != RoomState.GREEN) continue
            if (old != RoomState.UNEXPLORED && old != RoomState.OPENED) continue
            val room = DungeonScanner.room(tile.first, tile.second) ?: continue
            if (room.type in SKIPPED || !credited.add(room.name)) continue
            val here = (positions ?: positions(self).also { positions = it }).filter { (_, p) ->
                DungeonScanner.room(Math.floorDiv(p.first.toInt() - START + 16, 32), Math.floorDiv(p.second.toInt() - START + 16, 32))?.name == room.name
            }
            if (here.isEmpty()) { credited.remove(room.name); continue }
            for ((name, _) in here) stat(name).let { if (here.size == 1) it.solo += room.name else it.stacked += room.name }
        }
    }

    /** Where everyone is: you, then each living teammate from their entity in render distance, else their map icon. */
    fun positions(self: LocalPlayer): List<Pair<String, Pair<Double, Double>>> {
        val out = ArrayList<Pair<String, Pair<Double, Double>>>()
        out += self.gameProfile.name to (self.x to self.z)
        val icons = HotbarDungeonMap.markers.filter { !it.self }
        val level = Minecraft.getInstance().level ?: return out
        DungeonTeam.members().filter { !it.dead }.forEachIndexed { i, m ->
            val entity = level.players().firstOrNull { it !== self && it.gameProfile.name.equals(m.name, ignoreCase = true) }
            if (entity != null) out += m.name to (entity.x to entity.z)
            else icons.getOrNull(i)?.let { out += m.name to (it.x to it.z) }
        }
        return out
    }

    private fun code(cls: DungeonClass?) = when (cls) {
        DungeonClass.ARCHER -> "§6"
        DungeonClass.BERSERK -> "§c"
        DungeonClass.HEALER -> "§d"
        DungeonClass.MAGE -> "§b"
        DungeonClass.TANK -> "§a"
        null -> "§7"
    }

    private fun print() {
        val chat = Minecraft.getInstance().gui?.hud?.chat ?: return
        val shown = stats.values.filter { it.solo.isNotEmpty() || it.stacked.isNotEmpty() || it.deaths.isNotEmpty() }
        if (shown.isEmpty()) return
        chat.addClientSystemMessage(ChatPrefix.msgFor("Dungeon Clears", "§6Clear breakdown"))
        for (s in shown) {
            val rooms = s.solo.size + s.stacked.size
            val range = if (s.stacked.isEmpty()) "${s.solo.size}" else "${s.solo.size}-$rooms"
            val tip = (s.solo.map { "§f$it §b(Solo)" } + s.stacked.map { "§f$it §d(Stack)" }).joinToString("\n")
            val line = Component.empty().append(Component.literal(" ${code(s.cls)}${s.name}§f: "))
            line.append(Component.literal("§e$range ${if (rooms == 1 && s.stacked.isEmpty()) "Room" else "Rooms"}").withStyle { st ->
                if (tip.isEmpty()) st else st.withHoverEvent(HoverEvent.ShowText(Component.literal(tip)))
            })
            if (s.deaths.isNotEmpty()) {
                line.append(Component.literal(" §f| "))
                line.append(Component.literal("§c${s.deaths.size} ${if (s.deaths.size == 1) "Death" else "Deaths"}").withStyle { st ->
                    st.withHoverEvent(HoverEvent.ShowText(Component.literal(s.deaths.joinToString("\n") { "§7$it" })))
                })
            }
            chat.addClientSystemMessage(line)
        }
    }
}
