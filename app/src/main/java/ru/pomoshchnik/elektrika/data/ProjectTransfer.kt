package ru.pomoshchnik.elektrika.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Offline, human-readable single-project backup. Exports no account or device metadata.
 * Imported records receive fresh IDs, so an existing project can never be overwritten.
 */
class ProjectTransfer(private val database: LocalDatabase) {
    private val dao = database.dao()

    suspend fun exportJson(projectId: String): String = withContext(Dispatchers.IO) {
        database.withTransaction {
            val project = dao.getProject(projectId) ?: error("Проект не найден")
            val parts = dao.getParts(projectId)
            val rooms = dao.getRooms(projectId)
            JSONObject().apply {
                put("format", FORMAT)
                put("schemaVersion", 1)
                put("project", JSONObject().apply { put("name", project.name) })
                put("parts", JSONArray().apply {
                    parts.forEach { part ->
                        put(JSONObject().apply {
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
            }.toString(2).also {
                require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Проект слишком большой для экспорта JSON" }
            }
        }
    }

    suspend fun importJson(content: String): Project = withContext(Dispatchers.IO) {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Файл больше 4 МБ" }
        val json = JSONObject(content)
        require(json.optString("format") == FORMAT) { "Неподдерживаемый формат проекта" }
        require(json.optInt("schemaVersion") == 1) { "Неподдерживаемая версия проекта" }
        val projectName = validText(json.getJSONObject("project").getString("name"), 120, "Название проекта")
        val parts = json.getJSONArray("parts")
        val rooms = json.getJSONArray("rooms")
        require(parts.length() <= 5_000 && rooms.length() <= 1_000) { "Слишком много записей" }

        // Validate everything before creating the project or its children.
        val newId = UUID.randomUUID().toString()
        val checkedParts = (0 until parts.length()).map { index ->
            val row = parts.getJSONObject(index)
            val price = if (!row.has("priceKopeks") || row.isNull("priceKopeks")) null
                else wholeNumber(row, "priceKopeks", 0, 1_000_000_000)
            Part(
                projectId = newId,
                productId = validText(row.getString("productId"), 100, "ID оборудования"),
                nameSnapshot = validText(row.getString("name"), 160, "Название оборудования"),
                unitSnapshot = validText(row.getString("unit"), 20, "Единица измерения"),
                priceSnapshotKopeks = price,
                modulesSnapshot = wholeNumber(row, "modules", 0, 100).toInt(),
                quantity = wholeNumber(row, "quantity", 1, 10_000).toInt()
            )
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
        val project = Project(id = newId, name = projectName)
        database.withTransaction {
            dao.insert(project)
            checkedParts.forEach { dao.insert(it) }
            checkedRooms.forEach { dao.insert(it) }
        }
        project
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
