package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module

/**
 * Global admin/debug toggle. Doesn't do anything itself besides sit in the
 * Misc panel and hold its own enabled state (persisted the same way every
 * other module's enabled flag is, via ConfigManager.saveModulesOnly()).
 *
 * RenderableSetting.isVisible reads [isEnabled] directly, so flipping this
 * on/off immediately shows/hides every setting across every module that was
 * marked `.advanced = true` (aim tolerances, delays, jitter, debug logs,
 * etc.) — no per-module wiring needed.
 */
object AdvancedMode : Module(
    "Advanced Mode",
    Category.Misc,
    description = "Reveals advanced tuning settings (delays, tolerances, jitter, debug logs) across every module."
)