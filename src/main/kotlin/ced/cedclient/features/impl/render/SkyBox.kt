package ced.cedclient.features.impl.render

import ced.cedclient.config.CedPaths
import ced.cedclient.utils.Debug
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.vertex.VertexFormat
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import java.io.File
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.function.Supplier

/**
 * Draws a sky cube from a picture in the skyboxes folder (config/cedclient/skyboxes/<name>.png), in place of the
 * vanilla sky colour. The picture is a 3 x 2 grid of square faces:
 *   top row:    down,  up,    west
 *   bottom row: north, east,  south
 * (so a panorama reads north > east > south > west, clockwise from above; the up face's lower edge meets the east face).
 * Drawn with the End sky's pipeline, like SkyRenderer.renderEndSky, from SkyRendererSkyboxMixin.
 * shortcut: the cube does not turn with the time of day, and the picture is kept at full size on the GPU (a 6144 x 4096 one is ~100 MB).
 */
object SkyBox {
    val folder: File get() = CedPaths.dir.resolve("skyboxes")

    private var loaded: String? = null
    private var failed: String? = null
    private var texture: DynamicTexture? = null
    private var buffer: GpuBuffer? = null

    /** Names (without .png) of the pictures in the folder. */
    fun list(): List<String> = folder.listFiles { f -> f.extension.equals("png", true) }?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun reset() {
        texture?.close(); buffer?.close()
        texture = null; buffer = null; loaded = null; failed = null
    }

    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    private fun load(name: String) {
        reset()
        val file = File(folder, "$name.png")
        try {
            val image = file.inputStream().use { NativeImage.read(it) }
            val w = image.width.toFloat(); val h = image.height.toFloat()
            texture = DynamicTexture(Supplier { "CedClient skybox $name" }, image)
            buffer = build(w, h)
            loaded = name
            Debug.line("SkyBox", "loaded $name (${image.width}x${image.height})")
        } catch (e: Exception) {
            failed = name
            Debug.line("SkyBox", "could not load $name: ${e.message}")
        }
    }

    private fun build(w: Float, h: Float): GpuBuffer {
        val fmt = DefaultVertexFormat.POSITION_TEX_COLOR
        ByteBufferBuilder.exactlySized(24 * fmt.vertexSize).use { bytes ->
            val b = BufferBuilder(bytes, VertexFormat.Mode.QUADS, fmt)
            val r = 100f
            // face(column, row, top-left, top-right, bottom-right, bottom-left), corners as seen from inside the cube
            fun face(col: Int, row: Int, tl: FloatArray, tr: FloatArray, br: FloatArray, bl: FloatArray) {
                val iu = 1f / w; val iv = 1f / h // half a texel in, so linear filtering doesn't bleed over the seams
                val u0 = col / 3f + iu; val u1 = (col + 1) / 3f - iu
                val v0 = row / 2f + iv; val v1 = (row + 1) / 2f - iv
                // Order top-left, bottom-left, bottom-right, top-right: the winding that faces the camera in the middle.
                for ((p, uv) in listOf(tl to (u0 to v0), bl to (u0 to v1), br to (u1 to v1), tr to (u1 to v0)))
                    b.addVertex(p[0], p[1], p[2]).setUv(uv.first, uv.second).setColor(-1)
            }
            face(1, 1, v(r, r, -r), v(r, r, r), v(r, -r, r), v(r, -r, -r))       // east
            face(0, 1, v(-r, r, -r), v(r, r, -r), v(r, -r, -r), v(-r, -r, -r))   // north
            face(2, 1, v(r, r, r), v(-r, r, r), v(-r, -r, r), v(r, -r, r))       // south
            face(2, 0, v(-r, r, r), v(-r, r, -r), v(-r, -r, -r), v(-r, -r, r))   // west
            face(1, 0, v(-r, r, -r), v(-r, r, r), v(r, r, r), v(r, r, -r))       // up
            face(0, 0, v(-r, -r, r), v(-r, -r, -r), v(r, -r, -r), v(r, -r, r))   // down
            return b.buildOrThrow().use { RenderSystem.getDevice().createBuffer(Supplier { "CedClient skybox" }, 40, it.vertexBuffer()) }
        }
    }

    /** Draws the cube [name] with the camera's current view; does nothing when the picture can't be loaded. */
    fun render(name: String) {
        if (failed == name) return
        if (loaded != name) load(name)
        val tex = texture ?: return
        val buf = buffer ?: return
        val quads = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)
        val indices = quads.getBuffer(36)
        val target = Minecraft.getInstance().mainRenderTarget
        val transform = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrix(), Vector4f(1f, 1f, 1f, 1f), Vector3f(), Matrix4f())
        RenderSystem.getDevice().createCommandEncoder()
            .createRenderPass(Supplier { "CedClient skybox" }, target.colorTextureView!!, OptionalInt.empty(), target.depthTextureView, OptionalDouble.empty())
            .use { pass ->
                pass.setPipeline(RenderPipelines.END_SKY)
                RenderSystem.bindDefaultUniforms(pass)
                pass.setUniform("DynamicTransforms", transform)
                pass.bindTexture("Sampler0", tex.textureView, tex.sampler)
                pass.setVertexBuffer(0, buf)
                pass.setIndexBuffer(indices, quads.type())
                pass.drawIndexed(0, 0, 36, 1)
            }
    }
}
