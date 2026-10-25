package ced.cedclient.utils

object Debug {
    var enabled = false
    var tickCounter = 0

    class Line(val time: Long, val tag: String, val msg: String)

    private const val MAX_LINES = 2000
    private val buffer = ArrayDeque<Line>()

    /** Always recorded (cheap, capped) so the debug panel has history even if nobody turned console debug on. */
    @JvmStatic
    fun line(tag: String, msg: String) {
        synchronized(buffer) {
            buffer.addLast(Line(System.currentTimeMillis(), tag, msg))
            if (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        if (enabled) println("[$tag] $msg")
    }

    /** Oldest first. */
    fun lines(): List<Line> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    /** Tags seen so far, for the panel's filter. */
    fun tags(): List<String> = lines().map { it.tag }.distinct().sorted()

    /** Old-style rate-limited line; a leading "[Tag] " becomes the line's tag. */
    fun log(msg: String, interval: Int = 50) {
        tickCounter++
        if (tickCounter % interval == 0) {
            val m = TAGGED.matchEntire(msg)
            if (m != null) line(m.groupValues[1], m.groupValues[2]) else line("General", msg)
        }
    }

    private val TAGGED = Regex("""\[([^\]]{1,40})]\s*(.*)""", RegexOption.DOT_MATCHES_ALL)
}
