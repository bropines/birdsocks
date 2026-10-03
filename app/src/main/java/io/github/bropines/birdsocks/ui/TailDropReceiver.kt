package io.github.bropines.birdsocks.ui
import io.github.bropines.birdsocks.R
import io.github.bropines.birdsocks.BuildConfig

import io.github.bropines.birdsocks.admin.*
import io.github.bropines.birdsocks.core.*
import io.github.bropines.birdsocks.models.*

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import java.io.File

class TailDropReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val filePath = intent.getStringExtra("FILE_PATH") ?: return
        val notifId = intent.getIntExtra("NOTIF_ID", 0)
        val file = File(filePath)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent.action) {
            "ACCEPT_FILE" -> {
                if (file.exists()) {
                    try {
                        // Save to public Downloads folder
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val destFile = File(downloadsDir, file.name.removeSuffix(".pending"))
                        file.copyTo(destFile, overwrite = true)
                        file.delete()
                        Toast.makeText(context, context.getString(R.string.taildrop_saved_to_downloads_format, destFile.name), Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.taildrop_save_failed_format, e.message), Toast.LENGTH_LONG).show()
                    }
                }
                nm.cancel(notifId)
            }
            "REJECT_FILE" -> {
                if (file.exists()) file.delete()
                nm.cancel(notifId)
                Toast.makeText(context, context.getString(R.string.taildrop_file_deleted), Toast.LENGTH_SHORT).show()
            }
        }
    }
}