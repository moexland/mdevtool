package land.moex.nx809j.cooling

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** What the driver reports after a request. It says nothing about actual airflow or liquid flow. */
internal data class Driver(val running: Boolean?, val value: Int?, val error: String?)

/** One numeric node of a stock driver. */
internal interface Node {
    fun read(): Int
    fun write(value: Int)
}

/** A fixed node path; callers never pass paths, commands or raw register values. */
internal class SysNode(private val path: String) : Node {
    override fun read(): Int = FileInputStream(path).use { input ->
        val bytes = input.readNBytes(65)
        if (bytes.isEmpty() || bytes.size > 64) throw IOException("$path has an unexpected length")
        val text = bytes.toString(Charsets.US_ASCII).trim()
        if (!text.matches(Regex("[0-9]{1,3}"))) throw IOException("$path reads as \"$text\"")
        text.toInt()
    }

    override fun write(value: Int) {
        FileOutputStream(path).use { it.write(value.toString().toByteArray(Charsets.US_ASCII)) }
    }
}

/**
 * Drives one cooler through its enable node and its value node (fan level or pump speed), and
 * confirms every request by reading the nodes back. A request that fails falls back to a stop.
 *
 * valueFirst is the order the stock app uses: the pump gets its speed before it's enabled. The
 * fan driver drops the level while disabled and restores the previous one on enable, so the fan is
 * enabled first and given its level afterwards.
 */
internal class Cooler(
    private val enable: Node,
    private val value: Node,
    private val valueFirst: Boolean,
    private val valid: (Int) -> Boolean,
) {
    /** Runs at target, or stops when target is null. */
    fun apply(target: Int?): Driver {
        if (target != null) require(valid(target)) { "Unsupported value $target" }
        return try {
            if (target == null) {
                enable.write(0)
                if (readEnabled()) throw IOException("Still enabled after disabling")
                Driver(false, null, null)
            } else {
                if (valueFirst) writeValue(target)
                enable.write(1)
                if (!readEnabled()) throw IOException("Still disabled after enabling")
                if (!valueFirst) writeValue(target)
                val observed = value.read()
                if (observed != target) throw IOException("Value changed to $observed after enabling")
                Driver(true, observed, null)
            }
        } catch (failure: IOException) {
            stop(failure.message ?: "I/O error")
        }
    }

    private fun writeValue(target: Int) {
        value.write(target)
        val observed = value.read()
        if (observed != target) throw IOException("Value reads back as $observed, not $target")
    }

    private fun readEnabled(): Boolean = when (val state = enable.read()) {
        0 -> false
        1 -> true
        else -> throw IOException("Enable reads back as $state")
    }

    private fun stop(error: String): Driver = try {
        enable.write(0)
        val running = readEnabled()
        Driver(running, null, if (running) "$error; still enabled after disabling" else error)
    } catch (failure: IOException) {
        Driver(null, null, "$error; stop not confirmed: ${failure.message}")
    }

    companion object {
        // fan_speed_count in the same directory is never read: each read blocks for about a
        // second and can make the driver restart the fan.
        fun fan() = Cooler(
            SysNode("/sys/kernel/fan/fan_enable"),
            SysNode("/sys/kernel/fan/fan_speed_level"),
            valueFirst = false,
        ) { it in FAN_LEVELS }

        fun pump() = Cooler(
            SysNode("/proc/driver/micropump/enable"),
            SysNode("/proc/driver/micropump/speed"),
            valueFirst = true,
        ) { it in PUMP_SPEEDS }

        /** The driver takes 0–5; 1–5 set a 20–100 % duty cycle. */
        val FAN_LEVELS = 1..5
        /** The stock app's low, medium and high flow. */
        val PUMP_SPEEDS = listOf(60, 70, 80)
    }
}

/**
 * The body temperature that the stock fan rules use: the skin-msm-therm thermal zone. Its zone
 * number isn't stable (this phone has had it at 56 and at 60), so it's looked up by type.
 */
internal class SkinTemperature(private val root: File = File("/sys/class/thermal")) {
    private val temp: File? by lazy {
        root.listFiles { file -> file.name.startsWith("thermal_zone") }
            ?.firstOrNull { zone ->
                runCatching { File(zone, "type").readText().trim() == TYPE }.getOrDefault(false)
            }
            ?.let { File(it, "temp") }
    }

    /** Whole degrees Celsius, or null when the zone is missing or unreadable. */
    fun read(): Int? = temp?.let { runCatching { it.readText().trim().toInt() / 1000 }.getOrNull() }

    private companion object {
        const val TYPE = "skin-msm-therm"
    }
}
