package com.example.phomemoprint

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

private const val PHOMEMO_PACKAGE = "com.quyin.phomemo"
private const val TARGET_DPI = 200
private const val LABEL_WIDTH_MM = 50f
private const val LABEL_HEIGHT_MM = 30f
private val LABEL_WIDTH_PX = mmToPx(LABEL_WIDTH_MM, TARGET_DPI)
private val LABEL_HEIGHT_PX = mmToPx(LABEL_HEIGHT_MM, TARGET_DPI)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                PrintScreen()
            }
        }
    }

    private fun printViaPhomemoApp() {
        val imageUri = writeLabelToCache(createLabelBitmap())

        val baseSendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val packageManager = packageManager
        val directPhomemoIntent = baseSendIntent.cloneFilter().apply {
            setPackage(PHOMEMO_PACKAGE)
            putExtra(Intent.EXTRA_STREAM, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            if (directPhomemoIntent.resolveActivity(packageManager) != null) {
                startActivity(directPhomemoIntent)
            } else {
                startActivity(Intent.createChooser(baseSendIntent, getString(R.string.share_chooser_title)))
            }
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_share_target_found), Toast.LENGTH_LONG).show()
        }
    }

    private fun writeLabelToCache(bitmap: Bitmap): Uri {
        val outDir = File(cacheDir, "labels").apply { mkdirs() }
        val outFile = File(outDir, "label_50x30_200dpi.png")

        FileOutputStream(outFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }

        return FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            outFile,
        )
    }

    @Composable
    private fun PrintScreen() {
        val context = LocalContext.current

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

            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = {
                if (context is MainActivity) {
                    context.printViaPhomemoApp()
                }
            }) {
                Text(stringResource(R.string.print_button_text))
            }
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
