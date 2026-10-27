package ced.cedclient.render.pipeline

import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.client.renderer.RenderPipelines
import java.util.Optional

/**
 * Depth-disabled variants of vanilla pipelines, used to draw ESP boxes/tracers
 * that render through walls. Adapted from OdinFabric's CustomRenderPipelines.
 */
object CustomRenderPipelines {
    val LINES_ESP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withDepthStencilState(Optional.empty())
            .withLocation("cedclient/lines_esp")
            .build()
    )

    /** Vanilla's depth-tested translucent filled box, with both sides of every face drawn. */
    val FILLED_BOX: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withCull(false)
            .withLocation("cedclient/filled_box")
            .build()
    )

    /** Opaque, depth-writing colored quads (Halo): unlike DEBUG_QUADS, clouds/translucents drawn later stay behind it. */
    val SOLID_QUADS: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withCull(false)
            .withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT)
            .withLocation("cedclient/solid_quads")
            .build()
    )

    /** FILLED_BOX without depth test: shows through walls. */
    val FILLED_BOX_ESP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withCull(false)
            .withDepthStencilState(Optional.empty())
            .withLocation("cedclient/filled_box_esp")
            .build()
    )
}