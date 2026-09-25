# Changelog

Toutes les évolutions notables de ce projet sont documentées ici.
Format : [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/), versionnage [SemVer](https://semver.org/lang/fr/).

## [1.0.1] - 2026-09-25

Corrections issues de la validation sur appareil réel (Doogee V30T, Android 12).

### Corrigé
- Bulle invisible au premier démarrage du service : sa position était calculée avant que sa
  taille soit connue, ce qui la plaçait hors de l'écran (x = largeur de l'écran).
- Crash à l'arrêt du service quand un overlay était affiché : la bulle était ré-ajoutée avec un
  jeton de fenêtre invalide (`BadTokenException`), par exemple lors de la désactivation du service.
- Pages web traduites mot par mot : les fragments (liens, gras, italique) exposés séparément par
  Chrome sont désormais regroupés en paragraphes, avec un espace ajouté aux retours à la ligne.
- Textes courts mal identifiés (« Cat » détecté comme gallois, « Talk » comme roumain), ce qui
  déclenchait le téléchargement de modèles inutiles : ils suivent la langue dominante de l'écran.
- Libellés invisibles (boutons-icônes) traduits par-dessus les icônes : en OCR latin, un texte de
  l'arbre sans texte OCR à son emplacement est ignoré.
- Barre d'adresse traduite : les éléments de type URL/domaine sont exclus, même avec des
  caractères parasites de l'OCR.
- Gras détecté à tort et taille de texte sous-estimée : seuils recalibrés sur des mesures réelles
  (trait normal ≈ 0,14 et gras ≈ 0,21 de la taille ; taille ≈ hauteur de ligne OCR).

### Ajouté
- Journal de la durée de traduction (`TranslateOverlay` dans logcat).

## [1.0.0] - 2026-09-25

### Ajouté
- Service d'accessibilité unique : bulle flottante déplaçable (aimantée aux bords, position mémorisée)
  affichée au-dessus de toutes les applications sans permission `SYSTEM_ALERT_WINDOW`.
- Masquage automatique de la bulle quand l'application au premier plan est exclue, ainsi que sur
  l'écran verrouillé et dans TranslateOverlay.
- Capture hybride du texte : arbre d'accessibilité (texte exact) + capture d'écran et OCR ML Kit
  (Android 11+) pour le texte dans les images, avec fusion des deux sources.
- Détection de langue par bloc (ML Kit Language ID) avec repli sur la langue dominante de l'écran.
- Traduction on-device ML Kit (59 langues), cache LRU, téléchargement automatique des modèles
  (option Wi-Fi uniquement).
- Overlay de remplacement imitant le style : couleur de fond et de texte échantillonnées, taille
  estimée d'après les lignes OCR, alignement, gras heuristique, ajustement automatique de la taille.
  Toucher un bloc pour voir l'original, toucher ailleurs pour fermer.
- Paramètres : langue cible (français par défaut), applications exclues (liste avec recherche et
  cases à cocher), OCR et écriture OCR (latin, chinois, japonais, coréen, devanagari), gestion des
  modèles, taille et opacité de la bulle, version affichée.
- Écran de divulgation préalable à l'activation du service et aide « paramètres restreints » (Android 13+).
- Tests unitaires de la logique non-UI (fusion, langues, style, ajustement, visibilité, cache).
