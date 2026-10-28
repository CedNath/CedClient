package ced.cedclient.features.impl.misc

import ced.cedclient.config.CedPaths
import ced.cedclient.features.Category
import ced.cedclient.features.Module
import ced.cedclient.features.settings.NumberSetting
import ced.cedclient.utils.Debug
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.resources.Identifier
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Freeze Capture (dev tool): a watchdog thread notices when the client thread has not ticked or drawn a frame for longer
 * than the threshold and records where that thread is stuck, every 100 ms for as long as the stall lasts. When the
 * client recovers, the samples are written to config/cedclient/dev/freezes/. Joining a world or reloading resources
 * stalls on purpose and will show up too. Only the newest 30 reports are kept.
 */
object FreezeCapture : Module(
    "Freeze Capture", Category.Dev,
    "Saves the client thread's stack when the game stalls for longer than the threshold, to config/cedclient/dev/freezes."
) {
    private val thresholdMs = NumberSetting("Threshold (ms)", 200.0, 100.0, 2000.0, 50.0)

    @Volatile private var beat = 0L
    @Volatile private var clientThread: Thread? = null
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private const val KEEP = 30

    init {
        addSettings(thresholdMs)
        ClientTickEvents.START_CLIENT_TICK.register { pulse() }
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("cedclient", "freeze_capture")) { _, _ -> pulse() }
        Thread(::watch, "CedClient-FreezeCapture").apply { isDaemon = true }.start()
    }

    private fun pulse() {
        clientThread = Thread.currentThread()
        beat = System.nanoTime()
    }

    private fun watch() {
        var reportBeat = 0L
        var started = 0L
        val report = StringBuilder()
        var lastSample = 0L
        while (true) {
            try {
                Thread.sleep(20)
                val b = beat
                val thread = clientThread
                val now = System.nanoTime()
                if (!isEnabled || b == 0L || thread == null) continue
                val stalledMs = (now - b) / 1_000_000
                if (stalledMs >= thresholdMs.value) {
                    if (reportBeat != b) {
                        reportBeat = b
                        started = b
                        report.setLength(0)
                        lastSample = 0L
                    }
                    if (lastSample == 0L || now - lastSample >= 100_000_000L) {
                        lastSample = now
                        report.append("--- ${stalledMs} ms stalled ---\n")
                        thread.stackTrace.forEach { report.append("    at ").append(it).append('\n') }
                    }
                } else if (reportBeat != 0L && report.isNotEmpty() && b != reportBeat) {
                    write(report.toString(), (b - started) / 1_000_000)
                    report.setLength(0)
                    reportBeat = 0L
                }
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                Debug.line(name, "watchdog error: ${t.message}")
            }
        }
    }

    private fun write(text: String, totalMs: Long) {
        runCatching {
            val dir = File(CedPaths.dir, "dev/freezes")
            dir.mkdirs()
            File(dir, "freeze-${LocalDateTime.now().format(stamp)}-${totalMs}ms.txt").writeText(text)
            dir.listFiles { f -> f.name.startsWith("freeze-") }?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
            Debug.line(name, "stall of ~$totalMs ms saved")
        }
    }
}
