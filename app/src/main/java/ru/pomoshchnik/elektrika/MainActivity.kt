package ru.pomoshchnik.elektrika

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.room.Room as RoomFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.pomoshchnik.elektrika.data.*
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

private val accent = Color(0xFFCE403F)
private fun rub(kopeks: Long): String = NumberFormat.getCurrencyInstance(Locale("ru", "RU")).format(BigDecimal.valueOf(kopeks, 2))

class MainActivity : ComponentActivity() {
    private fun readLimitedJson(uri: android.net.Uri, maxBytes: Int): String {
        return contentResolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                require(output.size() <= maxBytes) { "Файл больше допустимых 4 МБ" }
            }
            output.toString("UTF-8")
        } ?: kotlin.error("Не удалось прочитать файл")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val db = RoomFactory.databaseBuilder(applicationContext, LocalDatabase::class.java, "electrician.db")
            .addMigrations(LocalDatabase.MIGRATION_1_2).build()
        val catalogService = CatalogService(db)
        val projectTransfer = ProjectTransfer(db)
        val dao = db.dao()
        setContent {
            val scope = rememberCoroutineScope()
            var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
            var tab by remember { mutableIntStateOf(0) }
            var selectedId by remember { mutableStateOf<String?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            var exportProjectId by remember { mutableStateOf<String?>(null) }
            val projectFlow = remember { dao.projects() }
            val productFlow = remember { dao.products() }
            val stateFlow = remember { dao.catalogState() }
            val projects by projectFlow.collectAsState(initial = emptyList())
            val products by productFlow.collectAsState(initial = emptyList())
            val catalogState by stateFlow.collectAsState(initial = null)
            // key() recreates the collection state when switching projects, preventing
            // the previous project's devices/rooms appearing while the new Flow loads.
            val parts = key(selectedId) {
                selectedId?.let { dao.parts(it).collectAsState(initial = emptyList()).value }
                    ?: emptyList()
            }
            val rooms = key(selectedId) {
                selectedId?.let { dao.rooms(it).collectAsState(initial = emptyList()).value }
                    ?: emptyList()
            }
            LaunchedEffect(Unit) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        catalogService.initialize()
                    }
                }.onFailure { error = it.localizedMessage }
            }
            LaunchedEffect(projects) {
                if (projects.isNotEmpty() && projects.none { it.id == selectedId }) selectedId = projects.first().id
                if (projects.isEmpty()) selectedId = null
            }
            val active = projects.firstOrNull { it.id == selectedId }
            val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
                if (uri != null) {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            val lines = mutableListOf("ПОМОЩНИК ЭЛЕКТРИКА", "Смета: " + (active?.name ?: "Проект"), "")
                            parts.forEach { part ->
                                val sum = part.priceSnapshotKopeks?.let { rub(it * part.quantity) } ?: "цена не указана"
                                lines += part.nameSnapshot + " × " + part.quantity + " — " + sum
                            }
                            lines += ""
                            lines += "Итого по известным ценам: " + rub(EstimateMath.calculate(parts.map { EstimateLine(it.priceSnapshotKopeks, it.quantity) }).knownKopeks)
                            if (parts.any { it.priceSnapshotKopeks == null }) lines += "ВНИМАНИЕ: стоимость неполная"
                            writePdf(output, lines)
                        } ?: kotlin.error("Нет доступа к файлу")
                    }.onSuccess { Toast.makeText(this, "PDF сохранён", Toast.LENGTH_SHORT).show() }
                        .onFailure { error = it.localizedMessage }
                }
            }
            val catalogueImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    runCatching {
                        val content = withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.use { stream ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val n = stream.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    require(output.size() <= CatalogService.MAX_BYTES) { "Файл больше 4 МБ" }
                                }
                                output.toString("UTF-8")
                            } ?: kotlin.error("Не удалось открыть каталог")
                        }
                        catalogService.importJson(content)
                    }.onSuccess { number ->
                        Toast.makeText(this@MainActivity, "Каталог обновлён: $number позиций", Toast.LENGTH_LONG).show()
                    }.onFailure { error = "Обновление каталога: ${it.localizedMessage}" }
                }
            }
            val projectExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                val id = exportProjectId
                exportProjectId = null
                if (uri != null && id != null) scope.launch {
                    runCatching {
                        val data = projectTransfer.exportJson(id)
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray(Charsets.UTF_8)) }
                                ?: kotlin.error("Не удалось сохранить файл")
                        }
                    }.onSuccess {
                        Toast.makeText(this@MainActivity, "Резервная копия сохранена", Toast.LENGTH_SHORT).show()
                    }.onFailure { error = "Экспорт: ${it.localizedMessage}" }
                }
            }
            val projectImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) scope.launch {
                    runCatching {
                        val data = withContext(Dispatchers.IO) { readLimitedJson(uri, ProjectTransfer.MAX_BYTES) }
                        projectTransfer.importJson(data)
                    }.onSuccess { imported ->
                        selectedId = imported.id
                        Toast.makeText(this@MainActivity, "Импортирован проект: ${imported.name}", Toast.LENGTH_LONG).show()
                    }.onFailure { error = "Импорт проекта: ${it.localizedMessage}" }
                }
            }
            val scheme = if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)
            MaterialTheme(colorScheme = scheme) {
                Scaffold(
                    topBar = {
                        Column {
                            Text("ПОМОЩНИК ЭЛЕКТРИКА", fontWeight = FontWeight.Bold, modifier = Modifier.padding(18.dp))
                            HorizontalDivider()
                        }
                    },
                    bottomBar = {
                        NavigationBar {
                            val labels = listOf("Объекты", "Щит", "План", "Расчёт", "Смета", "Ещё")
                            labels.forEachIndexed { i, label ->
                                NavigationBarItem(
                                    selected = i == tab, onClick = { tab = i },
                                    icon = { Text(listOf("▣", "⚡", "⌂", "∑", "₽", "⚙")[i]) },
                                    label = { Text(label, maxLines = 1) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (tab) {
                            0 -> ProjectsPage(projects, selectedId, onSelect = { selectedId = it },
                                onCreate = { name ->
                                    scope.launch {
                                        runCatching {
                                            val project = Project(name = name.trim())
                                            withContext(Dispatchers.IO) { dao.insert(project) }
                                            selectedId = project.id
                                        }.onFailure { error = it.localizedMessage }
                                    }
                                },
                                onDelete = { id ->
                                    scope.launch { runCatching { withContext(Dispatchers.IO) { dao.deleteProject(id) } }
                                        .onFailure { error = it.localizedMessage } }
                                },
                                onExport = { project ->
                                    exportProjectId = project.id
                                    projectExporter.launch("project-" + project.name.take(45).replace(Regex("[^a-zA-Zа-яА-ЯёЁ0-9 _-]"), "_") + ".json")
                                },
                                onImport = { projectImporter.launch(arrayOf("application/json", "text/plain")) }
                            )
                            1 -> if (active == null) EmptyProject() else PanelPage(active.name, parts, products,
                                onAdd = { product -> scope.launch {
                                    runCatching { withContext(Dispatchers.IO) {
                                        dao.insert(Part(projectId = active.id, productId = product.id, nameSnapshot = product.name,
                                            unitSnapshot = product.unit, priceSnapshotKopeks = product.priceKopeks,
                                            modulesSnapshot = product.modules))
                                    } }.onFailure { error = it.localizedMessage }
                                } },
                                onRemove = { part -> scope.launch { withContext(Dispatchers.IO) { dao.deletePart(part.id, active.id) } } }
                            )
                            2 -> if (active == null) EmptyProject() else RoomsPage(rooms,
                                onAdd = { name, w, l, h, sockets -> scope.launch {
                                    runCatching { withContext(Dispatchers.IO) {
                                        dao.insert(Room(projectId = active.id, name = name, widthM = w,
                                            lengthM = l, heightM = h, sockets = sockets))
                                    } }.onFailure { error = it.localizedMessage }
                                } },
                                onRemove = { room -> scope.launch { withContext(Dispatchers.IO) { dao.deleteRoom(room.id, active.id) } } }
                            )
                            3 -> CalculatorPage()
                            4 -> if (active == null) EmptyProject() else EstimatePage(parts) {
                                pdfLauncher.launch("smeta-" + active.name.take(40) + ".pdf")
                            }
                            else -> SettingsPage(dark, setDark = {
                                dark = it
                                prefs.edit().putBoolean("dark", it).apply()
                            }, version = catalogState?.version ?: "Загрузка...",
                                onImport = { catalogueImporter.launch(arrayOf("application/json", "text/plain")) })
                        }
                        if (error != null) AlertDialog(onDismissRequest = { error = null },
                            title = { Text("Ошибка") }, text = { Text(error ?: "") },
                            confirmButton = { TextButton(onClick = { error = null }) { Text("Понятно") } })
                    }
                }
            }
        }
    }
}

private fun writePdf(output: java.io.OutputStream, lines: List<String>) {
    val pdf = PdfDocument()
    try {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = android.graphics.Color.BLACK }
        val pageLines = 47
        lines.chunked(pageLines).forEachIndexed { i, chunk ->
            val info = PdfDocument.PageInfo.Builder(595, 842, i + 1).create()
            val page = pdf.startPage(info)
            page.canvas.drawColor(android.graphics.Color.WHITE)
            chunk.forEachIndexed { n, line ->
                // Clip long labels; line wrapping and graphical documents follow in later stages.
                page.canvas.drawText(line.take(84), 35f, 40f + n * 16f, paint)
            }
            pdf.finishPage(page)
        }
        pdf.writeTo(output)
    } finally { pdf.close() }
}

@Composable private fun Page(title: String, body: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        body()
    }
}

@Composable private fun EmptyProject() {
    Page("Нет выбранного объекта") { Text("Создай проект во вкладке «Объекты».") }
}

@Composable private fun ProjectsPage(
    projects: List<Project>, selectedId: String?, onSelect: (String) -> Unit,
    onCreate: (String) -> Unit, onDelete: (String) -> Unit,
    onExport: (Project) -> Unit, onImport: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    Page("Мои объекты") {
        OutlinedTextField(name, { name = it }, label = { Text("Название объекта") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { onCreate(name); name = "" }, enabled = name.isNotBlank() && name.length <= 120,
            modifier = Modifier.fillMaxWidth()) { Text("Создать проект") }
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text("Импортировать проект из JSON")
        }
        if (projects.isEmpty()) Text("Пока нет проектов. Все будущие данные будут храниться только в телефоне.")
        projects.forEach { project ->
            ElevatedCard(onClick = { onSelect(project.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(project.name, fontWeight = FontWeight.Medium)
                        Text(if (selectedId == project.id) "Открыт" else "Нажми для открытия",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column {
                        TextButton(onClick = { onExport(project) }) { Text("Экспорт") }
                        TextButton(onClick = { deleteId = project.id }) { Text("Удалить") }
                    }
                }
            }
        }
        Text("Перед удалением объекта сохрани резервную копию в JSON: восстановление удалённого проекта без файла невозможно.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text("Удалить объект?") }, text = { Text("Действие необратимо. Резервной копии пока нет.") },
        confirmButton = { TextButton(onClick = { deleteId?.let(onDelete); deleteId = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } })
}

@Composable private fun PanelPage(
    projectName: String, parts: List<Part>, products: List<Product>,
    onAdd: (Product) -> Unit, onRemove: (Part) -> Unit
) {
    var filter by remember { mutableStateOf("") }
    Page("Щит · " + projectName) {
        Text("Предварительный перечень аппаратов. DIN-рейки и электрические связи будут добавлены в редакторе щита.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Добавлено аппаратов: " + parts.size)
        parts.forEach { part ->
            ListItem(headlineContent = { Text(part.nameSnapshot) },
                supportingContent = { Text(if (part.priceSnapshotKopeks == null) "Цена неизвестна" else rub(part.priceSnapshotKopeks)) },
                trailingContent = { TextButton(onClick = { onRemove(part) }) { Text("×") } })
        }
        HorizontalDivider()
        Text("Каталог · 32 типовые позиции")
        OutlinedTextField(filter, { filter = it }, label = { Text("Поиск оборудования") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        products.filter { it.name.contains(filter, ignoreCase = true) || it.category.contains(filter, ignoreCase = true) }
            .take(40).forEach { product ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(product.name)
                            Text(product.category + " · " + product.modules + " мод.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onAdd(product) }) { Text("+") }
                    }
                }
            }
    }
}

@Composable private fun RoomsPage(rooms: List<Room>, onAdd: (String, Double, Double, Double, Int) -> Unit, onRemove: (Room) -> Unit) {
    var name by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("3.5") }
    var length by remember { mutableStateOf("4") }
    var height by remember { mutableStateOf("2.7") }
    var sockets by remember { mutableStateOf("4") }
    val w = width.replace(',', '.').toDoubleOrNull()
    val l = length.replace(',', '.').toDoubleOrNull()
    val h = height.replace(',', '.').toDoubleOrNull()
    val count = sockets.toIntOrNull()
    val valid = name.isNotBlank() && name.length <= 120 && w != null && w in 0.01..1000.0 &&
        l != null && l in 0.01..1000.0 && h != null && h in 0.01..100.0 && count != null && count in 0..10000
    Page("Планировка помещений") {
        Text("Это первый этап: размеры и количество розеток. Автоматический метраж кабеля появится после задания трасс.")
        OutlinedTextField(name, { name = it }, label = { Text("Комната") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumInput("Ширина, м", width, { width = it }, Modifier.weight(1f))
            NumInput("Длина, м", length, { length = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumInput("Высота, м", height, { height = it }, Modifier.weight(1f))
            NumInput("Розетки", sockets, { sockets = it }, Modifier.weight(1f))
        }
        Button(onClick = { onAdd(name.trim(), w!!, l!!, h!!, count!!); name = "" },
            enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Добавить помещение") }
        rooms.forEach { room ->
            ListItem(headlineContent = { Text(room.name) },
                supportingContent = { Text("Площадь " + "%.2f".format(room.widthM * room.lengthM) + " м² · розеток " + room.sockets) },
                trailingContent = { TextButton(onClick = { onRemove(room) }) { Text("×") } })
        }
        Text("Расстояния до щита нельзя определить только по длине и ширине комнат. Нужны расположение щита и маршруты прокладки.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun NumInput(title: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, label = { Text(title) }, modifier = modifier,
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable private fun CalculatorPage() {
    var power by remember { mutableStateOf("3500") }
    var voltage by remember { mutableStateOf("230") }
    var factor by remember { mutableStateOf("1") }
    var three by remember { mutableStateOf(false) }
    val p = power.replace(',', '.').toDoubleOrNull()
    val v = voltage.replace(',', '.').toDoubleOrNull()
    val k = factor.replace(',', '.').toDoubleOrNull()
    val current = runCatching {
        if (p == null || v == null || k == null) null
        else if (three) Engineering.threePhaseCurrent(p, v, k)
        else Engineering.singlePhaseCurrent(p, v, k)
    }.getOrNull()
    Page("Калькулятор электрика") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Трёхфазная сеть")
            Spacer(Modifier.weight(1f))
            Switch(three, { three = it })
        }
        NumInput("Мощность, Вт", power, { power = it }, Modifier.fillMaxWidth())
        NumInput("Напряжение, В", voltage, { voltage = it }, Modifier.fillMaxWidth())
        NumInput("Коэффициент мощности", factor, { factor = it }, Modifier.fillMaxWidth())
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Расчётный ток", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (current == null) "Проверь ввод" else "%.2f А".format(current),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
        }
        Text("Расчёт не заменяет проверку защитных устройств, сечения кабеля и требований действующих норм.")
    }
}

@Composable private fun EstimatePage(parts: List<Part>, onPdf: () -> Unit) {
    val totals = EstimateMath.calculate(parts.map { EstimateLine(it.priceSnapshotKopeks, it.quantity) })
    val sum = totals.knownKopeks
    val missing = totals.unknownLines
    Page("Смета электрощита") {
        Text("Цены сохраняются при добавлении в проект. У типового каталога цены намеренно не указаны.")
        parts.forEach { part ->
            ListItem(headlineContent = { Text(part.nameSnapshot) },
                supportingContent = { Text(part.quantity.toString() + " " + part.unitSnapshot) },
                trailingContent = { Text(part.priceSnapshotKopeks?.let { rub(it * part.quantity) } ?: "—") })
        }
        HorizontalDivider()
        Text((if (missing > 0) "Известная часть сметы: " else "Итого: ") + rub(sum),
            style = MaterialTheme.typography.titleLarge)
        if (missing > 0) Text("Без цены: " + missing + " позиций. Это не окончательная стоимость.",
            color = MaterialTheme.colorScheme.error)
        Button(onClick = onPdf, modifier = Modifier.fillMaxWidth()) { Text("Сохранить смету в PDF") }
    }
}

@Composable private fun SettingsPage(dark: Boolean, setDark: (Boolean) -> Unit, version: String, onImport: () -> Unit) {
    Page("Настройки") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Тёмная тема", modifier = Modifier.weight(1f))
            Switch(dark, setDark)
        }
        HorizontalDivider()
        Text("Версия каталога: " + version)
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Обновить базу из JSON-файла") }
        Text("Импорт проверяет весь файл до установки. При ошибке установленная база сохраняется.")
        Text("Серверные обновления появятся на этапе 7. Работа без интернета сохранится.")
        HorizontalDivider()
        Text("Поддержать автора", style = MaterialTheme.typography.titleMedium)
        Text("Приложение полностью бесплатное. Контакт Telegram и реквизиты доната появятся перед публичным релизом.")
        Text("0.4.0-alpha · Открытый исходный код: github.com/xottab32-droid/Pomoshchnik-Elektrika",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
