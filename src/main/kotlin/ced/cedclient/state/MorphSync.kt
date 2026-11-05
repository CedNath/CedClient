package ced.cedclient.state

import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.DevAccess
import ced.cedclient.utils.Debug
import com.google.gson.Gson
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * Morphs (close friends only to SET, everyone running the mod SEES them): a player renders as another mob.
 *
 *  - [request] (the Morph module) proves who you are to the site with a Mojang session handshake (joinServer here, hasJoined on
 *    the Worker; the server id is a hash of the request, so a proof cannot be reused for another morph), then POST /morph.
 *    The Worker allows only the close-friend UUIDs and one change per 30 s per player; this class also stops at 30 s.
 *  - [init] polls GET /morphs every 45 s (304 when unchanged) into a { uuid -> entity type } map. Everything from the Worker
 *    is untrusted: UUIDs must look like UUIDs and the type must be a living entity type in the registry.
 *  - [swap] (EntityRenderDispatcherMixin): the renderer is handed a client-only "proxy" entity of the morph type, copied from
 *    the player every frame, so the player looks like that mob. The proxy is never added to the level.
 */
object MorphSync {
    private const val BASE = "https://cedclient.kasajonny420-dab.workers.dev"
    private const val POLL_SECONDS = 45L
    private const val COOLDOWN_MS = 30_000L
    private val uuidHex = Regex("^[0-9a-f]{32}$")

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(8)).build()
    private val mc get() = Minecraft.getInstance()

    /** uuid (32 hex, no dashes) -> entity type id ("minecraft:zombie"). Replaced wholesale. */
    @Volatile private var morphs: Map<String, String> = emptyMap()
    @Volatile private var etag: String? = null
    @Volatile private var started = false
    @Volatile private var lastChange = 0L
    @Volatile private var busy = false
    @Volatile private var pendingSet = false
    @Volatile private var pendingId: String? = null

    private class Proxy(val typeId: String, val entity: LivingEntity, var lastTick: Int = -1)
    private val proxies = HashMap<UUID, Proxy>()
    private val badTypes = HashSet<String>()

    fun init() {
        if (started) return
        started = true
        val t = Thread({
            while (true) {
                try { if (mc.level != null) poll() } catch (_: InterruptedException) { return@Thread } catch (_: Throwable) {}
                try { Thread.sleep(POLL_SECONDS * 1000) } catch (_: InterruptedException) { return@Thread }
            }
        }, "cedclient-morph-sync")
        t.isDaemon = true
        t.start()
        // Drop proxies of players that left or lost their morph.
        ClientTickEvents.END_CLIENT_TICK.register {
            if (pendingSet && !busy && System.currentTimeMillis() - lastChange >= COOLDOWN_MS) { pendingSet = false; dispatch(pendingId) }
            if (proxies.isEmpty()) return@register
            val level = mc.level
            proxies.entries.removeIf { (id, p) ->
                val pl = level?.getPlayerByUUID(id)
                pl == null || morphs[hex(id)] != p.typeId || p.entity.level() !== level
            }
        }
    }

    private fun hex(id: UUID) = id.toString().replace("-", "")

    private fun poll() {
        val b = HttpRequest.newBuilder(URI.create("$BASE/morphs")).timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json").header("User-Agent", "CedClient").GET()
        etag?.let { b.header("If-None-Match", it) }
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() != 200) return
        val body = r.body()
        if (body.length > 200_000) return
        val out = HashMap<String, String>()
        for ((k, v) in JsonParser.parseString(body).asJsonObject.entrySet()) {
            if (!uuidHex.matches(k) || !v.isJsonPrimitive) continue
            val id = normalize(v.asString) ?: continue
            if (type(id) != null) out[k] = id
        }
        if (pendingSet) { val me = hex(mc.user.profileId); pendingId?.let { out[me] = it } ?: out.remove(me) } // your unsent pick stays on your screen
        morphs = out
        etag = r.headers().firstValue("ETag").orElse(null)
    }

    /** "zombie" / "Minecraft:Zombie" -> "minecraft:zombie", or null if it is not a plausible id. */
    fun normalize(raw: String): String? {
        val s = raw.trim().lowercase().replace(' ', '_')
        if (s.isEmpty() || s.length > 64) return null
        val id = Identifier.tryParse(if (':' in s) s else "minecraft:$s") ?: return null
        return id.toString()
    }

    /** The entity type with this id (not the player), or null. */
    fun type(id: String): EntityType<*>? {
        if (id in badTypes) return null
        val ident = Identifier.tryParse(id) ?: return null
        val t = BuiltInRegistries.ENTITY_TYPE.getOptional(ident).orElse(null)
        if (t == null || ident.path == "player") { badTypes += id; return null }
        return t
    }

    /** Ids containing [text], for the "did you mean" list. */
    fun suggest(text: String): List<String> {
        val s = text.trim().lowercase().replace(' ', '_')
        return BuiltInRegistries.ENTITY_TYPE.keySet().map { it.path }.filter { s in it }.sorted().take(10)
    }

    /** Your own current morph id (what you see on yourself), or null. */
    fun current(): String? = morphs[hex(mc.user.profileId)]

    /** Seconds until a change reaches the site again (0 = now). */
    fun cooldownLeft(): Long = maxOf(0L, (COOLDOWN_MS - (System.currentTimeMillis() - lastChange) + 999) / 1000)

    private val previews = HashMap<String, LivingEntity>()

    /** A client-only entity of [id] for the picker's 3D preview (kept per world), or null if it cannot be made. */
    fun previewEntity(id: String): LivingEntity? {
        val level = mc.level ?: return null
        previews[id]?.let { if (it.level() === level) return it }
        val made = try { type(id)?.create(level, EntitySpawnReason.LOAD) as? LivingEntity } catch (_: Throwable) { null } ?: return null
        made.id = 900_000 + (id.hashCode() and 0xFFFF) // other mods hash entities while rendering: an unassigned id throws
        previews[id] = made
        return made
    }

    // ---- sending

    /** Sets (or with null clears) your own morph. Returns a message when it was refused right away, null when the request started. */
    fun request(mob: String?): String? {
        if (!DevAccess.isCloseFriend) return "not allowed"
        val now = System.currentTimeMillis()
        var id: String? = null
        if (mob != null) {
            id = normalize(mob) ?: return "that is not a mob name"
            if (type(id) == null) return "no mob called \"$mob\"" + suggest(mob).takeIf { it.isNotEmpty() }?.let { ", maybe: " + it.joinToString(", ") }.orEmpty()
        }
        if (busy || now - lastChange < COOLDOWN_MS) {
            // Cooldown: you can still try mobs, but only on your own screen. The latest choice is sent when the cooldown ends.
            val me = hex(mc.user.profileId)
            pendingSet = true; pendingId = id
            morphs = if (id == null) morphs - me else morphs + (me to id)
            val wait = maxOf(1L, (COOLDOWN_MS - (now - lastChange) + 999) / 1000)
            chat("§7queued ${id?.removePrefix("minecraft:") ?: "no morph"}: others get your latest pick in about $wait s")
            return null
        }
        dispatch(id)
        return null
    }

    private fun chat(text: String) = mc.execute { mc.player?.sendSystemMessage(ChatPrefix.msgFor("Morph", text)) }

    private fun dispatch(id: String?) {
        val user = mc.user
        val uuid = hex(user.profileId)
        val name = user.name
        val token = user.accessToken
        busy = true; lastChange = System.currentTimeMillis()
        val t = Thread({
            var msg = "failed"
            var ok = false
            try {
                val ts = System.currentTimeMillis()
                val serverId = sha1("cedmorph|$uuid|${id ?: "-"}|$ts")
                val gson = Gson()
                val join = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/join"))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json").header("User-Agent", "Minecraft")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(mapOf("accessToken" to token, "selectedProfile" to uuid, "serverId" to serverId)))).build()
                var jr = http.send(join, HttpResponse.BodyHandlers.discarding())
                // Mojang's session server answers 5xx / 429 now and then: retry twice before giving up.
                var tries = 0
                while ((jr.statusCode() >= 500 || jr.statusCode() == 429) && tries++ < 2) {
                    Thread.sleep(1500L * tries)
                    jr = http.send(join, HttpResponse.BodyHandlers.discarding())
                }
                if (jr.statusCode() != 204) {
                    msg = "Mojang session check refused (HTTP ${jr.statusCode()})"
                    if (jr.statusCode() >= 500 || jr.statusCode() == 429) lastChange = 0L // Mojang side: no cooldown used
                } else {
                    val body = gson.toJson(mapOf("uuid" to uuid, "name" to name, "mob" to id, "ts" to ts))
                    val r = http.send(HttpRequest.newBuilder(URI.create("$BASE/morph")).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").header("User-Agent", "CedClient")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
                    when (r.statusCode()) {
                        200 -> { ok = true; msg = if (id == null) "morph removed" else "you are now ${id.removePrefix("minecraft:")} (others see it within about a minute)"
                            morphs = if (id == null) morphs - uuid else morphs + (uuid to id) }
                        429 -> { msg = "the site says wait: ${r.body().take(60)}"; lastChange = System.currentTimeMillis() }
                        403 -> msg = if (r.body().contains("not verified")) "the site could not verify you with Mojang (try again in a few seconds)" else "the site does not allow this account (${r.body().take(40)})"
                        else -> msg = "site error HTTP ${r.statusCode()}"
                    }
                }
            } catch (e: Throwable) {
                msg = "could not reach the site (${e.javaClass.simpleName})"
                lastChange = 0L // nothing was sent: no cooldown
            } finally { busy = false }
            Debug.line("Morph", "request ${id ?: "clear"}: ok=$ok $msg")
            mc.execute { mc.player?.sendSystemMessage(ChatPrefix.msgFor("Morph", (if (ok) "§a" else "§c") + msg)) }
        }, "cedclient-morph-send")
        t.isDaemon = true
        t.start()
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- rendering

    /** The entity the renderer should draw in place of [entity]: a proxy of the morph type for a morphed player, else [entity] itself. */
    @JvmStatic
    fun swap(entity: Entity): Entity {
        if (morphs.isEmpty() || entity !is Player) return entity
        val id = morphs[hex(entity.uuid)] ?: return entity
        val level = mc.level ?: return entity
        var p = proxies[entity.uuid]
        if (p == null || p.typeId != id || p.entity.level() !== level) {
            val made = try { type(id)?.create(level, EntitySpawnReason.LOAD) as? LivingEntity } catch (_: Throwable) { null }
            if (made == null) { badTypes += id; return entity }
            p = Proxy(id, made)
            proxies[entity.uuid] = p
        }
        val e = p.entity
        // Other mods hash entities while rendering (Skysoft's highlight map): an unassigned id throws. Same id as the player, so they treat it as that player.
        e.id = entity.id
        e.setPos(entity.x, entity.y, entity.z)
        e.xo = entity.xo; e.yo = entity.yo; e.zo = entity.zo
        e.xOld = entity.xOld; e.yOld = entity.yOld; e.zOld = entity.zOld
        e.yRot = entity.yRot; e.xRot = entity.xRot; e.yRotO = entity.yRotO; e.xRotO = entity.xRotO
        e.yBodyRot = entity.yBodyRot; e.yBodyRotO = entity.yBodyRotO
        e.yHeadRot = entity.yHeadRot; e.yHeadRotO = entity.yHeadRotO
        e.hurtTime = entity.hurtTime; e.deathTime = entity.deathTime
        e.setInvisible(entity.isInvisible)
        e.setShiftKeyDown(entity.isShiftKeyDown)
        e.setCustomName(entity.displayName)
        e.isCustomNameVisible = entity !== mc.player
        e.tickCount = entity.tickCount
        if (p.lastTick != entity.tickCount) {
            p.lastTick = entity.tickCount
            val dx = entity.x - entity.xo; val dz = entity.z - entity.zo
            e.walkAnimation.update(minOf(Math.sqrt(dx * dx + dz * dz).toFloat() * 4f, 1f), 0.4f, 1f)
        }
        return e
    }

    /**
     * Called first thing in LivingEntityRenderer.submit (MorphSubmitMixin). The player keeps its own AvatarRenderState (vanilla needs that, also for
     * the local player); a morphed one is drawn here instead, by the mob's own renderer from a render state copied off the player's.
     * True = drawn, the caller cancels the player model.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun submitMorph(av: net.minecraft.client.renderer.entity.state.AvatarRenderState, pose: com.mojang.blaze3d.vertex.PoseStack,
                    collector: net.minecraft.client.renderer.SubmitNodeCollector, camera: net.minecraft.client.renderer.state.level.CameraRenderState): Boolean {
        if (morphs.isEmpty() || av.isSpectator || av.isInvisible) return false
        val level = mc.level ?: return false
        val player = level.getEntity(av.id) as? Player ?: return false
        val shape = swap(player)
        if (shape === player || shape !is LivingEntity) return false
        return try {
            val renderer = mc.entityRenderDispatcher.getRenderer(shape) as net.minecraft.client.renderer.entity.EntityRenderer<LivingEntity, net.minecraft.client.renderer.entity.state.EntityRenderState>
            val partial = (av.ageInTicks - player.tickCount).coerceIn(0f, 1f)
            val st = renderer.createRenderState(shape, partial)
            st.x = av.x; st.y = av.y; st.z = av.z
            st.ageInTicks = av.ageInTicks
            st.lightCoords = av.lightCoords
            st.outlineColor = av.outlineColor
            if (st is net.minecraft.client.renderer.entity.state.LivingEntityRenderState) {
                st.walkAnimationPos = av.walkAnimationPos
                st.walkAnimationSpeed = av.walkAnimationSpeed
                st.bodyRot = av.bodyRot
                st.yRot = av.yRot
                st.xRot = av.xRot
                st.deathTime = av.deathTime
                st.hasRedOverlay = av.hasRedOverlay
            }
            renderer.submit(st, pose, collector, camera)
            true
        } catch (e: Throwable) {
            badTypes += (proxies[player.uuid]?.typeId ?: "")
            proxies.remove(player.uuid)
            false
        }
    }
}
