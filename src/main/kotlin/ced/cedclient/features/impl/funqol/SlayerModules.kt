package ced.cedclient.features.impl.funqol

import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.BooleanSetting
import ced.cedclient.features.settings.ColorSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.Setting.Companion.withDependency
import ced.cedclient.utils.Color

/**
 * One module per slayer. They only hold that slayer's options and on/off; the quest tracking, HUD and rendering
 * stay in [VampireSlayer] ("Slayer Tracker"), which reads these through [SlayerGate] / the settings directly.
 * A slayer's features run only while its module is on (and Slayer Tracker is).
 */

/** A [VampireSlayer] feature switch that also needs its slayer's [module] on. */
class SlayerGate(private val module: Module, private val setting: BooleanSetting) {
    val value get() = module.isEnabled && setting.value
}

private val BOX_STYLES = listOf("Outline", "Filled", "Filled + Outline")

object RevenantSlayer : Module(
    "Revenant Horror",
    Category.Skyblock,
    "Revenant Horror (zombie slayer). Nothing slayer-specific yet: its timers, stats and alerts are in Slayer Tracker.",
    defaultEnabled = true
)

object TarantulaSlayer : Module(
    "Tarantula Broodfather",
    Category.Skyblock,
    "Tarantula Broodfather (spider slayer). Needs Slayer Tracker on.",
    defaultEnabled = true
) {
    val hatchlings = BooleanSetting("Hatchlings Alert", true, "Title when you have to kill the hatchlings first.")

    init { addSettings(hatchlings) }
}

object SvenSlayer : Module(
    "Sven Packmaster",
    Category.Skyblock,
    "Sven Packmaster (wolf slayer). Nothing slayer-specific yet: its timers, stats and alerts are in Slayer Tracker.",
    defaultEnabled = true
)

object VoidgloomSlayer : Module(
    "Voidgloom Seraph",
    Category.Skyblock,
    "Voidgloom Seraph (enderman slayer): beacons, laser phase and Nukekubi skulls. Needs Slayer Tracker on.",
    defaultEnabled = true
) {
    val beaconAlert = BooleanSetting("Beacons", true, "Title + boxes for the beacons the boss throws and places.")
    val beaconColor = ColorSetting("Beacon Color", Color(255, 0, 0)).withDependency { beaconAlert.value }
    val beaconStyle = DropdownSetting("Beacon Style", BOX_STYLES, "Outline").withDependency { beaconAlert.value }
    val laserTimer = BooleanSetting("Laser Timer", true, "Small 8 s countdown above the boss during the laser phase.")
    val nukekubi = BooleanSetting("Nukekubi Skulls", true, "Boxes the Voidgloom skull heads.")
    val nukekubiColor = ColorSetting("Skull Color", Color(255, 170, 0)).withDependency { nukekubi.value }
    val nukekubiStyle = DropdownSetting("Skull Style", BOX_STYLES, "Outline").withDependency { nukekubi.value }

    init { addSettings(beaconAlert, beaconColor, beaconStyle, laserTimer, nukekubi, nukekubiColor, nukekubiStyle) }
}

object InfernoSlayer : Module(
    "Inferno Demonlord",
    Category.Skyblock,
    "Inferno Demonlord (blaze slayer): attunement, fire pits and the fire pillar. Needs Slayer Tracker on.",
    defaultEnabled = true
) {
    val attunementAlert = BooleanSetting("Attunement", true, "Title with the dagger attunement to use when it changes.")
    val firePits = BooleanSetting("Fire Pits", true, "Title when the boss drops under a third of its HP.")
    val firePillar = BooleanSetting("Fire Pillar", true, "Title with the fire pillar countdown.")

    init { addSettings(attunementAlert, firePits, firePillar) }
}

object RiftstalkerSlayer : Module(
    "Riftstalker Bloodfiend",
    Category.Skyblock,
    "Riftstalker Bloodfiend (vampire slayer): Steak Stake, Twinclaws, Blood Ichor, Killer Springs and the Château effigies. Needs Slayer Tracker on.",
    defaultEnabled = true
) {
    val steakAlert = BooleanSetting("Steak Alert", true, "Title + sound when your boss is at 20% HP or less (Steak Stake works).")
    val steakColor = ColorSetting("Steak Color", Color(255, 85, 85))
    val twinclawsAlert = BooleanSetting("Twinclaws Alert", true, "Title with the countdown when your boss starts Twinclaws.")
    val ichor = BooleanSetting("Blood Ichor Boxes", true)
    val ichorColor = ColorSetting("Ichor Color", Color(255, 40, 40)).withDependency { ichor.value }
    val ichorSpots = BooleanSetting("Ichor Stand Spots", true, "Outside Mania: marks the 2 blocks to stand on so Twinclaws breaks the Blood Ichor.")
    val ichorSpotColor = ColorSetting("Stand Spot Color", Color(85, 255, 255)).withDependency { ichorSpots.value }
    val springs = BooleanSetting("Killer Spring Boxes", true)
    val springColor = ColorSetting("Spring Color", Color(200, 85, 255)).withDependency { springs.value }
    val effigies = BooleanSetting("Blood Effigies", true, "HUD line + boxes at the standing effigies in Stillgore Château.")
    val effigyColor = ColorSetting("Effigy Color", Color(255, 170, 0)).withDependency { effigies.value }
    val effigyXray = BooleanSetting("Effigies Through Walls", false, "Draws the standing effigy boxes through walls.").withDependency { effigies.value }

    init {
        addSettings(steakAlert, steakColor, twinclawsAlert, ichor, ichorColor, ichorSpots, ichorSpotColor, springs, springColor, effigies, effigyColor, effigyXray)
        listOf(steakAlert, steakColor, twinclawsAlert).forEach { it.section = "Boss" }
        listOf(ichor, ichorColor, ichorSpots, ichorSpotColor, springs, springColor, effigies, effigyColor, effigyXray).forEach { it.section = "Rift" }
    }
}
