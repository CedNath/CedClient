package ced.cedclient.features.impl.render

import ced.cedclient.render.geometry
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.render.FilledBox
import ced.cedclient.render.LineBox
import ced.cedclient.render.pipeline.CustomRenderType
import ced.cedclient.state.IslandState
import ced.cedclient.utils.Color
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.monster.Silverfish

/**
 * Boxes Garden pests.
 *
 * Per the wiki, every Garden pest is a Silverfish or a Bat entity (the nametag's pest icon is a resource-pack
 * glyph, so matching text is fragile). Nothing else in the Garden uses those entities, so this boxes them.
 * The pest list refreshes every tick; boxes follow the interpolated position each frame so they move smoothly.
 *
//#if CHEAT
 * "See Through Walls" picks the *_ESP render types (no depth test) or the depth-tested ones, like FloorDrops.
//#else
//$ * Always drawn depth-tested (LINES_DEPTH / FILLED), so terrain hides it (Legit: no see-through-walls option).
//#endif
 */
object PestHighlighter : Module(
    "Pest Highlighter",
    Category.Render,
    "Highlights pests in the Garden"
) {
    private val mc: Minecraft = Minecraft.getInstance()

    private val color = ColorSetting("Box Color", Color(255, 85, 85), "Color of the box drawn around pests.")
    private val style = DropdownSetting("Style", listOf("Outline", "Filled", "Filled Outline"), "Outline")
//#if CHEAT
    private val seeThroughWalls = BooleanSetting(
        "See Through Walls",
        false,
        "Draw the box through blocks. Off: terrain hides it."
    )
//#endif
    private val onlyInGarden = BooleanSetting("Only In Garden", true, "Turn off to test elsewhere (boxes every silverfish and bat).")

    @Volatile private var pests: List<Entity> = emptyList()

    init {
//#if CHEAT
        addSettings(color, style, seeThroughWalls, onlyInGarden)
//#else
//$        addSettings(color, style, onlyInGarden)
//#endif

        ClientTickEvents.END_CLIENT_TICK.register {
            if (!shouldProcess()) {
                if (pests.isNotEmpty()) pests = emptyList()
                return@register
            }
            pests = scan()
        }

        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LevelRenderEvents.AfterSolidFeatures { context ->
            val current = pests
            if (!shouldProcess() || current.isEmpty()) return@AfterSolidFeatures

            val poseStack = context.poseStack()
            val cam = context.gameRenderer().mainCamera().position()
            val partial = mc.deltaTracker.getGameTimeDeltaPartialTick(false)
            // Pest hitboxes are tiny (silverfish 0.4 x 0.3), so pad them to stay visible.
            val shown = current.filter { it.isAlive }.map { e ->
                val shift = e.getPosition(partial).subtract(e.position())
                e.boundingBox.inflate(0.3).move(shift.x - cam.x, shift.y - cam.y, shift.z - cam.z)
            }
            val c = color.value
//#if CHEAT
            val through = seeThroughWalls.value
//#else
//$            val through = false
//#endif
            if (style.value != "Outline") {
                context.geometry(if (through) CustomRenderType.FILLED_ESP else CustomRenderType.FILLED) { pose, fill ->
                    // Fill at 40% of the colour's opacity so the pest stays visible, like DoorESP.
                    for (box in shown) FilledBox.draw(pose, fill, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat * 0.4f)
                }
            }
            if (style.value != "Filled") {
                context.geometry(if (through) CustomRenderType.LINES_ESP else CustomRenderType.LINES_DEPTH) { pose, buffer ->
                    for (box in shown) LineBox.draw(pose, buffer, box, c.redFloat, c.greenFloat, c.blueFloat, c.alphaFloat)
                }
            }
        })
    }

    override fun onDisable() {
        pests = emptyList()
    }

    private fun shouldProcess(): Boolean {
        if (!isEnabled) return false
        return !onlyInGarden.value || IslandState.island == IslandState.Island.GARDEN
    }

    private fun scan(): List<Entity> {
        val level = mc.level ?: return emptyList()
        return level.entitiesForRendering().filter { (it is Silverfish || it is Bat) && it.isAlive }
    }
}
