package com.chronofond

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import java.io.File

/**
 * Widget 1x1 qui affiche une image à fond transparent et lance une action au toucher.
 * Le lanceur n'ajoute pas de fond blanc aux widgets, contrairement aux raccourcis.
 */
class IconWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            claimPending(context, id)
            render(context, manager, id)
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (id in ids) {
            File(context.filesDir, "widget_$id.png").delete()
            prefs.edit().remove("intent_$id").apply()
        }
    }

    companion object {
        private const val PREFS = "icon_widgets"
        private const val PENDING_FILE = "widget_pending.png"

        /** Prépare la configuration du prochain widget ajouté (l'identifiant n'est connu qu'à l'ajout). */
        fun savePending(context: Context, icon: Bitmap, intent: Intent) {
            File(context.filesDir, PENDING_FILE).outputStream().use {
                icon.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("pending_intent", intent.toUri(Intent.URI_INTENT_SCHEME))
                .apply()
        }

        private fun claimPending(context: Context, id: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pendingFile = File(context.filesDir, PENDING_FILE)
            val uri = prefs.getString("pending_intent", null)
            if (uri != null && pendingFile.exists() && !File(context.filesDir, "widget_$id.png").exists()) {
                pendingFile.renameTo(File(context.filesDir, "widget_$id.png"))
                prefs.edit().putString("intent_$id", uri).remove("pending_intent").apply()
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.icon_widget)
            val file = File(context.filesDir, "widget_$id.png")
            if (file.exists()) {
                BitmapFactory.decodeFile(file.absolutePath)?.let { views.setImageViewBitmap(R.id.widget_icon, it) }
            }
            val uri = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("intent_$id", null)
            if (uri != null) {
                runCatching {
                    val intent = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    val pi = PendingIntent.getActivity(
                        context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    views.setOnClickPendingIntent(R.id.widget_icon, pi)
                }
            }
            manager.updateAppWidget(id, views)
        }

        /** Demande à Android d'ajouter le widget à l'écran d'accueil. Retourne false si non pris en charge. */
        fun requestPin(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            return manager.requestPinAppWidget(ComponentName(context, IconWidgetProvider::class.java), null, null)
        }
    }
}
