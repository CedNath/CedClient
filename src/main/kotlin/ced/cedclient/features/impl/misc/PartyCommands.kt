package ced.cedclient.features.impl.misc

import ced.cedclient.config.CedPaths
import ced.cedclient.events.chat.PartyChatEvent
import ced.cedclient.events.core.on
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.ActionSetting
import ced.cedclient.features.settings.DropdownSetting
import ced.cedclient.features.settings.TextSetting
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import ced.cedclient.state.DungeonState
import ced.cedclient.state.PartyApi
import ced.cedclient.ui.clickgui.ClickGUI
import ced.cedclient.ui.clickgui.SettingsPopup
import ced.cedclient.utils.ChatPrefix
import ced.cedclient.utils.PingTracker
import ced.cedclient.utils.PlayerUtils
import ced.cedclient.utils.TpsTracker
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Party chat commands ("!ping", "!song", "!warp" ...) other party members can trigger, like Odin's. Each command
 * has its own permission in the popup: Everyone, Friends (your Hypixel friends, learned from "/friend list" output)
 * or Only Me. Commands that run party commands (warp, invite, kick ...) only work while you are the party leader.
 */
object PartyCommands : Module(
    "Party Commands",
    Category.Misc,
    "Answers !commands typed in party chat (!ping, !tps, !song, !warp ...). Choose who may use each one."
) {
    private val mc get() = Minecraft.getInstance()

    private val PERMS = listOf("Off", "Everyone", "Friends", "Only Me")

    private class Cmd(val name: String, val aliases: List<String>, val description: String, val leaderOnly: Boolean, default: String,
                      val run: (sender: String, args: List<String>) -> Unit) {
        val perm = DropdownSetting("!$name", PERMS, default, description).also { it.inPopup = true }
    }

    private val cmds = ArrayList<Cmd>()
    private fun cmd(name: String, description: String, aliases: List<String> = emptyList(), leader: Boolean = false,
                    default: String = if (leader) "Friends" else "Everyone", run: (String, List<String>) -> Unit) =
        Cmd(name, aliases, description, leader, default, run).also { cmds += it }

    // ---- custom commands: !name -> a reply (or a /command), made in the popup ----
    private class Custom(val name: String, val response: String, perm: String) {
        val perm = DropdownSetting("!$name", PERMS, perm, response).also { it.inPopup = true }
        val remove = ActionSetting("Remove !$name") { removeCustom(this) }.also { it.inPopup = true }
    }
    private class CustomData(val name: String, val response: String, val perm: String)

    private val gson = Gson()
    private val customFile by lazy { CedPaths.file("custom_party_commands.json") }
    private val custom = ArrayList<Custom>()
    private var customSaved = ""

    private fun loadCustom() {
        runCatching {
            if (!customFile.exists()) return
            val list: List<CustomData> = gson.fromJson(customFile.readText(), object : TypeToken<List<CustomData>>() {}.type)
            list.forEach { if (customName.matches(it.name)) custom += Custom(it.name, it.response, it.perm.takeIf { p -> p in PERMS } ?: "Friends") }
        }
        customSaved = customJson()
    }

    private fun customJson() = gson.toJson(custom.map { CustomData(it.name, it.response, it.perm.value) })

    private fun saveCustom() {
        val json = customJson()
        if (json == customSaved) return
        customSaved = json
        runCatching { customFile.writeText(json) }
    }

    private fun removeCustom(c: Custom) { custom -= c; saveCustom(); openPopup() }

    private val customName = Regex("""\w{1,16}""")
    private val newName = TextSetting("Name", "", "Command name, without the !", 16)
    private val newResponse = TextSetting("Reply", "", "Sent to party chat. Starts with / to run a command instead. {sender} {args} {name} {ping} {tps} {song} {x} {y} {z} fill in.", 128)
    private val newPerm = DropdownSetting("Who may use it", PERMS.drop(1), "Friends")

    private fun addCustom() {
        val name = newName.value.trim().removePrefix("!").lowercase()
        val reply = newResponse.value.trim()
        val taken = cmds.any { it.name == name || name in it.aliases } || custom.any { it.name == name }
        if (!customName.matches(name) || reply.isEmpty() || taken) {
            mc.player?.sendSystemMessage(ChatPrefix.msgFor("Party Commands", "§cPick an unused name (letters/digits, up to 16) and a reply."))
            return
        }
        custom += Custom(name, reply, newPerm.value)
        newName.value = ""; newResponse.value = ""
        saveCustom()
        openPopup()
    }

    private fun fill(text: String, sender: String, args: List<String>): String {
        val p = mc.player
        return text.replace("{sender}", sender).replace("{args}", args.joinToString(" ")).replace("{name}", me())
            .replace("{ping}", PingTracker.latest().toString()).replace("{tps}", "%.1f".format(TpsTracker.currentTps()))
            .replace("{song}", Spotify.currentTrack() ?: "nothing")
            .replace("{x}", (p?.blockX ?: 0).toString()).replace("{y}", (p?.blockY ?: 0).toString()).replace("{z}", (p?.blockZ ?: 0).toString())
    }

    // Sending: one line every few ticks so a burst of commands can't get us muted.
    // Each entry waits REPLY_DELAY before it is sent: other mods answer the same !command (!help ...) in the same tick, and a
    // burst of party messages makes Hypixel rate-limit (and cut) the connection, so ours goes out after theirs.
    private const val REPLY_DELAY = 1800L
    private val outbox = ArrayDeque<Pair<Long, String>>()
    private var cooldown = 0
    private var saveTick = 0
    private fun say(text: String) { outbox += System.currentTimeMillis() + REPLY_DELAY to "pc $text" }
    private fun run(command: String) { outbox += System.currentTimeMillis() + REPLY_DELAY to command }

    private fun me() = PlayerUtils.getName() ?: ""
    private fun isLeader() = PartyApi.partyLeader.equals(me(), ignoreCase = true)

    // Friends
    private val friendsFile by lazy { CedPaths.file("friends.txt") }
    private val friends: MutableSet<String> by lazy {
        friendsFile.takeIf { it.exists() }?.readLines()?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toMutableSet() ?: mutableSetOf()
    }
    private fun saveFriends() { runCatching { friendsFile.writeText(friends.sorted().joinToString("\n")) } }
    // Like SkyHanni: the friend list is read from the clickable names in the "/friend list" message itself (/viewprofile <uuid>
    // with the name in its hover text), whenever it shows up (typed by you or by Refresh Friends); adds / removes are
    // picked up from their chat lines. A message can hold several lines, so these match per line.
    private val friendHeader = Regex("""Friends \(Page (\d+) of (\d+)\)""")
    private val friendAdded = Regex("""^You are now friends with (?:\[[^\]]+] )?(\w{1,16})""", RegexOption.MULTILINE)
    private val friendRemoved = Regex("""^You are no longer friends with (?:\[[^\]]+] )?(\w{1,16})""", RegexOption.MULTILINE)
    private val friendRemovedAlt = Regex("""^You removed (?:\[[^\]]+] )?(\w{1,16}) from your friends list""", RegexOption.MULTILINE)
    private val profileHover = Regex("""Click here to view (?:\[[^\]]+] )?(\w{1,16})'s profile""")
    private val plainName = Regex("""\w{1,16}""")
    private val codes = Regex("§.")
    private val friendLine = Regex("""^(?:\[[^\]]+] )?(\w{1,16}) (?:is|was) """, RegexOption.MULTILINE)
    private var refreshing = false

    /** Adds every friend named by a /viewprofile link in [message] (a "/friend list" page); returns how many it found. */
    private fun readFriendList(message: Component): Int {
        var found = 0
        message.visit({ style, text ->
            val command = (style.clickEvent as? ClickEvent.RunCommand)?.command()
            if (command != null && command.startsWith("/viewprofile")) {
                val hover = (style.hoverEvent as? HoverEvent.ShowText)?.value()?.string
                val name = hover?.let { profileHover.find(it)?.groupValues?.get(1) }
                    ?: text.trim().substringAfterLast(' ').takeIf { plainName.matches(it) }
                if (name != null) { friends += name.lowercase(); found++ }
            }
            java.util.Optional.empty<Unit>()
        }, Style.EMPTY)
        // No clickable names found: read the "Name is ..." lines of the page itself (names may carry a rank, or only a colour).
        // The text can carry literal colour codes ("§bwhatmr §cis currently offline"), so strip them before matching.
        val plainText = message.string.replace(codes, "")
        if (found == 0 && friendHeader.containsMatchIn(plainText)) {
            for (m in friendLine.findAll(plainText)) { friends += m.groupValues[1].lowercase(); found++ }
        }
        return found
    }

    // The first full sync walks every page of the friend list; its chat output is hidden that one time (later syncs show it).
    private val syncedFlag by lazy { CedPaths.file("friends_synced.flag") }
    private var hideSync = false
    private var syncStarted = 0L
    private var lastSyncMsg = 0L
    private const val PAGE_DELAY = 2500L
    private var quietUntil = 0L
    private var doneAt = 0L
    private var nextPage = 0
    private var nextPageAt = 0L
    private val friendEntry = Regex("""^(?:\[[^\]]+] )?\w{1,16} (?:is|was) """)

    private var confirmUntil = 0L
    private var friendsBackup: Set<String> = emptySet()

    /** The Refresh Friends button: asks first (click again within 5 s), and while a sync runs a click stops it. */
    private fun onRefreshClick() {
        val now = System.currentTimeMillis()
        if (refreshing) { cancelSync(); return }
        if (now > confirmUntil) {
            confirmUntil = now + 5000
            mc.player?.sendSystemMessage(ChatPrefix.msgFor("Party Commands", "§eThis reads your whole friend list and takes about a minute. Click Refresh Friends again within 5 seconds to start."))
            return
        }
        confirmUntil = 0
        refreshFriends()
    }

    /** Stops a running sync and puts back the friend list you had before it started. */
    private fun cancelSync() {
        refreshing = false; hideSync = false; nextPage = 0; quietUntil = 0
        friends.clear(); friends += friendsBackup
        mc.player?.sendSystemMessage(ChatPrefix.msgFor("Party Commands", "§7Friend sync stopped, your old list (§f${friends.size}§7 friends) is kept"))
    }

    private fun refreshFriends() {
        friendsBackup = friends.toSet()
        friends.clear()
        refreshing = true
        hideSync = !syncedFlag.exists()
        syncStarted = System.currentTimeMillis()
        lastSyncMsg = syncStarted
        run("friend list")
        mc.player?.sendSystemMessage(ChatPrefix.msgFor("Party Commands",
            if (hideSync) "§eSyncing your friends for the first time, this takes about a minute. The friend list stays out of chat while it loads."
            else "§7Reading your friend list..."))
    }

    /** Chat hook: friend list pages while the first sync runs (the dash lines only right next to one). */
    @JvmStatic
    fun hideChat(text: String): Boolean {
        // The last page is hidden too: the sync is already "done" when its message reaches this check, hence quietUntil.
        if (!isEnabled || !((refreshing && hideSync) || System.currentTimeMillis() < quietUntil)) return false
        val t = text.replace(codes, "").trim()
        if (friendHeader.containsMatchIn(t) || friendEntry.containsMatchIn(t)) { lastSyncMsg = System.currentTimeMillis(); return true }
        return t.startsWith("-----") && System.currentTimeMillis() - lastSyncMsg < 4000
    }

    private fun finishSync() {
        refreshing = false
        saveFriends()
        if (hideSync) { if (friends.isNotEmpty()) runCatching { syncedFlag.writeText("1") }; quietUntil = System.currentTimeMillis() + 2500 }
        hideSync = false
        doneAt = System.currentTimeMillis() + 700 // said from the tick, once the last page has gone by
    }

    private fun allowed(perm: String, sender: String) = when (perm) {
        "Everyone" -> true
        "Friends" -> sender.equals(me(), ignoreCase = true) || sender.lowercase() in friends
        "Only Me" -> sender.equals(me(), ignoreCase = true)
        else -> false
    }

    // !dt
    private val downtime = LinkedHashMap<String, String>() // player -> reason

    // The reason is typed by another player and only shown to you, but keep it plain anyway (no colour codes or odd symbols), 24 characters at most.
    private val reasonJunk = Regex("[^A-Za-z0-9 ',-]")
    private fun cleanReason(raw: String) = raw.replace(reasonJunk, "").replace(Regex("\\s+"), " ").trim().take(24).trim()
    private var wasEnded = false

    /** A !dt during the run: AutoRequeue skips the requeue for that run only. */
    internal var dtThisRun = false

    private val eightBall = listOf("It is certain.", "Without a doubt.", "Yes, definitely.", "Most likely.", "Ask again later.",
        "Cannot predict now.", "Don't count on it.", "My sources say no.", "Very doubtful.")

    private val floors = Regex("""^([fmt])([1-7])$""")

    init {
        // Info
        cmd("help", "Lists the enabled commands.") { _, _ ->
            say("Commands: " + cmds.filter { it.perm.value != "Off" && it.name != "help" }.joinToString(" ") { if (it.name == "queue") "!f7 !m7 !t5" else "!${it.name}" })
        }
        cmd("ping", "Your ping.") { _, _ -> say("Ping: ${PingTracker.latest()}ms") }
        cmd("tps", "The server TPS.") { _, _ -> say("TPS: %.1f".format(TpsTracker.currentTps())) }
        cmd("coords", "Your coordinates.", listOf("cords")) { _, _ ->
            mc.player?.let { say("x: ${it.blockX}, y: ${it.blockY}, z: ${it.blockZ}") }
        }
        cmd("time", "Your local time.") { _, _ -> say("It is ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))} for ${me()}.") }
        cmd("song", "The song playing on your PC, from Spotify or Apple Music (the Spotify module must be on).") { _, _ ->
            say(Spotify.currentTrack()?.let { "Now playing: $it" } ?: "No song detected.")
        }
        // Fun
        cmd("coinflip", "Heads or tails.", listOf("cf")) { _, _ -> say(if (Math.random() < 0.5) "Heads!" else "Tails!") }
        cmd("8ball", "Magic 8-ball.") { _, _ -> say(eightBall.random()) }
        cmd("dice", "Rolls a die.") { _, _ -> say("Rolled a ${(1..6).random()}!") }
        cmd("dt", "Adds the sender to the downtime list, announced when the run ends.", listOf("downtime")) { s, a ->
            // Like NoammAddons: the reason is kept and shown to you only, never sent to party chat.
            val reason = cleanReason(a.joinToString(" "))
            downtime[s] = reason
            dtThisRun = true
            mc.player?.sendSystemMessage(ChatPrefix.msgFor("Party Commands", "§f$s §7needs downtime" + if (reason.isNotEmpty()) " §8($reason)" else ""))
        }
        // Party (leader only)
        cmd("invite", "Invites a player.", listOf("inv"), leader = true) { _, a -> a.firstOrNull()?.let { run("p $it") } }
        cmd("allinvite", "Toggles all-invite.", listOf("allinv"), leader = true) { _, _ -> run("p settings allinvite") }
        cmd("warp", "Warps the party to you.", leader = true) { _, _ -> run("p warp") }
        cmd("transfer", "Transfers the party to a player (default: the sender).", leader = true) { s, a -> run("p transfer ${a.firstOrNull() ?: s}") }
        cmd("ptme", "Transfers the party to the sender.", leader = true) { s, _ -> run("p transfer $s") }
        cmd("promote", "Promotes a player (default: the sender).", leader = true) { s, a -> run("p promote ${a.firstOrNull() ?: s}") }
        cmd("demote", "Demotes a player (default: the sender).", leader = true) { s, a -> run("p demote ${a.firstOrNull() ?: s}") }
        cmd("kick", "Kicks a player.", leader = true, default = "Only Me") { _, a -> a.firstOrNull()?.let { run("p kick $it") } }
        // Typed as !f7 / !m7 / !t5 (see the PartyChatEvent handler); "queue" is only the name of its permission.
        cmd("queue", "Joins a dungeon or Kuudra: !f1-f7, !m1-m7, !t1-t5.", leader = true) { _, a ->
            val m = floors.matchEntire(a.firstOrNull()?.lowercase() ?: "")
            if (m == null) say("Usage: !f1-f7, !m1-m7, !t1-t5") else {
                val n = m.groupValues[2].toInt()
                val names = listOf("one", "two", "three", "four", "five", "six", "seven")
                when (m.groupValues[1]) {
                    "f" -> run("joininstance catacombs_floor_${names[n - 1]}")
                    "m" -> run("joininstance master_catacombs_floor_${names[n - 1]}")
                    else -> if (n <= 5) run("joininstance kuudra_${listOf("normal", "hot", "burning", "fiery", "infernal")[n - 1]}") else say("Kuudra tiers are t1-t5.")
                }
            }
        }

        loadCustom()
        addSettings(
            ActionSetting("Configure Commands", "Choose who may use each command.") { openPopup() },
            ActionSetting("Refresh Friends", "Reads your Hypixel friend list (needed for the Friends permission). Asks to confirm; click it again while it runs to stop.") { onRefreshClick() },
        )
        cmds.forEach { addSettings(it.perm) }

        on<PartyChatEvent> { e ->
            if (!isEnabled || !e.message.startsWith("!")) return@on
            val parts = e.message.drop(1).trim().split(Regex("\\s+"))
            val word = parts.firstOrNull()?.lowercase() ?: return@on
            custom.firstOrNull { it.name == word }?.let { cc ->
                if (allowed(cc.perm.value, e.cleanAuthor)) {
                    val out = fill(cc.response, e.cleanAuthor, parts.drop(1))
                    if (out.startsWith("/")) run(out.drop(1)) else say(out)
                }
                return@on
            }
            val floor = floors.matches(word)
            if (word == "queue") return@on
            val c = (if (floor) cmds.firstOrNull { it.name == "queue" } else cmds.firstOrNull { it.name == word || word in it.aliases }) ?: return@on
            if (!allowed(c.perm.value, e.cleanAuthor)) return@on
            if (c.leaderOnly && !isLeader()) return@on
            c.run(e.cleanAuthor, if (floor) listOf(word) else parts.drop(1))
        }

        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (overlay || !isEnabled) return@register
            val t = message.string.replace(codes, "")
            if (t.contains("Friends")) {
                if (readFriendList(message) > 0) saveFriends()
                friendHeader.find(t)?.let { m ->
                    val page = m.groupValues[1].toInt()
                    val pages = m.groupValues[2].toInt()
                    if (refreshing) syncStarted = System.currentTimeMillis()
                    // One page every 2.5 s: a burst of commands could get the player kicked for spam.
                    if (refreshing && page < pages) { nextPage = page + 1; nextPageAt = System.currentTimeMillis() + PAGE_DELAY }
                    else if (refreshing) finishSync()
                }
            }
            friendAdded.find(t)?.let { friends += it.groupValues[1].lowercase(); saveFriends() }
            (friendRemoved.find(t) ?: friendRemovedAlt.find(t))?.let { friends -= it.groupValues[1].lowercase(); saveFriends() }
        }

        // A queued reply (or the rest of a friend sync) belongs to the server it was triggered on: never send it after a disconnect / into a new lobby.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> outbox.clear(); nextPage = 0; doneAt = 0L }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> outbox.clear(); nextPage = 0 }

        ClientTickEvents.END_CLIENT_TICK.register {
            if (++saveTick % 40 == 0) saveCustom()
            if (doneAt != 0L && System.currentTimeMillis() >= doneAt) {
                doneAt = 0L
                mc.player?.sendSystemMessage(ChatPrefix.msgFor("Party Commands",
                    if (friends.isEmpty()) "§cDone, but no friends were read. Press Refresh Friends to try again." else "§aSynced §f${friends.size} §afriends"))
            }
            if (refreshing && nextPage > 0 && System.currentTimeMillis() >= nextPageAt) { run("friend list $nextPage"); nextPage = 0 }
            // No page for 30 s: give up.
            if (refreshing && System.currentTimeMillis() - syncStarted > 30_000) {
                nextPage = 0
                refreshing = false; hideSync = false
                mc.player?.sendSystemMessage(ChatPrefix.msgFor("Party Commands", "§cFriend sync timed out, press Refresh Friends to try again"))
            }
            if (cooldown > 0) cooldown--
            if (isEnabled && cooldown == 0 && outbox.firstOrNull()?.let { System.currentTimeMillis() >= it.first } == true) {
                mc.player?.connection?.sendCommand(outbox.removeFirst().second); cooldown = 24
            }
            val ended = DungeonState.runEnded
            if (isEnabled && ended && !wasEnded) {
                if (downtime.isNotEmpty()) {
                    // Party gets the names only; the reasons stay in your own chat.
                    say("Downtime needed by: ${downtime.keys.joinToString(", ")}")
                    mc.player?.sendSystemMessage(ChatPrefix.msgFor("Party Commands", "§7Players needing DT: §f" +
                        downtime.entries.joinToString("§7, §f") { (name, why) -> if (why.isEmpty()) name else "$name §8($why)§f" }))
                    downtime.clear()
                }
            }
            wasEnded = ended
        }
    }

    private fun openPopup() {
        fun section(name: String, vararg cmdNames: String) =
            SettingsPopup.Section(name, cmds.filter { it.name in cmdNames }.map { it.perm })
        (mc.gui.screen() as? ClickGUI)?.openPopup(SettingsPopup("Party Commands", listOf(
            listOf(section("Info", "help", "ping", "tps", "coords", "time", "song"), section("Fun", "coinflip", "8ball", "dice", "dt")),
            listOf(section("Party (leader only)", "invite", "allinvite", "warp", "transfer", "ptme", "promote", "demote", "kick", "queue")),
            listOf(
                SettingsPopup.Section("New Command", listOf(newName, newResponse, newPerm, ActionSetting("Add Command") { addCustom() })),
                SettingsPopup.Section("Your Commands", custom.flatMap { listOf(it.perm, it.remove) }),
            ),
        ), height = 660f))
    }

    override fun onDisable() { outbox.clear() }
}
