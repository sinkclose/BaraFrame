package com.multicast.bara

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

class ExampleBackgroundReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_QUERY_BACKGROUND_PLUGINS = "com.nxd1frnt.clockdesk2.ACTION_QUERY_BACKGROUND_PLUGINS"
        const val ACTION_REQUEST_BACKGROUND_DATA = "com.nxd1frnt.clockdesk2.ACTION_REQUEST_BACKGROUND_DATA"
        const val ACTION_UPDATE_BACKGROUND_DATA = "com.nxd1frnt.clockdesk2.ACTION_UPDATE_BACKGROUND_DATA"
        const val CLOCKDESK_PACKAGE = "com.nxd1frnt.clockdesk2"
        const val PLUGIN_ID = "maleframe" // Stable across product renames and updates.
        const val DEFAULT_COLOR = 0xFF151922.toInt()

        fun clockDeskInstalled(context: Context): Boolean = try {
            context.packageManager.getApplicationInfo(CLOCKDESK_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }

        fun broadcastUpdate(context: Context) {
            if (!clockDeskInstalled(context)) return
            val cached = ArtworkStore(context).current()
            val intent = Intent(ACTION_UPDATE_BACKGROUND_DATA).apply {
                setPackage(CLOCKDESK_PACKAGE)
                putExtra("plugin_id", PLUGIN_ID)
                putExtra("plugin_package_name", context.packageName)
            }
            if (cached != null) {
                val root = Uri.parse("content://${context.packageName}.artwork/background/")
                val uri = root.buildUpon().appendPath("current.jpg").appendQueryParameter("rev", cached.revision).build()
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    context.grantUriPermission(CLOCKDESK_PACKAGE, root, flags)
                    // Restore support for FileProvider URIs saved by earlier versions of ClockDesk.
                    context.grantUriPermission(CLOCKDESK_PACKAGE, Uri.parse("content://${context.packageName}.artwork/artwork/"), flags)
                } catch (e: SecurityException) {
                    Log.w("ArtworkBackground", "Could not grant ClockDesk background access", e)
                    return
                }
                intent.putExtra("image_uri", uri.toString())
                intent.clipData = ClipData.newRawUri("BaraFrame", uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else intent.putExtra("color", DEFAULT_COLOR)
            context.sendBroadcast(intent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_QUERY_BACKGROUND_PLUGINS -> broadcastUpdate(context)
            ACTION_REQUEST_BACKGROUND_DATA -> {
                broadcastUpdate(context)
                ArtworkScheduler.schedule(context, update = false)
                val store = ArtworkStore(context)
                if (store.settings().automatic && store.isDue()) ArtworkScheduler.refresh(context, false)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                broadcastUpdate(context)
                ArtworkScheduler.schedule(context, update = false)
            }
        }
    }
}
