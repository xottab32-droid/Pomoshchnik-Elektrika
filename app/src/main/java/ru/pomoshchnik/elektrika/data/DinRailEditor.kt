package ru.pomoshchnik.elektrika.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Mechanical layout ONLY. No electrical connectivity is implied by a position. */
@Entity(
    tableName = "din_rails",
    foreignKeys = [ForeignKey(
        entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId")]
)
data class DinRail(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val name: String,
    val capacity: Int,
    val sortOrder: Int
)

@Entity(
    tableName = "rail_placements",
    foreignKeys = [
        ForeignKey(
            entity = Part::class, parentColumns = ["id"], childColumns = ["partId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DinRail::class, parentColumns = ["id"], childColumns = ["railId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("railId")]
)
data class RailPlacement(
    @PrimaryKey val partId: String,
    val railId: String,
    val startModule: Int
)

/** Pure helper: valid modules are numbered from zero, and intervals are half-open. */
object RailGeometry {
    data class Occupied(val partId: String, val start: Int, val width: Int)

    fun fits(capacity: Int, proposedStart: Int, width: Int, existing: List<Occupied>, ignoringPart: String? = null): Boolean {
        if (capacity !in 1..48 || width !in 1..48 || proposedStart < 0 || proposedStart + width > capacity) return false
        return existing.none { slot ->
            slot.partId != ignoringPart &&
                proposedStart < slot.start + slot.width && proposedStart + width > slot.start
        }
    }

    fun firstFit(capacity: Int, width: Int, existing: List<Occupied>, ignoringPart: String? = null): Int? =
        (0..(capacity - width)).firstOrNull { fits(capacity, it, width, existing, ignoringPart) }
}

/** All modifications are validated and executed in Room transactions; devices aren't deleted when a rail is removed. */
class DinRailEditor(private val database: LocalDatabase) {
    private val dao = database.dao()

    suspend fun addRail(projectId: String, capacity: Int): DinRail = withContext(Dispatchers.IO) {
        require(capacity in listOf(6, 12, 18, 24, 36)) { "Выбери рейку на 6, 12, 18, 24 или 36 модулей" }
        database.withTransaction {
            require(dao.getProject(projectId) != null) { "Проект не найден" }
            val existing = dao.getRails(projectId)
            require(existing.size < 16) { "Максимум 16 реек в щите" }
            val next = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
            DinRail(projectId = projectId, name = "DIN-рейка ${existing.size + 1}", capacity = capacity, sortOrder = next)
                .also { dao.insertRail(it) }
        }
    }

    suspend fun placePart(projectId: String, partId: String, railId: String, requestedStart: Int? = null): RailPlacement =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val rail = dao.getRail(railId) ?: error("Рейка не найдена")
                val part = dao.getPart(partId) ?: error("Оборудование не найдено")
                require(rail.projectId == projectId && part.projectId == projectId) { "Оборудование из другого проекта" }
                require(part.quantity == 1) { "Сначала раздели количество на отдельные аппараты" }
                val width = part.modulesSnapshot
                require(width in 1..rail.capacity) { "Этот элемент не может быть размещён на DIN-рейке" }
                val parts = dao.getParts(projectId).associateBy { it.id }
                val occupied = dao.getPlacements(projectId).filter { it.railId == railId }.map { placement ->
                    val placedPart = requireNotNull(parts[placement.partId]) { "Повреждённая компоновка щита" }
                    RailGeometry.Occupied(placement.partId, placement.startModule, placedPart.modulesSnapshot)
                }
                val start = requestedStart ?: RailGeometry.firstFit(rail.capacity, width, occupied, partId)
                    ?: error("На рейке нет свободного места для аппарата")
                require(RailGeometry.fits(rail.capacity, start, width, occupied, partId)) {
                    "Место занято или аппарат выходит за границы рейки"
                }
                RailPlacement(partId = partId, railId = railId, startModule = start)
                    .also { dao.savePlacement(it) }
            }
        }

    suspend fun unplace(projectId: String, partId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            require(dao.getPart(partId)?.projectId == projectId) { "Оборудование из другого проекта" }
            dao.deletePlacement(partId)
        }
    }

    suspend fun removeRail(projectId: String, railId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            require(dao.getRail(railId)?.projectId == projectId) { "Рейка из другого проекта" }
            // Foreign key cascades remove *placements*, never the estimate parts.
            dao.deleteRail(railId)
        }
    }
}
