package ced.cedclient.ui.pv.tabs

import ced.cedclient.render.nvg.NVGRenderer
import ced.cedclient.ui.pv.PvCategory
import ced.cedclient.ui.pv.PvData
import ced.cedclient.ui.pv.PvTab
import ced.cedclient.ui.pv.SimplePvCategory
import ced.cedclient.utils.Colors

/**
 * Collections tab. skyblock-pv groups collections into a left sidebar by
 * resource category (Farming/Mining/Combat/Foraging/Fishing/...); this does
 * the same, reading straight off `member.collection` -- a flat
 * `"RAW_ID": totalAmount` map Hypixel returns directly, so unlike
 * Inventory this needs no base64/gzip/NBT decoding at all.
 *
 * The [entries] table below (raw SkyBlock item id -> display name +
 * category) is public SkyBlock game data, not anything read out of
 * skyblock-pv's own code -- it's necessarily incomplete (SkyBlock has
 * hundreds of collections) and will need entries added over time as you
 * notice missing ones in-game, same as any other static game-data table in
 * a SkyBlock mod. Start it here, extend as needed.
 */
object CollectionsTab : PvTab {

    override val id: String = "collections"
    override val displayName: String = "Collections"

    data class Entry(val rawId: String, val displayName: String, val category: String)

    private val entries = listOf(
        // Farming
        Entry("WHEAT", "Wheat", "Farming"),
        Entry("CARROT_ITEM", "Carrot", "Farming"),
        Entry("POTATO_ITEM", "Potato", "Farming"),
        Entry("PUMPKIN", "Pumpkin", "Farming"),
        Entry("MELON", "Melon", "Farming"),
        Entry("SEEDS", "Seeds", "Farming"),
        Entry("MUSHROOM_COLLECTION", "Mushroom", "Farming"),
        Entry("INK_SACK:3", "Cocoa Beans", "Farming"),
        Entry("SUGAR_CANE", "Sugar Cane", "Farming"),
        Entry("NETHER_STALK", "Nether Wart", "Farming"),
        Entry("CACTUS", "Cactus", "Farming"),
        Entry("FEATHER", "Feather", "Farming"),
        Entry("LEATHER", "Leather", "Farming"),
        Entry("PORK", "Raw Porkchop", "Farming"),
        Entry("RAW_CHICKEN", "Raw Chicken", "Farming"),
        Entry("MUTTON", "Raw Mutton", "Farming"),
        Entry("RABBIT", "Raw Rabbit", "Farming"),
        // Mining
        Entry("COBBLESTONE", "Cobblestone", "Mining"),
        Entry("COAL", "Coal", "Mining"),
        Entry("IRON_INGOT", "Iron Ingot", "Mining"),
        Entry("GOLD_INGOT", "Gold Ingot", "Mining"),
        Entry("DIAMOND", "Diamond", "Mining"),
        Entry("INK_SACK:4", "Lapis Lazuli", "Mining"),
        Entry("EMERALD", "Emerald", "Mining"),
        Entry("REDSTONE", "Redstone", "Mining"),
        Entry("QUARTZ", "Nether Quartz", "Mining"),
        Entry("OBSIDIAN", "Obsidian", "Mining"),
        Entry("GLOWSTONE_DUST", "Glowstone Dust", "Mining"),
        Entry("GRAVEL", "Gravel", "Mining"),
        Entry("SAND", "Sand", "Mining"),
        Entry("ENDER_STONE", "End Stone", "Mining"),
        Entry("MITHRIL_ORE", "Mithril", "Mining"),
        // Combat
        Entry("ROTTEN_FLESH", "Rotten Flesh", "Combat"),
        Entry("BONE", "Bone", "Combat"),
        Entry("STRING", "String", "Combat"),
        Entry("SPIDER_EYE", "Spider Eye", "Combat"),
        Entry("SULPHUR", "Gunpowder", "Combat"),
        Entry("ENDER_PEARL", "Ender Pearl", "Combat"),
        Entry("GHAST_TEAR", "Ghast Tear", "Combat"),
        Entry("SLIME_BALL", "Slimeball", "Combat"),
        Entry("MAGMA_CREAM", "Magma Cream", "Combat"),
        // Foraging
        Entry("LOG", "Oak Wood", "Foraging"),
        Entry("LOG:1", "Spruce Wood", "Foraging"),
        Entry("LOG:2", "Birch Wood", "Foraging"),
        Entry("LOG_2", "Acacia Wood", "Foraging"),
        Entry("LOG_2:1", "Dark Oak Wood", "Foraging"),
        // Fishing
        Entry("RAW_FISH", "Raw Fish", "Fishing"),
        Entry("RAW_FISH:1", "Raw Salmon", "Fishing"),
        Entry("RAW_FISH:2", "Clownfish", "Fishing"),
        Entry("RAW_FISH:3", "Pufferfish", "Fishing"),
        Entry("PRISMARINE_SHARD", "Prismarine Shard", "Fishing"),
        Entry("PRISMARINE_CRYSTALS", "Prismarine Crystals", "Fishing"),
        Entry("CLAY_BALL", "Clay", "Fishing"),
        Entry("SPONGE", "Sponge", "Fishing")
    )

    override val categories: List<PvCategory> = entries
        .map { it.category }
        .distinct()
        .map { SimplePvCategory(it, it) }

    private const val ROW_HEIGHT = 20f
    private const val COLUMN_WIDTH = 220f

    override fun draw(x: Float, y: Float, w: Float, h: Float, mouseX: Float, mouseY: Float, category: PvCategory?) {
        val member = PvData.currentMember()
        if (member == null) {
            NVGRenderer.text(
                "No profile loaded -- run /pv [username].", x + 16f, y + 16f, 16f, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont
            )
            return
        }

        val collection = member.getAsJsonObject("collection")
        val activeCategory = category?.id ?: categories.firstOrNull()?.id

        var col = 0
        var row = 0
        for (entry in entries.filter { it.category == activeCategory }) {
            if (y + 16f + row * ROW_HEIGHT + ROW_HEIGHT > y + h) {
                row = 0
                col++
            }

            val amount = collection?.get(entry.rawId)?.asLong ?: 0L
            val entryX = x + 16f + col * COLUMN_WIDTH
            val entryY = y + 16f + row * ROW_HEIGHT

            NVGRenderer.text(
                "${entry.displayName}: ${"%,d".format(amount)}",
                entryX, entryY, 14f,
                if (amount > 0) Colors.WHITE.rgba else Colors.MINECRAFT_DARK_GRAY.rgba,
                NVGRenderer.defaultFont
            )
            row++
        }
    }
}
