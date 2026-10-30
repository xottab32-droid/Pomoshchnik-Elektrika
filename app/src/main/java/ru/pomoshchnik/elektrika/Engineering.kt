package ru.pomoshchnik.elektrika

import kotlin.math.sqrt

/** Calculator only; not normative cable sizing or protective device selection. */
object Engineering {
    fun singlePhaseCurrent(powerW: Double, voltageV: Double, powerFactor: Double): Double {
        require(powerW >= 0 && powerW.isFinite() && voltageV > 0 && voltageV.isFinite() && powerFactor > 0 && powerFactor <= 1)
        return powerW / (voltageV * powerFactor)
    }
    fun threePhaseCurrent(powerW: Double, lineVoltageV: Double, powerFactor: Double): Double {
        require(powerW >= 0 && powerW.isFinite() && lineVoltageV > 0 && lineVoltageV.isFinite() && powerFactor > 0 && powerFactor <= 1)
        return powerW / (sqrt(3.0) * lineVoltageV * powerFactor)
    }
}
