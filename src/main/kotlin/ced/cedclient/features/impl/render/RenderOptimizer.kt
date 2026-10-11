package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.fog.FogData
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment
import net.minecraft.client.renderer.fog.environment.DarknessFogEnvironment
import net.minecraft.client.renderer.fog.environment.FogEnvironment
import net.minecraft.client.renderer.fog.environment.LavaFogEnvironment
import net.minecraft.client.renderer.fog.environment.PowderedSnowFogEnvironment
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment
import net.minecraft.resources.Identifier

/**
 * Turns off fog and screen overlays, one toggle each. Hooks:
 *   FogRendererMixin          -> fog (per environment, plus the fade at the render-distance edge)
 *   ScreenEffectRendererMixin -> underwater / fire / in-wall overlays
 *   GuiOverlayMixin           -> vignette, pumpkin, powder snow, portal, nausea overlays
 *   WeatherEffectRendererMixin-> rain / snow
 */
object RenderOptimizer : Module(
    "Render Optimizer",
    Category.Render,
    "Disable fog (any kind), underwater / fire overlays, vignette, weather and more"
) {
    // ---- fog ----
    private val noAtmosphericFog = BooleanSetting("No World Fog", true, "Distance / rain haze, and the Nether and End fog.")
    private val noRenderDistanceFog = BooleanSetting("No Render Distance Fog", false, "The fade at the edge of your render distance (chunks end with a hard edge).")
    private val noWaterFog = BooleanSetting("No Water Fog", true, "See clearly underwater.")
    private val noLavaFog = BooleanSetting("No Lava Fog", true, "See clearly in lava.")
    private val noSnowFog = BooleanSetting("No Powder Snow Fog", true, "See clearly inside powder snow.")
    private val noBlindnessFog = BooleanSetting("No Blindness Fog", false, "Blindness effect fog.")
    private val noDarknessFog = BooleanSetting("No Darkness Fog", false, "Darkness effect (Warden) fog.")

    // ---- overlays ----
    private val noWaterOverlay = BooleanSetting("No Underwater Overlay", true, "The blue texture on your screen underwater.")
    private val noFireOverlay = BooleanSetting("No Fire Overlay", true, "The flames on your screen while burning.")
    private val noWallOverlay = BooleanSetting("No In-Wall Overlay", false, "The block texture when your head is inside a block.")
    private val noVignette = BooleanSetting("No Vignette", false, "The dark screen edges.")
    private val noPumpkin = BooleanSetting("No Pumpkin Overlay", true, "Carved pumpkin blur on your head.")
    private val noSnowOverlay = BooleanSetting("No Freezing Overlay", true, "Frosty edges while freezing in powder snow.")
    private val noPortal = BooleanSetting("No Portal Overlay", false, "Purple swirl while standing in a Nether portal.")
    private val noNausea = BooleanSetting("No Nausea Overlay", false, "Wobble overlay from nausea.")
    private val noWeather = BooleanSetting("No Weather", false, "Hide rain and snow (and their splash particles / sounds).")

    private val fogSettings = listOf(
        noAtmosphericFog, noRenderDistanceFog, noWaterFog, noLavaFog, noSnowFog, noBlindnessFog, noDarknessFog,
    )
    private val overlaySettings = listOf(
        noWaterOverlay, noFireOverlay, noWallOverlay, noVignette, noPumpkin, noSnowOverlay, noPortal, noNausea, noWeather,
    )

    private val settingsAction = ActionSetting("Open Settings", "All Render Optimizer toggles in one window.") {
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(
            SettingsPopup(
                "Render Optimizer",
                columns = listOf(
                    listOf(SettingsPopup.Section("Fog", fogSettings)),
                    listOf(SettingsPopup.Section("Overlays", overlaySettings)),
                ),
                height = 560f,
            )
        )
    }

    init {
        addSettings(*(fogSettings + overlaySettings).toTypedArray(), settingsAction)
        // Only "Open Settings" stays in the panel; the toggles live in the popup.
        (fogSettings + overlaySettings).forEach { it.inPopup = true }
    }

    private fun on(setting: BooleanSetting) = isEnabled && setting.value

    // Pushed far past any render distance; start < end so the shader's fog ramp never divides by zero.
    private const val FAR_START = 1_000_000f
    private const val FAR_END = 2_000_000f

    /** After one fog environment has set up the fog: clears it again if that kind is disabled. */
    @JvmStatic
    fun afterEnvironmentFog(env: FogEnvironment, data: FogData, renderDistance: Float) {
        if (!isEnabled) return
        val disabled = when (env) {
            is AtmosphericFogEnvironment -> noAtmosphericFog.value
            is WaterFogEnvironment -> noWaterFog.value
            is LavaFogEnvironment -> noLavaFog.value
            is PowderedSnowFogEnvironment -> noSnowFog.value
            is BlindnessFogEnvironment -> noBlindnessFog.value
            is DarknessFogEnvironment -> noDarknessFog.value
            else -> false
        }
        if (!disabled) return
        data.environmentalStart = FAR_START
        data.environmentalEnd = FAR_END
        // Water / lava / snow / effects also pull the sky and clouds in close -- put them back to normal range.
        if (env !is AtmosphericFogEnvironment) {
            data.skyEnd = maxOf(data.skyEnd, renderDistance)
            data.cloudEnd = maxOf(data.cloudEnd, Minecraft.getInstance().options.cloudRange().get() * 16f)
        }
    }

    /** After the whole fog setup: removes the render-distance edge fade if disabled. */
    @JvmStatic
    fun afterFog(data: FogData) {
        if (!on(noRenderDistanceFog)) return
        data.renderDistanceStart = FAR_START
        data.renderDistanceEnd = FAR_END
    }

    @JvmStatic fun hideWaterOverlay() = on(noWaterOverlay)
    @JvmStatic fun hideFireOverlay() = on(noFireOverlay)
    @JvmStatic fun hideWallOverlay() = on(noWallOverlay)
    @JvmStatic fun hideVignette() = on(noVignette)
    @JvmStatic fun hidePortal() = on(noPortal)
    @JvmStatic fun hideNausea() = on(noNausea)
    @JvmStatic fun hideWeather() = on(noWeather)

    /** Full-screen texture overlays: the pumpkin blur comes from the helmet's equippable camera overlay. */
    @JvmStatic
    fun hideTextureOverlay(texture: Identifier): Boolean {
        if (!isEnabled) return false
        val path = texture.path
        return (noPumpkin.value && path.contains("pumpkinblur")) ||
                (noSnowOverlay.value && path.contains("powder_snow_outline"))
    }
}
