package ru.pomoshchnik.elektrika.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Offline project backup (JSON v3). Imports legacy v1/v2 backups.
 * The v3 format also preserves a graphical cabinet choice; all imported IDs are regenerated.
 * Never export account/device identifiers or raw database files.
 */
class ProjectTransfer(private val database: LocalDatabase) {
    private val dao = database.dao()

    suspend fun exportJson(projectId: String): String = withContext(Dispatchers.IO) {
        database.withTransaction {
            val project = dao.getProject(projectId) ?: error("Проект не найден")
            val parts = dao.getParts(projectId)
            val rooms = dao.getRooms(projectId) // legacy: kept for import compatibility
            val rails = dao.getRails(projectId)
            val placements = dao.getPlacements(projectId)
            val cabinet = dao.getCabinet(projectId)
            JSONObject().apply {
                put("format", FORMAT)
                put("schemaVersion", 3)
                put("project", JSONObject().apply { put("name", project.name) })
                put("cabinet", if (cabinet == null) JSONObject.NULL else JSONObject().apply {
                    put("modelCode", cabinet.modelCode)
                    put("displayName", cabinet.displayName)
                    put("rows", cabinet.rows)
                    put("modulesPerRow", cabinet.modulesPerRow)
                })
                put("parts", JSONArray().apply {
                    parts.forEach { part ->
                        put(JSONObject().apply {
                            put("backupId", part.id)
                            put("productId", part.productId)
                            put("name", part.nameSnapshot)
                            put("unit", part.unitSnapshot)
                            put("priceKopeks", part.priceSnapshotKopeks ?: JSONObject.NULL)
                            put("modules", part.modulesSnapshot)
                            put("quantity", part.quantity)
                        })
                    }
                })
                put("rooms", JSONArray().apply {
                    rooms.forEach { room ->
                        put(JSONObject().apply {
                            put("name", room.name)
                            put("widthM", room.widthM)
                            put("lengthM", room.lengthM)
                            put("heightM", room.heightM)
                            put("sockets", room.sockets)
                        })
                    }
                })
                put("rails", JSONArray().apply {
                    rails.forEach { rail ->
                        put(JSONObject().apply {
                            put("backupId", rail.id)
                            put("name", rail.name)
                            put("capacity", rail.capacity)
                            put("sortOrder", rail.sortOrder)
                        })
                    }
                })
                put("placements", JSONArray().apply {
                    placements.forEach { location ->
                        put(JSONObject().apply {
                            put("partBackupId", location.partId)
                            put("railBackupId", location.railId)
                            put("startModule", location.startModule)
                        })
                    }
                })
            }.toString(2).also {
                require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Проект слишком большой для экспорта JSON" }
            }
        }
    }

    /** Imports a new independent project; validates EVERYTHING before the first DB write. */
    suspend fun importJson(content: String, nameOverride: String? = null): Project = withContext(Dispatchers.IO) {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Файл больше 4 МБ" }
        val json = JSONObject(content)
        require(json.optString("format") == FORMAT) { "Неподдерживаемый формат проекта" }
        val schema = json.optInt("schemaVersion")
        require(schema in 1..3) { "Неподдерживаемая версия проекта" }

        val projectName = validText(nameOverride ?: json.getJSONObject("project").getString("name"), 120, "Название проекта")
        val parts = json.getJSONArray("parts")
        val rooms = json.getJSONArray("rooms")
        val rails = if (schema >= 2) json.getJSONArray("rails") else JSONArray()
        val placements = if (schema >= 2) json.getJSONArray("placements") else JSONArray()
        require(parts.length() <= 5_000 && rooms.length() <= 1_000 &&
            rails.length() <= 16 && placements.length() <= 5_000) { "Слишком много записей" }

        val newId = UUID.randomUUID().toString()
        val oldToNewParts = mutableMapOf<String, Part>()
        val checkedParts = (0 until parts.length()).map { index ->
            val row = parts.getJSONObject(index)
            val price = if (!row.has("priceKopeks") || row.isNull("priceKopeks")) null
                else wholeNumber(row, "priceKopeks", 0, 1_000_000_000)
            val part = Part(
                projectId = newId,
                productId = validText(row.getString("productId"), 100, "ID оборудования"),
                nameSnapshot = validText(row.getString("name"), 160, "Название оборудования"),
                unitSnapshot = validText(row.getString("unit"), 20, "Единица измерения"),
                priceSnapshotKopeks = price,
                modulesSnapshot = wholeNumber(row, "modules", 0, 100).toInt(),
                quantity = wholeNumber(row, "quantity", 1, 10_000).toInt()
            )
            if (schema >= 2) {
                val backupId = validText(row.getString("backupId"), 100, "ID записи щита")
                require(oldToNewParts.putIfAbsent(backupId, part) == null) { "Повторяющийся ID аппарата" }
            }
            part
        }
        val checkedRooms = (0 until rooms.length()).map { index ->
            val row = rooms.getJSONObject(index)
            Room(
                projectId = newId,
                name = validText(row.getString("name"), 120, "Название помещения"),
                widthM = positiveDouble(row, "widthM", 1_000.0),
                lengthM = positiveDouble(row, "lengthM", 1_000.0),
                heightM = positiveDouble(row, "heightM", 100.0),
                sockets = wholeNumber(row, "sockets", 0, 10_000).toInt()
            )
        }
        val oldToNewRails = mutableMapOf<String, DinRail>()
        val checkedRails = (0 until rails.length()).map { index ->
            val row = rails.getJSONObject(index)
            val backupId = validText(row.getString("backupId"), 100, "ID DIN-рейки")
            val capacity = wholeNumber(row, "capacity", 6, 36).toInt()
            require(capacity in listOf(6, 12, 18, 24, 36)) { "Недопустимое число модулей рейки" }
            val rail = DinRail(projectId = newId,
                name = validText(row.getString("name"), 120, "Имя DIN-рейки"),
                capacity = capacity, sortOrder = wholeNumber(row, "sortOrder", 0, 1000).toInt()
            )
            require(oldToNewRails.putIfAbsent(backupId, rail) == null) { "Повторяющийся ID рейки" }
            rail
        }
        val cabinet: CabinetSelection? =
            if (schema == 3 && json.has("cabinet") && !json.isNull("cabinet")) {
                val saved = json.getJSONObject("cabinet")
                val code = validText(saved.getString("modelCode"), 80, "Код корпуса")
                val model = CabinetCatalog.find(code)
                    ?: throw IllegalArgumentException("Неизвестный типоразмер корпуса")
                require(model.rows == wholeNumber(saved, "rows", 1, 16).toInt() &&
                    model.modulesPerRow == wholeNumber(saved, "modulesPerRow", 1, 36).toInt()) {
                    "Размеры корпуса не соответствуют модели"
                }
                require(checkedRails.size <= model.rows &&
                    checkedRails.all { it.capacity == model.modulesPerRow }) {
                    "DIN-рейки не помещаются в корпус"
                }
                CabinetSelection(newId, model.code, model.title, model.rows, model.modulesPerRow)
            } else null
        val placedPartIds = mutableSetOf<String>()
        val checkedPlacements = (0 until placements.length()).map { index ->
            val row = placements.getJSONObject(index)
            val oldPartId = validText(row.getString("partBackupId"), 100, "ID аппарата")
            val oldRailId = validText(row.getString("railBackupId"), 100, "ID рейки")
            val part = oldToNewParts[oldPartId] ?: error("Размещение с неизвестным аппаратом")
            val rail = oldToNewRails[oldRailId] ?: error("Размещение с неизвестной рейкой")
            require(placedPartIds.add(oldPartId)) { "Аппарат размещён несколько раз" }
            require(part.quantity == 1 && part.modulesSnapshot > 0) { "Недопустимый аппарат для DIN-рейки" }
            val start = wholeNumber(row, "startModule", 0, 35).toInt()
            RailPlacement(partId = part.id, railId = rail.id, startModule = start)
        }
        // Reject collisions, out-of-bounds modules and damaged backup references.
        checkedRails.forEach { rail ->
            val occupied = mutableListOf<RailGeometry.Occupied>()
            checkedPlacements.filter { it.railId == rail.id }.forEach { p ->
                val part = checkedParts.single { it.id == p.partId }
                require(RailGeometry.fits(rail.capacity, p.startModule, part.modulesSnapshot, occupied)) {
                    "Конфликт модулей в резервной копии"
                }
                occupied += RailGeometry.Occupied(p.partId, p.startModule, part.modulesSnapshot)
            }
        }

        val project = Project(id = newId, name = projectName)
        database.withTransaction {
            dao.insert(project)
            checkedParts.forEach { dao.insert(it) }
            checkedRooms.forEach { dao.insert(it) }
            if (cabinet != null) dao.saveCabinet(cabinet)
            checkedRails.forEach { dao.insertRail(it) }
            checkedPlacements.forEach { dao.savePlacement(it) }
        }
        project
    }

    /** Copies the project and mechanical layout while remapping IDs and preserving price snapshots. */
    suspend fun duplicateProject(projectId: String): Project {
        val projectName = withContext(Dispatchers.IO) {
            dao.getProject(projectId)?.name ?: error("Проект не найден")
        }
        return importJson(exportJson(projectId), nameOverride = ("Копия — " + projectName).take(120))
    }

    private fun validText(value: String, limit: Int, label: String): String = value.trim().also {
        require(it.isNotBlank() && it.length <= limit && it.none(Char::isISOControl)) { "Некорректное поле: $label" }
    }

    private fun wholeNumber(json: JSONObject, field: String, min: Long, max: Long): Long {
        val raw = json.get(field)
        require(raw is Number) { "$field должен быть числом" }
        val number = raw.toString().toLongOrNull() ?: throw IllegalArgumentException("$field должен быть целым числом")
        require(number in min..max) { "$field вне допустимого диапазона" }
        return number
    }

    private fun positiveDouble(json: JSONObject, field: String, max: Double): Double {
        val raw = json.get(field)
        require(raw is Number) { "$field должен быть числом" }
        val value = raw.toDouble()
        require(value.isFinite() && value in 0.01..max) { "$field вне допустимого диапазона" }
        return value
    }

    companion object {
        const val FORMAT = "ru.pomoshchnik.elektrika.project"
        const val MAX_BYTES = 4_000_000
    }
}
