package com.translateoverlay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.translateoverlay.settings.SettingsRepository
import com.translateoverlay.translate.TranslationEngine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(engine: TranslationEngine, settings: SettingsRepository, onBack: () -> Unit) {
    val s by settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        models = runCatching { engine.downloadedModels().sortedBy { TranslationEngine.displayName(it) } }
            .getOrDefault(emptyList())
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modèles de langue") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Les modèles sont téléchargés automatiquement la première fois qu'une langue est rencontrée. " +
                    "Vous pouvez aussi préparer la langue cible à l'avance pour un usage hors-ligne.",
                style = MaterialTheme.typography.bodySmall,
            )
            val targetName = TranslationEngine.displayName(s.targetLanguage)
            Button(
                enabled = !busy && s.targetLanguage !in models,
                onClick = {
                    scope.launch {
                        busy = true
                        message = runCatching {
                            if (!engine.canDownloadNow(s.wifiOnlyDownloads)) error("Pas de connexion adaptée (Wi-Fi requis ?)")
                            engine.download(s.targetLanguage, s.wifiOnlyDownloads)
                            "Modèle $targetName téléchargé"
                        }.getOrElse { "Échec : ${it.message}" }
                        refresh()
                        busy = false
                    }
                },
            ) { Text(if (s.targetLanguage in models) "$targetName : déjà téléchargé" else "Télécharger $targetName") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            SectionTitle("Téléchargés (${models.size})")
            LazyColumn {
                items(models, key = { it }) { code ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${TranslationEngine.displayName(code)} ($code)", Modifier.weight(1f))
                        IconButton(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    message = runCatching { engine.delete(code); "Modèle supprimé" }
                                        .getOrElse { "Échec : ${it.message}" }
                                    refresh()
                                    busy = false
                                }
                            },
                        ) { Icon(Icons.Default.Delete, "Supprimer") }
                    }
                }
            }
        }
    }
}
