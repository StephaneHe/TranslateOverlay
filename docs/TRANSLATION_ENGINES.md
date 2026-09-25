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

| Moteur | Où | he→fr (n=16) | en→fr (n=8) | en→he (n=4) | Latence d'un écran (15 blocs) |
|---|---|---|---|---|---|
| **ML Kit** (actuel) | appareil | **44,5** | 58,3 | 37,9 | < 1 s |
| Opus-MT tc-big | appareil ² | 52,8 (pivot en) ¹ | 68,0 | 36,1 | ~2–5 s estimés (CPU téléphone) |
| NLLB-200 600M | appareil ² | 56,2 | 66,0 | 33,3 | ~3–8 s estimés |
| NLLB-200 1.3B | appareil ² | 56,1 | 63,2 | 46,6 | trop lourd (1,3 Go) |
| TranslateGemma 4B | appareil ² | 56,3 | 66,2 | 46,1 | ~75 s (5 s/segment, CPU PC) |
| Google (modèle Translate) | en ligne | 63,5 | 75,5 | 51,0 | ~1 s |
| **NVIDIA · Gemma 4 31B** | en ligne (essai) | **72,9** | 79,6 | 59,6 | **p50 135 s, p95 215 s** |
| NVIDIA · Nemotron 3 Super 120B | en ligne (essai) | 67,3 | 72,4 | 51,4 | p50 59 s ; 3 × 503 sur 5 |
| NVIDIA · DeepSeek V4.1 Flash | en ligne (essai) | > 240 s | 79,0 | **60,8** | > 240 s |
| NVIDIA · GLM 5.3 Flash | en ligne (essai) | > 240 s | **86,8** | 59,8 | > 240 s |
| NVIDIA · Kimi K3 | en ligne (essai) | > 240 s | > 240 s | non testé | > 240 s |
| NVIDIA · Nemotron 3.5 Lightning 30B | en ligne (essai) | raisonnement affiché | > 240 s | > 240 s | inutilisable |
| NVIDIA · Riva Translate 4B v2 / v1.1 | en ligne (essai) | **hébreu non supporté** (sortie inventée) | non évaluable en lot ³ | — | 1–3 s |
| NVIDIA · Gemma 3 4B / 12B, Mistral Large 2 | — | listés mais **non déployés** pour le compte (HTTP 404) | | | |

² Mesuré sur PC (GPU pour Opus/NLLB, CPU pour TranslateGemma) ; sur le téléphone il faudrait
intégrer ONNX Runtime / CTranslate2 (non fait). ³ Riva v2 répond en grec, v1.1 recopie l'anglais
dès qu'on lui passe plusieurs phrases ; il est conçu pour une phrase par requête.

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
| NVIDIA API catalogue (Gemma 4 31B…) | **72,9** (Gemma 4 31B) | non | compte NVIDIA (e-mail), **sans carte** | gratuit (essai, 40 req/min, pas de SLA) | essai : prototypage | — | oui (sauf Riva) |
| Google web sans clé (`client=gtx`) | = Google | non | non | gratuit | — | — | oui |

Le point d'accès Google sans clé n'est **pas retenu** : non documenté, contraire aux conditions
d'utilisation, bloqué par des 429/captcha (observé pendant ce banc). Il n'a servi qu'à mesurer la
qualité du modèle Google.

## 5. Modèles NVIDIA (API catalogue build.nvidia.com, essai gratuit) — 2026-09-25

**Limites officielles** : l'API catalogue est une « trial experience » limitée à **40 requêtes par
minute** ; NVIDIA ne publie pas les limites par modèle (réponse du personnel NVIDIA, forum
développeurs, 22–23/04/2025 : <https://forums.developer.nvidia.com/t/model-limits/331075>). Les
anciens crédits (1 000 à l'inscription) ne sont plus présentés comme le mécanisme de quota. Aucune
en-tête `x-ratelimit-*` ni `Retry-After` n'a été renvoyée pendant nos 50 requêtes. Accès : compte
développeur NVIDIA (e-mail), **sans carte**. Usage prévu : prototypage, pas de SLA.

**Protocole** (`tools/mt-bench/nvidia_bench.py`) : clé lue à l'exécution dans `.env` local non versionné,
jamais affichée ni écrite ; 1 requête toutes les 3 s au plus (≤ 20/min) ; budget dur 150 requêtes
(compteur `nvidia-usage.json`) ; arrêt au 2ᵉ HTTP 429 ; une requête par (modèle, paire) avec un
tableau JSON de phrases ; même consigne pour tous (traduction fidèle, concise, noms propres
conservés, rien ajouté), température 0 ; délai HTTP 240 s → au-delà, « trop lent pour un overlay ».

**Consommation** : **50 requêtes** sur 150 (1 liste des modèles, 39 lots de qualité, 10 écrans de
latence), **0 × HTTP 429**, 8 × HTTP 503 « Service temporarily overloaded » (Nemotron), 9 lots
> 240 s. Budget non épuisé ; le quota partagé du failover n'a pas été approché (≤ 20/min).

**Exemples he→fr** (voir aussi §3) :

| Source | Référence | ML Kit | Google | Gemma 4 31B | Nemotron 3 Super |
|---|---|---|---|---|---|
| נקבע מותו של הצעיר שנורה בגליל המערבי | Le décès du jeune homme blessé par balle… a été prononcé | La **jeune femme** a été déterminée… | …a été **déterminée** | Le décès du jeune homme abattu en Galilée occidentale **a été prononcé** | …jeune homme **tiré**… a été constatée |
| בכיר איראני: "לא נתגמש על תוכנית הגרעין" | …« Nous ne transigerons pas… » | Senior iranien : « Ne pas être **rencontré**… » | « Nous ne serons pas flexibles… » | « Nous ne ferons aucune concession… » | « Nous ne céderons pas… » |
| לכל המבזקים | Toutes les brèves | **Boire** tous les éclairs | À tous les **clignotants** | Tous les flashs | À tous les flashs |
| שני נופלים ביישוב הקטן, בית מול בית | Deux soldats tombés… maison face à maison | Deux **tombant**… colonie… | Deux **tombent**… | Deux tombés dans la petite localité, maison face à maison | Deux morts dans le petit hameau… |
| עם חודש ראשון מתנה! | Avec le premier mois offert ! | — | Avec un **cadeau du premier mois** ! | Avec le premier mois offert ! | Avec le premier mois offert! |
| חגיגת טרייד אין | La fête de la reprise (trade-in) | La célébration des échanges **n'a pas** | Le commerce en fête | Célébration du Trade-in | Célébration du trade-in |

**Hallucinations / ajouts** : aucune invention chez Gemma 4 31B (à la différence de
TranslateGemma 4B local : « nord de la Palestine ») ; une omission en en→he (« (Felidae) »).
Nemotron : un contresens mineur (« tiré »), guillemets restés échappés (`\"`). DeepSeek ajoute
« (Carnivora) ». Riva Translate sur de l'hébreu invente un texte anglais sans rapport (« The
driver of the Libyan rebels… »).

## 6. Recommandation révisée — contrainte « gratuit, sans carte »

Azure et Google Cloud sont écartés (carte bancaire) ; leur code reste dans l'application mais ils
ne sont plus proposés comme recommandés.

- **NVIDIA en ligne : qualité excellente, latence rédhibitoire pour l'overlay.** Gemma 4 31B est le
  meilleur moteur mesuré en he→fr (72,9, contre 44,5 pour ML Kit et 63,5 pour Google), mais un écran
  prend **2 à 4 minutes** (p50 135 s, p95 215 s) sur l'essai gratuit, avec des 503 fréquents ; les
  modèles plus petits/rapides (Gemma 3) ne sont pas déployés pour ce compte. Utilisable au mieux
  comme **mode « lecture différée »** (traduire un article et revenir plus tard), pas pour la bulle.
  Usage réaliste : quelques dizaines d'écrans par jour sont compatibles avec 40 RPM, mais la clé du
  compte partagé **ne doit pas être embarquée dans l'APK** (elle sert à d'autres usages) : l'utilisateur devrait
  créer son propre compte NVIDIA (gratuit, sans carte) et saisir sa clé, pour un service sans
  garantie de disponibilité.
- **Recommandation principale : traduction sur l'appareil avec NLLB-200 600M** (ONNX Runtime ou
  CTranslate2 int8, ~600 Mo, un seul modèle pour toutes les paires) : +11,7 chrF sur ML Kit en
  he→fr, +7,7 en en→fr, hors-ligne, gratuit, privé ; latence estimée quelques secondes par écran.
  Licence CC-BY-NC : acceptable pour un usage personnel, pas pour une publication commerciale
  (sinon Opus-MT tc-big, Apache-2.0, 52,8 en he→fr via l'anglais).
- **Repli : ML Kit** (déjà intégré, instantané) ; ML Kit reste meilleur que NLLB 600M en en→he
  (37,9 contre 33,3) → router en→he vers ML Kit.
- À ne pas retenir : TranslateGemma 4B local (lent, invente), Riva Translate (pas d'hébreu),
  modèles « raisonnement » (lents, sortie non conforme).

Décision attendue de l'utilisateur : intégrer NLLB-200 600M sur l'appareil (chantier : runtime
natif + tokeniseur SentencePiece + téléchargement du modèle ~600 Mo), et/ou un mode « lecture
différée » via NVIDIA avec sa propre clé.
