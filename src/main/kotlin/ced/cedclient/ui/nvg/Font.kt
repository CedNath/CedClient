package ced.cedclient.ui.nvg

import org.lwjgl.system.MemoryUtil
import java.io.InputStream
import java.nio.ByteBuffer

class Font {

    val name: String
    private var buffer: ByteBuffer? = null

    // Real font (old NanoVG style)
    constructor(name: String, inputStream: InputStream) {
        this.name = name

        val bytes = inputStream.use { it.readBytes() }
        buffer = MemoryUtil.memAlloc(bytes.size).apply {
            put(bytes)
            flip()
        }
    }

    // Dummy font (STBFont mode)
    constructor(name: String) {
        this.name = name
        this.buffer = null
    }

    fun buffer(): ByteBuffer? = buffer

    override fun hashCode(): Int = name.hashCode()

    override fun equals(other: Any?): Boolean {
        return other is Font && other.name == name
    }
}
