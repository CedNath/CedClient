package ced.cedclient.render.pipeline

import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.LayeringTransform
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
            .createRenderSetup()
    )

    /**
     * Normal depth-tested lines (vanilla LINES pipeline) -- same setup as LINES_ESP, except
     * walls and terrain hide it. Used by features with a "See Through Walls" toggle.
     */
    /** Translucent filled boxes (FilledBox), depth-tested, no culling. */
    val FILLED: RenderType = RenderType.create(
        "filled-box",
        RenderSetup.builder(CustomRenderPipelines.FILLED_BOX)
            .sortOnUpload()
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .createRenderSetup()
    )

    /** Opaque colored quads that write depth (Halo). */
    val SOLID_QUADS: RenderType = RenderType.create(
        "solid-quads",
        RenderSetup.builder(CustomRenderPipelines.SOLID_QUADS).createRenderSetup()
    )

    /** FILLED, but through walls. */
    val FILLED_ESP: RenderType = RenderType.create(
        "filled-box-esp",
        RenderSetup.builder(CustomRenderPipelines.FILLED_BOX_ESP)
            .sortOnUpload()
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .createRenderSetup()
    )

    val LINES_DEPTH: RenderType = RenderType.create(
        "lines-depth",
        RenderSetup.builder(RenderPipelines.LINES)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .createRenderSetup()
    )
}