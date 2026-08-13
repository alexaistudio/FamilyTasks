package ru.simple.mycalendar.v2.peer

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ru.simple.mycalendar.v2.MainActivity
import ru.simple.mycalendar.v2.R
import ru.simple.mycalendar.v2.SyncLog
import ru.simple.mycalendar.v2.UiPreferences
import ru.simple.mycalendar.v2.V2App
import ru.simple.mycalendar.v2.notify.ReminderScheduler
import ru.simple.mycalendar.v2.sync.SyncWorker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-only Bluetooth transport. Phones first compare rotating keyed state tags,
 * then a deterministic matching round permits at most one peer per phone.
 * The actual L2CAP stream is mutually authenticated and encrypted by
 * [BluetoothPeerProtocol], without creating an Android Bluetooth bond.
 */
class BluetoothSyncService : Service() {
    private val running = AtomicBoolean(false)
    private val exchangeInProgress = AtomicBoolean(false)
    private val stateDigestDirty = AtomicBoolean(true)
    private val incomingHandlers = Executors.newFixedThreadPool(2)
    private val outgoingHandler = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val seenPeers = ConcurrentHashMap<String, SeenPeer>()
    private val seenLegacyPeers = ConcurrentHashMap<String, SeenLegacyPeer>()
    private val attempts = ConcurrentHashMap<String, AttemptState>()
    private val radioLock = Any()

    @Volatile private var activeServer: BluetoothServerSocket? = null
    @Volatile private var activeAdapter: BluetoothAdapter? = null
    @Volatile private var activePsm: Int = 0
    @Volatile private var localBeacon: BluetoothBeacon? = null
    @Volatile private var localDigest: ByteArray? = null
    @Volatile private var refreshFuture: ScheduledFuture<*>? = null
    @Volatile private var pairingFuture: ScheduledFuture<*>? = null
    @Volatile private var lastOutgoingAttemptElapsed = 0L

    private lateinit var protocol: BluetoothPeerProtocol
    private lateinit var app: V2App
    private lateinit var preferences: UiPreferences
    private lateinit var bluetoothDeviceId: String
    private val legacyPeerId = SecureRandom().nextInt()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            SyncLog.log(this@BluetoothSyncService, "BT: не удалось объявить BLE-маяк (код $errorCode)")
            BluetoothPeerState.markUnavailable(
                this@BluetoothSyncService,
                "Не удалось объявить приложение по Bluetooth (код $errorCode)"
            )
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleScanResult(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            SyncLog.log(this@BluetoothSyncService, "BT: сканер завершился с ошибкой (код $errorCode)")
            BluetoothPeerState.markUnavailable(
                this@BluetoothSyncService,
                "Не удалось искать приложение рядом (код $errorCode)"
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, foregroundNotification())
        app = application as V2App
        preferences = app.uiPreferences
        bluetoothDeviceId = preferences.bluetoothDeviceId()
        protocol = BluetoothPeerProtocol(
            this,
            app.database.tasks(),
            app.syncKeys,
            ReminderScheduler(this),
            preferences,
            onDatabaseChanged = {
                app.localChanges.markChanged()
                app.syncHealth.markLocalChange()
                SyncWorker.scheduleSoon(app)
                requestAdvertisementRefresh()
            }
        )
        SyncLog.log(this, "BT: служба запущена (id ${SyncLog.mask(bluetoothDeviceId)})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < 29) {
            BluetoothPeerState.markUnavailable(this, "Связь без системного сопряжения требует Android 10 или новее")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasBluetoothPermissions()) {
            BluetoothPeerState.markUnavailable(this, "Нет разрешения Android на поиск устройств рядом")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) {
            if (intent?.action == ACTION_SYNC_NOW) {
                stateDigestDirty.set(true)
                attempts.clear()
                requestAdvertisementRefresh()
            }
            return START_STICKY
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            BluetoothPeerState.markUnavailable(this, "На телефоне нет Bluetooth")
            stopSelf()
            return START_NOT_STICKY
        }
        activeAdapter = adapter
        Thread({ serverLoop(adapter) }, "familytasks-bt-server").start()
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        SyncLog.log(this, "BT: служба остановлена")
        stopRadio(activeAdapter)
        runCatching { activeServer?.close() }
        incomingHandlers.shutdownNow()
        outgoingHandler.shutdownNow()
        scheduler.shutdownNow()
        synchronized(radioLock) {
            localDigest?.fill(0)
            localDigest = null
            localBeacon = null
        }
        activeAdapter = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission", "NewApi")
    private fun serverLoop(adapter: BluetoothAdapter) {
        while (running.get()) {
            if (!adapter.isEnabled) {
                BluetoothPeerState.markUnavailable(this, "Bluetooth выключен")
                pauseRetry()
                continue
            }
            try {
                adapter.listenUsingInsecureL2capChannel().use { server ->
                    activeServer = server
                    startRadio(adapter, server.psm)
                    BluetoothPeerState.markWaiting(this)
                    while (running.get()) {
                        val socket = server.accept()
                        incomingHandlers.execute { exchange(socket, asClient = false, peerAddress = socket.remoteDevice.address) }
                    }
                }
            } catch (error: Exception) {
                SyncLog.log(this, "BT: серверный сокет закрылся: ${error.message}")
                if (running.get()) pauseRetry()
            } finally {
                stopRadio(adapter)
                activeServer = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRadio(adapter: BluetoothAdapter, psm: Int) {
        val advertiser = adapter.bluetoothLeAdvertiser
        val scanner = adapter.bluetoothLeScanner
        if (advertiser == null || scanner == null) {
            BluetoothPeerState.markUnavailable(this, "Телефон не поддерживает BLE-рекламу")
            return
        }
        activePsm = psm
        stateDigestDirty.set(true)
        refreshAdvertisingNow(force = true)
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(SERVICE_PARCEL_UUID).build()),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build(),
            scanCallback
        )
        refreshFuture?.cancel(false)
        refreshFuture = scheduler.scheduleWithFixedDelay(
            { refreshAdvertisingNow(force = false) },
            BEACON_REFRESH_SECONDS,
            BEACON_REFRESH_SECONDS,
            TimeUnit.SECONDS
        )
        pairingFuture?.cancel(false)
        pairingFuture = scheduler.scheduleWithFixedDelay(
            ::runPairingRound,
            INITIAL_DISCOVERY_SECONDS,
            PAIRING_ROUND_SECONDS,
            TimeUnit.SECONDS
        )
    }

    @SuppressLint("MissingPermission")
    private fun refreshAdvertisingNow(force: Boolean) {
        if (!running.get()) return
        val adapter = activeAdapter ?: return
        val psm = activePsm.takeIf { it != 0 } ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val epoch = BluetoothBeaconCodec.epoch()

        val digest = synchronized(radioLock) {
            val existing = localDigest
            if (stateDigestDirty.getAndSet(false) || existing == null) {
                val fresh = protocol.snapshotDigest()
                existing?.fill(0)
                localDigest = fresh
                fresh
            } else {
                existing
            }
        }
        val previous = localBeacon
        if (!force && previous?.epoch == epoch && previous.psm == psm) return

        val beacon = BluetoothBeaconCodec.create(
            psm = psm,
            epoch = epoch,
            deviceId = bluetoothDeviceId,
            snapshotDigest = digest,
            authenticate = app.syncKeys::authenticationProof
        )
        synchronized(radioLock) { localBeacon = beacon }

        val primary = AdvertiseData.Builder()
            .addServiceUuid(SERVICE_PARCEL_UUID)
            .addManufacturerData(
                LEGACY_MANUFACTURER_ID,
                ByteBuffer.allocate(LEGACY_PAYLOAD_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(psm.toShort())
                    .putInt(legacyPeerId)
                    .array()
            )
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addManufacturerData(BEACON_MANUFACTURER_ID, BluetoothBeaconCodec.encode(beacon))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        synchronized(radioLock) {
            runCatching { advertiser.stopAdvertising(advertiseCallback) }
            advertiser.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                    .setConnectable(true)
                    .build(),
                primary,
                scanResponse,
                advertiseCallback
            )
        }
    }

    private fun requestAdvertisementRefresh() {
        stateDigestDirty.set(true)
        if (!scheduler.isShutdown) scheduler.execute { refreshAdvertisingNow(force = true) }
    }

    @SuppressLint("MissingPermission")
    private fun stopRadio(adapter: BluetoothAdapter?) {
        refreshFuture?.cancel(false)
        pairingFuture?.cancel(false)
        refreshFuture = null
        pairingFuture = null
        activePsm = 0
        seenPeers.clear()
        seenLegacyPeers.clear()
        if (adapter == null || !hasBluetoothPermissions()) return
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        if (!running.get()) return
        val record = result.scanRecord ?: return
        val remote = record.getManufacturerSpecificData(BEACON_MANUFACTURER_ID)
            ?.let(BluetoothBeaconCodec::decode)
        if (remote == null) {
            rememberLegacyPeer(result, record.getManufacturerSpecificData(LEGACY_MANUFACTURER_ID))
            return
        }
        val digest = synchronized(radioLock) { localDigest?.copyOf() } ?: return
        val relation = try {
            BluetoothBeaconCodec.relation(
                remote,
                bluetoothDeviceId,
                digest,
                app.syncKeys::authenticationProof
            )
        } finally {
            digest.fill(0)
        }
        if (relation == BeaconRelation.FOREIGN_FAMILY || relation == BeaconRelation.SELF) return

        if (relation == BeaconRelation.SAME_STATE) {
            BluetoothPeerState.markUpToDate(this)
        } else {
            BluetoothPeerState.markDifference(this)
        }

        val now = SystemClock.elapsedRealtime()
        val address = result.device.address
        val firstSighting = !seenPeers.containsKey(address)
        seenLegacyPeers.remove(address)
        seenPeers.compute(address) { _, previous ->
            SeenPeer(
                result = result,
                beacon = remote,
                firstSeenElapsed = previous?.firstSeenElapsed ?: now,
                lastSeenElapsed = now
            )
        }
        if (firstSighting) {
            val state = if (relation == BeaconRelation.SAME_STATE) "совпадает" else "ОТЛИЧАЕТСЯ"
            SyncLog.log(this, "BT: рядом телефон ${SyncLog.mask(address)}, состояние $state")
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun runPairingRound() {
        if (!running.get() || exchangeInProgress.get()) return
        val local = localBeacon ?: return
        val digest = synchronized(radioLock) { localDigest?.copyOf() } ?: return
        val now = SystemClock.elapsedRealtime()
        try {
            seenPeers.entries.removeIf { now - it.value.lastSeenElapsed > PEER_TTL_MS }
            seenLegacyPeers.entries.removeIf { now - it.value.lastSeenElapsed > PEER_TTL_MS }
            val familyPeers = seenPeers.values.filter { peer ->
                BluetoothBeaconCodec.relation(
                    peer.beacon,
                    bluetoothDeviceId,
                    digest,
                    app.syncKeys::authenticationProof
                ) != BeaconRelation.FOREIGN_FAMILY
            }
            if (!app.syncHealth.bluetoothInitiationAllowed(preferences, app.sync.info().configured)) return
            if (now - lastOutgoingAttemptElapsed < GLOBAL_ATTEMPT_GAP_MS) return

            if (familyPeers.isNotEmpty()) {
                val allIds = familyPeers.map { it.beacon.pairingId } + local.pairingId
                val partnerId = BluetoothPairingPlanner.partnerFor(local.pairingId, allIds)
                val partner = familyPeers
                    .filter { it.beacon.pairingId == partnerId }
                    .maxByOrNull { it.lastSeenElapsed }
                if (partner != null) {
                    val relation = BluetoothBeaconCodec.relation(
                        partner.beacon,
                        bluetoothDeviceId,
                        digest,
                        app.syncKeys::authenticationProof
                    )
                    val preferred = Integer.compareUnsigned(local.pairingId, partner.beacon.pairingId) < 0
                    if (relation == BeaconRelation.DIFFERENT_STATE &&
                        (preferred || now - partner.firstSeenElapsed >= INITIATOR_GRACE_MS) &&
                        queueOutgoing(partner.result, partner.beacon.psm, partner.beacon.stateTag.contentHashCode(), now)
                    ) return
                }
            }

            // Rolling-upgrade fallback. A 2.0.9 peer has no v3 keyed beacon;
            // wait briefly for a scan response before using its legacy identity.
            val legacy = seenLegacyPeers.values
                .filter { now - it.firstSeenElapsed >= LEGACY_DISCOVERY_GRACE_MS }
                .sortedWith { left, right -> Integer.compareUnsigned(left.peerId, right.peerId) }
                .firstOrNull { peer ->
                    val preferred = Integer.compareUnsigned(legacyPeerId, peer.peerId) < 0
                    preferred || now - peer.firstSeenElapsed >= INITIATOR_GRACE_MS
                }
            if (legacy != null) queueOutgoing(legacy.result, legacy.psm, LEGACY_STATE_HASH, now)
        } finally {
            digest.fill(0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun rememberLegacyPeer(result: ScanResult, raw: ByteArray?) {
        if (raw == null || raw.size < LEGACY_PAYLOAD_BYTES) return
        val decoded = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val psm = decoded.short.toInt() and 0xffff
        val peerId = decoded.int
        if (psm == 0 || peerId == legacyPeerId) return
        val now = SystemClock.elapsedRealtime()
        val address = result.device.address
        seenLegacyPeers.compute(address) { _, previous ->
            SeenLegacyPeer(result, psm, peerId, previous?.firstSeenElapsed ?: now, now)
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun queueOutgoing(result: ScanResult, psm: Int, stateHash: Int, now: Long): Boolean {
        val address = result.device.address
        val attempt = attempts[address]
        val retryDelay = attempt?.retryDelayFor(stateHash) ?: 0L
        if (attempt != null && now - attempt.lastAttemptElapsed < retryDelay) return false
        lastOutgoingAttemptElapsed = now
        outgoingHandler.execute {
            BluetoothPeerState.markFound(this)
            SyncLog.log(this, "BT: подключаюсь к ${SyncLog.mask(address)}…")
            val socket = runCatching { result.device.createInsecureL2capChannel(psm) }
                .onFailure {
                    SyncLog.log(this, "BT: канал к ${SyncLog.mask(address)} не открылся: ${it.message}")
                }
                .getOrNull() ?: return@execute
            attempts[address] = AttemptState(now, stateHash, attempt?.failures ?: 0)
            runCatching { socket.connect() }
                .onSuccess { exchange(socket, asClient = true, peerAddress = address) }
                .onFailure { error ->
                    attempts[address] = AttemptState(
                        SystemClock.elapsedRealtime(),
                        stateHash,
                        (attempt?.failures ?: 0) + 1
                    )
                    SyncLog.log(this, "BT: подключение к ${SyncLog.mask(address)} не удалось: ${error.message}")
                    BluetoothPeerState.markConnectionFailed(this)
                    runCatching { socket.close() }
                }
        }
        return true
    }

    private fun exchange(socket: BluetoothSocket, asClient: Boolean, peerAddress: String) {
        if (!exchangeInProgress.compareAndSet(false, true)) {
            SyncLog.log(this, "BT: соединение с ${SyncLog.mask(peerAddress)} отклонено: уже идёт обмен")
            runCatching { socket.close() }
            return
        }
        BluetoothPeerState.markFound(this)
        try {
            if (asClient) protocol.exchangeAsClient(socket) else protocol.exchangeAsServer(socket)
            attempts[peerAddress] = AttemptState(SystemClock.elapsedRealtime(), 0, 0)
            requestAdvertisementRefresh()
        } catch (error: Exception) {
            val previous = attempts[peerAddress]
            attempts[peerAddress] = AttemptState(
                SystemClock.elapsedRealtime(),
                previous?.stateHash ?: 0,
                (previous?.failures ?: 0) + 1
            )
            val role = if (asClient) "клиент" else "сервер"
            SyncLog.log(
                this,
                "BT: обмен с ${SyncLog.mask(peerAddress)} оборвался ($role): ${error.message}"
            )
            if (error.message.orEmpty().contains("recovery-ключ")) {
                BluetoothPeerState.markKeyMismatch(this)
            } else {
                BluetoothPeerState.markConnectionFailed(this)
            }
            runCatching { socket.close() }
        } finally {
            exchangeInProgress.set(false)
        }
    }

    private fun pauseRetry() {
        try {
            Thread.sleep(10_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun hasBluetoothPermissions(): Boolean = when {
        Build.VERSION.SDK_INT >= 31 -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        ).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        else -> ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun foregroundNotification(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Фоновая Bluetooth-связь",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val intent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FamilyTasks")
            .setContentText("Фоновая Bluetooth-связь включена")
            .setContentIntent(intent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private data class SeenPeer(
        val result: ScanResult,
        val beacon: BluetoothBeacon,
        val firstSeenElapsed: Long,
        val lastSeenElapsed: Long
    )

    private data class SeenLegacyPeer(
        val result: ScanResult,
        val psm: Int,
        val peerId: Int,
        val firstSeenElapsed: Long,
        val lastSeenElapsed: Long
    )

    private data class AttemptState(
        val lastAttemptElapsed: Long,
        val stateHash: Int,
        val failures: Int
    ) {
        fun retryDelayFor(currentStateHash: Int): Long {
            if (stateHash != currentStateHash) return 0L
            return when (failures) {
                0 -> 2 * 60_000L
                1 -> 10_000L
                2 -> 30_000L
                else -> 2 * 60_000L
            }
        }
    }

    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("8ed553ac-6d2b-4ed6-9b11-52fa55758f41")
        private val SERVICE_PARCEL_UUID = ParcelUuid(SERVICE_UUID)
        private const val LEGACY_MANUFACTURER_ID = 0xFFFF
        private const val BEACON_MANUFACTURER_ID = 0xFFFE
        private const val LEGACY_PAYLOAD_BYTES = 6
        private const val LEGACY_STATE_HASH = Int.MIN_VALUE
        const val NOTIFICATION_CHANNEL = "bluetooth_sync_service_v2"
        private const val NOTIFICATION_ID = 41_003
        private const val ACTION_SYNC_NOW = "ru.simple.mycalendar.v2.BLUETOOTH_SYNC_NOW"
        private const val INITIAL_DISCOVERY_SECONDS = 4L
        private const val PAIRING_ROUND_SECONDS = 10L
        private const val BEACON_REFRESH_SECONDS = 10L
        private const val PEER_TTL_MS = 45_000L
        private const val INITIATOR_GRACE_MS = 8_000L
        private const val LEGACY_DISCOVERY_GRACE_MS = 6_000L
        private const val GLOBAL_ATTEMPT_GAP_MS = 8_000L

        fun applyEnabledState(context: Context, enabled: Boolean) {
            val intent = Intent(context, BluetoothSyncService::class.java)
            if (enabled) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(intent)
            }
        }

        fun requestSync(context: Context) {
            if (!UiPreferences(context).bluetoothSyncEnabled()) return
            val intent = Intent(context, BluetoothSyncService::class.java).setAction(ACTION_SYNC_NOW)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
