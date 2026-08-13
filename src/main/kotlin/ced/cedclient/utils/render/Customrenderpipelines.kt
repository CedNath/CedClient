package ced.cedclient.utils.render

import com.mojang.blaze3d.pipeline.RenderPipeline
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
}