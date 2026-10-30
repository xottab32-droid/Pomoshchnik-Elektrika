package ru.pomoshchnik.elektrika.data

import org.junit.Assert.*
import org.junit.Test

class RailGeometryTest {
    private val first = RailGeometry.Occupied("a", start = 1, width = 2)
    private val second = RailGeometry.Occupied("b", start = 5, width = 3)

    @Test fun findsContiguousSpaceAndAvoidsExistingDevices() {
        assertEquals(3, RailGeometry.firstFit(12, 2, listOf(
            RailGeometry.Occupied("a", 0, 3),
            RailGeometry.Occupied("b", 5, 2)
        )))
        assertEquals(0, RailGeometry.firstFit(12, 1, listOf(first, second)))
    }

    @Test fun rejectsOverlapAndOutOfBounds() {
        assertFalse(RailGeometry.fits(12, 2, 2, listOf(first)))
        assertTrue(RailGeometry.fits(12, 3, 2, listOf(first, second)))
        assertFalse(RailGeometry.fits(12, 11, 2, listOf(first)))
        assertFalse(RailGeometry.fits(12, -1, 2, emptyList()))
        assertFalse(RailGeometry.fits(12, 0, 0, emptyList()))
    }

    @Test fun letsExistingDeviceMoveIntoItsOwnSlotButNotNeighbors() {
        assertTrue(RailGeometry.fits(12, 1, 2, listOf(first, second), ignoringPart = "a"))
        assertFalse(RailGeometry.fits(12, 4, 2, listOf(first, second), ignoringPart = "a"))
        assertEquals(0, RailGeometry.firstFit(12, 2, listOf(first, second), ignoringPart = "a"))
    }

    @Test fun noSpaceReturnsNull() {
        val filled = listOf(RailGeometry.Occupied("a", 0, 12))
        assertNull(RailGeometry.firstFit(12, 1, filled))
    }
}
