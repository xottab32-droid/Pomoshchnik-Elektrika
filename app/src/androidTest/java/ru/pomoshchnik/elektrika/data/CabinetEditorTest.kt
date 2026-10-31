package ru.pomoshchnik.elektrika.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CabinetEditorTest {
    private lateinit var database: LocalDatabase
    private lateinit var editor: CabinetEditor
    private lateinit var railEditor: DinRailEditor
    private lateinit var transfer: ProjectTransfer

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), LocalDatabase::class.java
        ).build()
        editor = CabinetEditor(database)
        railEditor = DinRailEditor(database)
        transfer = ProjectTransfer(database)
    }

    @After fun tearDown() { database.close() }

    @Test fun cannotAddRailsBeforeChoosingCabinet() = runBlocking {
        val project = Project(name = "Новый")
        database.dao().insert(project)
        expectInvalid { railEditor.addRail(project.id, 12) }
        assertTrue(database.dao().getRails(project.id).isEmpty())
    }

    @Test fun cabinetCapsNumberOfRowsAndModuleWidth() = runBlocking {
        val project = Project(name = "Новый")
        database.dao().insert(project)
        editor.select(project.id, "universal-2x12")
        railEditor.addRail(project.id, 12)
        railEditor.addRail(project.id, 12)
        expectInvalid { railEditor.addRail(project.id, 12) }
        expectInvalid { railEditor.addRail(project.id, 18) }
        assertEquals(2, database.dao().getRails(project.id).size)
    }

    @Test fun wrongCabinetCannotSilentlyDestroyExistingLayout() = runBlocking {
        val project = Project(name = "Щит")
        database.dao().insert(project)
        editor.select(project.id, "universal-3x12")
        railEditor.addRail(project.id, 12)
        railEditor.addRail(project.id, 12)
        val part = Part(projectId = project.id, productId = "a5",
            nameSnapshot = "Автомат 2P", unitSnapshot = "шт",
            priceSnapshotKopeks = 15320, modulesSnapshot = 2)
        database.dao().insert(part)
        railEditor.placePart(project.id, part.id, database.dao().getRails(project.id).first().id, 3)

        expectInvalid { editor.select(project.id, "universal-1x12") }
        expectInvalid { editor.select(project.id, "universal-2x18") }
        assertEquals("universal-3x12", database.dao().getCabinet(project.id)?.modelCode)
        assertEquals(3, database.dao().getPlacements(project.id).single().startModule)
    }

    @Test fun cabinetSelectionSurvivesJsonBackupAndProjectDuplication() = runBlocking {
        val project = Project(name = "Дом")
        database.dao().insert(project)
        editor.select(project.id, "universal-3x18")
        val rail = railEditor.addRail(project.id, 18)
        val part = Part(projectId = project.id, productId = "r1",
            nameSnapshot = "УЗО", unitSnapshot = "шт", priceSnapshotKopeks = 112200,
            modulesSnapshot = 2)
        database.dao().insert(part)
        railEditor.placePart(project.id, part.id, rail.id, 5)
        val json = transfer.exportJson(project.id)
        assertEquals(3, JSONObject(json).getInt("schemaVersion"))

        val imported = transfer.importJson(json)
        val duplicated = transfer.duplicateProject(project.id)
        listOf(imported, duplicated).forEach { copy ->
            assertEquals("universal-3x18", database.dao().getCabinet(copy.id)?.modelCode)
            assertNotEquals(project.id, copy.id)
            assertEquals(1, database.dao().getRails(copy.id).size)
            assertEquals(5, database.dao().getPlacements(copy.id).single().startModule)
            assertEquals(112200L, database.dao().getParts(copy.id).single().priceSnapshotKopeks)
        }
    }

    @Test fun tamperedBackupCabinetIsRejectedWithoutPartialImport() = runBlocking {
        val project = Project(name = "Дом")
        database.dao().insert(project)
        editor.select(project.id, "universal-1x12")
        val json = JSONObject(transfer.exportJson(project.id))
        json.getJSONObject("cabinet").put("modulesPerRow", 36)
        expectInvalid { transfer.importJson(json.toString()) }
        assertEquals(1, database.dao().getProject(project.id)?.let { 1 } ?: 0)
    }

    @Test fun legacyRailBackupV2ImportsWithoutSilentlyChoosingIncorrectCabinet() = runBlocking {
        val project = Project(name = "Старая сборка")
        database.dao().insert(project)
        editor.select(project.id, "universal-2x12")
        railEditor.addRail(project.id, 12)
        val old = JSONObject(transfer.exportJson(project.id))
        old.put("schemaVersion", 2)
        old.remove("cabinet")
        val restored = transfer.importJson(old.toString())
        assertNull(database.dao().getCabinet(restored.id))
        assertEquals(12, database.dao().getRails(restored.id).single().capacity)
        editor.select(restored.id, "universal-2x12")
        assertNotNull(database.dao().getCabinet(restored.id))
    }

    private suspend fun expectInvalid(block: suspend () -> Any?) {
        try {
            block()
            fail("Invalid action should be rejected")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        }
    }
}
