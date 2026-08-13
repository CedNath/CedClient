package ced.cedclient.features.settings

/**
 * Simple boolean toggle setting.
 */
class BooleanSetting(
    name: String,
    initial: Boolean,
) : Setting<Boolean>(name, initial) {

    /** Toggle the boolean value. Useful for clickable UI elements. */
    fun toggle() {
        set(!value)
    }

    override fun set(newValue: Boolean) {
        super.set(newValue)
    }
}
