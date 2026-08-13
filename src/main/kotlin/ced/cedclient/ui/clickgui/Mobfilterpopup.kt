package ced.cedclient.ui.clickgui

/**
 * Mob type picker — "Select entities" style. Checking a box calls
 * EntityESP.onlyName(name), so it uses the exact same allow-list your
 * existing ESP filtering already reads from scan(). Feel free to add/remove
 * names or whole categories here — it's just a plain list, no registry lookups.
 */
class MobFilterPopup : EntityCheckListPopup("Select entities") {

    override fun groupsProvider(): List<Pair<String, List<String>>> = MOB_GROUPS

    companion object {
        private val MOB_GROUPS: List<Pair<String, List<String>>> = listOf(
            "Animals" to listOf(
                "Allay", "Armadillo", "Bee", "Camel", "Cat", "Chicken", "Cow", "Donkey",
                "Fox", "Frog", "Happy Ghast", "Goat", "Horse", "Llama", "Mooshroom", "Mule",
                "Ocelot", "Panda", "Parrot", "Pig", "Polar Bear", "Rabbit", "Sheep",
                "Skeleton Horse", "Sniffer", "Strider", "Sulfur Cube","Tadpole", "Trader Llama", "Turtle",
                "Wandering Trader", "Wolf"
            ),
            "Water Animals" to listOf(
                "Axolotl", "Cod", "Dolphin", "Glow Squid", "Nautilus", "Pufferfish", "Salmon", "Squid",
                "Tropical Fish"
            ),
            "Monsters" to listOf(
                "Blaze", "Bogged","Bat", "Breeze","Camel Husk", "Cave Spider","Creaking", "Creeper", "Drowned", "Elder Guardian",
                "Enderman", "Endermite", "Ghast", "Guardian", "Hoglin", "Husk", "Magma Cube",
                "Parched", "Phantom", "Piglin", "Piglin Brute", "Pillager", "Ravager", "Shulker", "Silverfish",
                "Skeleton", "Slime", "Spider", "Stray", "Vex", "Vindicator", "Warden", "Witch",
                "Wither Skeleton", "Zoglin", "Zombie", "Zombie Horse", "Zombie Nautilus", "Zombie Villager", "Zombified Piglin"
            ),
            "Illagers" to listOf(
                "Evoker", "Illusioner", "Pillager", "Ravager", "Vindicator", "Witch"
            ),
            "Bosses" to listOf(
                "Ender Dragon", "Wither"
            ),
            "Utility / Other" to listOf(
                "Armor Stand", "Boat", "Chest Boat", "Copper Golem", "Minecart", "Item Frame", "Glow Item Frame",
                "Painting", "Snow Golem", "Iron Golem", "Villager"
            )
        )
    }
}