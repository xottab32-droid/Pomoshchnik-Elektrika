package ru.pomoshchnik.elektrika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EstimateMathTest {
    @Test fun calculatesKopeksWithoutFloatingPoint() {
        assertEquals(EstimateTotal(30001L, 0), EstimateMath.calculate(listOf(EstimateLine(10000L, 3), EstimateLine(1L, 1))))
    }
    @Test fun unknownPriceDoesNotBecomeFree() {
        assertEquals(EstimateTotal(123L, 1), EstimateMath.calculate(listOf(EstimateLine(null, 1), EstimateLine(123L, 1))))
    }
    @Test fun rejectsNegativePricesAndInvalidQuantities() {
        assertThrows(IllegalArgumentException::class.java) { EstimateMath.calculate(listOf(EstimateLine(-100L, 1))) }
        assertThrows(IllegalArgumentException::class.java) { EstimateMath.calculate(listOf(EstimateLine(100L, 0))) }
    }
    @Test fun rejectsLongOverflow() {
        assertThrows(ArithmeticException::class.java) { EstimateMath.calculate(listOf(EstimateLine(Long.MAX_VALUE, 2))) }
    }
}
