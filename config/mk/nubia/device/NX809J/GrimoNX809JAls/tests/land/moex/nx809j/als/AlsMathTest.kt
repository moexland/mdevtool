// Host checks for AlsMath. Build and run them with the JVM Kotlin compiler from external/kotlinc,
// together with AlsMath.kt.
package land.moex.nx809j.als

import land.moex.nx809j.als.AlsMath.LcdParams
import land.moex.nx809j.als.AlsMath.Region

private var failures = 0

private fun expect(what: String, actual: Any?, expected: Any?) {
    if (actual != expected) {
        println("FAIL $what: expected $expected, got $actual")
        failures++
    }
}

private fun samples() {
    val outer = listOf(0, 13, 26, 39).flatMap { x -> listOf(0, 13, 26, 39).map { x to it } }
    val inner = listOf(6, 19, 32).flatMap { x -> listOf(6, 19, 32).map { x to it } }
    expect("40x40 points", AlsMath.samplePoints(40, 40), outer + inner)
    expect("larger captures stop at 25", AlsMath.samplePoints(80, 80).size, 25)
    expect("larger captures fill the corner grid first",
        AlsMath.samplePoints(80, 80).take(16), (0..2).flatMap { x ->
            (0..6).map { 13 * x to 13 * it }
        }.take(16))
    expect("tiny capture", AlsMath.samplePoints(10, 10), listOf(0 to 0))
}

private fun weights() {
    expect("flat", AlsMath.channel(IntArray(25) { 200 }), 200)
    expect("black", AlsMath.channel(IntArray(25)), 0)
    // The lowest sample weighs 0.15, the next four nothing.
    expect("one dark sample", AlsMath.channel(IntArray(25) { if (it == 0) 0 else 100 }), 85)
    expect("five dark samples", AlsMath.channel(IntArray(25) { if (it < 5) 0 else 100 }), 85)
    // Missing samples count as black.
    expect("one sample", AlsMath.channel(intArrayOf(255)), 13)
    expect("channels", AlsMath.rgb(IntArray(25) { 0xff102030.toInt() }).toList(),
        listOf(0x10, 0x20, 0x30))
}

private fun geometry() {
    // The region the device reported on 2026-09-27, on the 1216x2688 panel.
    val sensor = Region(555, 172, 595, 212)
    expect("portrait", AlsMath.rotate(sensor, 0, 1216, 2688), sensor)
    expect("rotation 1", AlsMath.rotate(sensor, 1, 2688, 1216), Region(172, 621, 212, 661))
    expect("rotation 2", AlsMath.rotate(sensor, 2, 1216, 2688), Region(621, 2476, 661, 2516))
    expect("rotation 3", AlsMath.rotate(sensor, 3, 2688, 1216), Region(2476, 555, 2516, 595))
    expect("rotated size", AlsMath.rotate(sensor, 1, 2688, 1216).width, 40)
    expect("native resolution", AlsMath.scale(sensor, 1216, 1216), sensor)
    expect("half resolution", AlsMath.scale(sensor, 608, 1216), Region(277, 86, 297, 106))
}

private fun modes() {
    expect("no ACL", AlsMath.modeCode(0), 20)
    expect("ACL 1", AlsMath.modeCode(1), 21)
    expect("ACL 3", AlsMath.modeCode(3), 23)
    expect("unknown ACL", AlsMath.modeCode(4), 20)
    expect("failed read", AlsMath.modeCode(-1), 20)
}

private fun resends() {
    val base = LcdParams(10, 20, 30, 1000, 31, 20)
    expect("first", AlsMath.shouldSend(null, base), true)
    expect("same", AlsMath.shouldSend(base, base), false)
    expect("colour", AlsMath.shouldSend(base, base.copy(blue = 31)), true)
    expect("backlight", AlsMath.shouldSend(base, base.copy(brightness = 1001)), true)
    expect("mode", AlsMath.shouldSend(base, base.copy(mode = 21)), true)
    expect("cool change", AlsMath.shouldSend(base, base.copy(temperature = 33)), false)
    expect("warm change", AlsMath.shouldSend(base, base.copy(temperature = 34)), true)
    val warm = base.copy(temperature = 36)
    expect("cooling down", AlsMath.shouldSend(warm, warm.copy(temperature = 30)), false)
}

fun main() {
    samples()
    weights()
    geometry()
    modes()
    resends()
    if (failures > 0) {
        println("$failures failed")
        kotlin.system.exitProcess(1)
    }
    println("AlsMathTest: all passed")
}
