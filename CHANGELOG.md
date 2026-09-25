# Changelog

Toutes les évolutions notables de ce projet sont documentées ici.
Format : [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/), versionnage [SemVer](https://semver.org/lang/fr/).

## [1.4.1] - 2026-09-25

### Modifié
- Azure et Google Cloud ne sont plus présentés comme recommandés (décision : pas de clé payante ni
  de carte bancaire) ; le code reste disponible. ML Kit reste le moteur par défaut.
- Étude des moteurs complétée avec 10 modèles de l'API catalogue NVIDIA (essai gratuit, sans
  carte) : Gemma 4 31B meilleur en he→fr (chrF++ 72,9) mais 2 à 4 min par écran → non intégré ;
  recommandation révisée : traduction sur l'appareil avec NLLB-200 600M (`docs/TRANSLATION_ENGINES.md`).

## [1.4.0] - 2026-09-25

Qualité de traduction (« les traductions sont mauvaises »). Étude et mesures :
`docs/TRANSLATION_ENGINES.md` (banc `tools/mt-bench/`, 28 segments réels ynet/Wikipédia).

### Ajouté
- Moteur de traduction interchangeable (Paramètres › Moteur de traduction) :
  - **ML Kit** (hors-ligne, défaut, toujours utilisé en repli) ;
  - **Microsoft Azure Translator** (recommandé : he↔fr direct, 2 M caractères/mois gratuits,
    « no trace ») ;
  - **Google Cloud Translation v2** (meilleur score mesuré : chrF++ he→fr 63,5 contre 44,5 pour
    ML Kit, en→fr 75,5 contre 58,3).
- Clé API saisie dans l'application, **chiffrée** (AES-GCM, clé Android Keystore), jamais affichée
  ni sauvegardée (exclue des sauvegardes et transferts), bouton « Tester ».
- Une seule requête par langue source et par écran (lots selon les limites de chaque service),
  cache des traductions.
- Repli automatique sur ML Kit sans clé, sans réseau, clé refusée ou quota épuisé, avec message ;
  le moteur utilisé est indiqué dans la légende de l'overlay.

### Modifié
- Textes de divulgation et « À propos » : le texte n'est envoyé qu'au service en ligne choisi.

## [1.3.0] - 2026-09-25

Hébreu → français sur ynet.co.il (site de référence), y compris le texte **dans** les images, sans
choix manuel d'alphabet. Mis au point sur 5 vraies bannières ynet et 4 écrans de la page d'accueil
(Doogee V30T) ; captures dans `docs/screenshots/ynet/`.

### Modifié
- Modèle OCR hébreu : `tessdata_best` (3,7 Mo) au lieu de `tessdata_fast` — lit les polices
  publicitaires condensées (« לרכישת מנוי », « יש הצעות שחייבים לקחת ») que le modèle rapide ratait.
- OCR automatique par ligne : chaque ligne latine douteuse (< 0,7 quand un autre alphabet est
  présent à l'écran) est relue par le modèle de cet alphabet ; recadrages préparés pour Tesseract
  (inversion du texte clair sur fond sombre, agrandissement ×2/×3 des petites lignes).
- Passes hébreu plein écran en mode « texte épars » à 3 échelles (1, ½, ¼) : texte sur images
  chargées et gros titres (100 px et plus) ; ~1,3 s.
- Sans texte hébreu dans la page, la passe hébreu est lancée si plusieurs lignes douteuses restent
  sans lecture ; les essais de relecture « à l'aveugle » sont limités à 2 (13,6 s → 3–5 s).

### Corrigé
- Charabia latin conservé quand l'écran contient un autre alphabet (« Mann n jpn n colmob »,
  « DpU 2.10 NT! ») : lignes latines < 0,7 écartées dans ce cas.
- Barre d'adresse et barre d'état traduites : l'OCR ignore les champs de saisie et les fenêtres
  système (zones issues de l'arbre d'accessibilité).
- Lectures superposées d'une même zone (bannière lue d'un bloc + sa vraie ligne) : dédoublonnage
  par confiance avec bonus de complétude ; lignes identiques émises une seule fois.
- Menu ynet fusionné sans espaces (« כותרותמבזקים… ») : éléments d'une même ligne séparés par un
  espace visible traités comme éléments distincts, aussi de droite à gauche.
- Corps de dépêches repliés (ynet « מבזקים ») traduits par-dessus les titres : nœuds web hors de
  leur conteneur écartés, nœuds empilés départagés par ce que l'OCR voit réellement.
- Charabia hébreu issu de petit texte latin (identifié comme yiddish, non traduisible) écarté.
- Texte d'application relu dans un autre alphabet par l'OCR (« hébreu vers français » de Chrome
  lu comme de l'hébreu) écarté, sans toucher au texte des images nommées en latin.

## [1.2.0] - 2026-09-25

Correctif « Les images ne sont pas traduites » (diagnostiqué et validé sur Doogee V30T, Android 12).

### Corrigé
- **Texte des images écarté dans les pages web (cause principale).** Chrome expose les textes
  d'une page comme éléments frères : ils étaient fusionnés en un seul bloc dont le cadre englobait
  les images situées entre eux, et tout bloc OCR couvert à plus de 50 % par ce cadre était supprimé
  (« SUMMER SALE » absorbé par le paragraphe « Our store news… »). Désormais :
  - les fragments web d'un même élément séparés par un espace vertical ne sont plus fusionnés ;
  - un bloc OCR n'est absorbé par le texte d'accessibilité que s'il lit **le même texte**
    (comparaison par mots, tolérante aux erreurs d'OCR), pas sur la seule géométrie ;
  - les lignes OCR ne sont rattachées à un bloc d'accessibilité que si elles en lisent le texte.
- Renvois « [1] » en exposant qui coupaient un paragraphe Wikipédia en deux.
- Mèmes (texte blanc cerné de noir sur photo) rendus en couleurs inversées : le fond est désormais
  mesuré sur un anneau autour du texte (médiane par canal), le contour est détecté et redessiné.
- Masque de l'overlay trop juste sur les images : le texte d'origine débordait sur les bords.
- Libellés courts de l'interface déjà dans la langue cible (« Partager », « Modifier »,
  « 25 sept. ») retraduits parce qu'identifiés comme danois ou indéterminés.
- Textes tout en majuscules traduits en minuscules : la casse est conservée (« VENTE D'ÉTÉ »).
- Bruit OCR de la barre d'outils du navigateur (« A O localhost… + 33 ») traduit.

### Ajouté
- **OCR automatique multi-alphabet (nouveau réglage par défaut « Automatique »)** : l'utilisateur
  n'a plus à choisir l'alphabet. Le modèle latin lit l'écran ; les lignes où il doute (confiance
  < 0,5, mesurée à 0,33 sur de l'hébreu contre 0,77–0,92 sur du vrai latin) sont relues avec
  Tesseract hébreu puis les modèles chinois, japonais, coréen, devanagari ; dès qu'un alphabet est
  identifié, tout l'écran est relu avec son modèle. Les alphabets présents dans le texte
  d'accessibilité déclenchent aussi une passe complète. Coût mesuré : 0,3–0,9 s d'OCR par écran.
  Les choix manuels restent disponibles ; l'ancien choix (latin par défaut) est réinitialisé.
- Journal de diagnostic du pipeline (`adb logcat -s TO-Diag`, builds debug) : capture, blocs OCR
  bruts avec confiance, absorptions, filtres, langues, décisions.

## [1.1.0] - 2026-09-25

Support complet de l'hébreu (validé sur Doogee V30T, Android 12).

### Ajouté
- Hébreu comme langue cible : rendu droite-à-gauche forcé dans l'overlay (une phrase hébraïque
  commençant par un mot latin ou un nombre reste RTL), bidi natif pour les mots latins et chiffres
  intégrés, police système Noto Sans Hebrew (normal et gras). Valable pour toutes les langues RTL
  prises en charge (arabe, persan, ourdou…).
- Alignement miroir : un paragraphe aligné à gauche en anglais devient aligné à droite en hébreu
  (et inversement pour une source hébraïque traduite vers une langue LTR) ; le centré reste centré.
- OCR hébreu embarqué via Tesseract (LSTM, `tessdata_fast` « heb », 0,96 Mo, ~450 ms par écran) :
  nouvelle écriture OCR « Hébreu (Tesseract, embarqué) ». ML Kit ne reconnaît pas l'hébreu.
- Blocs OCR ignorés quand l'écriture dominante de l'écran n'est pas lisible par le modèle OCR
  choisi (ex. page hébraïque avec OCR latin) ; lignes OCR à faible confiance ML Kit écartées.

### Corrigé
- Liste des langues triée sans tenir compte des accents (« Hébreu » après « Hongrois ») : tri
  `Collator` selon la langue de l'appareil.
- Texte court déjà dans la langue cible (ex. barre « Traduire la page ? » de Chrome) « retraduit »
  depuis la langue dominante de l'écran.
- Adresses `hôte:port` (ex. `localhost:8765/…`) traduites.

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
