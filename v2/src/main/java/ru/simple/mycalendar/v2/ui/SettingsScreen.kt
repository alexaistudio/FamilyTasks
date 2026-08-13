package ru.simple.mycalendar.v2.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.simple.mycalendar.v2.V2ViewModel
import ru.simple.mycalendar.v2.BuildConfig
import ru.simple.mycalendar.v2.SyncLog
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.simple.mycalendar.v2.peer.BluetoothPeerState
import ru.simple.mycalendar.v2.peer.BluetoothSyncService
import ru.simple.mycalendar.v2.data.resolvedName
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import ru.simple.mycalendar.v2.update.AppUpdater
import ru.simple.mycalendar.v2.update.UpdateInstallActivity
import ru.simple.mycalendar.v2.update.UpdateResult
import ru.simple.mycalendar.v2.update.UpdateProgress

private val syncIntervals = listOf(5, 15, 30, 60, 180, 360, 720, 1440)

@Composable
fun SettingsScreen(model: V2ViewModel) {
    val context = LocalContext.current
    val updateScope = rememberCoroutineScope()
    val updater = remember(context) { AppUpdater(context) }
    val state = model.syncState
    val profiles by model.profiles.collectAsStateWithLifecycle()
    val currentProfile = profiles.firstOrNull { it.id == model.currentUserId }
    var server by rememberSaveable(state.info.serverUrl) { mutableStateOf(state.info.serverUrl) }
    var certificateSha256 by rememberSaveable(state.info.certificateSha256) {
        mutableStateOf(state.info.certificateSha256)
    }
    var invite by rememberSaveable { mutableStateOf("") }
    var importedKey by rememberSaveable { mutableStateOf("") }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(true) }
    var qrMessage by rememberSaveable { mutableStateOf("") }
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    var showManualKey by rememberSaveable { mutableStateOf(false) }
    var profileName by rememberSaveable(currentProfile?.displayName) {
        mutableStateOf(currentProfile?.displayName.orEmpty())
    }
    var bluetoothStatus by remember { mutableStateOf(BluetoothPeerState.status(context)) }
    var updateWorking by rememberSaveable { mutableStateOf(false) }
    var updateMessage by rememberSaveable { mutableStateOf("") }
    var updateProgressBar by rememberSaveable { mutableStateOf("") }
    var updateReady by rememberSaveable { mutableStateOf(false) }
    var syncIntervalPosition by rememberSaveable(model.syncIntervalMinutes) {
        mutableFloatStateOf(syncIntervals.indexOf(model.syncIntervalMinutes).coerceAtLeast(0).toFloat())
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents.orEmpty()
        if (content.startsWith(RECOVERY_QR_PREFIX)) {
            importedKey = content.removePrefix(RECOVERY_QR_PREFIX)
            model.importRecoveryCode(importedKey)
            qrMessage = model.syncState.message
        } else if (content.isNotBlank()) {
            qrMessage = "Это не QR приложения FamilyTasks."
        }
    }
    val bluetoothPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasBluetoothPermissions(context)) {
            model.updateBluetoothSyncEnabled(true)
            qrMessage = "Разрешение получено. Теперь включите связь и на втором телефоне."
        } else {
            qrMessage = "Без разрешения «Устройства поблизости» приложения не смогут найти друг друга."
        }
    }
    val logSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(SyncLog.read(context).toByteArray())
                }
            }
        }
    }
    LaunchedEffect(model.bluetoothSyncEnabled) {
        while (true) {
            bluetoothStatus = BluetoothPeerState.status(context)
            delay(1_000L)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SettingsCard("Вид календаря") {
            Text("Размер текста дел: ${formatFontSize(model.calendarTaskFontSp)}")
            Slider(
                value = model.calendarTaskFontSp,
                onValueChange = { model.updateCalendarTaskFontSp((it * 2f).roundToInt() / 2f) },
                valueRange = 6f..10f,
                steps = 7
            )
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = .7f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "Пример: ★ Купить продукты",
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    fontSize = androidx.compose.ui.unit.TextUnit(
                        model.calendarTaskFontSp,
                        androidx.compose.ui.unit.TextUnitType.Sp
                    )
                )
            }
        }

        val visibleProfiles = (profiles + ru.simple.mycalendar.v2.data.UserProfileEntity(model.currentUserId))
            .distinctBy { it.id }
        SettingsCard("Участники семьи") {
            Text(
                "Ваш внутренний ID не меняется при переименовании и используется для адресных уведомлений.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it.take(60) },
                label = { Text("Ваше имя") },
                placeholder = { Text(currentProfile?.resolvedName(visibleProfiles) ?: "Пользователь 1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { model.renameCurrentUser(profileName) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить имя") }
            visibleProfiles.sortedWith(compareBy({ it.createdAt }, { it.id })).forEach { profile ->
                Text(
                    "• ${profile.resolvedName(visibleProfiles)}${if (profile.id == model.currentUserId) " — это вы" else ""}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Text("Подключения и синхронизация", style = MaterialTheme.typography.titleLarge)

        SettingsCard("PHP-сервер") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Фоновый обмен")
                    Text(
                        "Ручная синхронизация работает и при выключенном фоне",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = model.serverBackgroundSyncEnabled,
                    onCheckedChange = model::updateServerBackgroundSyncEnabled
                )
            }
            val selectedInterval = syncIntervals[
                syncIntervalPosition.roundToInt().coerceIn(syncIntervals.indices)
            ]
            Text("Проверять сервер: ${formatInterval(selectedInterval)}")
            Slider(
                value = syncIntervalPosition,
                onValueChange = { syncIntervalPosition = it },
                onValueChangeFinished = { model.updateSyncIntervalMinutes(selectedInterval) },
                valueRange = 0f..syncIntervals.lastIndex.toFloat(),
                steps = syncIntervals.size - 2
            )
            Text(
                "Изменения отправляются сразу; без изменений приложение запрашивает только новые пакеты. Android может отложить фоновый запуск во сне.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Сервер видит размер и время пакетов. Содержимое сжимается и шифруется на телефоне AES-256-GCM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = { showHelp = !showHelp }) {
                Text(if (showHelp) "Скрыть инструкцию" else "Как подключить PHP-сервер?")
            }
            if (showHelp) SyncHelp()

            if (!state.info.configured) {
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("HTTP/HTTPS-адрес sync.php") },
                    placeholder = { Text("http://192.168.1.10/sync.php") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (server.trim().startsWith("http://", ignoreCase = true) ||
                    (server.isNotBlank() && "://" !in server)
                ) {
                    Text(
                        "HTTP не раскрывает дела, но перехваченный токен позволяет мешать синхронизации. Используйте доверенную сеть.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedTextField(
                    value = certificateSha256,
                    onValueChange = { certificateSha256 = it },
                    label = { Text("SHA-256 сертификата для самоподписанного HTTPS") },
                    placeholder = { Text("AA:BB:… (необязательно)") },
                    supportingText = { Text("Разрешает защищённый HTTPS по IP с точным сертификатом.") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = invite,
                    onValueChange = { invite = it },
                    label = { Text("Одноразовый код из админки sync.php") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { model.connectSync(server, invite, certificateSha256) },
                    enabled = !state.working && server.isNotBlank() && invite.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Подключить этот телефон") }
            } else {
                Text("Подключено", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text("Сервер: ${state.info.serverUrl}")
                if (state.info.certificateSha256.isNotEmpty()) {
                    Text(
                        "Сертификат закреплён: ${state.info.certificateSha256.chunked(2).joinToString(":")}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text("Аккаунт: ${state.info.accountId}", style = MaterialTheme.typography.bodySmall)
                Text("Серверная позиция: ${state.info.lastSeq}")
                Button(
                    onClick = model::syncNow,
                    enabled = !state.working,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Синхронизировать сейчас") }
                TextButton(onClick = { confirmDisconnect = true }, modifier = Modifier.align(Alignment.End)) {
                    Text("Отключить PHP-сервер")
                }
            }
            if (state.working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                    Text(state.message)
                }
            } else if (state.message.isNotBlank()) {
                Text(state.message, color = MaterialTheme.colorScheme.primary)
            }
        }

        SettingsCard("Bluetooth между приложениями") {
            Text(
                "Самостоятельный обмен без сервера, системного сопряжения, PIN и доступа к другим данным телефона. Сначала сравниваются только защищённые отпечатки; зашифрованные дела передаются лишь при различии.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "QR переносит только общий ключ семьи. Он не делает телефон «первым» и не копирует пользователя: у каждого устройства остаётся собственный постоянный ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { showKey = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Показать семейный QR")
            }
            OutlinedButton(
                onClick = {
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Наведите камеру на семейный QR другого телефона")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Добавить этот телефон по QR") }
            TextButton(onClick = { showManualKey = !showManualKey }) {
                Text(if (showManualKey) "Скрыть ручной ввод" else "Ввести recovery-ключ вручную")
            }
            if (showManualKey) {
                OutlinedTextField(
                    value = importedKey,
                    onValueChange = { importedKey = it },
                    label = { Text("Recovery-код") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        model.importRecoveryCode(importedKey)
                        qrMessage = model.syncState.message
                    },
                    enabled = importedKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Принять ключ") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Фоновая Bluetooth-связь")
                    Text(
                        if (model.bluetoothSyncEnabled) "Включена" else "Выключена",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = model.bluetoothSyncEnabled,
                    onCheckedChange = { enabled ->
                        when {
                            !enabled -> model.updateBluetoothSyncEnabled(false)
                            Build.VERSION.SDK_INT < 29 -> {
                                qrMessage = "Связь без системного сопряжения требует Android 10 или новее."
                            }
                            hasBluetoothPermissions(context) -> model.updateBluetoothSyncEnabled(true)
                            else -> bluetoothPermissions.launch(requiredBluetoothPermissions())
                        }
                    }
                )
            }
            Text(
                bluetoothStatus.message,
                color = if (bluetoothStatus.lastSuccessfulSync > 0L) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            if (bluetoothStatus.lastSuccessfulSync > 0L) {
                Text(
                    "Последний защищённый обмен: ${DateFormat.getDateTimeInstance().format(Date(bluetoothStatus.lastSuccessfulSync))}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(
                onClick = {
                    val channelIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(
                            Settings.EXTRA_CHANNEL_ID,
                            BluetoothSyncService.NOTIFICATION_CHANNEL
                        )
                    runCatching { context.startActivity(channelIntent) }
                        .onFailure {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Скрыть или настроить служебное уведомление") }
            Text(
                "Постоянная Bluetooth-связь требует служебного уведомления Android. Здесь его можно сделать беззвучным или скрыть средствами системы; выключатель выше полностью остановит фоновый обмен.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (qrMessage.isNotBlank()) Text(qrMessage, color = MaterialTheme.colorScheme.primary)
            Text(
                "Android 10+. Включите на всех телефонах семьи — при встрече они сами разобьются на пары. Новые устройства включаются автоматически; если настроенный PHP-сервер работает, он получает приоритет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsCard("Уведомления") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Сообщать о новых делах после синхронизации")
                    Text(
                        "Только если дело адресовано вам или всем участникам",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = model.notifyAboutNewTasks, onCheckedChange = model::updateNotifyAboutNewTasks)
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Звуки и разрешения Android") }
        }

        SettingsCard("Обновления приложения") {
            Text(
                "Официальный источник: github.com/alexaistudio/FamilyTasks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Проверять и скачивать автоматически")
                    Text(
                        "Примерно дважды в сутки при наличии сети",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = model.automaticUpdatesEnabled,
                    onCheckedChange = model::updateAutomaticUpdatesEnabled
                )
            }
            Button(
                onClick = {
                    updateWorking = true
                    updateReady = false
                    updateMessage = "Иду в GitHub Releases…"
                    updateProgressBar = ""
                    updateScope.launch {
                        val progressEvents = Channel<UpdateProgress>(Channel.CONFLATED)
                        val progressRenderer = launch {
                            for (progress in progressEvents) {
                                when (progress) {
                                    UpdateProgress.CheckingGitHub -> {
                                        updateMessage = "Иду в GitHub Releases…"
                                        updateProgressBar = ""
                                    }
                                    is UpdateProgress.LatestVersion -> {
                                        updateMessage = "GitHub: последняя версия ${progress.version}."
                                        updateProgressBar = ""
                                    }
                                    is UpdateProgress.UpdateFound -> {
                                        updateMessage = "Обновление найдено: версия ${progress.version}, ${formatFileSize(progress.bytes)}. Начинаю загрузку."
                                        updateProgressBar = AppUpdater.asciiProgress(0L, progress.bytes)
                                    }
                                    is UpdateProgress.Downloading -> {
                                        updateMessage = "Скачиваю версию ${progress.version}: ${formatFileSize(progress.downloaded)} из ${formatFileSize(progress.total)}."
                                        updateProgressBar = AppUpdater.asciiProgress(progress.downloaded, progress.total)
                                    }
                                    is UpdateProgress.Verifying -> {
                                        updateMessage = "APK загружен. Проверяю SHA-256, package ID и подпись…"
                                        updateProgressBar = AppUpdater.asciiProgress(1L, 1L)
                                    }
                                }
                            }
                        }
                        val result = withContext(Dispatchers.IO) {
                            updater.checkAndDownload(progressEvents::trySend)
                        }
                        progressEvents.close()
                        progressRenderer.join()
                        when (result) {
                            is UpdateResult.Ready -> {
                                updateReady = true
                                updateMessage = "Версия ${result.version} скачана и проверена. Можно устанавливать."
                                updateProgressBar = AppUpdater.asciiProgress(1L, 1L)
                            }
                            is UpdateResult.UpToDate -> {
                                updateReady = false
                                updateMessage = "GitHub: последняя версия ${result.version}. У вас уже установлена актуальная ${BuildConfig.VERSION_NAME}."
                                updateProgressBar = ""
                            }
                            is UpdateResult.Error -> {
                                updateReady = false
                                updateMessage = result.message
                                updateProgressBar = ""
                            }
                        }
                        updateWorking = false
                    }
                },
                enabled = !updateWorking,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (updateWorking) "Проверка и загрузка…" else "Проверить обновления") }
            if (updateReady) {
                Button(
                    onClick = { UpdateInstallActivity.launch(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Установить скачанное обновление") }
            }
            if (updateMessage.isNotBlank()) {
                Text(updateMessage, color = MaterialTheme.colorScheme.primary)
            }
            if (updateProgressBar.isNotBlank()) {
                Text(
                    updateProgressBar,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "APK принимается только с тем же package ID, большей версией и той же подписью, что у установленного приложения. Android попросит один раз разрешить установку из FamilyTasks и подтвердить каждое обновление.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "FamilyTasks ${BuildConfig.VERSION_NAME}",
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/alexaistudio"))
                )
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text("Author · GitHub @alexaistudio") }
        TextButton(
            onClick = { logSaver.launch("familytasks-log.txt") },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) { Text("Сохранить журнал связи (log.txt)") }
        Text(
            "Последние 100 событий Bluetooth- и серверной синхронизации: время, тип события, результат. Идентификаторы скрыты вида a1b2c********; названий дел, имён и ключей в журнале нет.",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 24.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Отключить телефон?") },
            text = { Text("Локальные дела останутся. Токен сервера будет удалён с телефона.") },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Отмена") } },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; model.disconnectSync() }) { Text("Отключить") }
            }
        )
    }

    if (showKey) {
        RecoveryQrDialog(model.recoveryCode()) { showKey = false }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .58f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

private fun formatFontSize(value: Float): String = if (value % 1f == 0f) {
    "${value.toInt()} sp"
} else {
    "$value sp"
}

private fun formatInterval(minutes: Int): String = when {
    minutes < 60 -> "каждые $minutes мин."
    minutes == 60 -> "каждый час"
    minutes < 1440 -> "каждые ${minutes / 60} ч."
    else -> "раз в сутки"
}

private fun formatFileSize(bytes: Long): String =
    "%.1f МБ".format(bytes.coerceAtLeast(0L) / (1024.0 * 1024.0))

private fun requiredBluetoothPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 31 -> arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    )
    Build.VERSION.SDK_INT >= 29 -> arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    else -> emptyArray()
}

private fun hasBluetoothPermissions(context: Context): Boolean =
    requiredBluetoothPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun SyncHelp() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Откуда берётся одноразовый код?", fontWeight = FontWeight.Bold)
            Text("1. Загрузите server-v2/sync.php на свой хостинг или домашний сервер.")
            Text("2. Откройте адрес sync.php в обычном браузере и задайте пароль администратора.")
            Text("3. В открывшейся админке нажмите «Создать код на 24 часа». Этот код и HTTP/HTTPS-адрес введите здесь.")
            Text("Для HTTPS с самоподписанным сертификатом укажите его SHA-256-отпечаток. Тогда приложение примет этот точный сертификат даже по IP. При HTTP дела остаются зашифрованы, но приглашение и токен сеть не защищает.")
            Text(
                "Просто ввод адреса в приложении код не создаёт — его выдаёт только администратор сервера. Саморегистрации специально нет.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
