package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.utils.Color
import net.minecraft.client.Minecraft
import net.minecraft.client.color.block.BlockTintSources
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.renderer.block.FluidStateModelSet
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.Fluids
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Draws lava with the water texture (see FluidStateModelSetMixin) and, optionally, takes away the lava fog (LavaFogMixin).
 * Unique: the tint can be any colour, so lava can read as a blue pool, a green one, or anything else, and the chunk meshes are
 * rebuilt the moment the module is switched so the change shows without reloading.
 */
object LavaToWater : Module(
    "Lava To Water",
    Category.Visuals,
    "Draws lava with the water texture and hides the lava fog."
) {
    private val tint = BooleanSetting("Color Tint", false, "Tint the lava surface instead of the plain water blue.")
    private val tintColor = ColorSetting("Tint Color", Color(63, 118, 228), "The tint.").withDependency { tint.value }
    private val fog = BooleanSetting("Hide Fog", true, "No lava fog when you are in lava.")

    init { addSettings(tint, tintColor, fog) }

    /** True while the module is on; the mixins read these. */
    val active get() = isEnabled
    val hideFog get() = fog.value
    val tinted get() = tint.value
    val tintRgb get() = tintColor.value.rgba or (0xFF shl 24)

    override fun onEnable() = refresh()
    override fun onDisable() = refresh()

    /** Rebuild the chunk meshes so the new fluid look shows up (the mixin only changes what the model set returns). */
    private fun refresh() { Minecraft.getInstance().levelRenderer?.allChanged() }

    private fun isLava(fluid: Fluid) = fluid === Fluids.LAVA || fluid === Fluids.FLOWING_LAVA

    fun modelHook(set: FluidStateModelSet, state: FluidState, cir: CallbackInfoReturnable<FluidModel>) {
        if (!isEnabled || !isLava(state.type)) return
        val water = set.get(Fluids.WATER.defaultFluidState())
        if (!tint.value) { cir.returnValue = water; return }
        val rgb = tintRgb
        cir.returnValue = FluidModel(water.layer(), water.stillMaterial(), water.flowingMaterial(), water.overlayMaterial(), BlockTintSources.constant(rgb, rgb))
    }
}
