package land.moex.nx809j.cooling

import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.UserHandle
import java.util.concurrent.Executor

/**
 * Owns the only controller. The app is persistent, so the activity manager keeps this process
 * running for the system user and restarts it if it dies; closing the settings page doesn't stop
 * the coolers. Other users' processes only show the page.
 */
class CoolingApplication : Application() {
    internal fun interface Listener { fun changed() }

    private val listeners = mutableSetOf<Listener>()
    private val main = Handler(Looper.getMainLooper())

    // Everything below is confined to the worker thread.
    private lateinit var worker: Handler
    private lateinit var preferences: SharedPreferences
    private lateinit var engine: Engine
    private val retry = Retry()
    private val retryTask = Runnable { update(force = true) }
    private val sampleTask = Runnable { update(force = false) }
    private var charging = false
    private var inCall = false
    private var keyguardLocked = true
    private var interactive = false
    private var saveFailed = false

    internal val isOwnerProcess: Boolean
        get() = UserHandle.myUserId() == UserHandle.USER_SYSTEM

    /** The latest state for the UI; read and written on the main thread. */
    internal var snapshot: Engine.Snapshot? = null
        private set

    internal fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.changed()
    }

    internal fun removeListener(listener: Listener) { listeners.remove(listener) }

    override fun onCreate() {
        super.onCreate()
        if (!isOwnerProcess) return
        worker = Handler(HandlerThread("Cooling").apply { start() }.looper)
        val executor = Executor { worker.post(it) }
        worker.post {
            preferences = createDeviceProtectedStorageContext()
                .getSharedPreferences("cooling", MODE_PRIVATE)
            engine = Engine(
                Cooler.fan(),
                Cooler.pump(),
                SkinTemperature()::read,
                load(FAN, FAN_DEFAULT) { it in Cooler.FAN_LEVELS },
                load(PUMP, PUMP_DEFAULT) { it in Cooler.PUMP_SPEEDS },
            )
            val keyguard = getSystemService(KeyguardManager::class.java)
            val audio = getSystemService(AudioManager::class.java)
            keyguardLocked = keyguard.isKeyguardLocked
            interactive = getSystemService(PowerManager::class.java).isInteractive
            inCall = audio.mode in CALL_MODES
            keyguard.addKeyguardLockedStateListener(executor) {
                keyguardLocked = it
                conditionsChanged()
            }
            audio.addOnModeChangedListener(executor) {
                inCall = it in CALL_MODES
                conditionsChanged()
            }
            // BATTERY_CHANGED is sticky, so the current charging state arrives right away.
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SHUTDOWN)
            }
            registerReceiver(receiver, filter, null, worker, Context.RECEIVER_NOT_EXPORTED)
            engine.conditions = conditions()
            restart()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Like the stock rule, a full battery no longer counts as charging.
                Intent.ACTION_BATTERY_CHANGED ->
                    charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 &&
                        intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) <
                        intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                Intent.ACTION_SCREEN_ON -> interactive = true
                Intent.ACTION_SCREEN_OFF -> interactive = false
                Intent.ACTION_SHUTDOWN -> {
                    engine.shutDown()
                    restart()
                    return
                }
            }
            conditionsChanged()
        }
    }

    // A dark screen counts as locked even where the keyguard is off or hasn't locked yet.
    private fun conditions() = Conditions(charging, inCall, keyguardLocked || !interactive)

    private fun conditionsChanged() {
        val next = conditions()
        if (next == engine.conditions) return
        engine.conditions = next
        restart()
    }

    internal fun editFan(change: (Settings) -> Settings) = edit {
        engine.fan = change(engine.fan)
        save(FAN, engine.fan)
    }

    internal fun editPump(change: (Settings) -> Settings) = edit {
        engine.pump = change(engine.pump)
        save(PUMP, engine.pump)
    }

    /** Checks the drivers again and starts a new retry budget; the page calls it when shown. */
    internal fun refresh() {
        if (isOwnerProcess) worker.post { restart() }
    }

    private fun edit(block: () -> Unit) {
        if (!isOwnerProcess) return
        worker.post {
            if (engine.shuttingDown) return@post
            block()
            restart()
        }
    }

    private fun restart() {
        retry.reset()
        worker.removeCallbacks(retryTask)
        update(force = true)
    }

    private fun update(force: Boolean) {
        worker.removeCallbacks(sampleTask)
        val state = engine.update(force).copy(saveFailed = saveFailed)
        main.post {
            snapshot = state
            listeners.toList().forEach { it.changed() }
        }
        if (engine.sampling) worker.postDelayed(sampleTask, SAMPLE_INTERVAL)
        if (engine.failing && !engine.shuttingDown && !worker.hasCallbacks(retryTask)) {
            retry.nextDelay()?.let { worker.postDelayed(retryTask, it) }
        }
    }

    private fun load(prefix: String, default: Settings, valid: (Int) -> Boolean): Settings = try {
        val value = preferences.getInt("$prefix.value", default.value)
        Settings(
            preferences.getBoolean("$prefix.enabled", default.enabled),
            Mode.valueOf(preferences.getString("$prefix.mode", null) ?: default.mode.name),
            if (valid(value)) value else default.value,
        )
    } catch (_: RuntimeException) {
        // A value of the wrong type or an unknown mode: start from the defaults.
        default
    }

    private fun save(prefix: String, settings: Settings) {
        saveFailed = !preferences.edit()
            .putBoolean("$prefix.enabled", settings.enabled)
            .putString("$prefix.mode", settings.mode.name)
            .putInt("$prefix.value", settings.value)
            .commit()
    }

    private companion object {
        const val FAN = "fan"
        const val PUMP = "pump"
        val FAN_DEFAULT = Settings(enabled = false, mode = Mode.AUTO, value = 3)
        // The stock app defaults to high flow.
        val PUMP_DEFAULT = Settings(enabled = false, mode = Mode.AUTO, value = 80)

        // How often the automatic fan level reads the temperature. cubeserver's own interval
        // isn't known; this is this port's choice.
        const val SAMPLE_INTERVAL = 10_000L

        // Cellular calls and VoIP both put the audio system in one of these modes.
        val CALL_MODES = setOf(
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT,
        )
    }
}
