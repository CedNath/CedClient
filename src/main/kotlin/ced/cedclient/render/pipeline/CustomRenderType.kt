package ced.cedclient.render.pipeline

import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType

/**
 * Depth-tested line render type used for the highlight boxes. Walls and terrain hide it.
 */
object CustomRenderType {
    /** Normal depth-tested lines (vanilla LINES pipeline): walls and terrain hide it. */
    val LINES_DEPTH: RenderType = RenderType.create(
        "lines-depth",
        RenderSetup.builder(RenderPipelines.LINES)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .setOutputTarget(OutputTarget.ITEM_ENTITY_TARGET)
            .createRenderSetup()
    )
}