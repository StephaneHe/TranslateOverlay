package com.translateoverlay.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.translateoverlay.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcludedAppsScreen(settings: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val s by settings.settings.collectAsState()
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Applications exclues") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "La bulle est masquée automatiquement quand une application cochée est au premier plan.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Rechercher") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                // Excluded apps first, then alphabetical.
                val filtered = list
                    .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
                    .sortedWith(compareBy({ it.packageName !in s.excludedPackages }, { it.label.lowercase() }))
                LazyColumn {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = app.packageName in s.excludedPackages
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { settings.setExcluded(app.packageName, !checked) }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (app.icon != null) Image(app.icon, null, Modifier.size(36.dp))
                            else Spacer(Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                            Checkbox(checked = checked, onCheckedChange = { settings.setExcluded(app.packageName, it) })
                        }
                    }
                }
            }
        }
    }
}

private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val iconPx = (36 * context.resources.displayMetrics.density).toInt()
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(intent, 0)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != context.packageName }
        .map { info ->
            InstalledApp(
                packageName = info.activityInfo.packageName,
                label = info.loadLabel(pm).toString(),
                icon = runCatching { info.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull(),
            )
        }
        .sortedBy { it.label.lowercase() }
}
