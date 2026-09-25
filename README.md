# TranslateOverlay

Bulle flottante Android qui traduit **tout le texte visible à l'écran** (y compris dans les images) et
le **remplace en surimpression, au même endroit et dans un style proche** (couleurs, taille, alignement,
gras). Langue source détectée automatiquement, langue cible configurable (français par défaut).
Traduction, détection de langue et OCR **sur l'appareil** (Google ML Kit) : pas de clé API, hors-ligne
une fois les modèles téléchargés.

Version 1.4.1 — Android 8+ (OCR et imitation des couleurs : Android 11+). Étude technique : [docs/PLAN.md](docs/PLAN.md).

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

## Moteur de traduction

Par défaut **ML Kit** (hors-ligne, qualité limitée : passe par l'anglais). Pour une bien meilleure
qualité (mesures : `docs/TRANSLATION_ENGINES.md`), choisir dans Paramètres › Moteur de traduction :

- **Microsoft Azure Translator** (clé + carte bancaire ; non retenu pour l'instant) : créer une ressource « Translator » niveau gratuit F0
  (2 M caractères/mois) sur portal.azure.com, copier la clé et la région ;
- **Google Cloud Translation** : activer l'API « Cloud Translation » dans un projet avec
  facturation (500 k caractères/mois gratuits), créer une clé API.

La clé est chiffrée sur l'appareil (Android Keystore), jamais sauvegardée. Sans clé, sans réseau
ou en cas d'erreur, la traduction retombe automatiquement sur ML Kit.

## Hébreu (et langues RTL)

- **Cible** : choisir « Hébreu » dans Langue cible (modèle ~30 Mo téléchargé une fois). L'overlay
  s'affiche de droite à gauche, aligné en miroir de la source, mots latins/chiffres intégrés
  correctement ordonnés, police système Noto Sans Hebrew.
- **Source** : le texte exposé par les applications (pages web, apps natives) est lu directement. Le
  texte **dans les images** est lu en mode OCR « Automatique » (défaut) : l'OCR latin détecte ses
  lignes douteuses et les fait relire par Tesseract hébreu (ou les modèles CJK/devanagari) ; aucun
  réglage à faire, y compris pour une image hébraïque dans une page en anglais.

## Diagnostic

`adb logcat -s TO-Diag TranslateOverlay` (build debug) : capture, blocs OCR avec confiance, blocs
absorbés/filtrés, langue détectée et décision pour chaque bloc, durées.

## Build

```
JAVA_HOME=<JDK 17> ./gradlew assembleDebug testDebugUnitTest
```
