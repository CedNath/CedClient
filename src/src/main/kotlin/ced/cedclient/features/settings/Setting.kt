package ced.cedclient.features.settings

import ced.cedclient.features.Module
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Superclass of all settings.
 *
 * Implements the delegate protocol so `val x by BooleanSetting(...)` inside
 * a Module works: provideDelegate() fires once at module construction and
 * registers this setting on that module; getValue()/setValue() then make the
 * delegated property read/write straight through to [value].
 */
abstract class Setting<T>(
    val name: String,
    var description: String = "",
) : ReadWriteProperty<Module, T>, PropertyDelegateProvider<Module, ReadWriteProperty<Module, T>> {

    /**
     * Default value of the setting.
     */
    abstract val default: T

    /**
     * Current value of the setting.
     */
    abstract var value: T

    protected var hidden = false

    fun hide(): Setting<T> {
        hidden = true
        return this
    }

    /**
     * Dependency for whether this setting should be shown in the ClickGUI —
     * e.g. a "sound volume" NumberSetting only visible while a BooleanSetting
     * "playSound" is enabled.
     */
    protected var visibilityDependency: (() -> Boolean)? = null

    /**
     * Resets the setting to its default value.
     */
    open fun reset() {
        value = default
    }

    open val isVisible: Boolean
        get() = (visibilityDependency?.invoke() ?: true) && !hidden

    override operator fun provideDelegate(thisRef: Module, property: KProperty<*>): ReadWriteProperty<Module, T> =
        thisRef.registerSetting(this)

    override operator fun getValue(thisRef: Module, property: KProperty<*>): T =
        value

    override operator fun setValue(thisRef: Module, property: KProperty<*>, value: T) {
        this.value = value
    }

    companion object {

        fun <K : Setting<T>, T> K.withDependency(dependency: () -> Boolean): K {
            visibilityDependency = dependency
            return this
        }
    }
}