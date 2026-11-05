package land.moex.nx809j.cooling

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.preference.PreferenceFragment
import com.android.settingslib.widget.SelectorWithWidgetPreference

/** The settings page, in the same SettingsLib style as GrapheneOS's Updater. */
class CoolingActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.cooling_activity)
        window.decorView.filterTouchesWhenObscured = true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    class CoolingFragment : PreferenceFragment(), CoolingApplication.Listener {
        private lateinit var app: CoolingApplication
        private var fan: Section? = null
        private var pump: Section? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            app = requireActivity().application as CoolingApplication
            setPreferencesFromResource(R.xml.cooling_settings, rootKey)
            if (!app.isOwnerProcess) {
                for (key in listOf("fan", "pump")) find<Preference>(key).isVisible = false
                find<Preference>("owner_only").isVisible = true
                return
            }
            fan = Section(requireContext(), this, isFan = true, app::editFan)
            pump = Section(requireContext(), this, isFan = false, app::editPump)
        }

        fun <T : Preference> find(key: String): T =
            requireNotNull(findPreference(key)) { "cooling_settings.xml lacks $key" }

        override fun onResume() {
            super.onResume()
            if (app.isOwnerProcess) {
                app.addListener(this)
                app.refresh()
            }
        }

        override fun onPause() {
            if (app.isOwnerProcess) app.removeListener(this)
            super.onPause()
        }

        override fun changed() {
            val snapshot = app.snapshot ?: return
            fan?.render(snapshot.fan, snapshot.fanState, snapshot.celsius, snapshot.saveFailed)
            pump?.render(snapshot.pump, snapshot.pumpState, null, snapshot.saveFailed)
        }
    }

    /**
     * One cooler: a switch that allows it at all, the mode, its value, and a read-only line that
     * tells whether it's actually running and why. The switch never mirrors the running state.
     * The fan has a level, the pump a flow speed.
     */
    private class Section(
        private val context: Context,
        fragment: CoolingFragment,
        private val isFan: Boolean,
        private val edit: ((Settings) -> Settings) -> Unit,
    ) {
        private val prefix = if (isFan) "fan" else "pump"
        private val switch: SwitchPreferenceCompat = fragment.find("$prefix.switch")
        private val auto: SelectorWithWidgetPreference = fragment.find("$prefix.auto")
        private val manual: SelectorWithWidgetPreference = fragment.find("$prefix.manual")
        private val level: SeekBarPreference? = if (isFan) fragment.find("fan.level") else null
        private val speed: ListPreference? = if (isFan) null else fragment.find("pump.speed")
        private val status: Preference = fragment.find("$prefix.status")

        init {
            switch.setOnPreferenceChangeListener { _, value ->
                edit { it.copy(enabled = value as Boolean) }
                true
            }
            auto.setOnClickListener { edit { it.copy(mode = Mode.AUTO) } }
            manual.setOnClickListener { edit { it.copy(mode = Mode.MANUAL) } }
            level?.apply {
                min = Cooler.FAN_LEVELS.first
                max = Cooler.FAN_LEVELS.last
                setOnPreferenceChangeListener { _, value ->
                    edit { it.copy(value = value as Int) }
                    true
                }
            }
            speed?.apply {
                entries = SPEED_NAMES.map(context::getString).toTypedArray()
                entryValues = Cooler.PUMP_SPEEDS.map { it.toString() }.toTypedArray()
                setOnPreferenceChangeListener { _, value ->
                    edit { it.copy(value = (value as String).toInt()) }
                    true
                }
            }
        }

        fun render(settings: Settings, state: Engine.State, celsius: Int?, saveFailed: Boolean) {
            val stopping = state.reason == Reason.SHUTDOWN
            val editable = settings.enabled && !stopping
            switch.isChecked = settings.enabled
            switch.isEnabled = !stopping
            auto.isChecked = settings.mode == Mode.AUTO
            manual.isChecked = settings.mode == Mode.MANUAL
            auto.isEnabled = editable
            manual.isEnabled = editable
            level?.apply {
                isVisible = settings.mode == Mode.MANUAL
                isEnabled = editable
                value = settings.value
            }
            speed?.apply {
                isEnabled = editable
                value = settings.value.toString()
            }
            status.title = statusTitle(state)
            status.summary = listOfNotNull(
                state.driver?.error?.let { context.getString(R.string.driver_error, it) },
                reasonText(state.reason, celsius),
                if (saveFailed) context.getString(R.string.save_failed) else null,
            ).joinToString("\n").ifEmpty { null }
        }

        private fun statusTitle(state: Engine.State): String {
            val driver = state.driver ?: return context.getString(R.string.status_pending)
            val value = driver.value
            return when (driver.running) {
                null -> context.getString(R.string.status_unknown)
                true -> when {
                    value == null -> null
                    isFan -> context.getString(R.string.fan_running, value)
                    else -> PUMP_RUNNING.getOrNull(Cooler.PUMP_SPEEDS.indexOf(value))
                        ?.let(context::getString)
                } ?: context.getString(R.string.status_unknown)
                false -> context.getString(
                    when {
                        state.reason == Reason.IN_CALL -> R.string.paused
                        isFan -> R.string.fan_stopped
                        else -> R.string.pump_stopped
                    },
                )
            }
        }

        private fun reasonText(reason: Reason, celsius: Int?): String? = when (reason) {
            Reason.IN_CALL -> context.getString(R.string.reason_call)
            Reason.CHARGING -> context.getString(R.string.reason_charging)
            Reason.LOCKED -> context.getString(R.string.reason_locked)
            Reason.SHUTDOWN -> context.getString(R.string.reason_shutdown)
            Reason.UNLOCKED -> when {
                !isFan -> null
                celsius == null -> context.getString(R.string.reason_no_temperature)
                else -> context.getString(R.string.reason_temperature, celsius)
            }
            Reason.SWITCHED_OFF, Reason.MANUAL -> null
        }

        private companion object {
            // Both follow the order of Cooler.PUMP_SPEEDS.
            val SPEED_NAMES = listOf(R.string.speed_low, R.string.speed_medium, R.string.speed_high)
            val PUMP_RUNNING = listOf(
                R.string.pump_running_low,
                R.string.pump_running_medium,
                R.string.pump_running_high,
            )
        }
    }
}
