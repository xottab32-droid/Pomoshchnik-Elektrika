package ru.pomoshchnik.elektrika.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CloudCatalogUpdaterTest {
    private lateinit var database: LocalDatabase
    private lateinit var service: CatalogService

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), LocalDatabase::class.java
        ).build()
        service = CatalogService(database)
    }

    @After fun tearDown() { database.close() }

    private fun payload(version: String, price: String = "25000") = """
      {"format":"ru.pomoshchnik.elektrika.catalog","schemaVersion":1,
       "version":"$version","items":[
       {"id":"a1","category":"Автоматы","name":"Автомат C10",
        "unit":"шт","modules":1,"priceKopeks":$price}]}
    """.trimIndent()

    @Test fun oneTapCloudUpdateUsesPublishedCatalogueAndPreservesOldEstimate() = runBlocking {
        service.initialize()
        val project = Project(name = "Тест")
        database.dao().insert(project)
        database.dao().insert(Part(projectId = project.id, productId = "a1",
            nameSnapshot = "Автомат C10", unitSnapshot = "шт",
            priceSnapshotKopeks = 12000, modulesSnapshot = 1))
        val updater = CloudCatalogUpdater(service, database.dao()) {
            CloudCatalogRelease("2026.10.30.1", payload("2026.10.30.1"))
        }
        val result = updater.update()
        assertTrue(result is CloudUpdateResult.Installed)
        assertEquals("2026.10.30.1", database.dao().getCatalogState()?.version)
        assertEquals("cloud", database.dao().getCatalogState()?.source)
        assertEquals(25000L, database.dao().products().first().single().priceKopeks)
        assertEquals(12000L, database.dao().getParts(project.id).single().priceSnapshotKopeks)
    }

    @Test fun clickingUpdateAgainKeepsSameVersion() = runBlocking {
        service.initialize()
        val updater = CloudCatalogUpdater(service, database.dao()) {
            CloudCatalogRelease("2026.10.30.1", payload("2026.10.30.1"))
        }
        assertTrue(updater.update() is CloudUpdateResult.Installed)
        assertTrue(updater.update() is CloudUpdateResult.AlreadyCurrent)
    }

    @Test fun invalidCloudPayloadLeavesLocalDatabaseUntouched() = runBlocking {
        service.initialize()
        val previousCount = database.dao().productCount()
        val updater = CloudCatalogUpdater(service, database.dao()) {
            CloudCatalogRelease("2026.10.30.2", payload("2026.10.30.2", "-42"))
        }
        try {
            updater.update()
            fail("Negative price must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(previousCount, database.dao().productCount())
        assertEquals("LOCAL-1", database.dao().getCatalogState()?.version)
    }

    @Test fun networkFailureDoesNotDeleteOfflineCatalogue() = runBlocking {
        service.initialize()
        val updater = CloudCatalogUpdater(service, database.dao()) {
            throw IOException("Offline")
        }
        try {
            updater.update()
            fail("Network error expected")
        } catch (_: IOException) { }
        assertEquals(32, database.dao().productCount())
        assertEquals("LOCAL-1", database.dao().getCatalogState()?.version)
    }

    @Test fun mismatchBetweenServerVersionAndPayloadIsRejected() = runBlocking {
        service.initialize()
        val updater = CloudCatalogUpdater(service, database.dao()) {
            CloudCatalogRelease("2026.10.30.2", payload("2026.10.30.1"))
        }
        try {
            updater.update()
            fail("Mismatched server metadata must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals("LOCAL-1", database.dao().getCatalogState()?.version)
    }
}
