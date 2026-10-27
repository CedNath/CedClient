package ced.cedclient.features.impl.misc

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.utils.ChatPrefix
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/** Button: opens a window with one switch per module for the [CC] prefix on its chat messages. */
object ChatPrefixSettings : Module("Chat Prefix", Category.Misc, "Choose which modules put [CC] in front of their chat messages.") {

    override val isAction = true

    private var tick = 0

    init {
        ChatPrefix.load()
        ClientTickEvents.END_CLIENT_TICK.register { if (++tick % 40 == 0) ChatPrefix.saveIfChanged() }
    }

    override fun onAction() {
        val all = ChatPrefix.toggles.values.toList()
        val half = (all.size + 1) / 2
        (Minecraft.getInstance().gui.screen() as? ClickGUI)?.openPopup(
            SettingsPopup(
                "Chat Prefix",
                listOf(
                    listOf(SettingsPopup.Section("Show [CC] in", all.take(half))),
                    listOf(SettingsPopup.Section("Show [CC] in", all.drop(half))),
                ),
            )
        )
    }
}
