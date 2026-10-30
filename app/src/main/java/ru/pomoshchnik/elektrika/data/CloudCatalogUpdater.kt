package ru.pomoshchnik.elektrika.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** No account, analytics, or project data. One fixed public HTTPS GET on button press. */
internal object SupabaseCatalogueClient {
    private const val ENDPOINT = "https://olqazmqalxtqznehpmkb.supabase.co/rest/v1/catalog_releases?select=version,payload&published=eq.true&order=published_at.desc&limit=1"

    // Public browser/mobile key, NOT a secret or elevated server key.
    private const val PUBLISHABLE_KEY = "sb_publishable_B8cCVefhToCWwGMeR_jDCA_Ww8cW8kq"

    suspend fun fetchLatest(): CloudCatalogRelease = withContext(Dispatchers.IO) {
        val connection = URL(ENDPOINT).openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.useCaches = false
        connection.setRequestProperty("apikey", PUBLISHABLE_KEY)
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        try {
            val status = connection.responseCode
            require(status == 200) { "Не удалось получить каталог (HTTP " + status + ")" }
            val body = connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                while (true) {
                    val bytes = input.read(buffer)
                    if (bytes < 0) break
                    output.write(buffer, 0, bytes)
                    require(output.size() <= MAX_RESPONSE_BYTES) { "Каталог с сервера слишком большой" }
                }
                output.toString("UTF-8")
            }
            val releases = JSONArray(body)
            require(releases.length() == 1) { "На сервере ещё нет опубликованного каталога" }
            val entry = releases.getJSONObject(0)
            val version = entry.getString("version")
            require(version.isNotBlank() && version.length <= 64) { "Некорректная версия на сервере" }
            val payload = entry.getJSONObject("payload")
            require(payload.getString("version") == version) { "Версия ответа не совпадает с каталогом" }
            CloudCatalogRelease(version, payload.toString())
        } finally {
            connection.disconnect()
        }
    }

    private const val MAX_RESPONSE_BYTES = 4_250_000
}

data class CloudCatalogRelease(val version: String, val payload: String)

sealed interface CloudUpdateResult {
    data class AlreadyCurrent(val version: String) : CloudUpdateResult
    data class Installed(val previous: String, val version: String, val products: Int) : CloudUpdateResult
}

/** Injectable release loader makes update behavior testable completely offline. */
class CloudCatalogUpdater(
    private val service: CatalogService,
    private val dao: LocalDao,
    private val loadRelease: suspend () -> CloudCatalogRelease
) {
    suspend fun update(): CloudUpdateResult = withContext(Dispatchers.IO) {
        val release = loadRelease()
        require(org.json.JSONObject(release.payload).getString("version") == release.version) { "Версия ответа не совпадает с каталогом" }
        val currentVersion = dao.getCatalogState()?.version ?: "не установлена"
        if (currentVersion == release.version) {
            CloudUpdateResult.AlreadyCurrent(release.version)
        } else {
            val installedCount = service.importJson(release.payload)
            CloudUpdateResult.Installed(currentVersion, release.version, installedCount)
        }
    }
}
