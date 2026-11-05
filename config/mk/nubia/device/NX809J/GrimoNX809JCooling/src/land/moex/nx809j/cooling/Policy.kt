package land.moex.nx809j.cooling

/** Device state that the automatic mode reacts to. */
internal data class Conditions(
    val charging: Boolean = false,
    val inCall: Boolean = false,
    // Until the first readings arrive, assume the screen is locked so that nothing starts early.
    val locked: Boolean = true,
)

internal enum class Mode { AUTO, MANUAL }

/** A cooler's saved settings. value is the fan level or the pump speed. */
internal data class Settings(val enabled: Boolean, val mode: Mode, val value: Int)

/** Why a cooler should or shouldn't run right now. */
internal enum class Reason { SWITCHED_OFF, SHUTDOWN, MANUAL, IN_CALL, CHARGING, LOCKED, UNLOCKED }

internal val Reason.runs: Boolean
    get() = this == Reason.MANUAL || this == Reason.CHARGING || this == Reason.UNLOCKED

/**
 * The stock RedMagicOS rules for the fan and the pump, in the stock order: calls pause, charging
 * runs even when locked, a locked screen stops, and an unlocked screen runs. This port differs in
 * three ways: any charging counts, not only fast charging; a dark screen counts as locked; and the
 * switch gates everything, where the stock fast-charging rule ran even with the switch off. The
 * stock game and benchmark rules aren't ported. Manual mode ignores all the rules.
 */
internal fun decide(settings: Settings, conditions: Conditions, shuttingDown: Boolean): Reason = when {
    shuttingDown -> Reason.SHUTDOWN
    !settings.enabled -> Reason.SWITCHED_OFF
    settings.mode == Mode.MANUAL -> Reason.MANUAL
    conditions.inCall -> Reason.IN_CALL
    conditions.charging -> Reason.CHARGING
    conditions.locked -> Reason.LOCKED
    else -> Reason.UNLOCKED
}

/**
 * Picks the automatic fan level from the body (skin) temperature, the way the stock cubeserver does
 * for ordinary apps: level 2 up to 39 °C, level 4 from 44 °C, and between the two the level stays
 * where it was.
 */
internal class AutoFanLevel {
    private var level: Int? = null

    fun update(celsius: Int?): Int {
        if (celsius == null) {
            level = null
            return UNKNOWN
        }
        val next = when (val current = level) {
            null -> if (celsius < HIGH) LOW_LEVEL else HIGH_LEVEL
            else -> when {
                celsius <= LOW -> LOW_LEVEL
                celsius >= HIGH -> HIGH_LEVEL
                else -> current
            }
        }
        level = next
        return next
    }

    /** Starts over the next time the fan runs. */
    fun reset() { level = null }

    companion object {
        const val LOW = 39
        const val HIGH = 44
        const val LOW_LEVEL = 2
        const val HIGH_LEVEL = 4
        /** cubeserver's level when it has no temperature. */
        const val UNKNOWN = 3
        /** cubeserver's level for fast charging, which this port applies to any charging. */
        const val CHARGING = 4
    }
}

/** Bounded retries for nodes that are late or fail for a while; a new event starts a new budget. */
internal class Retry {
    private var attempt = 0
    fun reset() { attempt = 0 }
    fun nextDelay(): Long? = DELAYS.getOrNull(attempt++)

    private companion object {
        val DELAYS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 60_000L)
    }
}
