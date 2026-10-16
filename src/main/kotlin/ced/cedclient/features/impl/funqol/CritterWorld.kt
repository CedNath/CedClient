package ced.cedclient.features.impl.funqol

import ced.cedclient.features.impl.funqol.CritterSafari.Biome
import ced.cedclient.features.impl.funqol.CritterSafari.Critter
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.utils.Color
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Interaction
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * What Critter Safari reads from the world: the critters around you (Hypixel puts a name-tag entity reading the
 * species above each one), sparkling ones, Rockmite mounds, floor drops and the spots a Hunter offered a trade.
 * Also the fixed spots worth checking every run: the breakable Snooper (Cavern) and Troodon (Icy) walls, the bee
 * blocks in the Forest and the Hideonwall perches in the Haunted Biome.
 * Draws the boxes for them (depth-tested, so nothing shows through walls) and drops the Darkness effect.
 */
internal object CritterWorld {
    /** A labelled critter; [mob] is the body under the label (null when none was found). */
    class Sighting(val critter: Critter, val label: Entity, val mob: Entity?, val sparkling: Boolean, val clear: Boolean) {
        val body get() = mob ?: label
    }

    /** [pos] is the Hunter when its name tag was found ([seen]), else where you stood. */
    class Trade(val npc: String, val critter: Critter, val item: String, val pos: Vec3, val seen: Boolean, val biome: Biome?) {
        fun where() = (biome?.display?.plus(" ") ?: "") + "${pos.x.toInt()} ${pos.y.toInt()} ${pos.z.toInt()}"
    }

    var sightings: List<Sighting> = emptyList()
        private set
    val trades = mutableListOf<Trade>()
    private var mounds: List<AABB> = emptyList()
    private var drops: List<BlockPos> = emptyList()
    private val announcedSparkling = HashSet<Int>()
    /** Where a critter was when you threw a capsule at it: it comes back there if it escapes. */
    private class Pin(val critter: Critter, val box: AABB, val until: Long)
    private val pins = mutableListOf<Pin>()
    private var ticks = 0

    private fun spots(vararg xyz: Int) = xyz.toList().chunked(3).map { BlockPos(it[0], it[1], it[2]) }
    private val SNOOPER_WALLS = spots(-126, 39, 74, -114, 39, 87, -70, 39, 68, -96, 40, 17, -95, 40, 42)
    private val TROODON_WALLS = spots(-104, 80, -95, -131, 78, -61, -109, 89, -27)
    private val BEE_SPOTS = spots(-17, 68, 41, -22, 68, 39, 23, 70, 46, 25, 70, 41, -9, 81, 58, -1, 81, 60, 15, 88, 31, 20, 88, 33,
        -1, 84, 9, -13, 103, 21, -11, 103, 13)
    private val PERCHES = spots(18, 71, -57, -5, 71, -85, -24, 71, -76, -25, 71, -65, -25, 79, -57, 6, 79, -84, 17, 80, -75, 17, 79, -52)

    /** Walls seen gone this run (air where the wall was, with a clear line to it). */
    private val brokenWalls = HashSet<BlockPos>()
    /** Bee spots / perches looked at this run: true = worth going to (a bee nest / a Hideonwall on it). */
    private val beeSeen = HashMap<BlockPos, Boolean>()
    private val perchSeen = HashMap<BlockPos, Boolean>()

    /** Walls not seen broken yet in [biome]'s set, or null outside the Cavern / Icy. */
    fun wallsLeft(biome: Biome) = when (biome) {
        Biome.CAVERN -> SNOOPER_WALLS
        Biome.ICY -> TROODON_WALLS
        else -> null
    }?.count { it !in brokenWalls }

    private fun checkSpots(mc: Minecraft) {
        val level = mc.level ?: return
        for (pos in SNOOPER_WALLS + TROODON_WALLS) {
            if (pos !in brokenWalls && level.isLoaded(pos) && level.getBlockState(pos).isAir && canSee(mc, pos)) brokenWalls += pos
        }
        for (pos in BEE_SPOTS) if (level.isLoaded(pos) && canSee(mc, pos)) beeSeen[pos] = level.getBlockState(pos).`is`(Blocks.BEE_NEST)
        for (pos in PERCHES) if (level.isLoaded(pos) && canSee(mc, pos)) {
            val centre = Vec3.atCenterOf(pos)
            perchSeen[pos] = sightings.any { it.critter.name == "Hideonwall" && it.body.position().distanceToSqr(centre) < 2.25 }
        }
    }

    /** A clear line from your eyes to [pos] (or the first thing hit is that block itself). */
    private fun canSee(mc: Minecraft, pos: BlockPos): Boolean {
        val player = mc.player ?: return false
        if (player.eyePosition.distanceToSqr(Vec3.atCenterOf(pos)) > 64.0 * 64.0) return false
        val hit = mc.level?.clip(ClipContext(player.eyePosition, Vec3.atCenterOf(pos), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
            ?: return false
        return hit.type == HitResult.Type.MISS || hit.blockPos == pos
    }

    private val HARD_TO_FIND = setOf("Bloodbat")

    fun moundsNearby() = mounds.size

    fun tick() {
        val mc = Minecraft.getInstance()
        val player = mc.player
        if (player == null || !CritterSafari.isEnabled || !CritterSafari.inSafari()) {
            sightings = emptyList(); mounds = emptyList(); drops = emptyList()
            return
        }
        if (CritterSafari.removeDarkness.value && player.hasEffect(MobEffects.DARKNESS)) player.removeEffectNoUpdate(MobEffects.DARKNESS)
        if (++ticks % 5 != 0) return
        scan(mc)
        checkSpots(mc)
        val now = System.currentTimeMillis()
        pins.removeAll { it.until < now }
    }

    private fun scan(mc: Minecraft) {
        val level = mc.level ?: return
        val labels = ArrayList<Pair<Entity, Pair<Critter, Boolean>>>()
        val bodies = ArrayList<Entity>()
        val strings = HashMap<BlockPos, Int>()
        val mounds = ArrayList<AABB>()
        val cavern = CritterSafari.currentBiome() == Biome.CAVERN
        for (e in level.entitiesForRendering()) {
            val name = if (e.hasCustomName()) CritterSafari.strip(e.customName!!.string) else null
            val labelled = name?.let(::labelled)
            when {
                labelled != null -> labels += e to labelled
                e is Display.ItemDisplay -> if (e.itemStack.`is`(Items.STRING)) strings.merge(e.blockPosition(), 1, Int::plus)
                // Rockmite mounds are small interaction boxes low in the Cavern.
                e is Interaction -> if (cavern && e.width in 0.35f..1.1f && e.height in 0.25f..0.95f && e.y < 65.0) mounds += e.boundingBox
                isBody(e) -> bodies += e
            }
        }
        sightings = labels.map { (label, info) ->
            val body = bodies.filter { it.distanceToSqr(label) < 9.0 }.minByOrNull { it.distanceToSqr(label) }
            Sighting(info.first, label, body, info.second, clearLine(mc, body ?: label))
        }
        this.mounds = mounds
        // Hypixel builds a floor drop out of three string item displays in one block.
        drops = strings.filterValues { it >= 3 }.keys.toList()

        for (s in sightings) if (s.sparkling && s.clear && announcedSparkling.add(s.label.id)) {
            val p = s.body.blockPosition()
            CritterAlerts.sparkling(s.critter, (s.critter.biome.display + " ") + "${p.x} ${p.y} ${p.z}")
        }
    }

    /** A label's species, and whether it reads "Sparkling ..." (a name tag like "Tepid Shard" is not a critter). */
    private fun labelled(name: String): Pair<Critter, Boolean>? {
        CritterSafari.critterNamed(name)?.let { return it to false }
        if (name.length > 10 && name.startsWith("sparkling ", ignoreCase = true)) {
            CritterSafari.critterNamed(name.substring(10).trim())?.let { return it to true }
        }
        return null
    }

    private fun isBody(e: Entity) = e.type != EntityTypes.ARMOR_STAND && e.type != EntityTypes.INTERACTION && e.type != EntityTypes.TEXT_DISPLAY &&
        e.type != EntityTypes.BLOCK_DISPLAY && e.type != EntityTypes.ITEM_DISPLAY && e.type != EntityTypes.PLAYER && e.type != EntityTypes.ITEM

    private fun clearLine(mc: Minecraft, e: Entity): Boolean {
        val player = mc.player ?: return false
        val hit = mc.level?.clip(ClipContext(player.eyePosition, e.boundingBox.center, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
        return hit == null || hit.type == HitResult.Type.MISS
    }

    /** Species in sight right now (a clear line to them), for the Missing panel. */
    fun inSight(critter: Critter) = sightings.count { it.critter == critter && it.clear }

    /** "You threw a Critter Capsule at the X!": pin where the nearest X is. */
    fun onThrow(critter: Critter) {
        if (!CritterSafari.recatch.value) return
        val player = Minecraft.getInstance().player ?: return
        val s = sightings.filter { it.critter == critter }.minByOrNull { it.body.distanceToSqr(player) } ?: return
        pins.removeAll { it.critter == critter }
        pins += Pin(critter, s.body.boundingBox, System.currentTimeMillis() + 40_000)
    }

    fun onCatch(critter: Critter) { pins.removeAll { it.critter == critter } }

    fun addTrade(npc: String, critter: Critter, item: String): Trade {
        val mc = Minecraft.getInstance()
        val player = mc.player
        // The Hunter's own name tag, else where you are standing.
        val hunter = mc.level?.entitiesForRendering()
            ?.filter { it.hasCustomName() && CritterSafari.strip(it.customName!!.string).contains(npc) }
            ?.minByOrNull { if (player == null) 0.0 else it.distanceToSqr(player) }?.position()
        trades.removeAll { it.npc == npc }
        return Trade(npc, critter, item, hunter ?: player?.position() ?: Vec3.ZERO, hunter != null, CritterSafari.currentBiome()).also { trades += it }
    }

    fun reset() {
        trades.clear(); pins.clear(); announcedSparkling.clear()
        brokenWalls.clear(); beeSeen.clear(); perchSeen.clear()
    }

    /** [e]'s box where it is drawn this frame, so it doesn't step every tick. */
    private fun smoothBox(e: Entity): AABB =
        e.boundingBox.move(e.getPosition(Minecraft.getInstance().deltaTracker.getGameTimeDeltaPartialTick(false)).subtract(e.position()))

    fun render(poseStack: PoseStack, submits: SubmitNodeCollector, camera: CameraRenderState) {
        val s = CritterSafari
        if (!s.isEnabled || !s.inSafari()) return
        val boxes = ArrayList<Pair<AABB, Color>>()
        if (s.hardToFind.value) sightings.filter { it.critter.name in HARD_TO_FIND }.forEach { boxes += smoothBox(it.body).inflate(0.1) to s.hardToFindColor.value }
        sightings.filter { it.sparkling }.forEach { boxes += smoothBox(it.body).inflate(0.15) to Color(255, 255, 85) }
        val boxCountBeforeBlocks = boxes.size
        if (s.recatch.value) pins.forEach { boxes += it.box to s.recatchColor.value }
        if (s.floorDrops.value) drops.forEach { boxes += AABB(it) to s.floorDropColor.value }
        if (s.highlightMounds.value) mounds.forEach { boxes += it.inflate(0.05) to s.moundColor.value }
        if (s.highlightTrades.value) trades.filter { it.seen }.forEach { boxes += AABB.ofSize(it.pos.add(0.0, 1.0, 0.0), 0.8, 2.0, 0.8) to s.tradeColor.value }
        when (s.currentBiome()) {
            Biome.CAVERN -> if (s.snooperWalls.value) SNOOPER_WALLS.filter { it !in brokenWalls }.forEach { boxes += AABB(it) to s.wallColor.value }
            Biome.ICY -> if (s.troodonWalls.value) TROODON_WALLS.filter { it !in brokenWalls }.forEach { boxes += AABB(it) to s.wallColor.value }
            Biome.FOREST -> if (s.beeSpots.value) BEE_SPOTS.forEach { pos ->
                when (beeSeen[pos]) {
                    null -> boxes += AABB(pos) to s.beeColor.value
                    true -> boxes += AABB(pos).inflate(0.05) to s.beeNestColor.value
                    false -> {}
                }
            }
            Biome.HAUNTED -> if (s.perches.value) PERCHES.forEach { pos ->
                when (perchSeen[pos]) {
                    null -> boxes += AABB(pos) to s.perchColor.value
                    true -> boxes += AABB(pos).inflate(0.05) to s.perchFoundColor.value
                    false -> {}
                }
            }
            null -> {}
        }
        if (boxes.isEmpty()) return
        val cam = camera.pos
        // Block markers (everything but the critter bodies) also get a see-through fill, like waypoints.
        val fills = boxes.drop(boxCountBeforeBlocks)
        submits.submitCustomGeometry(poseStack, CustomRenderType.FILLED) { pose, fillBuffer ->
            for ((box, c) in fills) ced.cedclient.render.FilledBox.draw(pose, fillBuffer, box.move(-cam.x, -cam.y, -cam.z), c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat * 0.4f)
        }
        submits.submitCustomGeometry(poseStack, CustomRenderType.LINES_DEPTH) { pose, buffer ->
            for ((box, c) in boxes) LineBox.draw(pose, buffer, box.move(-cam.x, -cam.y, -cam.z), c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
        }
    }
}
