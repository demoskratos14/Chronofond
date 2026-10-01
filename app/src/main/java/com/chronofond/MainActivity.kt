package com.chronofond

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        Scheduler.startGuards(this)
        Scheduler.repairNow(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(R.drawable.app_background),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Surface(Modifier.fillMaxSize(), color = Color.Transparent) { App() }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val flow = remember { ctx.configFlow }
    val cfg by flow.collectAsState(initial = AppConfig())
    var tab by remember { mutableIntStateOf(0) }
    var showHelp by remember { mutableStateOf(false) }
    if (showHelp) HelpDialog { showHelp = false }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Chronofond") },
                actions = { TextButton(onClick = { showHelp = true }) { Text("Aide") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Accueil") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Verrouillage") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Raccourcis") })
            }
            when (tab) {
                0 -> ScreenPanel(Target.HOME, cfg.home, cfg)
                1 -> ScreenPanel(Target.LOCK, cfg.lock, cfg)
                else -> ShortcutsTab()
            }
        }
    }
}

private val INTERVALS = listOf(
    15 to "15 min", 30 to "30 min", 60 to "1 h", 180 to "3 h", 360 to "6 h", 1440 to "24 h"
)
private val COUNTS = Layouts.counts.map { it to (if (it == 1) "1 photo" else "$it") }

private val COLOR_LANDSCAPE = Color(0xFF64B5F6)
private val COLOR_PORTRAIT = Color(0xFF81C784)
private val COLOR_SQUARE = Color(0xFFBDBDBD)

@Composable
fun ScreenPanel(t: Target, sc: ScreenConfig, cfg: AppConfig) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun save(f: (ScreenConfig) -> ScreenConfig) {
        scope.launch {
            val n = ctx.updateScreen(t, f)
            Scheduler.schedule(ctx, t, n)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        scope.launch(Dispatchers.IO) {
            uris.forEach { PhotoRatios.of(ctx, it.toString()) }
        }
        save { old ->
            old.copy(
                uris = (old.uris + uris.map { it.toString() }).distinct(),
                current = emptyList()
            )
        }
    }

    val dm = ctx.resources.displayMetrics
    val screenRatio = min(dm.widthPixels, dm.heightPixels).toFloat() /
        max(dm.widthPixels, dm.heightPixels)

    val sameAsHome = t == Target.LOCK && cfg.lockSameAsHome
    val photos = if (sameAsHome) cfg.home.uris else sc.uris
    var gap by remember(t, sc.gapPx) { mutableFloatStateOf(sc.gapPx.toFloat()) }
    var margin by remember(t, sc.topMarginPct) { mutableFloatStateOf(sc.topMarginPct.toFloat()) }
    var status by remember(t) { mutableStateOf(Status.get(ctx, t)) }
    LaunchedEffect(t) {
        while (true) {
            status = Status.get(ctx, t)
            delay(1000)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(96.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.88f)
            ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SwitchRow("Rotation activée", sc.enabled) { v -> save { it.copy(enabled = v) } }

                if (t == Target.LOCK) {
                    SwitchRow("Mêmes photos que l'accueil", cfg.lockSameAsHome) { v ->
                        scope.launch { ctx.setLockSame(v) }
                    }
                }

                Text("Changer toutes les…", style = MaterialTheme.typography.titleSmall)
                ChipRow(INTERVALS, sc.intervalMin) { v -> save { it.copy(intervalMin = v) } }

                Text("Photos par image (mosaïque)", style = MaterialTheme.typography.titleSmall)
                ChipRow(COUNTS, sc.layout.count) { n ->
                    if (n != sc.layout.count) {
                        save {
                            it.copy(
                                layoutId = Layouts.forCount(n).first().id,
                                current = emptyList(), cellPtr = 0
                            )
                        }
                    }
                }

                if (sc.layout.count > 1) {
                    Text("Disposition", style = MaterialTheme.typography.titleSmall)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Layouts.forCount(sc.layout.count).forEach { l ->
                            LayoutThumb(l, screenRatio, l.id == sc.layoutId) {
                                save { it.copy(layoutId = l.id, current = emptyList(), cellPtr = 0) }
                            }
                        }
                    }
                    Text(
                        "Bleu : photo paysage · Vert : photo portrait · Gris : n'importe laquelle. " +
                            "Chaque case reçoit une photo de la bonne orientation.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                SwitchRow("Ordre aléatoire", sc.shuffle) { v -> save { it.copy(shuffle = v) } }

                if (sc.layout.count > 1) {
                    SwitchRow("Une seule case change à chaque fois", sc.singleCell) { v ->
                        save { it.copy(singleCell = v) }
                    }
                    Text("Espacement : ${gap.toInt()} px")
                    Slider(
                        value = gap,
                        onValueChange = { gap = it },
                        valueRange = 0f..40f,
                        onValueChangeFinished = { save { it.copy(gapPx = gap.toInt()) } }
                    )
                    if (t == Target.LOCK) {
                        Text("Marge en haut (horloge) : ${margin.toInt()} %")
                        Slider(
                            value = margin,
                            onValueChange = { margin = it },
                            valueRange = 0f..30f,
                            onValueChangeFinished = {
                                save { it.copy(topMarginPct = margin.toInt()) }
                            }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { picker.launch(arrayOf("image/*")) },
                        enabled = !sameAsHome
                    ) { Text("Ajouter des photos") }
                    OutlinedButton(onClick = { Scheduler.runNow(ctx, t) }) {
                        Text("Changer maintenant")
                    }
                }
                Text("Dernier essai : $status", style = MaterialTheme.typography.bodySmall)
                Text("${photos.size} photo(s)", style = MaterialTheme.typography.titleSmall)
            }
            }
        }

        items(photos) { uri ->
            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (!sameAsHome) {
                    Text(
                        "✕",
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(Color(0x99000000))
                            .clickable {
                                save { it.copy(uris = it.uris - uri, current = emptyList()) }
                            }
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LayoutThumb(l: MosaicLayout, screenRatio: Float, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .height(120.dp)
            .aspectRatio(screenRatio)
            .clip(shape)
            .background(Color.White)
            .border(if (selected) 3.dp else 1.dp, if (selected) primary else Color.LightGray, shape)
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val g = 2.dp.toPx()
            l.cells.forEach { c ->
                val color = when (shapeOf(c.w * screenRatio / c.h)) {
                    Shape.LANDSCAPE -> COLOR_LANDSCAPE
                    Shape.PORTRAIT -> COLOR_PORTRAIT
                    Shape.SQUARE -> COLOR_SQUARE
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(c.x * size.width + g / 2, c.y * size.height + g / 2),
                    size = Size(c.w * size.width - g, c.h * size.height - g),
                    cornerRadius = CornerRadius(4.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (v, label) ->
            FilterChip(
                selected = v == selected,
                onClick = { onSelect(v) },
                label = { Text(label) }
            )
        }
    }
}
