package ced.cedclient.features.impl.misc

import ced.cedclient.achievements.AchievementsScreen
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import net.minecraft.client.Minecraft

/** A button in the ClickGUI that opens the trophy list (same as /cc achievements). */
object AchievementsModule : Module(
    "Achievements",
    Category.Misc,
    "Opens your achievements: progress, and the halos and level colours they unlock."
) {
    override val isAction: Boolean = true

    override fun onAction() {
        val mc = Minecraft.getInstance()
        mc.execute { mc.setScreen(AchievementsScreen(mc.screen)) }
    }
}
