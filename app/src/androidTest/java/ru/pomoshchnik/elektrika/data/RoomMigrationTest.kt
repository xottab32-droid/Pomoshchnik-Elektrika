package ru.pomoshchnik.elektrika.data

import android.content.Context
import androidx.room.Room as RoomFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Tests a genuine file created with the version-1 SQLite schema before Room opens v2. */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {
    @Test fun migrationFromV1RetainsProjectsPartsAndRooms() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "room-migration-" + UUID.randomUUID() + ".db"
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                old.execSQL("""
                    CREATE TABLE IF NOT EXISTS `projects` (
                      `id` TEXT NOT NULL, `name` TEXT NOT NULL,
                      `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                old.execSQL("""
                    CREATE TABLE IF NOT EXISTS `products` (
                      `id` TEXT NOT NULL, `category` TEXT NOT NULL,
                      `name` TEXT NOT NULL, `unit` TEXT NOT NULL,
                      `modules` INTEGER NOT NULL, `priceKopeks` INTEGER,
                      PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                old.execSQL("""
                    CREATE TABLE IF NOT EXISTS `parts` (
                      `id` TEXT NOT NULL, `projectId` TEXT NOT NULL,
                      `productId` TEXT NOT NULL, `nameSnapshot` TEXT NOT NULL,
                      `unitSnapshot` TEXT NOT NULL, `priceSnapshotKopeks` INTEGER,
                      `modulesSnapshot` INTEGER NOT NULL, `quantity` INTEGER NOT NULL,
                      PRIMARY KEY(`id`),
                      FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                old.execSQL("CREATE INDEX IF NOT EXISTS `index_parts_projectId` ON `parts`(`projectId`)")
                old.execSQL("""
                    CREATE TABLE IF NOT EXISTS `rooms` (
                      `id` TEXT NOT NULL, `projectId` TEXT NOT NULL,
                      `name` TEXT NOT NULL, `widthM` REAL NOT NULL,
                      `lengthM` REAL NOT NULL, `heightM` REAL NOT NULL,
                      `sockets` INTEGER NOT NULL, PRIMARY KEY(`id`),
                      FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                old.execSQL("CREATE INDEX IF NOT EXISTS `index_rooms_projectId` ON `rooms`(`projectId`)")
                old.execSQL("INSERT INTO projects(id,name,updatedAt) VALUES ('project-old','Квартира',1)")
                old.execSQL("""INSERT INTO products(id,category,name,unit,modules,priceKopeks)
                    VALUES('a2','Автоматы','Автомат 1P C16','шт',1,15000)""")
                old.execSQL("""INSERT INTO parts(id,projectId,productId,nameSnapshot,unitSnapshot,
                    priceSnapshotKopeks,modulesSnapshot,quantity)
                    VALUES('part-old','project-old','a2','Автомат 1P C16','шт',12500,1,2)""")
                old.execSQL("""INSERT INTO rooms(id,projectId,name,widthM,lengthM,heightM,sockets)
                    VALUES('room-old','project-old','Кухня',3.0,4.0,2.7,6)""")
                old.version = 1
            }
            val db = RoomFactory.databaseBuilder(context, LocalDatabase::class.java, name)
                .addMigrations(LocalDatabase.MIGRATION_1_2).build()
            try {
                val project = db.dao().getProject("project-old")
                assertNotNull(project)
                assertEquals("Квартира", project?.name)
                assertEquals(12500L, db.dao().getParts("project-old").single().priceSnapshotKopeks)
                assertEquals(6, db.dao().getRooms("project-old").single().sockets)
                assertEquals(15000L, db.dao().products().let { kotlinx.coroutines.flow.first(it) }.single().priceKopeks)
                val catalog = CatalogService(db)
                catalog.initialize()
                assertEquals("LOCAL-1", db.dao().getCatalogState()?.version)
                assertEquals(1, db.dao().productCount())
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
