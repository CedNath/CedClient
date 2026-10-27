//#if CHEAT
package ced.cedclient.features.impl.funqol

import ced.cedclient.events.PlaySoundEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.impl.render.Box3D
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.state.DungeonState
import ced.cedclient.utils.Color
import ced.cedclient.utils.skyblockId
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoorHingeSide
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.phys.AABB
import net.minecraft.world.entity.player.Input

/**
 * F7 phase 2: swaps the four diorite pillars for coloured glass on your screen only, so you see the dragon crystals' room clearly.
 * Unique: each pillar keeps the colour of its crystal (lime, yellow, purple, red) and the glass can be turned off per pillar, and
 * the replacement only runs while the pillar still has diorite in it, so it costs nothing once the phase is over.
 */
object IHateDiorite : Module(
    "I Hate Diorite",
    Category.Dungeons,
    "Shows the F7 phase 2 diorite pillars as coloured glass."
) {
    private class Pillar(val name: String, val pos: BlockPos, val glass: BlockState, val setting: BooleanSetting)

    private val green = BooleanSetting("Lime Pillar", true, "The pillar at the lime crystal.")
    private val yellow = BooleanSetting("Yellow Pillar", true, "The pillar at the yellow crystal.")
    private val purple = BooleanSetting("Purple Pillar", true, "The pillar at the purple crystal.")
    private val red = BooleanSetting("Red Pillar", true, "The pillar at the red crystal.")

    private val pillars = listOf(
        Pillar("lime", BlockPos(46, 169, 41), Blocks.STAINED_GLASS.lime().defaultBlockState(), green),
        Pillar("yellow", BlockPos(46, 169, 65), Blocks.STAINED_GLASS.yellow().defaultBlockState(), yellow),
        Pillar("purple", BlockPos(100, 169, 65), Blocks.STAINED_GLASS.purple().defaultBlockState(), purple),
        Pillar("red", BlockPos(100, 169, 41), Blocks.STAINED_GLASS.red().defaultBlockState(), red),
    )

    private const val RADIUS = 3
    private const val HEIGHT = 37

    init {
        addSettings(green, yellow, purple, red)
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            val level = mc.level ?: return@register
            if (!isEnabled || DungeonState.f7Phase != 2) return@register
            for (p in pillars) {
                if (!p.setting.value) continue
                for (pos in BlockPos.betweenClosed(p.pos.offset(-RADIUS, 0, -RADIUS), p.pos.offset(RADIUS, HEIGHT, RADIUS))) {
                    val b = level.getBlockState(pos).block
                    if (b == Blocks.DIORITE || b == Blocks.POLISHED_DIORITE) level.setBlock(pos.immutable(), p.glass, 2)
                }
            }
        }
    }
}

/**
 * F7 phase 3 section 3: the iron doors the server sends point the wrong way on your screen, so any door that is not air is
 * set to a closed east facing door. Unique: it can be limited to the section, and a Fix Count counter is kept in debug.
 */
object DoorFix : Module(
    "Door Fix",
    Category.Dungeons,
    "Fixes how the F7 section 3 iron doors look.",
    defaultEnabled = true
) {
    private val doorState: BlockState = Blocks.IRON_DOOR.defaultBlockState()
        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
        .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
        .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
        .setValue(BlockStateProperties.OPEN, false)

    private val doors: List<BlockPos> = buildList {
        for (i in 0..6) {
            if (i != 1) add(BlockPos(1, 112 + i * 4, 104))
            if (i < 6) add(BlockPos(1, 113 + i * 4, 86))
            add(BlockPos(1, 112 + i * 4, 68))
        }
    }

    private val onlySection = BooleanSetting("Only In Section 3", true, "Only while you are in F7 phase 3, section 3.")

    init {
        addSettings(onlySection)
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            val level = mc.level ?: return@register
            if (!isEnabled || !DungeonState.inDungeon) return@register
            if (onlySection.value && DungeonState.f7P3Section != 3) return@register
            var fixed = 0
            for (pos in doors) if (!level.getBlockState(pos).isAir && level.getBlockState(pos) != doorState) { level.setBlock(pos, doorState, 2); fixed++ }
            if (fixed > 0) ced.cedclient.utils.Debug.line("Door Fix", "fixed $fixed doors")
        }
    }
}

/**
 * Shows the dungeon doors as glass on your screen: entrance, wither and blood doors each with their own colour. Unique: every
 * door type has its own colour (default: white entrance, black wither, red blood), and doors that are already open are left as
 * the server sends them.
 */
object IHateDoors : Module(
    "I Hate Doors",
    Category.Dungeons,
    "Shows the entrance, wither and blood doors as glass."
) {
    private val colors = listOf("Default", "White", "Black", "Cyan", "Light Blue", "Red", "Pink", "Orange", "Magenta", "Yellow", "Lime", "Gray", "Light Gray", "Purple", "Blue", "Brown", "Green")
    private val glass: List<BlockState> = listOf(
        Blocks.GLASS, Blocks.STAINED_GLASS.white(), Blocks.STAINED_GLASS.black(), Blocks.STAINED_GLASS.cyan(), Blocks.STAINED_GLASS.lightBlue(),
        Blocks.STAINED_GLASS.red(), Blocks.STAINED_GLASS.pink(), Blocks.STAINED_GLASS.orange(), Blocks.STAINED_GLASS.magenta(), Blocks.STAINED_GLASS.yellow(),
        Blocks.STAINED_GLASS.lime(), Blocks.STAINED_GLASS.gray(), Blocks.STAINED_GLASS.lightGray(), Blocks.STAINED_GLASS.purple(), Blocks.STAINED_GLASS.blue(),
        Blocks.STAINED_GLASS.brown(), Blocks.STAINED_GLASS.green()
    ).map { it.defaultBlockState() }

    private val entrance = BooleanSetting("Glass Entrance Door", false, "The entrance door.")
    private val wither = BooleanSetting("Glass Wither Door", true, "The wither doors.")
    private val blood = BooleanSetting("Glass Blood Door", true, "The blood door.")
    private val entranceColor = DropdownSetting("Entrance Glass", colors, "White", "Colour of the entrance door.")
    private val witherColor = DropdownSetting("Wither Glass", colors, "Black", "Colour of the wither doors.")
    private val bloodColor = DropdownSetting("Blood Glass", colors, "Red", "Colour of the blood door.")

    private val spots: List<Pair<Int, Int>> = (0..10).flatMap { i -> (0..10).map { j -> i to j } }
        .filter { (i, j) -> (i % 2 == 1) != (j % 2 == 1) }
        .map { (i, j) -> -185 + 16 * i to -185 + 16 * j }

    private fun stateFor(source: net.minecraft.world.level.block.Block): BlockState? = when (source) {
        Blocks.INFESTED_CHISELED_STONE_BRICKS -> if (entrance.value) glass[colors.indexOf(entranceColor.value).coerceAtLeast(0)] else null
        Blocks.COAL_BLOCK -> if (wither.value) glass[colors.indexOf(witherColor.value).coerceAtLeast(0)] else null
        Blocks.DYED_TERRACOTTA.red() -> if (blood.value) glass[colors.indexOf(bloodColor.value).coerceAtLeast(0)] else null
        else -> null
    }

    init {
        addSettings(entrance, wither, blood, entranceColor, witherColor, bloodColor)
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            val level = mc.level ?: return@register
            if (!isEnabled || !DungeonState.inDungeon || DungeonState.inBoss || mc.player?.tickCount?.rem(10) != 0) return@register
            val pos = BlockPos.MutableBlockPos()
            for ((x, z) in spots) {
                val source = level.getBlockState(pos.set(x, 69, z)).block
                val replacement = stateFor(source) ?: continue
                for (xx in x - 1..x + 1) for (zz in z - 1..z + 1) for (y in 69..72) {
                    pos.set(xx, y, zz)
                    if (level.getBlockState(pos).block == source) level.setBlock(pos.immutable(), replacement, 2)
                }
            }
        }
    }
}

/**
 * Spring Boots charge: the pitch of the note block plings (and the firework that resets it) while you sneak on the boots gives
 * the jump height; it is shown as a HUD value and a box where you would land. Unique: the HUD can show percent or blocks, and
 * the box uses a colour that runs from red (low) to green (full).
 */
object SpringBoots : RowHud("Spring Boots", "Shows the Spring Boots jump charge as a HUD value and a box at the jump height.", "spring_boots", 3, 180) {
    private val mode = DropdownSetting("Draw Mode", listOf("Percentage", "Blocks"), "Percentage", "How the HUD shows the charge.")
    private val box = BooleanSetting("Draw Box", true, "A box at the jump height above you.")
    private val phase = BooleanSetting("See Through Walls", true, "Draw the box through walls.")

    private val HIGH = floatArrayOf(0.82539684f, 0.8888889f, 0.93650794f, 1.0476191f, 1.1746032f, 1.3174603f, 1.7777778f)
    private val RESET = floatArrayOf(0.0952381f, 1.6984127f)
    private const val LOW = 0.6984127f
    private val heights = floatArrayOf(
        0.0f, 3.0f, 6.5f, 9.0f, 11.5f, 13.5f, 16.0f, 18.0f, 19.0f, 20.5f, 22.5f, 25.0f, 26.5f, 28.0f, 29.0f, 30.0f, 31.0f, 33.0f,
        34.0f, 35.5f, 37.0f, 38.0f, 39.5f, 40.0f, 41.0f, 42.5f, 43.5f, 44.0f, 45.0f, 46.0f, 47.0f, 48.0f, 49.0f, 50.0f, 51.0f, 52.0f,
        53.0f, 54.0f, 55.0f, 56.0f, 57.0f, 58.0f, 59.0f, 60.0f, 61.0f
    )

    private var height = 0f
    private var highs = 0
    private var lows = 0

    private fun reset() { height = 0f; highs = 0; lows = 0 }

    private fun charge() = (lows + highs).toFloat() / (heights.size - 1)

    override fun rows(): List<Triple<String, String, Int>>? {
        if (!isEnabled || height <= 0f) return null
        val pct = mode.value == "Percentage"
        val value = if (pct) "%.1f%%".format(java.util.Locale.ROOT, height / heights.last() * 100f) else "%.1f".format(java.util.Locale.ROOT, height)
        val c = charge()
        val color = (0xFF shl 24) or ((255 * (1 - c)).toInt().coerceIn(0, 255) shl 16) or ((255 * c).toInt().coerceIn(0, 255) shl 8)
        return listOf(Triple(if (pct) "Charge" else "Blocks", value, color))
    }

    override fun preview() = listOf(Triple("Charge", "55.0%", 0xFFFFAA00.toInt()))

    init {
        addSettings(mode, box, phase)
        loadPosition()
        on<PlaySoundEvent> {
            val player = Minecraft.getInstance().player ?: return@on
            if (!isEnabled || !player.onGround()) return@on
            val pling = it.soundName.endsWith("note_block.pling")
            val firework = it.soundName.endsWith("firework_rocket.launch")
            if (!pling && !firework) return@on
            if (pling && player.isCrouching && player.getItemBySlot(EquipmentSlot.FEET).skyblockId == "SPRING_BOOTS") {
                when {
                    it.pitch == LOW -> lows = (lows + 1).coerceAtMost(2)
                    HIGH.any { p -> p == it.pitch } -> highs++
                }
                height = heights[(lows + highs).coerceIn(heights.indices)]
            } else if (firework && RESET.any { p -> p == it.pitch }) reset()
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (height > 0f && (mc.player == null || !mc.player!!.isCrouching || !mc.player!!.onGround())) reset()
        }
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            if (!isEnabled || !box.value || height <= 0f) return@AfterSolidFeatures
            val p = Minecraft.getInstance().player ?: return@AfterSolidFeatures
            val c = charge()
            val color = Color((255 * (1 - c)).toInt().coerceIn(0, 255), (255 * c).toInt().coerceIn(0, 255), 60)
            Box3D.draw(context, listOf(AABB(p.x - 0.5, p.y + height, p.z - 0.5, p.x + 0.5, p.y + height + 1.0, p.z + 0.5) to color))
        })
    }
}

/**
 * When you hold two opposite movement keys (A and D, or W and S) vanilla cancels both and you stop. This keeps the one you
 * pressed last instead, like a snap-tap keyboard. Unique: it can be limited to horizontal keys or applied to forward and back
 * too, and it ignores keys while a screen is open.
 */
object SnappyTappy : Module(
    "Snappy Tappy",
    Category.Cheats,
    "Holding two opposite movement keys keeps moving in the direction you pressed last."
) {
    private val strafe = BooleanSetting("Left / Right", true, "A and D.")
    private val straight = BooleanSetting("Forward / Back", true, "W and S.")

    private val pressedAt = HashMap<KeyMapping, Long>()
    private val down = HashSet<KeyMapping>()

    init { addSettings(strafe, straight) }

    private fun physicallyDown(key: KeyMapping) = com.mojang.blaze3d.platform.InputConstants.isKeyDown(Minecraft.getInstance().window, KeyMappingHelper.getBoundKeyOf(key).value)

    /** Called from KeyboardInputMixin each tick with the input vanilla built; returns the same input or a fixed one. */
    @JvmStatic
    fun resolve(input: Input): Input {
        val mc = Minecraft.getInstance()
        if (!isEnabled || mc.gui.screen() != null) { down.clear(); pressedAt.clear(); return input }
        val o = mc.options
        for (key in listOf(o.keyLeft, o.keyRight, o.keyUp, o.keyDown)) {
            if (physicallyDown(key)) { if (down.add(key)) pressedAt[key] = System.nanoTime() } else { down.remove(key); pressedAt.remove(key) }
        }
        fun newer(a: KeyMapping, b: KeyMapping) = (pressedAt[a] ?: 0L) >= (pressedAt[b] ?: 0L)
        var f = input.forward(); var b = input.backward(); var l = input.left(); var r = input.right()
        if (straight.value && o.keyUp in down && o.keyDown in down) { if (newer(o.keyUp, o.keyDown)) b = false else f = false }
        if (strafe.value && o.keyLeft in down && o.keyRight in down) { if (newer(o.keyLeft, o.keyRight)) r = false else l = false }
        if (f == input.forward() && b == input.backward() && l == input.left() && r == input.right()) return input
        return Input(f, b, l, r, input.jump(), input.shift(), input.sprint())
    }
}
//#endif
