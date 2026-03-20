package com.example.phomemoprint

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.roundToInt

private const val PRINTER_DPI = 203
private const val PRINTER_WIDTH_MM = 48f
private val PRINTER_WIDTH_PX = mmToPx(PRINTER_WIDTH_MM, PRINTER_DPI)
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

enum class ItemType { TEXT, CLIPART, QR, IMAGE }

data class LabelObject(
    val id: Int,
    val type: ItemType,
    var x: Float,
    var y: Float,
    var width: Float,
    var height: Float,
    var rotation: Float = 0f,
    var text: String = "Text",
    var font: String = "Sans",
    var clipart: String = "★",
    var imageUri: String = "",
)

private enum class ResizeHandle {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                LabelEditorScreen(
                    onPrint = ::printNatively,
                    onSaveNative = ::saveNative,
                    onExportPng = ::exportPng,
                    onExportSvg = ::exportSvg,
                )
            }
        }
    }

    private fun ensurePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val needed = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) return true
        permissionLauncher.launch(needed.toTypedArray())
        Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
        return false
    }

    @Suppress("MissingPermission")
    private fun printNatively(lengthMm: Float, objects: List<LabelObject>) {
        if (!ensurePermissions()) return

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Toast.makeText(this, getString(R.string.bluetooth_not_supported), Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, getString(R.string.enable_bluetooth), Toast.LENGTH_LONG).show()
            return
        }
        val printer = adapter.bondedDevices.firstOrNull { (it.name ?: "").contains("T02", true) || (it.name ?: "").contains("PHOMEMO", true) }
        if (printer == null) {
            Toast.makeText(this, getString(R.string.no_printer_found), Toast.LENGTH_LONG).show()
            return
        }

        val bitmap = renderLabelBitmap(lengthMm, objects)
        Thread {
            runCatching {
                adapter.cancelDiscovery()
                printer.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
                    socket.connect()
                    socket.outputStream.use { out ->
                        out.write(byteArrayOf(0x1B, 0x40))
                        escPosRasterBands(bitmap, 48).forEach { band -> writeChunked(out, band, 256, 8) }
                        out.write(byteArrayOf(0x1B, 0x64, 0x05))
                        out.flush()
                    }
                }
            }.onSuccess {
                runOnUiThread { Toast.makeText(this, getString(R.string.print_sent, printer.name ?: printer.address), Toast.LENGTH_LONG).show() }
            }.onFailure {
                runOnUiThread { Toast.makeText(this, getString(R.string.print_failed, it.message ?: "error"), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun saveNative(lengthMm: Float, objects: List<LabelObject>) {
        val file = File(getExternalFilesDir(null), "label_${System.currentTimeMillis()}.phlabel")
        file.writeText(buildJson(lengthMm, objects))
        Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun exportPng(lengthMm: Float, objects: List<LabelObject>) {
        val file = File(getExternalFilesDir(null), "label_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { renderLabelBitmap(lengthMm, objects).compress(Bitmap.CompressFormat.PNG, 100, it) }
        Toast.makeText(this, "PNG: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun exportSvg(lengthMm: Float, objects: List<LabelObject>) {
        val file = File(getExternalFilesDir(null), "label_${System.currentTimeMillis()}.svg")
        file.writeText(buildSvg(lengthMm, objects))
        Toast.makeText(this, "SVG: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun LabelEditorScreen(
    onPrint: (Float, List<LabelObject>) -> Unit,
    onSaveNative: (Float, List<LabelObject>) -> Unit,
    onExportPng: (Float, List<LabelObject>) -> Unit,
    onExportSvg: (Float, List<LabelObject>) -> Unit,
) {
    var lengthMm by remember { mutableFloatStateOf(50f) }
    val objects = remember { mutableStateListOf<LabelObject>() }
    var selectedId by remember { mutableIntStateOf(-1) }
    var nextId by remember { mutableIntStateOf(1) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var revision by remember { mutableIntStateOf(0) }
    var resizeMode by remember { mutableStateOf(false) }
    val fontOptions = remember {
        listOf(
            "sans-serif",
            "serif",
            "monospace",
            "sans-serif-condensed",
            "sans-serif-light",
            "sans-serif-medium",
            "sans-serif-smallcaps",
            "casual",
            "cursive",
        )
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            objects += LabelObject(nextId++, ItemType.IMAGE, 30f, 30f, 140f, 100f, imageUri = uri.toString())
        }
    }

    val transformState = rememberTransformableState { z, _, _ -> zoom = (zoom * z).coerceIn(0.5f, 4f) }
    val selected = objects.firstOrNull { it.id == selectedId }
    val touch = { revision++ }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(stringResource(R.string.editor_title))
        Text(stringResource(R.string.label_length_mm, lengthMm.roundToInt()))
        Slider(value = lengthMm, onValueChange = { lengthMm = it }, valueRange = 20f..120f)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { objects += LabelObject(nextId++, ItemType.TEXT, 20f, 20f, 160f, 60f, text = "Hello") }) { Text("+Text") }
            Button(onClick = { objects += LabelObject(nextId++, ItemType.CLIPART, 30f, 30f, 80f, 80f, clipart = "★") }) { Text("+Clipart") }
            Button(onClick = { objects += LabelObject(nextId++, ItemType.QR, 20f, 20f, 120f, 120f, text = "https://phomemo.com") }) { Text("+QR") }
            Button(onClick = { imagePicker.launch("image/*") }) { Text("+Image") }
            Button(onClick = { resizeMode = !resizeMode }) { Text(if (resizeMode) "Done Resize" else "Resize Mode") }
        }

        Spacer(Modifier.height(8.dp))

        val editorWidthDp = 320.dp
        val lengthPx = mmToPx(lengthMm, PRINTER_DPI)
        val editorHeightDp = with(LocalDensity.current) { (editorWidthDp.toPx() * lengthPx / PRINTER_WIDTH_PX).toDp() }
        val docToUi = with(LocalDensity.current) { editorWidthDp.toPx() / PRINTER_WIDTH_PX }

        Box(
            modifier = Modifier
                .width(editorWidthDp)
                .height(editorHeightDp)
                .border(1.dp, ComposeColor.Black)
                .background(ComposeColor.White)
                .graphicsLayer(scaleX = zoom, scaleY = zoom)
                .transformable(transformState),
        ) {
            objects.forEach { item ->
                LabelObjectView(
                    item = item,
                    docToUi = docToUi,
                    selected = item.id == selectedId,
                    resizeMode = resizeMode,
                    onSelect = { selectedId = item.id },
                    onDrag = { dx, dy ->
                        item.x = (item.x + dx / docToUi).coerceIn(0f, PRINTER_WIDTH_PX - item.width)
                        item.y = (item.y + dy / docToUi).coerceIn(0f, lengthPx - item.height)
                        touch()
                    },
                    onResize = { handle, dx, dy ->
                        val aspect = (item.width / item.height).coerceAtLeast(0.1f)
                        val d = (maxOf(dx, dy) / docToUi)
                        when (handle) {
                            ResizeHandle.BOTTOM_RIGHT -> {
                                val newWidth = (item.width + d).coerceIn(20f, PRINTER_WIDTH_PX - item.x)
                                item.width = newWidth
                                item.height = (newWidth / aspect).coerceIn(20f, lengthPx - item.y)
                            }

                            ResizeHandle.TOP_LEFT -> {
                                val newWidth = (item.width - d).coerceAtLeast(20f)
                                val newHeight = (newWidth / aspect).coerceAtLeast(20f)
                                val newX = (item.x + item.width - newWidth).coerceAtLeast(0f)
                                val newY = (item.y + item.height - newHeight).coerceAtLeast(0f)
                                item.width = newWidth
                                item.height = newHeight
                                item.x = newX
                                item.y = newY
                            }

                            ResizeHandle.TOP_RIGHT -> {
                                val newWidth = (item.width + d).coerceIn(20f, PRINTER_WIDTH_PX - item.x)
                                val newHeight = (newWidth / aspect).coerceAtLeast(20f)
                                item.width = newWidth
                                item.y = (item.y + item.height - newHeight).coerceAtLeast(0f)
                                item.height = newHeight
                            }

                            ResizeHandle.BOTTOM_LEFT -> {
                                val newWidth = (item.width - d).coerceAtLeast(20f)
                                val newHeight = (newWidth / aspect).coerceAtLeast(20f)
                                item.x = (item.x + item.width - newWidth).coerceAtLeast(0f)
                                item.width = newWidth
                                item.height = newHeight
                            }
                        }
                        touch()
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        if (selected != null) {
            Text("rev:$revision")
            Text("Selected: ${selected.type}")
            Slider(value = selected.width, onValueChange = { selected.width = it; touch() }, valueRange = 20f..PRINTER_WIDTH_PX.toFloat())
            Slider(value = selected.height, onValueChange = { selected.height = it; touch() }, valueRange = 20f..lengthPx.toFloat())
            Slider(value = selected.rotation, onValueChange = { selected.rotation = it; touch() }, valueRange = -180f..180f)
            if (selected.type == ItemType.TEXT) {
                OutlinedTextField(value = selected.text, onValueChange = { selected.text = it; touch() }, label = { Text("Text") })
                LazyRow {
                    items(fontOptions) { f ->
                        Button(onClick = { selected.font = f; touch() }, modifier = Modifier.padding(end = 6.dp)) { Text(f) }
                    }
                }
            }
            if (selected.type == ItemType.CLIPART) {
                Row {
                    listOf("★", "❤", "✔", "⚑").forEach { c ->
                        Button(onClick = { selected.clipart = c; touch() }, modifier = Modifier.padding(end = 6.dp)) { Text(c) }
                    }
                }
            }
            if (selected.type == ItemType.QR) {
                OutlinedTextField(value = selected.text, onValueChange = { selected.text = it; touch() }, label = { Text("QR payload") })
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSaveNative(lengthMm, objects.toList()) }) { Text("Save") }
            Button(onClick = { onExportPng(lengthMm, objects.toList()) }) { Text("Export PNG") }
            Button(onClick = { onExportSvg(lengthMm, objects.toList()) }) { Text("Export SVG") }
            Button(onClick = { onPrint(lengthMm, objects.toList()) }) { Text("Print") }
        }

        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(objects, key = { it.id }) { item ->
                Text("#${item.id} ${item.type} x=${item.x.roundToInt()} y=${item.y.roundToInt()}")
            }
        }
    }
}

@Composable
private fun LabelObjectView(
    item: LabelObject,
    docToUi: Float,
    selected: Boolean,
    resizeMode: Boolean,
    onSelect: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onResize: (ResizeHandle, Float, Float) -> Unit,
) {
    val xDp = with(LocalDensity.current) { (item.x * docToUi).toDp() }
    val yDp = with(LocalDensity.current) { (item.y * docToUi).toDp() }
    val wDp = with(LocalDensity.current) { (item.width * docToUi).toDp() }
    val hDp = with(LocalDensity.current) { (item.height * docToUi).toDp() }

    Box(
        modifier = Modifier
            .offset { IntOffset(xDp.roundToPx(), yDp.roundToPx()) }
            .size(wDp, hDp)
            .graphicsLayer(rotationZ = item.rotation)
            .border(if (selected) 2.dp else 1.dp, if (selected) ComposeColor.Red else ComposeColor.Gray)
            .pointerInput(item.id) {
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, drag ->
                        change.consume()
                        onDrag(drag.x, drag.y)
                    },
                )
            }
            .pointerInput(item.id, selected) {
                detectTapGestures {
                    onSelect()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (item.type) {
            ItemType.TEXT -> Text(
                item.text,
                modifier = Modifier.fillMaxSize().padding(vertical = 5.dp, horizontal = 2.dp),
                fontFamily = when (item.font.lowercase()) {
                    "serif" -> FontFamily.Serif
                    "monospace" -> FontFamily.Monospace
                    "cursive" -> FontFamily.Cursive
                    else -> FontFamily.SansSerif
                },
            )

            ItemType.CLIPART -> Text(item.clipart)
            ItemType.QR -> QrPreview(item.text)
            ItemType.IMAGE -> Text("IMG")
        }

        if (selected && resizeMode) {
            ResizeHandleDot(Modifier.align(Alignment.TopStart), onDrag = { dx, dy -> onResize(ResizeHandle.TOP_LEFT, dx, dy) })
            ResizeHandleDot(Modifier.align(Alignment.TopEnd), onDrag = { dx, dy -> onResize(ResizeHandle.TOP_RIGHT, dx, dy) })
            ResizeHandleDot(Modifier.align(Alignment.BottomStart), onDrag = { dx, dy -> onResize(ResizeHandle.BOTTOM_LEFT, dx, dy) })
            ResizeHandleDot(Modifier.align(Alignment.BottomEnd), onDrag = { dx, dy -> onResize(ResizeHandle.BOTTOM_RIGHT, dx, dy) })
        }
    }
}

@Composable
private fun ResizeHandleDot(
    modifier: Modifier = Modifier,
    onDrag: (Float, Float) -> Unit,
) {
    Box(
        modifier = modifier
            .size(12.dp)
            .background(ComposeColor.Blue)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
    )
}

@Composable
private fun QrPreview(data: String) {
    val matrix = remember(data) {
        runCatching { MultiFormatWriter().encode(data, BarcodeFormat.QR_CODE, 64, 64) }.getOrNull()
    }
    Canvas(Modifier.fillMaxSize().padding(4.dp)) {
        if (matrix == null) return@Canvas
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = ComposeColor.Black,
                        topLeft = Offset(x * cell, y * cell),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                    )
                }
            }
        }
    }
}

private fun renderLabelBitmap(lengthMm: Float, objects: List<LabelObject>): Bitmap {
    val width = PRINTER_WIDTH_PX
    val height = mmToPx(lengthMm, PRINTER_DPI)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)

    objects.forEach { obj ->
        canvas.save()
        canvas.rotate(obj.rotation, obj.x + obj.width / 2f, obj.y + obj.height / 2f)
        when (obj.type) {
            ItemType.TEXT -> {
                val paint = Paint().apply {
                    color = Color.BLACK
                    textSize = fitTextSizeForBox(this, obj.text, obj.width, obj.height - 10f)
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.create(obj.font, android.graphics.Typeface.NORMAL)
                }
                val metrics = paint.fontMetrics
                val baseline = obj.y + (obj.height - 10f - (metrics.bottom - metrics.top)) / 2f - metrics.top + 5f
                canvas.drawText(obj.text, obj.x, baseline, paint)
            }

            ItemType.CLIPART -> {
                val paint = Paint().apply { color = Color.BLACK; textSize = obj.height * 0.9f; isAntiAlias = true }
                canvas.drawText(obj.clipart, obj.x, obj.y + obj.height * 0.8f, paint)
            }

            ItemType.QR -> {
                val matrix = runCatching { MultiFormatWriter().encode(obj.text, BarcodeFormat.QR_CODE, obj.width.toInt(), obj.height.toInt()) }.getOrNull()
                if (matrix != null) {
                    val p = Paint().apply { color = Color.BLACK }
                    for (y in 0 until matrix.height) {
                        for (x in 0 until matrix.width) {
                            if (matrix.get(x, y)) canvas.drawRect(obj.x + x, obj.y + y, obj.x + x + 1, obj.y + y + 1, p)
                        }
                    }
                }
            }

            ItemType.IMAGE -> {
                val p = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
                canvas.drawRect(obj.x, obj.y, obj.x + obj.width, obj.y + obj.height, p)
                canvas.drawLine(obj.x, obj.y, obj.x + obj.width, obj.y + obj.height, p)
                canvas.drawLine(obj.x + obj.width, obj.y, obj.x, obj.y + obj.height, p)
            }
        }
        canvas.restore()
    }
    return bitmap
}

private fun buildJson(lengthMm: Float, objects: List<LabelObject>): String {
    val items = objects.joinToString(prefix = "[", postfix = "]") { o ->
        """{"id":${o.id},"type":"${o.type}","x":${o.x},"y":${o.y},"w":${o.width},"h":${o.height},"rot":${o.rotation},"text":"${o.text.replace("\"", "'")}","font":"${o.font}","clip":"${o.clipart}","img":"${o.imageUri}"}"""
    }
    return """{"lengthMm":$lengthMm,"widthPx":$PRINTER_WIDTH_PX,"dpi":$PRINTER_DPI,"items":$items}"""
}

private fun buildSvg(lengthMm: Float, objects: List<LabelObject>): String {
    val width = PRINTER_WIDTH_PX
    val height = mmToPx(lengthMm, PRINTER_DPI)
    val body = buildString {
        objects.forEach { o ->
            when (o.type) {
                ItemType.TEXT -> append("<text x='${o.x}' y='${o.y + o.height}' font-size='${o.height * 0.7f}' transform='rotate(${o.rotation} ${o.x + o.width / 2} ${o.y + o.height / 2})'>${o.text}</text>")
                ItemType.CLIPART -> append("<text x='${o.x}' y='${o.y + o.height}' font-size='${o.height}' transform='rotate(${o.rotation} ${o.x + o.width / 2} ${o.y + o.height / 2})'>${o.clipart}</text>")
                ItemType.QR -> append("<rect x='${o.x}' y='${o.y}' width='${o.width}' height='${o.height}' stroke='black' fill='none'/><text x='${o.x + 2}' y='${o.y + 12}' font-size='10'>QR</text>")
                ItemType.IMAGE -> append("<rect x='${o.x}' y='${o.y}' width='${o.width}' height='${o.height}' stroke='black' fill='none'/>")
            }
        }
    }
    return """<svg xmlns='http://www.w3.org/2000/svg' width='$width' height='$height' viewBox='0 0 $width $height'><rect width='100%' height='100%' fill='white'/>$body</svg>"""
}

private fun mmToPx(mm: Float, dpi: Int): Int = ((mm / 25.4f) * dpi).roundToInt()

private fun fitTextSizeForBox(paint: Paint, text: String, width: Float, height: Float): Float {
    var low = 4f
    var high = 512f
    repeat(12) {
        val mid = (low + high) / 2f
        paint.textSize = mid
        val textWidth = paint.measureText(text)
        val fm = paint.fontMetrics
        val textHeight = fm.bottom - fm.top
        if (textWidth <= width && textHeight <= height) {
            low = mid
        } else {
            high = mid
        }
    }
    return low
}

private fun escPosRasterBands(bitmap: Bitmap, bandHeight: Int = 48): List<ByteArray> {
    val width = bitmap.width
    val height = bitmap.height
    val widthBytes = (width + 7) / 8
    val bands = mutableListOf<ByteArray>()
    var y = 0

    while (y < height) {
        val h = minOf(bandHeight, height - y)
        val mono = ByteArray(widthBytes * h)
        var i = 0
        for (row in 0 until h) {
            val yy = y + row
            for (xb in 0 until widthBytes) {
                var b = 0
                for (bit in 0 until 8) {
                    val x = xb * 8 + bit
                    if (x >= width) continue
                    val pixel = bitmap.getPixel(x, yy)
                    val lum = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
                    if (lum < 180) b = b or (1 shl (7 - bit))
                }
                mono[i++] = b.toByte()
            }
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
        out.write(byteArrayOf((widthBytes and 0xFF).toByte(), ((widthBytes shr 8) and 0xFF).toByte()))
        out.write(byteArrayOf((h and 0xFF).toByte(), ((h shr 8) and 0xFF).toByte()))
        out.write(mono)
        bands += out.toByteArray()
        y += h
    }
    return bands
}

private fun writeChunked(output: OutputStream, bytes: ByteArray, chunkSize: Int, delayMs: Long) {
    var offset = 0
    while (offset < bytes.size) {
        val end = minOf(offset + chunkSize, bytes.size)
        output.write(bytes, offset, end - offset)
        output.flush()
        offset = end
        if (delayMs > 0) Thread.sleep(delayMs)
    }
}
