//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.render.bufferSource
import ced.cedclient.render.draw
import ced.cedclient.render.polyline
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.utils.Color
import ced.cedclient.utils.skyblockId
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionResult
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the Gyrokinetic Wand's ability lands while you hold it: a box on the target block and a ring for the sucking range
 * (10 blocks). Unique: the number of mobs inside the ring is counted live and the ring turns from the idle colour to the hit
 * colour once at least the configured number of mobs would be pulled.
 */
object GyroHelper : Module(
    "Gyro Helper",
    Category.Combat,
    "Shows the Gyrokinetic Wand's target spot and sucking range, with the number of mobs inside."
) {
    private val drawBox = BooleanSetting("Draw Box", true, "A box where the wand's ability lands.")
    private val drawRing = BooleanSetting("Draw Sucking Range", true, "A ring with the radius of the pull (10 blocks).")
    private val boxColor = ColorSetting("Box Color", Color(85, 255, 255), "Colour of the box.")
    private val ringColor = ColorSetting("Ring Color", Color(170, 85, 255), "Colour of the ring.")
    private val hitColor = ColorSetting("Pull Color", Color(85, 255, 85), "Ring colour once enough mobs are inside.")
    private val minMobs = NumberSetting("Mobs For Pull Color", 3.0, 1.0, 20.0, 1.0, "How many mobs turn the ring to the pull colour.")
    private val range = NumberSetting("Range (blocks)", 25.0, 5.0, 40.0, 1.0, "How far the wand reaches.")

    private const val RADIUS = 10.0
    private const val SEGMENTS = 48

    private fun target(): Vec3? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        val level = mc.level ?: return null
        if (player.mainHandItem.skyblockId != "GYROKINETIC_WAND") return null
        val from = player.getEyePosition(1f)
        val to = from.add(player.getViewVector(1f).scale(range.value))
        val hit = level.clip(ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        return if (hit.type == HitResult.Type.BLOCK) Vec3(hit.location.x, hit.blockPos.y + 1.0, hit.location.z) else null
    }

    init {
        addSettings(drawBox, drawRing, boxColor, ringColor, hitColor, minMobs, range)
        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits { context ->
            if (!isEnabled) return@CollectSubmits
            val spot = target() ?: return@CollectSubmits
            val mc = Minecraft.getInstance()
            val cam = context.gameRenderer().mainCamera().position()
            val inside = mc.level?.entitiesForRendering()?.count {
                it is LivingEntity && it !is Player && it.isAlive && it.position().distanceTo(spot) <= RADIUS
            } ?: 0
            val ring = if (inside >= minMobs.value.toInt()) hitColor.value else ringColor.value
            val poseStack = context.poseStack()
            val buffers = context.bufferSource()
            val lines = buffers.getBuffer(CustomRenderType.LINES_ESP)
            if (drawBox.value) {
                val b = AABB(spot.x - 0.5, spot.y, spot.z - 0.5, spot.x + 0.5, spot.y + 1.0, spot.z + 0.5).move(-cam.x, -cam.y, -cam.z)
                val c = boxColor.value
                LineBox.draw(poseStack, lines, b, c.redFloat, c.greenFloat, c.blueFloat, 1f)
            }
            if (drawRing.value) {
                val pts = (0..SEGMENTS).map { i ->
                    val a = i * 2 * Math.PI / SEGMENTS
                    Vec3(spot.x + cos(a) * RADIUS, spot.y + 0.05, spot.z + sin(a) * RADIUS).subtract(cam)
                }
                LineBox.polyline(poseStack, lines, pts, ring.redFloat, ring.greenFloat, ring.blueFloat, 1f)
            }
            buffers.endBatch()
        })
    }
}

/**
 * Camera tweaks: skips the front-facing third person view when cycling with F5, and hides blindness and nausea from your own
 * screen. Unique: each tweak is its own switch, and the effect hiding can be limited to dungeons so it never changes how
 * potions look elsewhere.
 */
object CameraTweaks : Module(
    "Camera",
    Category.Visuals,
    "Skip the front camera view; hide blindness and nausea on your screen."
) {
    private val noFront = BooleanSetting("Disable Front Camera", true, "F5 cycles only first person and the back view.")
    private val noBlind = BooleanSetting("Disable Blindness", true, "Hide the blindness effect.")
    private val noNausea = BooleanSetting("Disable Nausea", true, "Hide the nausea wobble.")
    private val dungeonOnly = BooleanSetting("Effects: Dungeons Only", false, "Only hide the effects inside a dungeon.")
    private val customDist = BooleanSetting("Custom Camera Distance", false, "Set how far the third person camera sits from you.")
    private val distance = NumberSetting("Camera Distance", 4.0, 1.0, 10.0, 0.1, "Blocks behind you.")
    private val noClip = BooleanSetting("Camera Clip", false, "The third person camera no longer stops at walls.")

    /** Read by CameraDistanceMixin: the chosen distance, or null for vanilla's. */
    fun customDistance(): Double? = if (isEnabled && customDist.value) distance.value else null
    val noClipActive get() = isEnabled && noClip.value

    init {
        addSettings(noFront, noBlind, noNausea, dungeonOnly, customDist, distance, noClip)
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (!isEnabled) return@register
            if (noFront.value && mc.options.cameraType == CameraType.THIRD_PERSON_FRONT) mc.options.setCameraType(CameraType.FIRST_PERSON)
            val player = mc.player ?: return@register
            if (dungeonOnly.value && !ced.cedclient.state.DungeonState.inDungeon) return@register
            if (noBlind.value) player.removeEffectNoUpdate(MobEffects.BLINDNESS)
            if (noNausea.value) player.removeEffectNoUpdate(MobEffects.NAUSEA)
        }
    }
}

/**
 * Stops block placement with the SkyBlock items you list (their ids, comma separated) and, if enabled, with every player
 * head. Unique: a one-line chat notice the first time per item so you know why nothing was placed.
 */
object NoItemPlace : Module(
    "No Item Place",
    Category.Skyblock,
    "Blocks placing the listed SkyBlock items (and optionally every skull) as blocks."
) {
    private val ids = TextSetting("Item IDs", "", "SkyBlock item ids that must not be placed, comma separated (e.g. BONZO_MASK).", 400)
    private val skulls = BooleanSetting("Block All Skulls", true, "Also block placing any player head.")
    private val notice = BooleanSetting("Notice Once", true, "A chat notice the first time an item is blocked.")

    private val told = HashSet<String>()

    init {
        addSettings(ids, skulls, notice)
        UseBlockCallback.EVENT.register(UseBlockCallback { player, level, hand, _ ->
            if (!isEnabled || !level.isClientSide) return@UseBlockCallback InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            val id = stack.skyblockId ?: return@UseBlockCallback InteractionResult.PASS
            val listed = ids.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.any { it.equals(id, ignoreCase = true) }
            val skull = skulls.value && stack.item == net.minecraft.world.item.Items.PLAYER_HEAD
            if (!listed && !skull) return@UseBlockCallback InteractionResult.PASS
            if (notice.value && told.add(id)) player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§b[CC] §7No Item Place: $id will not be placed"))
            InteractionResult.FAIL
        })
    }
}
//#endif
