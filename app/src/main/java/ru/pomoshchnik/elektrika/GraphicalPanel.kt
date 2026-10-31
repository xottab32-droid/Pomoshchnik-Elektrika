package ru.pomoshchnik.elektrika

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.pomoshchnik.elektrika.data.*
import java.util.Locale

private val panelBackground = Color(0xFF282B32)
private val metal = Color(0xFF656E7B)
private val deviceWhite = Color(0xFFE8EAED)
private val electricRed = Color(0xFFC83B36)

/**
 * Cabinet-first graphic editor. The drawing is a mechanically representative schematic,
 * NOT a manufacturer-approved assembly drawing or an electrical wiring diagram.
 */
@Composable
fun GraphicalPanelPage(
    projectId: String,
    projectName: String,
    cabinet: CabinetSelection?,
    rails: List<DinRail>,
    placements: List<RailPlacement>,
    parts: List<Part>,
    products: List<Product>,
    onSelectCabinet: (String) -> Unit,
    onAddRail: (Int) -> Unit,
    onDeleteRail: (DinRail) -> Unit,
    onAdd: (Product) -> Unit,
    onRemove: (Part) -> Unit,
    onPlace: (String, String, Int?) -> Unit,
    onUnplace: (String) -> Unit
) {
    var pickerOpen by remember(projectId) { mutableStateOf(cabinet == null) }
    var selectedPartId by remember(projectId) { mutableStateOf<String?>(null) }
    var catalogOpen by remember(projectId) { mutableStateOf(false) }
    var query by remember(projectId) { mutableStateOf("") }
    var category by remember(projectId) { mutableStateOf("Все") }
    var zoom by remember(projectId) { mutableFloatStateOf(1f) }
    var railForDeletion by remember(projectId) { mutableStateOf<DinRail?>(null) }

    val byPartId = remember(parts) { parts.associateBy { it.id } }
    val byPlacement = remember(placements) { placements.associateBy { it.partId } }
    val categories = remember(products) { listOf("Все") + products.map { it.category }.distinct().sorted() }
    val filtered = products.filter {
        (category == "Все" || it.category == category) &&
            (it.name.contains(query.trim(), ignoreCase = true) ||
             it.category.contains(query.trim(), ignoreCase = true))
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Сборка щита", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(projectName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (cabinet != null) TextButton(onClick = { pickerOpen = !pickerOpen }) { Text("Корпус ▾") }
        }

        if (cabinet == null || pickerOpen) {
            Text("1. Выбери корпус", fontWeight = FontWeight.Bold)
            Text("Сначала типоразмер щита, затем DIN-рейки и аппараты. Макеты условные, без привязки к производителю.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CabinetChooser(
                currentCode = cabinet?.modelCode,
                onChoose = { code ->
                    onSelectCabinet(code)
                    pickerOpen = false
                }
            )
        }

        if (cabinet != null) {
            val selected = parts.firstOrNull { it.id == selectedPartId }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Масштаб", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = zoom, onValueChange = { zoom = it }, valueRange = 0.7f..1.3f,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                )
                Text(String.format(Locale.ROOT, "%.0f%%", zoom * 100f),
                    style = MaterialTheme.typography.labelSmall)
            }
            GraphicalCabinet(
                cabinet = cabinet,
                rails = rails,
                partsById = byPartId,
                placements = placements,
                selectedPartId = selectedPartId,
                zoom = zoom,
                onTapDevice = { selectedPartId = it },
                onTapEmptyModule = { railId, index -> selectedPartId?.let { onPlace(it, railId, index) } }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val canAddRail = rails.size < cabinet.rows
                Button(
                    onClick = { onAddRail(cabinet.modulesPerRow) }, enabled = canAddRail,
                    modifier = Modifier.weight(1f)
                ) { Text("+ DIN-рейка") }
                OutlinedButton(onClick = { catalogOpen = !catalogOpen }, modifier = Modifier.weight(1f)) {
                    Text(if (catalogOpen) "Скрыть аппараты" else "+ Аппарат")
                }
            }
            Text(
                if (selected == null) "Нажми аппарат ниже или на щите, затем свободный модуль DIN-рейки."
                else "Выбран: ${selected.nameSnapshot}. Нажми модуль для размещения.",
                color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )

            if (selected != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { selectedPartId = null }) { Text("Снять выбор") }
                    if (byPlacement.containsKey(selected.id)) {
                        TextButton(onClick = { onUnplace(selected.id) }) { Text("Убрать с рейки") }
                    }
                    val firstCompatible = rails.firstOrNull { rail ->
                        rail.capacity >= selected.modulesSnapshot && selected.modulesSnapshot > 0
                    }
                    if (firstCompatible != null) {
                        TextButton(onClick = { onPlace(selected.id, firstCompatible.id, null) }) {
                            Text("Авторазмещение")
                        }
                    }
                }
            }

            Text("Установленные DIN-рейки · ${rails.size}/${cabinet.rows}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (rails.isEmpty()) Text("Реек пока нет. Добавь первую рейку кнопкой над списком.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            rails.forEach { rail ->
                val used = placements.filter { it.railId == rail.id }
                    .sumOf { byPartId[it.partId]?.modulesSnapshot ?: 0 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${rail.name}: ${used}/${rail.capacity} мод.",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { railForDeletion = rail }) { Text("Удалить рейку") }
                }
            }

            HorizontalDivider()
            Text("Оборудование проекта", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (parts.isEmpty()) Text("Пока нет аппаратов. Открой каталог и добавь нужный.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            parts.forEach { part ->
                val canMount = part.modulesSnapshot > 0 && part.quantity == 1
                val placed = byPlacement[part.id]
                val highlighted = selectedPartId == part.id
                OutlinedCard(
                    onClick = { if (canMount) selectedPartId = part.id },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        DeviceGlyph(part.nameSnapshot, part.productId, part.modulesSnapshot.coerceAtLeast(1),
                            Modifier.size(45.dp, 72.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(part.nameSnapshot, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text(
                                when {
                                    !canMount -> "В смете · не размещается на DIN"
                                    placed == null -> "${part.modulesSnapshot} мод. · не установлен"
                                    else -> "На рейке · модуль ${placed.startModule + 1}"
                                }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            if (selectedPartId == part.id) selectedPartId = null
                            onRemove(part)
                        }) { Text("✕") }
                    }
                }
            }

            if (catalogOpen) {
                HorizontalDivider()
                Text("Каталог оборудования", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Поиск по каталогу") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { value ->
                        FilterChip(
                            selected = category == value, onClick = { category = value },
                            label = { Text(value) }
                        )
                    }
                }
                Text("Найдено: ${filtered.size}", style = MaterialTheme.typography.labelMedium)
                filtered.take(60).chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { product ->
                            OutlinedCard(
                                modifier = Modifier.weight(1f),
                                onClick = { onAdd(product) }
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    DeviceGlyph(product.name, product.id, product.modules.coerceAtLeast(1),
                                        Modifier.size(62.dp, 74.dp))
                                    Text(product.name, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 3)
                                    Text(if (product.modules == 0) "Материал" else "${product.modules} мод.",
                                        style = MaterialTheme.typography.labelSmall)
                                    Text("+ Добавить", color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            HorizontalDivider()
            Text("Графика отображает расположение, но пока не моделирует провода, электрические цепи и соответствие ПУЭ. " +
                "Корпуса — условные типоразмеры, не чертежи конкретных производителей.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    railForDeletion?.let { rail ->
        AlertDialog(
            onDismissRequest = { railForDeletion = null },
            title = { Text("Удалить DIN-рейку?") },
            text = { Text("Аппараты останутся в смете, но их расположение на этой рейке сбросится.") },
            confirmButton = { TextButton(onClick = {
                onDeleteRail(rail)
                railForDeletion = null
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { railForDeletion = null }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun CabinetChooser(currentCode: String?, onChoose: (String) -> Unit) {
    CabinetCatalog.models.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { model ->
                val selected = currentCode == model.code
                OutlinedCard(
                    onClick = { onChoose(model.code) },
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CabinetThumbnail(model, Modifier.fillMaxWidth().height(112.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(model.title, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium)
                        Text("${model.rows} × ${model.modulesPerRow} = ${model.totalModules} мод.",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CabinetThumbnail(model: CabinetModel, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(color = Color(0xFF252830), cornerRadius = CornerRadius(14f))
        drawRoundRect(color = Color(0xFF4C535D), topLeft = Offset(10f, 8f),
            size = Size(w - 20f, h - 16f), cornerRadius = CornerRadius(10f))
        val innerX = 22f
        val innerW = w - 44f
        for (i in 0 until model.rows) {
            val y = h * (i + 1f) / (model.rows + 1f)
            drawRoundRect(color = Color(0xFF171B22), topLeft = Offset(innerX, y - 7f),
                size = Size(innerW, 14f), cornerRadius = CornerRadius(3f))
            for (m in 0 until model.modulesPerRow) {
                val x = innerX + (m + .5f) * innerW / model.modulesPerRow
                drawRoundRect(color = Color(0xFF9AA4B2), topLeft = Offset(x - 2.2f, y - 2.2f),
                    size = Size(4.4f, 4.4f), cornerRadius = CornerRadius(2f))
            }
        }
    }
}

/** Dark metal cabinet, DIN track and labelled non-functional neutral/protective bus bars. */
@Composable
private fun GraphicalCabinet(
    cabinet: CabinetSelection,
    rails: List<DinRail>,
    partsById: Map<String, Part>,
    placements: List<RailPlacement>,
    selectedPartId: String?,
    zoom: Float,
    onTapDevice: (String) -> Unit,
    onTapEmptyModule: (String, Int) -> Unit
) {
    val unit = (30f * zoom).dp
    val bodyWidth = unit * cabinet.modulesPerRow + 34.dp
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(
            Modifier.width(bodyWidth + 28.dp).padding(4.dp)
                .border(2.dp, Color(0xFF1A1C21), RoundedCornerShape(12.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF373A41), panelBackground, Color(0xFF343842))),
                    RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = Color(0xFF71D99A), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text("ЩИТ · ${cabinet.displayName.uppercase()}", color = Color(0xFFF5F5F5),
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            }
            GraphicalBus("N  ·  НЕЙТРАЛЬ", Color(0xFF65B9F0), bodyWidth - 44.dp)
            repeat(cabinet.rows) { index ->
                val rail = rails.getOrNull(index)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", modifier = Modifier.width(18.dp),
                        color = Color(0xFFADB3BC), style = MaterialTheme.typography.labelSmall)
                    if (rail == null) {
                        Box(
                            Modifier.width(unit * cabinet.modulesPerRow)
                                .height(106.dp)
                                .border(1.dp, Color(0xFF657080), RoundedCornerShape(4.dp))
                                .background(Color(0xFF353941)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("МЕСТО ПОД DIN-РЕЙКУ", color = Color(0xFFADB3BC),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        val loc = placements.filter { it.railId == rail.id }
                        val byStart = loc.associateBy { it.startModule }
                        Column {
                            Row(Modifier.width(unit * rail.capacity).height(14.dp)
                                .background(Color(0xFF161B22), RoundedCornerShape(3.dp)),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically) {
                                repeat(rail.capacity.coerceAtMost(36)) {
                                    Box(Modifier.size(6.dp, 4.dp)
                                        .background(Color(0xFF8794A2), RoundedCornerShape(2.dp)))
                                }
                            }
                            Row(Modifier.height(94.dp)) {
                                var moduleIndex = 0
                                while (moduleIndex < rail.capacity) {
                                    val location = byStart[moduleIndex]
                                    val device = location?.let { partsById[it.partId] }
                                    val width = device?.modulesSnapshot?.coerceIn(1, rail.capacity - moduleIndex) ?: 1
                                    val currentModule = moduleIndex
                                    if (device == null) {
                                        Box(
                                            Modifier.width(unit).fillMaxHeight()
                                                .padding(end = 1.dp)
                                                .border(.5.dp, Color(0xFF616A76))
                                                .background(Color(0xFF2C3440))
                                                .clickable { onTapEmptyModule(rail.id, currentModule) },
                                            contentAlignment = Alignment.BottomCenter
                                        ) {
                                            Text("${currentModule + 1}",
                                                color = Color(0xFFABB3BB),
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                    } else {
                                        DeviceGlyph(
                                            label = device.nameSnapshot, kind = device.productId,
                                            modules = width,
                                            modifier = Modifier.width(unit * width).fillMaxHeight()
                                                .clickable { onTapDevice(device.id) },
                                            highlight = selectedPartId == device.id
                                        )
                                    }
                                    moduleIndex += width
                                }
                            }
                            Row(Modifier.width(unit * rail.capacity).height(13.dp)
                                .background(Color(0xFF151A22), RoundedCornerShape(3.dp)),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically) {
                                repeat(rail.capacity.coerceAtMost(36)) {
                                    Box(Modifier.size(6.dp, 4.dp)
                                        .background(Color(0xFF8794A2), RoundedCornerShape(2.dp)))
                                }
                            }
                        }
                    }
                }
            }
            GraphicalBus("PE  ·  ЗАЗЕМЛЕНИЕ", Color(0xFF8AD48D), bodyWidth - 44.dp)
            Text("${cabinet.rows} ряд. × ${cabinet.modulesPerRow} мод. = ${cabinet.rows * cabinet.modulesPerRow} мод.",
                color = Color(0xFFD0D4DC), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun GraphicalBus(label: String, accentColor: Color, width: androidx.compose.ui.unit.Dp) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color(0xFFBFC5CD), style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.width(width).height(17.dp)) {
            drawRoundRect(color = Color(0xFF687380), cornerRadius = CornerRadius(5f))
            val count = 11
            repeat(count) { index ->
                val x = 12f + index * (size.width - 24f) / (count - 1)
                drawCircle(color = accentColor, radius = 3f, center = Offset(x, size.height / 2))
                drawCircle(color = Color(0xFF353B44), radius = 1.2f, center = Offset(x, size.height / 2))
            }
        }
    }
}

/** Vector apparatus facades: screws, markings, switch lever, test/button details. */
@Composable
private fun DeviceGlyph(
    label: String,
    kind: String,
    modules: Int,
    modifier: Modifier,
    highlight: Boolean = false
) {
    val type = when {
        kind.startsWith("d") || label.contains("АВДТ", ignoreCase = true) -> "АВДТ"
        kind.startsWith("r") || label.contains("УЗО", ignoreCase = true) -> "УЗО"
        kind.startsWith("v") || label.contains("Реле", ignoreCase = true) -> "РЕЛЕ"
        kind.startsWith("spd") || label.contains("УЗИП", ignoreCase = true) -> "УЗИП"
        kind.startsWith("a") || label.contains("Автомат", ignoreCase = true) -> "АВ"
        else -> "ЭЛ."
    }
    val mark = Regex("(?:[BCД]\\s?\\d{1,3}|\\d{1,3}А|\\d{1,3}мА)",
        RegexOption.IGNORE_CASE).find(label)?.value ?: type
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val shadow = if (highlight) electricRed else Color(0xFF222833)
        drawRoundRect(color = shadow, topLeft = Offset(0f, 2f),
            size = Size(w, h - 2f), cornerRadius = CornerRadius(4f))
        drawRoundRect(brush = Brush.verticalGradient(listOf(Color(0xFFF6F7FA), deviceWhite, Color(0xFFD1D4D9))),
            topLeft = Offset(1.5f, 1.5f), size = Size(w - 3f, h - 3f), cornerRadius = CornerRadius(3f))
        val poles = modules.coerceIn(1, 6)
        for (p in 0 until poles) {
            val x = (p + .5f) * w / poles
            drawCircle(Color(0xFF77808C), radius = (w / poles * .12f).coerceIn(2f, 4f),
                center = Offset(x, h * .09f))
            drawCircle(Color(0xFF77808C), radius = (w / poles * .12f).coerceIn(2f, 4f),
                center = Offset(x, h * .91f))
        }
        drawRoundRect(color = electricRed, topLeft = Offset(2f, h * .25f),
            size = Size(w - 4f, h * .045f))
        drawRoundRect(color = Color(0xFF9FA7B2), topLeft = Offset(w * .13f, h * .42f),
            size = Size(w * .74f, h * .23f), cornerRadius = CornerRadius(4f))
        drawRoundRect(color = Color(0xFF273244), topLeft = Offset(w * .36f, h * .42f),
            size = Size(w * .28f, h * .18f), cornerRadius = CornerRadius(3f))
        if (type == "УЗО" || type == "АВДТ") {
            drawCircle(Color(0xFF6A9EBD), radius = (w * .065f).coerceIn(2f, 7f),
                center = Offset(w * .78f, h * .31f))
        }
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(40, 44, 51)
            textAlign = AndroidPaint.Align.CENTER
            textSize = (w / (if (modules > 1) 4.8f else 2.5f)).coerceIn(7f, 16f)
            isFakeBoldText = true
        }
        drawContext.canvas.nativeCanvas.drawText(type, w / 2f, h * .21f, paint)
        paint.textSize = (w / 4.8f).coerceIn(6f, 13f)
        drawContext.canvas.nativeCanvas.drawText(mark.take(8), w / 2f, h * .75f, paint)
        if (highlight) {
            drawRoundRect(color = electricRed, style = androidx.compose.ui.graphics.drawscope.Stroke(3.5f),
                cornerRadius = CornerRadius(4f))
        }
    }
}
