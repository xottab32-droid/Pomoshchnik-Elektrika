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
import androidx.core.view.WindowInsetsControllerCompat
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
            .addMigrations(LocalDatabase.MIGRATION_1_2, LocalDatabase.MIGRATION_2_3).build()
        val catalogService = CatalogService(db)
        val railEditor = DinRailEditor(db)
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
            val rails = key(selectedId) {
                selectedId?.let { dao.rails(it).collectAsState(initial = emptyList()).value }
                    ?: emptyList()
            }
            val placements = key(selectedId) {
                selectedId?.let { dao.placements(it).collectAsState(initial = emptyList()).value }
                    ?: emptyList()
            }
            // Existing Room entities are retained for old project backups, but the plan UI is postponed.
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
            var catalogueUpdating by remember { mutableStateOf(false) }
            var catalogueUpdateMessage by remember { mutableStateOf<String?>(null) }
            val cloudUpdater = remember {
                CloudCatalogUpdater(catalogService, dao) { SupabaseCatalogueClient.fetchLatest() }
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
            SideEffect {
                // Keep the Android clock/notification icons readable in both app themes.
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = scheme) {
                Scaffold(
                    topBar = {
                        // Android 15+ forces edge-to-edge; top bars MUST consume status-bar insets.
                        // Draw the surface behind the system bar, but keep ALL app content below it.
                        Column(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .statusBarsPadding()
                        ) {
                            Text("ПОМОЩНИК ЭЛЕКТРИКА", fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp))
                            HorizontalDivider()
                        }
                    },
                    bottomBar = {
                        NavigationBar {
                            val labels = listOf("Объекты", "Щит", "Расчёт", "Смета", "Ещё")
                            labels.forEachIndexed { i, label ->
                                NavigationBarItem(
                                    selected = i == tab, onClick = { tab = i },
                                    icon = { Text(listOf("▣", "⚡", "∑", "₽", "⚙")[i]) },
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
                                onImport = { projectImporter.launch(arrayOf("application/json", "text/plain")) },
                                onRename = { project, newName ->
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                dao.renameProject(project.id, newName.trim(), System.currentTimeMillis())
                                            }
                                        }.onFailure { error = "Переименование: ${it.localizedMessage}" }
                                    }
                                },
                                onDuplicate = { project ->
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) { projectTransfer.duplicateProject(project.id) }
                                        }.onSuccess { copy -> selectedId = copy.id }
                                            .onFailure { error = "Копирование: ${it.localizedMessage}" }
                                    }
                                }
                            )
                            1 -> if (active == null) EmptyProject() else PanelPage(
                                projectId = active.id, projectName = active.name,
                                parts = parts, products = products, rails = rails, placements = placements,
                                onAdd = { product ->
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) {
                                            dao.insert(Part(projectId = active.id, productId = product.id,
                                                nameSnapshot = product.name, unitSnapshot = product.unit,
                                                priceSnapshotKopeks = product.priceKopeks, modulesSnapshot = product.modules))
                                        } }.onFailure { error = it.localizedMessage }
                                    }
                                },
                                onRemove = { part ->
                                    scope.launch { runCatching { withContext(Dispatchers.IO) { dao.deletePart(part.id, active.id) } }
                                        .onFailure { error = it.localizedMessage } }
                                },
                                onAddRail = { capacity ->
                                    scope.launch { runCatching { railEditor.addRail(active.id, capacity) }
                                        .onFailure { error = it.localizedMessage } }
                                },
                                onDeleteRail = { rail ->
                                    scope.launch { runCatching { railEditor.removeRail(active.id, rail.id) }
                                        .onFailure { error = it.localizedMessage } }
                                },
                                onPlace = { partId, railId, moduleIndex ->
                                    scope.launch { runCatching { railEditor.placePart(active.id, partId, railId, moduleIndex) }
                                        .onFailure { error = it.localizedMessage } }
                                },
                                onUnplace = { partId ->
                                    scope.launch { runCatching { railEditor.unplace(active.id, partId) }
                                        .onFailure { error = it.localizedMessage } }
                                }
                            )
                            2 -> CalculatorPage()
                            3 -> if (active == null) EmptyProject() else EstimatePage(parts) {
                                pdfLauncher.launch("smeta-" + active.name.take(40) + ".pdf")
                            }
                            else -> SettingsPage(dark, setDark = {
                                dark = it
                                prefs.edit().putBoolean("dark", it).apply()
                            }, version = catalogState?.version ?: "Загрузка...",
                                updating = catalogueUpdating, message = catalogueUpdateMessage,
                                onUpdate = {
                                    if (!catalogueUpdating) {
                                        catalogueUpdating = true
                                        catalogueUpdateMessage = "Проверка новой версии на сервере..."
                                        scope.launch {
                                            try {
                                                when (val result = cloudUpdater.update()) {
                                                    is CloudUpdateResult.AlreadyCurrent ->
                                                        catalogueUpdateMessage = "Установлена актуальная версия " + result.version
                                                    is CloudUpdateResult.Installed ->
                                                        catalogueUpdateMessage = "База обновлена: " + result.previous + " → " +
                                                            result.version + " (" + result.products + " позиций)"
                                                }
                                            } catch (e: Exception) {
                                                catalogueUpdateMessage = "Ошибка обновления. Старая база сохранена."
                                                error = "Обновление каталога: " + (e.localizedMessage ?: "Неизвестная ошибка")
                                            } finally {
                                                catalogueUpdating = false
                                            }
                                        }
                                    }
                                })
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
    onExport: (Project) -> Unit, onImport: () -> Unit,
    onRename: (Project, String) -> Unit, onDuplicate: (Project) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var renameName by remember { mutableStateOf("") }
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
                        TextButton(onClick = {
                            renameTarget = project
                            renameName = project.name
                        }) { Text("Название") }
                        TextButton(onClick = { onDuplicate(project) }) { Text("Копия") }
                        TextButton(onClick = { onExport(project) }) { Text("Экспорт") }
                        TextButton(onClick = { deleteId = project.id }) { Text("Удалить") }
                    }
                }
            }
        }
        Text("Перед удалением объекта сохрани резервную копию в JSON: восстановление удалённого проекта без файла невозможно.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (renameTarget != null) AlertDialog(
        onDismissRequest = { renameTarget = null },
        title = { Text("Переименовать объект") },
        text = { OutlinedTextField(renameName, { renameName = it },
            label = { Text("Название") }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                renameTarget?.let { onRename(it, renameName) }
                renameTarget = null
            }, enabled = renameName.isNotBlank() && renameName.trim().length <= 120) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Отмена") } }
    )
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text("Удалить объект?") }, text = { Text("Действие необратимо. Сохрани резервную копию через «Экспорт», если данные нужны.") },
        confirmButton = { TextButton(onClick = { deleteId?.let(onDelete); deleteId = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Отмена") } })
}

@Composable private fun PanelPage(
    projectId: String, projectName: String, parts: List<Part>, products: List<Product>,
    rails: List<DinRail>, placements: List<RailPlacement>,
    onAdd: (Product) -> Unit, onRemove: (Part) -> Unit,
    onAddRail: (Int) -> Unit, onDeleteRail: (DinRail) -> Unit,
    onPlace: (String, String, Int?) -> Unit, onUnplace: (String) -> Unit
) {
    var search by remember(projectId) { mutableStateOf("") }
    var category by remember(projectId) { mutableStateOf("Все") }
    var selectedPartId by remember(projectId) { mutableStateOf<String?>(null) }
    var deletingRail by remember(projectId) { mutableStateOf<DinRail?>(null) }
    val partById = parts.associateBy { it.id }
    val placementByPart = placements.associateBy { it.partId }
    val categories = listOf("Все") + products.map { it.category }.distinct().sorted()
    val visibleProducts = products.filter { product ->
        (category == "Все" || product.category == category) &&
            (product.name.contains(search.trim(), ignoreCase = true) ||
                product.category.contains(search.trim(), ignoreCase = true))
    }
    Page("Щит · " + projectName) {
        Text("Компоновка DIN-реек (механическое расположение). Электрические соединения и однолинейная схема пока не создаются.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("DIN-рейки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Добавь рейку, выбери аппарат в перечне и нажми на свободный модуль. При перемещении учитывается ширина аппарата.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(6, 12, 18, 24, 36).forEach { size ->
                OutlinedButton(onClick = { onAddRail(size) }, enabled = rails.size < 16) {
                    Text("+ " + size + " мод.")
                }
            }
        }
        if (rails.isEmpty()) Text("Пока нет DIN-реек. Выбери количество модулей выше.")
        rails.forEach { rail ->
            val placedHere = placements.filter { it.railId == rail.id }
            val used = placedHere.sumOf { partById[it.partId]?.modulesSnapshot ?: 0 }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rail.name, fontWeight = FontWeight.SemiBold)
                            Text("${used}/${rail.capacity} модулей занято",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { deletingRail = rail }) { Text("Удалить") }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        var index = 0
                        while (index < rail.capacity) {
                            val placed = placedHere.firstOrNull { it.startModule == index }
                            val part = placed?.let { partById[it.partId] }
                            val width = part?.modulesSnapshot?.coerceIn(1, rail.capacity - index) ?: 1
                            val cellStart = index
                            val selected = part != null && part.id == selectedPartId
                            Box(
                                Modifier.width((width * 37).dp).height(60.dp)
                                    .padding(end = 2.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                    .background(
                                        if (part != null)
                                            if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .clickable(enabled = selectedPartId != null) {
                                        selectedPartId?.let { onPlace(it, rail.id, cellStart) }
                                    }.padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (part == null) "${index + 1}" else part.nameSnapshot,
                                    maxLines = if (width > 1) 2 else 1,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            index += width
                        }
                    }
                    if (selectedPartId != null) {
                        Button(onClick = { selectedPartId?.let { onPlace(it, rail.id, null) } }) {
                            Text("Разместить в первый свободный слот")
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Оборудование проекта · ${parts.size} поз.", style = MaterialTheme.typography.titleMedium)
        if (selectedPartId != null) {
            Text("Выбран аппарат: " + (partById[selectedPartId]?.nameSnapshot ?: "не найден") +
                ". Нажми свободный модуль на рейке, чтобы разместить или переместить.",
                color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { selectedPartId = null }) { Text("Отменить выбор") }
        }
        parts.forEach { part ->
            val location = placementByPart[part.id]
            ListItem(
                headlineContent = { Text(part.nameSnapshot) },
                supportingContent = {
                    Text(buildString {
                        append(if (part.priceSnapshotKopeks == null) "Цена неизвестна" else rub(part.priceSnapshotKopeks))
                        append(" · " + part.modulesSnapshot + " мод.")
                        if (location != null) {
                            append(" · " + (rails.firstOrNull { it.id == location.railId }?.name ?: "Рейка"))
                            append(" · поз. " + (location.startModule + 1))
                        }
                    })
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (part.modulesSnapshot > 0 && part.quantity == 1) {
                            TextButton(onClick = { selectedPartId = part.id }) { Text("Выбрать") }
                        }
                        if (location != null) {
                            TextButton(onClick = { onUnplace(part.id) }) { Text("Снять") }
                        }
                        TextButton(onClick = {
                            if (selectedPartId == part.id) selectedPartId = null
                            onRemove(part)
                        }) { Text("×") }
                    }
                }
            )
        }
        HorizontalDivider()
        Text("Каталог · ${products.size} позиций", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(search, { search = it }, label = { Text("Поиск оборудования") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { value ->
                FilterChip(selected = category == value, onClick = { category = value },
                    label = { Text(value) })
            }
        }
        Text("Найдено: ${visibleProducts.size}", style = MaterialTheme.typography.labelMedium)
        visibleProducts.take(60).forEach { product ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(product.name)
                        Text(product.category + " · " + product.modules + " мод. · " +
                            (product.priceKopeks?.let(::rub) ?: "цена неизвестна"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onAdd(product) }) { Text("+") }
                }
            }
        }
        if (visibleProducts.isEmpty()) Text("Ничего не найдено. Попробуй другую категорию или запрос.")
    }
    deletingRail?.let { rail ->
        AlertDialog(
            onDismissRequest = { deletingRail = null },
            title = { Text("Удалить ${rail.name}?") },
            text = { Text("Размещение аппаратов на этой рейке будет сброшено. Сами аппараты и смета останутся.") },
            confirmButton = {
                TextButton(onClick = { onDeleteRail(rail); deletingRail = null }) { Text("Удалить рейку") }
            },
            dismissButton = {
                TextButton(onClick = { deletingRail = null }) { Text("Отмена") }
            }
        )
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

@Composable private fun SettingsPage(dark: Boolean, setDark: (Boolean) -> Unit, version: String, updating: Boolean, message: String?, onUpdate: () -> Unit) {
    Page("Настройки") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Тёмная тема", modifier = Modifier.weight(1f))
            Switch(dark, setDark)
        }
        HorizontalDivider()
        Text("Версия каталога: " + version)
        Button(onClick = onUpdate, enabled = !updating && version != "Загрузка...",
            modifier = Modifier.fillMaxWidth()) {
            Text(if (updating) "Обновление..." else "Обновить базу")
        }
        if (message != null) Text(message)
        Text("Нажми кнопку, чтобы загрузить опубликованный каталог с сервера. " +
            "Без интернета приложение продолжает использовать сохранённую базу.")
        HorizontalDivider()
        Text("Поддержать автора", style = MaterialTheme.typography.titleMedium)
        Text("Приложение полностью бесплатное. Контакт Telegram и реквизиты доната появятся перед публичным релизом.")
        Text("0.5.0-alpha · Открытый исходный код: github.com/xottab32-droid/Pomoshchnik-Elektrika",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
