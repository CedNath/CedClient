package ced.cedclient.achievements

import ced.cedclient.config.CedPaths
import ced.cedclient.state.CosmeticOverride
import ced.cedclient.state.DungeonRunInfo
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Debug
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import java.time.LocalDate

/** What an unlocked achievement gives: a local cosmetic on your own name (halo, level colour). Nothing is sent to other players. */
class Reward(
    val id: String,
    val label: String,
    /** "halo" or "level": one of each can be equipped at a time. */
    val slot: String,
    /** 0xRRGGBB of the halo ring. */
    val halo: Int? = null,
    /** Bit flags in [ced.cedclient.state.HALO_ANIMS] order. */
    val haloAnim: Int = 0,
    /** Bit flags in [ced.cedclient.state.HALO_PARTICLES] order. */
    val haloParticles: Int = 0,
    /** One or more 0xRRGGBB stops: a gradient for the SkyBlock level prefix. */
    val levelColor: List<Int>? = null
)

class Achievement(
    val id: String,
    val name: String,
    val description: String,
    val group: String,
    val counter: String,
    val target: Long,
    val reward: Reward? = null
)

/**
 * Local achievements: counters (mod usage, dungeon runs, modules tried) are updated by [bump] / [max] and by a once-a-second
 * tick; an achievement unlocks when its counter reaches its target, announces itself, and may give a [Reward] you can equip
 * in the trophy screen (/cc achievements). Saved in config/cedclient/data/achievements.json, never sent anywhere.
 */
object Achievements {
    private class Save {
        val counters = LinkedHashMap<String, Long>()
        val unlocked = LinkedHashMap<String, Long>()
        val modulesTried = LinkedHashSet<String>()
        val panelsTried = LinkedHashSet<String>()
        val days = LinkedHashSet<String>()
        val equipped = LinkedHashMap<String, String>()
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val file get() = CedPaths.file("achievements.json")
    private var save = Save()
    private var dirty = false

    private val rewardList = listOf(
        Reward("halo_teal", "Teal Halo", "halo", halo = 0x2EE6C5),
        Reward("halo_gold", "Gold Halo", "halo", halo = 0xFFC83D, haloAnim = 1),
        Reward("halo_pink", "Pulsing Pink Halo", "halo", halo = 0xFF6EC7, haloAnim = 1 shl 2),
        Reward("halo_rainbow", "Rainbow Halo", "halo", halo = 0xFFFFFF, haloAnim = (1 shl 3) or 1, haloParticles = 1),
        Reward("halo_flame", "Flame Halo", "halo", halo = 0xFF6A1A, haloAnim = 1 shl 1, haloParticles = 1 shl 2),
        Reward("level_sunset", "Sunset Level", "level", levelColor = listOf(0xFF7A18, 0xAF002D)),
        Reward("level_ocean", "Ocean Level", "level", levelColor = listOf(0x00C6FF, 0x0072FF)),
        Reward("level_aurora", "Aurora Level", "level", levelColor = listOf(0x7CFFCB, 0x74F2CE, 0xA18CD1)),
    )
    val rewards: Map<String, Reward> = rewardList.associateBy { it.id }

    val all: List<Achievement> = listOf(
        // Using the mod
        Achievement("hello", "Hello, CedClient", "Start the game with CedClient installed.", "Mod", "launches", 1),
        Achievement("gui10", "Window Shopper", "Open the ClickGUI 10 times.", "Mod", "gui_opens", 10, rewards["halo_teal"]),
        Achievement("gui100", "Menu Native", "Open the ClickGUI 100 times.", "Mod", "gui_opens", 100),
        Achievement("mods5", "Tinkerer", "Turn on 5 different modules.", "Mod", "modules_tried", 5),
        Achievement("mods15", "Toolbox", "Turn on 15 different modules.", "Mod", "modules_tried", 15, rewards["halo_gold"]),
        Achievement("mods30", "Everything Bagel", "Turn on 30 different modules.", "Mod", "modules_tried", 30, rewards["level_ocean"]),
        Achievement("panels", "Cartographer", "Turn on a module in 8 different ClickGUI panels.", "Mod", "panels_tried", 8),
        Achievement("play1", "Settling In", "Play 1 hour with CedClient.", "Mod", "playtime_s", 3600),
        Achievement("play10", "Regular", "Play 10 hours with CedClient.", "Mod", "playtime_s", 36_000, rewards["halo_pink"]),
        Achievement("play50", "Resident", "Play 50 hours with CedClient.", "Mod", "playtime_s", 180_000, rewards["halo_rainbow"]),
        Achievement("days3", "Back Again", "Play on 3 different days.", "Mod", "days", 3),
        Achievement("days14", "Habit", "Play on 14 different days.", "Mod", "days", 14, rewards["level_aurora"]),
        // Dungeons (Hypixel)
        Achievement("run1", "First Descent", "Enter a dungeon run.", "Dungeons", "dungeon_runs", 1),
        Achievement("run10", "Delver", "Enter 10 dungeon runs.", "Dungeons", "dungeon_runs", 10),
        Achievement("run50", "Catacombs Local", "Enter 50 dungeon runs.", "Dungeons", "dungeon_runs", 50, rewards["halo_flame"]),
        Achievement("boss5", "Boss Rush", "Reach the boss room in 5 runs.", "Dungeons", "boss_rooms", 5),
        Achievement("s1", "S Rank", "Reach 270 score in a run.", "Dungeons", "s_runs", 1),
        Achievement("splus1", "Perfect Run", "Reach 300 score in a run.", "Dungeons", "splus_runs", 1, rewards["level_sunset"]),
        Achievement("splus10", "Clean Sweep", "Reach 300 score in 10 runs.", "Dungeons", "splus_runs", 10),
        Achievement("necron5", "Gravity Check", "See your Necron drag landing predicted 5 times.", "Dungeons", "necron_drops", 5),
        Achievement("secrets30", "Secret Hunter", "Find 30 secrets in one run.", "Dungeons", "secrets_run", 30),
        // Features of the mod
        Achievement("dungmods", "Dungeoneer's Kit", "Turn on 5 different modules from the Dungeons panel.", "Features", "dungeon_modules", 5),
        Achievement("cosmetic", "Dress Up", "Equip an achievement reward.", "Features", "rewards_equipped", 1),
        Achievement("hud", "Layout Artist", "Open the HUD editor.", "Features", "hud_edits", 1),
        Achievement("skyblock", "SkyBlock Setup", "Turn on 5 different modules from the Skyblock panel.", "Features", "skyblock_modules", 5),
    )

    private val byCounter = all.groupBy { it.counter }
    val groups = listOf("Mod", "Dungeons", "Features")

    fun counter(name: String): Long = save.counters[name] ?: 0L
    fun isUnlocked(a: Achievement) = save.unlocked.containsKey(a.id)
    fun unlockedAt(a: Achievement): Long? = save.unlocked[a.id]
    val unlockedCount get() = save.unlocked.size

    fun equipped(slot: String): String? = save.equipped[slot]
    fun hasReward(r: Reward) = all.any { it.reward?.id == r.id && isUnlocked(it) }

    fun toggleEquip(r: Reward) {
        if (!hasReward(r)) return
        if (save.equipped[r.slot] == r.id) save.equipped.remove(r.slot) else { save.equipped[r.slot] = r.id; bump("rewards_equipped") }
        RewardCosmetics.invalidate()
        dirty = true
        flush()
    }

    fun bump(counter: String, by: Long = 1) { set(counter, counter(counter) + by) }
    fun max(counter: String, value: Long) { if (value > counter(counter)) set(counter, value) }

    private fun set(counter: String, value: Long) {
        save.counters[counter] = value
        dirty = true
        for (a in byCounter[counter] ?: return) if (value >= a.target && !save.unlocked.containsKey(a.id)) unlock(a)
    }

    private fun unlock(a: Achievement) {
        save.unlocked[a.id] = System.currentTimeMillis()
        dirty = true
        Debug.line("Achievements", "unlocked ${a.id}")
        val mc = Minecraft.getInstance()
        mc.execute {
            runCatching {
                mc.player?.sendSystemMessage(Component.literal("§b[CC] §6Achievement unlocked: §f${a.name}§7 - ${a.description}" + (a.reward?.let { " §a(reward: ${it.label})" } ?: "")))
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.2f, 0.8f))
            }
        }
    }

    /** Counts a module the first time it is turned on by the player (not when the saved config is replayed). */
    fun moduleEnabled(name: String, panel: String) {
        if (save.modulesTried.add(name)) {
            dirty = true
            set("modules_tried", save.modulesTried.size.toLong())
            when (panel) { "Dungeons" -> bump("dungeon_modules"); "Skyblock" -> bump("skyblock_modules") }
        }
        if (save.panelsTried.add(panel)) { dirty = true; set("panels_tried", save.panelsTried.size.toLong()) }
    }

    private var world: Any? = null
    private var inRun = false
    private var wasBoss = false
    private var gotS = false
    private var gotSPlus = false
    private var ticks = 0

    fun init() {
        runCatching { if (file.isFile) gson.fromJson(file.readText(), Save::class.java)?.let { save = it } }
        bump("launches")
        if (save.days.add(LocalDate.now().toString())) { dirty = true; set("days", save.days.size.toLong()) }
        ClientTickEvents.END_CLIENT_TICK.register { mc -> tick(mc) }
    }

    private fun tick(mc: Minecraft) {
        if (mc.level !== world) {
            world = mc.level
            inRun = false; wasBoss = false; gotS = false; gotSPlus = false
        }
        if (++ticks % 20 != 0) return
        if (mc.level != null) bump("playtime_s")
        if (DungeonState.inDungeon) {
            if (!inRun) { inRun = true; bump("dungeon_runs") }
            if (!wasBoss && DungeonState.inBoss) { wasBoss = true; bump("boss_rooms") }
            val score = DungeonRunInfo.score
            if (!gotS && score >= 270) { gotS = true; bump("s_runs") }
            if (!gotSPlus && score >= 300) { gotSPlus = true; bump("splus_runs") }
            max("secrets_run", DungeonRunInfo.secretsFound.toLong())
        }
        if (dirty && ticks % 100 == 0) flush()
    }

    fun flush() {
        if (!dirty) return
        dirty = false
        runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(save)) }
    }
}

/** The rewards you equipped, laid over your own name's cosmetic override (a server-set halo or level colour still wins). */
object RewardCosmetics {
    @Volatile private var cache: CosmeticOverride? = null
    @Volatile private var ready = false

    fun invalidate() { ready = false }

    private fun build(): CosmeticOverride? {
        val halo = Achievements.equipped("halo")?.let { Achievements.rewards[it] }
        val level = Achievements.equipped("level")?.let { Achievements.rewards[it] }
        if (halo == null && level == null) return null
        return CosmeticOverride(halo = halo?.halo, haloAnim = halo?.haloAnim ?: 0, haloParticles = halo?.haloParticles ?: 0, levelColor = level?.levelColor)
    }

    /** [server] is what the cosmetics server says about [ign]; only your own name gets a reward overlay. */
    fun overlay(ign: String, server: CosmeticOverride?): CosmeticOverride? {
        val self = Minecraft.getInstance().user.name
        if (!ign.equals(self, ignoreCase = true)) return server
        if (!ready) { cache = build(); ready = true }
        val mine = cache ?: return server
        if (server == null) return mine
        return server.copy(
            halo = server.halo ?: mine.halo,
            haloAnim = if (server.halo != null) server.haloAnim else mine.haloAnim,
            haloParticles = if (server.halo != null) server.haloParticles else mine.haloParticles,
            levelColor = server.levelColor ?: mine.levelColor
        )
    }
}
