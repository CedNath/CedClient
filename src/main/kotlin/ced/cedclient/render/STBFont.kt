package cedclient.render

import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11.*
import org.lwjgl.stb.STBTTAlignedQuad
import org.lwjgl.stb.STBTTBakedChar
import org.lwjgl.stb.STBTruetype.*
import java.nio.ByteBuffer

object STBFont {
    private const val BITMAP_W = 1024
    private const val BITMAP_H = 1024
    private const val FONT_SIZE = 32f

    private var textureId = 0
    private val cdata = STBTTBakedChar.malloc(96)
    private var loaded = false

    fun loadFont() {
        if (loaded) return

        val mc = Minecraft.getInstance()

        // Mojang mappings: use static factory, NOT constructor
        val id = Identifier.parse("cedclient:font/roboto.ttf")

        val resource = mc.resourceManager.getResource(id).orElse(null)
            ?: throw IllegalStateException("Font not found: $id")

        val bytes = resource.open().readBytes()

        val fontBytes: ByteBuffer = BufferUtils.createByteBuffer(bytes.size).apply {
            put(bytes)
            flip()
        }

        val bitmap = BufferUtils.createByteBuffer(BITMAP_W * BITMAP_H)

        stbtt_BakeFontBitmap(fontBytes, FONT_SIZE, bitmap, BITMAP_W, BITMAP_H, 32, cdata)

        textureId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureId)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, BITMAP_W, BITMAP_H, 0, GL_RED, GL_UNSIGNED_BYTE, bitmap)

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        loaded = true
        println("STBFont → Loaded font atlas (tex=$textureId)")
    }

    fun drawText(text: String, x: Float, y: Float, scale: Float = 1f) {
        if (!loaded) return

        glEnable(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, textureId)

        var xpos = x
        var ypos = y

        for (c in text) {
            if (c.code !in 32..126) continue

            val q = STBTTAlignedQuad.malloc()
            val xBuf = floatArrayOf(xpos)
            val yBuf = floatArrayOf(ypos)

            stbtt_GetBakedQuad(cdata, BITMAP_W, BITMAP_H, c.code - 32, xBuf, yBuf, q, true)

            glBegin(GL_QUADS)
            glTexCoord2f(q.s0(), q.t0()); glVertex2f(q.x0() * scale, q.y0() * scale)
            glTexCoord2f(q.s1(), q.t0()); glVertex2f(q.x1() * scale, q.y0() * scale)
            glTexCoord2f(q.s1(), q.t1()); glVertex2f(q.x1() * scale, q.y1() * scale)
            glTexCoord2f(q.s0(), q.t1()); glVertex2f(q.x0() * scale, q.y1() * scale)
            glEnd()

            xpos = xBuf[0]
            ypos = yBuf[0]

            q.free()
        }
    }
}
