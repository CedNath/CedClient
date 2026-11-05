package ced.cedclient.features.impl.render

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.TextSetting
import ced.cedclient.state.MorphSync
import ced.cedclient.utils.ChatPrefix
import net.minecraft.client.Minecraft

/**
 * Close friends only (DevAccess.isCloseFriend). Turns you into any mob for everyone running CedClient: the choice goes to the
 * site (Mojang-verified, one change per 30 s) and the other clients pick it up from there. Seeing other people's morphs
 * needs no module, it is part of the mod.
 */
object Morph : Module(
    "Morph",
    Category.Visuals,
    "Look like any mob to everyone running CedClient. Pick a mob name (zombie, enderman, ender_dragon...) and press Apply. One change per 30 s.",
    defaultEnabled = false
) {
    private val choose = ActionSetting("Choose Mob...", "Opens the mob picker with a 3D preview.") {
        (Minecraft.getInstance().gui.screen() as? ced.cedclient.ui.clickgui.ClickGUI)
            ?.openPopup(ced.cedclient.ui.clickgui.MorphPickerPopup { mob.value = it.removePrefix("minecraft:") })
    }
    private val mob = TextSetting("Mob", "zombie", "Entity name, e.g. zombie, cow, wither_skeleton, ender_dragon.", 40)
    private val apply = ActionSetting("Apply Morph", "Becomes the mob above.") { say(MorphSync.request(mob.value)) }
    private val clear = ActionSetting("Remove Morph", "Back to your normal look.") { say(MorphSync.request(null)) }

    init { addSettings(choose, mob, apply, clear) }

    override fun onDisable() {
        // Turning the module off removes the morph (if the 30 s cooldown allows; otherwise it stays until you press Remove).
        MorphSync.request(null)?.let { if (it != "not allowed") say("$it (morph stays until you remove it)") }
    }

    private fun say(refusal: String?) {
        if (refusal == null) return
        Minecraft.getInstance().player?.sendSystemMessage(ChatPrefix.msgFor("Morph", "§c$refusal"))
    }
}
