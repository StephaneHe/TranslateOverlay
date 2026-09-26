# TranslateOverlay — Étude technique et plan

_Rédigé le 2026-09-25._

## 1. Besoin

> « un bouton flottant sur tout le reste (sauf les applications qui peuvent être paramétrées dans les
> paramètres) et qui permet de traduire en overlay tout type de texte depuis n'importe quelle langue qui
> devra être automatiquement détectée. l'overlay tentera de remplacer le texte de la langue source par le
> texte de la langue destination (paramétrable aussi, par défaut le français) dans le même style. »

Exigences :

| # | Exigence | Remarque |
|---|----------|----------|
| E1 | Bulle flottante déplaçable au-dessus de toutes les apps | |
| E2 | Bulle masquée quand l'app au premier plan est exclue | liste configurable (apps installées, cases à cocher) |
| E3 | Capture de *tout* texte visible, y compris dans les images | |
| E4 | Détection automatique de la langue source | par bloc |
| E5 | Traduction vers une langue cible paramétrable (défaut : français) | hors-ligne privilégié, sans clé API |
| E6 | Overlay remplaçant chaque bloc au même endroit, dans le même style | taille, couleurs, alignement, gras |
| E7 | Fermeture de l'overlay par tap/geste | |
| E8 | Écran Paramètres + version visible | |

## 2. Comparatif des approches

### 2.1 Capture du texte

| Approche | Avantages | Inconvénients |
|---|---|---|
| **A. AccessibilityService (arbre de nœuds)** | Texte exact (pas d'erreur OCR), limites (bounds) exactes en coordonnées écran, toutes écritures/langues, instantané, aucune autorisation supplémentaire | Ne voit pas le texte dessiné dans des images, jeux, canvas, vidéos ; pas de couleur/taille de police directement exploitables ; certaines apps exposent mal leur arbre |
| **B. MediaProjection + OCR** | Voit tout pixel à l'écran (images incluses) ; permet d'échantillonner les couleurs | Boîte de consentement système **à chaque session** (Android 14 : jeton à usage unique, service de premier plan `mediaProjection` obligatoire, indicateur de partage d'écran) ; erreurs OCR ; OCR limité à une famille d'écritures par modèle ; nécessite en plus `SYSTEM_ALERT_WINDOW` pour la bulle et l'accès aux statistiques d'usage pour l'app au premier plan |
| **C. Hybride AccessibilityService + `takeScreenshot()` + OCR** ✅ | Combine A et B : texte exact via l'arbre, OCR sur la capture pour le texte dans les images, couleurs échantillonnées sur la capture. `AccessibilityService.takeScreenshot()` (API 30+) **ne demande aucun consentement par capture**. Le même service fournit : fenêtres overlay `TYPE_ACCESSIBILITY_OVERLAY` (pas besoin de `SYSTEM_ALERT_WINDOW`) et événements de changement de fenêtre (app au premier plan, pas besoin de `PACKAGE_USAGE_STATS`) | Une seule autorisation mais « sensible » (service d'accessibilité) : politique Play Store stricte, « paramètres restreints » sur Android 13+ en sideload ; capture refusée pour les fenêtres `FLAG_SECURE` (banque, DRM) ; `takeScreenshot` limité à ~1 appel/s |

**Choix : C (hybride).** Une seule autorisation couvre E1, E2, E3 et la collecte d'indices de style. Sur
Android < 11 (API < 30), dégradation gracieuse : arbre d'accessibilité seul, style par défaut.

### 2.2 Fusion arbre + OCR

Les blocs de l'arbre sont prioritaires (texte exact). Un bloc OCR est écarté seulement s'il est
couvert à plus de 50 % par des blocs de l'arbre **qui lisent le même texte** (comparaison par mots) :
la géométrie seule ne suffit pas, le cadre d'un conteneur web peut englober une image (bug 1.1.0 :
texte des images supprimé). Logique pure `BlockMerger`, testée unitairement.

### 2.3 Traduction

| Option | Hors-ligne | Clé API | Qualité | Coût |
|---|---|---|---|---|
| **ML Kit Translation (on-device)** ✅ | Oui (modèle ~30 Mo/langue téléchargé une fois) | Non | Correcte (NMT compact), 59 langues | Gratuit |
| Google Cloud Translation / DeepL | Non | Oui | Très bonne | Payant au-delà du quota, données envoyées à un tiers |
| LLM local (Gemma via MediaPipe/LiteRT) | Oui | Non | Bonne mais lente, modèle 1–3 Go, RAM | Latence de plusieurs secondes par écran |

**Mise à jour 1.5.0 (2026-09-26)** : moteur en ligne NVIDIA (Nemotron 3 Ultra, secours Nemotron 3
Super, clé personnelle gratuite) affiché **par-dessus** la traduction ML Kit immédiate, bloc par
bloc ; ML Kit reste le dernier recours hors-ligne. Voir `docs/TRANSLATION_ENGINES.md` §7.

**Choix initial : ML Kit Translation.** Gratuit, sans clé, hors-ligne après téléchargement du modèle, latence de
quelques dizaines de ms par bloc. Le pivot se fait par l'anglais en interne (ML Kit gère). L'interface
`TextTranslator` permet de brancher plus tard un moteur en ligne (DeepL) ou un LLM local.

### 2.4 Détection de langue

**ML Kit Language Identification** (modèle embarqué, ~100 langues, hors-ligne). Détection **par bloc**
(`identifyPossibleLanguages`, seuil de confiance 0,5). Les blocs courts ou ambigus (« und ») héritent de la
**langue dominante de l'écran** (vote pondéré par la longueur de texte — logique pure `LanguageVoter`,
testée). Les blocs déjà dans la langue cible, ou non traduisibles (nombres, URL, ponctuation, heures),
sont laissés tels quels (`TranslatableFilter`, testé).

### 2.5 OCR

**ML Kit Text Recognition v2** : modèle latin embarqué (hors-ligne garanti). Écritures chinoise,
japonaise, coréenne, devanagari via les modules Google Play services (téléchargés à la demande par
GMS, APK plus léger) — choix de l'écriture OCR dans les Paramètres. Le texte non latin reste de toute
façon capté via l'arbre d'accessibilité dans les apps natives.

### 2.5 bis OCR de l'hébreu (ajout 1.1.0)

ML Kit Text Recognition v2 ne couvre pas l'alphabet hébreu (latin, chinois, japonais, coréen,
devanagari uniquement). Options évaluées :

| Option | Verdict |
|---|---|
| Documenter la limite (texte de l'arbre seulement) | insuffisant pour les images, menus photographiés, vidéos |
| API cloud (Google Vision, Azure) | clé API + envoi de l'écran à un tiers : contraire aux principes du projet |
| **Tesseract 5 LSTM on-device** (Tesseract4Android 4.7.0, `tessdata_fast` heb) ✅ | hors-ligne, 0,96 Mo de données embarquées, +6,6 Mo de bibliothèque arm64, **~450 ms** par écran mesurés sur Dimensity 1080 |

Tesseract est exposé comme écriture OCR « Hébreu ». Garde-fou : quand l'écriture dominante de
l'écran n'est pas lisible par le modèle OCR choisi, les blocs issus de l'OCR sont ignorés et
l'alignement mesuré sur ses lignes n'est pas utilisé.

### 2.5 ter OCR automatique multi-alphabet (ajout 1.2.0)

Constat sur appareil : le modèle latin de ML Kit renvoie du texte incohérent mais **avec une faible
confiance** sur les alphabets qu'il ne lit pas (hébreu lu « T272 Dh TOPn yYNn », confiance 0,33)
alors que du vrai texte latin obtient 0,77–0,92. Stratégie retenue (coût 0,3–0,9 s mesuré) :

1. OCR latin sur tout l'écran (toujours) ;
2. alphabets présents dans le texte d'accessibilité (≥ 12 lettres) → passe complète de leur modèle ;
3. lignes latines douteuses (< 0,5) en forme de ligne (≥ 4 caractères) → relecture du recadrage
   par Tesseract hébreu puis ML Kit chinois/japonais/coréen/devanagari ; score = confiance × part de
   lettres dans l'alphabet du modèle ; un alphabet découvert déclenche une passe complète ;
4. les lignes d'un modèle spécialisé doivent être plausibles (≥ 3 lettres de l'alphabet, lettres
   majoritaires) et ne pas recouvrir une zone lue avec confiance par le modèle latin.

Écarté : lancer tous les modèles à chaque fois (≈ 2–3 s, faux positifs sur les icônes).

### 2.6 Reproduction du style

Sources d'information :

- **Couleurs** : histogramme des pixels de la capture dans la zone du bloc → couleur dominante = fond,
  couleur significative la plus contrastée = texte (`ColorEstimator`, testé).
- **Taille** : hauteur moyenne des lignes (OCR) ou hauteur du bloc / nombre de lignes (arbre) × 0,75.
- **Gras** : ratio de pixels « encre » dans les lignes (seuil empirique) (`ColorEstimator`).
- **Alignement** : dispersion des bords gauches / droits / centres des lignes OCR (`AlignmentEstimator`,
  testé) ; gravité du nœud n'est pas exposée par l'accessibilité → gauche par défaut.
- **Sens d'écriture** : direction de paragraphe forcée selon la langue cible (RTL pour hébreu,
  arabe…), alignement converti en logique puis appliqué dans le sens cible → miroir automatique
  (`Bidi`, testé). Le bidi Unicode d'Android ordonne les mots latins et chiffres intégrés.
- **Ajustement** : la traduction est souvent plus longue (FR ≈ +15–30 % vs EN) → réduction progressive de
  la taille jusqu'à tenir dans le cadre, puis extension verticale limitée si nécessaire (`TextFitter`,
  logique de recherche testée avec une fonction de mesure injectée).

Le fond est peint opaque de la couleur estimée pour masquer le texte d'origine, puis le texte traduit est
dessiné par-dessus avec `StaticLayout`.

### 2.7 Détection de l'app au premier plan (exclusions)

| Option | Autorisation | Verdict |
|---|---|---|
| `UsageStatsManager` | `PACKAGE_USAGE_STATS` (réglage spécial) + polling | Redondant |
| **Événements `TYPE_WINDOW_STATE_CHANGED` + `getWindows()` du service** ✅ | Aucune en plus | Temps réel, gratuit |

On retient le paquet de la fenêtre `TYPE_APPLICATION` active ; les fenêtres IME, barre système et nos
propres overlays sont ignorées. Décision pure `BubbleVisibilityPolicy`, testée.

La liste des apps installées (écran Paramètres) utilise une balise `<queries>` sur l'intent
`MAIN/LAUNCHER` — pas besoin de `QUERY_ALL_PACKAGES` (restreint sur Play).

### 2.8 Contraintes Android récentes / Play Store

- **Politique Accessibility API de Google Play** : l'usage non lié à l'aide aux personnes handicapées
  exige une déclaration dans la Play Console, une **divulgation bien visible** et un consentement
  explicite in-app avant l'activation. L'app affiche un écran de divulgation avant de renvoyer vers les
  réglages d'accessibilité. `isAccessibilityTool=false` dans la config du service. Publication Play
  possible mais soumise à revue ; la distribution par APK/F-Droid est la voie la plus simple.
- **Android 13+ « paramètres restreints »** : pour un APK installé hors store, l'activation du service
  est grisée tant que l'utilisateur n'a pas choisi « Autoriser les paramètres restreints » dans Infos de
  l'appli (menu ⋮). Documenté dans le README et dans l'app.
- **`FLAG_SECURE`** : pas de capture possible → on retombe sur l'arbre seul (qui marche souvent).
- **`takeScreenshot` rate-limit** : ≥ 1 s entre deux appels → géré (pas de tap répété).
- **Téléchargement de modèles** : option « Wi-Fi uniquement » (par défaut : oui) et gestion des modèles
  téléchargés dans les Paramètres.
- **Vie privée** : tout est traité sur l'appareil ; aucune donnée d'écran n'est envoyée, aucune
  autorisation `INTERNET` n'est utilisée par notre code hors téléchargement des modèles par ML Kit.

### 2.9 minSdk

`minSdk 26` (Android 8, ~97 % du parc) ; OCR + style nécessitent API 30 (Android 11, ~90 %) avec
dégradation propre en dessous. `targetSdk/compileSdk 35`.

## 3. Architecture

```
app/src/main/java/com/translateoverlay/
├── TranslateOverlayApp.kt          Application
├── settings/SettingsRepository.kt  SharedPreferences (langue cible, exclusions, OCR, bulle…)
├── core/                           logique pure (testée) : TextBlock, Rect, BlockMerger,
│                                   LanguageVoter, TranslatableFilter, BubbleVisibilityPolicy,
│                                   ColorEstimator, AlignmentEstimator, TextFitter, TranslationCache
├── capture/NodeTextCollector.kt    arbre d'accessibilité → TextBlock
├── capture/ScreenshotOcr.kt        takeScreenshot + ML Kit OCR → TextBlock + Bitmap
├── translate/…                     LanguageDetector (ML Kit LangID), MlKitTranslator, ModelManager
├── pipeline/TranslationPipeline.kt orchestre capture → fusion → style → détection → traduction
├── service/OverlayAccessibilityService.kt  bulle, exclusions, déclenchement, overlay
├── overlay/BubbleView.kt, TranslationOverlayView.kt
└── ui/MainActivity.kt (Compose)    accueil/activation, Paramètres, Apps exclues, Modèles, À propos
```

## 4. Jalons

| Jalon | Contenu | Statut |
|---|---|---|
| J0 | Étude, squelette Gradle, git, docs | MVP |
| J1 | Service d'accessibilité + bulle flottante déplaçable (overlay accessibilité) | MVP |
| J2 | Exclusions par app au premier plan + écran de sélection des apps | MVP |
| J3 | Capture arbre + capture écran/OCR + fusion | MVP |
| J4 | Détection de langue + traduction ML Kit + gestion des modèles | MVP |
| J5 | Estimation du style + overlay de remplacement + fermeture/toggle original | MVP |
| J6 | Paramètres complets, À propos/version, divulgation, tests unitaires | MVP |
| J7 | Mode « live » (retraduction auto au défilement) | Post-MVP |
| J8 | Moteurs alternatifs (DeepL en ligne, LLM local) pour meilleure qualité | Post-MVP |
| J9 | Taille de police exacte via `EXTRA_DATA_RENDERING_INFO_KEY` (API 30) pour les nœuds | Post-MVP |
| J10 | Tests instrumentés sur appareil, préparation Play (vidéo de divulgation) | Post-MVP |
