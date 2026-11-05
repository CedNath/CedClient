//#if CHEAT
package ced.cedclient.pathing

import ced.cedclient.config.CedPaths
import ced.cedclient.state.Sidebar
import ced.cedclient.utils.skyblockId
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.Guardian
import java.io.File

/**
 * Records how YOU fight a slayer boss, to build the macro from it: `/cedclient path slayer start [name]`, kill the boss
 * yourself, `/cedclient path slayer stop`. One JSON line per tick in config/cedclient/slayerrecords/<name>.jsonl:
 * position, look, keys (incl. attack / use), held item and slot, health, the mobs around you (id, type, position, health,
 * on the ground), the name tags near you (boss health, timers), guardian beams, the slayer lines of the sidebar and every chat
 * message. `/cedclient path slayer mark <text>` drops a note ("boss spawned", "phase 2") at the current tick.
 */
object SlayerRecorder {
    private val mc = Minecraft.getInstance()
    private val gson = Gson()
    private val lines = ArrayList<String>()
    private var name = ""
    private var tick = 0
    private var lastSlot = -1
    private var lastHealth = -1f
    private var hookUntil = 0
    private var lastQuest = ""
    private val killed = HashSet<Int>()
    private var lastAttack = false
    private var lastUse = false
    private const val MAX_TICKS = 36000 // 30 minutes

    val active: Boolean get() = name.isNotEmpty()

    init {
        ClientTickEvents.END_CLIENT_TICK.register { sample() }
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (active && !overlay) {
                val text = Sidebar.clean(message.string)
                event("chat", text)
                // The moment a boss comes out, and where you stood: what the grind has to reach.
                if (text.contains("spawned") && (text.contains("SLAYER BOSS") || text.contains("MINIBOSS"))) {
                    val p = mc.player
                    event("spawn", "$text | you at ${p?.let { "${r(it.x)},${r(it.y)},${r(it.z)} hp${it.health.toInt()}" }} | quest ${questText()}")
                }
            }
        }
    }

    fun start(label: String?) {
        name = label?.takeIf { it.isNotBlank() }?.replace(Regex("[^A-Za-z0-9_-]"), "_") ?: "slayer-${System.currentTimeMillis() / 1000}"
        lines.clear(); tick = 0; lastSlot = -1; lastHealth = -1f; lastQuest = ""; killed.clear(); hookUntil = 0
        line(JsonObject().apply { addProperty("type", "header"); addProperty("name", name); addProperty("note", "20 ticks per second; mobs within 20 blocks") })
    }

    fun mark(text: String) { if (active) event("mark", text) }

    /** Ends the recording and writes it; the file, or null if nothing was recording. */
    fun stop(): File? {
        if (!active) return null
        val file = CedPaths.dir.resolve("slayerrecords").also { it.mkdirs() }.resolve("$name.jsonl")
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        name = ""; lines.clear()
        return file
    }

    private fun questText(): String {
        val s = Sidebar.lines
        val q = s.indexOfFirst { it.contains("Slayer Quest") }
        return if (q < 0) "none" else s.drop(q + 1).take(4).joinToString(" | ")
    }

    private fun event(type: String, text: String) = line(JsonObject().apply { addProperty("type", type); addProperty("t", tick); addProperty("text", text) })

    private fun line(o: JsonObject) { lines += gson.toJson(o) }

    private fun r(v: Double) = Math.round(v * 100.0) / 100.0

    private fun sample() {
        if (!active) return
        val p = mc.player ?: return
        val level = mc.level ?: return
        if (tick >= MAX_TICKS) { event("mark", "auto-stopped at the time limit"); stop(); return }
        val o = mc.options
        val slot = p.inventory.selectedSlot
        if (slot != lastSlot) { event("slot", "${slot + 1} ${p.mainHandItem.skyblockId}"); lastSlot = slot }
        if (lastHealth >= 0f && p.health < lastHealth - 0.5f) event("hurt", "${r((lastHealth - p.health).toDouble())} -> ${r(p.health.toDouble())}")
        if (lastHealth >= 0f && p.health > lastHealth + 3f) event("heal", "${r(lastHealth.toDouble())} -> ${r(p.health.toDouble())}")
        lastHealth = p.health
        // Edge of the attack / use buttons: when you press them and what is under the crosshair, and the click count.
        val atk = o.keyAttack.isDown; val use = o.keyUse.isDown
        if (atk && !lastAttack) event("attack", "${p.mainHandItem.skyblockId} on ${(mc.hitResult as? net.minecraft.world.phys.EntityHitResult)?.entity?.let { it.type.description.string + "#" + it.id } ?: "nothing"}")
        if (use && !lastUse) {
            event("use", "${p.mainHandItem.skyblockId} pitch ${r(p.xRot.toDouble())}")
            if (p.mainHandItem.skyblockId == "MOODY_GRAPPLESHOT") hookUntil = tick + 80
        }
        lastAttack = atk; lastUse = use
        // Quest progress (kills / XP towards the boss) whenever the line changes.
        val quest = questText()
        if (quest != lastQuest) { event("quest", quest); lastQuest = quest }
        // Kills: a mob within 20 blocks whose health reached 0, with what it was, how far, and what you held. Together with the quest
        // line this shows which kills count towards the boss and how fast you chain them.
        level.entitiesForRendering().filter { it is LivingEntity && it !is ArmorStand && it !== p && it.id !in killed && it.distanceToSqr(p) < 900.0 }.forEach { e ->
            val m = e as LivingEntity
            if (m.health <= 0f || m.isDeadOrDying) {
                killed += e.id
                event("kill", "${e.type.description.string}#${e.id} maxhp ${m.maxHealth.toInt()} d${r(e.distanceTo(p).toDouble())} dy${r(e.y - p.y)} with ${p.mainHandItem.skyblockId} at ${r(e.x)},${r(e.y)},${r(e.z)}")
            }
        }

        line(JsonObject().apply {
            addProperty("t", tick)
            addProperty("x", r(p.x)); addProperty("y", r(p.y)); addProperty("z", r(p.z))
            addProperty("yaw", r(p.yRot.toDouble())); addProperty("pitch", r(p.xRot.toDouble()))
            addProperty("ground", p.onGround()); addProperty("hp", p.health.toInt())
            addProperty("keys", (if (o.keyUp.isDown) "W" else "") + (if (o.keyLeft.isDown) "A" else "") + (if (o.keyDown.isDown) "S" else "") +
                (if (o.keyRight.isDown) "D" else "") + (if (o.keyJump.isDown) "J" else "") + (if (o.keyShift.isDown) "C" else "") +
                (if (o.keySprint.isDown) "R" else "") + (if (o.keyAttack.isDown) "L" else "") + (if (o.keyUse.isDown) "U" else ""))
            addProperty("item", p.mainHandItem.skyblockId)
            val mobs = JsonArray()
            level.entitiesForRendering().filter { it is LivingEntity && it !is ArmorStand && it !== p && it.distanceToSqr(p) < 400.0 }
                .sortedBy { it.distanceToSqr(p) }.take(10).forEach { e ->
                    mobs.add(JsonObject().apply {
                        addProperty("id", e.id); addProperty("type", e.type.description.string)
                        addProperty("x", r(e.x)); addProperty("y", r(e.y)); addProperty("z", r(e.z))
                        addProperty("hp", (e as LivingEntity).health.toInt()); addProperty("max", e.maxHealth.toInt())
                        if (!e.onGround()) addProperty("air", true)
                        if (e.isInvisible) addProperty("inv", true)
                        if (e is Guardian) addProperty("beam", e.activeAttackTarget?.id ?: -1)
                    })
                }
            add("mobs", mobs)
            addProperty("abs", r(p.absorptionAmount.toDouble())); addProperty("maxhp", p.maxHealth.toInt())
            addProperty("sneak", p.isCrouching); addProperty("sprinting", p.isSprinting)
            addProperty("vx", r(p.deltaMovement.x)); addProperty("vy", r(p.deltaMovement.y)); addProperty("vz", r(p.deltaMovement.z))
            // What the crosshair is on, and how far: shows what you aim at and when you swing.
            (mc.hitResult as? net.minecraft.world.phys.EntityHitResult)?.entity?.let { e ->
                addProperty("aim", "${e.type.description.string}#${e.id} d${r(e.distanceTo(p).toDouble())}")
            }
            // Every guardian beam in 40 blocks: from where to where, who it hits, and how far you are from the line (hurts under ~1.6).
            val lasers = JsonArray()
            val body = p.position().add(0.0, 0.9, 0.0)
            level.entitiesForRendering().filterIsInstance<Guardian>().filter { it.distanceToSqr(p) < 1600.0 }.forEach { g ->
                val t = g.activeAttackTarget
                lasers.add(JsonObject().apply {
                    addProperty("id", g.id)
                    addProperty("x", r(g.x)); addProperty("y", r(g.y)); addProperty("z", r(g.z))
                    if (t == null) addProperty("target", -1) else {
                        val a = g.eyePosition; val b = t.eyePosition; val ab = b.subtract(a)
                        val len2 = ab.lengthSqr()
                        val closest = if (len2 < 1.0e-4) a else a.add(ab.scale((body.subtract(a).dot(ab) / len2).coerceIn(0.0, 1.0)))
                        addProperty("target", t.id); addProperty("tname", t.type.description.string)
                        addProperty("tx", r(b.x)); addProperty("ty", r(b.y)); addProperty("tz", r(b.z))
                        addProperty("youDist", r(body.distanceTo(closest)))
                    }
                })
            }
            add("lasers", lasers)
            // Fireballs and other projectiles in 20 blocks, with where they are heading: what you dodge when you move about.
            val shots = JsonArray()
            level.entitiesForRendering().filter { it is net.minecraft.world.entity.projectile.Projectile && it.distanceToSqr(p) < 400.0 }
                .sortedBy { it.distanceToSqr(p) }.take(12).forEach { e ->
                    shots.add(JsonObject().apply {
                        addProperty("type", e.type.description.string)
                        addProperty("x", r(e.x)); addProperty("y", r(e.y)); addProperty("z", r(e.z))
                        addProperty("vx", r(e.deltaMovement.x)); addProperty("vy", r(e.deltaMovement.y)); addProperty("vz", r(e.deltaMovement.z))
                        addProperty("d", r(e.distanceTo(p).toDouble()))
                    })
                }
            if (shots.size() > 0) add("shots", shots)
            // The Grappleshot's "anvil" is no projectile: for 4 s after each throw, every other entity near you (armor stands with what they
            // wear on the head, falling blocks, displays, items) so its type, path out and back, and speed show up.
            if (tick < hookUntil) {
                val ents = JsonArray()
                level.entitiesForRendering().filter { e -> e !== p && e !is net.minecraft.world.entity.projectile.Projectile && (e !is LivingEntity || (e is ArmorStand && !e.hasCustomName())) && e.distanceToSqr(p) < 900.0 }
                    .sortedBy { it.distanceToSqr(p) }.take(15).forEach { e ->
                        ents.add(JsonObject().apply {
                            addProperty("id", e.id); addProperty("type", e.type.description.string)
                            addProperty("x", r(e.x)); addProperty("y", r(e.y)); addProperty("z", r(e.z))
                            addProperty("d", r(e.distanceTo(p).toDouble()))
                            if (e is LivingEntity) addProperty("head", e.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).let { it.item.toString() + " " + it.skyblockId })
                            if (e is net.minecraft.world.entity.item.ItemEntity) addProperty("item", e.item.item.toString())
                            if (e is net.minecraft.world.entity.item.FallingBlockEntity) addProperty("block", e.blockState.toString())
                        })
                    }
                add("ents", ents)
            }
            if (tick % 5 == 0) {
                val tags = JsonArray()
                level.entitiesForRendering().filterIsInstance<ArmorStand>().filter { it.hasCustomName() && it.distanceToSqr(p) < 144.0 }
                    .sortedBy { it.distanceToSqr(p) }.take(12).forEach { tags.add("${Sidebar.clean(it.customName!!.string).take(60)} @${r(it.x)},${r(it.y)},${r(it.z)}") }
                add("tags", tags)
            }
            if (tick % 20 == 0) {
                val s = Sidebar.lines
                val q = s.indexOfFirst { it.contains("Slayer Quest") }
                if (q >= 0) addProperty("quest", s.drop(q + 1).take(4).joinToString(" | "))
            }
        })
        tick++
    }
}
//#endif
