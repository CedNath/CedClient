package ced.cedclient.features.impl.render

import ced.cedclient.utils.Color

/**
 * What EntityESP / BlockESP draw for one "Only" filter entry, toggled from the filter popups. Combined with the
 * module's own Show Boxes / Tracers / Labels switches, which still turn each kind off everywhere.
 * [color] (box, tracer and label) replaces the module's default colours for this entry; null = default.
 */
class EspStyle(
    var box: Boolean = true,
    var tracer: Boolean = true,
    var label: Boolean = true,
    var color: Color? = null,
) {
    companion object {
        /** For matches not coming from an Only entry (an empty Only list shows everything in EntityESP). */
        val ALL = EspStyle()
    }
}
