// SkullTextureHolder.kt
package ced.cedclient.utils

/**
 * Simple registry for named skull textures.
 * Store the exact base64 "Value" string (or a key you use) under a name.
 *
 * Example:
 *   SkullTextureHolder.register("NORMAL_BEACH_BALL", "<base64-value>")
 */
object SkullTextureHolder {
    private val map = mutableMapOf<String, String>()

    fun register(name: String, textureValue: String) {
        map[name] = textureValue
    }

    fun unregister(name: String) {
        map.remove(name)
    }

    fun getTexture(name: String): String? = map[name]
}
