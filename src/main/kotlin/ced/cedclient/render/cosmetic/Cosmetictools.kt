package ced.cedclient.render.cosmetic

import ced.cedclient.features.impl.render.ActionBarHud
//#if CHEAT
import ced.cedclient.features.impl.render.Freecam
//#endif
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
//#if CHEAT
import net.minecraft.world.level.ClipContext
//#endif
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * The bulk tools from the cb mod: World Edit region (pos1 / pos2, fill / replace / remove), flood fill,
 * and the ruler (straight lines of blocks, also through the air). All of them go through
 * [CosmeticStore.applyChanges], so each operation is one undo step.
 */
object CosmeticTools {
    private val mc get() = Minecraft.getInstance()

    // ------------------------------------------------------------------
    // Region selection
    // ------------------------------------------------------------------

    var pos1: BlockPos? = null
        private set
    var pos2: BlockPos? = null
        private set

    fun setPos1(pos: BlockPos) { pos1 = pos; CosmeticChat.say("Pos1 set to ${pos.x}, ${pos.y}, ${pos.z}") }
    fun setPos2(pos: BlockPos) { pos2 = pos; CosmeticChat.say("Pos2 set to ${pos.x}, ${pos.y}, ${pos.z}") }
    fun clearSelection() { pos1 = null; pos2 = null }

    fun selectionInfo(): String {
        val a = pos1
        val b = pos2
        if (a == null && b == null) return "No selection set"
        if (a == null) return "Pos1 not set (Pos2: ${b!!.x}, ${b.y}, ${b.z})"
        if (b == null) return "Pos2 not set (Pos1: ${a.x}, ${a.y}, ${a.z})"
        val sx = Math.abs(a.x - b.x) + 1
        val sy = Math.abs(a.y - b.y) + 1
        val sz = Math.abs(a.z - b.z) + 1
        return "${sx}x${sy}x$sz (${sx * sy * sz} blocks) | P1 ${a.x},${a.y},${a.z} | P2 ${b.x},${b.y},${b.z}"
    }

    /** Largest region an edit may touch: the Max Region Size setting. */
    private val maxRegion get() = CosmeticConfig.maxRegion

    private inline fun forRegion(fn: (BlockPos) -> Unit): String? {
        val a = pos1 ?: return "§cPos1 not set"
        val b = pos2 ?: return "§cPos2 not set"
        val vol = (Math.abs(a.x - b.x) + 1L) * (Math.abs(a.y - b.y) + 1L) * (Math.abs(a.z - b.z) + 1L)
        if (vol > maxRegion) return "§cSelection too big ($vol blocks, max $maxRegion - raise Max Region Size in the settings)"
        for (p in BlockPos.betweenClosed(a, b)) fn(p.immutable())
        return null
    }

    /** Fills the selection with [specs] ("stone" or weighted "stone:3 andesite:1"). Air gets phantom blocks. */
    fun fill(specs: String, shape: String? = null): String {
        val blocks = CosmeticCodec.parseWeighted(specs) ?: return "§cInvalid block specification"
        val level = mc.level ?: return "§cNot in a world"
        val changes = ArrayList<Pair<BlockPos, String?>>()
        forRegion { p ->
            val air = level.getBlockState(p).isAir
            changes += p to CosmeticCodec.buildRaw(CosmeticCodec.serialize(CosmeticCodec.pickWeighted(blocks)), air, shape)
        }?.let { return it }
        CosmeticStore.applyChanges(changes)
        return "§aFilled ${changes.size} blocks with ${describe(blocks)}"
    }

    /** Replaces every block in the selection that currently looks like [from] (real or cosmetic) with [toSpecs]. */
    fun replace(from: String, toSpecs: String): String {
        val a = pos1 ?: return "§cPos1 not set"
        val b = pos2 ?: return "§cPos2 not set"
        return replaceBetween(a, b, from, toSpecs)
    }

    const val DEFAULT_NEAR_RADIUS = 8
    /** Biggest replacenear radius whose cube fits in Max Region Size. */
    fun maxNearRadius(): Int = ((Math.cbrt(maxRegion.toDouble()) - 1) / 2).toInt().coerceAtLeast(1)

//#if CHEAT
    /** [replace] in a cube of [radius] blocks around the player (the camera in Freecam) instead of the selection. */
//#else
//$    /** [replace] in a cube of [radius] blocks around the player instead of the selection. */
//#endif
    fun replaceNear(radius: Int, from: String, toSpecs: String): String {
//#if CHEAT
        val eye = aimEye() ?: return "§cNot in a world"
//#else
//$        val player = mc.player ?: return "§cNot in a world"
//#endif
        val r = radius.coerceIn(1, maxNearRadius())
//#if CHEAT
        val c = if (Freecam.isActive) BlockPos.containing(eye) else mc.player?.blockPosition() ?: return "§cNot in a world"
//#else
//$        val c = player.blockPosition()
//#endif
        val result = replaceBetween(c.offset(-r, -r, -r), c.offset(r, r, r), from, toSpecs)
        return if (result.startsWith("§a")) "$result within $r blocks" else result
    }

    private fun replaceBetween(a: BlockPos, b: BlockPos, from: String, toSpecs: String): String {
        val fromState = CosmeticCodec.resolve(CosmeticCodec.normalizeId(from)) ?: return "§cUnknown block: $from"
        val blocks = CosmeticCodec.parseWeighted(toSpecs) ?: return "§cInvalid target block specification"
        val level = mc.level ?: return "§cNot in a world"
        val vol = (Math.abs(a.x - b.x) + 1L) * (Math.abs(a.y - b.y) + 1L) * (Math.abs(a.z - b.z) + 1L)
        if (vol > maxRegion) return "§cSelection too big ($vol blocks, max $maxRegion - raise Max Region Size in the settings)"
        val changes = ArrayList<Pair<BlockPos, String?>>()
        for (m in BlockPos.betweenClosed(a, b)) {
            val p = m.immutable()
            val key = p.asLong()
            val visible = CosmeticStore.cache[key]?.let { CosmeticStore.retexture[key] ?: it } ?: level.getBlockState(p)
            if (visible.block == fromState.block) {
                val wasPhantom = CosmeticStore.phantom.contains(key)
                changes += p to CosmeticCodec.buildRaw(CosmeticCodec.serialize(CosmeticCodec.pickWeighted(blocks)), wasPhantom, null)
            }
        }
        CosmeticStore.applyChanges(changes)
        return "§aReplaced ${changes.size} '${fromState.block.let { BuiltInRegistries.BLOCK.getKey(it).path }}' blocks"
    }

    /** Removes cosmetic blocks in the selection (only ones that look like [filter], if given). */
    fun removeIn(filter: String?): String {
        val filterState = filter?.let { CosmeticCodec.resolve(CosmeticCodec.normalizeId(it)) ?: return "§cUnknown block: $it" }
        val changes = ArrayList<Pair<BlockPos, String?>>()
        forRegion { p ->
            val key = p.asLong()
            val state = CosmeticStore.cache[key] ?: return@forRegion
            val visible = CosmeticStore.retexture[key] ?: state
            if (filterState == null || visible.block == filterState.block) changes += p to null
        }?.let { return it }
        CosmeticStore.applyChanges(changes)
        return "§aRemoved ${changes.size} cosmetic blocks in the selection"
    }

    /** Flood-fills connected blocks of the same real type as [start] (6-way), up to the Fill Limit. */
    fun floodFill(start: BlockPos, specs: String, shape: String? = null): String {
        val level = mc.level ?: return "§cNot in a world"
        val blocks = CosmeticCodec.parseWeighted(specs) ?: return "§cInvalid block specification"
        val match = level.getBlockState(start).block
        if (level.getBlockState(start).isAir) return "§cLook at a solid block to fill"
        val limit = CosmeticConfig.fillLimit
        val visited = HashSet<Long>()
        val queue = ArrayDeque<BlockPos>()
        queue.add(start)
        visited.add(start.asLong())
        val changes = ArrayList<Pair<BlockPos, String?>>()
        var hitLimit = false
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            if (level.getBlockState(p).block != match) continue
            if (changes.size >= limit) { hitLimit = true; break }
            changes += p to CosmeticCodec.buildRaw(CosmeticCodec.serialize(CosmeticCodec.pickWeighted(blocks)), false, shape)
            for (dir in Direction.values()) {
                val n = p.relative(dir)
                if (visited.add(n.asLong())) queue.add(n)
            }
        }
        CosmeticStore.applyChanges(changes)
        val name = BuiltInRegistries.BLOCK.getKey(match).path
        return "§aFilled ${changes.size} '$name' blocks" + if (hitLimit) " §7(limit $limit reached)" else ""
    }

    private fun describe(blocks: List<Pair<net.minecraft.world.level.block.state.BlockState, Int>>): String =
        if (blocks.size == 1) BuiltInRegistries.BLOCK.getKey(blocks[0].first.block).path else "${blocks.size} block types"

    // ------------------------------------------------------------------
//#if CHEAT
    // Aim: the Freecam camera while Freecam is on, else the player's eyes. Vanilla's hitResult always comes
    // from the player's body, so in Freecam every tool would otherwise target the block in front of you.
    // ------------------------------------------------------------------

    /** Reach while in Freecam: you fly around to build, so it's much longer than survival reach. */
    private const val FREECAM_REACH = 64.0

    fun aimEye(): Vec3? = if (Freecam.isActive) Freecam.getInterpolatedPos(1f) else mc.player?.getEyePosition(1f)

    fun aimLook(): Vec3? =
        if (Freecam.isActive) Vec3.directionFromRotation(Freecam.getPitch(1f), Freecam.getYaw(1f)) else mc.player?.lookAngle

    /** The block under the crosshair: vanilla's target, or in Freecam a fresh ray from the camera. */
    fun aimHit(): BlockHitResult? {
        if (!Freecam.isActive) return (mc.hitResult as? BlockHitResult)?.takeIf { it.type == HitResult.Type.BLOCK }
        val level = mc.level ?: return null
        val player = mc.player ?: return null
        val eye = aimEye() ?: return null
        val end = eye.add((aimLook() ?: return null).scale(FREECAM_REACH))
        return level.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
            .takeIf { it.type == HitResult.Type.BLOCK }
    }

    // ------------------------------------------------------------------
//#endif
    // Phantom ray cast (phantom blocks have no hitbox, so the crosshair passes through them)
    // ------------------------------------------------------------------

    /** First phantom block along the view ray within reach, plus the face the ray entered it through. */
//#if CHEAT
    fun phantomHit(reach: Double = if (Freecam.isActive) FREECAM_REACH else 5.0): Pair<BlockPos, Direction>? {
        val eye = aimEye() ?: return null
        val look = aimLook() ?: return null
//#else
//$    fun phantomHit(reach: Double = 5.0): Pair<BlockPos, Direction>? {
//$        val player = mc.player ?: return null
//$        val eye = player.getEyePosition(1f)
//$        val look = player.lookAngle
//#endif
        val checked = HashSet<Long>()
        var dist = 0.0
        while (dist <= reach) {
            val bx = Math.floor(eye.x + look.x * dist).toInt()
            val by = Math.floor(eye.y + look.y * dist).toInt()
            val bz = Math.floor(eye.z + look.z * dist).toInt()
            val key = BlockPos.asLong(bx, by, bz)
            if (checked.add(key) && CosmeticStore.phantom.contains(key) && CosmeticStore.cache.containsKey(key)) {
                return BlockPos(bx, by, bz) to entryFace(eye, look, bx, by, bz)
            }
            dist += 0.15
        }
        return null
    }

    private fun entryFace(eye: Vec3, dir: Vec3, x: Int, y: Int, z: Int): Direction {
        var tNear = Double.NEGATIVE_INFINITY
        var face = Direction.NORTH
        if (dir.x != 0.0) {
            val t = minOf((x - eye.x) / dir.x, (x + 1 - eye.x) / dir.x)
            if (t > tNear) { tNear = t; face = if (dir.x > 0) Direction.WEST else Direction.EAST }
        }
        if (dir.y != 0.0) {
            val t = minOf((y - eye.y) / dir.y, (y + 1 - eye.y) / dir.y)
            if (t > tNear) { tNear = t; face = if (dir.y > 0) Direction.DOWN else Direction.UP }
        }
        if (dir.z != 0.0) {
            val t = minOf((z - eye.z) / dir.z, (z + 1 - eye.z) / dir.z)
            if (t > tNear) { face = if (dir.z > 0) Direction.NORTH else Direction.SOUTH }
        }
        return face
    }

    // ------------------------------------------------------------------
    // Ruler
    // ------------------------------------------------------------------

    var rulerActive = false
        private set
    var rulerStart: BlockPos? = null
        private set
    var rulerPreview: List<BlockPos> = emptyList()
        private set
    var rulerReachTarget: BlockPos? = null
        private set
    private var airReach = 4.0

    fun toggleRuler() {
        rulerActive = !rulerActive
        if (!rulerActive) cancelRuler(false)
        CosmeticChat.say(if (rulerActive) "Ruler §aON §7(reach ${airReach.toInt()}, scroll to change)" else "Ruler §cOFF")
    }

    fun cancelRuler(announce: Boolean = true) {
        val had = rulerStart != null
        rulerStart = null
        rulerPreview = emptyList()
        if (!rulerActive) rulerReachTarget = null
        if (announce && had) CosmeticChat.say("§eRuler: cancelled")
    }

    /** Mouse wheel while the ruler is on changes how far it reaches into the air. */
    fun onScroll(amount: Double): Boolean {
        if (!rulerActive || mc.gui.screen() != null) return false
        airReach = (airReach + amount).coerceIn(1.0, 100.0)
        ActionBarHud.notify(net.minecraft.network.chat.Component.literal("§bRuler reach: ${airReach.toInt()}"))
        return true
    }

    private fun airTarget(): BlockPos? {
//#if CHEAT
        val eye = aimEye() ?: return null
        val t = eye.add((aimLook() ?: return null).scale(airReach))
//#else
//$        val player = mc.player ?: return null
//$        val t = player.getEyePosition(1f).add(player.lookAngle.scale(airReach))
//#endif
        return BlockPos.containing(t)
    }

    private fun rulerTarget(): BlockPos? {
//#if CHEAT
        aimHit()?.let { hit ->
            val d = hit.location.distanceTo(aimEye() ?: hit.location)
//#else
//$        val hit = mc.hitResult
//$        if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) {
//$            val d = hit.location.distanceTo(mc.player?.getEyePosition(1f) ?: hit.location)
//#endif
            if (d <= airReach + 0.5) return hit.blockPos
        }
        return airTarget()
    }

    fun tickRuler() {
        if (!rulerActive) return
        rulerReachTarget = airTarget()
        val start = rulerStart ?: return
        val end = rulerTarget() ?: return
        rulerPreview = line(start, end)
    }

    /** Right click: first click sets the start, second places the line with [raw] (phantom where the world is air). */
    fun rulerClick(raw: String, placeMode: Boolean) {
        val start = rulerStart
        if (start == null) {
            val p = rulerTarget() ?: return
            rulerStart = p
            CosmeticChat.say("Ruler: start set at ${p.x}, ${p.y}, ${p.z}")
            return
        }
        val level = mc.level ?: return
        if (rulerPreview.isEmpty()) return
        val parsed = CosmeticCodec.parseRaw(raw)
        val changes = rulerPreview.map { p ->
            val air = level.getBlockState(p).isAir
            p to CosmeticCodec.buildRaw(parsed.base, placeMode || air, parsed.shape)
        }
        CosmeticStore.applyChanges(changes)
        CosmeticChat.say("Ruler: placed ${changes.size} blocks")
        rulerStart = null
        rulerPreview = emptyList()
    }

    /** 3D Bresenham line, inclusive. */
    private fun line(a: BlockPos, b: BlockPos): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        var x = a.x; var y = a.y; var z = a.z
        val dx = Math.abs(b.x - x); val dy = Math.abs(b.y - y); val dz = Math.abs(b.z - z)
        val sx = if (b.x > x) 1 else -1; val sy = if (b.y > y) 1 else -1; val sz = if (b.z > z) 1 else -1
        when {
            dx >= dy && dx >= dz -> {
                var ey = 2 * dy - dx; var ez = 2 * dz - dx
                repeat(dx + 1) {
                    out += BlockPos(x, y, z)
                    if (ey >= 0) { y += sy; ey -= 2 * dx }
                    if (ez >= 0) { z += sz; ez -= 2 * dx }
                    ey += 2 * dy; ez += 2 * dz; x += sx
                }
            }
            dy >= dx && dy >= dz -> {
                var ex = 2 * dx - dy; var ez = 2 * dz - dy
                repeat(dy + 1) {
                    out += BlockPos(x, y, z)
                    if (ex >= 0) { x += sx; ex -= 2 * dy }
                    if (ez >= 0) { z += sz; ez -= 2 * dy }
                    ex += 2 * dx; ez += 2 * dz; y += sy
                }
            }
            else -> {
                var ex = 2 * dx - dz; var ey = 2 * dy - dz
                repeat(dz + 1) {
                    out += BlockPos(x, y, z)
                    if (ex >= 0) { x += sx; ex -= 2 * dz }
                    if (ey >= 0) { y += sy; ey -= 2 * dz }
                    ex += 2 * dx; ey += 2 * dy; z += sz
                }
            }
        }
        return out
    }
}
