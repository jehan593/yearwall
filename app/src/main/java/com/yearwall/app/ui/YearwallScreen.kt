package com.yearwall.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yearwall.app.automation.CrashGuard
import com.yearwall.app.automation.StatusChecker
import com.yearwall.app.automation.StatusItem
import com.yearwall.app.automation.YearwallEngine
import com.yearwall.app.data.YearwallStore
import com.yearwall.app.ui.theme.nord11
import com.yearwall.app.ui.theme.nord14
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * The status/setup screen — `yearwall status` + `yearwall setup` + `yearwall rm`, kept in the
 * same per-piece reporting structure as the original. Design knobs are the shell script's only
 * two tunables (LINE_Y, LINE_W) plus the grid column count, at exactly the shell defaults.
 */
@Composable
fun YearwallScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(emptyList<StatusItem>()) }
    var prefs by remember { mutableStateOf(YearwallStore.Prefs()) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            // The state below starts at Prefs() defaults, so it must be rehydrated from the
            // store on every composition — otherwise a process kill/recreation leaves the
            // controls showing Lock/21/29 while the engine keeps using the stored values.
            runSafely { prefs = YearwallStore.read(context) }
            runSafely { status = StatusChecker.collect(context) }
            runSafely { preview = loadPreview(context) }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        // -- Preview ---------------------------------------------------------------------------
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Preview", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                preview?.let {
                    Image(
                        it,
                        contentDescription = "Current yearwall render",
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } ?: Text(
                    "Nothing rendered yet — press Apply now.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // -- Actions ---------------------------------------------------------------------------
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        runSafely { YearwallEngine.apply(context, force = true) }
                        runSafely { status = StatusChecker.collect(context) }
                        runSafely { preview = loadPreview(context) }
                        busy = false
                    }
                },
                enabled = !busy
            ) { Text(if (busy) "Working…" else "Apply now") }
            OutlinedButton(onClick = { refresh() }) { Text("Refresh status") }
        }

        // -- Design knobs (columns / whole-content Y — everything else derives automatically) ----
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Automation settings", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Text(
                    "Changes apply on the next wallpaper update. Apply now to use them immediately.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // -- Screen target (termux-wallpaper -l = lock only; these are the equivalents) --
                Text("Apply to", fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TargetChoice("Lock", prefs.target == "lock") {
                        prefs = prefs.copy(target = "lock")
                        scope.launch { YearwallStore.setTarget(context, "lock") }
                    }
                    TargetChoice("Home", prefs.target == "home") {
                        prefs = prefs.copy(target = "home")
                        scope.launch { YearwallStore.setTarget(context, "home") }
                    }
                    TargetChoice("Both", prefs.target == "both") {
                        prefs = prefs.copy(target = "both")
                        scope.launch { YearwallStore.setTarget(context, "both") }
                    }
                }
                // -- Design knobs: only two, on purpose. "Grid columns" = grid width & dot
                // density (dot/spacing/text sizes all derive from it); "Content Y" moves the
                // whole composite — header, separator AND grid — as one block.
                Stepper(
                    label = "Grid columns",
                    value = prefs.columns,
                    range = 5..25
                ) { next ->
                    prefs = prefs.copy(columns = next)
                    scope.launch { YearwallStore.setColumns(context, next) }
                }
                Stepper(
                    label = "Content Y",
                    value = prefs.contentYPercent,
                    range = 10..70,
                    suffix = "%"
                ) { next ->
                    prefs = prefs.copy(contentYPercent = next)
                    scope.launch { YearwallStore.setContentYPercent(context, next) }
                }
                // Reset lives at the card's bottom, full-width for an easy thumb target.
                TextButton(
                    onClick = {
                        scope.launch {
                            YearwallStore.resetAutomation(context)
                            prefs = YearwallStore.read(context)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Reset to defaults") }
            }
        }

        // -- Remove (the `rm` command) -------------------------------------------------------------
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Remove", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Text(
                    "Stops the automatic updates and sets the default Nord wallpaper.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { confirmRemove = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = nord11,
                        contentColor = MaterialTheme.colorScheme.background
                    )
                ) { Text("Remove yearwall") }
            }
        }

        // -- Status (the `status` command) — last, below the settings and remove cards -----------
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Status", fontWeight = FontWeight.Medium, fontSize = 16.sp)
            status.forEach { StatusRow(it) }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove yearwall?") },
            text = {
                Text(
                    "Yearwall will stop updating and set the default Nord wallpaper."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    busy = true
                    scope.launch {
                        runSafely { YearwallEngine.remove(context) }
                        runSafely { status = StatusChecker.collect(context) }
                        runSafely { preview = loadPreview(context) }
                        busy = false
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            }
        )
    }
}

/** Selected state of the Lock/Home/Both target trio: filled when chosen, outlined otherwise. */
@Composable
private fun TargetChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    if (selected) {
        Button(onClick = onSelect) { Text(label) }
    } else {
        OutlinedButton(onClick = onSelect) { Text(label) }
    }
}

@Composable
private fun StatusRow(item: StatusItem) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .padding(top = 14.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(if (item.ok) nord14 else nord11)
        )
        Column {
            Text(item.title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                item.detail,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    suffix: String = "",
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, fontSize = 14.sp)
            Text(
                "$value$suffix",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { onChange((value - step).coerceIn(range)) },
                enabled = value > range.first
            ) { Text("−") }
            TextButton(
                onClick = { onChange((value + step).coerceIn(range)) },
                enabled = value < range.last
            ) { Text("+") }
        }
    }
}

// Decoded at ~half scale for the preview card only — the on-disk render stays untouched.
private suspend fun loadPreview(context: android.content.Context): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val file = File(context.filesDir, YearwallEngine.IMAGE_FILE)
        if (!file.exists()) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth > 512 * sample) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )?.asImageBitmap()
    }

// Nothing a user taps should ever crash the process; the Status list is the error surface.
private suspend fun runSafely(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: Throwable) { }
}
