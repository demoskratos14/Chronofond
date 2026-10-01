package com.chronofond

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private class AppEntry(val label: String, val pkg: String, val icon: Bitmap)

private class ContactEntry(
    val id: Long, val lookupKey: String, val name: String, val thumb: String?, val photo: String?
)

/** Ce que l'éditeur doit créer : une appli (pkg) ou un contact (numbers + lookupUri). */
private class Source(
    val title: String,
    val key: String,
    val original: Bitmap,
    val label: String,
    val pkg: String? = null,
    val numbers: List<String> = emptyList(),
    val lookupUri: Uri? = null
)

private fun loadContacts(ctx: Context): List<ContactEntry> {
    val list = mutableListOf<ContactEntry>()
    val cols = arrayOf(
        ContactsContract.Contacts._ID,
        ContactsContract.Contacts.LOOKUP_KEY,
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        ContactsContract.Contacts.PHOTO_URI
    )
    ctx.contentResolver.query(
        ContactsContract.Contacts.CONTENT_URI, cols, null, null,
        "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"
    )?.use { c ->
        while (c.moveToNext()) {
            list.add(
                ContactEntry(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3), c.getString(4))
            )
        }
    }
    return list.filter { it.name.isNotBlank() }
}

private fun loadNumbers(ctx: Context, contactId: Long): List<String> {
    val out = mutableListOf<Pair<String, Int>>()
    ctx.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
        ),
        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
        arrayOf(contactId.toString()), null
    )?.use { c ->
        while (c.moveToNext()) {
            val n = c.getString(0)?.trim().orEmpty()
            if (n.isNotEmpty()) out.add(n to c.getInt(1))
        }
    }
    return out.sortedByDescending { it.second }.map { it.first }.distinct()
}

private fun initialBitmap(name: String): Bitmap {
    val s = 432
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFF5E35B1.toInt()
    c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
    p.color = 0xFFFFFFFF.toInt()
    p.textSize = s * 0.5f
    p.textAlign = Paint.Align.CENTER
    val y = s / 2f - (p.descent() + p.ascent()) / 2f
    c.drawText(name.trim().take(1).uppercase().ifEmpty { "?" }, s / 2f, y, p)
    return bmp
}

private fun circleCrop(src: Bitmap): Bitmap {
    val s = min(src.width, src.height)
    val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawCircle(s / 2f, s / 2f, s / 2f, p)
    p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    c.drawBitmap(src, -(src.width - s) / 2f, -(src.height - s) / 2f, p)
    return out
}

private fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    d.setBounds(0, 0, size, size)
    d.draw(c)
    return bmp
}

private fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map {
            AppEntry(
                it.loadLabel(pm).toString(),
                it.activityInfo.packageName,
                drawableToBitmap(it.loadIcon(pm), 96)
            )
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

private fun loadImage(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = try {
    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
    ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        val m = max(info.size.width, info.size.height)
        if (m > maxSide) {
            val f = maxSide.toFloat() / m
            decoder.setTargetSize(
                max(1, (info.size.width * f).toInt()),
                max(1, (info.size.height * f).toInt())
            )
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
} catch (e: Exception) {
    null
}

/**
 * Dessine l'icône réduite dans un carré transparent.
 * scale : 0.2..1 (part du carré occupée), ox/oy : 0 = gauche/haut, 1 = droite/bas.
 */
private fun composeIcon(base: Bitmap, scale: Float, ox: Float, oy: Float, out: Int): Bitmap {
    val bmp = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val s = out * scale
    val r = min(s / base.width, s / base.height)
    val w = base.width * r
    val h = base.height * r
    val left = ox * (out - s) + (s - w) / 2f
    val top = oy * (out - s) + (s - h) / 2f
    val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    c.drawBitmap(base, null, RectF(left, top, left + w, top + h), p)
    return bmp
}

@Composable
fun ShortcutsTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(0) } // 0 = applications, 1 = contacts
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var contacts by remember { mutableStateOf<List<ContactEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Source?>(null) }
    var query by remember { mutableStateOf("") }
    var hasContacts by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasContacts = it
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(ctx) }
        loading = false
    }
    LaunchedEffect(hasContacts) {
        if (hasContacts) contacts = withContext(Dispatchers.IO) { loadContacts(ctx) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        color = Color.White.copy(alpha = 0.92f),
        shape = RoundedCornerShape(16.dp)
    ) {
        val sel = selected
        if (sel != null) {
            Editor(sel) { selected = null }
        } else {
            Column(Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == 0) Button(onClick = {}) { Text("Applications") }
                    else OutlinedButton(onClick = { mode = 0; query = "" }) { Text("Applications") }
                    if (mode == 1) Button(onClick = {}) { Text("Contacts") }
                    else OutlinedButton(onClick = { mode = 1; query = "" }) { Text("Contacts") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Rechercher") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                if (mode == 0) {
                    if (loading) Text("Chargement des applications…")
                    val shown = apps.filter { it.label.contains(query, ignoreCase = true) }
                    LazyColumn(Modifier.weightedListHeight()) {
                        items(shown, key = { it.pkg }) { a ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val hi = runCatching {
                                        drawableToBitmap(ctx.packageManager.getApplicationIcon(a.pkg), 432)
                                    }.getOrDefault(a.icon)
                                    selected = Source(a.label, a.pkg, hi, a.label, pkg = a.pkg)
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Image(a.icon.asImageBitmap(), null, Modifier.size(40.dp))
                                Text(a.label)
                            }
                        }
                    }
                } else if (!hasContacts) {
                    Text("Chronofond a besoin de lire vos contacts pour les afficher dans la liste.")
                    Button(
                        onClick = { contactsPerm.launch(Manifest.permission.READ_CONTACTS) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("Autoriser l'accès aux contacts") }
                } else {
                    val shown = contacts.filter { it.name.contains(query, ignoreCase = true) }
                    LazyColumn(Modifier.weightedListHeight()) {
                        items(shown, key = { it.id }) { ct ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    scope.launch {
                                        selected = withContext(Dispatchers.IO) {
                                            val photo = listOfNotNull(ct.photo, ct.thumb).firstNotNullOfOrNull {
                                                loadImage(ctx, Uri.parse(it), 432)
                                            } ?: initialBitmap(ct.name)
                                            Source(
                                                title = ct.name,
                                                key = "contact_${ct.id}",
                                                original = photo,
                                                label = ct.name,
                                                numbers = loadNumbers(ctx, ct.id),
                                                lookupUri = ContactsContract.Contacts.getLookupUri(ct.id, ct.lookupKey)
                                            )
                                        }
                                    }
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (ct.thumb != null) {
                                    AsyncImage(ct.thumb, null, Modifier.size(40.dp).clip(CircleShape))
                                } else {
                                    Box(
                                        Modifier.size(40.dp).background(Color(0xFF5E35B1), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) { Text(ct.name.take(1).uppercase(), color = Color.White) }
                                }
                                Text(ct.name)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Hauteur fixe raisonnable pour la liste (évite une liste de hauteur infinie dans l'onglet). */
private fun Modifier.weightedListHeight(): Modifier = this.fillMaxWidth().height(480.dp)

@Composable
private fun Editor(src: Source, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val isContact = src.pkg == null
    var base by remember(src) { mutableStateOf(src.original) }
    var scale by remember(src) { mutableFloatStateOf(0.6f) }
    var ox by remember(src) { mutableFloatStateOf(0.5f) }
    var oy by remember(src) { mutableFloatStateOf(0.5f) }
    var label by remember(src) { mutableStateOf(src.label) }
    var hideName by remember(src) { mutableStateOf(false) }
    var round by remember(src) { mutableStateOf(true) }
    var asWidget by remember(src) { mutableStateOf(true) }
    var callDirect by remember(src) { mutableStateOf(src.numbers.isNotEmpty()) }
    var numberIdx by remember(src) { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }

    val scaleNow by rememberUpdatedState(scale)

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bmp = loadImage(ctx, uri, 432)
            if (bmp != null) base = bmp else msg = "Image illisible"
        }
    }

    val shaped = remember(base, round, isContact) { if (isContact && round) circleCrop(base) else base }
    val preview = remember(shaped, scale, ox, oy) {
        composeIcon(shaped, scale, ox, oy, 300).asImageBitmap()
    }

    fun doCreate() {
        val intent: Intent? = when {
            src.pkg != null -> ctx.packageManager.getLaunchIntentForPackage(src.pkg)
            callDirect -> Intent(Intent.ACTION_CALL, Uri.fromParts("tel", src.numbers[numberIdx], null))
            else -> Intent(Intent.ACTION_VIEW, src.lookupUri)
        }
        val icon = composeIcon(shaped, scale, ox, oy, 432)
        val note = if (isContact && callDirect)
            " Si l'appel ne part pas au toucher, autorisez aussi « Passer des appels » dans les autorisations de Chronofond."
        else ""
        if (asWidget) {
            msg = createWidget(ctx, intent, icon, note)
            return
        }
        msg = createShortcut(ctx, src.key, intent, icon, if (hideName) "" else label, note)
    }

    val callPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) doCreate()
        else msg = "Autorisation Téléphone refusée : choisissez « Ouvrir la fiche » ou autorisez-la dans les réglages."
    }

    Column(
        Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(src.title, style = MaterialTheme.typography.titleMedium)
        Text(
            "Le carré représente la place normale d'une icône sur l'écran d'accueil. " +
                "Faites glisser l'icône dedans pour la placer.",
            style = MaterialTheme.typography.bodySmall
        )

        Box(
            Modifier
                .size(240.dp)
                .align(Alignment.CenterHorizontally)
                .border(2.dp, Color(0xFF5E35B1))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val free = size.width * (1f - scaleNow)
                        if (free > 20f) {
                            ox = (ox + drag.x / free).coerceIn(0f, 1f)
                            oy = (oy + drag.y / free).coerceIn(0f, 1f)
                        }
                    }
                }
        ) {
            Canvas(Modifier.size(240.dp)) {
                val cell = size.width / 12f
                for (i in 0 until 12) for (j in 0 until 12) {
                    val c = if ((i + j) % 2 == 0) Color(0xFFE0E0E0) else Color(0xFFF5F5F5)
                    drawRect(c, Offset(i * cell, j * cell), Size(cell, cell))
                }
            }
            Image(preview, null, Modifier.size(240.dp))
        }

        Text("Taille : ${(scale * 100).toInt()} %")
        Slider(value = scale, onValueChange = { scale = it }, valueRange = 0.2f..1f)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ox = 0.5f; oy = 0.5f }) { Text("Centrer") }
            OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) { Text("Changer l'image") }
            OutlinedButton(onClick = { base = src.original }) { Text("D'origine") }
        }

        if (isContact) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = round, onCheckedChange = { round = it })
                Text("Image ronde")
            }
            Text("Au toucher :", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = callDirect, onClick = { if (src.numbers.isNotEmpty()) callDirect = true })
                Text(
                    if (src.numbers.isEmpty()) "Appel direct (aucun numéro)" else "Appel direct",
                    modifier = Modifier.clickable { if (src.numbers.isNotEmpty()) callDirect = true }
                )
            }
            if (callDirect && src.numbers.size > 1) {
                src.numbers.forEachIndexed { i, n ->
                    Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = numberIdx == i, onClick = { numberIdx = i })
                        Text(n, modifier = Modifier.clickable { numberIdx = i })
                    }
                }
            } else if (callDirect && src.numbers.size == 1) {
                Text(src.numbers[0], modifier = Modifier.padding(start = 48.dp), style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !callDirect, onClick = { callDirect = false })
                Text("Ouvrir la fiche du contact", modifier = Modifier.clickable { callDirect = false })
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = asWidget, onCheckedChange = { asWidget = it })
            Text("Fond transparent (widget)")
        }
        Text(
            if (asWidget)
                "Méthode widget : l'icône est ajoutée comme un petit widget 1x1, sans fond ajouté par le lanceur. " +
                    "Le nom n'est pas affiché."
            else
                "Méthode raccourci : le lanceur peut ajouter un fond (blanc sur certains téléphones).",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Nom du raccourci") },
            singleLine = true,
            enabled = !hideName && !asWidget,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = hideName, onCheckedChange = { hideName = it })
            Text("Masquer le nom")
        }

        Button(
            onClick = {
                val needCall = isContact && callDirect &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) !=
                    PackageManager.PERMISSION_GRANTED
                if (needCall) callPerm.launch(Manifest.permission.CALL_PHONE) else doCreate()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Créer le raccourci") }

        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)

        OutlinedButton(onClick = { openPermissions(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text("Autorisation « Créer des raccourcis »")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Retour à la liste") }
    }
}

private fun createWidget(ctx: Context, launch: Intent?, icon: Bitmap, note: String): String {
    if (launch == null) return "Impossible de créer ce widget."
    IconWidgetProvider.savePending(ctx, icon, launch)
    return if (IconWidgetProvider.requestPin(ctx)) {
        "Demande envoyée. Validez l'ajout du widget s'il est demandé, puis placez-le où vous voulez. " +
            "Vous pouvez ensuite retirer l'icône d'origine de l'écran d'accueil." + note
    } else {
        "Votre lanceur n'ajoute pas le widget automatiquement : appuyez longuement sur l'écran d'accueil, " +
            "choisissez Widgets, puis « Icône Chronofond » (1x1) et placez-le." + note
    }
}

private fun createShortcut(
    ctx: Context, key: String, launch: Intent?, icon: Bitmap, label: String, note: String
): String {
    if (launch == null) return "Impossible de créer ce raccourci."
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
        return "Votre lanceur ne permet pas d'ajouter des raccourcis."
    }
    val name = label.trim().ifEmpty { " " }
    val info = ShortcutInfoCompat.Builder(ctx, "chronofond_${key}_${System.currentTimeMillis()}")
        .setShortLabel(name)
        .setIcon(IconCompat.createWithBitmap(icon))
        .setIntent(launch)
        .build()
    val ok = ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    return if (ok) {
        "Demande envoyée. Validez l'ajout s'il est demandé. Si rien n'apparaît sur l'écran d'accueil, " +
            "autorisez « Créer des raccourcis » avec le bouton ci-dessous, puis recommencez. " +
            "Vous pouvez ensuite retirer l'icône d'origine de l'écran d'accueil." + note
    } else {
        "Le lanceur a refusé la demande."
    }
}

private fun openPermissions(ctx: Context) {
    try {
        val i = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", ctx.packageName)
        ctx.startActivity(i)
    } catch (e: Exception) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            )
        }
    }
}
