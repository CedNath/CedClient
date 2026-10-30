package ced.cedclient.features.impl.funqol

import ced.cedclient.render.geometry
import ced.cedclient.features.impl.render.ActionBarHud
import ced.cedclient.config.CedPaths
import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.TickEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.RenderableSetting
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.IslandState
import ced.cedclient.state.Sidebar
import ced.cedclient.utils.Color
import ced.cedclient.utils.getSkullTexture
import com.google.gson.GsonBuilder
import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import java.io.File
import java.util.Base64
import java.util.Optional

/**
 * Slayer HUD (every slayer: quest / boss timers, kill times, bosses per hour, RNG meter, boss HP), alerts for
 * every slayer (boss soon / spawned, mini-bosses, Voidgloom beacons, Inferno attunement / fire pits / pillars,
 * Tarantula hatchlings; the overworld boss tracking lives in [SlayerBosses]) plus the Vampire
 * (Riftstalker Bloodfiend) helpers: your boss boxed, its HP and when the Steak Stake works (20% HP), Twinclaws
 * countdown, Mania circles timer, Blood Ichor / Killer Spring boxes and the Stillgore Château Blood Effigies.
 *
 * Your boss: Hypixel no longer labels whose boss is whose, so when the sidebar switches to "Slay the boss!" the
 * newest Bloodfiend within 15 blocks of you is locked in as yours until it dies or the quest ends.
 */
object VampireSlayer : Module(
    "Slayer Tracker",
    Category.Skyblock,
    "Slayer timers and stats for every slayer, and Vampire Slayer helpers (Steak, Twinclaws, Ichor, Springs, Effigies).",
    defaultEnabled = false
), HudElement {
    private val hud = BooleanSetting("Slayer HUD", true, "Quest and boss timers, kill times, bosses per hour, RNG meter.")
    private val bossBox = BooleanSetting("Box Your Boss", true, "Vampire: boxes the Bloodfiend locked in as yours.")
    private val bossColor = ColorSetting("Boss Color", Color(85, 255, 85)).withDependency { bossBox.value }
    // Each slayer's own options live in its module (SlayerModules.kt); a feature runs only while that module is on.
    private val steakAlert = SlayerGate(RiftstalkerSlayer, RiftstalkerSlayer.steakAlert)
    private val steakColor = RiftstalkerSlayer.steakColor
    private val twinclawsAlert = SlayerGate(RiftstalkerSlayer, RiftstalkerSlayer.twinclawsAlert)
    private val ichor = SlayerGate(RiftstalkerSlayer, RiftstalkerSlayer.ichor)
    private val ichorColor = RiftstalkerSlayer.ichorColor
    private val ichorSpots = SlayerGate(RiftstalkerSlayer, RiftstalkerSlayer.ichorSpots)
    private val ichorSpotColor = RiftstalkerSlayer.ichorSpotColor
    private val springs = SlayerGate(RiftstalkerSlayer, RiftstalkerSlayer.springs)
    private val springColor = RiftstalkerSlayer.springColor
    private val effigies = SlayerGate(RiftstalkerSlayer, RiftstalkerSlayer.effigies)
    private val effigyColor = RiftstalkerSlayer.effigyColor
    private val effigyXray = SlayerGate(RiftstalkerSlayer, RiftstalkerSlayer.effigyXray)

    // ---- every slayer
    private val bossSoon = BooleanSetting("Boss Soon Alert", true, "Title when the quest is 90% done.")
    private val bossSpawned = BooleanSetting("Boss Spawned Alert", true)
    private val minibossAlert = BooleanSetting("Mini-Boss Alert", true)
    private val minibossBox = BooleanSetting("Box Mini-Bosses", true, "Boxes slayer mini-bosses near you (anyone's).")
    private val minibossColor = ColorSetting("Mini-Boss Color", Color(255, 170, 255)).withDependency { minibossBox.value }
    private val beaconAlert = SlayerGate(VoidgloomSlayer, VoidgloomSlayer.beaconAlert)
    private val beaconColor = VoidgloomSlayer.beaconColor
    private val beaconStyle = VoidgloomSlayer.beaconStyle
    private val laserTimer = SlayerGate(VoidgloomSlayer, VoidgloomSlayer.laserTimer)
    private val nukekubi = SlayerGate(VoidgloomSlayer, VoidgloomSlayer.nukekubi)
    private val nukekubiColor = VoidgloomSlayer.nukekubiColor
    private val nukekubiStyle = VoidgloomSlayer.nukekubiStyle
    private val attunementAlert = SlayerGate(InfernoSlayer, InfernoSlayer.attunementAlert)
    private val firePits = SlayerGate(InfernoSlayer, InfernoSlayer.firePits)
    private val firePillar = SlayerGate(InfernoSlayer, InfernoSlayer.firePillar)
    private val hatchlings = SlayerGate(TarantulaSlayer, TarantulaSlayer.hatchlings)

    // Inline: the main toggles. The shared alerts sit in a popup so the panel stays short.
    private val alertSettings: List<RenderableSetting<*>> = listOf(bossSoon, bossSpawned, minibossAlert, minibossBox, minibossColor)
    private val popupSettings = alertSettings

    private fun popup(title: String, vararg columns: SettingsPopup.Section) =
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(SettingsPopup(title, columns.map { listOf(it) }))

    private val alertsButton = ActionSetting("Alerts", "Boss soon, boss spawned, mini-boss titles.") {
        popup("Slayer Alerts", SettingsPopup.Section("Every Slayer", alertSettings))
    }

    // HUD lines, keyed by the row label they hide (the header always shows).
    private val hudLines = linkedMapOf(
        "Quest" to BooleanSetting("Quest Timer", true), "Boss" to BooleanSetting("Boss Timer", true),
        "HP" to BooleanSetting("Boss HP", true), "Steak at" to BooleanSetting("Steak At", true),
        "Mania circles" to BooleanSetting("Mania Circles", true), "Twinclaws" to BooleanSetting("Twinclaws", true),
        "Attunement" to BooleanSetting("Attunement", true), "Hits left" to BooleanSetting("Hits Left", true),
        "Fire pillar" to BooleanSetting("Fire Pillar", true), "Laser phase" to BooleanSetting("Laser Phase", true), "Beacons" to BooleanSetting("Beacons", true),
        "Last kill" to BooleanSetting("Last Kill", true), "Kills" to BooleanSetting("Kills / Hour", true),
        "Avg / best" to BooleanSetting("Avg / Best", true), "RNG meter" to BooleanSetting("RNG Meter", true),
        "Effigies" to BooleanSetting("Effigies Line", true))
    private fun shown(label: String) = hudLines[label.substringBefore(" (")]?.value != false
    private val hudButton = ActionSetting("HUD Lines", "Pick what the Slayer HUD shows.") {
        val all = hudLines.values.toList()
        popup("Slayer HUD", SettingsPopup.Section("Timers / Boss", all.take(10)), SettingsPopup.Section("Stats", all.drop(10)))
    }

    init {
        addSettings(hud, hudButton, bossBox, bossColor, alertsButton)
        addSettings(*popupSettings.toTypedArray(), *hudLines.values.toTypedArray())
        (popupSettings + hudLines.values).forEach { it.inPopup = true }
        on<ChatMessageEvent> { onChat(it.unformattedText.trim()) }
        // Hypixel's "Impel: ..." can be the title or the subtitle; the Twinclaws title must not cover it.
        on<ced.cedclient.events.TitleEvent> { if (it.unformattedText.contains("Impel", true)) impelTitleAt = System.currentTimeMillis() }
        on<ced.cedclient.events.SubtitleEvent> { if (it.unformattedText.contains("Impel", true)) impelTitleAt = System.currentTimeMillis() }
        on<TickEvent.End> { tick() }
        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            renderWorld(context)
        })
    }

    private val mc get() = Minecraft.getInstance()
    private fun inRift() = IslandState.island == IslandState.Island.THE_RIFT

    // ---------------------------------------------------------------- quest + stats (every slayer)

    /** "Riftstalker Bloodfiend V" etc. from the sidebar, while a quest is running. */
    private var slayer: String? = null
    /** The last slayer you had a quest for, so its session stats stay up between quests. */
    private var lastSlayer: String? = null
    private var questStart = 0L
    private var bossSpawn = 0L
    private var bossUp = false
    private var lastKill: Long? = null
    private var rngXp: String? = null
    private var soonWarned = false
    private var lastAttunement: String? = null
    private var beaconCount = 0

    private class Session(var kills: Int = 0, var killTimeTotal: Long = 0, var best: Long = Long.MAX_VALUE, val started: Long = System.currentTimeMillis())
    private val sessions = LinkedHashMap<String, Session>()

    private val RNG = Regex("""^RNG Meter - ([\d,]+) Stored XP$""")
    private val MINIBOSS = Regex("""^SLAYER MINI-BOSS (.+) has spawned!$""")
    private val PROGRESS = Regex("""^\(([\d,.]+k?)/([\d,.]+k?)\)""")

    private fun onChat(line: String) {
        if (!isEnabled) return
        when {
            line == "SLAYER QUEST STARTED!" -> { questStart = System.currentTimeMillis(); bossUp = false; boss = null; soonWarned = false; SlayerBosses.reset() }
            line == "SLAYER QUEST COMPLETE!" || line == "NICE! SLAYER BOSS SLAIN!" -> {
                if (bossUp) {
                    val time = System.currentTimeMillis() - bossSpawn
                    lastKill = time
                    val s = sessions.getOrPut(slayer ?: "Slayer") { Session() }
                    s.kills++; s.killTimeTotal += time; s.best = minOf(s.best, time)
                }
                bossUp = false; boss = null; SlayerBosses.reset()
            }
            line == "SLAYER QUEST FAILED!" || line == "Your Slayer Quest has been cancelled!" -> { bossUp = false; boss = null; SlayerBosses.reset() }
            line.startsWith("You need to kill the Broodfather's hatchlings") -> if (hatchlings.value) alert("§c§lKILL HATCHLINGS", "", 1.2f)
            else -> {
                RNG.matchEntire(line)?.let { rngXp = it.groupValues[1] }
                MINIBOSS.matchEntire(line)?.let { if (minibossAlert.value) alert("§e§lMINI-BOSS", "§f" + it.groupValues[1], 1.2f) }
            }
        }
    }

    private fun readQuest() {
        val lines = Sidebar.lines
        val i = lines.indexOf("Slayer Quest")
        slayer = if (i >= 0) lines.getOrNull(i + 1) else null
        slayer?.let { lastSlayer = it }
        val slay = i >= 0 && lines.drop(i + 1).take(3).any { it == "Slay the boss!" }
        if (i >= 0 && !slay && bossSoon.value && !soonWarned && inSlayerArea()) {
            lines.drop(i + 1).take(3).firstNotNullOfOrNull { PROGRESS.find(it) }?.let { m ->
                // "11k" = 11,000: dropping the suffix made 430/11k read as 430/11 (fired instantly)
                fun num(s: String) = (s.removeSuffix("k").replace(",", "").toDoubleOrNull() ?: 0.0) * (if (s.endsWith("k")) 1000 else 1)
                val have = num(m.groupValues[1])
                val need = num(m.groupValues[2])
                if (need > 0 && have / need >= 0.9) { soonWarned = true; alert("§e§lBOSS SOON", "", 1.0f) }
            }
        }
        if (slay && !bossUp) {
            if (bossSpawned.value) alert("§c§lBOSS SPAWNED", slayer ?: "", 1.4f)
            bossUp = true
            bossSpawn = System.currentTimeMillis()
            if (questStart == 0L) questStart = bossSpawn
            lockAt = bossSpawn
        }
        if (i < 0 && bossUp) { bossUp = false; boss = null; SlayerBosses.reset() }
    }

    // ---------------------------------------------------------------- vampire boss

    private const val STEAK_FRACTION = 0.2f
    private val TWINCLAWS = Regex("""TWINCLAWS\s+([\d.]+)s""")
    private const val MANIA_TICKS = 26 * 20
    private const val ICHOR_SKIN = "c0340923a6de4825a176813d133503eff186db0896e32b6704928c2a2bf68422"
    private const val SPRING_SKIN = "77f7a7bc8ac86f23ca7bf98afeb76960227e1832fe209a3026f6ceb8bde74f"

    private var boss: Player? = null
    /** Whose boss it is per its "Spawned by" stand (null when Hypixel doesn't show one). */
    private var bossOwner: String? = null
    private var lockAt = 0L
    private var twinclaws: Float? = null
    private var steakWarned = false
    private var twinclawsWarned = false
    private var ichors: List<AABB> = emptyList()
    private var springBoxes: List<AABB> = emptyList()

    private fun isBloodfiend(e: Player) = e !== mc.player && e.name.string.trim() == "Bloodfiend"

    /** Tier from the boss's max HP: 625 / 1100 / 1800 / 2400 / 3000. */
    private fun tier(max: Float) = TIER_HP.indexOfFirst { it == max } + 1
    private val TIER_HP = listOf(625f, 1100f, 1800f, 2400f, 3000f)

    /**
     * The boss's real max HP. Minecraft caps max health at 1024, so T3-T5 (1800 / 2400 / 3000) read as 1024: then
     * take the tier from the quest name on the sidebar ("Riftstalker Bloodfiend V") instead.
     */
    private fun maxHp(b: Player): Float {
        if (b.maxHealth < 1024f) return b.maxHealth
        val roman = slayer?.substringAfterLast(' ')
        val t = listOf("I", "II", "III", "IV", "V").indexOf(roman)
        return if (t >= 0) TIER_HP[t] else maxOf(b.maxHealth, b.health)
    }

    private fun trackBoss() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        // The old way: the Bloodfiend whose "Spawned by: <you>" stand is beside it, no timing needed.
        val spawned = SlayerBosses.spawnedBy(level)
        if (bossUp && boss == null) {
            boss = level.players().firstOrNull { isBloodfiend(it) && SlayerBosses.ownerNear(spawned, it).equals(mc.user.name, ignoreCase = true) }
        }
        bossOwner = boss?.let { SlayerBosses.ownerNear(spawned, it) }
        // Lock the newest Bloodfiend near you for 3 s after "Slay the boss!" shows up.
        if (bossUp && boss == null && System.currentTimeMillis() - lockAt < 3_000) {
            boss = level.players().filter { isBloodfiend(it) && it.distanceToSqr(player) < 15.0 * 15.0 }.minByOrNull { it.tickCount }
        }
        val b = boss
        if (b != null && (b.isRemoved || !b.isAlive)) boss = null

        twinclaws = null
        val stands = level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { it.distanceToSqr(player) < 30.0 * 30.0 }
        boss?.let { bossEntity ->
            twinclaws = stands.filter { it.distanceToSqr(bossEntity) < 36.0 && it.hasCustomName() }
                .firstNotNullOfOrNull { TWINCLAWS.find(it.customName!!.string)?.groupValues?.get(1)?.toFloatOrNull() }
        }
        // Always tracked (Slayer Helper walks onto the Twinclaws line); the boxes are what the setting toggles.
        ichors = stands.filter { skin(it)?.contains(ICHOR_SKIN) == true }.map { it.boundingBox.inflate(0.3) }
        springBoxes = if (springs.value) stands.filter { skin(it)?.contains(SPRING_SKIN) == true }.map { it.boundingBox.inflate(0.3) } else emptyList()
    }

    private fun skin(stand: ArmorStand) = SlayerBosses.skinOf(stand)

    private fun canSteak(b: Player) = b.health <= maxHp(b) * STEAK_FRACTION

    /** SlayerCheats: your locked Vampire boss is at Steak Stake HP right now. */
    internal fun bossSteakable() = boss?.let(::canSteak) == true
    /** Tier (1-5) of your locked Vampire boss, 0 when none. */
    internal fun bossTier() = boss?.let { tier(maxHp(it)) } ?: 0
    /** Mania: the boss rides its circles vehicle. */
    internal fun bossInMania() = boss?.vehicle != null
    /** Your locked boss (Mania Walk picks the green tile nearest it). */
    internal fun bossEntity(): Player? = boss
    /** Seconds left on your boss's Twinclaws, null when it isn't charging. */
    internal fun twinclawsLeft() = twinclaws
    /** Middles of the Blood Ichors near your boss. */
    internal fun ichorCenters() = ichors.map { it.center }

    private fun alerts() {
        val b = boss
        if (b == null) { steakWarned = false; twinclawsWarned = false; return }
        if (steakAlert.value && canSteak(b)) {
            title("§c§lSTEAK!", "")
            if (!steakWarned) { steakWarned = true; ping(1.5f) }
        }
        val t = twinclaws
        if (twinclawsAlert.value && t != null) {
            // An Impel title is up: keep it readable, put the countdown in the action bar instead.
            if (System.currentTimeMillis() - impelTitleAt < 2_000) ActionBarHud.notify(Component.literal("§6§lTWINCLAWS §e%.1fs".format(t)))
            else title("§6§lTWINCLAWS", "§e%.1fs".format(t))
            if (!twinclawsWarned) { twinclawsWarned = true; ping(0.8f) }
        } else twinclawsWarned = false
    }

    /** When Hypixel last sent an Impel title / subtitle. */
    private var impelTitleAt = 0L

    private fun title(text: String, sub: String) {
        ced.cedclient.features.impl.misc.ClientTitles.setTimes(0, 10, 2)
        ced.cedclient.features.impl.misc.ClientTitles.setTitle(Component.literal(text))
        ced.cedclient.features.impl.misc.ClientTitles.setSubtitle(Component.literal(sub))
    }

    private fun alert(text: String, sub: String, pitch: Float) {
        ced.cedclient.features.impl.misc.ClientTitles.setTimes(2, 30, 8)
        ced.cedclient.features.impl.misc.ClientTitles.setTitle(Component.literal(text))
        ced.cedclient.features.impl.misc.ClientTitles.setSubtitle(Component.literal(sub))
        ping(pitch)
    }

    /** Overworld bosses: Voidgloom beacons, Inferno attunement / fire pits / pillar. */
    private fun overworldAlerts() {
        val b = SlayerBosses
        if (beaconAlert.value && b.beacons.size > beaconCount) alert("§4§lBEACON", "", 0.6f)
        beaconCount = b.beacons.size
        val att = b.attunement
        if (attunementAlert.value && att != null && att != lastAttunement) {
            val color = mapOf("ASHEN" to "§8", "SPIRIT" to "§f", "CRYSTAL" to "§b", "AURIC" to "§e")[att] ?: "§f"
            alert("$color§l$att", "", 1.6f)
        }
        lastAttunement = att
        if (firePits.value && b.firePitsNow()) alert("§c§lFIRE PITS", "", 0.8f)
        val p = b.pillar
        if (firePillar.value && p != null) title("§6§lFIRE PILLAR", "§e%.1fs".format(p))
    }

    private val viewProj = org.joml.Matrix4f()
    private val clip = org.joml.Vector4f()
    /** Screen spot (0..1) just above the boss while its laser phase runs, from the world pass; null otherwise. */
    @Volatile private var laserSpot: FloatArray? = null

    /** World pass: projects the point above the boss so the HUD pass can write the laser countdown there. */
    private fun captureLaser(camera: CameraRenderState) {
        val body = SlayerBosses.body
        if (inRift() || !bossUp || !laserTimer.value || SlayerBosses.laser == null || body == null) { laserSpot = null; return }
        viewProj.set(camera.projectionMatrix).mul(camera.viewRotationMatrix)
        val p = body.getPosition(mc.deltaTracker.getGameTimeDeltaPartialTick(false))
        val cam = camera.pos
        clip.set((p.x - cam.x).toFloat(), (p.y + body.bbHeight + 0.4 - cam.y).toFloat(), (p.z - cam.z).toFloat(), 1f)
        viewProj.transform(clip)
        laserSpot = if (clip.w <= 0.05f) null else floatArrayOf(clip.x / clip.w * 0.5f + 0.5f, 0.5f - clip.y / clip.w * 0.5f)
    }

    /** HUD pass: a small countdown above the boss during the laser phase. */
    private fun drawLaser(g: GuiGraphicsExtractor) {
        val spot = laserSpot ?: return
        val left = SlayerBosses.laser ?: return
        val text = "%.1fs".format(left)
        val size = 9f
        HudCard.draw(g, 0, 0) {
            HudCard.text(text, spot[0] * g.guiWidth() - HudCard.width(text, size) / 2f, spot[1] * g.guiHeight() - size, size, 0xFF55FFFF.toInt())
        }
    }

    private fun ping(pitch: Float) = mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), pitch, 1f))

    // ---------------------------------------------------------------- blood effigies

    private val EFFIGIES = listOf(BlockPos(150, 73, 95), BlockPos(193, 87, 119), BlockPos(235, 104, 147),
        BlockPos(293, 90, 134), BlockPos(262, 93, 94), BlockPos(240, 123, 118))
    private const val EFFIGY_RESPAWN_MS = 20 * 60_000L
    /** Per effigy: null = standing (or not known yet), else when it respawns. */
    private val effigyRespawn = arrayOfNulls<Long>(6)
    private var effigyKnown = false

    /** The "Effigies: ⧯⧯⧯⧯⧯⧯" sidebar line: grey hearts are standing, red ones broken. */
    private fun readEffigies() {
        val line = Sidebar.components.firstOrNull { Sidebar.clean(it.string).startsWith("Effigies:") } ?: run { effigyKnown = false; return }
        val broken = ArrayList<Boolean>()
        // Hypixel colours the hearts with legacy § codes inside the text as well as with styles: track both.
        line.visit({ style, text ->
            var red = style.color?.value.let { it == 0xFF5555 || it == 0xAA0000 }
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '§' && i + 1 < text.length) { red = text[i + 1].lowercaseChar() in "c4"; i += 2; continue }
                if (c == '⧯') broken += red
                i++
            }
            Optional.empty<Unit>()
        }, net.minecraft.network.chat.Style.EMPTY)
        if (broken.size != 6) return
        effigyKnown = true
        val now = System.currentTimeMillis()
        broken.forEachIndexed { i, b ->
            effigyRespawn[i] = when {
                !b -> null
                effigyRespawn[i] == null -> now + EFFIGY_RESPAWN_MS
                else -> effigyRespawn[i]
            }
        }
    }

    // ---------------------------------------------------------------- tick + render

    private var ticks = 0

    private fun tick() {
        if (!isEnabled || mc.player == null) return
        if (++ticks % 5 == 0) {
            readQuest()
            if (inRift()) readEffigies()
        }
        if (inRift()) { trackBoss(); alerts() } else {
            boss = null; ichors = emptyList(); springBoxes = emptyList()
            if (bossUp) { SlayerBosses.tick(System.currentTimeMillis() - lockAt < 3_000); overworldAlerts() }
        }
        minibosses = if (minibossBox.value && ticks % 5 == 0) findMinibosses() else if (minibossBox.value) minibosses.filterNot { it.isRemoved } else emptyList()
    }

    /** Slayer mini-boss names as their name tags show them. */
    private val MINIBOSSES = listOf("Revenant Sycophant", "Revenant Champion", "Deformed Revenant", "Atoned Champion", "Atoned Revenant",
        "Tarantula Vermin", "Tarantula Beast", "Mutant Tarantula", "Pack Enforcer", "Sven Follower", "Sven Alpha",
        "Voidling Devotee", "Voidling Radical", "Voidcrazed Maniac", "Flare Demon", "Kindleheart Demon", "Burningsoul Demon")
    private var minibosses: List<net.minecraft.world.entity.Entity> = emptyList()

    /** The mob under each mini-boss name tag within 40 blocks. */
    private fun findMinibosses(): List<net.minecraft.world.entity.Entity> {
        val player = mc.player ?: return emptyList()
        val all = mc.level?.entitiesForRendering()?.filter { it.distanceToSqr(player) < 40.0 * 40.0 } ?: return emptyList()
        val tags = all.filter { e -> e is ArmorStand && e.hasCustomName() && e.customName!!.string.let { n -> MINIBOSSES.any { n.contains(it) } } }
        return tags.mapNotNull { tag ->
            all.filter { it is net.minecraft.world.entity.LivingEntity && it !is ArmorStand && it !== player && it.distanceToSqr(tag) < 9.0 }
                .minByOrNull { it.distanceToSqr(tag) }
        }
    }

    /** Tile a walker (Slayer Helper's Mania Walk / Ichor Twinclaws) is heading to or standing on; drawn yellow. Null = none. */
    var walkTarget: BlockPos? = null
    /** Tiles that walker still walks over to [walkTarget], in order; drawn as a smooth line from your feet. */
    var walkPath: List<BlockPos> = emptyList()
    private val WALK_TARGET_COLOR = Color(255, 255, 85)

    /** Line from you over [walkPath] tile tops, corners rounded off (Chaikin, 3 passes). */
    private fun drawWalkPath(context: LevelRenderContext, camera: CameraRenderState) {
        val player = mc.player ?: return
        val path = walkPath.takeIf { it.isNotEmpty() } ?: return
        val feet = player.getPosition(mc.deltaTracker.getGameTimeDeltaPartialTick(false))
        var pts = listOf(feet.add(0.0, 0.1, 0.0)) + path.map { net.minecraft.world.phys.Vec3(it.x + 0.5, it.y + 1.1, it.z + 0.5) }
        repeat(3) {
            if (pts.size < 3) return@repeat
            val out = ArrayList<net.minecraft.world.phys.Vec3>(pts.size * 2)
            out += pts.first()
            for (i in 0 until pts.size - 1) {
                val p = pts[i]; val q = pts[i + 1]
                out += p.lerp(q, 0.25); out += p.lerp(q, 0.75)
            }
            out += pts.last()
            pts = out
        }
        val cam = camera.pos
        val c = WALK_TARGET_COLOR
        val points = pts.map { it.subtract(cam.x, cam.y, cam.z) }
        context.geometry(CustomRenderType.LINES_ESP) { pose, buffer ->
            LineBox.polyline(pose, buffer, points, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
        }
    }

    private fun renderWorld(context: LevelRenderContext) {
        val camera = context.levelState().cameraRenderState
        if (!isEnabled) return
        captureLaser(camera)
        val boxes = ArrayList<Pair<AABB, Color>>()
        val fills = ArrayList<Pair<AABB, Color>>()
        // Beacons and skulls are drawn through walls (you need to find them fast).
        val xrayBoxes = ArrayList<Pair<AABB, Color>>()
        val xrayFills = ArrayList<Pair<AABB, Color>>()
        if (minibossBox.value) minibosses.forEach { boxes += smoothBox(it).inflate(0.1) to minibossColor.value }
        if (!inRift()) {
            if (bossUp) {
                SlayerBosses.body?.takeIf { bossBox.value }?.let { boxes += smoothBox(it).inflate(0.1) to bossColor.value }
                if (beaconAlert.value) SlayerBosses.beacons.forEach { styled(it, beaconColor.value, beaconStyle.value, xrayBoxes, xrayFills) }
                if (nukekubi.value) SlayerBosses.skulls.forEach { styled(it, nukekubiColor.value, nukekubiStyle.value, xrayBoxes, xrayFills) }
            }
            drawBoxes(context, camera, boxes, fills)
            drawBoxes(context, camera, xrayBoxes, xrayFills, throughWalls = true)
            return
        }
        boss?.takeIf { bossBox.value }?.let { boxes += smoothBox(it).inflate(0.1) to if (canSteak(it)) steakColor.value else bossColor.value }
        // Waypoint-style boxes (ichors, stand spots, springs, effigies) are filled + outlined; boss boxes stay outlines.
        if (ichor.value) ichors.forEach { styled(it, ichorColor.value, "Filled + Outline", boxes, fills) }
        // Twinclaws fires one claw at you and one straight opposite: stand on the ichor, or mirrored across the boss
        // (2+ blocks out) so the other claw hits it. Outside Mania only (Slayer Helper walks you there in Mania).
        boss?.takeIf { ichorSpots.value && it.vehicle == null }?.let { b ->
            for (c in ichors.map { it.center }) {
                val ix = c.x - b.x; val iz = c.z - b.z
                val len = kotlin.math.sqrt(ix * ix + iz * iz).takeIf { it > 0.1 } ?: continue
                val back = maxOf(2.0, len) / len
                for ((x, z) in listOf(c.x to c.z, (b.x - ix * back) to (b.z - iz * back)))
                    styled(AABB(BlockPos.containing(x, b.y - 0.5, z)), ichorSpotColor.value, "Filled + Outline", boxes, fills)
            }
        }
        springBoxes.forEach { styled(it, springColor.value, "Filled + Outline", boxes, fills) }
        walkTarget?.let { styled(AABB(it), WALK_TARGET_COLOR, "Filled + Outline", boxes, fills) }
        if (effigies.value && effigyKnown) EFFIGIES.forEachIndexed { i, pos ->
            if (effigyRespawn[i] == null) AABB(pos).expandTowards(0.0, 1.0, 0.0).let {
                if (effigyXray.value) styled(it, effigyColor.value, "Filled + Outline", xrayBoxes, xrayFills) else styled(it, effigyColor.value, "Filled + Outline", boxes, fills)
            }
        }
        drawBoxes(context, camera, boxes, fills)
        drawBoxes(context, camera, xrayBoxes, xrayFills, throughWalls = true)
        drawWalkPath(context, camera)
    }

    /** [e]'s box where it is drawn this frame (between its last two tick positions), so the box doesn't step every tick. */
    private fun smoothBox(e: net.minecraft.world.entity.Entity): AABB =
        e.boundingBox.move(e.getPosition(mc.deltaTracker.getGameTimeDeltaPartialTick(false)).subtract(e.position()))

    private val BOX_STYLES get() = listOf("Outline", "Filled", "Filled + Outline") // getter: used by settings declared above it

    /** Adds [box] as an outline and/or a fill, per a Box Style setting. */
    private fun styled(box: AABB, color: Color, style: String, outlines: MutableList<Pair<AABB, Color>>, fills: MutableList<Pair<AABB, Color>>) {
        if (style != "Filled") outlines += box to color
        if (style != "Outline") fills += box to color
    }

    private fun drawBoxes(context: LevelRenderContext, camera: CameraRenderState,
                          boxes: List<Pair<AABB, Color>>, fills: List<Pair<AABB, Color>> = emptyList(), throughWalls: Boolean = false) {
        if (boxes.isEmpty() && fills.isEmpty()) return
        val cam = camera.pos
        if (fills.isNotEmpty()) {
            context.geometry(if (throughWalls) CustomRenderType.FILLED_ESP else CustomRenderType.FILLED) { pose, fillBuffer ->
                // See-through fill (40% of the colour's opacity) so the beacon / skull inside stays visible.
                for ((box, c) in fills) ced.cedclient.render.FilledBox.draw(pose, fillBuffer, box.move(-cam.x, -cam.y, -cam.z), c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat * 0.4f)
            }
        }
        context.geometry(if (throughWalls) CustomRenderType.LINES_ESP else CustomRenderType.LINES_DEPTH) { pose, buffer ->
            for ((box, c) in boxes) LineBox.draw(pose, buffer, box.move(-cam.x, -cam.y, -cam.z), c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
        }
    }

    // ---------------------------------------------------------------- HUD

    private fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "%.1fs".format(ms / 1000.0)
    }

    private fun rows(): List<Triple<String, String, Int>> {
        val now = System.currentTimeMillis()
        val out = ArrayList<Triple<String, String, Int>>()
        val name = slayer
        out += Triple(name ?: lastSlayer ?: "Slayer", if (name == null) "no quest" else "", 0xFFFFAA00.toInt())
        if (name != null && questStart > 0) out += Triple("Quest", time(now - questStart), -1)
        if (bossUp) out += Triple("Boss", time(now - bossSpawn), -1)
        boss?.let { b ->
            val max = maxHp(b)
            bossOwner?.let { out += Triple("Owner", it, 0xFF55FFFF.toInt()) }
            val tier = tier(max).takeIf { it > 0 }?.let { " (T$it)" } ?: ""
            out += Triple("HP$tier", "%,d / %,d".format(b.health.toInt(), max.toInt()), if (canSteak(b)) 0xFFFF5555.toInt() else -1)
            if (!canSteak(b)) out += Triple("Steak at", "%,d".format((max * STEAK_FRACTION).toInt()), 0xFFAAAAAA.toInt())
            b.vehicle?.let { v -> out += Triple("Mania circles", "%.1fs".format(((MANIA_TICKS - v.tickCount) / 20f).coerceAtLeast(0f)), 0xFFFF55FF.toInt()) }
            twinclaws?.let { out += Triple("Twinclaws", "%.1fs".format(it), 0xFFFFAA00.toInt()) }
        }
        if (bossUp && !inRift() && SlayerBosses.maxHp > 0) {
            val b = SlayerBosses
            out += Triple("HP", b.format(b.hp) + " / " + b.format(b.maxHp), -1)
            b.owner?.let { out += Triple("Owner", it, 0xFF55FFFF.toInt()) }
            b.attunement?.let { out += Triple("Attunement", it, 0xFFFFAA00.toInt()) }
            b.hits?.let { out += Triple("Hits left", it.toString(), 0xFFFF5555.toInt()) }
            b.pillar?.let { out += Triple("Fire pillar", "%.1fs".format(it), 0xFFFFAA00.toInt()) }
            b.laser?.let { out += Triple("Laser phase", "%.1fs".format(it), 0xFF55FFFF.toInt()) }
            if (b.beacons.isNotEmpty()) out += Triple("Beacons", b.beacons.size.toString(), 0xFFFF5555.toInt())
        }
        lastKill?.let { out += Triple("Last kill", time(it), -1) }
        sessions[lastSlayer ?: ""]?.takeIf { it.kills > 0 }?.let { s ->
            val hours = (now - s.started) / 3_600_000.0
            out += Triple("Kills", "${s.kills}  (%.1f/h)".format(if (hours > 0) s.kills / hours else 0.0), -1)
            out += Triple("Avg / best", time(s.killTimeTotal / s.kills) + " / " + time(s.best), -1)
        }
        rngXp?.let { out += Triple("RNG meter", "$it XP", 0xFFFF55FF.toInt()) }
        if (inRift() && effigies.value && effigyKnown) {
            val up = effigyRespawn.count { it == null }
            val next = effigyRespawn.filterNotNull().minOrNull()
            out += Triple("Effigies", "$up/6 up" + (next?.let { "  next " + time((it - now).coerceAtLeast(0)) } ?: ""), 0xFFFF5555.toInt())
        }
        return out
    }

    fun render(g: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (isEnabled && !mc.gui.hud.isHidden) drawLaser(g)
        if (!isEnabled || !hud.value || mc.gui.hud.isHidden) return
        if (!inSlayerArea()) return
        if (lastSlayer == null && !(inRift() && effigyKnown)) return
        draw(g, rows())
    }

    /** Sidebar areas where slayer bosses spawn (and the effigies stand): the HUD only shows there. */
    private val SLAYER_AREAS = listOf("Graveyard", "Crypts", "Spider's Den", "Howling Cave", "Void Sepulture",
        "Zealot Bruiser Hideout", "Smoldering Tomb", "Stillgore Ch")
    private fun inSlayerArea() = Sidebar.area?.let { area -> SLAYER_AREAS.any { area.contains(it, ignoreCase = true) } } == true

    override fun renderInternal(g: GuiGraphicsExtractor) = draw(g, rows().takeIf { it.size > 1 } ?: listOf(
        Triple("Riftstalker Bloodfiend V", "", 0xFFFFAA00.toInt()), Triple("Boss", "0:42", -1), Triple("HP (T5)", "1,204 / 3,000", -1),
        Triple("Steak at", "600", 0xFFAAAAAA.toInt()), Triple("Kills", "12  (31.4/h)", -1)))

    private fun draw(g: GuiGraphicsExtractor, all: List<Triple<String, String, Int>>) {
        val rows = all.filterIndexed { i, r -> i == 0 || shown(r.first) }
        val s = panelScale
        val size = 11f * s
        val lineH = 13f * s
        val pad = 7f * s
        val gap = 10f * s
        val leftW = rows.filter { it.second.isNotEmpty() }.maxOfOrNull { HudCard.width(it.first, size) } ?: 0f
        val width = HudCard.steady("Slayer", rows.maxOf { if (it.second.isEmpty()) HudCard.width(it.first, size) else leftW + gap + HudCard.width(it.second, size) } + pad * 2, s)
        val height = rows.size * lineH + pad * 2 - (lineH - size)
        lastWidth = (width / s).toInt()
        lastHeight = (height / s).toInt()
        HudCard.draw(g, panelX, panelY) {
            HudCard.card(width, height, s)
            rows.forEachIndexed { i, (left, right, color) ->
                val y = pad + i * lineH
                HudCard.text(left, pad, y, size, if (right.isEmpty()) color else 0xFFBBBBBB.toInt())
                if (right.isNotEmpty()) HudCard.text(right, pad + leftW + gap, y, size, color)
            }
        }
    }

    override val label = "Slayer"
    private const val DEFAULT_X = 578
    private const val DEFAULT_Y = 31
    private const val DEFAULT_SCALE = 1.1f
    override var panelX = DEFAULT_X
    override var panelY = DEFAULT_Y
    override var panelScale = DEFAULT_SCALE
    override var lastWidth = 0
        private set
    override var lastHeight = 0
        private set

    private data class HudPosition(val x: Int, val y: Int, val scale: Float)
    private val gson = GsonBuilder().create()
    private val hudFile by lazy { CedPaths.hud("slayer") }

    init {
        runCatching { gson.fromJson(hudFile.readText(), HudPosition::class.java) }.getOrNull()?.let {
            panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
        }
    }

    override fun save() {
        runCatching { hudFile.parentFile?.mkdirs(); hudFile.writeText(gson.toJson(HudPosition(panelX, panelY, panelScale))) }
    }

    override fun resetPosition() { panelX = DEFAULT_X; panelY = DEFAULT_Y; panelScale = DEFAULT_SCALE }
    override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }
}
