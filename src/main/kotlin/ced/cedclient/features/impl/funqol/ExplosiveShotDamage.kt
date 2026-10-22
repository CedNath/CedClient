package ced.cedclient.features.impl.funqol

import ced.cedclient.events.ChatMessageEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.utils.ChatPrefix
import net.minecraft.client.Minecraft

/**
 * Archer's Explosive Shot splits its damage over every mob it hits, so Hypixel's "hit 6 enemies for X damage" is the
 * total. This posts X / enemies: the damage each mob took. Works even when ChatFilter hides the original line.
 */
object ExplosiveShotDamage : Module(
    "Explosive Shot Damage",
    Category.Skyblock,
    "Shows the damage per enemy of your Explosive Shot (the total split over the mobs it hit)"
) {
    private val line = Regex("""Your Explosive Shot hit (\d+) enem(?:y|ies) for ([\d,.]+) damage\.""")

    /** (enemies hit, per-enemy damage text) for a chat line, or null when it isn't an Explosive Shot line. */
    internal fun perEnemy(text: String): Pair<Int, String>? {
        val m = line.matchEntire(text.trim()) ?: return null
        val hits = m.groupValues[1].toInt().takeIf { it > 0 } ?: return null
        val total = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        return hits to "%,.1f".format(total / hits)
    }

    init {
        on<ChatMessageEvent> { e ->
            if (!isEnabled) return@on
            val (hits, each) = perEnemy(e.unformattedText) ?: return@on
            Minecraft.getInstance().player?.sendSystemMessage(ChatPrefix.msgFor("Explosive Shot Damage", "§6Explosive Shot §e$each §7per enemy §8($hits hit)"))
        }
        // Self-check on the example line: 6,066,269,716.9 / 6.
        check(perEnemy("Your Explosive Shot hit 6 enemies for 6,066,269,716.9 damage.") == 6 to "%,.1f".format(1_011_044_952.8167))
    }
}
