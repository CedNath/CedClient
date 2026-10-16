package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module

/**
 * A packet that fails to handle (bad data from the server, a mod throwing mid-handler) gets logged and
 * skipped instead of kicking you with "Network Protocol Error". The hook lives in PacketErrorMixin.
 */
object KeepConnection : Module(
    "Keep Connection",
    Category.Misc,
    "A packet that fails to handle is logged and skipped instead of disconnecting you.",
    defaultEnabled = true
)
