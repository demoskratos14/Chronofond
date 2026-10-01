package com.chronofond

import android.app.WallpaperManager
import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class Pick(val current: List<String>, val cursor: Int, val cellPtr: Int)

/** Choisit les photos à afficher au prochain changement, en fonction de la forme de chaque case. */
object Picker {

    fun next(
        sc: ScreenConfig,
        pool: List<String>,
        cellRatios: List<Float>,
        ratioOf: (String) -> Float
    ): Pick {
        val n = cellRatios.size
        val validCurrent = sc.current.size == n && sc.current.all { it in pool }
        val order = ordered(pool, sc)

        // Mode "une seule case change à chaque rotation"
        if (sc.singleCell && n > 1 && validCurrent) {
            val ptr = sc.cellPtr % n
            val cur = sc.current.toMutableList()
            val k = choose(cellRatios[ptr], order, sc.current.toSet(), ratioOf)
            cur[ptr] = order[k]
            val cursor = if (sc.shuffle) sc.cursor else sc.cursor + k + 1
            return Pick(cur, cursor, (ptr + 1) % n)
        }

        // Renouvellement complet : on évite de remettre les photos déjà affichées si le lot est assez grand
        val used = (if (pool.size >= 2 * n) sc.current.toSet() else emptySet()).toMutableSet()
        val result = arrayOfNulls<String>(n)
        val taken = mutableSetOf<Int>()
        // les cases "carrées" (qui acceptent tout) passent en dernier
        val sequence = cellRatios.indices.sortedBy { if (shapeOf(cellRatios[it]) == Shape.SQUARE) 1 else 0 }
        for (i in sequence) {
            val k = choose(cellRatios[i], order, used, ratioOf)
            result[i] = order[k]
            used += order[k]
            taken += k
        }
        var cursor = sc.cursor
        if (!sc.shuffle) {
            var prefix = 0
            while (prefix in taken) prefix++
            cursor += max(1, prefix)
        }
        return Pick(result.map { it!! }, cursor, 0)
    }

    private fun ordered(pool: List<String>, sc: ScreenConfig): List<String> =
        if (sc.shuffle) pool.shuffled()
        else {
            val s = sc.cursor % pool.size
            pool.drop(s) + pool.take(s)
        }

    /** Indice (dans `order`) de la meilleure photo pour une case de ce ratio. */
    private fun choose(
        ratio: Float,
        order: List<String>,
        used: Set<String>,
        ratioOf: (String) -> Float
    ): Int {
        val shape = shapeOf(ratio)
        val free = order.indices.filter { order[it] !in used }
        // 1) première photo libre de la bonne forme (les cases carrées acceptent tout)
        free.firstOrNull { shape == Shape.SQUARE || shapeOf(ratioOf(order[it])) == shape }
            ?.let { return it }
        // 2) sinon la photo libre dont le ratio est le plus proche
        free.minByOrNull { dist(ratioOf(order[it]), ratio) }?.let { return it }
        // 3) sinon (lot trop petit) on réutilise la plus proche
        return order.indices.minByOrNull { dist(ratioOf(order[it]), ratio) } ?: 0
    }

    private fun dist(a: Float, b: Float) = abs(ln(a / b))
}

/** Dernier résultat de chaque écran, affiché dans l'appli pour comprendre les échecs. */
object Status {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("status", Context.MODE_PRIVATE)

    fun set(ctx: Context, t: Target, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        prefs(ctx).edit().putString(t.name, "$time — $msg").apply()
    }

    fun get(ctx: Context, t: Target): String =
        prefs(ctx).getString(t.name, "Aucun changement tenté pour l'instant") ?: ""
}

/** Mémorise l'identifiant du fond posé par l'appli, pour détecter qu'un autre programme l'a remplacé. */
object Guard {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("wpid", Context.MODE_PRIVATE)
    fun saved(ctx: Context, t: Target): Int = prefs(ctx).getInt(t.name, 0)
    fun save(ctx: Context, t: Target, id: Int) = prefs(ctx).edit().putInt(t.name, id).apply()
}

private fun canRead(ctx: Context, s: String): Boolean = try {
    ctx.contentResolver.openInputStream(Uri.parse(s))?.use { true } ?: false
} catch (e: Exception) {
    false
}

class WallpaperWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val target = inputData.getString("target")?.let { Target.valueOf(it) }
            ?: return@withContext Result.failure()
        val force = inputData.getBoolean("force", false)
        val repair = inputData.getBoolean("repair", false)
        val app = applicationContext
        val cfg = app.configFlow.first()
        val sc = if (target == Target.HOME) cfg.home else cfg.lock
        val flag = if (target == Target.HOME) WallpaperManager.FLAG_SYSTEM
        else WallpaperManager.FLAG_LOCK
        val wm = WallpaperManager.getInstance(app)

        // Mode "réparation" : ne fait rien si le fond posé par l'appli est toujours en place
        if (repair) {
            val saved = Guard.saved(app, target)
            if (!sc.enabled || saved == 0 || sc.current.isEmpty() || wm.getWallpaperId(flag) == saved) {
                return@withContext Result.success()
            }
        }

        // "Changer maintenant" (force) fonctionne même si la rotation automatique est désactivée
        if (!sc.enabled && !force) {
            Status.set(app, target, "Rotation désactivée (activez l'interrupteur)")
            return@withContext Result.success()
        }

        val pool = if (target == Target.LOCK && cfg.lockSameAsHome) cfg.home.uris else sc.uris
        if (pool.isEmpty()) {
            Status.set(app, target, "Aucune photo dans la liste")
            return@withContext Result.success()
        }

        try {
            val dm = app.resources.displayMetrics
            val w = min(dm.widthPixels, dm.heightPixels)
            val h = max(dm.widthPixels, dm.heightPixels)
            val ratios = Mosaic.cellRects(sc, w, h).map { it.width() / it.height() }
            val pick = if (repair && sc.current.size == ratios.size)
                Pick(sc.current, sc.cursor, sc.cellPtr)   // on remet le même fond, sans avancer
            else Picker.next(sc, pool, ratios) { PhotoRatios.of(app, it) }

            if (pick.current.none { canRead(app, it) }) {
                Status.set(app, target, "Photos illisibles (permission perdue ?) : retirez-les puis ajoutez-les de nouveau")
                return@withContext Result.success()
            }

            val bmp = Mosaic.render(app, pick.current, sc, w, h)
            wm.setBitmap(bmp, null, true, flag)
            Guard.save(app, target, wm.getWallpaperId(flag))
            bmp.recycle()
            app.updateScreen(target) {
                it.copy(cursor = pick.cursor, current = pick.current, cellPtr = pick.cellPtr)
            }
            Status.set(
                app, target,
                if (repair) "Fond remis en place : le système l'avait remplacé"
                else "Fond d'écran changé (${pick.current.size} photo(s))"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // On réessaiera à la prochaine période, mais on garde la raison de l'échec
            Status.set(app, target, "Erreur : ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
        Result.success()
    }
}

object Scheduler {
    private fun name(t: Target) = "wallpaper_${t.name}"
    private fun guardName(t: Target) = "guard_${t.name}"

    /** Surveillance toutes les 15 min : remet le fond si le système l'a remplacé. */
    fun startGuards(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        for (t in Target.values()) {
            val req = PeriodicWorkRequestBuilder<WallpaperWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf("target" to t.name, "repair" to true))
                .build()
            wm.enqueueUniquePeriodicWork(guardName(t), ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    /** Vérification immédiate (à l'ouverture de l'appli). */
    fun repairNow(ctx: Context) {
        for (t in Target.values()) {
            val req = OneTimeWorkRequestBuilder<WallpaperWorker>()
                .setInputData(workDataOf("target" to t.name, "repair" to true))
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }

    fun schedule(ctx: Context, t: Target, sc: ScreenConfig) {
        val wm = WorkManager.getInstance(ctx)
        if (!sc.enabled) {
            wm.cancelUniqueWork(name(t))
            wm.cancelUniqueWork(guardName(t))
            return
        }
        val req = PeriodicWorkRequestBuilder<WallpaperWorker>(
            sc.intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES
        )
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setInputData(workDataOf("target" to t.name))
            .build()
        wm.enqueueUniquePeriodicWork(name(t), ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun runNow(ctx: Context, t: Target) {
        val req = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setInputData(workDataOf("target" to t.name, "force" to true))
            .build()
        WorkManager.getInstance(ctx).enqueue(req)
    }
}
