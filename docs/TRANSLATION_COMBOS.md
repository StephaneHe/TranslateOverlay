# Meilleure combinaison de modèles en ligne (NVIDIA) — étude

_2026-09-27. Étude seulement : aucun changement de l'application. Modèles locaux (ML Kit, Tesseract,
NLLB, Opus-MT) hors périmètre ; le repli ML Kit de l'app reste tel quel._

Retour utilisateur : « les résultats ne sont pas très bons ; trouver la combinaison qui améliore la
traduction ». Question : quels modèles en ligne gratuits, et comment les combiner (lecture, contexte,
consignes, relecture, juge), pour la meilleure traduction **réelle** d'écrans **réels**, avec la cible
de latence **premier bloc amélioré ≤ 2 s, écran complet ≤ 8 s** et le quota partagé ?

## 1. Méthode

- **Écrans réels** (`tools/mt-bench/combos_corpus.json`), blocs dans l'ordre où l'app les envoie,
  **références écrites à la main** bloc par bloc :
  - **S1 ynet** (accueil capturé sur le V30T, 24 blocs : menu, titres, chapô, brèves, pubs, mentions) he→fr ;
  - **S2 bannières** (texte des 4 pubs ynet de l'étude vision, 27 lignes telles que l'OCR les découpe)
    he→fr, et **S2m** (même contenu, lignes d'un même titre regroupées : 20 blocs) ;
  - **S3 / S4 Wikipédia « Cat »** (8 blocs : logo, onglets, bandeaux, paragraphe) en→fr et en→he.
  Pas de capture de réseau social ni d'autre app disponible pour ce tour.
- **Configurations** :
  - **b0** : consigne actuelle de l'app (1.8.0) ;
  - **b1** : + **contexte** (app, URL, titre de la page), « les blocs sont un seul écran, de haut en bas,
    servez-vous-en comme contexte », règles de style (site d'info / appli, libellés courts, noms propres
    sous leur forme usuelle, rien d'ajouté, longueur proche de l'original) ;
  - **b2** : b1 + règle « libellés d'interface conventionnels » (sans exemple tiré des références) ;
  - **relecture** : un 2ᵉ passage corrige la 1ʳᵉ traduction ; **juge** : choisit A/B bloc par bloc ;
  - **pipeline image** : lecture par modèle vision (`docs/VISION_OCR.md`) → traduction par Ultra.
- **Mesures** : chrF++ par écran (et sur les seuls menus) contre les références ; **évaluation
  manuelle** (exactitude, contresens, naturel, sur 5) de 19 blocs représentatifs ; latence en flux :
  premier jeton, **premier bloc complet**, écran complet. Chaque écran = **1 requête** par modèle.
- **Sécurité** : clé lue au lancement dans `$NVIDIA_ENV_FILE`, jamais écrite ni affichée ;
  ≥ 3,2 s entre requêtes ; budget 80, compteur `tools/mt-bench/nvidia-usage-combos.json`.

## 2. Modèles disponibles (sonde sur S1, consigne b1)

| Modèle | Résultat | Premier bloc | Écran complet |
|---|---|---|---|
| **Nemotron 3 Ultra 550B** | 24/24 blocs | 1,8 s | 6,2 s |
| **Nemotron 3 Super 120B** | 24/24 blocs | 1,0 s | 3,6 s |
| **Kimi K3** | 24/24 blocs | 15,4 s | 36,3 s |
| Mistral Nemotron | 7/24 blocs | 58 s | délai dépassé |
| DeepSeek V4.1 Flash, GLM 5.3, Gemma 4 31B | aucun jeton en 75 s | — | — |
| Mistral Large, Llama 3.1 Nemotron Ultra 253B | HTTP 404 (non déployés pour le compte) | — | — |

## 3. Qualité (chrF++)

| Configuration | S1 ynet | S2 bannières | S2m bannières regroupées | S3 wiki en→fr | S4 wiki en→he |
|---|---|---|---|---|---|
| Ultra **b0** (app 1.8.0) | 74,4 | 68,1 | — | **82,6** | 51,7 ¹ |
| Ultra **b1** (contexte) | **78,9** / 77,5 ² | **70,6** | 71,3 | 81,5 | 64,2 / 68,0 ² |
| Ultra b2 (+ libellés) | 78,6 / 79,4 / 79,0 ² | 69,2 / 68,9 ² | — | 80,8 | 71,6 / 64,2 ² |
| Super b1 / b2 | 73,1 / 71,9 | 69,5 (b2) | 66,8 | 78,1 (b2) | 60,7 (b2) |
| Kimi K3 b1 | 77,2 | — | **78,5** | — | **réponse inutilisable** (« [1!!!!!… ») |
| Ultra b1 **+ relecture Ultra** | 78,9 (±0) | 70,4 | — | — | 64,2 (±0) |
| **Juge** Ultra (Ultra b1 vs Kimi b1) | 80,2 (+1,3) | — | — | — | — |
| Image : lecture Llama 3.2 11B Vision → Ultra b1 | — | 51,1 ³ | — | — | — |
| Image : lecture parfaite (référence) → Ultra b1 | — | 64,0 ³ | — | — | — |

¹ réponse coupée au délai de 75 s. ² répétitions : l'écart d'une exécution à l'autre est de ±1 sur S1
et ±4 sur S4 — b2 n'est pas meilleur que b1. ³ score par bannière entière (lignes lues concaténées),
non comparable aux colonnes S2.

**Évaluation manuelle** (19 blocs : 9 de S1, 10 de S2 ; exactitude / naturel, sur 5 ; contresens graves) :

| Configuration | Exactitude | Naturel | Contresens graves |
|---|---|---|---|
| Ultra b0 | 4,0 | 3,9 | 1 (« לצפר לכם » → « Pour vous siffler ») |
| **Ultra b1** | 4,0 | **4,2** | 2 (« קומו! » → « Coucou ! », « לצפר לכם » → « à vous chanter ») |
| Super b2 | 3,9 | 4,1 | 3 (« Eisenkot » → « **Gantz** », « Gaza va exploser **de l'intérieur** », « Souccothon ») |
| Kimi K3 b1 (S1 + S2m) | 4,3 | 4,3 | 0 sur l'échantillon (mais sortie inutilisable en hébreu) |

## 4. Latence (flux ; temps depuis l'envoi)

| Modèle (b0/b1/b2) | n | Premier bloc p50 | Premier bloc max | Écran complet p50 | p95 / max | Premier bloc ≤ 2 s | Écran ≤ 8 s |
|---|---|---|---|---|---|---|---|
| **Ultra** | 19 | **1,2 s** | 49,2 s | 8,8 s | 77 s | 63 % | 47 % |
| **Super** | 6 | **0,7 s** | 1,0 s | **3,8 s** | 5,3 s | **100 %** | **100 %** |
| Kimi K3 | 3 | 3–15 s | — | 30–36 s | — | 0 % | 0 % |

Ultra est **excellent quand il n'attend pas**, mais l'essai gratuit le met parfois en file
(premier jeton à 24 s, 46 s ; écran à 33–77 s) : **la moitié des écrans dépassent 8 s**. Super est
**régulier**. Relecture : +7 à +22 s. Juge : +5,6 s après les deux traductions.

## 5. Exemples avant (app 1.8.0) → après (recommandé)

| Source | 1.8.0 (Ultra b0, lignes découpées) | Recommandé (Ultra b1, lignes regroupées) | Référence |
|---|---|---|---|
| לרכישת מנוי | Pour l'achat d'un abonnement | **Pour s'abonner** | S'abonner |
| מבחן דרך: החשמלית הקטנה… | **Test** routier : la petite électrique… | **Essai** routier : la petite électrique à l'âme sportive | Essai routier… |
| "נתניהו הוזהר…" | "Netanyahu a été averti…" | « **Netanyahou** a été averti… » | « Netanyahou… » |
| Search (Wikipédia) | Recherche | **Rechercher** | Rechercher |
| עם חודש / ראשון מתנה! (2 lignes) | Avec un mois / **Premier offert !** | **Avec le premier mois offert !** | Avec le premier mois offert ! |
| ישראל / לא / שוכחת | Israël / **Ne** / **Oublie pas** | **Israël n'oublie pas** | Israël n'oublie pas |
| טקס 7.10 / הזיכרון / הלאומי | Cérémonie 7.10 / **La mémoire** / Nationale | Cérémonie du 7.10, mémoire nationale (Kimi : **Cérémonie nationale du souvenir du 7.10**) | Cérémonie nationale du souvenir du 7.10 |
| Talk (en→he) | שיחה | שיחה | שיחה |
| Paragraphe « The cat… » en→he | **coupé** (77 s) | complet en 5,3 s | — |

Erreurs qui **restent** avec la meilleure combinaison : jeux de mots publicitaires (« לצפר לכם » :
observer les oiseaux / klaxonner), noms de campagnes (« קומו! » → « Coucou ! »), libellés maison
(« מבזקים » → « Flashs » plutôt que « Brèves », « מידע נוסף » → « Plus d'informations »). Seul Kimi K3
résout les deux premiers, en 30 s.

## 6. Recommandation

### Meilleure combinaison mesurée

**Nemotron 3 Ultra avec contexte d'écran (b1), en une requête par écran, + lignes d'un même titre
regroupées avant l'envoi, + Nemotron 3 Super lancé en parallèle seulement si Ultra n'a rien renvoyé
après 1,5 s** (Ultra remplace ensuite les blocs de Super quand il arrive ; ML Kit reste l'affichage
immédiat et le dernier recours).

- **Qualité** : chrF++ S1 **78,9** (1.8.0 : 74,4), S2 70,6 et titres d'image enfin lisibles grâce au
  regroupement, S3 81,5, S4 64–68 (1.8.0 : 51,7, réponse coupée) ; évaluation manuelle 4,0 / 4,2.
- **Latence** (d'après les 19 + 6 mesures) : premier bloc amélioré **≤ 2,3 s dans tous les cas**
  (Ultra en 1,2 s médiane, sinon Super à 1,5 + 0,7 s) ; écran complet **≤ 7 s** (Super) puis version
  Ultra en 8,8 s médiane.
- **Requêtes** : **1,4 par écran** en moyenne (Super n'est lancé que dans ~40 % des cas) ; avec le
  limiteur à 20/min, ~14 écrans par minute.

### Deux alternatives

1. **Super seul, avec contexte (b2)** : le plus rapide et le plus régulier (premier bloc 0,7 s,
   écran 3,6–5,3 s, 1 requête), mais −3 à −4 chrF++ et des erreurs de noms propres (« Gantz » pour
   Eisenkot) : acceptable comme mode « rapide ».
2. **Ultra b1 immédiat + passe Kimi K3 différée sur les blocs publicitaires et titres d'image,
   cible française seulement** : meilleure qualité mesurée sur les textes publicitaires (78,5 contre
   71,3), mais +1 requête et **30 s** de plus, et Kimi a produit une réponse inutilisable en hébreu.

### Écartés (mesurés)

- **Relecture** par un 2ᵉ passage : aucun gain (±0) pour +7 à +22 s.
- **Juge** Ultra/Kimi bloc par bloc : +1,3 chrF++ seulement, et il faut attendre Kimi (36 s).
- **Pipeline vision d'abord** (Llama 3.2 11B lit l'écran) : 24–53 s par image, positions
  inutilisables ; même avec une lecture parfaite, la traduction plafonne (64 contre 51) — voir
  `docs/VISION_OCR.md`.
- **Consigne « libellés d'interface »** (b2) : neutre (dans le bruit).
- DeepSeek V4.1 Flash, GLM 5.3, Gemma 4 31B, Mistral Nemotron (trop lents) ; Mistral Large,
  Llama 3.1 Nemotron Ultra 253B (non déployés).

## 7. Plan d'intégration proposé (à valider)

1. **Contexte dans la requête** : envoyer le paquet de l'app, le titre de la fenêtre
   (`AccessibilityWindowInfo.title`) et, dans un navigateur, l'URL (texte de la barre d'adresse, déjà
   repérée comme zone ignorée par l'OCR) ; consigne b1. Coût : ~150 jetons de plus, 0 requête.
2. **Regrouper les lignes d'un même titre** avant l'envoi (lignes OCR adjacentes de même taille et
   même couleur, déjà mesurées par l'estimation de style) : un bloc = un titre, un seul cadre
   d'overlay. Logique pure + tests.
3. **Secours Super « couvert »** : dans la chaîne de repli, lancer Super si Ultra n'a produit aucun
   bloc après 1,5 s (au lieu d'attendre son échec) ; les blocs d'Ultra remplacent ceux de Super (rang).
   Le compteur passe à ~1,4 requête par écran ; repères et légende inchangés (jaune « S » tant que
   Super est affiché).
4. ML Kit : inchangé (affichage immédiat et dernier recours).
5. Validation sur le V30T : ynet, Wikipédia en→fr/en→he, bannières ; objectifs ≤ 2 s / ≤ 8 s.
6. **Option** (à décider) : passe Kimi K3 différée sur les publicités et titres d'image, cible
   française uniquement.

## 8. Consommation

**41 requêtes** sur le budget de 80 (sondes : 8 ; Ultra b0/b1/b2 + répétitions : 18 ; Super : 5 ;
Kimi : 3 ; relecture : 3 ; juge : 1 ; pipeline image : 3), **0 × 429, 0 × 503**. S'y ajoutent les 14
requêtes de l'étude vision (`docs/VISION_OCR.md`). Données : `tools/mt-bench/combos-results.json`,
`combos-scores.json`, banc `combos_bench.py`.
