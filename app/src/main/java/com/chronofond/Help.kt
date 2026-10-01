package com.chronofond

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Conseils selon la marque détectée. Les noms de menus peuvent varier selon la version du système. */
private fun tipsFor(brand: String): Pair<String, List<String>> = when {
    brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> Pair(
        "Xiaomi / Redmi / POCO (MIUI ou HyperOS)",
        listOf(
            "Paramètres > Applications > Gérer les applications > Chronofond > Démarrage automatique : activer.",
            "Dans la même fiche, Économie de batterie : « Aucune restriction ».",
            "Autres autorisations : autoriser « Créer des raccourcis » (et « Passer des appels » pour les appels directs).",
            "Désactiver le carrousel de fond d'écran et les mises à jour automatiques de l'application Thèmes."
        )
    )
    brand.contains("samsung") -> Pair(
        "Samsung (One UI)",
        listOf(
            "Paramètres > Applications > Chronofond > Batterie : choisir « Illimitée ».",
            "Paramètres > Batterie > Limites d'utilisation en arrière-plan : retirer Chronofond des applications mises en veille.",
            "Autoriser l'application à s'exécuter en arrière-plan si le système le propose."
        )
    )
    brand.contains("huawei") || brand.contains("honor") -> Pair(
        "Huawei / Honor",
        listOf(
            "Paramètres > Batterie > Lancement d'applications > Chronofond : passer en gestion manuelle.",
            "Cocher « Lancement automatique », « Lancement secondaire » et « Exécution en arrière-plan »."
        )
    )
    brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> Pair(
        "Oppo / Realme / OnePlus",
        listOf(
            "Paramètres > Applications > Chronofond > Utilisation de la batterie : autoriser l'activité en arrière-plan.",
            "Activer le démarrage automatique (ou « Lancement automatique ») pour Chronofond.",
            "Verrouiller l'application dans la liste des applications récentes si le système le permet."
        )
    )
    else -> Pair(
        "Autres téléphones",
        listOf(
            "Paramètres > Applications > Chronofond > Batterie : choisir « Sans restriction ».",
            "Si votre marque a un gestionnaire de démarrage ou d'économie d'énergie, autorisez Chronofond."
        )
    )
}

private fun openAppSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        )
    }
}

private fun openBatterySettings(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val (title, steps) = tipsFor(Build.MANUFACTURER.lowercase())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aide") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Téléphone détecté : ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Si le fond ne change pas tout seul, le système bloque probablement l'application en " +
                        "arrière-plan. Réglages conseillés (indicatifs, les noms de menus varient selon la version) :",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(title, style = MaterialTheme.typography.titleSmall)
                steps.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                Text("Pour tous les téléphones", style = MaterialTheme.typography.titleSmall)
                Text("• Ne fermez pas l'appli en la balayant dans les applications récentes.")
                Text("• « Changer maintenant » fonctionne même si la rotation est désactivée.")
                Text("• La ligne « Dernier essai » indique l'heure et le résultat du dernier changement : si l'heure est ancienne, le système a bloqué l'appli.")
                Text("• Onglet Raccourcis : si rien n'apparaît sur l'écran d'accueil, autorisez « Créer des raccourcis ».")
                OutlinedButton(onClick = { openBatterySettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Réglages d'optimisation de la batterie")
                }
            }
        },
        confirmButton = { TextButton(onClick = { openAppSettings(ctx) }) { Text("Réglages de l'appli") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}
