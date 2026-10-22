package ced.cedclient.features.impl.funqol

import ced.cedclient.features.impl.funqol.CritterSafari.Biome
import ced.cedclient.features.impl.funqol.CritterSafari.Critter
import ced.cedclient.features.impl.funqol.CritterSafari.Run
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.utils.ChatPrefix
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * Critter Safari alerts: the Gemzie / Wumpa / Doomspiral encounters, birds at the Birdfeeder, sparkling critters,
 * your Hunting Hotspot, biome milestones and Hunter shard trades. Each one is a title + chat line (+ sound), and
 * "Alert + Party" also posts it in party chat (one message every 1.5 s so Hypixel doesn't drop them).
 */
internal object CritterAlerts {
    val MODES = listOf("Off", "Alert", "Alert + Party")

    private enum class Stage(val word: String) { READY("ready"), STARTED("started"), DONE("done") }

    private val BIRDFEEDER = Regex("""^\S+ ([A-Za-z ]+?)s? (?:were|was) attracted to the Birdfeeder!$""")
    private val NPC_LINE = Regex("""^\[NPC] (Hunter [^:]{1,30}): (.*)$""")
    private val TRADE_PRICE = Regex("""(?:in exchange for(?:,\s*say,)?|give m-m-me)\s+(?:an?|the)\s+(.+?)\s*[.!?…]*$""")

    private val recent = HashMap<String, Long>()
    private val partyQueue = ArrayDeque<String>()
    private var nextPartyAt = 0L
    /** Hunter -> the shard they just offered, waiting for the line that names their price. */
    private val pendingTrades = HashMap<String, Critter>()

    /** Chat lines with no catch in them. True when the line was an alert line. */
    fun onLine(line: String): Boolean {
        val s = CritterSafari
        when {
            line.startsWith("A rumbling sound can be heard") -> encounter(s.gemzie, "Gemzie", Stage.READY, "chamber open, 3 to catch")
            line.startsWith("You hear the sound of massive footsteps") -> encounter(s.wumpa, "Wumpa", Stage.READY, "it wakes in about 30 s")
            line.startsWith("The Wumpa has awoken") -> encounter(s.wumpa, "Wumpa", Stage.STARTED, "the fight is on")
            line.startsWith("The cave opens up again") -> encounter(s.wumpa, "Wumpa", Stage.DONE, "the cave reopened")
            line.startsWith("You used the Soothing Incense to light the candle") -> encounter(s.doomspiral, "Doomspiral", Stage.READY, "ritual started")
            line.startsWith("Your ritual summoned a Doomspiral") || line.startsWith("Something stirs in the Haunted Biome") ->
                encounter(s.doomspiral, "Doomspiral", Stage.STARTED, "it's up in the Haunted Biome")
            line.startsWith("The Doomspiral retreats back underground") || line.startsWith("The darkness in the Haunted Biome fades away") ->
                encounter(s.doomspiral, "Doomspiral", Stage.DONE, "it's gone")
            else -> {
                BIRDFEEDER.matchEntire(line)?.let { m -> bird(m.groupValues[1].trim()); return true }
                NPC_LINE.matchEntire(line)?.let { m -> hunter(m.groupValues[1].trim(), m.groupValues[2]); return true }
                return false
            }
        }
        return true
    }

    fun hotspot(biome: Biome) = fire(CritterSafari.hotspotAlert, "hotspot", "HOTSPOT", biome.display + " Biome", biome.color, 1.2f, "My hotspot: ${biome.display}")

    fun sparkling(critter: Critter, where: String) =
        fire(CritterSafari.sparklingAlert, null, "SPARKLING!", "${critter.name} - $where", 0xFFFFFF55.toInt(), 2.0f, "Sparkling ${critter.name}! ($where)")

    /** After every catch: biome done / everything but the Macaw / everything, once per run. */
    fun checkMilestones(run: Run) {
        for (b in Biome.entries) if (run.biomeComplete(b) && run.announced.add(b.name)) {
            fire(CritterSafari.biomeDone, null, "${b.display} Done!", "", b.color, 1.4f, "${b.display} Done!")
        }
        val missing = Biome.entries.flatMap(run::missing)
        if (missing.size == 1 && missing[0].name == "Macaw" && run.announced.add("macaw")) {
            fire(CritterSafari.allButMacaw, null, "All but Macaw!", "", 0xFF55FF55.toInt(), 1.6f, "Everything except Macaw done!")
        }
        if (missing.isEmpty() && run.announced.add("all")) {
            fire(CritterSafari.allDone, null, "Everything Done!", "", 0xFF55FF55.toInt(), 2.0f, "Everything done!")
        }
    }

    private fun encounter(setting: DropdownSetting, boss: String, stage: Stage, detail: String) {
        // The Gemzie chamber opens once per Gemzie, so it isn't held back like the others.
        val key = if (boss == "Gemzie") null else "$boss:$stage"
        fire(setting, key, "${boss.uppercase()} ${stage.name}", detail, 0xFFFFAA00.toInt(), if (stage == Stage.STARTED) 1.8f else 1.4f, "$boss ${stage.word}")
    }

    private fun bird(name: String) {
        val critter = CritterSafari.critterNamed(name) ?: return
        val setting = if (critter.name == "Macaw") CritterSafari.macawAlert else CritterSafari.birdAlert
        fire(setting, null, "${critter.name.uppercase()}!", "at the Birdfeeder", critter.rarity.color, 1.6f, "${critter.name} at the Birdfeeder!")
    }

    private fun hunter(npc: String, text: String) {
        TRADE_PRICE.find(text)?.let { m ->
            val critter = pendingTrades.remove(npc) ?: return
            val item = m.groupValues[1].trim().ifEmpty { return }
            val trade = CritterWorld.addTrade(npc, critter, item)
            val where = trade.where()
            fire(CritterSafari.tradeAlert, "trade:$npc:${critter.name}", "TRADE", "${critter.name} Shard for $item", 0xFF55FFFF.toInt(), 1.2f,
                "$npc ($where): ${critter.name} Shard for $item")
            return
        }
        // "Shard" alone shows up in flavour text too: it also has to name a species.
        if (text.contains("Shard")) CritterSafari.critterIn(text)?.let { pendingTrades[npc] = it }
    }

    private fun fire(setting: DropdownSetting, key: String?, title: String, subtitle: String, color: Int, pitch: Float, party: String) {
        if (setting.value == "Off") return
        val now = System.currentTimeMillis()
        if (key != null) {
            if (now - (recent[key] ?: 0L) < 20_000) return
            recent[key] = now
        }
        val mc = Minecraft.getInstance()
        mc.gui.setTimes(5, 50, 15)
        mc.gui.setTitle(Component.literal(title).withColor(color and 0xFFFFFF))
        mc.gui.setSubtitle(Component.literal(subtitle))
        mc.player?.sendSystemMessage(ChatPrefix.msgFor("Critter Safari", "§e$title" + if (subtitle.isEmpty()) "" else " §7- §f$subtitle"))
        if (CritterSafari.alertSound.value) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, 1f))
        if (setting.value == "Alert + Party") partyQueue.addLast(party)
    }

    fun tick() {
        val player = Minecraft.getInstance().player ?: return partyQueue.clear()
        if (partyQueue.isEmpty() || System.currentTimeMillis() < nextPartyAt) return
        player.connection.sendCommand("pc " + partyQueue.removeFirst())
        nextPartyAt = System.currentTimeMillis() + 1500
    }

    fun reset() {
        pendingTrades.clear()
        recent.clear()
    }
}
