package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module


object HardcodedCosmetics : Module(
    "Cosmetics",
    Category.CedClient,
    "Shows Custom built-in cosmetic tag on your client.",
    defaultEnabled = true
)