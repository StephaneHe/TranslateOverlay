# TranslateOverlay

Bulle flottante Android qui traduit **tout le texte visible à l'écran** (y compris dans les images) et
le **remplace en surimpression, au même endroit et dans un style proche** (couleurs, taille, alignement,
gras). Langue source détectée automatiquement, langue cible configurable (français par défaut).
Traduction, détection de langue et OCR **sur l'appareil** (Google ML Kit) : pas de clé API, hors-ligne
une fois les modèles téléchargés.

Version 1.0.0 — Android 8+ (OCR et imitation des couleurs : Android 11+). Étude technique : [docs/PLAN.md](docs/PLAN.md).

## Permissions

| Permission | Usage |
|---|---|
| Service d'accessibilité | bulle au-dessus des apps, détection de l'app au premier plan (exclusions), lecture du texte et capture d'écran **uniquement au tap sur la bulle** |
| Internet / état réseau | téléchargement des modèles de traduction ML Kit (~30 Mo par langue, une fois) |

Aucune autre permission (ni `SYSTEM_ALERT_WINDOW`, ni MediaProjection, ni accès aux statistiques d'usage).

## Activation

1. Installer l'APK (`app/build/outputs/apk/debug/app-debug.apk`) puis ouvrir TranslateOverlay.
2. « Activer le service » → accepter la divulgation → Accessibilité › Applications installées ›
   TranslateOverlay → activer.
3. **Android 13+ avec APK hors Play Store** : si l'option est grisée, Infos de l'appli › menu ⋮ ›
   « Autoriser les paramètres restreints », puis recommencer l'étape 2.
4. Toucher la bulle dans n'importe quelle app. Toucher un bloc traduit affiche l'original ; toucher
   ailleurs ferme l'overlay. Appui long sur la bulle : ouvrir les paramètres.

Paramètres : langue cible, applications exclues (bulle masquée), OCR et écriture OCR, modèles
téléchargés, Wi-Fi uniquement, taille/opacité de la bulle, version.

## Build

```
JAVA_HOME=<JDK 17> ./gradlew assembleDebug testDebugUnitTest
```
