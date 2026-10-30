package ru.pomoshchnik.elektrika.data

import android.content.Context
import androidx.room.Room as RoomFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DinRailEditorTest {
    private lateinit var db: LocalDatabase
    private lateinit var editor: DinRailEditor
    private lateinit var backup: ProjectTransfer

    @Before fun setUp() {
        db = RoomFactory.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), LocalDatabase::class.java
        ).build()
        editor = DinRailEditor(db)
        backup = ProjectTransfer(db)
    }

    @After fun tearDown() { db.close() }

    @Test fun placementPreventsOverlapAndOutOfBoundsAndAllowsMoving() = runBlocking {
        val project = Project(name = "Щит")
        db.dao().insert(project)
        val rail = editor.addRail(project.id, 12)
        val breaker = Part(projectId = project.id, productId = "a2", nameSnapshot = "Автомат",
            unitSnapshot = "шт", priceSnapshotKopeks = 5000, modulesSnapshot = 2)
        val rcd = Part(projectId = project.id, productId = "r1", nameSnapshot = "УЗО",
            unitSnapshot = "шт", priceSnapshotKopeks = 15000, modulesSnapshot = 2)
        db.dao().insert(breaker)
        db.dao().insert(rcd)

        assertEquals(0, editor.placePart(project.id, breaker.id, rail.id).startModule)
        assertEquals(2, editor.placePart(project.id, rcd.id, rail.id).startModule)
        assertRejected { editor.placePart(project.id, rcd.id, rail.id, 1) }
        assertRejected { editor.placePart(project.id, breaker.id, rail.id, 11) }
        assertEquals(4, editor.placePart(project.id, breaker.id, rail.id, 4).startModule)
        assertEquals(2, db.dao().getPlacements(project.id).size)
    }

    @Test fun deletingRailDoesNotDeletePartsOrChangeHistoricalPrices() = runBlocking {
        val project = Project(name = "Дом")
        db.dao().insert(project)
        val rail = editor.addRail(project.id, 18)
        val part = Part(projectId = project.id, productId = "r1", nameSnapshot = "УЗО",
            unitSnapshot = "шт", priceSnapshotKopeks = 15000, modulesSnapshot = 2)
        db.dao().insert(part)
        editor.placePart(project.id, part.id, rail.id)
        editor.removeRail(project.id, rail.id)
        assertTrue(db.dao().getPlacements(project.id).isEmpty())
        assertTrue(db.dao().getRails(project.id).isEmpty())
        assertEquals(15000L, db.dao().getParts(project.id).single().priceSnapshotKopeks)
    }

    @Test fun isolationAcrossProjectsAndNonMountableMaterial() = runBlocking {
        val p1 = Project(name = "Первый")
        val p2 = Project(name = "Второй")
        db.dao().insert(p1)
        db.dao().insert(p2)
        val rail = editor.addRail(p1.id, 12)
        val part = Part(projectId = p2.id, productId = "a1", nameSnapshot = "Автомат",
            unitSnapshot = "шт", priceSnapshotKopeks = null, modulesSnapshot = 1)
        val cable = Part(projectId = p1.id, productId = "c1", nameSnapshot = "Кабель",
            unitSnapshot = "м", priceSnapshotKopeks = null, modulesSnapshot = 0)
        db.dao().insert(part)
        db.dao().insert(cable)
        assertRejected { editor.placePart(p1.id, part.id, rail.id) }
        assertRejected { editor.placePart(p1.id, cable.id, rail.id) }
        assertTrue(db.dao().getPlacements(p1.id).isEmpty())
    }

    @Test fun backupAndCopyPreserveRailPositionsButAllocateNewIds() = runBlocking {
        val project = Project(name = "Проект")
        db.dao().insert(project)
        val rail = editor.addRail(project.id, 12)
        val part = Part(projectId = project.id, productId = "a5", nameSnapshot = "Автомат 2P",
            unitSnapshot = "шт", priceSnapshotKopeks = 12345, modulesSnapshot = 2)
        db.dao().insert(part)
        editor.placePart(project.id, part.id, rail.id, 5)

        val data = backup.exportJson(project.id)
        assertEquals(2, JSONObject(data).getInt("schemaVersion"))
        val imported = backup.importJson(data)
        val copied = backup.duplicateProject(project.id)
        for (candidate in listOf(imported, copied)) {
            assertNotEquals(project.id, candidate.id)
            val newPart = db.dao().getParts(candidate.id).single()
            val newRail = db.dao().getRails(candidate.id).single()
            val location = db.dao().getPlacements(candidate.id).single()
            assertNotEquals(part.id, newPart.id)
            assertNotEquals(rail.id, newRail.id)
            assertEquals(newPart.id, location.partId)
            assertEquals(newRail.id, location.railId)
            assertEquals(5, location.startModule)
            assertEquals(12345L, newPart.priceSnapshotKopeks)
        }
    }

    @Test fun corruptedBackupDoesNotCreatePartialProject() = runBlocking {
        val project = Project(name = "Исходник")
        db.dao().insert(project)
        val rail = editor.addRail(project.id, 12)
        val part = Part(projectId = project.id, productId = "a2", nameSnapshot = "Автомат",
            unitSnapshot = "шт", priceSnapshotKopeks = 0, modulesSnapshot = 1)
        db.dao().insert(part)
        editor.placePart(project.id, part.id, rail.id)
        val json = JSONObject(backup.exportJson(project.id))
        val placement = json.getJSONArray("placements").getJSONObject(0)
        placement.put("startModule", 99)
        assertRejected { backup.importJson(json.toString()) }
        assertEquals(1, db.dao().projects().first().size)
    }

    @Test fun legacyVersion1BackupRemainsImportable() = runBlocking {
        val json = JSONObject().apply {
            put("format", ProjectTransfer.FORMAT)
            put("schemaVersion", 1)
            put("project", JSONObject().put("name", "Старая смета"))
            put("parts", JSONArray().put(JSONObject().apply {
                put("productId", "a1")
                put("name", "Автомат")
                put("unit", "шт")
                put("priceKopeks", JSONObject.NULL)
                put("modules", 1)
                put("quantity", 1)
            }))
            put("rooms", JSONArray())
        }.toString()
        val restored = backup.importJson(json)
        assertEquals(1, db.dao().getParts(restored.id).size)
        assertTrue(db.dao().getRails(restored.id).isEmpty())
    }

    private suspend fun assertRejected(block: suspend () -> Any?) {
        try {
            block()
            fail("Invalid arrangement should fail")
        } catch (e: IllegalArgumentException) {
            // Expected.
        } catch (e: IllegalStateException) {
            // Expected for missing data or no free space.
        }
    }
}
