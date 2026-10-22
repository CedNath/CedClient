package ced.cedclient.utils

import org.slf4j.LoggerFactory

/**
 * Runs per-tick / per-frame feature code so a bug in it logs once and skips that tick instead of crashing the game.
 * Inline, so `return@register` and friends still work inside [block].
 */
inline fun safely(name: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        Safely.report(name, e)
    }
}

object Safely {
    private val logger = LoggerFactory.getLogger("CedClient")
    private val reported = HashSet<String>()

    /** One error line (with the stack) per feature, so a bug that repeats every tick does not flood the log. */
    fun report(name: String, e: Throwable) {
        if (reported.add(name)) logger.error("$name failed, skipping it", e)
    }
}
