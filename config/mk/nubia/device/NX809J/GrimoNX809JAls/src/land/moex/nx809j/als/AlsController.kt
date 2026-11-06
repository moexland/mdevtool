package land.moex.nx809j.als

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Point
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Display

/**
 * Does what the monitor mode of the stock ZteLightSensorController does, the only mode NX809J
 * uses. The vendor sensor zte.sensor.zte_als_monitor reports whenever the light sensor wants
 * the panel state over it. On each report this captures that part of the screen, reads the
 * panel and passes the result to the HAL if anything changed.
 *
 * Stock enables the monitor sensor exactly while auto-brightness has the light sensor enabled,
 * which an app can't observe. This enables it while the default display is on and the current
 * user has auto-brightness on, which is when auto-brightness uses the light sensor here.
 */
internal class AlsController(private val context: Context) {
    private val handler = Handler(HandlerThread(TAG).apply { start() }.looper)
    private val resolver = context.contentResolver
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val panel = PanelState()
    private val hal = SubsysHal()

    /** The pixels over the sensor in the natural orientation; stock defaults if unset. */
    private val region = AlsMath.Region(
        feature("x0", 530), feature("y0", 250), feature("x1", 570), feature("y1", 290))

    // Confined to the handler thread.
    private var monitor: Sensor? = null
    private var enabled = false
    private var queued = false
    private var sent: AlsMath.LcdParams? = null

    private val listener = object : SensorEventListener {
        // The event is the request; its value only feeds stock debug logs. Reports that arrive
        // while one is queued are handled together.
        override fun onSensorChanged(event: SensorEvent) {
            if (!enabled || queued) return
            queued = true
            handler.post {
                queued = false
                if (enabled) compensate()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun start() {
        handler.post {
            monitor = sensors.getSensorList(Sensor.TYPE_ALL)
                .firstOrNull { it.stringType == MONITOR_SENSOR }
            if (monitor == null) {
                Log.w(TAG, "No $MONITOR_SENSOR sensor, nothing to do")
                return@post
            }
            Log.i(TAG, "Monitoring $MONITOR_SENSOR for $region")
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = update()
            }
            resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false,
                observer, UserHandle.USER_ALL)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = update()
            }, IntentFilter(Intent.ACTION_USER_SWITCHED), null, handler,
                Context.RECEIVER_NOT_EXPORTED)
            // State changes only: refresh rate changes, frequent with the idle timer, are
            // reported as display changes otherwise.
            displays.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) update()
                }
            }, handler, DisplayManager.EVENT_TYPE_DISPLAY_STATE)
            update()
        }
    }

    private fun update() {
        val screenOn = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
        val auto = Settings.System.getIntForUser(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, UserHandle.USER_CURRENT) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val enable = screenOn && auto
        if (enable == enabled) return
        enabled = enable
        // Stock forgets what it sent whenever it enables or disables the sensor.
        sent = null
        if (enable) {
            sensors.registerListener(listener, monitor, SensorManager.SENSOR_DELAY_NORMAL,
                handler)
        } else {
            sensors.unregisterListener(listener)
        }
        Log.i(TAG, "Monitor ${if (enable) "on" else "off"} (screen on $screenOn, auto $auto)")
    }

    private fun compensate() {
        if (!hal.connect()) {
            if (debug()) Log.d(TAG, "HAL not available")
            return
        }
        val rgb = capture() ?: return
        val params = AlsMath.LcdParams(rgb[0], rgb[1], rgb[2], panel.backlight(),
            panel.temperature(), AlsMath.modeCode(panel.acl()))
        if (AlsMath.shouldSend(sent, params) && hal.setLcdParam(params)) sent = params
    }

    /** The colour over the sensor; stock sends black when the capture fails, this skips. */
    private fun capture(): IntArray? {
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val size = Point().also { display.getRealSize(it) }
        val rotation = display.rotation
        val naturalWidth = if (rotation == 1 || rotation == 3) size.y else size.x
        val crop = AlsMath.rotate(
            AlsMath.scale(region, naturalWidth, display.mode.physicalWidth),
            rotation, size.x, size.y)
        val bitmap = try {
            ScreenCapturer.capture(Display.DEFAULT_DISPLAY,
                Rect(crop.left, crop.top, crop.right, crop.bottom))
        } catch (e: Exception) {
            Log.w(TAG, "Capture failed", e)
            null
        } ?: return null
        try {
            val points = AlsMath.samplePoints(bitmap.width, bitmap.height)
            val rgb = AlsMath.rgb(IntArray(points.size) {
                bitmap.getPixel(points[it].first, points[it].second)
            })
            if (debug()) Log.d(TAG, "rotation $rotation, crop $crop, rgb ${rgb.joinToString()}")
            return rgb
        } finally {
            bitmap.recycle()
        }
    }

    private fun feature(corner: String, default: Int) =
        SystemProperties.getInt("ro.vendor.feature.zte_screenshot_region_$corner", default)

    private companion object {
        const val MONITOR_SENSOR = "zte.sensor.zte_als_monitor"
    }
}
