package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.render.FilledBox
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB

/**
 * Boxes the secrets you take in a dungeon for a moment: the chest / lever / skull you clicked, the item you picked
 * up, the bat you hit (it stays boxed where it died). Only your own actions count, so it shows which secrets were yours.
 */
object SecretHighlight : Module(
    "Secret Highlight",
    Category.Skyblock,
    "Boxes the secrets you click, pick up or kill in dungeons for a moment."
) {
    private val color = ColorSetting("Box Color", Color(80, 255, 120), "Color of the box.")
    private val style = DropdownSetting("Style", listOf("Outline", "Filled", "Filled Outline"), "Filled Outline")
    private val blocks = BooleanSetting("Clicked Blocks", true, "Chests, levers and skulls (wither essence, redstone key) you click.")
    private val items = BooleanSetting("Picked Up Items", true, "Items you pick up.")
    private val bats = BooleanSetting("Bats", true, "Bats you hit.")
    private val duration = NumberSetting("Duration", 1.5, 0.25, 10.0, 0.25, "Seconds the box stays.")
    private val onlyInDungeon = BooleanSetting("Only In Dungeons", true, "Turn off to test elsewhere.")

    private val SHOW_MS get() = (duration.value * 1000).toLong()
    private class Mark(var box: AABB, var until: Long)

    private val marks = ArrayList<Mark>()
    private val hitBats = HashMap<Int, Mark>()
    /** Item entities near you last tick (id -> box), to see which one vanished into your inventory. */
    private var nearItems = HashMap<Int, AABB>()
    private var lastCount = 0

    private val mc get() = Minecraft.getInstance()
    private fun active() = isEnabled && (!onlyInDungeon.value || (DungeonState.active && !DungeonState.inBoss))

    init {
        addSettings(color, style, blocks, items, bats, duration, onlyInDungeon)

        UseBlockCallback.EVENT.register(UseBlockCallback { _, level, _, hit ->
            if (level.isClientSide && active() && blocks.value) {
                val state = level.getBlockState(hit.blockPos)
                if (state.`is`(Blocks.CHEST) || state.`is`(Blocks.TRAPPED_CHEST) || state.`is`(Blocks.LEVER) ||
                    state.`is`(Blocks.PLAYER_HEAD) || state.`is`(Blocks.PLAYER_WALL_HEAD))
                    marks += Mark(AABB(hit.blockPos), System.currentTimeMillis() + SHOW_MS)
            }
            InteractionResult.PASS
        })

        AttackEntityCallback.EVENT.register(AttackEntityCallback { _, level, _, entity, _ ->
            if (level.isClientSide && active() && bats.value && entity is Bat)
                hitBats.getOrPut(entity.id) { Mark(entity.boundingBox.inflate(0.3), Long.MAX_VALUE).also { marks += it } }
            InteractionResult.PASS
        })

        ClientTickEvents.END_CLIENT_TICK.register {
            val level = mc.level
            val player = mc.player
            val now = System.currentTimeMillis()
            marks.removeAll { it.until < now }
            if (level == null || player == null || !active()) { marks.clear(); hitBats.clear(); nearItems.clear(); return@register }
            // A hit bat follows its entity; once it is gone the box stays for SHOW_MS.
            val iter = hitBats.iterator()
            while (iter.hasNext()) {
                val (id, mark) = iter.next()
                val bat = level.getEntity(id)
                if (bat != null && bat.isAlive) mark.box = bat.boundingBox.inflate(0.3)
                else { mark.until = now + SHOW_MS; iter.remove() }
            }
            if (items.value) {
                val inv = player.inventory
                val count = (0 until 41).sumOf { inv.getItem(it).count }
                val seen = HashMap<Int, AABB>()
                for (e in level.getEntitiesOfClass(ItemEntity::class.java, player.boundingBox.inflate(6.0)) { true }) seen[e.id] = e.boundingBox.inflate(0.2)
                if (count > lastCount) for ((id, box) in nearItems) if (id !in seen && box.center.distanceToSqr(player.eyePosition) < 9.0)
                    marks += Mark(box, now + SHOW_MS)
                nearItems = seen
                lastCount = count
            }
        }

        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (marks.isEmpty() || !active()) return@AfterSolidFeatures
            val poseStack = context.poseStack()
            val cam = context.gameRenderer().mainCamera.position()
            val bufferSource = context.bufferSource()
            val shown = marks.map { it.box.move(-cam.x, -cam.y, -cam.z) }
            val c = color.value
            if (style.value != "Outline") {
                val fill = bufferSource.getBuffer(CustomRenderType.FILLED)
                for (box in shown) FilledBox.draw(poseStack, fill, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat * 0.4f)
                bufferSource.endBatch()
            }
            if (style.value != "Filled") {
                val buffer = bufferSource.getBuffer(CustomRenderType.LINES_DEPTH)
                for (box in shown) LineBox.draw(poseStack, buffer, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
                bufferSource.endBatch()
            }
        })
    }

    override fun onDisable() { marks.clear(); hitBats.clear(); nearItems.clear() }
}
