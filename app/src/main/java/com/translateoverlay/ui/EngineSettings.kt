package com.translateoverlay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.translateoverlay.settings.Settings
import com.translateoverlay.settings.SettingsRepository
import com.translateoverlay.translate.SecretStore
import com.translateoverlay.translate.TranslationProvider
import com.translateoverlay.translate.TranslatorRouter
import kotlinx.coroutines.launch

/**
 * Translation engine choice. Online engines need the user's own API key, kept encrypted on the
 * device (Android Keystore) and never shown back in clear.
 */
@Composable
fun TranslationEngineSection(
    s: Settings,
    settings: SettingsRepository,
    secrets: SecretStore,
    router: TranslatorRouter,
) {
    val scope = rememberCoroutineScope()
    var keyInput by remember(s.provider) { mutableStateOf("") }
    var region by remember(s.azureRegion) { mutableStateOf(s.azureRegion) }
    var status by remember(s.provider) { mutableStateOf<String?>(null) }
    var keySaved by remember(s.provider) { mutableStateOf(router.isReady(s.provider)) }

    SectionTitle("Moteur de traduction")
    TranslationProvider.entries.forEach { p ->
        Row(
            Modifier.fillMaxWidth().clickable { settings.setProvider(p) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = s.provider == p, onClick = { settings.setProvider(p) })
            Text(p.label, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (!s.provider.online) {
        Text(
            "Fonctionne sans réseau, mais la qualité est limitée (passe par l'anglais). " +
                "Voir docs/TRANSLATION_ENGINES.md pour la comparaison.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    val keyName = router.keyName(s.provider) ?: return
    val company = when (s.provider) {
        TranslationProvider.NVIDIA -> "NVIDIA"
        TranslationProvider.AZURE -> "Microsoft"
        else -> "Google"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.provider == TranslationProvider.NVIDIA) {
            Text(
                "Clé gratuite, sans carte bancaire : créez un compte sur build.nvidia.com puis « Get API Key ». " +
                    "La traduction hors-ligne ML Kit s'affiche aussitôt, puis chaque bloc est remplacé par celle de " +
                    "Nemotron Ultra (secours : Nemotron Super). Au plus 20 requêtes par minute (~3 par écran).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Le texte affiché est envoyé à $company uniquement quand vous touchez la bulle. Sans clé, sans réseau " +
                "ou en cas d'erreur, la traduction hors-ligne ML Kit reste affichée.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(if (keySaved) "Clé API enregistrée (saisir pour remplacer)" else "Clé API") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (s.provider == TranslationProvider.AZURE) {
            OutlinedTextField(
                value = region,
                onValueChange = { region = it },
                label = { Text("Région de la ressource (ex. francecentral, vide si globale)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (keyInput.isNotBlank()) {
                    secrets.put(keyName, keyInput)
                    router.onKeyChanged()
                }
                if (s.provider == TranslationProvider.AZURE) settings.setAzureRegion(region)
                keyInput = ""
                keySaved = router.isReady(s.provider)
                status = if (keySaved) "Clé enregistrée (chiffrée sur l'appareil)" else "Aucune clé"
            }) { Text("Enregistrer") }
            OutlinedButton(enabled = keySaved, onClick = {
                status = "Test en cours…"
                scope.launch { status = router.test(s.provider, s.targetLanguage, region) }
            }) { Text("Tester") }
            TextButton(enabled = keySaved, onClick = {
                secrets.put(keyName, null)
                router.onKeyChanged()
                keySaved = false
                status = "Clé supprimée"
            }) { Text("Supprimer") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
