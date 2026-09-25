# Changelog

Toutes les évolutions notables de ce projet sont documentées ici.
Format : [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/), versionnage [SemVer](https://semver.org/lang/fr/).

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
