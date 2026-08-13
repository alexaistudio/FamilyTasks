package ru.simple.mycalendar.v2.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

const val RECOVERY_QR_PREFIX = "mycalendar-v2:key:"

@Composable
fun RecoveryQrDialog(code: String, onDismiss: () -> Unit) {
    val bitmap = remember(code) {
        val matrix = QRCodeWriter().encode(RECOVERY_QR_PREFIX + code, BarcodeFormat.QR_CODE, 720, 720)
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565).also { image ->
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                image.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ключ для второго телефона") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Сканируйте этот QR только своим вторым телефоном. Серверу он не отправляется.")
                Image(bitmap.asImageBitmap(), "Recovery QR", Modifier.fillMaxWidth().size(280.dp))
                SelectionContainer { Text(code, style = MaterialTheme.typography.bodySmall) }
                Text("Кто получит этот код, сможет расшифровать серверную копию.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}
