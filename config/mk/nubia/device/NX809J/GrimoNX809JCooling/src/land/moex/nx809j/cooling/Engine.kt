package land.moex.nx809j.cooling

/**
 * Turns the settings and the device conditions into requests for the fan and the pump. It isn't
 * thread-safe; CoolingApplication confines it to one worker thread.
 */
internal class Engine(
    private val fanCooler: Cooler,
    private val pumpCooler: Cooler,
    private val temperature: () -> Int?,
    var fan: Settings,
    var pump: Settings,
) {
    /** One cooler: why it should or shouldn't run, the value requested, and the driver's answer. */
    data class State(val reason: Reason, val target: Int?, val driver: Driver?)

    data class Snapshot(
        val fan: Settings,
        val pump: Settings,
        val fanState: State,
        val pumpState: State,
        /** The body temperature behind the automatic fan level, when one was used. */
        val celsius: Int?,
        val saveFailed: Boolean = false,
    )

    var conditions = Conditions()
    var shuttingDown = false
        private set
    private val autoLevel = AutoFanLevel()
    private var fanState = State(Reason.SWITCHED_OFF, null, null)
    private var pumpState = State(Reason.SWITCHED_OFF, null, null)

    /** Stops both coolers for good; later edits are ignored. */
    fun shutDown() { shuttingDown = true }

    /** Whether the automatic fan level needs new temperature readings. */
    val sampling: Boolean get() = fanState.reason == Reason.UNLOCKED

    val failing: Boolean get() = fanState.driver?.error != null || pumpState.driver?.error != null

    /**
     * Requests what the current settings and conditions call for. With force, the nodes are
     * written even when the request hasn't changed, so the driver state is checked again;
     * otherwise only a changed request touches them.
     */
    fun update(force: Boolean): Snapshot {
        val fanReason = decide(fan, conditions, shuttingDown)
        if (fanReason != Reason.UNLOCKED) autoLevel.reset()
        var celsius: Int? = null
        val fanTarget = when {
            !fanReason.runs -> null
            fan.mode == Mode.MANUAL -> fan.value
            fanReason == Reason.CHARGING -> AutoFanLevel.CHARGING
            else -> temperature().let { celsius = it; autoLevel.update(it) }
        }
        fanState = request(fanCooler, fanState, fanReason, fanTarget, force)

        val pumpReason = decide(pump, conditions, shuttingDown)
        val pumpTarget = if (pumpReason.runs) pump.value else null
        pumpState = request(pumpCooler, pumpState, pumpReason, pumpTarget, force)

        return Snapshot(fan, pump, fanState, pumpState, celsius)
    }

    private fun request(cooler: Cooler, last: State, reason: Reason, target: Int?, force: Boolean): State {
        val driver = if (force || last.driver == null || target != last.target) {
            cooler.apply(target)
        } else {
            last.driver
        }
        return State(reason, target, driver)
    }
}
