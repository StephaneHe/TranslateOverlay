"""NVIDIA API catalog (integrate.api.nvidia.com) translation benchmark, same corpus as bench.py.

SAFETY (the key is shared with other uses of the account):
- the key is read at run time from $NVIDIA_ENV_FILE (NVIDIA_API_KEY) and never printed,
  logged, written or sent anywhere but integrate.api.nvidia.com (any output is redacted);
- global limiter: at most 1 request every 3 s (<= 20/min; NVIDIA trial limit: 40 RPM);
- hard budget of 150 requests for the whole study, counter persisted in nvidia-usage.json;
- on HTTP 429: honour Retry-After once, stop all calls at the second 429.

Usage:  .venv/Scripts/python nvidia_bench.py models            (1 request: list models)
        .venv/Scripts/python nvidia_bench.py run MODEL [...]    (1 request per model and pair)
        .venv/Scripts/python nvidia_bench.py latency MODEL N    (N "screen" requests of 15 blocks)
        .venv/Scripts/python nvidia_bench.py score              (no request: chrF++ tables)
"""
import json
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = "https://integrate.api.nvidia.com/v1"
ENV_FILE = Path(__import__("os").environ.get("NVIDIA_ENV_FILE", ".env"))
HERE = Path(__file__).parent
USAGE = HERE / "nvidia-usage.json"
RESULTS = HERE / "nvidia-results.json"
BUDGET = 150
MIN_INTERVAL_S = 3.0
HTTP_TIMEOUT_S = 240
RUN_DEADLINE_S = 330  # last request may still take HTTP_TIMEOUT_S: 330 + 240 < 600 s (Bash timeout)
_T_START = time.time()
NAMES = {"he": "Hebrew", "fr": "French", "en": "English"}


def _load_key() -> str:
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*NVIDIA_API_KEY\s*=\s*(.+?)\s*$", line)
        if m:
            return m.group(1).strip().strip('"').strip("'")
    sys.exit("NVIDIA_API_KEY absent de .env")


_KEY = _load_key()


def redact(text) -> str:
    return str(text).replace(_KEY, "***") if _KEY else str(text)


def _usage() -> dict:
    if USAGE.exists():
        return json.loads(USAGE.read_text(encoding="utf-8"))
    return {"budget": BUDGET, "requests": 0, "http_429": 0, "stopped": False, "log": []}


_state = {"last": 0.0}


def call(method: str, path: str, body: dict | None = None, label: str = "") -> tuple[int, dict | None, float]:
    usage = _usage()
    if usage["stopped"]:
        sys.exit("Arrêt : appels NVIDIA suspendus (429 répétés). Voir nvidia-usage.json.")
    if usage["requests"] >= BUDGET:
        sys.exit(f"Budget épuisé : {usage['requests']}/{BUDGET} requêtes.")
    wait = MIN_INTERVAL_S - (time.time() - _state["last"])
    if wait > 0:
        time.sleep(wait)
    for attempt in (1, 2):
        usage["requests"] += 1
        USAGE.write_text(json.dumps(usage, indent=1), encoding="utf-8")  # count before sending
        req = urllib.request.Request(
            BASE + path, method=method,
            data=json.dumps(body).encode() if body is not None else None,
            headers={"Authorization": f"Bearer {_KEY}", "Content-Type": "application/json", "Accept": "application/json"},
        )
        t0 = time.time()
        status, data, headers = 0, None, {}
        try:
            with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as r:
                status, headers = r.status, dict(r.headers)
                data = json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            status, headers = e.code, dict(e.headers or {})
            data = {"error": redact(e.read().decode("utf-8", "replace")[:300])}
        except Exception as e:  # noqa: BLE001 - network error
            status, data = -1, {"error": redact(type(e).__name__ + ": " + str(e))[:300]}
        ms = (time.time() - t0) * 1000
        _state["last"] = time.time()
        limits = {k: v for k, v in headers.items() if k.lower().startswith(("x-ratelimit", "retry-after", "ratelimit"))}
        usage["log"].append({"t": time.strftime("%H:%M:%S"), "label": label, "status": status, "ms": int(ms), "limits": limits})
        if status == 429:
            usage["http_429"] += 1
            if usage["http_429"] >= 2:
                usage["stopped"] = True
                USAGE.write_text(json.dumps(usage, indent=1), encoding="utf-8")
                sys.exit("Deuxième HTTP 429 : arrêt de tous les appels NVIDIA.")
            USAGE.write_text(json.dumps(usage, indent=1), encoding="utf-8")
            retry = float(headers.get("Retry-After", headers.get("retry-after", "30")) or 30)
            print(f"HTTP 429 ({label}) : pause {retry:.0f} s (Retry-After)", flush=True)
            time.sleep(retry)
            continue
        USAGE.write_text(json.dumps(usage, indent=1), encoding="utf-8")
        print(f"[{usage['requests']}/{BUDGET}] {label} -> HTTP {status} en {ms:.0f} ms {limits or ''}", flush=True)
        return status, data, ms
    return 429, None, 0.0


SYSTEM = ("You are a professional translator. Translate each string of the JSON array from {src} to {tgt}. "
          "Be faithful and concise, keep proper nouns, numbers and punctuation style, add nothing, no notes. "
          "Return ONLY a JSON array of translated strings, same length and same order.")


def parse_output(content: str, n: int, lines_mode: bool) -> list[str] | None:
    """JSON array (tolerating « » quotes), else one line per input."""
    content = re.sub(r"<think>.*?</think>", "", content, flags=re.S).strip()
    if not lines_mode:
        m = re.search(r"\[.*\]", content, flags=re.S)
        for candidate in ([m.group(0)] if m else []) + ([re.sub(r"[«»“”]", '"', m.group(0))] if m else []):
            try:
                out = json.loads(candidate)
                if isinstance(out, list) and len(out) == n:
                    return [str(x).strip() for x in out]
            except json.JSONDecodeError:
                pass
    lines = [re.sub(r"^\s*(\d+[.)]\s*|[-*]\s+)", "", l).strip() for l in content.splitlines() if l.strip()]
    return lines if len(lines) == n else None


def translate_batch(model: str, texts: list[str], src: str, tgt: str, label: str) -> tuple[list[str] | None, float, str, str]:
    riva = "riva-translate" in model
    if riva:  # model card format: plain instruction, document-level; one line per sentence
        messages = [
            {"role": "system", "content": f"You are an expert at translating text from {NAMES[src]} to {NAMES[tgt]}."},
            {"role": "user", "content": "\n".join(texts)},
        ]
    else:
        messages = [
            {"role": "system", "content": SYSTEM.format(src=NAMES[src], tgt=NAMES[tgt])},
            {"role": "user", "content": json.dumps(texts, ensure_ascii=False)},
        ]
        if "nemotron" in model:  # Nemotron: hidden reasoning otherwise eats the output budget (truncated answer)
            messages.insert(0, {"role": "system", "content": "detailed thinking off"})
    body = {"model": model, "temperature": 0, "top_p": 1, "max_tokens": 3000, "stream": False, "messages": messages}
    status, data, ms = call("POST", "/chat/completions", body, label)
    if status == -1 and ms >= (HTTP_TIMEOUT_S - 5) * 1000:
        return None, ms, f"TROP LENT pour un overlay (> {HTTP_TIMEOUT_S} s)", ""
    if status == 404 and "Not found for account" in json.dumps(data):
        return None, ms, "NON DÉPLOYÉ pour ce compte (HTTP 404)", ""
    if status != 200 or not data:
        return None, ms, redact(json.dumps(data, ensure_ascii=False))[:300], ""
    content = data["choices"][0]["message"].get("content") or ""
    out = parse_output(content, len(texts), lines_mode=riva)
    if out is None:
        return None, ms, "réponse non conforme: " + content[:200], content
    return out, ms, "", content


def cmd_models():
    status, data, _ = call("GET", "/models", label="GET /models")
    ids = sorted(m["id"] for m in (data or {}).get("data", []))
    (HERE / "nvidia-models.json").write_text(json.dumps(ids, indent=1), encoding="utf-8")
    print(len(ids), "modèles")


def cmd_run(models: list[str], pairs: list[str]):
    corpus = json.load(open(HERE / "corpus.json", encoding="utf-8"))
    results = json.loads(RESULTS.read_text(encoding="utf-8")) if RESULTS.exists() else {}
    for model in models:
        for pair in pairs:
            key = f"{model}|{pair}"
            prev = results.get(key, {})
            if any(str(v.get("error", "")).startswith("NON DÉPLOYÉ") for k, v in results.items() if k.startswith(model + "|")):
                continue  # model not deployed for this account: don't spend the other pairs
            if prev.get("out") or str(prev.get("error", "")).startswith(
                ("TROP LENT", "réponse non conforme", "NON DÉPLOYÉ", "HÉBREU NON"),
            ):
                continue  # done, or not worth another request
            if time.time() - _T_START > RUN_DEADLINE_S:
                print("Limite de durée de l'exécution atteinte : relancer pour continuer.")
                return
            src, tgt = pair.split("-")
            items = [c for c in corpus if c["pair"] == pair]
            out, ms, err, raw = translate_batch(model, [c["src"] for c in items], src, tgt, f"{model} {pair} x{len(items)}")
            results[key] = {"ids": [c["id"] for c in items], "out": out, "ms": int(ms), "error": err, "raw": raw}
            RESULTS.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
            if err:
                print("   !", err[:160])


def cmd_latency(model: str, n: int):
    """A realistic 'screen': 15 short blocks (headlines, labels, ad lines) he->fr."""
    corpus = json.load(open(HERE / "corpus.json", encoding="utf-8"))
    he = [c["src"] for c in corpus if c["pair"] == "he-fr"][:15]
    results = json.loads(RESULTS.read_text(encoding="utf-8")) if RESULTS.exists() else {}
    samples = results.setdefault(f"{model}|latency", {"ms": []})["ms"]
    for i in range(n):
        if time.time() - _T_START > RUN_DEADLINE_S:
            print("Limite de durée de l'exécution atteinte.")
            return
        out, ms, err, _ = translate_batch(model, he, "he", "fr", f"{model} écran#{i + 1}")
        if err.startswith("TROP LENT"):
            samples.append(int(ms))
            print("   ! trop lent, arrêt des mesures pour ce modèle")
            return
        if out:
            samples.append(int(ms))
        RESULTS.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")


def cmd_score():
    import sacrebleu
    corpus = {c["id"]: c for c in json.load(open(HERE / "corpus.json", encoding="utf-8"))}
    results = json.loads(RESULTS.read_text(encoding="utf-8"))
    for key, r in sorted(results.items()):
        if key.endswith("|latency"):
            ms = sorted(r["ms"])
            if ms:
                p95 = ms[min(len(ms) - 1, round(0.95 * (len(ms) - 1)))]
                print(f"{key}: n={len(ms)} p50={statistics.median(ms):.0f} ms p95={p95} ms")
            continue
        if not r.get("out"):
            print(f"{key}: ÉCHEC {r.get('error', '')[:120]}")
            continue
        refs = [corpus[i]["ref"] for i in r["ids"]]
        score = sacrebleu.corpus_chrf(r["out"], [refs], word_order=2).score
        print(f"{key}: chrF++={score:.1f} (batch {r['ms']} ms)")


if __name__ == "__main__":
    try:
        cmd = sys.argv[1]
        if cmd == "models":
            cmd_models()
        elif cmd == "run":
            args = sys.argv[2:]
            pairs = [a for a in args if re.fullmatch(r"[a-z]{2}-[a-z]{2}", a)] or ["he-fr", "en-fr", "en-he"]
            cmd_run([a for a in args if a not in pairs], pairs)
        elif cmd == "latency":
            cmd_latency(sys.argv[2], int(sys.argv[3]))
        elif cmd == "score":
            cmd_score()
        u = _usage()
        print(f"Requêtes NVIDIA consommées : {u['requests']}/{BUDGET}, 429 : {u['http_429']}")
    except SystemExit as e:
        print(redact(e))
        raise
