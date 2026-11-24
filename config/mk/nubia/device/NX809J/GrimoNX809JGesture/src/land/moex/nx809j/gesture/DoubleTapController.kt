package land.moex.nx809j.gesture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.UEventObserver
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Display
import java.io.File
import java.io.IOException

/**
 * Double tap to wake.
 *
 * While /proc/touchscreen/wake_gesture is 1, the touch driver keeps its gesture engine armed
 * with the screen off and reports a double tap as a "double_tap=true" uevent on the zte_touch
 * platform device; it sends no wake key. The node follows the current user's Tap to wake
 * switch. As on the stock system, a tap is dropped while the proximity sensor is covered: in a
 * pocket, face down, or against the face during a call.
 *
 * The driver takes a new value only while the touch controller is powered. With the screen
 * off it drops the value, yet the write still succeeds. Every write is therefore read back,
 * and retried once the default display is on.
 */
internal class DoubleTapController(private val context: Context) {
    private val thread = HandlerThread(TAG).apply { start() }
    private val handler = Handler(thread.looper)
    private val resolver = context.contentResolver
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    // Not reference counted: the timeout may release it before the handler does.
    private val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:tap")
        .apply { setReferenceCounted(false) }

    private var proximity: Sensor? = null
    private var enabled = false
    private var displayOn = false
    private var retries = 0
    private var checking = false

    private val uevents = object : UEventObserver() {
        override fun onUEvent(event: UEvent) {
            if (event.get(UEVENT_KEY) != "true") return
            // The driver's wakeup source only covers the uevent; hold the CPU until decided.
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            handler.post { onDoubleTap() }
        }
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (checking) finishCheck(isNear(event.values[0], event.sensor.maximumRange))
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val proximityTimeout = Runnable {
        if (!checking) return@Runnable
        Log.w(TAG, "No proximity reading within $PROXIMITY_TIMEOUT_MS ms; waking anyway")
        finishCheck(near = false)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {}

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val wasOn = displayOn
            displayOn = isDisplayOn()
            if (displayOn && !wasOn) {
                retries = 0
                apply()
            }
        }
    }

    private val retry = Runnable { apply() }

    fun start() {
        handler.post {
            proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            if (proximity == null) Log.w(TAG, "No proximity sensor; taps are not filtered")
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DOUBLE_TAP_TO_WAKE), false,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = update()
                }, UserHandle.USER_ALL)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = update()
            }, IntentFilter(Intent.ACTION_USER_SWITCHED), null, handler,
                Context.RECEIVER_NOT_EXPORTED)
            // State changes only: refresh rate changes, frequent with the idle timer, are
            // reported as display changes otherwise.
            displayManager.registerDisplayListener(displayListener, handler,
                DisplayManager.EVENT_TYPE_DISPLAY_STATE)
            displayOn = isDisplayOn()
            uevents.startObserving(UEVENT_MATCH)
            update()
        }
    }

    private fun update() {
        // The same fallback as Settings and PowerManagerService when the key is missing.
        val wanted = Settings.Secure.getIntForUser(resolver, Settings.Secure.DOUBLE_TAP_TO_WAKE,
            0, UserHandle.USER_CURRENT) != 0
        if (wanted != enabled) Log.i(TAG, "Double tap to wake ${if (wanted) "on" else "off"}")
        enabled = wanted
        retries = 0
        apply()
    }

    /** Mirrors the switch into the driver. */
    private fun apply() {
        handler.removeCallbacks(retry)
        val value = if (enabled) "1" else "0"
        try {
            if (readNode() == value) return
            File(NODE).writeText(value)
            if (readNode() == value) {
                Log.i(TAG, "$NODE set to $value")
                return
            }
        } catch (e: IOException) {
            Log.e(TAG, "Cannot access $NODE", e)
            return
        }
        // Dropped. The controller may resume a little after the display reports ON.
        if (displayOn && retries < MAX_RETRIES) {
            retries++
            handler.postDelayed(retry, RETRY_DELAY_MS)
        } else {
            Log.i(TAG, "$NODE not accepted yet; retrying when the display turns on")
        }
    }

    private fun readNode() = File(NODE).readText().trim()

    private fun isDisplayOn() =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON

    private fun onDoubleTap() {
        if (checking) return
        if (!enabled || powerManager.isInteractive) {
            wakeLock.release()
            return
        }
        val sensor = proximity ?: return finish(near = false)
        checking = true
        // On-change sensors report their current state right after activation.
        sensorManager.registerListener(proximityListener, sensor,
            SensorManager.SENSOR_DELAY_FASTEST, handler)
        handler.postDelayed(proximityTimeout, PROXIMITY_TIMEOUT_MS)
    }

    private fun finishCheck(near: Boolean) {
        checking = false
        handler.removeCallbacks(proximityTimeout)
        sensorManager.unregisterListener(proximityListener)
        finish(near)
    }

    private fun finish(near: Boolean) {
        if (near) {
            Log.i(TAG, "Double tap ignored: proximity near")
        } else {
            Log.i(TAG, "Double tap: waking up")
            powerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_TAP,
                WAKE_DETAILS)
        }
        wakeLock.release()
    }

    private companion object {
        const val NODE = "/proc/touchscreen/wake_gesture"
        const val UEVENT_MATCH = "MODALIAS=platform:zte_touch"
        const val UEVENT_KEY = "double_tap"
        const val WAKE_DETAILS = "land.moex.nx809j.gesture:DOUBLE_TAP"
        const val PROXIMITY_TIMEOUT_MS = 300L
        const val WAKE_LOCK_TIMEOUT_MS = 1000L
        const val RETRY_DELAY_MS = 500L
        const val MAX_RETRIES = 5

        // The threshold DisplayPowerProximityStateController uses for the screen-off sensor.
        const val PROXIMITY_THRESHOLD = 5f

        fun isNear(distance: Float, maximumRange: Float) =
            distance >= 0f && distance < minOf(maximumRange, PROXIMITY_THRESHOLD)
    }
}
