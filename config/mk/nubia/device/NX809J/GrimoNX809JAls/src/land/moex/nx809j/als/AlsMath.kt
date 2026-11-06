package land.moex.nx809j.als

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The arithmetic of the stock ZteLightSensorController in services.jar, without Android types
 * so that the host tests can run it. Stock method names are given where they help to compare.
 */
internal object AlsMath {
    /** A pixel rectangle with exclusive right and bottom edges, like android.graphics.Rect. */
    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** The panel state that setLCDparam passes along with the request serial. */
    data class LcdParams(
        val red: Int,
        val green: Int,
        val blue: Int,
        val brightness: Int,
        val temperature: Int,
        val mode: Int,
    )

    /** Samples per channel; a capture with fewer points counts the missing ones as black. */
    const val SAMPLES = 25

    /** Temperature changes below this don't cause a resend. */
    const val RESEND_TEMPERATURE = 34

    /**
     * Scales [region], given in pixels of a panel [physical] pixels wide, to a display of the
     * same orientation [logical] pixels wide.
     */
    fun scale(region: Region, logical: Int, physical: Int): Region {
        if (logical == physical || physical <= 0) return region
        fun s(v: Int) = (v.toLong() * logical / physical).toInt()
        return Region(s(region.left), s(region.top), s(region.right), s(region.bottom))
    }

    /**
     * Moves a [region] given in the natural (portrait) orientation into display [rotation]
     * (Surface.ROTATION_*), where the display measures [width] by [height].
     *
     * Stock getTargetRGB() handles 90 and 270 degrees and leaves 180 alone; phones normally
     * don't rotate to 180, and it is handled here anyway. Stock maps the corners (x0, y1) and
     * (x1, y0); mapping two opposite corners and sorting gives the same rectangle.
     */
    fun rotate(region: Region, rotation: Int, width: Int, height: Int): Region {
        fun map(x: Int, y: Int): Pair<Int, Int> = when (rotation) {
            1 -> y to height - x
            2 -> width - x to height - y
            3 -> width - y to x
            else -> x to y
        }
        val (ax, ay) = map(region.left, region.top)
        val (bx, by) = map(region.right, region.bottom)
        return Region(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
    }

    /**
     * Where stock getTargetRGB(Rect) samples a [width] by [height] capture: every 13 pixels
     * from the corner, up to 16 points, then every 13 pixels from (6, 6), staying 7 pixels off
     * the far edges, up to [SAMPLES] in all. A 40 by 40 capture gives 16 + 9 points.
     */
    fun samplePoints(width: Int, height: Int): List<Pair<Int, Int>> {
        val points = mutableListOf<Pair<Int, Int>>()
        for (x in 0 until width step 13) {
            for (y in 0 until height step 13) {
                if (points.size > 15) break
                points += x to y
            }
        }
        for (x in 6 until width - 7 step 13) {
            for (y in 6 until height - 7 step 13) {
                if (points.size >= SAMPLES) break
                points += x to y
            }
        }
        return points
    }

    /**
     * Stock weighted order statistic of one channel. The samples are sorted ascending; the
     * lowest weighs 0.15 and the next 24 go in groups of four that share the weights below.
     * The float operations follow stock, so the result rounds the same way.
     */
    fun channel(samples: IntArray): Int {
        val sorted = FloatArray(SAMPLES)
        for (i in 0 until min(samples.size, SAMPLES)) sorted[i] = samples[i].toFloat()
        sorted.sort()
        var sum = sorted[0] * 0.15f * 25f
        for (i in 1 until SAMPLES) sum += sorted[i] * GROUP_WEIGHTS[(i - 1) / 4] * 25f / 4f
        return (sum / 25f).roundToInt()
    }

    private val GROUP_WEIGHTS = floatArrayOf(0f, 0.1f, 0.05f, 0.35f, 0.15f, 0.2f)

    /** Red, green and blue of the ARGB [pixels] taken at [samplePoints]. */
    fun rgb(pixels: IntArray): IntArray = IntArray(3) { c ->
        val shift = 16 - 8 * c
        channel(IntArray(pixels.size) { (pixels[it] shr shift) and 0xff })
    }

    /**
     * The mode code of stock handleAlsMonitorSensorEvent(). Its tens digit comes from the
     * stock colour mode setting (Settings.System lcd_color_gamut). Only the stock colour mode
     * settings write it, so it keeps its default, 2. The units digit is the panel's ACL level
     * when it is 1 to 3, else 0.
     */
    fun modeCode(acl: Int): Int = 20 + if (acl in 1..3) acl else 0

    /**
     * Whether stock would send [now] after [last]: always first after the sensor is enabled
     * ([last] is null), then on any change of colour, backlight or mode, and on a temperature
     * change only to [RESEND_TEMPERATURE] or more.
     */
    fun shouldSend(last: LcdParams?, now: LcdParams): Boolean = when {
        last == null -> true
        now.red != last.red || now.green != last.green || now.blue != last.blue -> true
        now.brightness != last.brightness || now.mode != last.mode -> true
        else -> now.temperature != last.temperature && now.temperature >= RESEND_TEMPERATURE
    }
}
