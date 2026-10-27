//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.events.SubtitleEvent
import ced.cedclient.events.TitleEvent
import ced.cedclient.events.core.TickEvent
import ced.cedclient.mixin.accessor.MinecraftAccessor
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.HeaderSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.state.IslandState
import ced.cedclient.utils.HumanLook
import ced.cedclient.utils.cleanName
import ced.cedclient.utils.loreLines
import ced.cedclient.utils.skyblockId
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.EntityHitResult

/**
 * Slayer automation (dev + friends only, see DevAccess):
 *  - Auto Steak: holds the Steak Stake as soon as your Vampire boss is steakable (you still hit it yourself).
 *  - Auto Impel: any tier, only during Mania: the "Impel: JUMP / SNEAK / CLICK UP / CLICK DOWN"
 *    subtitle is done for you (smoothly looks up/down + left-clicks, then eases your camera back). Outside Mania you do them.
 *  - Auto Holy Ice: uses Holy Ice ~1 s before your boss's Twinclaws hits (any tier), then switches back.
 *  - Sun Gecko Hazard Ice: after "Sun Gecko used Hazard Zone!", Holy Ice once when the floor under you turns red terracotta.
 *  - Auto Dagger: on the Crimson Isle, looking at a boss, switches to the dagger whose attunement matches the
 *    boss's shield (name tag "ASHEN ♨8" etc.) and right-clicks it once if it's on the other attunement.
 */
object SlayerCheats : Module(
    "Slayer Helper",
    Category.Cheats,
    "Auto Steak Stake swap (Vampire) and auto dagger swap / attunement (Inferno).",
    defaultEnabled = false
) {
    private val autoSteak = BooleanSetting("Auto Steak", true, "Switches to the Steak Stake when your Vampire boss can be steaked.")
    private val steakDelay = NumberSetting("Steak Delay (ms)", 150.0, 0.0, 500.0, 10.0, "Wait this long after the boss becomes steakable before switching.").withDependency { autoSteak.value }
    private val autoImpel = BooleanSetting("Auto Impel", true, "Vampire, during Mania only: does the Impel (jump / sneak / click up / click down).")
    private val maniaWalk = BooleanSetting("Mania Walk", true, "During Mania, walks onto the nearest green terracotta and stays there.")
    private val maniaSneak = BooleanSetting("Mania Sneak", true, "During Mania: crouches from 1 s before Twinclaws hits until 1 s after, so the knockback doesn't push you off the green.")
    private val showWalkTarget = BooleanSetting("Show Walk Target", true, "Yellow box on the green tile Mania Walk / Ichor Twinclaws is walking to.")
    private val ichorWalk = BooleanSetting("Ichor Twinclaws", true, "During Mania: while a Blood Ichor is up, stays on the claw line so the next Twinclaws breaks it.")
    private val autoIce = BooleanSetting("Auto Holy Ice", true, "Uses Holy Ice about 1 s before Twinclaws hits, then switches back.")
    private val iceAt = NumberSetting("Ice At (s left)", 0.8, 0.3, 2.0, 0.1, "Twinclaws seconds left when the Holy Ice is used. 0.8 keeps you ICED through a Mania tick right after the hit.").withDependency { autoIce.value }
    private val iceDelay = NumberSetting("Ice Delay (ms)", 100.0, 0.0, 300.0, 10.0, "Wait after switching to the Holy Ice before using it, and after using it before switching back.")
    private val autoHeal = BooleanSetting("Auto Heal", true, "Stillgore Château only: uses the Healing Melon at around Heal At hearts (random ±1, re-rolled after each use).")
    private val healAt = NumberSetting("Heal At (hearts)", 5.5, 2.0, 13.0, 0.5, "Heals at around this many hearts (random ±1 heart each time). In Mania it heals below 9.5 hearts anyway.").withDependency { autoHeal.value }
    private val nukeIce = BooleanSetting("Sun Gecko Hazard Ice", true, "Holy Ice when you stand on the Hazard Zone area as it turns red.")
    private val autoDagger = BooleanSetting("Auto Dagger", true, "Inferno: switches to the right dagger and attunement for the shield.")
    private val swapDelay = NumberSetting("Swap Delay (ms)", 100.0, 0.0, 300.0, 10.0, "Minimum time between two swaps / clicks.").withDependency { autoDagger.value }

    init {
        addSettings(
            HeaderSetting("Vampire Boss", "Rift Vampire Slayer: steaking your boss."),
            autoSteak, steakDelay,
            HeaderSetting("Vampire Mania", "While your Vampire boss is in Mania: impels, staying on the green tiles, breaking ichors."),
            autoImpel, maniaWalk, maniaSneak, ichorWalk, showWalkTarget,
            HeaderSetting("Holy Ice & Healing", "Item swaps that use an item and switch back: Holy Ice, Healing Melon."),
            autoIce, iceAt, iceDelay, autoHeal, healAt, nukeIce,
            HeaderSetting("Inferno", "Crimson Isle Inferno Demonlord: daggers and attunement."),
            autoDagger, swapDelay,
        )
        on<TickEvent.End> { tick() }
        // "Took 8 (ICED) from Twinclaws!": the hit has landed.
        on<ced.cedclient.events.ChatMessageEvent> { if (it.unformattedText.contains("from Twinclaws")) twinclawsIncoming = false }
        LevelRenderEvents.START_MAIN.register { if (isEnabled) lookFrame() }
        // Impel can come as the title or the subtitle (or "Impel" in one, the action in the other): check both together.
        on<TitleEvent> { lastTitle = it.unformattedText.trim(); lastTitleAt = System.currentTimeMillis(); onImpelText() }
        on<SubtitleEvent> { lastSubtitle = it.unformattedText.trim(); lastSubtitleAt = System.currentTimeMillis(); onImpelText() }
    }

    private val mc get() = Minecraft.getInstance()
    private var lastAction = 0L
    private var steakableSince = 0L

    private fun tick() {
        val player = mc.player ?: return
        if (!isEnabled || mc.gui.screen() != null) { VampireSlayer.walkTarget = null; VampireSlayer.walkPath = emptyList(); releaseSneak(); return stopWalk() }
        precise = false
        heading = null
        headingPath = emptyList()
        routes = null
        arenaYDone = false
        // A Holy Ice use in progress (switch, use, switch back) holds off the other swaps.
        if (iceStep()) { impelTick(); if (!ichorWalk()) maniaWalk(); afterWalk(); return }
        val steakable = autoSteak.value && IslandState.island == IslandState.Island.THE_RIFT && VampireSlayer.bossSteakable()
        if (!steakable) steakableSince = 0L else if (steakableSince == 0L) steakableSince = System.currentTimeMillis()
        if (steakable && System.currentTimeMillis() - steakableSince >= steakDelay.value) {
            val slot = hotbarSlot { it.cleanName.contains("Steak Stake") || it.skyblockId?.contains("STEAK_STAKE") == true }
            if (slot != null && player.inventory.selectedSlot != slot) player.inventory.setSelectedSlot(slot)
        }
        if (autoIce.value && IslandState.island == IslandState.Island.THE_RIFT) holyIce()
        if (nukeIce.value && IslandState.island == IslandState.Island.THE_RIFT) nukeIce()
        if (autoHeal.value && IslandState.island == IslandState.Island.THE_RIFT && ced.cedclient.state.Sidebar.area?.contains("Stillgore", ignoreCase = true) == true) heal()
        if (autoDagger.value && IslandState.island == IslandState.Island.CRIMSON_ISLE) dagger()
        impelTick()
        if (!ichorWalk()) maniaWalk()
        afterWalk()
    }

    /** Tile [holdOn] steered to this tick (null when neither walker ran). */
    private var heading: net.minecraft.core.BlockPos? = null
    /** Tiles still to walk over to [heading], in order (the first is where it walks now). */
    private var headingPath: List<net.minecraft.core.BlockPos> = emptyList()

    private fun afterWalk() {
        maniaSneak()
        VampireSlayer.walkTarget = heading?.takeIf { showWalkTarget.value }
        VampireSlayer.walkPath = if (showWalkTarget.value) headingPath else emptyList()
        maniaDebug()
    }

    // ---- boss debug log (dev account only): "[BossDbg]" lines in latest.log every 5 ticks while your Vampire boss is up, to see why a run died

    private var dbgTicks = 0
    private var dbgHp = -1f
    private var dbgOnGreen = true
    private var dbgHeading: net.minecraft.core.BlockPos? = null

    /** Ticks until the follow-up action bar line after a Holy Ice use ("NOT ENOUGH MANA" shows there), 0 = none. */
    private var iceLogTicks = 0
    private fun actionBar() = (mc.gui as ced.cedclient.mixin.accessor.GuiAccessor).`ced$getOverlayMessage`()?.string?.let { "\"$it\"" }

    private fun maniaDebug() {
        if (!ced.cedclient.utils.DevAccess.isDev || IslandState.island != IslandState.Island.THE_RIFT) return
        if (iceLogTicks > 0 && --iceLogTicks == 0) println("[BossDbg] ICE +0.25s | actionbar=${actionBar()} tc=${VampireSlayer.twinclawsLeft()}")
        val player = mc.player ?: return
        val boss = VampireSlayer.bossEntity() ?: return
        val mania = VampireSlayer.bossInMania()
        val under = net.minecraft.core.BlockPos.containing(player.x, player.y - 0.2, player.z)
        val floor = mc.level?.getBlockState(under)?.block?.let { net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(it).path } ?: "?"
        val onGreen = arenaTile(under) // green below/above the arena level counts as off (log 20:32:05)
        val hp = player.health
        val events = ArrayList<String>()
        if (dbgHp >= 0 && hp < dbgHp - 0.01f) events += "HIT -%.1f".format(dbgHp - hp)
        if (mania && dbgOnGreen && !onGreen) events += "OFF GREEN"
        if (heading != dbgHeading) events += "TARGET ${dbgHeading?.toShortString()} -> ${heading?.toShortString()}"
        dbgHp = hp; dbgOnGreen = onGreen; dbgHeading = heading
        if (events.isEmpty() && ++dbgTicks % 5 != 0) return
        val bd = kotlin.math.sqrt((player.x - boss.x) * (player.x - boss.x) + (player.z - boss.z) * (player.z - boss.z))
        println("[BossDbg] ${events.joinToString(" | ").ifEmpty { "-" }} | mania=$mania bossHp=%.0f/%.0f impel=%s ice=%d hp=%.1f pos=%.2f,%.2f,%.2f floor=%s boss=%.2f,%.2f dist=%.2f tc=%s ichors=%d walk=%s sneak=%s precise=%s target=%s wp=%s path=%d keys=%s%s%s%s".format(
            boss.health, boss.maxHealth, impel, iceStage, hp, player.x, player.y, player.z, floor, boss.x, boss.z, bd, VampireSlayer.twinclawsLeft()?.let { "%.1f".format(it) } ?: "-",
            VampireSlayer.ichorCenters().size, walking, sneaking, precise, heading?.toShortString(), headingPath.firstOrNull()?.toShortString(), headingPath.size,
            if (mc.options.keyUp.isDown) "W" else "", if (mc.options.keyLeft.isDown) "A" else "", if (mc.options.keyDown.isDown) "S" else "", if (mc.options.keyRight.isDown) "D" else ""))
    }

    // ---- holy ice

    /** Iced this Twinclaws already (reset once the countdown is gone). */
    private var iced = false
    /** Slot to go back to after using the ice, -1 when none. */
    private var returnSlot = -1
    /** 0 = idle, 1 = switched to the ice (use it after the delay), 2 = used (switch back after the delay). */
    private var iceStage = 0
    private var iceStageAt = 0L

    /** Switch to the Holy Ice (or [match]) now; [iceStep] uses it and switches back, each step after Ice Delay. */
    private fun startIce(match: (ItemStack) -> Boolean = { it.cleanName.contains("Holy Ice") }): Boolean {
        val player = mc.player ?: return false
        if (iceStage != 0) return false
        val slot = hotbarSlot(match) ?: return false
        val previous = player.inventory.selectedSlot
        player.inventory.setSelectedSlot(slot)  
        returnSlot = if (previous != slot) previous else -1
        iceStage = 1
        iceStageAt = System.currentTimeMillis()
        return true
    }

    /** Your own attacks are dropped while a Holy Ice / Healing Melon swap runs (our impel clicks still go). */
    private var ownClick = false
    fun blocksAttack() = isEnabled && iceStage != 0 && !ownClick

    /** True while an ice use is in progress (the rest of the tick waits). */
    private fun iceStep(): Boolean {
        val player = mc.player ?: return false
        if (iceStage == 0 || System.currentTimeMillis() - iceStageAt < iceDelay.value) return iceStage != 0
        if (iceStage == 1) {
            val result = mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
            if (ced.cedclient.utils.DevAccess.isDev) {
                println("[BossDbg] ICE USE | held=${player.mainHandItem.cleanName} slot=${player.inventory.selectedSlot} result=$result tc=${VampireSlayer.twinclawsLeft()} actionbar=${actionBar()}")
                iceLogTicks = 5
            }
            iceStage = 2
        } else {
            if (returnSlot >= 0) player.inventory.setSelectedSlot(returnSlot)
            returnSlot = -1
            iceStage = 0
        }
        iceStageAt = System.currentTimeMillis()
        return true
    }

    private fun holyIce() {
        val left = VampireSlayer.twinclawsLeft()
        if (left == null) { iced = false; return }
        if (iced || left > iceAt.value) return
        if (startIce()) iced = true
    }

    // ---- auto heal

    private var healedAt = 0L
    // Random ±1 heart around Heal At, re-rolled after every melon, so it doesn't always fire at the same HP.
    private var healOffset = kotlin.random.Random.nextDouble(-1.0, 1.0)


    private fun heal() {
        val player = mc.player ?: return
        // 1 s between uses so one low moment doesn't eat several melons.
        // In Mania one tick off the green is 15 damage: stay above 15 + a boss hit (log 20:19:16: died at 15.3 HP).
        val healBelow = ((healAt.value + healOffset) * 2).toFloat() // hearts -> HP
        val below = if (VampireSlayer.bossInMania()) maxOf(healBelow, 19f) else healBelow
        if (player.health > below || System.currentTimeMillis() - healedAt < 1_000) return
        if (startIce { it.cleanName.contains("Healing Melon") }) {
            healedAt = System.currentTimeMillis()
            healOffset = kotlin.random.Random.nextDouble(-1.0, 1.0)
        }
    }

    // ---- sun gecko hazard zone

    private var nukeIcedAt = 0L

    /** Hazard Zone marks part of the arena in terracotta; standing on it as it turns red means you get hit. */
    private fun nukeIce() {
        val player = mc.player ?: return
        val level = mc.level ?: return
        if (!SunGecko.nukeRecent() || System.currentTimeMillis() - nukeIcedAt < 5_000) return
        val below = player.blockPosition().below()
        // Spirit Beam repaints a ~4x4 under you, so then look 3 blocks around instead of only straight down.
        val r = if (SunGecko.beamRecent()) 3 else 0
        val red = (-r..r).any { dx -> (-r..r).any { dz -> (0..2).any { dy ->
            level.getBlockState(below.offset(dx, -dy, dz)).`is`(net.minecraft.world.level.block.Blocks.RED_TERRACOTTA) } } }
        if (!red) return
        if (startIce()) nukeIcedAt = System.currentTimeMillis()
    }

    // ---- mania walk

    private var walking = false
    /** Mania Walk never picks a tile closer than this (blocks) to the boss. Set in game with "/cc dev maniadist <blocks>"; not saved. */
    var maniaMinDist = 3.0
    /** The tile we're heading to / holding on; kept until it stops being green so we don't keep re-picking. */
    private var maniaTarget: net.minecraft.core.BlockPos? = null
    private val walkKeys get() = listOf(mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight)
    private fun green(pos: net.minecraft.core.BlockPos) =
        mc.level?.getBlockState(pos)?.`is`(net.minecraft.world.level.block.Blocks.GREEN_TERRACOTTA) == true

    /**
     * Mania: on green, stays centred on that tile; off green (it changed, or knockback), walks (WASD, camera left
     * alone) to the nearest green you can reach.
     */
    private fun maniaWalk() {
        val player = mc.player ?: return stopWalk()
        if (!maniaWalk.value || IslandState.island != IslandState.Island.THE_RIFT || !VampireSlayer.bossInMania()) { maniaTarget = null; return stopWalk() }
        // In the air (Impel JUMP, knockback): the floor reads as air, which is NOT "off green". Keep the tile you had
        // and steer to it (log 19:30:46: a jump made it switch tiles right as Twinclaws hit).
        if (!player.onGround()) maniaTarget?.takeIf { green(it) }?.let { return holdOn(it) }
        val under = net.minecraft.core.BlockPos.containing(player.x, player.y - 0.2, player.z)
        val onGreen = arenaTile(under) // green below/above the arena level counts as off (log 20:32:05)
        // On green: stay on this tile (centred). Moving to a "better" tile while safe is what got you killed (log
        // 17:35): it walked off the edge onto stone / flip-flopped at the edge until Mania ticked.
        // Off green (tile changed, knocked off): the nearest green, kept until it's gone or you're on green again.
        // Twinclaws knocks you ~1 block away from the boss and Mania checks ~0.7 s later, while you're still in the
        // air (log 17:45): so while it charges, stand on a tile with green behind it (reached over green, within 5).
        val boss = VampireSlayer.bossEntity()
        val charging = VampireSlayer.twinclawsLeft() != null && boss != null
        // The Twinclaws move only goes outward: never nearer the boss than your tile or than maniadist (log 19:30:37:
        // it picked a tile 3 blocks from the boss and ended up 1.6 away).
        val bd = { p: net.minecraft.core.BlockPos -> boss?.distanceToSqr(p.x + 0.5, boss.y, p.z + 0.5) ?: 0.0 }
        val outward = { p: net.minecraft.core.BlockPos -> bd(p) >= maxOf(bd(under) - 0.01, maniaMinDist * maniaMinDist) }
        val target = when {
            !onGreen -> maniaTarget?.takeIf { green(it) && canGo(it) } ?: nearestGreen(player)
            // Calm: get onto the north-south / east-west line through the boss. The green there is mostly 3 wide, so
            // the Twinclaws knockback lands on green (off the lines it's often 1-2 wide: log 20:19:16). Only over green,
            // one tile at a time (routes), never inside maniadist; stays on a line tile once there.
            !charging -> if (boss == null) under else {
                val onLine = { p: net.minecraft.core.BlockPos -> axisOff(boss, p) < 1.5 && bd(p) >= maniaMinDist * maniaMinDist }
                maniaTarget?.takeIf { green(it) && it in routes() && onLine(it) }
                    ?: under.takeIf(onLine)
                    ?: routes().keys.filter(onLine)
                        .minByOrNull { player.distanceToSqr(it.x + 0.5, player.y, it.z + 0.5) + 4 * axisOff(boss, it) }
                    ?: under
            }
            else -> maniaTarget?.takeIf { it in routes() && room(boss!!, it) && (it == under || outward(it)) }
                ?: under.takeIf { room(boss!!, it) }
                ?: routes().keys.filter { room(boss!!, it) && outward(it) && player.distanceToSqr(it.x + 0.5, player.y, it.z + 0.5) <= 25.0 }
                    .minByOrNull { player.distanceToSqr(it.x + 0.5, player.y, it.z + 0.5) }
                ?: under
        }
        maniaTarget = target
        if (target == null) return stopWalk()
        holdOn(target)
    }

    /** Set while walking the last stretch onto a tile: sneak then (see [maniaSneak]) so you stop on it, not past it. */
    private var precise = false

    /**
     * Walks onto the middle of [tile] and stays there. Stops within 0.25 of the middle; once stopped it only moves
     * again when you're less than 0.3 inside the tile's edge (a tile's edge is 0.5 from its middle).
     */
    private fun holdOn(tile: net.minecraft.core.BlockPos) {
        val player = mc.player ?: return
        heading = tile
        // Not straight there (the boss is in the way): walk the green route, one waypoint at a time.
        val route = routeTo(tile) ?: listOf(tile)
        headingPath = route
        val wp = route.first()
        if (wp != tile) return walkToward(wp.x + 0.5 - player.x, wp.z + 0.5 - player.z)
        // (No more "stand 0.3 toward the boss" during Twinclaws: that nudge overshot onto redstone, log 19:56:14.)
        val dx = tile.x + 0.5 - player.x
        val dz = tile.z + 0.5 - player.z
        val d = kotlin.math.sqrt(dx * dx + dz * dz)
        val under = net.minecraft.core.BlockPos.containing(player.x, player.y - 0.2, player.z)
        // Stop within 0.25 of the middle; once stopped, stay put while still on the tile within 0.35 of the middle.
        // (Restarting tighter than the stop point made it twitch: lots of tiny walks + crouches.)
        val settled = under.x == tile.x && under.z == tile.z && kotlin.math.abs(dx) < 0.35 && kotlin.math.abs(dz) < 0.35
        if (d < 0.25 || (!walking && settled)) return stopWalk()
        // Crouch-in only for a real step between green tiles (0.5+ away), never off the green (Mania hits for 15).
        if (d in 0.5..1.2 && green(under)) precise = true
        walkToward(dx, dz)
    }

    /** Holds the WASD keys that move toward (dx, dz) from where you face; the camera is left alone. */
    private fun walkToward(dx: Double, dz: Double) {
        val player = mc.player ?: return
        val wanted = Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
        val diff = net.minecraft.util.Mth.wrapDegrees(wanted - player.yRot)
        mc.options.keyUp.setDown(kotlin.math.abs(diff) < 67.5f)
        mc.options.keyDown.setDown(kotlin.math.abs(diff) > 112.5f)
        mc.options.keyRight.setDown(diff > 22.5f && diff < 157.5f)
        mc.options.keyLeft.setDown(diff < -22.5f && diff > -157.5f)
        walking = true
    }

    /**
     * Twinclaws fires one claw at you and one straight opposite, and only a claw breaks a Blood Ichor. So the whole
     * time a Blood Ichor is up in Mania, stand on a green tile on the line through the boss and the ichor (either side, any
     * distance): then one of the claws runs along it. The nearest such tile wins.
     * Returns true when it is steering this tick (Mania Walk then waits).
     */
    private fun ichorWalk(): Boolean {
        val player = mc.player ?: return false
        if (!ichorWalk.value || IslandState.island != IslandState.Island.THE_RIFT) return false
        // Mania only (outside it you move yourself, with Ichor Stand Spots showing where).
        if (!VampireSlayer.bossInMania()) return false
        val boss = VampireSlayer.bossEntity() ?: return false
        // Unit directions boss -> each ichor.
        val lines = VampireSlayer.ichorCenters().mapNotNull { c ->
            val ix = c.x - boss.x; val iz = c.z - boss.z
            val len = kotlin.math.sqrt(ix * ix + iz * iz).takeIf { it > 0.1 } ?: return@mapNotNull null
            (ix / len) to (iz / len)
        }
        if (lines.isEmpty()) return false
        // In the air (jump / knockback): keep steering to the tile we had, don't hand over to Mania Walk's old one.
        if (!player.onGround()) ichorTarget?.takeIf { green(it) }?.let { holdOn(it); return true }
        // Off the green: Mania Walk runs you to the nearest green first (Mania ticks for 15), then this takes over.
        if (!green(net.minecraft.core.BlockPos.containing(player.x, player.y - 0.2, player.z))) return false
        // Nearest green tile within 12 blocks whose middle is on a line (within 0.7 sideways), not nearer the boss
        // than maniadist. None on the line: Mania Walk keeps you on the green instead.
        val near = maniaMinDist
        fun onLine(pos: net.minecraft.core.BlockPos): Boolean {
            if (!arenaTile(pos)) return false
            val px = pos.x + 0.5 - boss.x; val pz = pos.z + 0.5 - boss.z
            return lines.any { (ux, uz) ->
                kotlin.math.abs(px * ux + pz * uz) >= near && kotlin.math.abs(px * uz - pz * ux) < 0.7
            }
        }
        // Keep the tile we're on / heading to while it still qualifies, so it doesn't flip between tiles.
        // Only line tiles you can reach without leaving the green.
        val tile = ichorTarget?.takeIf { onLine(it) && it in routes() } ?: run {
            val floor = player.blockPosition().below()
            val candidates = ArrayList<net.minecraft.core.BlockPos>()
            for (ox in -12..12) for (oz in -12..12) for (dy in -2..1) {
                val pos = floor.offset(ox, dy, oz)
                if (onLine(pos)) candidates.add(pos)
            }
            val d = { p: net.minecraft.core.BlockPos -> player.distanceToSqr(p.x + 0.5, player.y, p.z + 0.5) }
            // Nearest you can walk to, with green behind it for the knockback; else just the nearest you can walk to.
            val sorted = candidates.sortedBy(d)
            sorted.firstOrNull { room(boss, it) && it in routes() } ?: sorted.firstOrNull { it in routes() }
        }
        ichorTarget = tile
        if (tile == null) return false
        holdOn(tile)
        return true
    }

    private var ichorTarget: net.minecraft.core.BlockPos? = null

    /**
     * Can you walk straight to [tile] with WASD (no jumping)? Checks every 0.3 blocks along the way, across your
     * width: nothing higher than a step (0.6) at your feet and nothing at head height. Walls / fences / raised
     * tiles mean no, so the walkers never pick a tile they'd just run into a wall trying to reach.
     */
    private fun reachable(tile: net.minecraft.core.BlockPos): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false
        val tx = tile.x + 0.5 - player.x; val tz = tile.z + 0.5 - player.z
        val len = kotlin.math.sqrt(tx * tx + tz * tz)
        if (len < 0.3) return true
        val ux = tx / len; val uz = tz / len
        // Don't walk past the boss: a point within [bossAvoid] of it that's nearer than you are now blocks the line.
        val boss = VampireSlayer.bossEntity()
        val avoid = bossAvoid()
        val nowSq = boss?.let { (player.x - it.x) * (player.x - it.x) + (player.z - it.z) * (player.z - it.z) } ?: 0.0
        fun blocked(x: Double, y: Double, z: Double, maxTop: Double): Boolean {
            val p = net.minecraft.core.BlockPos.containing(x, y, z)
            val shape = level.getBlockState(p).getCollisionShape(level, p)
            return !shape.isEmpty && p.y + shape.max(net.minecraft.core.Direction.Axis.Y) > maxTop
        }
        var s = 0.3
        while (true) {
            val t = minOf(s, len)
            if (boss != null) {
                val bx = player.x + ux * t - boss.x; val bz = player.z + uz * t - boss.z
                val sq = bx * bx + bz * bz
                if (sq < avoid * avoid && sq < nowSq) return false
            }
            for (off in doubleArrayOf(-0.3, 0.0, 0.3)) {
                val x = player.x + ux * t - uz * off
                val z = player.z + uz * t + ux * off
                if (blocked(x, player.y + 0.01, z, player.y + 0.6)) return false
                if (blocked(x, player.y + 1.0, z, player.y + 1.0) || blocked(x, player.y + 1.7, z, player.y + 1.7)) return false
            }
            if (t >= len) return true
            s += 0.3
        }
    }

    /** How close to the boss the walkers may pass: half a block inside maniadist, at least 1.5. */
    private fun bossAvoid() = (maniaMinDist - 0.5).coerceAtLeast(1.5)

    /** Green tiles you can walk to over green from the one you stand on (each mapped to the tile before it). Once per tick. */
    private var routes: HashMap<net.minecraft.core.BlockPos, net.minecraft.core.BlockPos?>? = null

    private fun routes(): Map<net.minecraft.core.BlockPos, net.minecraft.core.BlockPos?> {
        routes?.let { return it }
        val map = HashMap<net.minecraft.core.BlockPos, net.minecraft.core.BlockPos?>()
        routes = map
        val player = mc.player ?: return map
        val under = net.minecraft.core.BlockPos.containing(player.x, player.y - 0.2, player.z)
        val start = listOf(under, under.below()).firstOrNull { green(it) } ?: return map
        val boss = VampireSlayer.bossEntity()
        val avoid = bossAvoid()
        map[start] = null
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty() && map.size < 1500) {
            val p = queue.removeFirst()
            for (n in listOf(p.north(), p.south(), p.east(), p.west())) for (dy in -1..1) {
                val q = n.above(dy)
                if (q in map || !arenaTile(q)) continue
                if (boss != null && boss.distanceToSqr(q.x + 0.5, boss.y, q.z + 0.5) < avoid * avoid) continue
                map[q] = p
                queue.addLast(q)
            }
        }
        return map
    }

    /** You can get to [tile]: straight, or along the green around the boss. */
    private fun canGo(tile: net.minecraft.core.BlockPos) = reachable(tile) || tile in routes()

    /**
     * Tiles to walk over to get to [tile], in order (the first is where to walk now). Reachable over green: one
     * neighbouring green tile at a time, so it never cuts a corner over stone (log 17:36). Otherwise (you're off
     * the green) straight there if the line is clear.
     */
    private fun routeTo(tile: net.minecraft.core.BlockPos): List<net.minecraft.core.BlockPos>? {
        val parents = routes()
        if (tile in parents) {
            val path = generateSequence(tile) { parents[it] }.toList() // tile ... the tile you're on
            return if (path.size <= 1) listOf(tile) else path.dropLast(1).reversed()
        }
        return if (reachable(tile)) listOf(tile) else null
    }

    /** Room to get knocked back: the 2 blocks behind [p] (away from the boss) are green too. */
    private fun room(boss: net.minecraft.world.entity.Entity, p: net.minecraft.core.BlockPos): Boolean {
        val ax = p.x + 0.5 - boss.x; val az = p.z + 0.5 - boss.z
        val len = kotlin.math.sqrt(ax * ax + az * az).takeIf { it > 0.1 } ?: return false
        return (1..2).all { s ->
            val b = net.minecraft.core.BlockPos.containing(p.x + 0.5 + ax / len * s, p.y.toDouble(), p.z + 0.5 + az / len * s)
            (-1..1).any { dy -> green(b.above(dy)) }
        }
    }

    /**
     * The arena floor level: the height most green tiles around you are at (once per tick). Green patches above or
     * below it are ignored, so it never walks you down a level it can't climb back from (log 20:32:05).
     */
    private var arenaYDone = false
    private var arenaYValue: Int? = null
    private fun arenaY(): Int? {
        if (arenaYDone) return arenaYValue
        arenaYDone = true
        val floor = mc.player?.blockPosition()?.below() ?: return null.also { arenaYValue = null }
        val counts = HashMap<Int, Int>()
        for (dx in -12..12) for (dz in -12..12) for (dy in -3..2) {
            val pos = floor.offset(dx, dy, dz)
            if (green(pos)) counts.merge(pos.y, 1, Int::plus)
        }
        arenaYValue = counts.maxByOrNull { it.value }?.key
        return arenaYValue
    }

    /** A green tile on the arena floor level. */
    private fun arenaTile(pos: net.minecraft.core.BlockPos) = green(pos) && arenaY().let { it == null || pos.y == it }

    /** How far [p]'s middle is from the nearer of the north-south / east-west lines through the boss. */
    private fun axisOff(boss: net.minecraft.world.entity.Entity, p: net.minecraft.core.BlockPos) =
        minOf(kotlin.math.abs(p.x + 0.5 - boss.x), kotlin.math.abs(p.z + 0.5 - boss.z))

    /** Nearest green tile within 12 blocks that you can walk to (straight, or over green). */
    private fun nearestGreen(player: net.minecraft.world.entity.player.Player): net.minecraft.core.BlockPos? {
        val floor = player.blockPosition().below()
        val greens = ArrayList<net.minecraft.core.BlockPos>()
        for (dx in -12..12) for (dz in -12..12) for (dy in -2..1) {
            val pos = floor.offset(dx, dy, dz)
            if (arenaTile(pos)) greens.add(pos)
        }
        return greens.sortedBy { player.distanceToSqr(it.x + 0.5, player.y, it.z + 0.5) }.firstOrNull { canGo(it) }
    }

    private fun stopWalk() {
        if (!walking) return
        walkKeys.forEach { it.setDown(false) }
        walking = false
    }

    // ---- mania sneak

    private var sneaking = false
    /** Last tick Twinclaws was still counting down; the knockback lands right after it's gone. */
    /** Twinclaws counted down and its "Took .. from Twinclaws!" hasn't come yet. */
    private var twinclawsIncoming = false
    private var twinclawsSeenAt = 0L

    /**
     * Crouching cuts Twinclaws knockback: hold sneak from 1 s before the hit until 1 s after, during Mania.
     * While Twinclaws charges and a walker is still moving you to a spot, no sneak at all: full speed to get there.
     */
    private fun maniaSneak() {
        val left = VampireSlayer.twinclawsLeft()
        val now = System.currentTimeMillis()
        if (left != null) { twinclawsSeenAt = now; twinclawsIncoming = true }
        val want = maniaSneak.value && IslandState.island == IslandState.Island.THE_RIFT && VampireSlayer.bossInMania() &&
            !(left != null && walking) &&
            // Knocked off the green and walking back: never crouch (slow) or a Mania tick (15) can land first.
            !(walking && mc.player?.let { green(net.minecraft.core.BlockPos.containing(it.x, it.y - 0.2, it.z)) } != true) &&
            (precise || if (left != null) left <= 1.0f else now - twinclawsSeenAt < 1_000)
        // An Impel SNEAK owns the shift key while it runs (it may be letting go to crouch fresh).
        if (impel == Impel.SNEAK) { sneaking = sneaking || want; return }
        if (want) { mc.options.keyShift.setDown(true); sneaking = true } else releaseSneak()
    }

    private fun releaseSneak() {
        if (!sneaking) return
        // An Impel SNEAK in progress keeps the key; it lets go itself.
        if (impel != Impel.SNEAK) mc.options.keyShift.setDown(false)
        sneaking = false
    }

    // ---- impels

    private enum class Impel { JUMP, SNEAK, CLICK_UP, CLICK_DOWN }
    private var impel: Impel? = null
    private var impelTicks = 0
    private var savedPitch = 0f
    private var lastImpel: Impel? = null
    private var lastImpelAt = 0L

    private var lastTitle = ""
    private var lastTitleAt = 0L
    private var lastSubtitle = ""
    private var lastSubtitleAt = 0L

    private fun onImpelText() {
        val now = System.currentTimeMillis()
        // Newest first, so a fresh action wins over a stale one in the other slot.
        val pieces = listOf(lastTitle to lastTitleAt, lastSubtitle to lastSubtitleAt)
            .filter { now - it.second < 1500 }.sortedByDescending { it.second }.map { it.first.uppercase() }
        val text = pieces.joinToString(" ")
        if (!isEnabled || !autoImpel.value || "IMPEL" !in text) return
        // Mania itself is the gate (no tier check: an exact-max-HP tier miss silently blocked it).
        if (IslandState.island != IslandState.Island.THE_RIFT || !VampireSlayer.bossInMania()) return
        val type = pieces.firstNotNullOfOrNull { p ->
            when {
                "CLICK UP" in p -> Impel.CLICK_UP
                "CLICK DOWN" in p -> Impel.CLICK_DOWN
                "SNEAK" in p -> Impel.SNEAK
                "JUMP" in p -> Impel.JUMP
                else -> null
            }
        } ?: return
        // Twinclaws about to hit: its knockback throws you in the air anyway (that counts as the jump), and jumping
        // yourself just makes you land later / further off the green.
        // Also while you're still in the air from the knockback. Once you've landed it must jump (log 19:49:07: skipped
        // ~1 s after the hit while already on the ground -> "You failed to complete an impel!", 20 damage).
        // A skip doesn't count as done: the repeating subtitle jumps once you're on the ground and Twinclaws is past.
        val onGround = mc.player?.onGround() == true
        // The countdown disappears ~0.4 s BEFORE the hit lands (log 20:12:16: jumped in that gap, the knockback threw
        // you 2 blocks off the green). So it counts as incoming until "Took .. from Twinclaws!" (1.5 s fallback).
        val incoming = twinclawsIncoming && System.currentTimeMillis() - twinclawsSeenAt < 1_500
        if (type == Impel.JUMP && ((VampireSlayer.twinclawsLeft() ?: 99f) <= 1.5f || incoming || !onGround)) {
            if (System.currentTimeMillis() - jumpSkipLoggedAt >= 1000 && ced.cedclient.utils.DevAccess.isDev) {
                println("[BossDbg] JUMP IMPEL SKIPPED | tc=${VampireSlayer.twinclawsLeft()} onGround=$onGround sinceHit=${System.currentTimeMillis() - twinclawsSeenAt}ms")
                jumpSkipLoggedAt = System.currentTimeMillis()
            }
            return
        }
        // The subtitle repeats with its countdown: once per impel (same type again only after 2 s).
        if (impel == type || (lastImpel == type && System.currentTimeMillis() - lastImpelAt < 2000)) return
        lastImpel = type
        lastImpelAt = System.currentTimeMillis()
        endImpel()
        impel = type
        // Still easing back from the last click impel: the pitch to return to is that one, not the current.
        savedPitch = if (lookPitch != null) savedPitch else mc.player?.xRot ?: 0f
        if (type == Impel.CLICK_UP || type == Impel.CLICK_DOWN) {
            lookPitch = if (type == Impel.CLICK_UP) -90f else 90f
            look = HumanLook(smoothTimeSeconds = 0.07f, maxDegreesPerSecond = 1400f, jitterDegrees = 0.4f)
            clicks = 0
            impelTicks = 30 // give up after 1.5 s
        } else impelTicks = 6
        // Hypixel only counts a NEW crouch: if shift is already down (Mania Sneak), let go 2 ticks first (log 17:45:31).
        sneakRelease = if (type == Impel.SNEAK && mc.options.keyShift.isDown) 2 else 0
        if (sneakRelease > 0) impelTicks += sneakRelease
    }

    private var jumpSkipLoggedAt = 0L

    /** Ticks left to hold shift UP before an Impel SNEAK presses it. */
    private var sneakRelease = 0

    private fun impelTick() {
        val type = impel ?: return
        val player = mc.player ?: return endImpel()
        when (type) {
            Impel.JUMP -> mc.options.keyJump.setDown(true)
            Impel.SNEAK -> if (sneakRelease > 0) { sneakRelease--; mc.options.keyShift.setDown(false) } else mc.options.keyShift.setDown(true)
            Impel.CLICK_UP, Impel.CLICK_DOWN -> {
                // Click once the smooth turn is (nearly) there; two clicks, then ease back.
                val target = lookPitch ?: return endImpel()
                if (kotlin.math.abs(player.xRot - target) < 6f && impelTicks % 2 == 0) {
                    ownClick = true; (mc as MinecraftAccessor).cedStartAttack(); ownClick = false
                    if (++clicks >= 2) return endImpel()
                }
            }
        }
        if (--impelTicks <= 0) endImpel()
    }

    // ---- smooth look (per frame, so the turn is smooth at any fps)

    /** Pitch being eased to (an impel click, or back to where you were), null when idle. */
    private var lookPitch: Float? = null
    private var look = HumanLook()
    private var clicks = 0
    private var lastFrameNanos = 0L

    private fun lookFrame() {
        val player = mc.player
        val target = lookPitch
        if (player == null || target == null) { lastFrameNanos = 0L; return }
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((now - lastFrameNanos) / 1e9f).let { if (it <= 0f || it > 0.1f) 1f / 60f else it }
        lastFrameNanos = now
        val (_, pitch) = look.step(player.yRot, player.xRot, player.yRot, target, dt)
        player.xRot = pitch.coerceIn(-90f, 90f)
        // Back where you were: done.
        if (impel == null && kotlin.math.abs(player.xRot - target) < 0.5f) { player.xRot = target; lookPitch = null }
    }

    private fun endImpel() {
        when (impel) {
            Impel.JUMP -> mc.options.keyJump.setDown(false)
            Impel.SNEAK -> mc.options.keyShift.setDown(false)
            Impel.CLICK_UP, Impel.CLICK_DOWN -> lookPitch = savedPitch // eased back by [lookFrame]
            null -> {}
        }
        impel = null
    }

    override fun onDisable() { endImpel(); lookPitch = null; stopWalk(); iceStage = 0; returnSlot = -1 }

    private fun hotbarSlot(match: (ItemStack) -> Boolean): Int? =
        (0 until 9).firstOrNull { i -> mc.player!!.inventory.getItem(i).let { !it.isEmpty && match(it) } }

    // ---- daggers

    private enum class Dagger(val ids: Set<String>, val attunements: List<String>) {
        TWILIGHT(setOf("HEARTMAW_DAGGER", "BURSTMAW_DAGGER", "MAWDUST_DAGGER"), listOf("SPIRIT", "CRYSTAL")),
        FIREDUST(setOf("HEARTFIRE_DAGGER", "BURSTFIRE_DAGGER", "FIREDUST_DAGGER"), listOf("ASHEN", "AURIC")),
    }

    private val SHIELD = Regex("""\b(ASHEN|SPIRIT|CRYSTAL|AURIC)\b""")
    private val ATTUNED = Regex("""Attuned:\s*(\w+)""", RegexOption.IGNORE_CASE)

    private fun daggerOf(stack: ItemStack) = stack.skyblockId?.let { id -> Dagger.entries.firstOrNull { id in it.ids } }

    /** The attunement shown on the dagger itself ("Attuned: Spirit" in its lore). */
    private fun attunementOf(stack: ItemStack) =
        stack.loreLines.firstNotNullOfOrNull { ATTUNED.find(it)?.groupValues?.get(1)?.uppercase() }

    private fun dagger() {
        val player = mc.player ?: return
        val now = System.currentTimeMillis()
        if (now - lastAction < swapDelay.value) return
        val target = (mc.hitResult as? EntityHitResult)?.entity ?: return
        // The shield is on one of the name tags stacked over the boss.
        val shield = mc.level?.entitiesForRendering()
            ?.filter { it is ArmorStand && it.hasCustomName() && it.distanceTo(target) < 5f }
            ?.firstNotNullOfOrNull { SHIELD.find(it.customName!!.string)?.groupValues?.get(1) } ?: return
        val dagger = Dagger.entries.firstOrNull { shield in it.attunements } ?: return
        val held = player.mainHandItem
        if (daggerOf(held) != dagger) {
            // Only take over while you are already fighting with a dagger in hand.
            if (daggerOf(held) == null) return
            val slot = hotbarSlot { daggerOf(it) == dagger } ?: return
            player.inventory.setSelectedSlot(slot)
            lastAction = now
            return
        }
        val current = attunementOf(held) ?: return
        if (current != shield) {
            mc.gameMode?.useItem(player, InteractionHand.MAIN_HAND)
            lastAction = now + 250 // give the lore time to update before trying again
        }
    }
}
//#endif
