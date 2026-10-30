package ru.pomoshchnik.elektrika.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "projects")
data class Project(@PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String, val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "products")
data class Product(
    @PrimaryKey val id: String,
    val category: String,
    val name: String,
    val unit: String,
    val modules: Int = 0,
    val priceKopeks: Long? = null
)

@Entity(tableName = "parts", foreignKeys = [ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)], indices = [Index("projectId")])
data class Part(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val productId: String,
    val nameSnapshot: String,
    val unitSnapshot: String,
    val priceSnapshotKopeks: Long?,
    val modulesSnapshot: Int,
    val quantity: Int = 1
)

@Entity(tableName = "rooms", foreignKeys = [ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)], indices = [Index("projectId")])
data class Room(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val name: String,
    val widthM: Double,
    val lengthM: Double,
    val heightM: Double,
    val sockets: Int
)

@Dao
interface LocalDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun projects(): Flow<List<Project>>
    @Query("SELECT * FROM products ORDER BY category, name")
    fun products(): Flow<List<Product>>
    @Query("SELECT * FROM parts WHERE projectId = :projectId ORDER BY id")
    fun parts(projectId: String): Flow<List<Part>>
    @Query("SELECT * FROM rooms WHERE projectId = :projectId ORDER BY id")
    fun rooms(projectId: String): Flow<List<Room>>
    @Query("SELECT COUNT(*) FROM products")
    suspend fun productCount(): Int
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(project: Project)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(part: Part)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(room: Room)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<Product>)
    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: String)
    @Query("DELETE FROM parts WHERE id = :id AND projectId = :projectId")
    suspend fun deletePart(id: String, projectId: String)
    @Query("DELETE FROM rooms WHERE id = :id AND projectId = :projectId")
    suspend fun deleteRoom(id: String, projectId: String)
}

@Database(entities = [Project::class, Product::class, Part::class, Room::class], version = 1, exportSchema = true)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao
}

object BundledCatalog {
    // Illustrative reference data. NULL price explicitly means "unknown", not zero.
    val entries = listOf(
        Product("a1","Автоматы","Автомат 1P C10","шт",1),
        Product("a2","Автоматы","Автомат 1P C16","шт",1),
        Product("a3","Автоматы","Автомат 1P C20","шт",1),
        Product("a4","Автоматы","Автомат 1P C25","шт",1),
        Product("a5","Автоматы","Автомат 2P C25","шт",2),
        Product("a6","Автоматы","Автомат 3P C32","шт",3),
        Product("r1","УЗО","УЗО 2P 40А / 30мА тип A","шт",2),
        Product("r2","УЗО","УЗО 2P 63А / 30мА тип A","шт",2),
        Product("d1","АВДТ","АВДТ C16 / 30мА тип A","шт",2),
        Product("d2","АВДТ","АВДТ C10 / 30мА тип A","шт",2),
        Product("v1","Реле","Реле контроля напряжения 63А","шт",3),
        Product("v2","Контакторы","Контактор 2P 25А","шт",2),
        Product("p1","Щиты","Корпус 24 модуля","шт"),
        Product("p2","Щиты","Корпус 36 модулей","шт"),
        Product("din","Монтаж","DIN-рейка 35 мм","м"),
        Product("pe","Монтаж","Шина PE","шт"),
        Product("n","Монтаж","Шина N","шт"),
        Product("k1","Монтаж","Шина гребёнчатая 1P","м"),
        Product("k2","Клеммы","Клемма 2,5 мм²","шт"),
        Product("k3","Клеммы","Клемма 6 мм²","шт"),
        Product("h1","Наконечники","НШВИ 2,5 мм²","шт"),
        Product("h2","Наконечники","НШВИ 6 мм²","шт"),
        Product("w1","Провода","ПуГВ 2,5 мм²","м"),
        Product("w2","Провода","ПуГВ 6 мм²","м"),
        Product("c1","Кабели","ВВГнг(А)-LS 3×1,5 мм²","м"),
        Product("c2","Кабели","ВВГнг(А)-LS 3×2,5 мм²","м"),
        Product("s1","Розетки","Розетка 16 А IP20","шт"),
        Product("s2","Розетки","Розетка 16 А IP44","шт"),
        Product("s3","Выключатели","Выключатель одноклавишный","шт"),
        Product("b1","Монтаж","Подрозетник 68 мм","шт"),
        Product("b2","Монтаж","Коробка распределительная","шт"),
        Product("spd","УЗИП","УЗИП тип 2, 1P+N","шт",2)
    )
}
