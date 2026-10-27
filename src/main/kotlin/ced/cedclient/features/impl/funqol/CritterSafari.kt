package ced.cedclient.features.impl.funqol

import ced.cedclient.config.CedPaths
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.TickEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.misc.Theme
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.CritterHistoryPopup
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.utils.Color
import ced.cedclient.state.Sidebar
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import java.io.File

/**
 * Critter Safari tracker: species caught this run by you and your party,
 * per biome, which ones are still missing in the biome you're in, and a saved history of runs (an
 * older runs file in config/ is imported once). Catches come from Hypixel's chat lines ("CAPTURE!", "LOOT SHARE!").
 * The panels are HUD elements: Critter Progress, Critter Missing and Critter Trades.
 * Alerts / party posts: [CritterAlerts]. World reading and boxes: [CritterWorld]. Shard prices: [CritterPrices].
 */
object CritterSafari : Module(
    "Critter Safari",
    Category.Skyblock,
    "Critter Safari run tracker: species caught by you and your party, per biome, what's still missing.",
    defaultEnabled = true
) {
    private val showWhere = DropdownSetting("Show HUD", listOf("In Safari", "Safari + Entrance", "Everywhere"), "Safari + Entrance")
    private val showMissing = BooleanSetting("Missing Species HUD", true, "Lists what's left in the biome you're in.")
    private val showPerPlayer = BooleanSetting("Per Player", true, "Unique catches per party member.")
    private val showHotspot = BooleanSetting("Show Hotspot", true)
    private val uniqueOnly = BooleanSetting("Unique Only", false, "Count one catch of every species as done, ignoring spawn quotas.")
    private val nearby = BooleanSetting("Nearby Counts", true, "Missing panel: how many of each missing species are in sight right now.")
    private val showTrades = BooleanSetting("Hunter Trades HUD", true, "Lists the Hunter shard trades found this run.")
    private val hideLootShare = BooleanSetting("Hide Loot Share Messages", false, "Hides the LOOT SHARE! chat lines in the Safari. They are still counted.")

    // ---- profit
    private val showValue = BooleanSetting("Shard Value", true, "What this run's shards are worth on the bazaar, on the progress panel.")
    private val priceSide = DropdownSetting("Price", listOf("Instant Sell", "Sell Offer"), "Instant Sell").withDependency { showValue.value }
    private val showBoth = BooleanSetting("Show Both Prices", false).withDependency { showValue.value }
    private val subtractTax = BooleanSetting("Subtract Bazaar Tax", true, "Takes the 1.25% tax off.").withDependency { showValue.value }

    // ---- alerts (popup)
    internal val alertSound = BooleanSetting("Alert Sound", true)
    internal val gemzie = DropdownSetting("Gemzie", CritterAlerts.MODES, "Alert")
    internal val wumpa = DropdownSetting("Wumpa", CritterAlerts.MODES, "Alert")
    internal val doomspiral = DropdownSetting("Doomspiral", CritterAlerts.MODES, "Alert")
    internal val macawAlert = DropdownSetting("Macaw", CritterAlerts.MODES, "Alert")
    internal val birdAlert = DropdownSetting("Other Birdfeeder Birds", CritterAlerts.MODES, "Off")
    internal val sparklingAlert = DropdownSetting("Sparkling Critters", CritterAlerts.MODES, "Alert")
    internal val hotspotAlert = DropdownSetting("Hotspot", CritterAlerts.MODES, "Alert")
    internal val tradeAlert = DropdownSetting("Hunter Trades", CritterAlerts.MODES, "Alert")
    internal val biomeDone = DropdownSetting("Biome Done", CritterAlerts.MODES, "Off")
    internal val allButMacaw = DropdownSetting("All But Macaw", CritterAlerts.MODES, "Off")
    internal val allDone = DropdownSetting("Everything Done", CritterAlerts.MODES, "Off")

    // ---- highlights (popup)
    internal val hardToFind = BooleanSetting("Hard-To-Find Critters", true, "Boxes Bloodbats.")
    internal val hardToFindColor = ColorSetting("Hard-To-Find Color", Color(255, 85, 85))
    internal val recatch = BooleanSetting("Recatch Spot", true, "Boxes where a critter was when you threw at it: it comes back there if it escapes.")
    internal val recatchColor = ColorSetting("Recatch Color", Color(255, 255, 85))
    internal val floorDrops = BooleanSetting("Floor Drops", true)
    internal val floorDropColor = ColorSetting("Floor Drop Color", Color(85, 255, 170))
    internal val highlightMounds = BooleanSetting("Rockmite Mounds", false, "Boxes the mounds near you in the Cavern.")
    internal val moundColor = ColorSetting("Mound Color", Color(204, 119, 68))
    internal val highlightTrades = BooleanSetting("Hunter Trade Spots", true)
    internal val tradeColor = ColorSetting("Trade Color", Color(85, 255, 255))
    internal val snooperWalls = BooleanSetting("Snooper Walls", true, "Boxes the breakable Cavern walls until you've seen them broken.")
    internal val troodonWalls = BooleanSetting("Troodon Walls", true, "Boxes the breakable Icy walls until you've seen them broken.")
    internal val wallColor = ColorSetting("Wall Color", Color(255, 170, 0))
    internal val beeSpots = BooleanSetting("Bee Spots", true, "The 11 Forest spots a bee block sits: unchecked ones until you look, then only bee nests.")
    internal val beeColor = ColorSetting("Bee Spot Color", Color(85, 255, 85))
    internal val beeNestColor = ColorSetting("Bee Nest Color", Color(255, 204, 34))
    internal val perches = BooleanSetting("Hideonwall Perches", true, "The 8 Haunted spots a Hideonwall sits: unchecked ones, then ones with a Hideonwall.")
    internal val perchColor = ColorSetting("Perch Color", Color(170, 85, 255))
    internal val perchFoundColor = ColorSetting("Perch Found Color", Color(85, 255, 85))
    internal val removeDarkness = BooleanSetting("Remove Darkness", false, "Drops the Darkness effect in the Safari (client only).")

    private val alertSettings: List<RenderableSetting<*>> = listOf(alertSound, gemzie, wumpa, doomspiral, macawAlert, birdAlert, sparklingAlert, hotspotAlert, tradeAlert, biomeDone, allButMacaw, allDone)
    private val highlightSettings: List<RenderableSetting<*>> = listOf(hardToFind, hardToFindColor, recatch, recatchColor, floorDrops, floorDropColor, highlightMounds, moundColor,
        highlightTrades, tradeColor, removeDarkness, snooperWalls, troodonWalls, wallColor, beeSpots, beeColor, beeNestColor, perches, perchColor, perchFoundColor)

    private val hudSettings: List<RenderableSetting<*>> = listOf(showPerPlayer, showHotspot, uniqueOnly, nearby, showValue, priceSide, showBoth, subtractTax)
    private val hudOptions = ActionSetting("HUD Options", "What the panels show: per player, hotspot, nearby counts, shard value.") {
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(SettingsPopup("Critter HUD", listOf(
            listOf(SettingsPopup.Section("Panels", listOf(showPerPlayer, showHotspot, uniqueOnly, nearby))),
            listOf(SettingsPopup.Section("Shard Value", listOf(showValue, priceSide, showBoth, subtractTax))),
        )))
    }
    private val alerts = ActionSetting("Alerts", "Encounters, birds, sparkling critters, milestones; each can also go to party chat.") {
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(SettingsPopup("Critter Alerts", listOf(
            listOf(SettingsPopup.Section("Encounters", listOf(alertSound, gemzie, wumpa, doomspiral, macawAlert, birdAlert))),
            listOf(SettingsPopup.Section("Run", listOf(sparklingAlert, hotspotAlert, tradeAlert, biomeDone, allButMacaw, allDone))),
        )))
    }
    private val highlights = ActionSetting("Highlights", "Boxes in the world: Bloodbats, recatch spots, floor drops, mounds, trades.") {
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(SettingsPopup("Critter Highlights", listOf(
            listOf(SettingsPopup.Section("Critters", listOf(hardToFind, hardToFindColor, recatch, recatchColor, removeDarkness))),
            listOf(SettingsPopup.Section("World", listOf(floorDrops, floorDropColor, highlightMounds, moundColor, highlightTrades, tradeColor))),
            listOf(SettingsPopup.Section("Spots", listOf(snooperWalls, troodonWalls, wallColor, beeSpots, beeColor, beeNestColor, perches, perchColor, perchFoundColor))),
        ), height = 650f))
    }
    private val history = ActionSetting("Run History", "Your saved Safari runs.") {
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(CritterHistoryPopup())
    }

    init {
        addSettings(showWhere, showMissing, showTrades, hideLootShare, hudOptions, alerts, highlights, history)
        addSettings(*hudSettings.toTypedArray(), *alertSettings.toTypedArray(), *highlightSettings.toTypedArray())
        (hudSettings + alertSettings + highlightSettings).forEach { it.inPopup = true }
        on<ChatMessageEvent> { onChat(it.formattedText) }
        on<TickEvent.End> { tick(); CritterWorld.tick(); CritterAlerts.tick() }
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            CritterWorld.render(context)
        })
    }

    internal fun inSafari() = where != Where.ELSEWHERE
    internal fun currentBiome() = biome
    internal fun critterNamed(name: String) = BY_NAME[name]
    internal fun critterCount() = CRITTERS.size

    /** What [shards] (species name -> amount) are worth with the Price / tax settings. */
    internal fun runValue(shards: Map<String, Int>) = CritterPrices.value(shards, priceSide.value == "Sell Offer", subtractTax.value)
    internal fun formatCoins(coins: Long) = if (CritterPrices.loaded) CritterPrices.format(coins) else "-"

    /** ChatFilter: hide LOOT SHARE! lines in the Safari (tracking has already counted them). */
    @JvmStatic
    fun hideChat(text: String) = isEnabled && hideLootShare.value && inSafari() && strip(text).startsWith("LOOT SHARE!")

    // ---------------------------------------------------------------- species

    enum class Biome(val display: String, val rgb: Int) {
        FOREST("Forest", 0x55FF55), CAVERN("Cavern", 0xFFAA00), ICY("Icy", 0x55FFFF), HAUNTED("Haunted", 0xAA00AA);
        val color get() = 0xFF000000.toInt() or rgb
    }

    enum class Rarity(rgb: Int) {
        COMMON(0xFFFFFF), UNCOMMON(0x55FF55), RARE(0x5555FF), EPIC(0xAA00AA), LEGENDARY(0xFFAA00);
        val color = 0xFF000000.toInt() or rgb
    }

    /** [quota]: how many of it spawn per run (catch them all for the biome to count as done). */
    class Critter(val name: String, val biome: Biome, val rarity: Rarity, val quota: Int = 0)

    private val CRITTERS = listOf(
        Critter("Foxtrot", Biome.FOREST, Rarity.COMMON), Critter("Bluebird", Biome.FOREST, Rarity.UNCOMMON),
        Critter("Honeybug", Biome.FOREST, Rarity.UNCOMMON), Critter("Treefrog", Biome.FOREST, Rarity.UNCOMMON),
        Critter("Woodchucker", Biome.FOREST, Rarity.UNCOMMON), Critter("Fluffling", Biome.FOREST, Rarity.RARE),
        Critter("Hideonfloor", Biome.FOREST, Rarity.RARE), Critter("Parakeet", Biome.FOREST, Rarity.RARE),
        Critter("Macaw", Biome.FOREST, Rarity.LEGENDARY),
        Critter("Cavernfish", Biome.CAVERN, Rarity.COMMON), Critter("Flitter", Biome.CAVERN, Rarity.COMMON),
        Critter("Shyworm", Biome.CAVERN, Rarity.COMMON), Critter("Driftling", Biome.CAVERN, Rarity.UNCOMMON),
        Critter("Chuckwalla", Biome.CAVERN, Rarity.RARE), Critter("Rockmite", Biome.CAVERN, Rarity.RARE),
        Critter("Scrappy", Biome.CAVERN, Rarity.RARE), Critter("Snoozle", Biome.CAVERN, Rarity.RARE),
        Critter("Gemzie", Biome.CAVERN, Rarity.EPIC, 3),
        Critter("Strongarm", Biome.ICY, Rarity.COMMON), Critter("Tepid", Biome.ICY, Rarity.COMMON),
        Critter("Polaris", Biome.ICY, Rarity.UNCOMMON), Critter("Shuddersquid", Biome.ICY, Rarity.UNCOMMON),
        Critter("Billygoat", Biome.ICY, Rarity.RARE), Critter("Mantis Shrimp", Biome.ICY, Rarity.RARE),
        Critter("Nozzlenose", Biome.ICY, Rarity.RARE), Critter("Troodon", Biome.ICY, Rarity.RARE, 3),
        Critter("Wumpa", Biome.ICY, Rarity.LEGENDARY, 1),
        Critter("Areita", Biome.HAUNTED, Rarity.UNCOMMON), Critter("Bloodbat", Biome.HAUNTED, Rarity.UNCOMMON),
        Critter("Duplico", Biome.HAUNTED, Rarity.UNCOMMON), Critter("Gazer", Biome.HAUNTED, Rarity.UNCOMMON, 4),
        Critter("Litterbug", Biome.HAUNTED, Rarity.UNCOMMON), Critter("Solsnatcher", Biome.HAUNTED, Rarity.UNCOMMON),
        Critter("Gimmiegold", Biome.HAUNTED, Rarity.RARE), Critter("Hideonwall", Biome.HAUNTED, Rarity.RARE),
        Critter("Hideyho", Biome.HAUNTED, Rarity.RARE, 1), Critter("Doomspiral", Biome.HAUNTED, Rarity.LEGENDARY, 1),
    )
    private val BY_NAME = CRITTERS.associateBy { it.name }
    private val LONGEST_FIRST = CRITTERS.sortedByDescending { it.name.length }
    internal fun critterIn(line: String) = LONGEST_FIRST.firstOrNull { line.contains(it.name) }
    private fun inBiome(biome: Biome) = CRITTERS.filter { it.biome == biome }

    // ---------------------------------------------------------------- run

    class Run(val self: String, val started: Long) {
        val own = LinkedHashMap<Critter, Int>()
        val shared = LinkedHashMap<Critter, MutableMap<String, Int>>()
        val attempts = LinkedHashMap<Critter, Int>()
        val shards = LinkedHashMap<Critter, Int>()
        var ownShards = 0
        var totalShards = 0
        var lastEvent = started
        /** Milestones already announced this run. */
        val announced = HashSet<String>()

        fun partyCatches(c: Critter) = (own[c] ?: 0) + (shared[c]?.values?.sum() ?: 0)
        fun required(c: Critter) = if (uniqueOnly.value || c.quota <= 0) 1 else c.quota
        fun complete(c: Critter) = partyCatches(c) >= required(c)
        fun partyUnique(biome: Biome? = null) = CRITTERS.count { (biome == null || it.biome == biome) && complete(it) }
        fun ownUnique() = own.size
        fun missing(biome: Biome) = inBiome(biome).filterNot(::complete)
        fun biomeComplete(biome: Biome) = missing(biome).isEmpty()
        val isEmpty get() = own.isEmpty() && shared.isEmpty() && attempts.isEmpty()

        /** Unique species per player, per biome; you first, then the others alphabetically. */
        fun uniquePerPlayer(): Map<String, Map<Biome, Int>> {
            val result = LinkedHashMap<String, MutableMap<Biome, Int>>()
            if (own.isNotEmpty()) result[self] = own.keys.groupingBy { it.biome }.eachCount().toMutableMap()
            val others = sortedMapOf<String, MutableMap<Biome, Int>>()
            for ((critter, byPlayer) in shared) for (player in byPlayer.keys) {
                others.getOrPut(player) { mutableMapOf() }.merge(critter.biome, 1, Int::plus)
            }
            result.putAll(others)
            return result
        }
    }

    private var current: Run? = null
    private var last: Run? = null

    private val ATTEMPT = Regex("""^You threw a Critter Capsule at the (.+)!$""")
    private val LOOT_SHARE_CATCHER = Regex("""from\s+(\w{1,16})\s+(?:catching|finding)\b""")
    private val SHARD_AMOUNT = Regex("""(\d[\d,]*)x\s+\S""")
    private val ENTERED = Regex("""^(?:\[[^]]+]\s*)?(\w{1,16}) entered Critter Safari!$""")
    private val HOTSPOT = Regex("""^HOTSPOT! Your Hunting Hotspot is the (.+?) Biome!$""")
    private val PLAYER_SAID = Regex("""^(?:Party|Guild|Officer|Co-op|Team|Friend) >.*|^(?:From|To) .*|^(?:\[(?!NPC]|MOB])[^]]+]\s*)?\w{1,16}: .*""")
    private val STACK_SUFFIX = Regex("""\s*(?:\(\s*[x×]?\s*\d+\s*\)|\[\s*[x×]?\s*\d+\s*])$""")

    private var hotspot: Biome? = null

    private fun selfName() = Minecraft.getInstance().user?.name ?: "You"

    private fun onChat(raw: String) {
        if (!isEnabled) return
        for (part in raw.split(LINE_BREAK)) {
            val line = STACK_SUFFIX.replace(part.replace(CODES, "").trim(), "").trim()
            if (line.isEmpty() || PLAYER_SAID.matches(line)) continue
            handleLine(line)
        }
    }

    private fun handleLine(line: String) {
        HOTSPOT.matchEntire(line)?.let { m ->
            hotspot = Biome.entries.firstOrNull { it.display.equals(m.groupValues[1], ignoreCase = true) }
            hotspot?.let(CritterAlerts::hotspot)
            return
        }
        ENTERED.matchEntire(line)?.let { m ->
            if (m.groupValues[1] == selfName()) { startRun(); chatEntered = true; ticksAway = 0; arrivalStartsRun = false; lastEventMillis = System.currentTimeMillis() }
            return
        }
        val now = System.currentTimeMillis()
        when {
            line.startsWith("CAPTURE!") -> {
                val critter = critterIn(line) ?: return
                Minecraft.getInstance().let { SafariMap.learn(it, critter.biome) }
                val run = runForEvent(now)
                val amount = shardAmount(line)
                run.own.merge(critter, 1, Int::plus)
                run.shards.merge(critter, amount, Int::plus)
                run.ownShards += amount
                run.totalShards += amount
                CritterWorld.onCatch(critter)
                CritterAlerts.checkMilestones(run)
            }
            line.startsWith("LOOT SHARE!") -> {
                val critter = critterIn(line) ?: return
                val catcher = LOOT_SHARE_CATCHER.find(line)?.groupValues?.get(1) ?: return
                val run = runForEvent(now)
                val amount = shardAmount(line)
                run.shared.getOrPut(critter) { sortedMapOf() }.merge(catcher, 1, Int::plus)
                run.shards.merge(critter, amount, Int::plus)
                run.totalShards += amount
                CritterAlerts.checkMilestones(run)
            }
            else -> {
                if (CritterAlerts.onLine(line)) return
                val critter = ATTEMPT.matchEntire(line)?.let { BY_NAME[it.groupValues[1]] } ?: return
                runForEvent(now).attempts.merge(critter, 1, Int::plus)
                CritterWorld.onThrow(critter)
            }
        }
    }

    private fun runForEvent(now: Long): Run {
        // A catch means you are already in: a pending "arrival starts a run" would wipe it.
        arrivalStartsRun = false
        chatEntered = true
        val run = current ?: startRun()
        run.lastEvent = now
        lastEventMillis = now
        return run
    }

    private fun shardAmount(line: String) =
        SHARD_AMOUNT.find(line)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 1

    private fun startRun(): Run {
        current?.let(::archive)
        CritterAlerts.reset()
        CritterWorld.reset()
        return Run(selfName(), System.currentTimeMillis()).also { current = it; hotspot = null }
    }

    private fun endRun() {
        SafariMap.save()
        current?.let(::archive)
        current = null
    }

    private fun archive(run: Run) {
        if (run.isEmpty) return
        last = run
        RunHistory.add(run)
    }

    // ---------------------------------------------------------------- where am I

    private enum class Where { ELSEWHERE, ENTRANCE, INSIDE }

    private var where = Where.ELSEWHERE
    private var biome: Biome? = null
    private var chatEntered = false
    private var ticks = 0
    private var ticksAway = 0
    private var arrivalStartsRun = false
    private var lastEventMillis = 0L
    private var lastLevel: ClientLevel? = null

    private fun tick() {
        val mc = Minecraft.getInstance()
        if (mc.level !== lastLevel) {
            lastLevel = mc.level
            // New world (warp, server switch): the run you were in is over.
            endRun()
            chatEntered = false
            where = Where.ELSEWHERE
            biome = null
            ticksAway = 0
            arrivalStartsRun = true
        }
        if (!isEnabled || mc.player == null || ++ticks % 10 != 0) return
        updateLocation(mc)

        // Arriving after being away 5 s starts a run, leaving for 2 s
        // with no catch in the last minute ends it.
        if (where != Where.ELSEWHERE) {
            // ...unless the entry banner or a catch just came in (then that already is the new run).
            if (ticksAway >= 100 && System.currentTimeMillis() - lastEventMillis > 10_000) arrivalStartsRun = true
            ticksAway = 0
            if (arrivalStartsRun) { arrivalStartsRun = false; startRun() }
        } else {
            ticksAway += 10
            if (current != null && System.currentTimeMillis() - lastEventMillis >= 60_000 && ticksAway >= 40) endRun()
        }
    }


    private fun updateLocation(mc: Minecraft) {
        val stated = statedArea(mc)
        val named = stated?.let { s -> Biome.entries.firstOrNull { s.contains("${it.display} Biome") } }
        val seen = labelBiome(mc)
        // Teach the map from what is certain right now: the biome named on the scoreboard, or the critters around you.
        (named ?: seen)?.let { SafariMap.learn(mc, it) }
        val known = named ?: SafariMap.biomeAt(mc) ?: seen
        where = when {
            stated != null -> when {
                named == null && !stated.contains("Safari") -> Where.ELSEWHERE
                stated.contains("Entrance") || stated.contains("Entry") -> Where.ENTRANCE
                known != null -> Where.INSIDE
                else -> Where.ENTRANCE
            }.also { chatEntered = it != Where.ELSEWHERE }
            seen != null || chatEntered -> Where.INSIDE
            else -> Where.ELSEWHERE
        }
        biome = if (where == Where.INSIDE) known else null
    }

    private val CODES = Regex("§.")
    private val INVISIBLE = Regex("""[\p{Cf}\p{Co}]""")
    private val LINE_BREAK = Regex("""\r?\n|\\n""")
    internal fun strip(text: String) = text.replace(CODES, "").replace(INVISIBLE, "").trim()

    /** "Area" from the sidebar (⏣ Forest Biome) or the tab list ("Area: ..."). */
    private fun statedArea(mc: Minecraft): String? {
        Sidebar.area?.let { return it }
        for (info in mc.player?.connection?.onlinePlayers.orEmpty()) {
            val text = info.tabListDisplayName?.string?.let(::strip) ?: continue
            if (text.startsWith("Area: ")) return text.removePrefix("Area: ").trim().ifEmpty { null }
        }
        return null
    }

    /** The biome of the critters within 24 blocks, when they all agree (each species lives in one biome). */
    private fun labelBiome(mc: Minecraft): Biome? {
        val player = mc.player ?: return null
        val biomes = mc.level?.entitiesForRendering()
            ?.filter { it.hasCustomName() && it.distanceToSqr(player) < 24.0 * 24.0 }
            ?.mapNotNull { BY_NAME[strip(it.customName!!.string)]?.biome }
            ?.toSet().orEmpty()
        return biomes.singleOrNull()
    }

    /**
     * Where each biome is, learned while you play (critter_safari_map.json): a point every few blocks you've been
     * in a known biome. You're in the biome of the nearest point within 40 blocks.
     */
    private object SafariMap {
        private val file: File by lazy { CedPaths.file("critter_safari_map.json") }
        private val gson = GsonBuilder().create()
        private val nodes: MutableList<IntArray> by lazy {
            runCatching { gson.fromJson<MutableList<IntArray>>(file.readText(), object : TypeToken<MutableList<IntArray>>() {}.type) }
                .getOrNull() ?: mutableListOf()
        }
        private var unsaved = 0
        private var savedAt = 0L

        fun biomeAt(mc: Minecraft): Biome? {
            val p = mc.player?.position() ?: return null
            var best: IntArray? = null
            var bestSq = 40.0 * 40.0
            for (n in nodes) {
                val dx = p.x - n[0]; val dy = p.y - n[1]; val dz = p.z - n[2]
                val d = dx * dx + dy * dy + dz * dz
                if (d < bestSq) { bestSq = d; best = n }
            }
            return best?.let { Biome.entries.getOrNull(it[3]) }
        }

        fun learn(mc: Minecraft, biome: Biome) {
            val p = mc.player?.blockPosition() ?: return
            if (nodes.size >= 5000) return
            if (nodes.any { n -> val dx = p.x - n[0]; val dy = p.y - n[1]; val dz = p.z - n[2]; dx * dx + dy * dy + dz * dz < 36 }) return
            nodes += intArrayOf(p.x, p.y, p.z, biome.ordinal)
            if (++unsaved >= 10 || System.currentTimeMillis() - savedAt > 30_000) save()
        }

        fun save() {
            if (unsaved == 0) return
            unsaved = 0
            savedAt = System.currentTimeMillis()
            runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(nodes)) }
                .onFailure { println("[CritterSafari] Failed to save the Safari map: ${it.message}") }
        }
    }

    private fun showsHere() = when (showWhere.value) {
        "In Safari" -> where == Where.INSIDE
        "Everywhere" -> true
        else -> where != Where.ELSEWHERE
    }

    // ---------------------------------------------------------------- run history

    /** Saved runs (critter_runs.json); an older runs file in the same format is imported the first time. */
    object RunHistory {
        class Record {
            var started = 0L
            var ended = 0L
            var self: String? = null
            var own: MutableMap<String, Int> = LinkedHashMap()
            var shared: MutableMap<String, Int> = LinkedHashMap()
            var attempts: MutableMap<String, Int> = LinkedHashMap()
            var shards: MutableMap<String, Int> = LinkedHashMap()
            var ownShards = 0
            var totalShards = 0
        }

        private const val MAX_RUNS = 500
        private val gson = GsonBuilder().setPrettyPrinting().create()
        private val file: File by lazy { CedPaths.file("critter_runs.json") }
        val runs: MutableList<Record> by lazy { load() }

        private fun load(): MutableList<Record> {
            val source = file.takeIf { it.exists() }
                ?: FabricLoader.getInstance().configDir.resolve("crittermod-runs.json").toFile().takeIf { it.exists() }
                ?: return mutableListOf()
            return runCatching {
                gson.fromJson<MutableList<Record>>(source.readText(), object : TypeToken<MutableList<Record>>() {}.type)
            }.getOrNull() ?: mutableListOf()
        }

        fun add(run: Run) {
            runs += Record().apply {
                started = run.started
                ended = run.lastEvent
                self = run.self
                run.own.forEach { (c, n) -> own[c.name] = n }
                run.shared.forEach { (c, m) -> shared[c.name] = m.values.sum() }
                run.attempts.forEach { (c, n) -> attempts[c.name] = n }
                run.shards.forEach { (c, n) -> shards[c.name] = n }
                ownShards = run.ownShards
                totalShards = run.totalShards
            }
            while (runs.size > MAX_RUNS) runs.removeAt(0)
            runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(runs)) }
                .onFailure { println("[CritterSafari] Failed to save runs: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- HUD

    private const val LABEL = 0xFFBBBBBB.toInt()
    private const val DIM = 0xFF888888.toInt()
    private const val DONE = 0xFF55FF55.toInt()
    private const val HEADER = 0xFFFFAA00.toInt()
    private const val YOU = 0xFF55FFFF.toInt()

    /** Rows of "left ... right" text, optionally with a progress bar under the right side. */
    private class Panel {
        class Row(val left: String, val leftColor: Int, val right: String = "", val rightColor: Int = LABEL, val bar: Float = -1f, val barColor: Int = 0)
        val rows = mutableListOf<Row>()
        fun title(text: String, color: Int) { rows += Row(text, color) }
        fun pair(left: String, right: String, leftColor: Int, rightColor: Int) { rows += Row(left, leftColor, right, rightColor) }
        fun bar(label: String, have: Int, total: Int, labelColor: Int, valueColor: Int) {
            rows += Row(label, labelColor, "$have/$total", valueColor, if (total == 0) 0f else have.toFloat() / total, valueColor)
        }
        fun blank() { rows += Row("", 0) }
    }

    private fun progressPanel(): Panel {
        val run = current ?: last
        val shown = run ?: Run(selfName(), System.currentTimeMillis())
        val total = CRITTERS.size
        val p = Panel()
        p.title(when {
            run == null -> "Critter Safari (ready)"
            current != null -> "Critter Safari  " + duration(System.currentTimeMillis() - shown.started)
            else -> "Critter Safari (last run)"
        }, HEADER)
        p.bar("Party", shown.partyUnique(), total, LABEL, if (shown.partyUnique() == total) DONE else -1)
        p.bar("You", shown.ownUnique(), total, LABEL, YOU)
        if (showHotspot.value) hotspot?.let { p.pair("Hotspot", it.display, LABEL, it.color) }
        if (showValue.value && shown.shards.isNotEmpty()) {
            val shards = shown.shards.mapKeys { it.key.name }
            val offer = priceSide.value == "Sell Offer"
            val value = CritterPrices.format(CritterPrices.value(shards, offer, subtractTax.value))
            val other = if (showBoth.value) "  (" + CritterPrices.format(CritterPrices.value(shards, !offer, subtractTax.value)) + if (offer) " instant)" else " offers)" else ""
            if (CritterPrices.loaded) p.pair("Value", value + other, LABEL, HEADER)
        }
        p.blank()
        for (b in Biome.entries) {
            val done = shown.biomeComplete(b)
            p.bar(if (done) "${b.display} *" else b.display, shown.partyUnique(b), inBiome(b).size, b.color, if (done) DONE else b.color)
        }
        if (showPerPlayer.value) {
            val perPlayer = shown.uniquePerPlayer()
            if (perPlayer.size > 1) {
                p.blank()
                perPlayer.forEach { (player, counts) ->
                    p.pair(player, counts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.display} ${it.value}" }, YOU, DIM)
                }
            }
        }
        return p
    }

    private fun missingPanel(b: Biome): Panel {
        val run = current ?: last
        val missing = run?.missing(b) ?: inBiome(b)
        val p = Panel()
        if (missing.isEmpty()) {
            p.title("${b.display} Biome", b.color)
            p.pair("all ${inBiome(b).size} caught", "", DONE, DIM)
            return p
        }
        p.title("${b.display} Biome — ${missing.size} left", b.color)
        for (c in missing) {
            val caught = run?.partyCatches(c) ?: 0
            val need = run?.required(c) ?: 1
            val tried = run?.attempts?.get(c) ?: 0
            val near = if (nearby.value) CritterWorld.inSight(c) else 0
            val note = listOfNotNull(
                "$caught/$need".takeIf { need > 1 },
                "$tried tried".takeIf { tried > 0 && need <= 1 },
                "$near near".takeIf { near > 0 },
            ).joinToString("  ")
            p.pair(c.name, note, c.rarity.color, if (near > 0) DONE else DIM)
        }
        CritterWorld.wallsLeft(b)?.let { left ->
            p.pair(if (b == Biome.CAVERN) "Snooper walls" else "Troodon walls", if (left == 0) "all broken" else "$left left", LABEL, if (left == 0) DONE else DIM)
        }
        if (b == Biome.CAVERN && CritterWorld.moundsNearby() > 0) p.pair("Mounds nearby", CritterWorld.moundsNearby().toString(), LABEL, DIM)
        return p
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** One NanoVG card (same style as the other HUD panels): "left ... right" rows, progress bars on the right. */
    private fun draw(g: GuiGraphicsExtractor, panel: Panel, hud: PanelHud) {
        val s = hud.panelScale
        val size = 11f * s
        val lineH = 13f * s
        val pad = 7f * s
        val gap = 10f * s
        val barW = 36f * s
        val leftW = panel.rows.filter { it.right.isNotEmpty() }.maxOfOrNull { HudCard.width(it.left, size) } ?: 0f
        val width = HudCard.steady(hud.label, panel.rows.maxOf { r ->
            if (r.right.isEmpty()) HudCard.width(r.left, size)
            else leftW + gap + HudCard.width(r.right, size) + if (r.bar >= 0) gap + barW else 0f
        } + pad * 2, s)
        val height = panel.rows.size * lineH + pad * 2 - (lineH - size)
        hud.lastWidth = (width / s).toInt()
        hud.lastHeight = (height / s).toInt()

        HudCard.draw(g, hud.panelX, hud.panelY) {
            HudCard.card(width, height, s)
            panel.rows.forEachIndexed { i, r ->
                val y = pad + i * lineH
                HudCard.text(r.left, pad, y, size, r.leftColor)
                if (r.right.isEmpty()) return@forEachIndexed
                HudCard.text(r.right, pad + leftW + gap, y, size, r.rightColor)
                if (r.bar >= 0) {
                    val bx = width - pad - barW
                    val by = y + size / 2f - 1.5f * s
                    NVGRenderer.rect(bx, by, barW, 3f * s, 0xFF33333A.toInt(), 1.5f * s)
                    val fill = barW * r.bar.coerceIn(0f, 1f)
                    if (fill > 0f) NVGRenderer.rect(bx, by, fill, 3f * s, r.barColor or 0xFF000000.toInt(), 1.5f * s)
                }
            }
        }
    }

    /** Position / scale persistence shared by the two panels (critter_hud.json). */
    abstract class PanelHud(override val label: String, private val key: String, private val defX: Int, private val defY: Int, private val defScale: Float = 1f) : HudElement {
        override var panelX = defX
        override var panelY = defY
        override var panelScale = defScale
        override var lastWidth = 0
        override var lastHeight = 0

        init { HudPositions.load(this, key) }
        override fun save() = HudPositions.save()
        override fun resetPosition() { panelX = defX; panelY = defY; panelScale = defScale }
        override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }
    }

    private object HudPositions {
        private val file by lazy { CedPaths.hud("critter") }
        private val gson = GsonBuilder().create()
        private val saved: MutableMap<String, FloatArray> by lazy {
            runCatching { gson.fromJson<MutableMap<String, FloatArray>>(file.readText(), object : TypeToken<MutableMap<String, FloatArray>>() {}.type) }
                .getOrNull() ?: mutableMapOf()
        }
        private val huds = mutableMapOf<String, PanelHud>()

        fun load(hud: PanelHud, key: String) {
            huds[key] = hud
            saved[key]?.takeIf { it.size == 3 }?.let { hud.panelX = it[0].toInt(); hud.panelY = it[1].toInt(); hud.panelScale = it[2] }
        }

        fun save() {
            huds.forEach { (k, h) -> saved[k] = floatArrayOf(h.panelX.toFloat(), h.panelY.toFloat(), h.panelScale) }
            runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(saved)) }
        }
    }

    object ProgressHud : PanelHud("Critter Progress", "progress", 3, 2, 0.9f) {
        fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
            if (!isEnabled || Minecraft.getInstance().gui.hud.isHidden || !showsHere()) return
            draw(g, progressPanel(), this)
        }
        override fun renderInternal(g: GuiGraphicsExtractor) = draw(g, progressPanel(), this)
    }

    private fun tradesPanel(): Panel? {
        val player = Minecraft.getInstance().player ?: return null
        if (CritterWorld.trades.isEmpty()) return null
        val p = Panel()
        p.title("Hunter Trades", HEADER)
        for (t in CritterWorld.trades.sortedBy { it.pos.distanceTo(player.position()) }) {
            val metres = t.pos.distanceTo(player.position()).toInt()
            p.pair("${t.critter.name} for ${t.item}", "${t.biome?.display ?: ""} ${metres}m".trim(), t.critter.rarity.color, DIM)
        }
        return p
    }

    object TradesHud : PanelHud("Critter Trades", "trades", 144, 6, 0.7f) {
        fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
            if (!isEnabled || !showTrades.value || Minecraft.getInstance().gui.hud.isHidden || !showsHere()) return
            draw(g, tradesPanel() ?: return, this)
        }
        override fun renderInternal(g: GuiGraphicsExtractor) {
            val sample = Panel().apply { title("Hunter Trades", HEADER); pair("Mantis Shrimp for Wriggleworm", "Icy 40m", Rarity.RARE.color, DIM) }
            draw(g, tradesPanel() ?: sample, this)
        }
    }

    object MissingHud : PanelHud("Critter Missing", "missing", 978, 0) {
        fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
            if (!isEnabled || !showMissing.value || Minecraft.getInstance().gui.hud.isHidden) return
            val b = biome ?: return
            if (!showsHere()) return
            draw(g, missingPanel(b), this)
        }
        override fun renderInternal(g: GuiGraphicsExtractor) = draw(g, missingPanel(biome ?: Biome.FOREST), this)
    }
}
