package ru.pomoshchnik.elektrika.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Drawn form factors are schematic previews, NOT dimension-verified manufacturer CAD models. */
data class CabinetModel(
    val code: String,
    val title: String,
    val rows: Int,
    val modulesPerRow: Int
) {
    val totalModules: Int get() = rows * modulesPerRow
}

object CabinetCatalog {
    val models = listOf(
        CabinetModel("universal-1x6", "Мини-щит", 1, 6),
        CabinetModel("universal-1x12", "Щит 12", 1, 12),
        CabinetModel("universal-2x12", "Щит 24", 2, 12),
        CabinetModel("universal-3x12", "Щит 36", 3, 12),
        CabinetModel("universal-2x18", "Щит 36 широкий", 2, 18),
        CabinetModel("universal-3x18", "Щит 54", 3, 18),
        CabinetModel("universal-3x24", "Щит 72", 3, 24)
    )

    fun find(code: String): CabinetModel? = models.firstOrNull { it.code == code }
}

@Entity(
    tableName = "cabinet_selections",
    foreignKeys = [ForeignKey(
        entity = Project::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class CabinetSelection(
    @PrimaryKey val projectId: String,
    val modelCode: String,
    val displayName: String,
    val rows: Int,
    val modulesPerRow: Int
)

class CabinetEditor(private val db: LocalDatabase) {
    private val dao = db.dao()

    /**
     * Existing v3 DIN rails are not lost on upgrade. Only a cabinet whose row size and
     * capacity can accommodate them may be selected; no layout is silently resized.
     */
    suspend fun select(projectId: String, modelCode: String): CabinetSelection = withContext(Dispatchers.IO) {
        val model = CabinetCatalog.find(modelCode) ?: throw IllegalArgumentException("Неизвестная модель корпуса")
        db.withTransaction {
            require(dao.getProject(projectId) != null) { "Проект не найден" }
            val rails = dao.getRails(projectId)
            require(rails.size <= model.rows && rails.all { it.capacity == model.modulesPerRow }) {
                "Сначала выбери корпус, совместимый с уже установленными DIN-рейками"
            }
            CabinetSelection(projectId, model.code, model.title, model.rows, model.modulesPerRow)
                .also { dao.saveCabinet(it) }
        }
    }
}
