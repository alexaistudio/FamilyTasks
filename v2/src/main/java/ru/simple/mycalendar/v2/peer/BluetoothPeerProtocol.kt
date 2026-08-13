package ru.simple.mycalendar.v2.peer

import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.runBlocking
import ru.simple.mycalendar.v2.UiPreferences
import ru.simple.mycalendar.v2.SyncLog
import ru.simple.mycalendar.v2.data.TaskDao
import ru.simple.mycalendar.v2.notify.IncomingTaskNotifier
import ru.simple.mycalendar.v2.notify.ReminderScheduler
import ru.simple.mycalendar.v2.security.EncryptedEnvelope
import ru.simple.mycalendar.v2.security.SyncKeyController
import ru.simple.mycalendar.v2.sync.SnapshotCodec
import ru.simple.mycalendar.v2.sync.SyncSnapshot
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

class BluetoothPeerProtocol(
    context: Context,
    private val dao: TaskDao,
    private val crypto: SyncKeyController,
    private val reminders: ReminderScheduler,
    private val preferences: UiPreferences,
    private val onDatabaseChanged: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val notifier = IncomingTaskNotifier(appContext)

    /**
     * A phone may discover several family peers at once. Serialize complete
     * exchanges per device so every outgoing snapshot starts from a stable,
     * fully merged database state; later pairwise rounds relay that union.
     */
    @Synchronized
    fun exchangeAsClient(socket: BluetoothSocket) {
        socket.use {
            val input = DataInputStream(it.inputStream.buffered())
            val output = DataOutputStream(it.outputStream.buffered())
            val clientNonce = randomNonce()
            output.write(MAGIC)
            output.write(clientNonce)
            output.flush()

            val serverNonce = ByteArray(NONCE_BYTES).also(input::readFully)
            val serverProof = ByteArray(PROOF_BYTES).also(input::readFully)
            verifyProof("server", clientNonce, serverNonce, serverProof)
            output.write(proof("client", clientNonce, serverNonce))
            output.flush()

            val prepared = prepareSnapshot()
            val localState = stateTag(prepared.digest, clientNonce, serverNonce)
            val remoteState = ByteArray(PROOF_BYTES)
            try {
                output.write(localState)
                output.flush()
                input.readFully(remoteState)
                if (!MessageDigest.isEqual(localState, remoteState)) {
                    sessionEnvelope(clientNonce, serverNonce).useEnvelope { envelope ->
                        sendSnapshot(output, envelope, prepared.plain, clientNonce, serverNonce, "client")
                        receiveAndMerge(input, envelope, clientNonce, serverNonce, "server", prepared.digest)
                    }
                } else {
                    SyncLog.log(appContext, "BT: отпечатки совпали, передача снимка не нужна")
                }
            } finally {
                prepared.clear()
                localState.fill(0)
                remoteState.fill(0)
            }
            markSuccessfulExchange()
        }
    }

    @Synchronized
    fun exchangeAsServer(socket: BluetoothSocket) {
        socket.use {
            val input = DataInputStream(it.inputStream.buffered())
            val output = DataOutputStream(it.outputStream.buffered())
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Чужой Bluetooth-протокол" }
            val clientNonce = ByteArray(NONCE_BYTES).also(input::readFully)
            val serverNonce = randomNonce()
            output.write(serverNonce)
            output.write(proof("server", clientNonce, serverNonce))
            output.flush()
            val clientProof = ByteArray(PROOF_BYTES).also(input::readFully)
            verifyProof("client", clientNonce, serverNonce, clientProof)

            val remoteState = ByteArray(PROOF_BYTES).also(input::readFully)
            val prepared = prepareSnapshot()
            val localState = stateTag(prepared.digest, clientNonce, serverNonce)
            try {
                output.write(localState)
                output.flush()
                if (!MessageDigest.isEqual(localState, remoteState)) {
                    sessionEnvelope(clientNonce, serverNonce).useEnvelope { envelope ->
                        receiveAndMerge(input, envelope, clientNonce, serverNonce, "client", prepared.digest)
                        sendSnapshot(output, envelope, prepared.plain, clientNonce, serverNonce, "server")
                    }
                } else {
                    SyncLog.log(appContext, "BT: отпечатки совпали, передача снимка не нужна")
                }
            } finally {
                prepared.clear()
                localState.fill(0)
                remoteState.fill(0)
            }
            markSuccessfulExchange()
        }
    }

    private fun sendSnapshot(
        output: DataOutputStream,
        envelope: EncryptedEnvelope,
        plain: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        direction: String
    ) {
        val encrypted = envelope.encrypt(plain, aad(clientNonce, serverNonce, direction))
        require(encrypted.size <= MAX_PACKET_BYTES) { "Bluetooth-пакет слишком большой" }
        output.writeInt(encrypted.size)
        output.write(encrypted)
        output.flush()
        encrypted.fill(0)
    }

    private fun receiveAndMerge(
        input: DataInputStream,
        envelope: EncryptedEnvelope,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        direction: String,
        localDigestBefore: ByteArray
    ) {
        val size = input.readInt()
        require(size in 33..MAX_PACKET_BYTES) { "Неверный размер Bluetooth-пакета" }
        val encrypted = ByteArray(size).also(input::readFully)
        val plain = try {
            envelope.decrypt(encrypted, aad(clientNonce, serverNonce, direction))
        } finally {
            encrypted.fill(0)
        }
        val remote = try {
            SnapshotCodec.decode(plain)
        } finally {
            plain.fill(0)
        }
        val (incoming, databaseChanged) = runBlocking {
            val ids = remote.tasks.filter { it.deletedAt == null && dao.find(it.id) == null }.map { it.id }
            dao.mergeSnapshot(remote.tasks, remote.purges, remote.profiles)
            remote.tasks.mapNotNull { dao.find(it.id) }.forEach(reminders::schedule)
            remote.purges.forEach { reminders.cancel(it.taskId) }
            val incomingTasks = ids.mapNotNull { dao.find(it) }.filter { it.deletedAt == null }
            val after = calculateSnapshotDigest()
            val changed = try {
                !MessageDigest.isEqual(localDigestBefore, after)
            } finally {
                after.fill(0)
            }
            incomingTasks to changed
        }
        SyncLog.log(
            appContext,
            "BT: получен снимок (дел ${remote.tasks.size}, удалений ${remote.purges.size}, " +
                "профилей ${remote.profiles.size}); локальная база изменилась: " +
                if (databaseChanged) "да" else "нет"
        )
        if (databaseChanged) onDatabaseChanged()
        if (BluetoothPeerState.hasSynced(appContext) && preferences.notifyAboutNewTasks()) {
            notifier.show(incoming)
        }
    }

    private fun proof(role: String, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        crypto.authenticationProof(label("proof/$role", clientNonce, serverNonce))

    private fun verifyProof(role: String, clientNonce: ByteArray, serverNonce: ByteArray, actual: ByteArray) {
        val expected = proof(role, clientNonce, serverNonce)
        try {
            require(MessageDigest.isEqual(expected, actual)) { "Bluetooth-устройство не знает общий recovery-ключ" }
        } finally {
            expected.fill(0)
        }
    }

    private fun sessionEnvelope(clientNonce: ByteArray, serverNonce: ByteArray): EncryptedEnvelope {
        val sessionKey = crypto.authenticationProof(label("session", clientNonce, serverNonce))
        return try {
            EncryptedEnvelope(sessionKey)
        } finally {
            sessionKey.fill(0)
        }
    }

    private fun aad(clientNonce: ByteArray, serverNonce: ByteArray, direction: String): ByteArray =
        label("payload/$direction", clientNonce, serverNonce)

    private fun prepareSnapshot(): PreparedSnapshot {
        val snapshot = runBlocking {
            SyncSnapshot(dao.snapshotTasks(), dao.snapshotPurges(), dao.snapshotProfiles())
        }
        val plain = SnapshotCodec.encode(snapshot)
        return PreparedSnapshot(plain, MessageDigest.getInstance("SHA-256").digest(plain))
    }

    /** Canonical local state used only to build a keyed BLE equality tag. */
    fun snapshotDigest(): ByteArray = runBlocking { calculateSnapshotDigest() }

    private suspend fun calculateSnapshotDigest(): ByteArray {
        val plain = SnapshotCodec.encode(
            SyncSnapshot(dao.snapshotTasks(), dao.snapshotPurges(), dao.snapshotProfiles())
        )
        return try {
            MessageDigest.getInstance("SHA-256").digest(plain)
        } finally {
            plain.fill(0)
        }
    }

    private fun stateTag(digest: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray {
        val input = label("state", clientNonce, serverNonce) + digest
        return try {
            crypto.authenticationProof(input)
        } finally {
            input.fill(0)
        }
    }

    private fun label(name: String, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        "mycalendar-v2/bluetooth/$name/".toByteArray() + clientNonce + serverNonce

    private fun randomNonce(): ByteArray = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)

    private fun markSuccessfulExchange() {
        SyncLog.log(appContext, "BT: защищённый обмен успешно завершён")
        BluetoothPeerState.markSuccess(appContext)
    }

    private inline fun EncryptedEnvelope.useEnvelope(block: (EncryptedEnvelope) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private data class PreparedSnapshot(val plain: ByteArray, val digest: ByteArray) {
        fun clear() {
            plain.fill(0)
            digest.fill(0)
        }
    }

    companion object {
        private val MAGIC = "MCBT2".toByteArray()
        private const val NONCE_BYTES = 32
        private const val PROOF_BYTES = 32
        private const val MAX_PACKET_BYTES = 12 * 1024 * 1024
    }
}
