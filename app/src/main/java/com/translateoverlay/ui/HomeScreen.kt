package com.translateoverlay.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.translateoverlay.BuildConfig
import com.translateoverlay.settings.OcrScript
import com.translateoverlay.settings.SettingsRepository
import com.translateoverlay.translate.SecretStore
import com.translateoverlay.translate.TranslationEngine
import com.translateoverlay.translate.TranslatorRouter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: SettingsRepository,
    engine: TranslationEngine,
    secrets: SecretStore,
    router: TranslatorRouter,
    serviceEnabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    val s by settings.settings.collectAsState()
    var showDisclosure by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showScriptPicker by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("TranslateOverlay") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ServiceCard(serviceEnabled, onEnable = { showDisclosure = true }, onOpenAppInfo = onOpenAppInfo)

            SectionTitle("Traduction")
            ClickableRow(
                title = "Langue cible",
                subtitle = TranslationEngine.displayName(s.targetLanguage),
                onClick = { showLanguagePicker = true },
            )
            ClickableRow("Modèles de langue", "Téléchargés sur l'appareil, utilisables hors-ligne") {
                onNavigate(Screen.MODELS)
            }
            SwitchRow(
                "Télécharger les modèles en Wi-Fi uniquement",
                "Un modèle pèse environ 30 Mo",
                s.wifiOnlyDownloads,
                settings::setWifiOnlyDownloads,
            )

            TranslationEngineSection(s, settings, secrets, router)

            SectionTitle("Capture du texte")
            SwitchRow(
                "Lire aussi le texte dans les images (OCR)",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Capture d'écran + reconnaissance de texte, sert aussi à imiter les couleurs"
                else "Nécessite Android 11 ou plus récent",
                s.ocrEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                settings::setOcrEnabled,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            )
            ClickableRow("Écriture pour l'OCR", s.ocrScript.label, enabled = s.ocrEnabled) {
                showScriptPicker = true
            }

            SectionTitle("Bulle flottante")
            SwitchRow("Afficher la bulle", "Toucher : traduire · appui long : ouvrir l'app", s.bubbleEnabled, settings::setBubbleEnabled)
            ClickableRow(
                "Applications exclues",
                if (s.excludedPackages.isEmpty()) "Aucune" else "${s.excludedPackages.size} application(s) — bulle masquée",
            ) { onNavigate(Screen.EXCLUDED_APPS) }
            SliderRow("Taille : ${s.bubbleSizeDp} dp", s.bubbleSizeDp.toFloat(), 36f..80f) {
                settings.setBubbleSizeDp(it.roundToInt())
            }
            SliderRow("Opacité : ${(s.bubbleOpacity * 100).roundToInt()} %", s.bubbleOpacity, 0.3f..1f) {
                settings.setBubbleOpacity(it)
            }

            SectionTitle("À propos")
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                    "OCR et détection de langue sur l'appareil (ML Kit, Tesseract). Traduction : ML Kit " +
                    "hors-ligne, ou le moteur en ligne choisi ci-dessus (le texte lui est alors envoyé). " +
                    "Aucun contenu d'écran n'est conservé.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("Utilisation du service d'accessibilité") },
            text = {
                Text(
                    "TranslateOverlay utilise l'API d'accessibilité d'Android pour :\n" +
                        "• afficher la bulle flottante au-dessus des autres applications ;\n" +
                        "• détecter l'application au premier plan afin de masquer la bulle dans les applications exclues ;\n" +
                        "• lire le texte affiché et faire une capture d'écran, uniquement quand vous touchez la bulle, pour le traduire.\n\n" +
                        "Par défaut, tout est traité sur l'appareil. Si vous choisissez un moteur de traduction en " +
                        "ligne (Azure ou Google), le texte à traduire est envoyé à ce seul service au moment du " +
                        "toucher. Rien n'est collecté ni conservé par l'application.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showDisclosure = false; onOpenAccessibility() }) { Text("J'accepte") }
            },
            dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text("Refuser") } },
        )
    }

    if (showLanguagePicker) {
        PickerDialog(
            title = "Langue cible",
            options = engine.supportedLanguages,
            selected = s.targetLanguage,
            label = { TranslationEngine.displayName(it) },
            onPick = { settings.setTargetLanguage(it); showLanguagePicker = false },
            onDismiss = { showLanguagePicker = false },
        )
    }
    if (showScriptPicker) {
        PickerDialog(
            title = "Écriture pour l'OCR",
            options = OcrScript.entries.toList(),
            selected = s.ocrScript,
            label = { it.label },
            onPick = { settings.setOcrScript(it); showScriptPicker = false },
            onDismiss = { showScriptPicker = false },
        )
    }
}

@Composable
private fun ServiceCard(enabled: Boolean, onEnable: () -> Unit, onOpenAppInfo: () -> Unit) {
    val colors = if (enabled) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (enabled) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (enabled) "Service actif — la bulle est disponible" else "Service d'accessibilité désactivé",
                    fontWeight = FontWeight.Bold,
                )
            }
            if (!enabled) {
                Text("Activez « TranslateOverlay » dans Paramètres › Accessibilité › Applications installées.")
                Button(onClick = onEnable) { Text("Activer le service") }
                if (MainActivity.restrictedSettingsApply) {
                    Text(
                        "Option grisée ? (Android 13+, APK installé hors Play Store) : ouvrez Infos de l'appli, " +
                            "menu ⋮ › « Autoriser les paramètres restreints », puis réessayez.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onOpenAppInfo) { Text("Ouvrir Infos de l'appli") }
                }
            } else {
                OutlinedButton(onClick = onEnable) { Text("Réglages d'accessibilité") }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun ClickableRow(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun <T> PickerDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options) { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = { onPick(option) })
                        Text(label(option))
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } },
    )
}
