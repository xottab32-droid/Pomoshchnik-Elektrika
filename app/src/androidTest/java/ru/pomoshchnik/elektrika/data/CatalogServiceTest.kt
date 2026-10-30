package ru.pomoshchnik.elektrika.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogServiceTest {
    private lateinit var db: LocalDatabase
    private lateinit var service: CatalogService

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LocalDatabase::class.java
        ).build()
        service = CatalogService(db)
    }

    @After fun tearDown() { db.close() }

    private fun catalogue(version: String, price: String = "12500") = """
        {"format":"ru.pomoshchnik.elektrika.catalog","schemaVersion":1,
        "version":"$version","items":[
        {"id":"breaker.1","category":"Автоматы","name":"Автомат 1P C16",
        "unit":"шт","modules":1,"priceKopeks":$price}]}
    """.trimIndent()

    @Test fun firstStartInstallsBundledCatalogue() = runBlocking {
        service.initialize()
        assertEquals(32, db.dao().productCount())
        assertEquals("LOCAL-1", db.dao().catalogState().first()?.version)
    }

    @Test fun validImportInstallsPriceAndVersion() = runBlocking {
        service.initialize()
        assertEquals(1, service.importJson(catalogue("2026.10.30")))
        assertEquals(1, db.dao().productCount())
        assertEquals(12500L, db.dao().products().first().single().priceKopeks)
        assertEquals("2026.10.30", db.dao().catalogState().first()?.version)
    }

    @Test fun invalidImportPreservesPreviousVersionAndProducts() = runBlocking {
        service.initialize()
        service.importJson(catalogue("2026.10.30"))
        try {
            service.importJson(catalogue("2026.11.01", "-100"))
            fail("Invalid price must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals("2026.10.30", db.dao().catalogState().first()?.version)
        assertEquals(12500L, db.dao().products().first().single().priceKopeks)
    }

    @Test fun updatingCatalogueDoesNotChangeExistingEstimate() = runBlocking {
        service.initialize()
        service.importJson(catalogue("2026.10.30"))
        val project = Project(name = "Тестовый объект")
        db.dao().insert(project)
        db.dao().insert(Part(projectId = project.id, productId = "breaker.1",
            nameSnapshot = "Автомат 1P C16", unitSnapshot = "шт",
            priceSnapshotKopeks = 12500, modulesSnapshot = 1))
        service.importJson(catalogue("2026.11.01", "19900"))
        assertEquals(12500L, db.dao().parts(project.id).first().single().priceSnapshotKopeks)
        assertEquals(19900L, db.dao().products().first().single().priceKopeks)
    }
}
