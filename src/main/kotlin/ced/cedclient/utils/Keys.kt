package ced.cedclient.utils

import com.mojang.blaze3d.platform.InputConstants

/**
 * Key codes. Since 26.3 Minecraft runs on SDL, so every key code is an SDL scancode
 * (the same space as [InputConstants.KEY_ESCAPE] etc). Mouse buttons are 1 based
 * (left = 1, middle = 2, right = 3). [UNKNOWN] (-1) means "unbound".
 */
object Keys {
    const val UNKNOWN = -1

    const val A = 4
    const val B = 5
    const val C = 6
    const val G = 10
    const val L = 15
    const val P = 19
    const val R = 21
    const val S = 22
    const val V = 25
    const val X = 27
    const val Y = 28
    const val Z = 29

    const val ENTER = 40
    const val ESCAPE = 41
    const val BACKSPACE = 42
    const val TAB = 43
    const val MINUS = 45
    const val EQUAL = 46
    const val HOME = 74
    const val DELETE = 76
    const val END = 77
    const val RIGHT = 79
    const val LEFT = 80
    const val DOWN = 81
    const val UP = 82
    const val KP_ENTER = 88
    const val LEFT_SHIFT = 225
    const val LEFT_ALT = 226
    const val RIGHT_SHIFT = 229

    const val MOUSE_LEFT = InputConstants.MOUSE_BUTTON_LEFT
    const val MOUSE_MIDDLE = InputConstants.MOUSE_BUTTON_MIDDLE
    const val MOUSE_RIGHT = InputConstants.MOUSE_BUTTON_RIGHT

    /** Scancode of the number row key for [n] (0..9). */
    fun digit(n: Int): Int = if (n == 0) 39 else 29 + n

    fun isDown(key: Int): Boolean = key != UNKNOWN && InputConstants.isKeyDown(key)

    fun name(key: Int): String =
        InputConstants.Type.KEYBOARD.getOrCreate(key).displayName.string

    /** Converts a key code saved by 26.1 / 26.2 builds (GLFW) to a scancode. */
    fun fromGlfw(code: Int): Int = when (code) {
        in 65..90 -> 4 + (code - 65)
        in 49..57 -> 30 + (code - 49)
        48 -> 39
        257 -> 40
        256 -> 41
        259 -> 42
        258 -> 43
        32 -> 44
        45 -> 45
        61 -> 46
        91 -> 47
        93 -> 48
        92 -> 49
        59 -> 51
        39 -> 52
        96 -> 53
        44 -> 54
        46 -> 55
        47 -> 56
        280 -> 57
        in 290..301 -> 58 + (code - 290)
        283 -> 70
        281 -> 71
        284 -> 72
        260 -> 73
        268 -> 74
        266 -> 75
        261 -> 76
        269 -> 77
        267 -> 78
        262 -> 79
        263 -> 80
        264 -> 81
        265 -> 82
        282 -> 83
        331 -> 84
        332 -> 85
        333 -> 86
        334 -> 87
        335 -> 88
        in 321..329 -> 89 + (code - 321)
        320 -> 98
        330 -> 99
        in 302..313 -> 104 + (code - 302)
        341 -> 224
        340 -> 225
        342 -> 226
        343 -> 227
        345 -> 228
        344 -> 229
        346 -> 230
        347 -> 231
        348 -> 118
        else -> UNKNOWN
    }
}
