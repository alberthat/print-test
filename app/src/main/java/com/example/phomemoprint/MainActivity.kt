package com.example.phomemoprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.unit.dp
import java.io.FileOutputStream
import kotlin.math.roundToInt

private const val TARGET_DPI = 200
private const val LABEL_WIDTH_MM = 50f
private const val LABEL_HEIGHT_MM = 30f
private val LABEL_WIDTH_PX = mmToPx(LABEL_WIDTH_MM, TARGET_DPI)
private val LABEL_HEIGHT_PX = mmToPx(LABEL_HEIGHT_MM, TARGET_DPI)
private val LABEL_WIDTH_MILS = mmToMils(LABEL_WIDTH_MM)
private val LABEL_HEIGHT_MILS = mmToMils(LABEL_HEIGHT_MM)
private val LABEL_WIDTH_POINTS = mmToPdfPoints(LABEL_WIDTH_MM)
private val LABEL_HEIGHT_POINTS = mmToPdfPoints(LABEL_HEIGHT_MM)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                PrintScreen(onPrint = { printLabel() })
            }
        }
    }

    private fun printLabel() {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager

        // Phomemo T02 prints through Android print services/plugins.
        val attributes = PrintAttributes.Builder()
            .setResolution(PrintAttributes.Resolution("label_200dpi", "200dpi", TARGET_DPI, TARGET_DPI))
            .setMediaSize(PrintAttributes.MediaSize("PHOMEMO_50x30", "Phomemo 50x30mm", LABEL_WIDTH_MILS, LABEL_HEIGHT_MILS))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
            .build()

        printManager.print(
            "phomemo_t02_label",
            LabelPrintAdapter(bitmap = createLabelBitmap()),
            attributes,
        )
    }
}

private fun mmToPx(mm: Float, dpi: Int): Int = ((mm / 25.4f) * dpi).roundToInt()
private fun mmToMils(mm: Float): Int = ((mm / 25.4f) * 1000f).roundToInt()
private fun mmToPdfPoints(mm: Float): Int = ((mm / 25.4f) * 72f).roundToInt()

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

private class LabelPrintAdapter(
    private val bitmap: Bitmap,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }

        callback.onLayoutFinished(
            PrintDocumentInfo.Builder("phomemo_t02_label.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(1)
                .build(),
            true,
        )
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(LABEL_WIDTH_POINTS, LABEL_HEIGHT_POINTS, 1).create()
        val page = document.startPage(pageInfo)

        page.canvas.drawColor(Color.WHITE)
        page.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, LABEL_WIDTH_POINTS.toFloat(), LABEL_HEIGHT_POINTS.toFloat()), null)

        document.finishPage(page)

        if (cancellationSignal.isCanceled) {
            document.close()
            callback.onWriteCancelled()
            return
        }

        FileOutputStream(destination.fileDescriptor).use { output ->
            document.writeTo(output)
        }

        document.close()
        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
    }
}

@Composable
private fun PrintScreen(onPrint: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor(0xFFF4F4F4))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Preview: 50mm × 30mm at 200dpi")
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
        Text("Output pixels: ${LABEL_WIDTH_PX} x ${LABEL_HEIGHT_PX}")

        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onPrint) {
            Text("Print to Phomemo T02")
        }
    }
}
