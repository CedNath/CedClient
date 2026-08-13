package ced.cedclient.features.settings

class ActionSetting(
    initialLabel: String,
    private val action: () -> Unit
) : Setting<Unit>(initialLabel, Unit) {

    @Volatile
    var label: String = initialLabel

    fun invoke() {
        try {
            action()
        } catch (t: Throwable) {
            println("[ActionSetting] action failed: ${t.message}")
        }
    }

    /**
     * Compatibility helper used by other modules/tests.
     * Calls the action and logs exceptions; keeps the same semantics as invoke().
     */
    fun debugInvoke() {
        invoke()
    }
}
