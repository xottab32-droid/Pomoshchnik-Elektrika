package ru.pomoshchnik.elektrika.data

import android.content.Context
import androidx.room.Room as RoomFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectTransferTest {
    private lateinit var database: LocalDatabase
    private lateinit var transfer: ProjectTransfer

    @Before fun setUp() {
        database = RoomFactory.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), LocalDatabase::class.java
        ).build()
        transfer = ProjectTransfer(database)
    }

    @After fun tearDown() { database.close() }

    @Test fun backupRestoresRoomsDevicesAndOriginalPrices() = runBlocking {
        val source = Project(name = "Квартира")
        database.dao().insert(source)
        database.dao().insert(Room(projectId = source.id, name = "Спальня",
            widthM = 3.5, lengthM = 4.0, heightM = 2.7, sockets = 4))
        database.dao().insert(Part(projectId = source.id, productId = "a2",
            nameSnapshot = "Автомат C16", unitSnapshot = "шт",
            priceSnapshotKopeks = 12500, modulesSnapshot = 1, quantity = 2))

        val backup = transfer.exportJson(source.id)
        val imported = transfer.importJson(backup)
        assertNotEquals(source.id, imported.id)
        assertEquals(2, database.dao().projects().first().size)
        assertEquals("Спальня", database.dao().getRooms(imported.id).single().name)
        val restored = database.dao().getParts(imported.id).single()
        assertEquals(12500L, restored.priceSnapshotKopeks)
        assertEquals(2, restored.quantity)
        assertNotEquals(database.dao().getParts(source.id).single().id, restored.id)
    }

    @Test fun duplicateKeepsHistoricalPricesAndHasIndependentChildren() = runBlocking {
        val original = Project(name = "Дом")
        database.dao().insert(original)
        database.dao().insert(Part(projectId = original.id, productId = "r1",
            nameSnapshot = "УЗО 40А", unitSnapshot = "шт",
            priceSnapshotKopeks = 179900, modulesSnapshot = 2))
        database.dao().insert(Room(projectId = original.id, name = "Прихожая",
            widthM = 2.0, lengthM = 5.0, heightM = 2.8, sockets = 2))

        val duplicate = transfer.duplicateProject(original.id)
        assertEquals("Копия — Дом", duplicate.name)
        assertNotEquals(original.id, duplicate.id)
        val copiedPart = database.dao().getParts(duplicate.id).single()
        assertEquals(179900L, copiedPart.priceSnapshotKopeks)
        assertNotEquals(database.dao().getParts(original.id).single().id, copiedPart.id)
        val copiedRoom = database.dao().getRooms(duplicate.id).single()
        assertNotEquals(database.dao().getRooms(original.id).single().id, copiedRoom.id)
        database.dao().deleteProject(original.id)
        assertEquals(1, database.dao().getParts(duplicate.id).size)
        assertEquals(1, database.dao().getRooms(duplicate.id).size)
    }

    @Test fun renameDoesNotModifyDevicesOrRoomDetails() = runBlocking {
        val original = Project(name = "До")
        database.dao().insert(original)
        database.dao().insert(Room(projectId = original.id, name = "Кухня",
            widthM = 4.0, lengthM = 3.0, heightM = 2.7, sockets = 5))
        database.dao().renameProject(original.id, "После", 123456L)
        assertEquals("После", database.dao().getProject(original.id)?.name)
        assertEquals(123456L, database.dao().getProject(original.id)?.updatedAt)
        assertEquals(5, database.dao().getRooms(original.id).single().sockets)
    }

    @Test fun malformedFileDoesNotCreatePartialProject() = runBlocking {
        val original = Project(name = "Исходный")
        database.dao().insert(original)
        val good = transfer.exportJson(original.id)
        val json = JSONObject(good)
        json.put("rooms", org.json.JSONArray().put(JSONObject().apply {
            put("name", "Некорректное помещение")
            put("widthM", -3)
            put("lengthM", 4)
            put("heightM", 2.7)
            put("sockets", 3)
        }))
        try {
            transfer.importJson(json.toString())
            fail("Invalid dimensions must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, database.dao().projects().first().size)
    }

    @Test fun exportingUnknownProjectFailsWithoutSideEffects() = runBlocking {
        try {
            transfer.exportJson("missing")
            fail("Missing project must be rejected")
        } catch (_: IllegalStateException) { }
        assertTrue(database.dao().projects().first().isEmpty())
    }
}
