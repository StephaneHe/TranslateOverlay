# Moteurs de traduction — étude comparative

_2026-09-25. Retour utilisateur : « trouvez un modèle plus efficace, les traductions sont mauvaises »._

## 1. Méthode

- **Corpus** : 28 segments réels issus des tests sur appareil (ynet.co.il, Wikipédia), là où ML Kit
  s'est trompé : 16 hébreu→français (titres, publicités, libellés d'UI), 8 anglais→français,
  4 anglais→hébreu. Sorties ML Kit **relevées sur le téléphone** (V30T). Références : traductions
  humaines (une par segment). Fichiers : `tools/mt-bench/corpus.json`, `results.json`, `bench.py`.
- **Métrique** : chrF++ (sacrebleu, 0–100, une seule référence → à lire comme un ordre de grandeur,
  complété par la lecture des sorties ci-dessous).
- **Exécuté sur PC** : Opus-MT (MarianMT), NLLB-200 distillé 600M / 1.3B, TranslateGemma 4B (Q4_K_M,
  llama.cpp, **CPU**), Google Translate (point d'accès web sans clé, pour mesurer la qualité du
  modèle Google). Non mesurables sans clé : Google Cloud officiel (même famille de modèle que la
  colonne « Google »), Azure Translator, DeepL, LLM en ligne (Gemini, Claude, GPT).

## 2. Résultats mesurés (chrF++)

| Paire | ML Kit (actuel) | Opus-MT | NLLB 600M | NLLB 1.3B | TranslateGemma 4B | Google |
|---|---|---|---|---|---|---|
| he→fr (n=16) | **44,5** | 52,8 (pivot en) ¹ | 56,2 | 56,1 | 56,3 | **63,5** |
| en→fr (n=8)  | 58,3 | 68,0 | 66,0 | 63,2 | 66,2 | **75,5** |
| en→he (n=4)  | 37,9 | 36,1 | 33,3 | 46,6 | 46,1 | **51,0** |

¹ Il n'existe pas de modèle Opus-MT he→fr publié (dépôt `Helsinki-NLP/opus-mt-he-fr` vide) :
he→en→fr avec les modèles `tc-big`.

Latence (indicative) : TranslateGemma 4B ≈ **5 s par segment sur le CPU 8 threads du PC**
(un téléphone Dimensity 1080 est plusieurs fois plus lent) → inutilisable pour un écran de
15–25 blocs. Opus/NLLB : 0,1–0,5 s par segment sur CPU PC. En ligne : ~0,2–0,8 s pour **tout
l'écran** en une requête groupée.

## 3. Exemples côte à côte (he→fr)

| Source | Référence | ML Kit | Google | TranslateGemma 4B | NLLB 600M |
|---|---|---|---|---|---|
| חשש לטביעת אדם בחוף באשקלון… | Crainte d'une **noyade** à Ashkelon : deux jeunes **secourus**… | Craignant **l'inflation** d'une personne… ont été **montrés** | Crainte d'un homme qui **se noie**… ont été **secourus** | Préoccupation concernant un possible **noyade**… **secourus** | Il craint d'avoir retrouvé un homme sur la plage de **Bachelon** |
| לכל המבזקים | Toutes les brèves | **Boire** tous les éclairs | À tous les clignotants | Pour tous les articles de presse | Pour tous ceux qui brillent |
| הרוג בתאונה בבקעת הירדן, 3 פצועים במצב קשה | Un mort… vallée du Jourdain, 3 blessés dans un état grave | **Tuer**… vallée de la **Jordanie**… en **mode difficile** | Tué… vallée du Jourdain… état grave | Décès… vallée du Jourdain… état grave | Tué… vallée du Jourdain… état grave |
| נקבע מותו של הצעיר שנורה בגליל המערבי | Le décès du jeune homme blessé par balle… a été prononcé | La **jeune femme** a été déterminée que le jeune homme… | La mort du jeune homme abattu en Galilée occidentale a été déterminée | … abattu **dans le nord de la Palestine** ² | Le décès d'un jeune homme tué en Galilée occidentale |
| בכיר איראני: "לא נתגמש על תוכנית הגרעין" | Haut responsable iranien : « Nous ne transigerons pas… » | **Senior** iranien : « Ne pas être **rencontré**… » | Un haut responsable iranien : « Nous ne serons pas flexibles… » | … « Nous ne céderons pas… » | … « Nous ne **gagnerons** pas… » |
| המכירה אסורה למי שטרם מלאו לו 18 שנים | La vente est interdite aux moins de 18 ans | …de la **mettre fin pendant** 18 ans | …interdite aux moins de 18 ans | …aux personnes de moins de 18 ans | …aux personnes de moins de 18 ans |
| מבחן דרך: החשמלית הקטנה עם הנשמה הספורטיבית | Essai routier : la petite électrique à l'âme sportive | **Testez à travers** : la petite **électricité**… | Essai routier : le petit **tramway**… | Test : le petit véhicule électrique performant | Test de route : petit **train** électrique… |

² TranslateGemma 4B est le plus fluide mais **invente** parfois (« nord de la Palestine », en→he
« שהוכחד על ידי בני אדם » = « exterminé par l'homme ») : risque inacceptable sur des titres de presse.

en→fr : ML Kit « Un carnivore **contrausse** », « un **crécuscularprédicator** », « Cat
(**Disambimuation**) » ; Google « Un carnivore strict… », « un prédateur crépusculaire »,
« Chat (homonymie) ». en→he : ML Kit laisse « colluqueal cols » non traduit ; Google « המכונה גם חתולים ».

## 4. Tableau comparatif

| Option | Qualité he↔fr (mesurée / attendue) | Hors-ligne | Clé / compte | Coût | Confidentialité | Taille / RAM | Hébreu |
|---|---|---|---|---|---|---|---|
| **ML Kit** (actuel) | 44,5 — pivot anglais, erreurs grossières | oui | non | gratuit | local | ~30 Mo/langue | oui |
| Opus-MT / Bergamot (Marian int8) | 52,8 — pivot anglais | oui | non | gratuit | local | ~20–75 Mo/paire, + runtime ONNX/CT2 | he↔en seulement |
| NLLB-200 600M | 56,2 — noms propres abîmés | oui | non | licence **CC-BY-NC** | local | ~600 Mo int8, RAM ≥ 2 Go | oui |
| TranslateGemma 4B | 56,3 — fluide, hallucinations | oui | non | gratuit (licence Gemma) | local | 2,5 Go, RAM ≥ 4 Go, ~5 s/segment | oui |
| **Google Cloud Translation v2** | **63,5 / 75,5 / 51,0** (modèle Google) | non | clé API + facturation (carte) | 500 k car./mois gratuits puis 20 $/M | données non utilisées pour l'entraînement | — | oui, direct |
| **Azure AI Translator** | non mesurée (niveau Google attendu) | non | clé + région (carte) | **2 M car./mois gratuits** (F0) puis ~10 $/M | « no trace », rien n'est stocké | — | oui, direct |
| DeepL | très bonne (réputation) | non | abonnement payant (offres Free/Pro retirées en 07/2026) | ~26 $/mois | payant : supprimé après traduction | — | oui depuis 2025 |
| Gemini API (Flash-Lite) | attendue ≥ Google, contexte d'écran | non | compte Google, sans carte | gratuit (quotas) | **gratuit = données utilisées par Google** | — | oui |
| Claude / GPT (mini) | attendue ≥ Google | non | clé + carte | ~0,1–1 $ / 1000 écrans | pas d'entraînement (API) | — | oui |
| Google web sans clé (`client=gtx`) | = Google | non | non | gratuit | — | — | oui |

Le point d'accès Google sans clé n'est **pas retenu** : non documenté, contraire aux conditions
d'utilisation, bloqué par des 429/captcha (observé pendant ce banc). Il n'a servi qu'à mesurer la
qualité du modèle Google.

## 5. Recommandation

- **Moteur principal : Microsoft Azure AI Translator** (niveau F0). Traduction directe he↔fr sans
  pivot, qualité du niveau des grands services en ligne, **2 M caractères/mois gratuits**
  (≈ 1 000 à 2 000 écrans), politique « no trace » (rien n'est conservé), une seule requête groupée
  par écran (~0,2–0,5 s). Contrainte : compte Azure (carte bancaire pour la vérification) → clé +
  région à saisir dans les Paramètres.
- **Alternative équivalente : Google Cloud Translation v2**, meilleur score mesuré ici (même modèle
  que la colonne « Google »), 500 k caractères/mois gratuits, compte Google Cloud avec facturation.
- **Repli hors-ligne : ML Kit** (automatique si pas de réseau, clé absente/invalide, quota épuisé).
- **Plus tard (hors-ligne de meilleure qualité)** : Opus-MT/Bergamot int8 via ONNX Runtime
  (+8 à +10 chrF sur ML Kit, ~150 Mo pour he→en→fr) — gros chantier d'intégration (tokeniseur
  SentencePiece, runtime natif), à envisager si l'usage hors-ligne est prioritaire. Les LLM locaux
  (TranslateGemma 4B) sont trop lents et hallucinent sur ce téléphone.

Tous les moteurs nettement meilleurs demandent une clé liée à un compte → implémentés derrière un
champ de clé (chiffrée sur l'appareil, jamais committée) ; le choix du fournisseur et de la clé
revient à l'utilisateur.
