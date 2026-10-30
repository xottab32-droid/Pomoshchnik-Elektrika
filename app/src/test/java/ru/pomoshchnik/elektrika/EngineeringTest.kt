package ru.pomoshchnik.elektrika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineeringTest {
    @Test fun singlePhase() { assertEquals(10.0, Engineering.singlePhaseCurrent(2300.0, 230.0, 1.0), 0.0001) }
    @Test fun threePhase() { assertEquals(10.0, Engineering.threePhaseCurrent(6928.2032302755, 400.0, 1.0), 0.01) }
    @Test fun badInputs() { assertThrows(IllegalArgumentException::class.java) { Engineering.singlePhaseCurrent(230.0, 0.0, 1.0) } }
}
