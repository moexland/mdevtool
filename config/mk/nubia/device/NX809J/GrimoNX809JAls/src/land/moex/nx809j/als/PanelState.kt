package land.moex.nx809j.als

import android.os.IThermalService
import android.os.ServiceManager
import android.os.Temperature
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The panel readings besides the captured colour. As in stock, a failed read keeps the last
 * value, which starts at -1.
 */
internal class PanelState {
    private var backlight = -1
    private var acl = -1
    private var thermal: IThermalService? = null
    private val failed = mutableSetOf<File>()

    /**
     * The raw backlight level, 0 to 8190 on this panel. Stock reads it through the
     * /sys/class/backlight link; the node it points to is tried as well.
     */
    fun backlight(): Int = (BACKLIGHT.firstNotNullOfOrNull(::read) ?: backlight).also { backlight = it }

    /** The panel's ACL (automatic current limit) level. */
    fun acl(): Int = (read(ACL) ?: acl).also { acl = it }

    /**
     * Stock takes the first sensor of type 17 (speaker), which on this device is "alps", the
     * thermistor of the light and proximity sensor; -1 when that fails.
     */
    fun temperature(): Int = try {
        val service = thermal
            ?: IThermalService.Stub.asInterface(ServiceManager.getService("thermalservice"))
                ?.also { thermal = it }
        service?.getCurrentTemperaturesWithType(Temperature.TYPE_SPEAKER)
            ?.firstOrNull()?.value?.toInt() ?: -1
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read the temperature", e)
        thermal = null
        -1
    }

    /** Runs on every sensor event, so each file's first failure is the only one logged. */
    private fun read(file: File): Int? = try {
        file.readText().trim().toInt()
    } catch (e: IOException) {
        if (failed.add(file)) Log.w(TAG, "Cannot read $file: $e")
        null
    } catch (e: NumberFormatException) {
        if (failed.add(file)) Log.w(TAG, "Unexpected content in $file")
        null
    }

    private companion object {
        val BACKLIGHT = listOf(
            File("/sys/class/backlight/panel0-backlight/brightness"),
            File("/sys/devices/platform/soc/9800000.qcom,mdss_mdp/backlight/panel0-backlight/brightness"),
        )
        val ACL = File("/proc/driver/lcd_acl")
    }
}
