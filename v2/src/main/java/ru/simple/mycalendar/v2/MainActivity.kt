package ru.simple.mycalendar.v2

import android.os.Bundle
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.simple.mycalendar.v2.ui.MyCalendarV2Theme
import ru.simple.mycalendar.v2.ui.MyCalendarApp

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            MyCalendarV2Theme {
                val app = application as V2App
                val model: V2ViewModel = viewModel(factory = V2ViewModelFactory(app.tasks, app.sync, app.uiPreferences))
                MyCalendarApp(model)
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("permissions_v2", MODE_PRIVATE)
            if (!prefs.getBoolean("notification_asked", false)) {
                prefs.edit().putBoolean("notification_asked", true).apply()
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
