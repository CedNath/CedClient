package ced.cedclient.features.impl.funqol

import ced.cedclient.utils.getSkullTexture
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import java.util.Base64
import kotlin.math.abs

/**
 * Slayer Tracker for the overworld slayers (Revenant, Tarantula, Sven, Voidgloom, Inferno): finds your boss by its
 * name tag ("☠ Voidgloom Seraph IV 12.5M❤") when the sidebar switches to "Slay the boss!" (the newest one within
 * 15 blocks, since Hypixel no longer says whose boss is whose), reads its HP, and the boss-specific bits:
 * Voidgloom beacons and Nukekubi skulls, Inferno attunement / fire pits / fire pillars.
 */
internal object SlayerBosses {
    private val BOSS_NAMES = listOf("Revenant Horror", "Atoned Horror", "Tarantula Broodfather", "Sven Packmaster",
        "Voidgloom Seraph", "Inferno Demonlord")
    private val OWNER = Regex("""Spawned by: (\w{1,16})""")
    private val CODES = Regex("§.")
    private val HP = Regex("""([\d.,]+)([kMB]?)❤""")
    private val ATTUNEMENT = Regex("""\b(ASHEN|SPIRIT|CRYSTAL|AURIC)\b""")
    private val HITS = Regex("""Hits?:?\s*(\d+)|(\d+)\s+Hits?""")
    private val PILLAR = Regex("""^([\d.]+)s 8 hits$""")
    private const val NUKEKUBI_SKIN = "eb07594e2df273921a77c101d0bfdfa1115abed5b9b2029eb496ceba9bdbb4b3"

    /** The name-tag armor stand of your boss, and the mob under it. */
    var label: ArmorStand? = null
        private set
    var body: Entity? = null
        private set
    var name: String? = null
        private set
    /** Whose boss it is, from its "Spawned by: <name>" stand; null while Hypixel doesn't show that line. */
    var owner: String? = null
        private set
    var hp = 0.0
        private set
    var maxHp = 0.0
        private set
    var attunement: String? = null
        private set
    var hits: Int? = null
        private set
    var pillar: Float? = null
        private set
    var beacons: List<AABB> = emptyList()
        private set
    var skulls: List<AABB> = emptyList()
        private set
    /** Seconds left of the Voidgloom laser phase, null when it isn't running. */
    var laser: Float? = null
        private set
    private var laserTicks = 0
    private const val LASER_TICKS = 160
    private var firePitsWarned = false
    private var ticks = 0
    private var placed: List<AABB> = emptyList()

    fun reset() {
        label = null; body = null; name = null; owner = null; hp = 0.0; maxHp = 0.0; attunement = null; hits = null
        pillar = null; laser = null; laserTicks = 0; beacons = emptyList(); placed = emptyList(); skulls = emptyList(); firePitsWarned = false
    }

    private fun parseHp(text: String): Double? {
        val m = HP.findAll(text).lastOrNull() ?: return null
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return n * when (m.groupValues[2]) { "k" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
    }

    /** Every "Spawned by: <name>" stand in sight with the name on it. Shared with VampireSlayer. */
    fun spawnedBy(level: net.minecraft.client.multiplayer.ClientLevel): List<Pair<ArmorStand, String>> =
        level.entitiesForRendering().filterIsInstance<ArmorStand>()
            .mapNotNull { s -> s.customName?.string?.let { OWNER.find(it.replace(CODES, "").trim()) }?.let { s to it.groupValues[1] } }

    /** The owner on the "Spawned by" stand right beside [e] (its name tag stack), or null. */
    fun ownerNear(stands: List<Pair<ArmorStand, String>>, e: Entity): String? =
        stands.filter { abs(it.first.x - e.x) < 1.5 && abs(it.first.z - e.z) < 1.5 && abs(it.first.y - e.y) < 3.5 }
            .minByOrNull { it.first.distanceToSqr(e) }?.second

    private fun bossName(text: String) = BOSS_NAMES.firstOrNull { text.contains(it) }

    /** The mob class each boss is, null when unknown. */
    private fun bodyType(boss: String?): Class<out LivingEntity>? = when (boss) {
        "Voidgloom Seraph" -> net.minecraft.world.entity.monster.Enderman::class.java
        "Revenant Horror", "Atoned Horror" -> net.minecraft.world.entity.monster.zombie.Zombie::class.java
        "Tarantula Broodfather" -> net.minecraft.world.entity.monster.spider.Spider::class.java
        "Sven Packmaster" -> net.minecraft.world.entity.animal.wolf.Wolf::class.java
        "Inferno Demonlord" -> net.minecraft.world.entity.monster.Blaze::class.java
        else -> null
    }

    /** Called every tick while a slayer quest is on; [lockWindow] = "Slay the boss!" appeared in the last 3 s. */
    fun tick(lockWindow: Boolean) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val stands = level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { it.distanceToSqr(player) < 40.0 * 40.0 }

        // The old way (most mods): Hypixel puts a "Spawned by: <name>" stand next to the boss tag. When it's there, your
        // boss is the tag beside your own name, no timing or distance needed.
        val spawnedBy = stands.mapNotNull { s -> s.customName?.string?.let { OWNER.find(it.replace(CODES, "").trim()) }?.let { s to it.groupValues[1] } }
        if (label == null) {
            val me = mc.user.name
            label = spawnedBy.filter { it.second.equals(me, ignoreCase = true) }.firstNotNullOfOrNull { (stand, _) ->
                stands.filter { it.hasCustomName() && bossName(it.customName!!.string) != null && abs(it.x - stand.x) < 1.5 && abs(it.z - stand.z) < 1.5 && abs(it.y - stand.y) < 3.0 }
                    .minByOrNull { it.distanceToSqr(stand) }
            }
        }
        if (label == null && lockWindow) {
            label = stands.filter { it.hasCustomName() && bossName(it.customName!!.string) != null && it.distanceToSqr(player) < 15.0 * 15.0 }
                .minByOrNull { it.tickCount }
        }
        val l = label ?: return
        if (l.isRemoved) { reset(); return }
        val text = l.customName?.string ?: return
        name = bossName(text)
        owner = spawnedBy.filter { abs(it.first.x - l.x) < 1.5 && abs(it.first.z - l.z) < 1.5 && abs(it.first.y - l.y) < 3.0 }
            .minByOrNull { it.first.distanceToSqr(l) }?.second
        parseHp(text)?.let { hp = it; maxHp = maxOf(maxHp, it) }
        // The mob under the tag: the right kind of mob only (an Enderman for Voidgloom), so a nearby player or other mob can't be
        // boxed instead. While nothing fits close to the tag (laser phase lifts the boss away) it keeps the last one.
        val kind = bodyType(name)
        val previous = body
        val nearest = level.entitiesForRendering()
            .filter { it is LivingEntity && it !is ArmorStand && it !== player && it.isAlive && (kind == null || kind.isInstance(it)) && it.distanceToSqr(l) < 36.0 }
            .minByOrNull { it.distanceToSqr(l) }
        body = nearest ?: previous?.takeIf { it.isAlive && !it.isRemoved && it.distanceToSqr(l) < 400.0 }
        if (body !== previous) ced.cedclient.utils.Debug.line("Slayer", "boss body ${previous?.type?.description?.string ?: "none"} -> ${body?.type?.description?.string ?: "none"} (${name})")

        // Lines Hypixel stacks over the boss: attunement ("ASHEN ♨8"), shield hits.
        val near = stands.filter { it !== l && it.hasCustomName() && it.distanceToSqr(l) < 16.0 }.map { it.customName!!.string }
        attunement = near.firstNotNullOfOrNull { ATTUNEMENT.find(it)?.groupValues?.get(1) }
        hits = near.plus(text).firstNotNullOfOrNull { s -> HITS.find(s)?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() } }
        pillar = stands.filter { it.hasCustomName() }.firstNotNullOfOrNull { PILLAR.matchEntire(it.customName!!.string.trim())?.groupValues?.get(1)?.toFloatOrNull() }

        if (name == "Voidgloom Seraph") {
            // Laser phase: the boss rides an armor stand for 8 s (same trigger Skyblocker uses).
            val wasLaser = laser != null
            if (body?.vehicle is ArmorStand) laser = (LASER_TICKS - laserTicks++).coerceAtLeast(0) / 20f
            else { laser = null; laserTicks = 0 }
            if (wasLaser != (laser != null)) ced.cedclient.utils.Debug.line("Slayer", if (laser != null) "laser phase started" else "laser phase ended")
            val flying = stands.filter { it.getItemBySlot(EquipmentSlot.HEAD).hoverName.string == "Beacon" && it.distanceToSqr(l) < 30.0 * 30.0 }
                .map { it.boundingBox.inflate(0.2) }
            if (++ticks % 10 == 0) placed = placedBeacons(l.blockPosition())
            beacons = flying + placed
            skulls = stands.filter { skin(it)?.contains(NUKEKUBI_SKIN) == true }.map { it.boundingBox.inflate(0.2) }
        } else { beacons = emptyList(); skulls = emptyList(); laser = null; laserTicks = 0 }
    }

    /** Beacon blocks the boss has set down around it. */
    private fun placedBeacons(centre: BlockPos): List<AABB> {
        val level = Minecraft.getInstance().level ?: return emptyList()
        val out = ArrayList<AABB>()
        for (p in BlockPos.betweenClosed(centre.offset(-12, -6, -12), centre.offset(12, 6, 12))) {
            if (level.getBlockState(p).`is`(Blocks.BEACON)) out += AABB(p)
        }
        return out
    }

    private fun skin(stand: ArmorStand) = skinOf(stand)

    /** Entity id -> (head texture as sent, decoded): decoding every armour stand's head every tick adds up. */
    private val skins = HashMap<Int, Pair<String, String?>>()

    /** Decoded skull texture JSON of [stand]'s head, or null. Shared with VampireSlayer. */
    fun skinOf(stand: ArmorStand): String? {
        val raw = stand.getItemBySlot(EquipmentSlot.HEAD).getSkullTexture() ?: return null
        skins[stand.id]?.takeIf { it.first == raw }?.let { return it.second }
        if (skins.size > 4000) skins.clear()
        val decoded = runCatching { String(Base64.getDecoder().decode(raw)) }.getOrNull()
        skins[stand.id] = raw to decoded
        return decoded
    }

    /** Inferno T3/T4 drop fire pits when the boss goes under a third of its HP: true once per boss. */
    fun firePitsNow(): Boolean {
        if (firePitsWarned || name != "Inferno Demonlord" || maxHp <= 0 || hp > maxHp / 3) return false
        firePitsWarned = true
        return true
    }

    fun format(n: Double): String = when {
        n >= 1e9 -> "%.2fB".format(n / 1e9)
        n >= 1e6 -> "%.2fM".format(n / 1e6)
        n >= 1e4 -> "%.1fk".format(n / 1e3)
        else -> "%,d".format(n.toLong())
    }
}
