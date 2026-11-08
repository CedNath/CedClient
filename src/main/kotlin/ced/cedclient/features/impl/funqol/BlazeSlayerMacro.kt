//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.TickEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.config.CedPaths
import ced.cedclient.features.impl.render.HudCard
import ced.cedclient.features.impl.render.HudElement
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.KeybindSetting
import ced.cedclient.utils.Keys
import com.google.gson.Gson
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.GuiGraphicsExtractor
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.mixin.accessor.MinecraftAccessor
import ced.cedclient.state.Sidebar
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.Debug
import ced.cedclient.utils.HumanLook
import ced.cedclient.utils.cleanName
import ced.cedclient.utils.loreLines
import ced.cedclient.utils.skyblockId
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Blaze
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Blaze slayer macro (cheat build only): the whole Inferno Demonlord loop in the Smoldering Tomb.
 *  WARP    /warp smold when you are not in the Smoldering Tomb.
 *  CALL    no "Slayer Quest" on the sidebar: /call maddox, then Slayer > Inferno Demonlord > tier > Confirm.
 *  GRIND   walks to the spot and kills Blazes for the quest XP until the sidebar says "Slay the boss!".
 *  FIGHT   boss + demons found by the "SLAYER BOSS" name tag stacks with your name: right dagger and attunement for the
 *          shield, skips the IMMUNE demon, hits fire pillars ("4s 8 hits"), steps off the orange terracotta DDR marks.
 * Stops (and lets go of every key) below the HP limit, when you die, or when you switch it off.
 */
object BlazeSlayerMacro : Module(
    "Blaze Slayer Macro",
    Category.Cheats,
    "Full Inferno Demonlord loop: warp, call Maddox, grind Blazes, fight the boss (daggers, pillars, demons, fire), repeat.",
    defaultEnabled = false
) {
    private val toggleKey = KeybindSetting("Toggle Key", Keys.UNKNOWN, "Switches the macro on / off from anywhere (not while a screen is open).")
    private val tier = DropdownSetting("Tier", listOf("I", "II", "III", "IV"), "III", "Which Inferno Demonlord tier to start.")
    private val grindSlot = NumberSetting("Grind Slot", 1.0, 1.0, 9.0, 1.0, "Hotbar slot of the weapon used on the Blazes before the boss.")
    private val spotX = NumberSetting("Spot X", -291.0, -30000.0, 30000.0, 1.0, "Where it stands while grinding.")
    private val roamRadius = NumberSetting("Box Size", 20.0, 4.0, 40.0, 1.0, "Side of the flat square around the spot it may walk around in freely (straight walking, no pathfinding). Outside it walks back in.")
    private val spotY = NumberSetting("Spot Y", 97.0, -64.0, 320.0, 1.0, "")
    private val spotZ = NumberSetting("Spot Z", -1000.0, -30000.0, 30000.0, 1.0, "")
    private val autoSlayer = BooleanSetting("Auto Slayer", true, "Hypixel's Auto Slayer starts the next quest itself after each boss, so Maddox is not called then. Off: it calls Maddox after every boss.")
    private val stopLow = BooleanSetting("Stop On Low HP", false, "Switches the macro off when your health falls under the limit below (off: it keeps going, and dying just restarts the boss).")
    private val stopAt = NumberSetting("Stop Below HP (%)", 25.0, 5.0, 90.0, 5.0, "Health limit for Stop On Low HP.").withDependency { stopLow.value }

    private val turnSmooth = NumberSetting("Aim Smoothness (ms)", 60.0, 20.0, 400.0, 10.0, "Milliseconds to settle on the target; lower aims faster.")
    private val turnSpeed = NumberSetting("Max Turn Speed (deg/s)", 1800.0, 200.0, 4000.0, 100.0, "Fastest the camera may turn.")
    private val turnJitter = NumberSetting("Aim Jitter (deg)", 0.3, 0.0, 2.0, 0.1, "Small wobble added to the aim.")

    private val useHook = BooleanSetting("Use Grappleshot", true, "Grind: hooks the nearest Blaze with the Moody Grappleshot (pulls it in, stops it flying), then hits it. Needs the item in your hotbar.")
    private val hookRange = NumberSetting("Hook Range", 20.0, 5.0, 40.0, 1.0, "Longest Blaze distance it throws the hook at.").withDependency { useHook.value }
    private val useRagnarock = BooleanSetting("Use Ragnarock", true, "Casts the Ragnarock Axe once, when the two demons first spawn. Needs it in your hotbar.")
    private val useImplosion = BooleanSetting("Use Wither Sword", true, "When the demons land: jumps and right clicks a Hyperion / Astraea / Scylla / Valkyrie from mid-air, looking down (heals you).")
    private val implosionDelay = NumberSetting("Implosion Delay (ms)", 3200.0, 0.0, 6000.0, 100.0, "How long after the Ragnarock cast the Wither sword waits, so its channel is not cut short.").withDependency { useImplosion.value }
    private val retreatDist =NumberSetting("Back Off Distance", 4.5, 2.0, 8.0, 0.5, "How far it walks back while the demons are still in the air.")
    private val useWand = BooleanSetting("Use Wand of Atonement", true, "Right clicks the Wand of Atonement (hotbar) when your health is low.")
    private val wandBelow = NumberSetting("Wand Below HP (%)", 55.0, 10.0, 90.0, 5.0, "Health share under which the wand is used (its heal does not stack, so at most every 6.5 s).").withDependency { useWand.value }
    private val useFlare = BooleanSetting("Place SOS Flare", true, "When the quest is a kill or two from spawning the boss: walks to the most open spot of the box and places the SOS Flare (hotbar).")
    private val keepDist = NumberSetting("Back Off Under (blocks)", 1.0, 0.0, 4.0, 0.1, "In the boss fight it only steps back when closer than this to the boss or a demon (a bigger number made it walk back and forth while hitting). 0: never.")
    private val demonBackMax = NumberSetting("Demon Back Off Max", 6.0, 2.0, 15.0, 1.0, "Quazii / Typhoeus: it walks up to the demon, then inches backwards crouching while it hits; after this many blocks it walks up to it again.")
    private val crouchFight =BooleanSetting("Crouch In Fight", true, "Holds shift while it stands and hits the boss or a demon (it stops walking about; it gets up to move, dodge fire or cast).")
    private val hookMiniOnly = BooleanSetting("Hook Only Big Mobs", false, "Off: the Grappleshot is thrown at any Blaze, your minibosses and the Millennia-Aged Blazes (12M HP) first. On: only at those big ones, the rest must come within reach by themselves (ordinary Blazes hover out of melee range, so they are mostly left alone).").withDependency { useHook.value }
    private val hookDelay = NumberSetting("Hook Delay (ms)", 1800.0, 500.0, 6000.0, 100.0, "Wait between two hooks: the pulled Blaze needs about this long to arrive before it is hit.").withDependency { useHook.value }

    private val strafeFight = BooleanSetting("Strafe In Fight", true, "While it stands and hits the boss or a demon within reach it sprints and crouches and strafes sideways / back at random around the spot, the way it was recorded from a real kill, instead of standing still.")
    private val moveGrind = BooleanSetting("Move In Grind", true, "While it hits a Blaze within reach on the grind it keeps moving the way a real grind was recorded: sprinting, a new direction every 0.3-0.6 s (W / A / S / D and the diagonals), now and then a jump.")
    private val safeFight = BooleanSetting("Safespot Fight", false, "Grinds the same, but once the boss spawns it walks to the safespot below and stays there until the boss is dead: no backing off, no fire dodging, no chasing.")
    private val safeX = NumberSetting("Safespot X", -291.0, -30000.0, 30000.0, 1.0, "Block X of the safespot.").withDependency { safeFight.value }
    private val safeY = NumberSetting("Safespot Y", 78.0, -64.0, 320.0, 1.0, "Block Y of the safespot.").withDependency { safeFight.value }
    private val safeZ = NumberSetting("Safespot Z", -1010.0, -30000.0, 30000.0, 1.0, "Block Z of the safespot.").withDependency { safeFight.value }

    init { addSettings(toggleKey, tier, grindSlot, useHook, hookRange, hookMiniOnly, hookDelay, useRagnarock, useImplosion, implosionDelay, retreatDist, keepDist, demonBackMax, crouchFight, strafeFight, moveGrind, useWand, wandBelow, useFlare, autoSlayer, spotX, spotY, spotZ, roamRadius, safeFight, safeX, safeY, safeZ, stopLow, stopAt, turnSmooth, turnSpeed, turnJitter)
        listOf(turnSmooth, turnSpeed, turnJitter).forEach { it.advanced = true } }

    private val mc get() = Minecraft.getInstance()

    private enum class State { WARP, CALL, GRIND, FIGHT }
    private var state = State.WARP
    private var lastCommand = 0L
    private var noQuestSince = 0L
    private var callTries = 0
    private var screenSince = 0L
    private var lastScreenClick = 0L

    private fun setState(s: State, why: String) {
        if (s == state) return
        Debug.line("BlazeMacro", "state $state -> $s ($why)")
        state = s
    }

    private var questAssumedUntil = 0L
    private var probeFresh = true
    private var probeStart = 0L
    private var probeSlain0 = 0
    private var questFailed = false
    private var enabledAt = 0L

    /** A real keypress on a movement key (raw keyboard state, so the keys the macro itself holds do not count). */
    private fun manualMove(): Boolean {
        val o = mc.options
        return listOf(o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump).any { k ->
            val key = com.mojang.blaze3d.platform.InputConstants.getKey(k.saveString())
            key.type == com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD && Keys.isDown(key.value)
        }
    }
    private var ragnaCasted = false
    private var lastLevel: Any? = null
    private var levelSince = 0L

    // ---- toggle key

    private var keyWasDown = false

    init {
        ClientTickEvents.END_CLIENT_TICK.register {
            val key = toggleKey.value
            val down = key > 0 && mc.gui.screen() == null && Keys.isDown(key)
            if (down && !keyWasDown) toggle()
            keyWasDown = down
        }
    }

    // ---- status HUD ("what it is doing", kills)

    internal var status = "Idle"
    internal var slain = 0
    internal var bosses = 0
    private val counted = HashSet<Int>()

    /** Blazes seen dying close to you (once each): the HUD's "slain". */
    private fun countKills(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel) {
        for (e in level.entitiesForRendering()) {
            if (e is Blaze && e.isDeadOrDying && e.distanceToSqr(player) < 144.0 && counted.add(e.id)) slain++
        }
        if (counted.size > 4000) counted.clear()
    }

    fun renderHud(g: GuiGraphicsExtractor) {
        if (isEnabled && !mc.gui.hud.isHidden) StatusHud.draw(g)
    }

    object StatusHud : HudElement {
        override val label: String get() = "Blaze Slayer Macro"
        override val hudActive: Boolean get() = BlazeSlayerMacro.isEnabled
        override var panelX = 4
        override var panelY = 150
        override var panelScale = 1f
        override var lastWidth = 0
            private set
        override var lastHeight = 0
            private set

        /** Full card: title + status, the boss count as the headline, slain mobs as a row. */
        fun draw(g: GuiGraphicsExtractor) {
            val s = panelScale
            val size = 9f * s
            val padX = 12f * s
            val padY = 10f * s
            val title = "Blaze Slayer"
            val status = BlazeSlayerMacro.status
            val bosses = BlazeSlayerMacro.bosses.toString()
            val rows = listOf(HudCard.Row("Mobs slain", BlazeSlayerMacro.slain.toString()))
            val inner = maxOf(
                HudCard.width(title, size * 1.15f), HudCard.width(status, size * 0.9f),
                HudCard.width(bosses, size * 2.1f) + HudCard.width("bosses", size) + size, HudCard.rowsWidth(rows, size),
            )
            val w = HudCard.steady(label, inner + padX * 2, s)
            val headH = HudCard.headerHeight(size, true)
            val lineY = padY + headH + HudCard.headlineHeight(size) + 4f * s
            val h = lineY + 6f * s + HudCard.rowsHeight(rows.size, size) + padY
            lastWidth = (w / s).toInt()
            lastHeight = (h / s).toInt()
            HudCard.draw(g, panelX, panelY) {
                HudCard.card(w, h, s)
                HudCard.header(title, status, padX, padY, size)
                HudCard.headline(bosses, "bosses", null, padX, padY + headH, w - padX * 2, size)
                HudCard.divider(padX, lineY, w - padX * 2, s)
                HudCard.rows(rows, padX, lineY + 6f * s, w - padX * 2, size)
            }
        }

        override fun renderInternal(g: GuiGraphicsExtractor) = draw(g)

        private data class Pos(val x: Int, val y: Int, val scale: Float)
        private val gson = Gson()
        private val file by lazy { CedPaths.hud("blaze_macro") }

        init {
            runCatching { gson.fromJson(file.readText(), Pos::class.java) }.getOrNull()?.let {
                panelX = it.x; panelY = it.y; panelScale = it.scale.coerceIn(0.4f, 3f)
            }
        }

        override fun save() { runCatching { file.parentFile?.mkdirs(); file.writeText(gson.toJson(Pos(panelX, panelY, panelScale))) } }
        override fun resetPosition() { panelX = 4; panelY = 150; panelScale = 1f }
        override fun adjustScale(delta: Float) { panelScale = (panelScale + delta).coerceIn(0.4f, 3f) }
    }

    private fun ownBossUp(level: net.minecraft.client.multiplayer.ClientLevel) = level.entitiesForRendering().any {
        it is ArmorStand && it.hasCustomName() && clean(it.customName!!.string).let { t -> t.contains("SLAYER BOSS") && t.contains(mc.user.name, ignoreCase = true) }
    }

    init {
        on<ChatMessageEvent> {
            if (!isEnabled) return@on
            val t = it.unformattedText
            val now = System.currentTimeMillis()
            Debug.line("BlazeMacro", "chat: ${t.take(200)}")
            when {
                t.contains("You already have an active Slayer Quest") -> {
                    questAssumedUntil = now + 15 * 60_000L
                    Debug.line("BlazeMacro", "chat: quest already active, not calling Maddox again")
                    mc.execute { mc.player?.closeContainer() }
                }
                t.contains("SLAYER QUEST STARTED") -> { questAssumedUntil = now + 15 * 60_000L; questFailed = false }
                // Boss killed: Auto Slayer starts the next quest by itself, so do not call Maddox.
                t.contains("SLAYER QUEST COMPLETE") -> {
                    probeFresh = false; questFailed = false; ragnaCasted = false; demonPhase = 0; wavesDone = 0; waveSawDemons = false; lastBossMax = 0.0; bosses++
                    status = if (autoSlayer.value) "Boss slain, waiting for Auto Slayer" else "Boss slain"
                    questAssumedUntil = if (autoSlayer.value) now + 15 * 60_000L else 0L
                    Debug.line("BlazeMacro", "quest complete: ${if (autoSlayer.value) "waiting for Auto Slayer" else "will call Maddox"}")
                    exportLog("boss $bosses killed")
                }
                // Died or cancelled: no Auto Slayer then, call Maddox for a new boss.
                t.contains("SLAYER QUEST FAILED") || t.contains("Your Slayer Quest has been cancelled") -> {
                    questAssumedUntil = 0L; probeFresh = false; questFailed = true; noQuestSince = 0L; ragnaCasted = false; demonPhase = 0; wavesDone = 0; waveSawDemons = false; lastBossMax = 0.0
                    Debug.line("BlazeMacro", "quest failed/cancelled: will call Maddox")
                    exportLog("quest failed / died")
                }
            }
        }
        on<TickEvent.End> { tick() }
        LevelRenderEvents.START_MAIN.register { if (isEnabled) lookFrame() }
    }

    override fun onEnable() {
        startRec()
        state = State.WARP; lastCommand = 0L; noQuestSince = 0L; callTries = 0; lookTarget = null; pfFailedGoal = null
        probeFresh = true; probeStart = 0L
        enabledAt = System.currentTimeMillis()
        ragnaCasted = false; demonPhase = 0; wavesDone = 0; waveSawDemons = false; slain = 0; bosses = 0; counted.clear(); status = "Starting"
        Debug.line("BlazeMacro", "enabled")
    }

    override fun onDisable() {
        releaseMovement(); lookTarget = null; status = "Idle"; Debug.line("BlazeMacro", "disabled")
        exportLog("macro switched off")
        Debug.tap = null
    }

    // ---- session log: every BlazeMacro line + snapshots, written to one file after each boss (or death) and when switched off

    private val rec = ArrayList<String>()
    private var recStart = 0L
    private var snapN = 0
    private var lastHp = -1f

    private fun startRec() {
        synchronized(rec) { rec.clear() }
        recStart = System.currentTimeMillis(); snapN = 0; lastHp = -1f
        Debug.tap = { l ->
            if (l.tag == "BlazeMacro") synchronized(rec) { if (rec.size < 80_000) rec += "%8.2f  %s".format((l.time - recStart) / 1000.0, l.msg) }
        }
    }

    private fun exportLog(reason: String) {
        val lines = synchronized(rec) { ArrayList(rec).also { rec.clear() } }
        val from = recStart
        recStart = System.currentTimeMillis()
        if (lines.size < 3) return
        runCatching {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())
            val f = java.io.File(CedPaths.dir, "debug/blaze-macro-$stamp.txt")
            f.parentFile.mkdirs()
            val head = buildString {
                appendLine("Blaze Slayer Macro log: $reason")
                appendLine("exported ${java.util.Date()}, covers ${(recStart - from) / 1000} s, ${lines.size} lines, bosses killed this run: $bosses")
                appendLine("settings: " + getSettings().joinToString("; ") { "${it.name}=${it.value}" })
                appendLine("-".repeat(60))
            }
            f.writeText(head + lines.joinToString("\n") + "\n")
            mc.execute { mc.player?.sendSystemMessage(ChatPrefix.msgFor("Blaze Slayer Macro", "§7debug log saved: §f${f.absolutePath}")) }
        }.onFailure { Debug.line("BlazeMacro", "log export failed: $it") }
    }

    private fun f1(d: Double) = "%.1f".format(d)

    /** What is around you, for the log: mobs, name tags, beams, fire marks, keys. */
    private fun situation(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel): String {
        val o = mc.options
        val keys = listOf(o.keyUp to "W", o.keyLeft to "A", o.keyDown to "S", o.keyRight to "D", o.keyShift to "SHIFT", o.keySprint to "SPRINT", o.keyJump to "JUMP")
            .filter { it.first.isDown }.joinToString("") { it.second + " " }.trim()
        val mobs = level.entitiesForRendering().filter { it is LivingEntity && it !is ArmorStand && it !== player && it.distanceToSqr(player) < 144.0 }
            .sortedBy { it.distanceToSqr(player) }.take(7).joinToString(", ") {
                "${it.type.description.string}#${it.id} d${f1(it.distanceTo(player).toDouble())} dy${f1(it.y - player.y)} hp${(it as LivingEntity).health.toInt()}${if (it.isInvisible) " inv" else ""}${if (it.onGround()) "" else " air"}"
            }
        val tags = level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { it.hasCustomName() && it.distanceToSqr(player) < 100.0 }
            .sortedBy { it.distanceToSqr(player) }.take(8).joinToString(" | ") { "\"${clean(it.customName!!.string).take(48)}\" d${f1(it.distanceTo(player).toDouble())}" }
        val beams = level.entitiesForRendering().filterIsInstance<net.minecraft.world.entity.monster.Guardian>()
            .filter { it.distanceToSqr(player) < 1600.0 }.joinToString(", ") { g -> "g#${g.id} d${f1(g.distanceTo(player).toDouble())} ->${g.activeAttackTarget?.let { it.type.description.string + "#" + it.id } ?: "none"}" }
        val marks = markedBlocks(player, level).count { Math.abs(it.x - player.blockPosition().x) <= 3 && Math.abs(it.z - player.blockPosition().z) <= 3 }
        val under = level.getBlockState(player.blockPosition().below()).block.descriptionId.substringAfterLast('.')
        val feet = level.getBlockState(player.blockPosition()).block.descriptionId.substringAfterLast('.')
        return "keys[$keys] ground=${player.onGround()} under=$under feet=$feet marks3=$marks guardians[${beams.ifEmpty { "-" }}] mobs[${mobs.ifEmpty { "-" }}] tags[${tags.ifEmpty { "-" }}]"
    }

    private fun snapshot(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel) {
        val lt = lookTarget
        Debug.line("BlazeMacro", "SNAP $state \"$status\" pos(${f1(player.x)},${f1(player.y)},${f1(player.z)}) hp${player.health.toInt()}/${player.maxHealth.toInt()} " +
            "yaw${f1(player.yRot.toDouble())} pitch${f1(player.xRot.toDouble())} aim${lt?.let { "(${f1(it.first.toDouble())},${f1(it.second.toDouble())})" } ?: "-"} " +
            "held=${player.mainHandItem.skyblockId}@${player.inventory.selectedSlot + 1} pf=${Pathfinding.active} | demonPhase=$demonPhase waves=$wavesDone retreat=$retreatMode approach=$approachMode " +
            "flee=${maxOf(0, fleeUntil - ticks)} lock=$fightLock bossAir=$bossAirTicks | ${situation(player, level)} | quest ${questLines()}")
    }

    private var lastYaw = 0f
    private var lastPitch = 0f

    /** 10 times a second: where you look, where the macro wants you to look, the gap, and how far the camera moved since the last sample. */
    private fun logRotation(player: net.minecraft.client.player.LocalPlayer) {
        val lt = lookTarget
        val dy = Mth.wrapDegrees(player.yRot - lastYaw); val dp = player.xRot - lastPitch
        val aim = if (lt == null) "aim=none" else "aim(${f1(lt.first.toDouble())},${f1(lt.second.toDouble())}) off(${f1(Mth.wrapDegrees(lt.first - player.yRot).toDouble())},${f1((lt.second - player.xRot).toDouble())})"
        // Where the fight target really is from here, and how far the camera is from facing its centre.
        val tgt = if (state == State.FIGHT) mc.level?.getEntity(fightLock) else null
        val onTarget = tgt?.let {
            val c = it.boundingBox.center
            val tx = c.x - player.x; val tz = c.z - player.z; val ty = c.y - player.eyeY
            val iy = Math.toDegrees(atan2(-tx, tz)).toFloat()
            val ip = (-Math.toDegrees(atan2(ty, hypot(tx, tz)))).toFloat()
            " target ideal(${f1(iy.toDouble())},${f1(ip.toDouble())}) err(${f1(Mth.wrapDegrees(iy - player.yRot).toDouble())},${f1((ip - player.xRot).toDouble())}) d${f1(hypot(tx, tz))}"
        } ?: ""
        val v = player.deltaMovement
        Debug.line("BlazeMacro", "ROT yaw${f1(player.yRot.toDouble())} pitch${f1(player.xRot.toDouble())} head${f1(player.yHeadRot.toDouble())} body${f1(player.yBodyRot.toDouble())} " +
            "$aim moved(${f1(dy.toDouble())},${f1(dp.toDouble())})$onTarget vel(${"%.2f,%.2f,%.2f".format(v.x, v.y, v.z)}) speed${"%.2f".format(hypot(v.x, v.z))} sneak=${player.isShiftKeyDown} sprint=${player.isSprinting}")
        lastYaw = player.yRot; lastPitch = player.xRot
    }

    /** Health drops are logged with the surroundings, to see what is hurting (fire trail, laser, a hit). */
    private fun watchDamage(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel) {
        val hp = player.health
        if (lastHp >= 0f && lastHp - hp >= 1.5f) Debug.line("BlazeMacro", "DAMAGE -${f1((lastHp - hp).toDouble())} hp now ${hp.toInt()}/${player.maxHealth.toInt()} | ${situation(player, level)}")
        lastHp = hp
    }

    private var lastRepeat = ""
    private var lastRepeatAt = 0L

    /** For lines that would otherwise fire every tick: logged when the text changes, else at most every 3 s. */
    private fun debugRepeat(msg: String) {
        val now = System.currentTimeMillis()
        if (msg == lastRepeat && now - lastRepeatAt < 3000) return
        lastRepeat = msg; lastRepeatAt = now
        Debug.line("BlazeMacro", msg)
    }

    /** "why it is (not) doing something" lines, for finding what the waiting is spent on. Deduplicated like [debugRepeat]. */
    private fun why(msg: String) = debugRepeat("why: $msg")

    private var targetAt = 0L
    private var swungTarget = true

    /** A new thing to attack was picked: the clock for "how long until the first swing". */
    private fun newTarget(label: String) {
        targetAt = System.currentTimeMillis(); swungTarget = false
        Debug.line("BlazeMacro", "target picked: $label")
    }

    private fun stop(why: String) {
        mc.player?.sendSystemMessage(ChatPrefix.msgFor("Blaze Slayer Macro", "§cstopped §7($why)"))
        Debug.line("BlazeMacro", "stopped: $why")
        if (isEnabled) toggle()
    }

    // ---- main loop

    private fun tick() {
        if (!isEnabled) return
        val player = mc.player ?: return
        val level = mc.level ?: return
        // Dead: the quest fails ("SLAYER QUEST FAILED!"), then this calls Maddox and starts a new boss.
        // You touched a movement key yourself: hand control back.
        if (System.currentTimeMillis() - enabledAt > 1500 && manualMove()) return stop("you moved yourself")
        // A new world (warp, server change): the first seconds are a bad time to send a command, it gets dropped.
        if (level !== lastLevel) { lastLevel = level; levelSince = System.currentTimeMillis(); lastCommand = 0L }
        if (player.isDeadOrDying) { status = "Dead, waiting to respawn"; releaseMovement(); if (mc.gui.screen() is net.minecraft.client.gui.screens.DeathScreen) player.respawn(); return }
        if (stopLow.value && player.health < player.maxHealth * stopAt.value / 100.0) return stop("health ${player.health.toInt()}/${player.maxHealth.toInt()}")

        val screen = mc.gui.screen()
        if (screen != null) {
            releaseMovement()
            // The sidebar lags behind a Confirm click: don't call Maddox again the moment the menu closes.
            noQuestSince = 0L
            lastCommand = System.currentTimeMillis()
            if (screen is AbstractContainerScreen<*>) menu(screen) else debugRepeat("unexpected screen ${screen.javaClass.simpleName}, waiting")
            return
        }
        screenSince = 0L
        countKills(player, level)
        watchDamage(player, level)
        snapN++
        if (snapN % (if (state == State.FIGHT) 10 else 40) == 0) snapshot(player, level)
        if (snapN % 2 == 0 && (state == State.FIGHT || lookTarget != null)) logRotation(player)

        // /warp smold lands in "The Wasteland", at the mouth of the tomb.
        if (Sidebar.area?.let { it.contains("Smoldering Tomb", ignoreCase = true) || it.contains("Wasteland", ignoreCase = true) } != true) {
            setState(State.WARP, "area=${Sidebar.area}")
            status = "Warping to the tomb"
            lookTarget = null
            probeFresh = true; probeStart = 0L
            releaseMovement()
            val now = System.currentTimeMillis()
            if (now - levelSince > 3000 && now - lastCommand > 6000) { lastCommand = now; player.connection.sendCommand("warp smold"); Debug.line("BlazeMacro", "/warp smold") }
            return
        }

        val lines = Sidebar.lines
        val q = lines.indexOfFirst { it.contains("Slayer Quest") }
        // "You already have an active Slayer Quest!" in chat means one is running even when the sidebar does not show it.
        val assumed = System.currentTimeMillis() < questAssumedUntil
        if (q < 0 && !assumed) {
            // Just enabled / warped: the sidebar only shows the quest after a kill, so grind for a while before believing "no quest".
            if (probeFresh && !questFailed) {
                val t = System.currentTimeMillis()
                if (probeStart == 0L) { probeStart = t; probeSlain0 = slain; Debug.line("BlazeMacro", "no quest line yet: killing 2 blazes (25 s at most) before calling Maddox") }
                // The sidebar only shows the quest after a kill: two kills and still nothing means there is no quest.
                if (slain - probeSlain0 < 2 && t - probeStart < 25000) { setState(State.GRIND, "probing for the quest line"); grind(player, level); return }
                Debug.line("BlazeMacro", "no quest line after ${slain - probeSlain0} kills / ${(t - probeStart) / 1000} s: calling Maddox")
                probeFresh = false
            }
            // No quest: wait a few seconds first, the next one often starts by itself right after "QUEST COMPLETE".
            val now = System.currentTimeMillis()
            if (noQuestSince == 0L) noQuestSince = now + 2500L + (Math.random() * 1500).toLong()
            releaseMovement()
            if (now < noQuestSince) return
            setState(State.CALL, "no Slayer Quest on the sidebar")
            status = "Calling Maddox for a quest"
            if (now - lastCommand > 7000) {
                if (++callTries > 4) return stop("Maddox did not open the menu after 4 calls (Batphone in inventory?)")
                lastCommand = now
                player.connection.sendCommand("call maddox")
                Debug.line("BlazeMacro", "/call maddox (try $callTries)")
            }
            return
        }
        noQuestSince = 0L; callTries = 0; probeFresh = false

        val bossPhase = if (q >= 0) lines.drop(q + 1).take(3).any { it == "Slay the boss!" } else ownBossUp(level)
        if (bossPhase) { setState(State.FIGHT, if (q >= 0) "sidebar: Slay the boss!" else "own boss name tag"); fight(player, level) }
        else if (q >= 0 && lines.drop(q + 1).take(3).any { it == "Boss slain!" }) releaseMovement()
        else { setState(State.GRIND, "quest running"); grind(player, level) }
    }

    // ---- menus (Maddox)

    private fun menu(screen: AbstractContainerScreen<*>) {
        val player = mc.player ?: return
        val now = System.currentTimeMillis()
        status = "Starting a quest with Maddox"
        if (screenSince == 0L) screenSince = now
        if (now - screenSince < 350 + (Math.random() * 250).toLong() || now - lastScreenClick < 500) return
        val title = screen.title.string
        val want: (ItemStack) -> Boolean = when (title) {
            "Slayer" -> { s -> s.cleanName.contains("Inferno Demonlord") } // the item is named "☠ Inferno Demonlord"
            "Inferno Demonlord" -> { s -> s.cleanName == "Inferno Demonlord ${tier.value}" }
            "Confirm" -> { s -> s.cleanName == "Confirm" }
            else -> return
        }
        val slot = screen.menu.slots.firstOrNull { it.container !== player.inventory && it.hasItem() && want(it.item) }
        if (slot == null) { Debug.line("BlazeMacro", "menu \"$title\": wanted item not found"); lastScreenClick = now + 1500; return }
        Debug.line("BlazeMacro", "menu \"$title\": click slot ${slot.index} (${slot.item.cleanName})")
        mc.gameMode?.handleContainerInput(screen.menu.containerId, slot.index, 0, ContainerInput.PICKUP, player)
        lastScreenClick = now
    }

    // ---- grind

    private var noMobSince = 0L
    private var closeBlazeAt = 0L
    private val unreachable = HashMap<Int, Long>()
    private var returningToSpot = false
    private var lastHook = 0L
    private var hookedId = -1
    private var bossAirTicks = 0
    private var ragnaCastAt = 0L
    private var holdGrappleUntil = 0L
    private var noHookSince = 0L
    private var targetId = -1
    private var targetSince = 0L
    private var targetSeenAt = 0L
    private var targetBest = 0.0

    /** The floor under [e]: Blazes hover, so the path goes to the ground below one, not to a spot in the air. */
    private fun groundBelow(e: Entity): Vec3 {
        val level = mc.level ?: return e.position()
        var p = e.blockPosition()
        repeat(30) {
            val below = p.below()
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty) return Vec3.atBottomCenterOf(p)
            p = below
        }
        return e.position()
    }
    private var nextAttackTick = 0
    private var ticks = 0

    /** Where it grinds (box centre): the safespot itself with Safespot Fight on, so the boss spawns where it will stand. */
    private fun spot() =
        if (safeFight.value) Vec3(safeX.value + 0.5, safeY.value, safeZ.value + 0.5)
        else Vec3(spotX.value + 0.5, spotY.value, spotZ.value + 0.5)

    private fun grind(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel) {
        ticks++
        status = "Killing blazes"
        if (inBox(player.position(), -1.0)) backGoal = null // back inside: the next trip back picks a fresh point
        if (autoHeal(player)) return
        val now = System.currentTimeMillis()
        // Not while the Pathfinder walks: it picks its own item (the Aspect of the Void for a teleport) and aims itself; forcing the
        // grind slot every tick kept it from ever clicking the teleport, so it stood there replanning the same route.
        if (Pathfinding.active) lookTarget = null
        else if (now >= holdGrappleUntil) player.inventory.setSelectedSlot(grindSlot.value.toInt() - 1)
        unreachable.values.removeIf { it < now }
        // Other players' slayer bosses (the Blaze body under a "<name>'s SLAYER BOSS" tag that is not yours) are never attacked.
        val me = mc.user.name
        val foreign = level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { it.hasCustomName() && it.distanceToSqr(player) < 90.0 * 90.0 }
            .filter { val t = clean(it.customName!!.string); t.contains("SLAYER BOSS") && !t.contains(me, ignoreCase = true) }
        // Blazes behind a wall or pillar (no line of sight) are not targets at all: walking at them just ran into the wall.
        val blazes = level.entitiesForRendering().filterIsInstance<Blaze>().filter { b ->
            b.isAlive && !b.isInvisible && b.id !in unreachable && b.distanceToSqr(player) < 90.0 * 90.0 &&
                foreign.none { a -> Math.abs(a.x - b.x) < 3.5 && Math.abs(a.z - b.z) < 3.5 && b.y <= a.y + 9.0 && b.y > a.y - 5.0 } &&
                player.hasLineOfSight(b)
        }
        // A miniboss you spawned (Flare / Kindleheart / Burningsoul Demon) is killed before anything else.
        val mini = ownMiniboss(player, level)
        val mobs: List<LivingEntity> = if (mini != null) listOf(mini) else blazes
        if (mini != null) status = "Killing a miniboss"
        val mob = mobs.minByOrNull { it.distanceToSqr(player) }
        debugRepeat("grind: ${mobs.size} blazes (${unreachable.size} skipped), nearest ${mob?.let { "%.0f".format(player.distanceTo(it)) } ?: "-"} blocks, spot %.0f blocks away, pos ${player.blockPosition().toShortString()}".format(player.position().distanceTo(spot())))
        if (mob == null) {
            if (noMobSince == 0L) noMobSince = now
            if (!inBox(player.position(), 3.0) && now - noMobSince > 1500) goTo(backPoint(), 1.0) else releaseMovement()
            return
        }
        noMobSince = 0L

        // A kill or two from the boss: open ground (the boss and the demons drop in around you), and the SOS Flare, once per 3 minutes.
        if (useFlare.value) {
            if (nearSpawn()) {
                val flare = slotOf(player, "SOS_FLARE")
                if (flare == null) why("flare: the quest is close to the boss but there is no SOS Flare in the hotbar")
                else if (now - flareAt <= 175_000) why("flare: one was placed ${(now - flareAt) / 1000} s ago (lasts 3 min)")
                else {
                    status = "Moving to open ground for the flare"
                    val open = openSpot(player, level)
                    if (hypot(player.x - open.x, player.z - open.z) > 1.8 && moveTo(open, 1.5)) { why("flare: walking to the open spot"); return }
                    player.inventory.setSelectedSlot(flare)
                    mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
                    holdGrappleUntil = now + 300
                    flareAt = now
                    Debug.line("BlazeMacro", "SOS Flare placed (quest close to the boss)")
                    return
                }
            } else why("flare: quest not close to the boss yet: ${questLines()}")
        }

        // Pulled (or just close) Blazes first: hit them.
        // While walking back to the spot only ones within reach count: engaging at 4 cancelled the walk (pfStop), then the walk
        // restarted when the Blaze drifted out again, so it turned toward the Blaze and the spot in turns, going nowhere.
        val nearRange = if (returningToSpot) 2.9 else 4.0
        // Sticky: the one it was hitting stays the target while it is still in reach (nearest-each-tick flipped the aim between Blazes).
        val nearList = mobs.filter { player.eyePosition.distanceTo(it.boundingBox.center) < nearRange && player.hasLineOfSight(it) }
        val near = nearList.firstOrNull { it.id == nearLock } ?: nearList.minByOrNull { it.distanceToSqr(player) }
        nearLock = near?.id ?: -1
        if (near != null) { lockId = near.id; noHookSince = 0L; engage(player, near, 2.9, attackEvery = 3, minDist = 1.2, leash = true); return }

        // Close to a wall: back off into the open (inside the box) instead of standing in the corner.
        // Only when nothing is being walked to (or the boss is about to spawn): backing off while a Blaze by the wall is the target made
        // it walk back in on the next tick, back and forth at 20 Hz. Once started, it backs off for 12 ticks whatever the pressure says.
        if (ticks < wallUntil) { status = "Backing off the wall"; walkToward(wallDir.first, wallDir.second, sprint = false); return }
        if (!mobs.any { walkable(it, mini) } || nearSpawn()) {
            wallPush(player, level)?.let { (dx, dz) ->
                if (inBox(player.position().add(dx * 1.5, 0.0, dz * 1.5), 3.0)) { wallDir = dx to dz; wallUntil = ticks + 12; return }
            }
        }

        // Moody Grappleshot: aim at the nearest Blaze in sight, throw the hook, back to the weapon (the next tick does that).
        // Big ones (your miniboss, Millennia-Aged) are always hooked. Ordinary Blazes standing on the ground are walked up to and hit;
        // only the ones out of reach (hovering) are hooked.
        val isBig = { e: LivingEntity -> e === mini || e.maxHealth >= 5_000_000f }
        // Order of kills: nearest first, but one already hurt counts 3 blocks nearer (finish what was started), and the one it is on is
        // kept unless another is clearly better (4+ blocks), so it never flips between two at the same distance.
        // Recorded by hand (data2, 50 kills): 64% nearest, 94% one of the two nearest, all within ~3 blocks: it fights what is next to it
        // and lets the rest come (or hooks them), it does not run 20 blocks after one. So only blazes within 12 blocks are walked to,
        // unless none has been that close for 4 s.
        if (mobs.any { player.distanceTo(it) < 12.0 }) closeBlazeAt = now
        val chaseMax = if (now - closeBlazeAt > 4000) 90.0 else 12.0
        val score = { e: LivingEntity -> player.distanceTo(e) - (if (e.health < e.maxHealth * 0.97f) 1.5 else 0.0) - (if (e.id == lockId) 3.0 else 0.0) }
        val ground = mobs.filter { !isBig(it) && onFoot(it) && player.distanceTo(it) < chaseMax }.minByOrNull(score)
        val grapple = if (useHook.value) (0 until 9).firstOrNull { player.inventory.getItem(it).skyblockId == "MOODY_GRAPPLESHOT" } else null
        if (grapple != null) {
            // The boss spawns where you stand: be on the spot before hooking, and go back to it after every kill.
            // Once it starts walking back it finishes (to within 1.5 blocks): stopping at the edge and restarting made it stutter.
            val spotDist = player.position().distanceTo(spot())
            if (!inBox(player.position(), 3.0)) returningToSpot = true
            if (returningToSpot) {
                status = "Walking back to the spot"
                if (inBox(player.position(), -1.0)) { returningToSpot = false; pfStop() }
                else if (goTo(backPoint(), 1.0)) return
                else returningToSpot = false
            }
            // Just hooked: that Blaze flies off further first and then comes to you. Stay put, keep facing it, pick no other one.
            if (now - lastHook < hookDelay.value) {
                status = "Waiting for the hooked blaze"
                why("waiting for the hooked blaze ${(hookDelay.value - (now - lastHook)).toLong()} ms more")
                // The anvil flies ~18 blocks out and back in about 1.6 s and picks up Blazes on the way (recorded: 2-4 per throw). Moving
                // away from the throw while it is out makes the way back longer and sweeps a new stretch, so it keeps moving, sprinting,
                // inside the box, like the recorded grind.
                val away = Math.toRadians(hookYaw.toDouble())
                if (now - lastHook < 1700 && inBox(player.position().add(Math.sin(away) * 2.0, 0.0, -Math.cos(away) * 2.0), 1.0)) {
                    releaseMovement()
                    walkToward(Math.sin(away), -Math.cos(away), sprint = true)
                    why("hook out: moving away from the throw while the anvil comes back")
                } else releaseMovement()
                mobs.firstOrNull { it.id == hookedId }?.let { lookAt(player, it.boundingBox.center) }
                return
            }
            // Big ones only (default): your slayer minibosses and the Millennia-Aged Blazes (12M max HP, a normal Smoldering Blaze has 2M);
            // ordinary Blazes are walked up to and hit.
            val hookPool: List<LivingEntity> = if (hookMiniOnly.value) listOfNotNull(mini) + blazes.filter { it.maxHealth >= 5_000_000f } else mobs
            val inRange = hookPool.filter { player.distanceTo(it) <= hookRange.value && player.hasLineOfSight(it) }
            val hookable = inRange.filter(isBig).minByOrNull { it.distanceToSqr(player) }
                ?: if (ground != null) null else bestHookLine(player, inRange)
            if (hookable != null) {
                noHookSince = 0L
                releaseMovement()
                status = "Hooking blazes"
                lookAt(player, hookable.boundingBox.center)
                val lt = lookTarget
                val aimed = lt != null && Math.abs(Mth.wrapDegrees(lt.first - player.yRot)) < 5f && Math.abs(lt.second - player.xRot) < 6f
                if (aimed && now - lastHook > hookDelay.value) {
                    player.inventory.setSelectedSlot(grapple)
                    mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
                    lastHook = now; hookedId = hookable.id; holdGrappleUntil = now + 300; hookYaw = player.yRot
                    Debug.line("BlazeMacro", "hook blaze ${hookable.id} at %.1f blocks, ${inRange.size} in range".format(player.distanceTo(hookable)))
                }
                return
            }
            // Nothing in hook range: wait at the spot for 5 s, then go after the nearest one.
            if (noHookSince == 0L) noHookSince = now
            if (!hookMiniOnly.value && ground == null && now - noHookSince < 1500) {
                if (!inBox(player.position(), 3.0)) goTo(backPoint(), 1.0) else releaseMovement()
                return
            }
        }

        // Never chase a Blaze far from the spot: wait there for one to come closer.
        // The one it is going for stays the same until it is dead or out of reach (the nearest changes every tick, which made it
        // swing round and restart the walk). Only Blazes inside the box and low enough to hit are walked to.
        val locked = mobs.firstOrNull { it.id == lockId && walkable(it, mini) }
        val tgt = locked ?: ground ?: mob.takeIf { walkable(it, mini) && player.distanceTo(it) < chaseMax } ?: run {
            why("nothing to walk to: ${mobs.size} blazes, none inside the box and low enough (hook ${if (grapple != null) "ready" else "missing"})")
            releaseMovement(); return
        }
        if (tgt.id != lockId) newTarget("blaze id${tgt.id}, %.1f blocks away, ground=${ground?.id == tgt.id}".format(tgt.distanceTo(player)))
        lockId = tgt.id

        // The skip timer runs only while it makes no progress: it used to count from the pick, so a Blaze 16 blocks off was dropped on the
        // way (the walk takes 3 s) and the next one picked, over and over; it also kept the old start time when the same Blaze came back
        // after a hook or a kill, skipping it at once. Progress = 0.7 blocks closer, or already within reach.
        val tDist = tgt.distanceTo(player).toDouble()
        if (tgt.id != targetId || now - targetSeenAt > 600) { targetId = tgt.id; targetSince = now; targetBest = tDist }
        targetSeenAt = now
        if (tDist < targetBest - 0.7 || tDist < 4.5) { targetBest = minOf(targetBest, tDist); targetSince = now }
        // No progress and no hit for 2.5 s (hovering out of reach): leave it for a while and take another one.
        if (now - targetSince > 2500) { unreachable[tgt.id] = now + 10000; targetSince = now; Debug.line("BlazeMacro", "skipping blaze ${tgt.id}: no progress in 2.5 s (%.1f blocks)".format(tDist)); return }
        if (player.eyePosition.distanceTo(tgt.boundingBox.center) < 4.0 && player.hasLineOfSight(tgt)) {
            engage(player, tgt, 2.9, attackEvery = 3, minDist = 1.2, leash = true)
        } else {
            // Look at the one it is going for first, then walk (in the box, where it walks with the keys; the Pathfinder turns itself).
            if (inBox(player.position(), 3.0)) {
                lookAt(player, tgt.boundingBox.center)
                val lt = lookTarget
                if (lt == null || Math.abs(Mth.wrapDegrees(lt.first - player.yRot)) > 25f) {
                    why("turning to blaze id${tgt.id} before walking (yaw off ${if (lt == null) "?" else (Math.abs(Mth.wrapDegrees(lt.first - player.yRot)) / 5).toInt() * 5} deg)")
                    releaseMovement(); return
                }
                why("walking to blaze id${tgt.id}, %.1f blocks".format(player.distanceTo(tgt)))
            }
            if (!moveTo(groundBelow(tgt), 2.0)) {
                unreachable[tgt.id] = now + 8000
                Debug.line("BlazeMacro", "no path to blaze ${tgt.id}, skipping it for 8 s")
            }
        }
    }

    /** On the ground, or hovering low enough to hit by walking up to it (within about 2 blocks of the floor), inside the box. */
    private fun onFoot(e: LivingEntity): Boolean = inBox(e.position(), 3.0) && (e.onGround() || e.y - groundBelow(e).y <= 2.0)

    private var lockId = -1
    private var nearLock = -1
    private var wallUntil = 0
    private var wallDir = 0.0 to 0.0
    private var retreatMode = false
    private var approachMode = false

    // ---- healing, flare, walls

    private var wandAt = 0L
    private var flareAt = 0L
    private var openTarget: Vec3? = null
    private var openAt = 0L

    private fun slotOf(player: net.minecraft.client.player.LocalPlayer, id: String): Int? =
        (0 until 9).firstOrNull { player.inventory.getItem(it).skyblockId == id }

    /** Wand of Atonement when health is low (its heal does not stack: 6 s apart). True when it was used (the slot is then held a moment). */
    private fun autoHeal(player: net.minecraft.client.player.LocalPlayer): Boolean {
        if (!useWand.value || player.health >= player.maxHealth * wandBelow.value / 100.0) return false
        val now = System.currentTimeMillis()
        // The heal lasts 6 s and does not stack: wait it out (plus a margin) before casting again.
        if (now - wandAt < 8000) return false
        // Swapping to the wand cancels the Ragnarock channel (and the Implosion step): not while the wave sequence runs.
        if (ragnaCastAt != 0L && now - ragnaCastAt < implosionDelay.value + 800) return false
        if (demonPhase in 1..2) return false
        val slot = slotOf(player, "WAND_OF_ATONEMENT") ?: return false
        player.inventory.setSelectedSlot(slot)
        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        holdGrappleUntil = now + 300
        wandAt = now
        Debug.line("BlazeMacro", "Wand of Atonement at ${player.health.toInt()}/${player.maxHealth.toInt()} HP")
        return true
    }

    private val KILLS = Regex("""(\d+)/(\d+)\s+Kills""")
    private val COMBAT_XP = Regex("""\(?([\d,.]+)(k?)/([\d,.]+)(k?)\)?\s*Combat XP""")

    /** The sidebar lines under "Slayer Quest", for the log. */
    private fun questLines(): String {
        val lines = Sidebar.lines
        val q = lines.indexOfFirst { it.contains("Slayer Quest") }
        return if (q < 0) "no Slayer Quest line" else lines.drop(q + 1).take(4).joinToString(" | ")
    }

    /** The quest is a kill or two (or the last tenth of its XP) away from spawning the boss, read off the sidebar. */
    private fun nearSpawn(): Boolean {
        val lines = Sidebar.lines
        val q = lines.indexOfFirst { it.contains("Slayer Quest") }
        if (q < 0) return false
        for (l in lines.drop(q + 1).take(4)) {
            KILLS.find(l)?.let { return it.groupValues[2].toInt() - it.groupValues[1].toInt() <= 2 }
            COMBAT_XP.find(l)?.let { m ->
                fun n(num: String, k: String) = (num.replace(",", "").toDoubleOrNull() ?: 0.0) * (if (k == "k") 1000 else 1)
                val total = n(m.groupValues[3], m.groupValues[4])
                return total > 0 && n(m.groupValues[1], m.groupValues[2]) / total >= 0.9
            }
        }
        return false
    }

    private fun solidAt(level: net.minecraft.world.level.Level, x: Double, y: Double, z: Double): Boolean {
        val p = BlockPos.containing(x, y, z)
        return !level.getBlockState(p).getCollisionShape(level, p).isEmpty
    }

    /** Walks in direction (dx, dz); when a wall is within ~2 blocks that way, swings to the nearest free direction (or along the wall's push-out) instead. */
    private fun retreatStep(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.world.level.Level, dx: Double, dz: Double) {
        val len = hypot(dx, dz).takeIf { it > 0.01 } ?: return walkToward(0.0, 0.0, sprint = false)
        val base = atan2(dz, dx)
        fun blocked(a: Double) = doubleArrayOf(0.9, 1.6, 2.3).any { r -> doubleArrayOf(0.5, 1.4).any { h -> solidAt(level, player.x + cos(a) * r, player.y + h, player.z + sin(a) * r) } }
        for (off in doubleArrayOf(0.0, 0.6, -0.6, 1.2, -1.2, 1.8, -1.8, Math.PI)) {
            val a = base + off
            if (!blocked(a)) return walkToward(cos(a), sin(a), sprint = false)
        }
        val push = wallPush(player, level)
        if (push != null) walkToward(push.first, push.second, sprint = false) else walkToward(0.0, 0.0, sprint = false)
    }

    /** Away from a wall: the direction (unit) that points out of a big solid mass within about 2 blocks round you, or null in the open. */
    private fun wallPush(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.world.level.Level): Pair<Double, Double>? {
        var px = 0.0; var pz = 0.0; var blocked = 0
        for (i in 0 until 16) {
            val a = i * Math.PI / 8
            val dx = cos(a); val dz = sin(a)
            var hit = false
            for (r in doubleArrayOf(1.3, 2.2)) for (h in doubleArrayOf(0.5, 1.4)) {
                if (solidAt(level, player.x + dx * r, player.y + h, player.z + dz * r)) hit = true
            }
            if (hit) { blocked++; px -= dx; pz -= dz }
        }
        val len = hypot(px, pz)
        return if (blocked >= 6 && len > 0.4) (px / len) to (pz / len) else null
    }

    /** The most open point of the box (most directions free for 3.5 blocks), kept for 10 s so it does not jump about. */
    private fun openSpot(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.world.level.Level): Vec3 {
        val now = System.currentTimeMillis()
        openTarget?.let { if (now - openAt < 10_000) return it }
        val s = spot()
        val half = roamRadius.value / 2.0 - 1.0
        var best = Vec3(player.x, s.y, player.z)
        var bestScore = -1.0
        var x = s.x - half
        while (x <= s.x + half) {
            var z = s.z - half
            while (z <= s.z + half) {
                if (!solidAt(level, x, s.y + 0.5, z) && !solidAt(level, x, s.y + 1.4, z) && solidAt(level, x, s.y - 0.5, z)) {
                    var free = 0
                    for (i in 0 until 16) {
                        val a = i * Math.PI / 8
                        var ok = true
                        for (r in doubleArrayOf(1.5, 2.5, 3.5)) for (h in doubleArrayOf(0.5, 1.4)) {
                            if (solidAt(level, x + cos(a) * r, s.y + h, z + sin(a) * r)) ok = false
                        }
                        if (ok) free++
                    }
                    // Most open wins; the nearer one if equal.
                    val score = free - hypot(x - player.x, z - player.z) * 0.01
                    if (score > bestScore) { bestScore = score; best = Vec3(x, s.y, z) }
                }
                z += 2.0
            }
            x += 2.0
        }
        openTarget = best; openAt = now
        Debug.line("BlazeMacro", "open spot ${best.x.toInt()},${best.y.toInt()},${best.z.toInt()} (score ${"%.1f".format(bestScore)})")
        return best
    }

    /** Worth walking to: your miniboss anywhere, else a Blaze inside the box, at your level, no more than 3 blocks up. */
    private fun walkable(e: LivingEntity, mini: LivingEntity?): Boolean =
        e === mini || (inBox(e.position(), 3.0) && e.y - groundBelow(e).y <= 3.0)

    private var wavesDone = 0
    private var waveSawDemons = false

    /**
     * True while the boss HP is at or just under the next split (50% on tiers I and II, 66.7% then 33.3% on III and IV) and the
     * demons of that wave have not shown up yet. Read from the Slayer Tracker's view of the boss tag.
     */
    private fun hpSplitCue(): Boolean {
        if (waveSawDemons) return false
        val b = SlayerBosses
        val now = System.currentTimeMillis()
        val seen = b.name == "Inferno Demonlord" && b.maxHp > 0.0
        if (seen) { lastBossRatio = b.hp / b.maxHp; lastBossMax = b.maxHp; lastBossSeenAt = now }
        // The tag (HP display) is gone while the boss is up in the air: if it was last seen close to a split, that is the lift-off.
        if (!seen && (lastBossMax <= 0.0 || now - lastBossSeenAt > 8000)) return false
        val splits = if (lastBossMax >= 40_000_000.0) listOf(0.667, 0.333) else listOf(0.5)
        if (wavesDone >= splits.size) return false
        return lastBossRatio <= splits[wavesDone] + (if (seen) 0.02 else 0.10)
    }

    private var lastBossRatio = 1.0
    private var lastBossMax = 0.0
    private var lastBossSeenAt = 0L

    private var demonPhase = 0
    private var demonPhaseAt = 0L
    private var lastDemonSeen = 0L
    private var retreatFrom: Vec3? = null
    private val WITHER_SWORDS = setOf("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE")

    /** Ragnarock Axe, once per boss. True when it was cast (the swap window then holds the slot for a moment). */
    private fun castRagnarock(player: net.minecraft.client.player.LocalPlayer): Boolean {
        ragnaCasted = true
        val slot = (0 until 9).firstOrNull { player.inventory.getItem(it).skyblockId == "RAGNAROCK_AXE" }
        if (slot == null) { Debug.line("BlazeMacro", "demons spawned but there is no Ragnarock Axe in the hotbar"); return false }
        player.inventory.setSelectedSlot(slot)
        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        holdGrappleUntil = System.currentTimeMillis() + 350
        ragnaCastAt = System.currentTimeMillis()
        Debug.line("BlazeMacro", "Ragnarock cast from slot ${slot + 1}")
        return true
    }

    /**
     * Wither sword (Hyperion / Astraea / Scylla / Valkyrie) right click, thrown from mid-air with the camera pointing down:
     * the implosion heals you and the teleport has nowhere to go but the floor. Looks down, jumps, casts once airborne.
     * True when done (cast, or there is no such sword).
     */
    private fun implosion(player: net.minecraft.client.player.LocalPlayer): Boolean {
        val slot = (0 until 9).firstOrNull { player.inventory.getItem(it).skyblockId in WITHER_SWORDS } ?: run {
            Debug.line("BlazeMacro", "no Hyperion / Astraea / Scylla / Valkyrie in the hotbar, skipping Implosion"); return true
        }
        lookTarget = player.yRot to 80f
        if (player.xRot < 55f) return false           // look down first
        if (player.onGround()) { mc.options.keyJump.setDown(true); return false }
        mc.options.keyJump.setDown(false)
        player.inventory.setSelectedSlot(slot)
        mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
        holdGrappleUntil = System.currentTimeMillis() + 350
        Debug.line("BlazeMacro", "Implosion cast from slot ${slot + 1} in mid-air")
        return true
    }

    /** The miniboss tag(s) carry the owner's name and "MINIBOSS" / "Demon"; the mob under the tag is the target. Logged so the format can be checked. */
    private var lastMiniLog = ""

    private fun ownMiniboss(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel): LivingEntity? {
        val me = mc.user.name
        val stands = level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { it.hasCustomName() && it.distanceToSqr(player) < 60.0 * 60.0 }
            .map { it to clean(it.customName!!.string) }
        // Recorded: the miniboss tags read "[Lv456]  Kindleheart Demon 17.5M/17.5M❤" and "SLAYER MINIBOSS", with no owner name on them (the boss
        // has "<name>'s SLAYER BOSS"), so requiring the name never matched. Yours spawns on top of you: a demon tag within 30 blocks, not dead (0/..).
        val tags = stands.filter { (a, t) ->
            (t.contains("MINIBOSS", ignoreCase = true) || MINIBOSS.containsMatchIn(t)) &&
                (t.contains(me, ignoreCase = true) || (MINIBOSS.containsMatchIn(t) && !t.contains(" 0/") && a.distanceToSqr(player) < 30.0 * 30.0))
        }
        val living = level.entitiesForRendering().filterIsInstance<LivingEntity>().filter { it !is ArmorStand && it !== player && it.isAlive && !it.isInvisible }
        val found = tags.firstNotNullOfOrNull { (anchor, text) ->
            living.filter { Math.abs(it.x - anchor.x) < 3.5 && Math.abs(it.z - anchor.z) < 3.5 && it.y <= anchor.y + 9.0 && it.y > anchor.y - 5.0 }
                .minByOrNull { it.distanceToSqr(anchor) }?.let { it to text }
        } ?: return null
        if (found.second != lastMiniLog) { lastMiniLog = found.second; Debug.line("BlazeMacro", "own miniboss ${found.first.type.description.string}: \"${found.second}\"") }
        return found.first
    }

    // ---- fight

    private val MINIBOSS = Regex("""(Flare|Kindleheart|Burningsoul) Demon""")
    /** The circled-letter Quazii / Typhoeus tags, followed by HP that is not 0 (a dead demon reads "0/10M"). */
    private val DEMON = Regex("(ⓆⓊⒶⓏⒾⒾ|ⓉⓎⓅⒽⓄⒺⓊⓈ)\\s+(?!0/)")
    private val SHIELD = Regex("""\b(ASHEN|SPIRIT|CRYSTAL|AURIC)\b""")
    private val PILLAR = Regex("""^([\d.]+)s (\d+) hits?$""")
    private val ATTUNED = Regex("""Attuned:\s*(\w+)""", RegexOption.IGNORE_CASE)

    /** Hypixel's two dagger families: Spirit/Crystal (Deathripper line) and Ashen/Auric (Pyrochaos line). */
    private val SPIRIT_FAMILY = setOf("HEARTMAW_DAGGER", "BURSTMAW_DAGGER", "MAWDUST_DAGGER")
    private val ASHEN_FAMILY = setOf("HEARTFIRE_DAGGER", "BURSTFIRE_DAGGER", "FIREDUST_DAGGER")

    private class Part(val entity: LivingEntity, val shield: String?, val immune: Boolean, val text: String)

    private var lastTarget = -1
    private var fightLock = -1
    private var lastSwap = 0L
    private var fleeUntil = 0
    private var fleeDir = Vec3.ZERO

    private fun clean(s: String) = Sidebar.clean(s).trim()

    private var fightShield: String? = null

    /**
     * One tick of stepping off the DDR marks. Typhoeus plants them under you nearly all the time (a new set every ~0.4 s in the log), so
     * this used to be the whole fight: it walked backwards, never swung and kept the Wand of Atonement in hand (2 hits in 8 s, then dead).
     * Now it keeps the camera on the demon, puts the dagger back, and hits whenever the demon is in reach.
     */
    private fun fleeStep(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel) {
        status = "Dodging fire"; why("stepping off DDR marks (${fleeUntil - ticks} ticks left)")
        walkToward(fleeDir.x, fleeDir.z, sprint = true)
        val target = level.getEntity(fightLock) ?: return
        val point = target.boundingBox.center
        lookAt(player, point)
        if (System.currentTimeMillis() < holdGrappleUntil || !prepareWeapon(player, fightShield)) return
        val lt = lookTarget ?: return
        val facing = Math.abs(Mth.wrapDegrees(lt.first - player.yRot)) < 12f && Math.abs(lt.second - player.xRot) < 15f
        if (facing && player.eyePosition.distanceTo(point) < 4.2 && ticks >= nextAttackTick) {
            (mc as MinecraftAccessor).cedStartAttack()
            nextAttackTick = ticks + 3 + (Math.random() * 2).toInt()
            why("hitting while stepping off the marks")
        }
    }

    private fun fight(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel) {
        ticks++
        // Not while it walks to the safespot: stopping the walk here every tick made goTo plan a new route (and log + chat it) 75 times a second.
        if (Pathfinding.active && !(safeFight.value && pfGoal != null)) pfStop()
        status = "Fighting the boss"
        if (System.currentTimeMillis() < holdGrappleUntil) { why("holding the slot for a cast (${holdGrappleUntil - System.currentTimeMillis()} ms)"); return } // a cast (Ragnarock) is in its swap window
        if (autoHeal(player)) return
        val stands = level.entitiesForRendering().filterIsInstance<ArmorStand>()
            .filter { it.hasCustomName() && it.distanceToSqr(player) < 45.0 * 45.0 }
            .map { it to clean(it.customName!!.string) }

        // Safespot: go there first, then every branch below holds still (no fire dodging, pillars, backing off or chasing).
        if (safeFight.value) {
            val s = Vec3(safeX.value + 0.5, safeY.value, safeZ.value + 0.5)
            if (hypot(s.x - player.x, s.z - player.z) > 1.0 || Math.abs(s.y - player.y) > 2.0) {
                status = "Going to the safespot"; why("walking to the safespot ${safeX.value.toInt()} ${safeY.value.toInt()} ${safeZ.value.toInt()}")
                if (goTo(s, 0.8)) return
            }
        }
        // DDR Apocalypse: orange terracotta marks the blocks that are about to burn. Get off them.
        if (safeFight.value) { fleeUntil = 0 } else if (ticks < fleeUntil) { fleeStep(player, level); return }
        val marked = markedBlocks(player, level)
        val feet = player.blockPosition()
        // Within 2 blocks of a mark or a flame (the pits are crosses that grow into fire 1.5 s later): get clear of it, by a way that is clear.
        if (!safeFight.value && marked.any { Math.abs(it.x - feet.x) <= 2 && Math.abs(it.z - feet.z) <= 2 }) {
            val near = marked.filter { Math.abs(it.x - feet.x) <= 3 && Math.abs(it.z - feet.z) <= 3 }
            val cx = near.map { it.x + 0.5 }.average(); val cz = near.map { it.z + 0.5 }.average()
            var dx = player.x - cx; var dz = player.z - cz
            val len = sqrt(dx * dx + dz * dz).takeIf { it > 0.1 } ?: 1.0
            dx /= len; dz /= len
            fleeDir = safeFleeDir(player, level, marked, dx, dz); fleeUntil = ticks + 8 + (Math.random() * 6).toInt()
            Debug.line("BlazeMacro", "fire pit marks near (${marked.size} blocks): moving off ${"%.1f,%.1f".format(fleeDir.x, fleeDir.z)}")
            fleeStep(player, level)
            return
        }

        // Fire pillar: "4s 8 hits" floating over it. Hit it until the text is gone.
        val pillar = stands.firstOrNull { PILLAR.matches(it.second) }
        if (pillar != null && !safeFight.value) {
            val p = pillar.first
            status = "Breaking a fire pillar"
            val point = Vec3(p.x, player.eyeY.coerceIn(p.y - 3.0, p.y - 0.6), p.z)
            debugRepeat("pillar \"${pillar.second.replace(Regex("[\\d.]+s "), "")}\" at ${p.blockPosition().toShortString()}")
            engageAt(player, point, 2.6, attackEvery = 2, strict = true)
            return
        }

        val parts = parts(player, level, stands)
        // A demon is a mob under a Quazii / Typhoeus tag that still has HP (not a dead one at 0/..), never just any mob near the boss tag:
        // any non-Blaze paired to the stack used to count, which started "demon" phases (backing off, hopping, Implosion) for nothing.
        val demons = parts.filter { it.entity !is Blaze && DEMON.containsMatchIn(it.text) }
        // The boss body: a Blaze under its own tag, else (after the demons die the tags shift, or the tag is gone) the Blaze that has the
        // boss's health.
        val bossParts = parts.filter { it.entity is Blaze && (it.text.contains("Inferno Demonlord") || it.text == "by HP") }
            .ifEmpty { bossByHealth(player, level, stands) }
        // The two demons drop out of the sky: back off, jumping, and pop Ragnarock; when they land, Implosion from mid-air; then fight.
        val nowMs = System.currentTimeMillis()
        // The boss Blaze lifts off the ground a moment before the two demons drop: that is the cue (Ragnarock needs the time).
        // Only the real boss body: ordinary Blazes hover all the time, so any Blaze near the tag looked "lifted off".
        val bossBody = bossParts.firstOrNull()?.entity
        if (bossBody != null && !bossBody.onGround()) bossAirTicks++ else bossAirTicks = 0
        if (demons.isNotEmpty()) waveSawDemons = true
        // The earliest cue: the boss splits at a fixed share of its HP (the HP is on its tag), a second or two before it lifts off.
        // (No "boss is off the ground" cue: a Tier IV boss hovers and flies the whole fight, which started a wave every few seconds.)
        val waveActive = demons.isNotEmpty() || hpSplitCue()
        if (!waveActive) {
            if (demonPhase != 0 && nowMs - lastDemonSeen > 2000) { demonPhase = 0; wavesDone++; waveSawDemons = false } // ready for the next wave
        } else {
            lastDemonSeen = nowMs
            if (demonPhase == 0) {
                demonPhase = 1; demonPhaseAt = nowMs; retreatFrom = player.position()
                Debug.line("BlazeMacro", "demons out: backing off ${retreatDist.value} blocks")
            }
            if (demonPhase == 1) {
                status = "Backing off, Ragnarock"
                if (useRagnarock.value && !ragnaCasted && castRagnarock(player)) return
                val from = retreatFrom ?: player.position()
                val nearest = demons.minByOrNull { it.entity.distanceToSqr(player) }?.entity ?: bossBody ?: return
                lookAt(player, nearest.boundingBox.center)
                lookTarget = lookTarget?.let { it.first to player.xRot.coerceIn(-20f, 30f) } // face them; pitch kept but never stuck looking straight up/down
                val dx = nearest.x - player.x; val dz = nearest.z - player.z
                why("wave phase 1: backing off %.1f/${retreatDist.value}, ${demons.size} demons out, ${(nowMs - demonPhaseAt) / 1000} s in, ragnarock ${if (ragnaCasted) "cast" else "not yet"}".format(hypot(player.x - from.x, player.z - from.z)).replace(Regex("(\\d+)\\.\\d+/"), "$1/"))
                if (safeFight.value) releaseMovement()
                else if (hypot(player.x - from.x, player.z - from.z) < retreatDist.value) retreatStep(player, level, -dx, -dz) else releaseMovement()
                if (!safeFight.value) mc.options.keyJump.setDown(player.onGround()) // hops while backing off
                if ((demons.isNotEmpty() && demons.none { !it.entity.onGround() }) || nowMs - demonPhaseAt > 9000) { demonPhase = 2; demonPhaseAt = nowMs; mc.options.keyJump.setDown(false) }
                return
            }
            if (demonPhase == 2) {
                // Let Ragnarock's channel run first: swapping items straight away cancelled it.
                if (ragnaCastAt != 0L && nowMs - ragnaCastAt < implosionDelay.value) { status = "Ragnarock channel"; why("wave phase 2: waiting ${implosionDelay.value.toLong() - (nowMs - ragnaCastAt)} ms for the Ragnarock channel")
                    // Not standing still for the 3 s (that is what got it killed): face the nearest demon and inch back, off any laser.
                    val near = demons.minByOrNull { it.entity.distanceToSqr(player) }?.entity
                    if (near == null) { releaseMovement(); return }
                    lookAt(player, near.boundingBox.center)
                    lookTarget = lookTarget?.let { it.first to player.xRot.coerceIn(-20f, 30f) }
                    val from = retreatFrom ?: player.position()
                    val laser = laserDodge(player, level)
                    if (laser != null) walkToward(laser.x, laser.z, sprint = false)
                    else if (hypot(player.x - from.x, player.z - from.z) < retreatDist.value * 2) retreatStep(player, level, player.x - near.x, player.z - near.z)
                    else walkToward(0.0, 0.0, sprint = false)
                    mc.options.keyShift.setDown(true)
                    return
                }
                why("wave phase 2: implosion step, ${nowMs - demonPhaseAt} ms in")
                status = "Implosion"
                if (!useImplosion.value || implosion(player) || nowMs - demonPhaseAt > 3000) { demonPhase = 3; mc.options.keyJump.setDown(false) }
                return
            }
        }
        val pool = demons.ifEmpty { bossParts }
        val candidates = pool.filter { !it.immune }
        // Sticks to the one it is on until it is dead or immune: nearest-each-tick flipped between Quazii and Typhoeus, swapping the dagger every tick.
        val target = candidates.firstOrNull { it.entity.id == fightLock } ?: candidates.minByOrNull { it.entity.distanceToSqr(player) }
        if (target == null) {
            releaseMovement()
            // Demons spawning / all immune: keep facing the same way, level (not down at the floor).
            lookTarget = player.yRot to player.xRot.coerceIn(-20f, 30f)
            debugRepeat("no attackable part (parts=${parts.size}, immune=${parts.count { it.immune }}); blazes near: ${blazeInfo(player, level)}")
            return
        }
        fightLock = target.entity.id
        fightShield = target.shield
        if (target.entity.id != lastTarget) {
            lastTarget = target.entity.id
            newTarget("${target.entity.type.description.string} id${target.entity.id}, %.1f blocks away, ${candidates.size} candidates".format(target.entity.distanceTo(player)))
            Debug.line("BlazeMacro", "target ${target.entity.type.description.string} shield=${target.shield} \"${target.text}\"")
        }
        status = "Fighting " + target.entity.type.description.string.let { if (it.contains("Wither")) "Quazii" else if (it.contains("Piglin")) "Typhoeus" else "the boss" }
        if (!prepareWeapon(player, target.shield)) {
            why("weapon not ready for ${target.shield}: holding ${player.mainHandItem.skyblockId}, attunement ${player.mainHandItem.loreLines.firstNotNullOfOrNull { ATTUNED.find(it)?.groupValues?.get(1) }}")
            lookAt(player, target.entity.boundingBox.center); return
        }
        // The boss: a small fixed back-off. The demons walk right up to you, so they get a bounded kite instead (back off at most
        // Demon Back Off Max blocks, then close in again), never standing still in their fire / lasers.
        if (target.entity is Blaze) engage(player, target.entity, 2.9, attackEvery = 3, minDist = keepDist.value, strict = true)
        else {
            val dodge = laserDodge(player, level)
            if (dodge != null) status = "Dodging the laser"
            debugRepeat("demon fight: laser dodge=${dodge?.let { "%.1f,%.1f".format(it.x, it.z) }}")
            engage(player, target.entity, 2.9, attackEvery = 3, strict = true, backMax = demonBackMax.value, dodge = dodge)
        }
    }

    /**
     * Quazii's laser is a Guardian beam (the invisible Guardian next to him), which hurts. When you stand within 1.6 blocks of a
     * beam, the horizontal direction (unit) that takes you off it, sideways to the beam; else null.
     */
    private fun laserDodge(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel): Vec3? {
        val p = player.position().add(0.0, 0.9, 0.0)
        var best: Vec3? = null
        var bestDist = 1.6
        for (g in level.entitiesForRendering()) {
            if (g !is net.minecraft.world.entity.monster.Guardian || g.distanceToSqr(player) > 40.0 * 40.0) continue
            val t = g.activeAttackTarget ?: continue
            val a = g.eyePosition; val b = t.eyePosition
            val ab = b.subtract(a)
            val len2 = ab.lengthSqr().takeIf { it > 1.0e-4 } ?: continue
            val closest = a.add(ab.scale(((p.subtract(a)).dot(ab) / len2).coerceIn(0.0, 1.0)))
            val d = p.distanceTo(closest)
            if (d >= bestDist) continue
            val hl = hypot(ab.x, ab.z)
            if (hl < 0.01) continue
            val nx = -ab.z / hl; val nz = ab.x / hl
            val side = if ((p.x - closest.x) * nx + (p.z - closest.z) * nz >= 0) 1.0 else -1.0
            bestDist = d; best = Vec3(nx * side, 0.0, nz * side)
        }
        return best
    }

    /** Boss / demon entities of YOUR boss: each is paired with the "<you>'s SLAYER BOSS" stand above it and the tags stacked around it. */
    private fun parts(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel, stands: List<Pair<ArmorStand, String>>): List<Part> {
        val me = mc.user.name
        val own = stands.filter { it.second.contains("SLAYER BOSS") && it.second.contains(me, ignoreCase = true) }
        if (own.isEmpty()) return bossByHealth(player, level, stands)
        val living = level.entitiesForRendering().filterIsInstance<LivingEntity>().filter { it !is ArmorStand && it !== player && it.isAlive }
        val found = own.mapNotNull { (anchor, _) ->
            val stack = stands.filter { Math.abs(it.first.x - anchor.x) < 1.2 && Math.abs(it.first.z - anchor.z) < 1.2 && it.first.y > anchor.y - 1.6 && it.first.y < anchor.y + 2.2 }
                .map { it.second }
            val text = stack.joinToString(" | ")
            val near = living.filter { Math.abs(it.x - anchor.x) < 3.5 && Math.abs(it.z - anchor.z) < 3.5 && it.y <= anchor.y + 9.0 && it.y > anchor.y - 5.0 }
            // Quazii is the Wither Skeleton, Typhoeus the Zombified Piglin (there is an invisible Guardian about too, aiming at it
            // missed the visible mob). Otherwise the nearest visible one.
            val prefer = if (text.contains("Inferno Demonlord")) null else when {
                text.contains("ⓆⓊⒶⓏⒾⒾ") -> "Wither Skeleton"
                text.contains("ⓉⓎⓅⒽⓄⒺⓊⓈ") -> "Zombified Piglin"
                else -> null
            }
            // The boss body (no demon prefix) is the Blaze already locked on, else the one whose health matches the boss: ordinary Blazes
            // hover right next to the tag, and "nearest to the tag" kept swapping the target onto them mid-fight.
            val bossHp = knownBossHp()
            val entity = prefer?.let { p -> near.filter { it.type.description.string == p }.minByOrNull { it.distanceToSqr(anchor) } }
                ?: (if (prefer == null) near.firstOrNull { it.id == fightLock && it is Blaze } else null)
                ?: (if (prefer == null && bossHp > 0.0) near.filter { it is Blaze && it.health.toDouble() in bossHp * 0.25..bossHp * 1.03 }.minByOrNull { Math.abs(it.health - bossHp) } else null)
                ?: near.filter { !it.isInvisible }.minByOrNull { it.distanceToSqr(anchor) }
                ?: near.minByOrNull { it.distanceToSqr(anchor) }
                ?: return@mapNotNull null
            // Its own shield line (right above its "SLAYER BOSS" stand); the whole stack mixes in the other tags standing on the same spot.
            val shield = shieldAbove(anchor, stands) ?: stack.firstNotNullOfOrNull { SHIELD.find(it)?.groupValues?.get(1) }
            Part(entity, shield, stack.any { it.contains("IMMUNE") }, text)
        }.distinctBy { it.entity.id }
        return found.ifEmpty { bossByHealth(player, level, stands) }
    }

    private val BOSS_HP = listOf(2.5e6, 10e6, 45e6, 150e6)

    /** The four nearest Blazes with what the client knows of them, for the log when the boss cannot be found. */
    private fun blazeInfo(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel): String =
        level.entitiesForRendering().filterIsInstance<Blaze>().sortedBy { it.distanceToSqr(player) }.take(4).joinToString("; ") {
            "id${it.id} d%.0f y%.0f max%.0f hp%.0f ground=${it.onGround()} alive=${it.isAlive}".format(it.distanceTo(player), it.y, it.maxHealth, it.health)
        }

    /** The boss HP as last read off its tag (the tag is gone while it is in the air, so up to 15 s old), 0 when unknown. */
    private fun knownBossHp(): Double {
        val b = SlayerBosses
        if (b.name == "Inferno Demonlord" && b.hp > 0.0) return b.hp
        return if (lastBossMax > 0.0 && System.currentTimeMillis() - lastBossSeenAt < 40_000) lastBossRatio * lastBossMax else 0.0
    }

    /** The full HP of the tier set in the settings (the boss starts at it, before any tag is read). */
    private fun tierHp(): Double = when (tier.value) { "I" -> 2.5e6; "II" -> 10e6; "III" -> 45e6; else -> 150e6 }

    /** The attunement tag stacked right above a "SLAYER BOSS" stand (the shield line is the one above it): own boss only, never a neighbour's. */
    private fun shieldAbove(anchor: ArmorStand, stands: List<Pair<ArmorStand, String>>): String? =
        stands.filter { Math.abs(it.first.x - anchor.x) < 0.6 && Math.abs(it.first.z - anchor.z) < 0.6 && it.first.y > anchor.y + 0.05 && it.first.y < anchor.y + 1.0 }
            .sortedBy { it.first.y }.firstNotNullOfOrNull { SHIELD.find(it.second)?.groupValues?.get(1) }

    /**
     * The boss body without a tag: a Blaze whose own health is the boss HP. The client's max health is 1024 for every mob here (it is
     * no use), but the entity's CURRENT health is the real number: it matches the tag (98.9M on the tag, 98931880 on the entity),
     * or, at the spawn before any tag, a full boss HP of a tier. Not under another player's boss tag.
     */
    private fun bossByHealth(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel, stands: List<Pair<ArmorStand, String>>): List<Part> {
        val foreign = stands.filter { it.second.contains("SLAYER BOSS") && !it.second.contains(mc.user.name, ignoreCase = true) }.map { it.first }
        val hp = knownBossHp()
        val full = tierHp()
        val body = level.entitiesForRendering().filterIsInstance<Blaze>().filter { e ->
            val h = e.health.toDouble()
            // The last HP read off the tag can be well out of date: while the tag is hidden the boss can lose half of it. So a wide window
            // below it (a quarter up to just over it), and without any tag only the FULL HP of the tier in the settings (a damaged
            // Millennia-Aged Blaze sat at 9.94M, "a Tier II boss", in the log).
            e.isAlive && h > 10_000.0 && ((hp > 0.0 && h in hp * 0.25..hp * 1.03) || (hp <= 0.0 && Math.abs(h - full) < full * 0.01)) &&
                e.distanceToSqr(player) < 60.0 * 60.0 &&
                foreign.none { a -> Math.abs(a.x - e.x) < 3.5 && Math.abs(a.z - e.z) < 3.5 && e.y <= a.y + 9.0 && e.y > a.y - 5.0 }
        }.maxByOrNull { it.health } ?: return emptyList()
        // Its shield line: the tag above its own "SLAYER BOSS" stand, which is the one beside its "Inferno Demonlord" name tag (the demons'
        // tags can sit on the very same spot, so the whole stack is no use). Not immune here: the demon's IMMUNE tag overlaps it.
        val name = stands.filter { it.second.contains("Inferno Demonlord") }.minByOrNull { it.first.distanceToSqr(body) }
        val anchor = name?.let { n -> stands.filter { it.second.contains("SLAYER BOSS") && it.second.contains(mc.user.name, ignoreCase = true) }.minByOrNull { it.first.distanceToSqr(n.first) } }
        val shield = anchor?.let { shieldAbove(it.first, stands) }
        debugRepeat("boss found by its health ~${(body.health / 1_000_000).toInt()}M (tag says ~${(hp / 1_000_000).toInt()}M), shield $shield")
        return listOf(Part(body, shield, false, "by HP"))
    }

    private fun markedBlocks(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.client.multiplayer.ClientLevel): List<BlockPos> {
        val base = player.blockPosition()
        val out = ArrayList<BlockPos>()
        // The orange terracotta crosses (the warning) and the fire they turn into (the pit itself).
        for (p in BlockPos.betweenClosed(base.offset(-4, -2, -4), base.offset(4, 1, 4))) {
            val st = level.getBlockState(p)
            if (st.`is`(Blocks.DYED_TERRACOTTA.orange()) || st.`is`(Blocks.FIRE) || st.`is`(Blocks.SOUL_FIRE)) out += p.immutable()
        }
        return out
    }

    /** A way out of the fire pits: from the way straight away from them, try turning left and right until the spot 3.5 blocks off is clear of marks, has floor, and no wall. */
    private fun safeFleeDir(player: net.minecraft.client.player.LocalPlayer, level: net.minecraft.world.level.Level, marked: List<BlockPos>, ax: Double, az: Double): Vec3 {
        val base = atan2(az, ax)
        for (off in doubleArrayOf(0.0, 0.785, -0.785, 1.571, -1.571, 2.356, -2.356, 3.1416)) {
            val a = base + off
            val dx = cos(a); val dz = sin(a)
            val tx = player.x + dx * 3.5; val tz = player.z + dz * 3.5
            val cell = BlockPos.containing(tx, player.y, tz)
            if (marked.any { Math.abs(it.x - cell.x) <= 1 && Math.abs(it.z - cell.z) <= 1 }) continue
            if (!solidAt(level, tx, player.y - 0.5, tz)) continue
            if (solidAt(level, tx, player.y + 0.5, tz) || solidAt(level, tx, player.y + 1.4, tz)) continue
            if (!inBox(Vec3(tx, player.y, tz), 3.0)) continue
            return Vec3(dx, 0.0, dz)
        }
        return Vec3(ax, 0.0, az)
    }

    /** Right dagger in hand with the right attunement for [shield]. True when it is ready to hit. */
    private fun prepareWeapon(player: net.minecraft.client.player.LocalPlayer, shield: String?): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSwap < 150 + (Math.random() * 180).toLong()) return false
        if (shield == null) {
            // No shield (Tier I): any dagger will do, but it has to be a dagger again after the hook / Ragnarock / Wither sword.
            val daggers = SPIRIT_FAMILY + ASHEN_FAMILY
            if (player.mainHandItem.skyblockId in daggers) return true
            val slot = (0 until 9).firstOrNull { player.inventory.getItem(it).skyblockId in daggers } ?: (grindSlot.value.toInt() - 1)
            if (slot == player.inventory.selectedSlot) return true
            player.inventory.setSelectedSlot(slot)
            lastSwap = now
            Debug.line("BlazeMacro", "back to the dagger in slot ${slot + 1}")
            return false
        }
        val family = if (shield == "SPIRIT" || shield == "CRYSTAL") SPIRIT_FAMILY else ASHEN_FAMILY
        val held = player.mainHandItem
        if (held.skyblockId !in family) {
            val slot = (0 until 9).firstOrNull { player.inventory.getItem(it).skyblockId in family } ?: return true
            player.inventory.setSelectedSlot(slot)
            lastSwap = now
            Debug.line("BlazeMacro", "dagger swap to slot ${slot + 1} for $shield")
            return false
        }
        val current = held.loreLines.firstNotNullOfOrNull { ATTUNED.find(it)?.groupValues?.get(1)?.uppercase() }
        if (current != null && current != shield) {
            // Every right click flips the dagger to its other attunement, and the lore (what is read here) lags behind: clicking again
            // before it changed flipped it straight back (5 clicks in 2 s in the log). One click, then wait for the lore to change.
            if (now < attuneUntil && current == attunedFrom) return false
            mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
            attunedFrom = current; attuneUntil = now + 1500
            Debug.line("BlazeMacro", "attune $current -> $shield")
            return false
        }
        return true
    }

    private var attuneUntil = 0L
    private var attunedFrom: String? = null

    // ---- aiming, walking, hitting

    private var lookTarget: Pair<Float, Float>? = null
    private val look = HumanLook()
    private var lastFrame = 0L

    private fun lookAt(player: net.minecraft.client.player.LocalPlayer, point: Vec3) {
        val dx = point.x - player.x; val dz = point.z - player.z; val dy = point.y - player.eyeY
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, hypot(dx, dz))).toFloat()).coerceIn(-90f, 90f)
        // Dead zone: a Blaze bobbing a little must not make the camera twitch after it. Only a real change moves the target.
        val old = lookTarget
        if (old != null && Math.abs(Mth.wrapDegrees(yaw - old.first)) < 3f && Math.abs(pitch - old.second) < 3f) return
        lookTarget = yaw to pitch
    }

    /** Per frame so the turn is smooth at any fps. */
    private fun lookFrame() {
        val player = mc.player ?: return
        val t = lookTarget ?: run { lastFrame = 0L; return }
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1e9f).let { if (it <= 0f || it > 0.1f) 1f / 60f else it }
        lastFrame = now
        look.smoothTimeSeconds = turnSmooth.value.toFloat() / 1000f
        look.maxDegreesPerSecond = turnSpeed.value.toFloat()
        look.jitterDegrees = turnJitter.value.toFloat()
        val (yaw, pitch) = look.step(player.yRot, player.xRot, t.first, t.second, dt)
        // Move the previous-tick rotation by the same amount (what the mouse does), or the camera interpolates between ticks and looks choppy.
        val newPitch = pitch.coerceIn(-90f, 90f)
        player.yRotO += yaw - player.yRot
        player.xRotO += newPitch - player.xRot
        player.yRot = yaw
        player.xRot = newPitch
    }

    private fun engage(player: net.minecraft.client.player.LocalPlayer, e: Entity, reach: Double, attackEvery: Int, minDist: Double = 0.0, leash: Boolean = false, strict: Boolean = false, backMax: Double = 0.0, dodge: Vec3? = null) =
        engageAt(player, e.boundingBox.center, reach, attackEvery, minDist, leash, strict, if (strict) e else null, backMax, dodge)

    private var kiteFrom: Vec3? = null
    private var kiteTick = 0

    /**
     * Faces [point], walks to within [reach] blocks of it (backs off when closer than [minDist]), hits every [attackEvery] ticks
     * once it is in front of you.
     */
    private fun engageAt(player: net.minecraft.client.player.LocalPlayer, point: Vec3, reach: Double, attackEvery: Int, minDist: Double = 0.0, leash: Boolean = false, strict: Boolean = false, expect: Entity? = null, backMax: Double = 0.0, dodge: Vec3? = null) {
        if (Pathfinding.active) pfStop()
        lookAt(player, point)
        val dx = point.x - player.x; val dz = point.z - player.z
        val dist = sqrt(dx * dx + dz * dz)
        // A dead band, so it does not flip between stepping back and stepping in every tick at the limits: it backs off until 0.7 clear of
        // the minimum, and closes in until it is within reach.
        // Demons (backMax): close in, then crouch-walk slowly backwards while hitting; after backMax blocks close in again.
        var inching = false
        if (backMax > 0.0) {
            // The anchor only counts while this ran every tick: the fire dodge / weapon swap in between moved you without inching.
            if (ticks - kiteTick > 2) kiteFrom = null
            kiteTick = ticks
            if (!approachMode) {
                val from = kiteFrom ?: player.position().also { kiteFrom = it }
                if (hypot(player.x - from.x, player.z - from.z) >= backMax) {
                    approachMode = true; kiteFrom = null
                    Debug.line("BlazeMacro", "demon: inched back ${backMax.toInt()} blocks, closing in again")
                } else inching = true
            } else kiteFrom = null
        }
        if (retreatMode) { if (dist > minDist + 0.7) retreatMode = false } else if (backMax == 0.0 && minDist > 0.0 && dist < minDist) retreatMode = true
        if (approachMode) { if (dist <= reach) approachMode = false } else if (dist > reach + 0.8) approachMode = true
        if (state == State.FIGHT && safeFight.value) { retreatMode = false; approachMode = false } // safespot: never leaves it
        if (retreatMode) walkToward(-dx, -dz, sprint = false)
        else if (inching) walkToward(-dx, -dz, sprint = false)
        else if (approachMode) {
            // On the grind it does not follow a Blaze out of the box (that is what dragged it away and made it walk back).
            val len = maxOf(dist, 0.1)
            if (leash && !inBox(player.position().add(dx / len, 0.0, dz / len), 3.0)) releaseMovement() else walkToward(dx, dz, sprint = dist > 6.0)
        } else if (strict && state == State.FIGHT && !safeFight.value && dodge == null && strafeFight.value && inBox(player.position(), 0.0)) strafeInPlace()
        else if (!strict && state == State.GRIND && moveGrind.value && !retreatMode && inBox(player.position(), 0.0)) wanderInPlace()
        else releaseMovement()
        // Standing and hitting: crouch (no stepping about). Not while it is moving back or in.
        // Standing and hitting: crouch. Against the demons it is crouching while it inches back.
        mc.options.keyShift.setDown(strict && crouchFight.value && !retreatMode && !approachMode)
        if (dodge != null) {
            // Off the beam, crouched, circling the target: sideways, plus a pull toward it when it is out of reach.
            val pull = if (dist > reach && dist > 0.1) 0.8 else 0.0
            walkToward(dodge.x + dx / maxOf(dist, 0.1) * pull, dodge.z + dz / maxOf(dist, 0.1) * pull, sprint = false)
        }
        val lt = lookTarget
        val yawOff = if (lt != null) Math.abs(Mth.wrapDegrees(lt.first - player.yRot)) else 999f
        val pitchOff = if (lt != null) Math.abs(lt.second - player.xRot) else 999f
        val facing = lt != null && yawOff < 12f && pitchOff < 15f
        val reachDist = player.eyePosition.distanceTo(point)
        if (!facing) why("not swinging: still aiming (yaw off ${(yawOff / 5).toInt() * 5}, pitch off ${(pitchOff / 5).toInt() * 5} deg)")
        else if (reachDist >= 4.2) why("not swinging: out of range (%.1f blocks, mode ${if (retreatMode) "backing off" else if (approachMode) "closing in" else "holding"})".format(reachDist))
        else if (ticks >= nextAttackTick) {
            // Always swing: a mob in front of the target gets hit too, which is fine.
            (mc as MinecraftAccessor).cedStartAttack()
            nextAttackTick = ticks + attackEvery + (Math.random() * 2).toInt()
            if (!swungTarget) { swungTarget = true; Debug.line("BlazeMacro", "first swing ${System.currentTimeMillis() - targetAt} ms after the target was picked") }
        }
    }

    private var hookYaw = 0f

    /**
     * The Blaze to throw the hook at so the anvil (a straight line of ~18 blocks out, picking up Blazes within ~2.5 blocks of its path)
     * takes the most with it: the candidate whose line has the most Blazes along it, the nearest on a tie.
     */
    private fun bestHookLine(player: net.minecraft.client.player.LocalPlayer, cands: List<LivingEntity>): LivingEntity? {
        val eye = player.eyePosition
        return cands.sortedBy { it.distanceToSqr(player) }.maxByOrNull { c ->
            val dx = c.x - player.x; val dz = c.z - player.z
            val len = hypot(dx, dz).coerceAtLeast(0.1)
            val ux = dx / len; val uz = dz / len
            val n = cands.count { o ->
                val ox = o.x - player.x; val oz = o.z - player.z
                val along = ox * ux + oz * uz
                along in 0.0..18.0 && Math.abs(-ox * uz + oz * ux) <= 2.8 && Math.abs(o.y - player.y) <= 2.0
            }
            n
        }
    }

    private var wanderUntil = 0
    private var wanderDir = 0

    /**
     * Recorded grind (94 s): sprint held 96% of the time, ~3 direction changes a second, W 30% / A 19% / S 30% / D 22% of the ticks,
     * about 1 jump press in 20 s. Eight directions in short bursts, a rare jump.
     */
    private fun wanderInPlace() {
        releaseMovement()
        if (ticks >= wanderUntil) {
            wanderDir = (0..7).random(); wanderUntil = ticks + 5 + (Math.random() * 8).toInt()
            Debug.line("BlazeMacro", "wander dir $wanderDir for ${wanderUntil - ticks} ticks")
        }
        val o = mc.options
        if (wanderDir == 0 || wanderDir == 4 || wanderDir == 5) o.keyUp.setDown(true)
        if (wanderDir == 1 || wanderDir == 6 || wanderDir == 7) o.keyDown.setDown(true)
        if (wanderDir == 2 || wanderDir == 4 || wanderDir == 6) o.keyLeft.setDown(true)
        if (wanderDir == 3 || wanderDir == 5 || wanderDir == 7) o.keyRight.setDown(true)
        o.keySprint.setDown(true)
        val p = mc.player
        if (p != null && p.onGround() && Math.random() < 0.01) o.keyJump.setDown(true)
    }

    private var strafeUntil = 0
    private var strafeDir = 0

    /** Recorded kills: sprint held, crouched, A / D / S in short random bursts (10-25 ticks), almost never W, attack held on and off. */
    private fun strafeInPlace() {
        releaseMovement()
        if (ticks >= strafeUntil) {
            strafeDir = (0..5).random(); strafeUntil = ticks + 10 + (Math.random() * 15).toInt()
            Debug.line("BlazeMacro", "strafe dir $strafeDir for ${strafeUntil - ticks} ticks")
        }
        val o = mc.options
        when (strafeDir) {
            0 -> o.keyLeft.setDown(true)
            1 -> o.keyRight.setDown(true)
            2 -> o.keyDown.setDown(true)
            3 -> { o.keyLeft.setDown(true); o.keyDown.setDown(true) }
            4 -> { o.keyRight.setDown(true); o.keyDown.setDown(true) }
        }
        o.keySprint.setDown(true)
    }

    /** A point just inside the edge of the box nearest to you: walking back goes there, not to the middle. */
    private fun backPoint(): Vec3 {
        // Chosen once per trip back and then kept: it used to follow the player, so the goal kept moving and the walk restarted.
        backGoal?.let { return it }
        val p = mc.player?.position() ?: return spot()
        val half = roamRadius.value / 2.0 - 1.0
        val s = spot()
        return Vec3(p.x.coerceIn(s.x - half, s.x + half), s.y, p.z.coerceIn(s.z - half, s.z + half)).also { backGoal = it }
    }

    private var backGoal: Vec3? = null

    /** The flat square (Roam Box Size) around the spot, [margin] blocks bigger (negative: smaller). Height within 4 blocks of the spot. */
    private fun inBox(p: Vec3, margin: Double): Boolean {
        val half = roamRadius.value / 2.0 + margin
        val s = spot()
        // Height: 8 blocks (a ledge a few blocks up is still the same spot), and not checked at all for a while after a walk back failed
        // (it stood on a ledge the Pathfinder could not get off, and was asked again every tick).
        val heightOk = System.currentTimeMillis() < leashOffUntil || Math.abs(p.y - s.y) <= 8.0
        return Math.abs(p.x - s.x) <= half && Math.abs(p.z - s.z) <= half && heightOk
    }

    /**
     * Moves toward [goal] (kept inside the box): straight walking while you are in the flat box, the Pathfinder module when you are
     * outside it. True: moving, or there.
     */
    private fun moveTo(goal: Vec3, stopDist: Double): Boolean {
        val player = mc.player ?: return false
        val half = roamRadius.value / 2.0
        val s = spot()
        val g = Vec3(goal.x.coerceIn(s.x - half, s.x + half), goal.y, goal.z.coerceIn(s.z - half, s.z + half))
        if (!inBox(player.position(), 3.0)) return goTo(backPoint(), 1.0) // outside the box it only ever walks back in, never chases from out there
        if (Math.abs(g.y - player.y) > 4.0) return false // another level: not something to walk to
        val dx = g.x - player.x; val dz = g.z - player.z
        if (hypot(dx, dz) <= stopDist) { releaseMovement(); return true }
        // A pillar or wall in the straight line (checked for the body's width, knee and head height): the Pathfinder goes round it.
        if (!clearLine(player, g)) return goTo(g, stopDist)
        if (Pathfinding.active) pfStop()
        // No camera turn here: the keys already steer relative to where you face (zigzagging the mouse around looked botty).
        walkToward(dx, dz, sprint = true)
        mc.options.keyJump.setDown(player.onGround() && player.horizontalCollision)
        return true
    }

    /** True when nothing solid is between you and [to] along the body's width (three parallel rays, at knee and head height). */
    private fun clearLine(player: net.minecraft.client.player.LocalPlayer, to: Vec3): Boolean {
        val level = mc.level ?: return true
        val dx = to.x - player.x; val dz = to.z - player.z
        val len = hypot(dx, dz).takeIf { it > 0.01 } ?: return true
        val px = -dz / len * 0.35; val pz = dx / len * 0.35
        for (side in doubleArrayOf(-1.0, 0.0, 1.0)) for (h in doubleArrayOf(0.5, 1.6)) {
            val a = Vec3(player.x + px * side, player.y + h, player.z + pz * side)
            val b = Vec3(to.x + px * side, player.y + h, to.z + pz * side)
            val hit = level.clip(net.minecraft.world.level.ClipContext(a, b, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player))
            if (hit.type != net.minecraft.world.phys.HitResult.Type.MISS) return false
        }
        return true
    }

    /** Generation of the current walk: the callback of a walk that was replaced or cancelled is ignored. */
    private var pfGen = 0
    private var pfGoal: BlockPos? = null
    private var pfFailedGoal: BlockPos? = null
    private var leashOffUntil = 0L
    private var pfFailedAt = 0L

    private fun pfStop() {
        pfGen++; pfGoal = null
        if (Pathfinding.active) Pathfinding.stop(false)
    }

    /**
     * Walks with the Pathfinder module (its planner and walker, the one behind /cedclient path goto).
     * True: walking, or already there. False: it found no route (the same goal is not retried for 4 s).
     */
    private fun goTo(goal: Vec3, stopDist: Double): Boolean {
        val player = mc.player ?: return false
        val block = BlockPos.containing(goal)
        if (player.position().distanceTo(goal) <= stopDist) { pfStop(); return true }
        val now = System.currentTimeMillis()
        if (pfFailedGoal?.let { it.distSqr(block) <= 4.0 } == true && now - pfFailedAt < 4000) return false
        lookTarget = null // the Pathfinder turns the camera itself
        if (Pathfinding.active && pfGoal?.let { it.distSqr(block) <= 4.0 } == true) return true
        if (!Pathfinding.isEnabled) Pathfinding.toggle()
        val gen = ++pfGen
        pfGoal = block
        Debug.line("BlazeMacro", "Pathfinder walk to ${block.toShortString()}")
        return Pathfinding.goTo(block) { ok ->
            if (gen == pfGen) {
                pfGoal = null
                // "Arrived" can still be 5 blocks off (a ledge above the goal): that is a failure too, or it asks again every tick.
                val here = mc.player?.position()
                val close = here != null && here.distanceTo(goal) <= maxOf(stopDist, 3.0)
                if (!ok || !close) {
                    pfFailedGoal = block; pfFailedAt = System.currentTimeMillis(); leashOffUntil = pfFailedAt + 20_000
                    Debug.line("BlazeMacro", "Pathfinder ${if (ok) "stopped ${here?.distanceTo(goal)?.toInt()} blocks short of" else "found no route to"} ${block.toShortString()}: not walking there for 4 s, box leash off for 20 s")
                }
            }
        }
    }

    /** Holds the WASD keys that move toward (dx, dz) from where you face. */
    private fun walkToward(dx: Double, dz: Double, sprint: Boolean) {
        val player = mc.player ?: return
        val diff = Mth.wrapDegrees(Math.toDegrees(atan2(-dx, dz)).toFloat() - player.yRot)
        mc.options.keyUp.setDown(Math.abs(diff) < 67.5f)
        mc.options.keyDown.setDown(Math.abs(diff) > 112.5f)
        mc.options.keyRight.setDown(diff > 22.5f && diff < 157.5f)
        mc.options.keyLeft.setDown(diff < -22.5f && diff > -157.5f)
        mc.options.keySprint.setDown(sprint && Math.abs(diff) < 67.5f)
    }

    private fun releaseMovement() {
        pfStop()
        val o = mc.options
        o.keyUp.setDown(false); o.keyDown.setDown(false); o.keyLeft.setDown(false); o.keyRight.setDown(false)
        o.keyJump.setDown(false); o.keySprint.setDown(false); o.keyShift.setDown(false)
    }
}
//#endif
