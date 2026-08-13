package ru.simple.mycalendar.v2.peer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.simple.mycalendar.v2.UiPreferences

class BluetoothBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED && UiPreferences(context).bluetoothSyncEnabled()) {
            BluetoothSyncService.applyEnabledState(context, true)
        }
    }
}
