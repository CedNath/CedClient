package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.features.settings.TextSetting
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
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.network.chat.Component
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
    "Secret Clicked",
    Category.Skyblock,
    "Boxes the secrets you click, pick up or kill in dungeons for a moment.",
    defaultEnabled = true
) {
    private val color = ColorSetting("Box Color", Color(80, 255, 120), "Color of the box.")
    private val style = DropdownSetting("Style", listOf("Outline", "Filled", "Filled Outline"), "Filled Outline")
    private val blocks = BooleanSetting("Clicked Blocks", true, "Chests, levers and skulls (wither essence, redstone key) you click.")
    private val items = BooleanSetting("Picked Up Items", true, "Items you pick up.")
    private val bats = BooleanSetting("Bats", true, "Bats you hit.")
    private val sound = BooleanSetting("Sound", false, "Plays a ding when you take a secret (a bat: when it dies).")
    private val soundChoice = DropdownSetting("Sound Type", listOf("XP Orb", "Bell", "Chime", "Iron Xylophone", "Anvil Land"), "XP Orb", "Which sound plays.")
        .withDependency { sound.value }
    private val soundTest = ActionSetting("Play Test Sound") { ding(force = true) }
    private val duration = NumberSetting("Duration", 1.5, 0.25, 10.0, 0.25, "Seconds the box stays.")
    private val lockedTitle = BooleanSetting("Locked Chest Title", false, "Shows a title when you click a locked chest (\"That chest is locked!\").")
    private val lockedText = TextSetting("Locked Chest Text", "Locked Chest!", "Title text.", 32)
    private val lockedColor = ColorSetting("Locked Chest Color", Color(255, 85, 85), "Title color.")
    private val lockedSeconds = NumberSetting("Locked Chest Duration", 2.0, 0.5, 5.0, 0.5, "Seconds the title stays.")
    private val throughWalls = BooleanSetting("Through Walls", false, "Show the box through blocks.")
    private val onlyInDungeon = BooleanSetting("Only In Dungeons", true, "Turn off to test elsewhere.")

    init { addSettings(color, style, throughWalls, duration, blocks, items, bats, onlyInDungeon, sound, soundChoice, soundTest, lockedTitle, lockedText, lockedColor, lockedSeconds) }

    private val SHOW_MS get() = (duration.value * 1000).toLong()
    private class Mark(var box: AABB, var until: Long)

    private val marks = ArrayList<Mark>()
    private val hitBats = HashMap<Int, Mark>()

    private val mc get() = Minecraft.getInstance()
    private fun ding(force: Boolean = false) {
        if (!force && !sound.value) return
        val event = when (soundChoice.value) {
            "Bell" -> SoundEvents.BELL_BLOCK
            "Chime" -> SoundEvents.AMETHYST_BLOCK_CHIME
            "Iron Xylophone" -> SoundEvents.NOTE_BLOCK_IRON_XYLOPHONE.value()
            "Anvil Land" -> SoundEvents.ANVIL_LAND
            else -> SoundEvents.EXPERIENCE_ORB_PICKUP
        }
        mc.soundManager.play(SimpleSoundInstance.forUI(event, if (event === SoundEvents.EXPERIENCE_ORB_PICKUP) 1.2f else 1f, 1f))
    }
    /** SecretSignals: a secret item was taken near you. */
    fun onItemSecret(box: AABB) {
        if (!active() || !items.value) return
        marks += Mark(box.inflate(0.2), System.currentTimeMillis() + SHOW_MS)
        ding()
    }

    /** SecretSignals: the bat-secret sound; [box] = the closest bat seen. Bats you hit are already boxed and dinged on death. */
    fun onBatSecret(box: AABB?) {
        if (!active() || !bats.value || hitBats.isNotEmpty() || box == null) return
        marks += Mark(box.inflate(0.3), System.currentTimeMillis() + SHOW_MS)
        ding()
    }

    private fun active() = isEnabled && (!onlyInDungeon.value || (DungeonState.active && !DungeonState.inBoss))

    init {
        SecretSignals.ensure()

        on<ChatMessageEvent> {
            if (!lockedTitle.value || !active() || !it.unformattedText.trim().equals("That chest is locked!", true)) return@on
            mc.gui.hud.setTimes(3, (lockedSeconds.value * 20).toInt(), 10)
            mc.gui.hud.setTitle(Component.literal(lockedText.value).withColor(lockedColor.value.rgba and 0xFFFFFF))
            mc.gui.hud.setSubtitle(Component.empty())
        }

        UseBlockCallback.EVENT.register(UseBlockCallback { _, level, _, hit ->
            if (level.isClientSide && active() && blocks.value) {
                if (SecretSignals.isSecretBlock(level, hit.blockPos))
                    { marks += Mark(AABB(hit.blockPos), System.currentTimeMillis() + SHOW_MS); ding() }
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
            if (level == null || player == null || !active()) { marks.clear(); hitBats.clear(); return@register }
            // A hit bat follows its entity; once it is gone the box stays for SHOW_MS.
            val iter = hitBats.iterator()
            while (iter.hasNext()) {
                val (id, mark) = iter.next()
                val bat = level.getEntity(id)
                if (bat != null && bat.isAlive) mark.box = bat.boundingBox.inflate(0.3)
                else { mark.until = now + SHOW_MS; iter.remove(); ding() }
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
                val fill = bufferSource.getBuffer(if (throughWalls.value) CustomRenderType.FILLED_ESP else CustomRenderType.FILLED)
                for (box in shown) FilledBox.draw(poseStack, fill, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat * 0.4f)
                bufferSource.endBatch()
            }
            if (style.value != "Filled") {
                val buffer = bufferSource.getBuffer(if (throughWalls.value) CustomRenderType.LINES_ESP else CustomRenderType.LINES_DEPTH)
                for (box in shown) LineBox.draw(poseStack, buffer, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
                bufferSource.endBatch()
            }
        })
    }

    fun clear() { marks.clear(); hitBats.clear() }
}
