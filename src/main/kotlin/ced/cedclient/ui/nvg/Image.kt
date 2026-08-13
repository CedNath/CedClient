package ced.cedclient.ui.nvg

import org.lwjgl.system.MemoryUtil
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer

class Image(
    val identifier: String,
    var isSVG: Boolean = identifier.endsWith(".svg", ignoreCase = true),
    var stream: InputStream = getStream(identifier),   // <-- must be public
    private var buffer: ByteBuffer? = null
) {

    fun buffer(): ByteBuffer {
        if (buffer == null) {
            val bytes = stream.readBytes()
            buffer = MemoryUtil.memAlloc(bytes.size).put(bytes).flip() as ByteBuffer
            stream.close()
        }
        return buffer ?: throw IllegalStateException("Image has no data")
    }

    override fun equals(other: Any?): Boolean {
        return other is Image && other.identifier == identifier
    }

    override fun hashCode(): Int = identifier.hashCode()

    companion object {

        private fun getStream(path: String): InputStream {
            return Image::class.java.getResourceAsStream(path)
                ?: throw FileNotFoundException("Resource not found: $path")
        }
    }
}
