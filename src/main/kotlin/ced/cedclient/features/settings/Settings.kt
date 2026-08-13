package ced.cedclient.features.settings

/**
 * Generic base class for settings.
 * - `name` is the display label.
 * - `value` holds the current value and is mutable and publicly accessible.
 */
open class Setting<T>(
    val name: String,
    initial: T
) {
    @Volatile
    var value: T = initial
        set(value) {
            field = value
        }

    /** Whether the UI should render this setting. UI code may observe this. */
    @Volatile
    var visible: Boolean = true

    /**
     * Whether this setting is a fine-tuning knob most users never need.
     * The ClickGUI shows non-advanced settings by default and hides
     * advanced ones behind a "show more" row, per-module.
     */
    @Volatile
    var advanced: Boolean = false

    /** Optional description/help text for the UI. */
    var description: String? = null

    /** Update the value (keeps a single place to override if needed). */
    open fun set(newValue: T) {
        value = newValue
    }

    override fun toString(): String = "$name=$value"
}