package ru.simple.mycalendar.v2

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import ru.simple.mycalendar.v2.data.AppDatabase
import ru.simple.mycalendar.v2.data.TaskRepository
import ru.simple.mycalendar.v2.notify.ReminderScheduler
import ru.simple.mycalendar.v2.security.SecureKeys
import ru.simple.mycalendar.v2.security.SyncKeyController
import ru.simple.mycalendar.v2.sync.SyncClient
import ru.simple.mycalendar.v2.sync.SyncWorker
import ru.simple.mycalendar.v2.sync.ServerSyncHealth
import ru.simple.mycalendar.v2.peer.BluetoothSyncService
import ru.simple.mycalendar.v2.update.UpdateWorker

class V2App : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var tasks: TaskRepository
        private set
    lateinit var sync: SyncClient
        private set
    lateinit var uiPreferences: UiPreferences
        private set
    lateinit var syncKeys: SyncKeyController
        private set
    lateinit var localChanges: LocalChangeTracker
        private set
    lateinit var syncHealth: ServerSyncHealth
        private set

    override fun onCreate() {
        super.onCreate()

        // sqlcipher-android does not load its native library automatically on every device/OS.
        // Loading before Room opens the first connection prevents the original nativeOpen crash.
        System.loadLibrary("sqlcipher")

        val keys = SecureKeys(this)
        val databaseKey = keys.getOrCreate("database_key")
        val factory = SupportOpenHelperFactory(databaseKey.copyOf())
        databaseKey.fill(0)
        database = Room.databaseBuilder(this, AppDatabase::class.java, "mycalendar-v2.db")
            .openHelperFactory(factory)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()
        uiPreferences = UiPreferences(this)
        val reminders = ReminderScheduler(this)
        val revisionPrefs = getSharedPreferences("revision_identity_v2", MODE_PRIVATE)
        val revisionDevice = revisionPrefs.getString("device", null) ?: java.util.UUID.randomUUID().toString().also {
            check(revisionPrefs.edit().putString("device", it).commit())
        }
        localChanges = LocalChangeTracker(this)
        syncHealth = ServerSyncHealth(this)
        tasks = TaskRepository(database.tasks(), reminders, revisionDevice, uiPreferences.localUserId()) {
            localChanges.markChanged()
            syncHealth.markLocalChange()
            SyncWorker.scheduleSoon(this)
            BluetoothSyncService.requestSync(this)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            tasks.ensureCurrentProfile()
            database.tasks().snapshotTasks().filter { it.deletedAt == null }.forEach(reminders::schedule)
        }

        syncKeys = SyncKeyController(keys)
        sync = SyncClient(this, database.tasks(), keys, syncKeys, reminders, uiPreferences, localChanges)
        SyncWorker.schedule(this)
        UpdateWorker.schedule(this)
        if (uiPreferences.bluetoothSyncEnabled()) {
            BluetoothSyncService.applyEnabledState(this, true)
        }
    }
}
