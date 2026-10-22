package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module

/** Marks every resource pack as compatible (hook: PackCompatibilityMixin). */
object TexturePackFix : Module(
    "Texture Pack Fix",
    Category.Misc,
    "Bypasses the resource pack compatibility check, so packs for other versions load without the warning."
)
