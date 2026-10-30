package ru.pomoshchnik.elektrika.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Offline catalogue import. No network permissions or project-price recalculation. */
@Entity(tableName = "catalog_state")
data class CatalogState(
    @PrimaryKey val id: Int = 1,
    val version: String,
    val source: String,
    val installedAt: Long
)

class CatalogService(private val database: LocalDatabase) {
    private val dao = database.dao()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (dao.getCatalogState() == null) {
                if (dao.productCount() == 0) dao.insertProducts(BundledCatalog.entries)
                dao.saveCatalogState(CatalogState(version = "LOCAL-1", source = "bundled", installedAt = System.currentTimeMillis()))
            }
        }
    }

    /** Parse and validate the *whole* package before changing a single database row. */
    suspend fun importJson(content: String): Int = withContext(Dispatchers.IO) {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Файл больше 4 МБ" }
        val root = JSONObject(content)
        require(root.optString("format") == "ru.pomoshchnik.elektrika.catalog") { "Неверный формат каталога" }
        require(root.optInt("schemaVersion") == 1) { "Неподдерживаемая схема каталога" }
        val version = text(root.getString("version"), 64, "Версия")
        require(version != "LOCAL-1") { "Зарезервированная версия" }
        val jsonItems = root.getJSONArray("items")
        require(jsonItems.length() in 1..10_000) { "Каталог должен содержать от 1 до 10 000 записей" }
        val ids = HashSet<String>()
        val validated = ArrayList<Product>(jsonItems.length())
        for (i in 0 until jsonItems.length()) {
            val row = jsonItems.getJSONObject(i)
            val id = text(row.getString("id"), 100, "ID")
            require(id.matches(Regex("[a-zA-Z0-9._:-]+"))) { "Некорректный ID $id" }
            require(ids.add(id)) { "Повторяющийся ID $id" }
            val category = text(row.getString("category"), 80, "Категория")
            val name = text(row.getString("name"), 160, "Наименование")
            val unit = text(row.getString("unit"), 20, "Единица измерения")
            val modules = integer(row, "modules")
            require(modules in 0..100) { "Некорректная ширина $id" }
            val price: Long? = if (!row.has("priceKopeks") || row.isNull("priceKopeks")) null else integer(row, "priceKopeks")
            require(price == null || price in 0..1_000_000_000L) { "Некорректная цена $id" }
            validated.add(Product(id, category, name, unit, modules.toInt(), price))
        }
        database.withTransaction {
            require(dao.getCatalogState()?.version != version) { "Версия $version уже установлена" }
            dao.deleteAllProducts()
            dao.insertProducts(validated)
            dao.saveCatalogState(CatalogState(version = version, source = "cloud", installedAt = System.currentTimeMillis()))
        }
        validated.size
    }

    private fun integer(row: JSONObject, key: String): Long {
        val v = row.get(key)
        require(v is Number) { "$key должно быть числом" }
        return v.toString().toLongOrNull() ?: throw IllegalArgumentException("$key должно быть целым числом")
    }

    private fun text(value: String, limit: Int, label: String): String = value.trim().also {
        require(it.isNotBlank() && it.length <= limit && it.none(Char::isISOControl)) { "Некорректное поле «$label»" }
    }

    companion object {
        const val MAX_BYTES = 4_000_000
    }
}
