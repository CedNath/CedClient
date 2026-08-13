package ced.cedclient.ui.nvg

import com.mojang.blaze3d.opengl.GlStateManager
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import net.minecraft.client.renderer.MultiBufferSource
import org.joml.Matrix3x2f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL30

class NVGSpecialRenderer(
    vertexConsumers: MultiBufferSource.BufferSource
) : PictureInPictureRenderer<NVGSpecialRenderer.NVGRenderState>(vertexConsumers) {

    // Cache one FBO per (colorTex, depthTex) glId pair, since DirectStateAccess
    // (Mojang's internal FBO cache) is no longer reachable from public API.
    private val fboCache = HashMap<Pair<Int, Int>, Int>()

    override fun renderToTexture(state: NVGRenderState, poseStack: PoseStack) {
        val colorTexView = RenderSystem.outputColorTextureOverride ?: return
        val depthTexView = RenderSystem.outputDepthTextureOverride ?: return

        val glColorTex = (colorTexView.texture() as? GlTexture) ?: return
        val glDepthTex = (depthTexView.texture() as? GlTexture) ?: return

        val fbWidth = glColorTex.getWidth(0)
        val fbHeight = glColorTex.getHeight(0)

        val fbo = fboCache.getOrPut(glColorTex.glId() to glDepthTex.glId()) {
            val newFbo = GL30.glGenFramebuffers()
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, newFbo)
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, glColorTex.glId(), 0)
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, glDepthTex.glId(), 0)
            newFbo
        }

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
        GlStateManager._viewport(0, 0, fbWidth, fbHeight)

        // --- NEW: FBO completeness check ---
        val status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            println("NVG FBO INCOMPLETE! status = $status")
        }
        // ------------------------------------

        // IMPORTANT: NanoVG's GL3 backend assumes texture unit 0 (GL_TEXTURE0)
        // is active and binds the font atlas / image textures there without
        // setting the active unit itself each draw. Minecraft's renderer
        // routinely leaves a *different* unit active (multiple samplers per
        // pipeline stage), so without this reset, NanoVG's glBindTexture calls
        // land on whatever unit MC last left active — the font atlas gets
        // bound to the wrong unit, and the shader samples nothing/garbage from
        // unit 0. That's why solid nvgRect()/nvgCircle() fills (which never
        // sample a texture) render fine while nvgText() renders fully
        // transparent. Using GlStateManager (not raw GL13.glActiveTexture)
        // keeps MC's own cached "active texture unit" state in sync, so
        // whatever renders after this PIP pass doesn't desync in the other
        // direction.
        GlStateManager._activeTexture(GL13.GL_TEXTURE0)

        // NanoVG draws in framebuffer space
        NVGRenderer.beginFrame(fbWidth.toFloat(), fbHeight.toFloat())
        state.renderContent()
        NVGRenderer.endFrame()
        state.afterRender?.invoke()


        GlStateManager._disableDepthTest()
        GlStateManager._enableBlend()
        GlStateManager._blendFuncSeparate(770, 771, 1, 0)
    }

    override fun getTranslateY(height: Int, windowScaleFactor: Int): Float = height / 2f
    override fun getRenderStateClass(): Class<NVGRenderState> = NVGRenderState::class.java
    override fun getTextureLabel(): String = "nvg_renderer"

    data class NVGRenderState(
        private val x: Int,
        private val y: Int,
        private val width: Int,
        private val height: Int,
        private val poseMatrix: Matrix3x2f,
        private val scissor: ScreenRectangle?,
        private val bounds: ScreenRectangle?,
        val renderContent: () -> Unit,
        val afterRender: (() -> Unit)? = null
    ) : PictureInPictureRenderState {

        override fun scale(): Float = 1f
        override fun x0(): Int = x
        override fun y0(): Int = y
        override fun x1(): Int = x + width
        override fun y1(): Int = y + height
        override fun scissorArea(): ScreenRectangle? = scissor
        override fun bounds(): ScreenRectangle? = bounds
    }

    companion object {

        fun draw(
            context: GuiGraphicsExtractor,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            renderContent: () -> Unit,
            afterRender: (() -> Unit)? = null
        ) {
            val scissor = context.scissorStack.peek()
            val pose = Matrix3x2f(context.pose())
            val bounds = createBounds(x, y, x + width, y + height, pose, scissor)

            val state = NVGRenderState(
                x,
                y,
                width,
                height,
                pose,
                scissor,
                bounds,
                renderContent,
                afterRender
            )

            context.guiRenderState.addPicturesInPictureState(state)
        }

        private fun createBounds(
            x0: Int,
            y0: Int,
            x1: Int,
            y1: Int,
            pose: Matrix3x2f,
            scissorArea: ScreenRectangle?
        ): ScreenRectangle? {
            val screenRect = ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose)
            return if (scissorArea != null) scissorArea.intersection(screenRect) else screenRect
        }
    }
}