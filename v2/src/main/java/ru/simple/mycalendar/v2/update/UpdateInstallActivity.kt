package ru.simple.mycalendar.v2.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider

class UpdateInstallActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) openInstaller() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageManager.canRequestPackageInstalls()) {
            openInstaller()
        } else {
            permission.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun openInstaller() {
        val updater = AppUpdater(this)
        val file = updater.readyApk() ?: return finish()
        try {
            updater.verifyOfficialApk(file)
        } catch (_: Exception) {
            file.delete()
            return finish()
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
        val install = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
        install.clipData = ClipData.newRawUri("FamilyTasks update", uri)
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        install.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        startActivity(install)
        finish()
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(Intent(context, UpdateInstallActivity::class.java))
        }
    }
}
