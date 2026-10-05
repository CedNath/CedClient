package ced.cedclient.render.pipeline

import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType

/**
 * See-through-walls variant of RenderTypes.lines(), used for ESP boxes/tracers.
 */
object CustomRenderType {
    val LINES_ESP: RenderType = RenderType.create(
        "lines-esp",
        RenderSetup.builder(CustomRenderPipelines.LINES_ESP)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
            .createRenderSetup()
    )

    /**
     * Normal depth-tested lines (vanilla LINES pipeline) -- same setup as LINES_ESP, except
     * walls and terrain hide it. Used by features with a "See Through Walls" toggle.
     */
    val LINES_DEPTH: RenderType = RenderType.create(
        "lines-depth",
        RenderSetup.builder(RenderPipelines.LINES)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
            .createRenderSetup()
    )
}