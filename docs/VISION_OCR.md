# Lecture des images par modèle multimodal (NVIDIA) — étude

_2026-09-27. Étude seulement : aucun changement de l'application._

**Question** : au lieu de l'OCR local (ML Kit latin + Tesseract hébreu), envoyer l'image à un modèle
vision qui renverrait, pour chaque ligne de texte, sa position, son texte source et sa traduction ;
l'app dessinerait l'overlay comme aujourd'hui. (Générer une image traduite est écarté : aucun modèle
d'édition d'image sur le compte, trop lent, hébreu mal rendu.)

## 1. Méthode

- **Corpus** (`tools/mt-bench/vision_corpus.json`) : les 4 vraies bannières ynet capturées sur le
  V30T où l'OCR échoue aujourd'hui (gros titres hébreux sur fond coloré : Menou Pais, Kumu / 7
  octobre, KKL oiseaux, Geely), + 2 images anglaises (mème, affiche) ; recadrées, JPEG q80, 800 px
  maximum. La bulle de l'app est visible sur 3 bannières, comme en usage réel (elle masque une partie
  de « לא » et de « מתי? »). **Référence** établie à la main : texte de chaque ligne, boîte, traduction
  française de l'image entière.
- **Mesures** : (1) lecture = chrF et CER sur le texte source (lignes triées haut → bas) ;
  (2) traduction = chrF++ (une seule référence, à lire comme un ordre de grandeur) ;
  (3) positions = IoU de chaque ligne de référence avec la meilleure boîte prédite (moyenne, et part ≥ 0,5) ;
  (4) latence ; (5) taille envoyée, jetons ; (6) texte inventé (ligne prédite ne ressemblant à
  aucune ligne de référence, puis vérification à l'œil).
- **OCR actuel** : Tesseract 5 avec **le modèle hébreu de l'app** (`tessdata_best`), mode texte épars
  (`--psm 11`), sur PC — approximation de la chaîne de l'app (qui ajoute des relectures par ligne et
  plusieurs échelles). Traduction de l'OCR : ce que l'app **affichait** (1.3.0, OCR + ML Kit) sur ces
  bannières, relu sur les captures `docs/screenshots/ynet/*_after.png`.
- **Appel** : `POST /v1/chat/completions`, message utilisateur `[{"type":"text"}, {"type":"image_url",
  "image_url":{"url":"data:image/jpeg;base64,…"}}]` (format OpenAI accepté par les 2 modèles qui ont
  répondu) ; image envoyée **dans la requête**, 45–133 Ko en base64 (sous la limite de ~180 Ko de
  l'API catalogue pour une image incluse ; au-delà il faut l'API d'« assets »). Consigne unique :
  « chaque ligne : texte exact, boîte [x0,y0,x1,y1] en 0–1000, traduction française ; JSON seul ».
- **Sécurité** : clé lue au lancement dans `$NVIDIA_ENV_FILE`, jamais écrite ni affichée ;
  ≥ 3,2 s entre deux requêtes ; budget dur 20, compteur `tools/mt-bench/nvidia-usage-vision.json`.

## 2. Résultats

### Bannières hébraïques (4 images) — moyennes

| Méthode | Lecture chrF ↑ | CER ↓ | Traduction chrF++ ↑ | IoU boîtes ↑ (≥ 0,5) | JSON respecté | Latence / image | Envoi |
|---|---|---|---|---|---|---|---|
| **OCR actuel** (Tesseract heb best, PC) | 27,2 | 0,61 | 22,9 (affiché par l'app 1.3.0) | **0,48** (47 %) | — | **0,6–1,1 s** (PC ; ~1,3 s sur V30T) | rien |
| **Llama 3.2 11B Vision** | **72,9** | **0,37** | **41,0** | 0,21 (0 %) | 2/4 | 20–53 s | 1 requête, ~6 500 jetons d'entrée |
| Nemotron 3 Nano Omni 30B | 8,0 (1 image) | 0,80 | 8,7 | 0,33 (40 %) | oui | **5,4 s** | 1 requête, 709 jetons |
| Llama 3.2 90B Vision | — | — | — | — | — | **> 170 s** (délai dépassé 2 fois) | |
| Phi-3 Vision 128k | — | — | — | — | — | HTTP 404 : non déployé pour le compte | |

Par image (lecture chrF / traduction chrF++) : Llama 11B — Menou Pais 96 / 40, Kumu 51 / 40,
KKL 66 / 40, Geely 78 / 44 ; Tesseract — 37, 19, 20, 32 ; app 1.3.0 (traduction affichée) — 35, 10,
29, 18. Nemotron Omni n'a répondu qu'une fois (3 × HTTP 503 « Worker local total request limit
reached (16/16) » ensuite).

### Images anglaises (mème, affiche)

| Méthode | Lecture chrF | IoU | Latence |
|---|---|---|---|
| OCR actuel (Tesseract eng / ML Kit latin dans l'app) | 99–100 | 0,83–0,87 (100 % ≥ 0,5) | 0,3–0,5 s |
| Llama 3.2 11B Vision | 100 | 0,27–0,77 | 12–22 s |

Pour l'anglais, l'OCR local est déjà parfait : le modèle vision n'apporte rien.

### Latence et quota (Llama 11B, 6 images pleines)

p50 **24,2 s**, p95 **52,5 s** (12,4 / 19,6 / 22,1 / 26,2 / 44,1 / 52,5 s). Une image = **1 requête**
(le quota de l'essai compte les requêtes : 40/min pour le compte, l'app s'en tient à 20/min) et
~6 500 jetons d'entrée (l'image est découpée en tuiles de 560 px : ~1 600 jetons par tuile).
**Recadrée sur un seul titre** (376 × 380 px, 1 tuile, 20 Ko) : 1 723 jetons, **6,9 s**, mais lecture
pire (« לא שוכחת » lu « אנחהטיה », la bulle coupait le mot).

## 3. Exemples côte à côte

| Image | Référence | OCR actuel → app 1.3.0 | Llama 3.2 11B Vision | Nemotron Omni |
|---|---|---|---|---|
| Menou Pais, gros titre rose | עם חודש ראשון מתנה! — « Avec le premier mois offert ! » | Tesseract « ny חודפו », « Inn NN » → affiché « Avec AFT / Cadeau en ligne! » | **« עם חודש ראשון מתנה! »** → « Avec un mois de cadeau! » | « עברית » → « hébreu » (inventé) |
| Menou Pais | יש הצעות שחייבים לקחת — « Il y a des offres à ne pas manquer » | « יש הצעות שוחייבים לקחת » → « Il y a des enchères à prendre » | exact → « Il y a des offres qui doivent être prises » | « אזל חוברות » (inventé) |
| Kumu, titre bleu | ישראל לא שוכחת — « Israël n'oublie pas » | rien de lisible (« דאר ») → **non traduit** | « ישראל » seul → « Israël » (« לא שוכחת » manqué) | 503 |
| Kumu, titre blanc | טקס 7.10 הזיכרון הלאומי — « Cérémonie nationale du souvenir du 7.10 » | « הזינרון » → **non traduit** | « 7.10 », « הויכרון » → « **L'incendie** » (contresens), « הלאומי » → « National » | 503 |
| Kumu, crédit | צילום: זיו קורן | → « Photo: Vue vered » | **exact** → « Photo: Ziv Koren » | 503 |
| KKL, titre | יש לנו משהו לצפר לכם | → « Nous avons quelque chose / À l'oiseau que vous » | **exact** → « Nous avons quelque chose à vous montrer » | — |
| Geely, bandeau | ימי מכירות חגיגיים עם מגוון הטבות | lu exact par Tesseract sur PC ; → non affiché par l'app 1.3.0 | **exact** → « Jours de ventes festifs avec une variété d'avantages » | 503 |
| Geely, mentions légales | ההטבות בכפוף לתקנון… | → « Les Lilecots privés ont été condamnés par des voitures… » | fragments, dont « 30-27.9.26 בן חדש רכב חדש » (réordonné / partiellement inventé) | 503 |

**Positions** : Llama 11B donne des boîtes en 0–1 (pas 0–1000 comme demandé), grossières et souvent
alignées sur une grille ([0,35, 0,15, 0,65, 0,35]…) : aucune ligne de référence à IoU ≥ 0,5 sur
l'hébreu — **inutilisables pour placer l'overlay**. Nemotron Omni place bien les zones (le bouton
« לרכישת מנוי » à la bonne place) mais **invente le texte** qu'il y met.

**Hallucinations** : Nemotron Omni — 4 lignes sur 6 inventées sur Menou Pais. Llama 11B — rare mais
présent : contresens de lecture (« הויכרון » → « L'incendie »), ligne recomposée sur Geely, mot absent
lu dans le titre recadré ; parfois des éléments réels hors référence (« 2026 » sur le calendrier, les
« 文A » de la bulle). **Format** : Llama 11B répond en prose au lieu du JSON demandé sur 3 images sur 6
(lecture de secours nécessaire).

## 4. Consommation

**14 requêtes** sur le budget de 20 : 4 sondes (1 par modèle), 6 images Llama 11B, 1 recadrage
Llama 11B, 1 relance Llama 90B (délai 170 s), 2 relances Nemotron Omni (503). **0 × HTTP 429**,
3 × HTTP 503 (Nemotron Omni seulement : arrêt appliqué à ce modèle ; les 2 mesures Llama restantes
ont été faites, arrêt ensuite). Compteur : `tools/mt-bench/nvidia-usage-vision.json`.

## 5. Recommandation

**Ne rien changer dans l'application pour l'instant.**

- *Remplacer l'OCR* : non. Le seul modèle qui lit bien l'hébreu (Llama 3.2 11B Vision : lecture
  chrF 73 contre 27 pour l'OCR, traduction 41 contre 23) met **24 s en médiane et 53 s au pire par
  image**, contre ~1 s ; ses **boîtes sont inutilisables** (IoU 0,21), il ne suit pas le format une
  fois sur deux et fait parfois des contresens. En anglais, l'OCR est déjà parfait.
- *Compléter l'OCR seulement quand il échoue* : c'est la seule piste qui tient, **mais pas avec les
  modèles disponibles aujourd'hui**. Il faudrait : détecter l'échec (lignes hébraïques de faible
  confiance), envoyer l'image seule en arrière-plan à Llama 11B (1 requête, 20–50 s), puis
  **garder les boîtes de l'OCR** et n'y reporter que le texte du modèle — appariement fragile (le
  modèle fusionne ou coupe les lignes autrement). Coût : +1 requête par image en échec, résultat
  plus de 20 s après l'overlay, risque de texte faux présenté comme sûr. Le gain réel porte sur les
  bannières publicitaires ; la lecture du reste de l'écran passe déjà par l'arbre d'accessibilité.
- *Modèles écartés* : Nemotron 3 Nano Omni (rapide, bonnes zones, mais invente l'hébreu),
  Llama 3.2 90B Vision (> 170 s), Phi-3 Vision (non déployé pour le compte).
- *À surveiller* : un modèle vision rapide (< 5 s) qui lise l'hébreu **et** donne des boîtes fiables
  rendrait la piste « complément sur échec » intéressante ; le banc
  (`tools/mt-bench/vision_bench.py`, corpus et référence) permet de le mesurer en ~10 requêtes.
