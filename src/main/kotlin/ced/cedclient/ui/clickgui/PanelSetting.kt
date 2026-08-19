package ced.cedclient.ui.clickgui

import ced.cedclient.features.Category

/**
 * Holds one panel's persisted x/y/extended state, decoupled from the actual
 * Panel view object (Phase 4) -- that's what lets ConfigManager (Phase 3)
 * save/load panel layout without needing the real ClickGUI screen built yet.
 * Phase 4's Panel.kt will hold (or wrap) one of these per category.
 */
class PanelSetting(
    val category: Category,
    var x: Float = 0f,
    var y: Float = 0f,
    var extended: Boolean = true
)