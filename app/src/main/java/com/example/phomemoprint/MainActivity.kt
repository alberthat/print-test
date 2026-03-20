package com.example.phomemoprint

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlin.math.roundToInt

private const val TARGET_DPI = 200
private const val LABEL_WIDTH_MM = 50f
private const val LABEL_HEIGHT_MM = 30f
private val LABEL_WIDTH_PX = mmToPx(LABEL_WIDTH_MM, TARGET_DPI)
private val LABEL_HEIGHT_PX = mmToPx(LABEL_HEIGHT_MM, TARGET_DPI)
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            printNativelyToPhomemo()
        } else {
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                PrintScreen(onNativePrint = { startNativePrintFlow() })
            }
        }
    }

    private fun startNativePrintFlow() {
        val needed = requiredBluetoothPermissions().filterNot(::hasPermission)
        if (needed.isEmpty()) {
            printNativelyToPhomemo()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requiredBluetoothPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            emptyList()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("MissingPermission")
    private fun printNativelyToPhomemo() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, getString(R.string.bluetooth_not_supported), Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, getString(R.string.enable_bluetooth), Toast.LENGTH_LONG).show()
            return
        }

        val printer = findLikelyPhomemoDevice(adapter.bondedDevices)
        if (printer == null) {
            Toast.makeText(this, getString(R.string.no_printer_found), Toast.LENGTH_LONG).show()
            return
        }

        Thread {
            try {
                adapter.cancelDiscovery()
                printer.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
                    socket.connect()
                    socket.outputStream.use { output ->
                        output.write(byteArrayOf(0x1B, 0x40)) // ESC @ initialize

                        val bands = escPosRasterBands(createLabelBitmap(), bandHeight = 48)
                        bands.forEach { band ->
                            writeChunked(output, band, chunkSize = 256, delayMs = 8)
                        }

                        output.write(byteArrayOf(0x1B, 0x64, 0x05)) // feed 5 lines
                        output.flush()
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.print_sent, printer.name ?: printer.address), Toast.LENGTH_LONG).show()
                }
            } catch (error: IOException) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.print_failed, error.message ?: "I/O error"), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun findLikelyPhomemoDevice(bonded: Set<BluetoothDevice>): BluetoothDevice? {
        return bonded.firstOrNull { device ->
            val name = device.name?.uppercase().orEmpty()
            name.contains("PHOMEMO") || name.contains("T02") || name.contains("M02")
        }
    }
}

private fun mmToPx(mm: Float, dpi: Int): Int = ((mm / 25.4f) * dpi).roundToInt()

private fun createLabelBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(LABEL_WIDTH_PX, LABEL_HEIGHT_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    canvas.drawColor(Color.WHITE)

    val borderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    val linePaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    canvas.drawRect(2f, 2f, (LABEL_WIDTH_PX - 2).toFloat(), (LABEL_HEIGHT_PX - 2).toFloat(), borderPaint)
    canvas.drawLine(20f, 20f, (LABEL_WIDTH_PX - 20).toFloat(), (LABEL_HEIGHT_PX - 20).toFloat(), linePaint)
    canvas.drawLine(20f, (LABEL_HEIGHT_PX - 20).toFloat(), (LABEL_WIDTH_PX - 20).toFloat(), 20f, linePaint)
    canvas.drawText("50x30mm @200dpi", LABEL_WIDTH_PX / 2f, LABEL_HEIGHT_PX / 2f, textPaint)

    return bitmap
}

private fun escPosRasterBands(bitmap: Bitmap, bandHeight: Int = 48): List<ByteArray> {
    val width = bitmap.width
    val height = bitmap.height
    val widthBytes = (width + 7) / 8

    val bands = mutableListOf<ByteArray>()
    var y = 0

    while (y < height) {
        val currentBandHeight = minOf(bandHeight, height - y)
        val monochrome = ByteArray(widthBytes * currentBandHeight)
        var index = 0

        for (row in 0 until currentBandHeight) {
            val yPixel = y + row
            for (xByte in 0 until widthBytes) {
                var value = 0
                for (bit in 0 until 8) {
                    val x = xByte * 8 + bit
                    if (x >= width) continue

                    val pixel = bitmap.getPixel(x, yPixel)
                    val luminance = (
                        0.299 * Color.red(pixel) +
                            0.587 * Color.green(pixel) +
                            0.114 * Color.blue(pixel)
                        ).toInt()

                    if (luminance < 180) {
                        value = value or (1 shl (7 - bit))
                    }
                }
                monochrome[index++] = value.toByte()
            }
        }

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
        out.write(byteArrayOf((widthBytes and 0xFF).toByte(), ((widthBytes shr 8) and 0xFF).toByte()))
        out.write(byteArrayOf((currentBandHeight and 0xFF).toByte(), ((currentBandHeight shr 8) and 0xFF).toByte()))
        out.write(monochrome)
        bands += out.toByteArray()

        y += currentBandHeight
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

@Composable
private fun PrintScreen(onNativePrint: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor(0xFFF4F4F4))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.preview_title))
        Spacer(modifier = Modifier.height(16.dp))

        Canvas(
            modifier = Modifier
                .size(width = 300.dp, height = 180.dp)
                .background(ComposeColor.White),
        ) {
            drawRect(
                color = ComposeColor.Black,
                style = Stroke(width = 3f),
            )
            drawLine(
                color = ComposeColor.Black,
                start = Offset(8f, 8f),
                end = Offset(size.width - 8f, size.height - 8f),
                strokeWidth = 3f,
            )
            drawLine(
                color = ComposeColor.Black,
                start = Offset(8f, size.height - 8f),
                end = Offset(size.width - 8f, 8f),
                strokeWidth = 3f,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.output_pixels, LABEL_WIDTH_PX, LABEL_HEIGHT_PX))
        Spacer(modifier = Modifier.height(8.dp))
        Text(stringResource(R.string.native_print_hint))

        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onNativePrint) {
            Text(stringResource(R.string.print_button_native))
        }
    }
}
