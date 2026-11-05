// Host checks for the rules and the driver handshake. Build and run them with the JVM Kotlin
// compiler from external/kotlinc, together with Policy.kt, Hardware.kt and Engine.kt.
package land.moex.nx809j.cooling

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

/** A node backed by an Int, logging writes, with switchable failures. */
private open class FakeNode(private val name: String, private val log: MutableList<String>) : Node {
    var value = 0
    var failWrites = false
    var failReads = false
    /** When set, reads return this instead of what was written. */
    var stuck: Int? = null

    override fun read(): Int {
        if (failReads) throw IOException("$name read failed")
        return stuck ?: value
    }

    override fun write(value: Int) {
        log.add("$name=$value")
        if (failWrites) throw IOException("$name write failed")
        set(value)
    }

    open fun set(value: Int) { this.value = value }
}

/** soc_fan: disabling stashes the level and reads 0; enabling restores it. */
private class FanDriver {
    val log = mutableListOf<String>()
    private var saved = 0
    val level = FakeNode("level", log)
    val enable = object : FakeNode("enable", log) {
        override fun set(value: Int) {
            super.set(value)
            if (value == 0) {
                saved = level.value
                level.value = 0
            } else {
                level.value = saved
            }
        }
    }
    val cooler = Cooler(enable, level, valueFirst = false) { it in Cooler.FAN_LEVELS }
}

private class PumpDriver {
    val log = mutableListOf<String>()
    val enable = FakeNode("enable", log)
    val speed = FakeNode("speed", log)
    val cooler = Cooler(enable, speed, valueFirst = true) { it in Cooler.PUMP_SPEEDS }
}

private class Rig(fanSettings: Settings, pumpSettings: Settings = OFF) {
    val fan = FanDriver()
    val pump = PumpDriver()
    var celsius: Int? = 35
    val engine = Engine(fan.cooler, pump.cooler, { celsius }, fanSettings, pumpSettings)
    fun update(conditions: Conditions, force: Boolean = true): Engine.Snapshot {
        engine.conditions = conditions
        return engine.update(force)
    }
}

private val OFF = Settings(enabled = false, mode = Mode.AUTO, value = 3)
private val AUTO_FAN = Settings(enabled = true, mode = Mode.AUTO, value = 3)
private val AUTO_PUMP = Settings(enabled = true, mode = Mode.AUTO, value = 70)
private val UNLOCKED = Conditions(charging = false, inCall = false, locked = false)

private var passed = 0

private fun scenario(name: String, body: () -> Unit) {
    try {
        body()
    } catch (failure: Throwable) {
        throw AssertionError(name, failure)
    }
    passed++
    println("PASS: $name")
}

fun main() {
    scenario("rules follow the stock order") {
        val auto = AUTO_FAN
        check(decide(auto, Conditions(charging = true, inCall = true, locked = true), false) == Reason.IN_CALL)
        check(decide(auto, Conditions(charging = true, inCall = false, locked = true), false) == Reason.CHARGING)
        check(decide(auto, Conditions(charging = false, inCall = false, locked = true), false) == Reason.LOCKED)
        check(decide(auto, UNLOCKED, false) == Reason.UNLOCKED)
    }
    scenario("manual mode ignores calls, charging and the lock screen") {
        val manual = AUTO_FAN.copy(mode = Mode.MANUAL)
        for (conditions in listOf(Conditions(), Conditions(inCall = true), Conditions(charging = true))) {
            check(decide(manual, conditions, false) == Reason.MANUAL)
        }
    }
    scenario("the switch and shutdown override every mode") {
        check(decide(AUTO_FAN.copy(enabled = false, mode = Mode.MANUAL), UNLOCKED, false) == Reason.SWITCHED_OFF)
        check(decide(AUTO_FAN.copy(mode = Mode.MANUAL), UNLOCKED, true) == Reason.SHUTDOWN)
    }
    scenario("auto fan level starts from 44 °C and holds between 39 and 44") {
        val level = AutoFanLevel()
        check(level.update(43) == 2)
        check(level.update(44) == 4)
        check(level.update(40) == 4)
        check(level.update(39) == 2)
        check(level.update(43) == 2)
        level.reset()
        check(level.update(46) == 4)
        check(level.update(null) == 3)
    }
    scenario("fan is enabled before its level is written") {
        val fan = FanDriver().apply { level.value = 5 }
        check(fan.cooler.apply(2) == Driver(true, 2, null))
        check(fan.log == listOf("enable=1", "level=2"))
        check(fan.cooler.apply(null) == Driver(false, null, null))
    }
    scenario("pump gets its speed before it's enabled") {
        val pump = PumpDriver()
        check(pump.cooler.apply(80) == Driver(true, 80, null))
        check(pump.log == listOf("speed=80", "enable=1"))
    }
    scenario("a wrong readback falls back to a confirmed stop") {
        val pump = PumpDriver().apply { speed.stuck = 66 }
        val driver = pump.cooler.apply(70)
        check(driver.running == false && driver.error != null)
        check("enable=1" !in pump.log && pump.log.last() == "enable=0")
    }
    scenario("a driver stuck on is never reported as stopped") {
        val fan = FanDriver().apply { enable.stuck = 1 }
        val driver = fan.cooler.apply(null)
        check(driver.running == true && driver.error != null)
    }
    scenario("unwritable nodes leave the state unknown") {
        val fan = FanDriver().apply { enable.failWrites = true }
        val driver = fan.cooler.apply(3)
        check(driver.running == null && driver.error != null)
    }
    scenario("values outside the driver's range never reach the nodes") {
        val fan = FanDriver()
        try {
            fan.cooler.apply(6)
            error("accepted level 6")
        } catch (_: IllegalArgumentException) {
            check(fan.log.isEmpty())
        }
    }
    scenario("auto fan follows the temperature while unlocked") {
        val rig = Rig(AUTO_FAN)
        rig.celsius = 45
        check(rig.update(UNLOCKED).fanState.driver == Driver(true, 4, null))
        rig.celsius = 41
        check(rig.update(UNLOCKED, force = false).fanState.target == 4)
        rig.celsius = 38
        val snapshot = rig.update(UNLOCKED, force = false)
        check(snapshot.fanState.driver == Driver(true, 2, null) && snapshot.celsius == 38)
        check(rig.engine.sampling)
    }
    scenario("an unchanged level isn't rewritten between samples") {
        val rig = Rig(AUTO_FAN)
        rig.update(UNLOCKED)
        val writes = rig.fan.log.size
        rig.update(UNLOCKED, force = false)
        check(rig.fan.log.size == writes)
    }
    scenario("charging runs both at the charging level even when locked") {
        val rig = Rig(AUTO_FAN, AUTO_PUMP)
        val snapshot = rig.update(Conditions(charging = true, inCall = false, locked = true))
        check(snapshot.fanState.driver == Driver(true, AutoFanLevel.CHARGING, null))
        check(snapshot.pumpState.driver == Driver(true, 70, null))
        check(!rig.engine.sampling)
    }
    scenario("calls pause both coolers in auto mode, not in manual mode") {
        val rig = Rig(AUTO_FAN, AUTO_PUMP.copy(mode = Mode.MANUAL))
        val snapshot = rig.update(UNLOCKED.copy(inCall = true))
        check(snapshot.fanState.reason == Reason.IN_CALL && snapshot.fanState.driver?.running == false)
        check(snapshot.pumpState.reason == Reason.MANUAL && snapshot.pumpState.driver?.running == true)
    }
    scenario("a locked screen stops auto mode and the next start picks a fresh level") {
        val rig = Rig(AUTO_FAN)
        rig.celsius = 45
        rig.update(UNLOCKED)
        check(rig.update(Conditions()).fanState.driver?.running == false)
        rig.celsius = 41
        check(rig.update(UNLOCKED).fanState.driver == Driver(true, 2, null))
    }
    scenario("manual fan runs at the chosen level") {
        val rig = Rig(AUTO_FAN.copy(mode = Mode.MANUAL, value = 5))
        check(rig.update(Conditions()).fanState.driver == Driver(true, 5, null))
    }
    scenario("shutdown stops both coolers") {
        val rig = Rig(AUTO_FAN.copy(mode = Mode.MANUAL), AUTO_PUMP.copy(mode = Mode.MANUAL))
        rig.update(UNLOCKED)
        rig.engine.shutDown()
        val snapshot = rig.update(UNLOCKED)
        check(snapshot.fanState.driver?.running == false && snapshot.pumpState.driver?.running == false)
        check(snapshot.fanState.reason == Reason.SHUTDOWN)
    }
    scenario("failures are reported for retries") {
        val rig = Rig(AUTO_FAN.copy(mode = Mode.MANUAL))
        rig.fan.level.failWrites = true
        rig.update(UNLOCKED)
        check(rig.engine.failing)
        rig.fan.level.failWrites = false
        rig.update(UNLOCKED)
        check(!rig.engine.failing)
    }
    scenario("the skin zone is found by its type, not its number") {
        val root = createTempDirectory("thermal").toFile()
        for ((index, type) in listOf("cpu-0-0", "skin-msm-therm", "battery").withIndex()) {
            File(root, "thermal_zone$index").apply {
                mkdirs()
                File(this, "type").writeText("$type\n")
                File(this, "temp").writeText("${28250 + index}\n")
            }
        }
        check(SkinTemperature(root).read() == 28)
        File(root, "thermal_zone1/type").writeText("xo-therm\n")
        check(SkinTemperature(root).read() == null)
        root.deleteRecursively()
    }
    scenario("retry delays are bounded and restart after reset") {
        val retry = Retry()
        check(List(6) { retry.nextDelay() } == listOf(1000L, 2000L, 5000L, 10000L, 30000L, 60000L))
        check(retry.nextDelay() == null)
        retry.reset()
        check(retry.nextDelay() == 1000L)
    }
    println("PASS: $passed cooling scenarios")
}
