package ced.cedclient.state

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
import java.time.Duration
import java.util.UUID

/**
 * Morphs: a player renders as another mob. Morphs are SET on the CedClient site (invited buddies sign in with Discord and
 * pick their mob there); the mod only reads them.
 *
 *  - [init] polls GET /morphs every 60 s (304 when unchanged) into a { uuid -> entity type } map. Everything from the Worker
 *    is untrusted: UUIDs must look like UUIDs and the type must be a living entity type in the registry.
 *  - [swap] (EntityRenderDispatcherMixin): the renderer is handed a client-only "proxy" entity of the morph type, copied from
 *    the player every frame, so the player looks like that mob. The proxy is never added to the level.
 */
object MorphSync {
    private const val BASE = "https://cedclient.kasajonny420-dab.workers.dev"
    private const val POLL_SECONDS = 60L
    private val uuidHex = Regex("^[0-9a-f]{32}$")

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(8)).build()
    private val mc get() = Minecraft.getInstance()

    /** uuid (32 hex, no dashes) -> entity type id ("minecraft:zombie"). Replaced wholesale. */
    @Volatile private var morphs: Map<String, String> = emptyMap()
    @Volatile private var etag: String? = null
    @Volatile private var started = false

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
