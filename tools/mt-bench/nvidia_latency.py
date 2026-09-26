"""Latency tuning of the NVIDIA API catalog for the overlay (2026-09-26).

Same safety rules as nvidia_bench.py (the key is shared with other uses of the account): key read at run
time from $NVIDIA_ENV_FILE, never printed; >= 3 s between request starts (<= 20/min) ; hard
budget of 150 requests for this round, counter persisted in nvidia-usage-2.json (the app tests on
the emulator are added to it by hand); stop at the second HTTP 429.

Measures one "screen" of 15 Hebrew blocks (corpus he-fr) -> French:
  - TTFT (time to first streamed token) and total time, per request;
  - screen time = time until every block is translated, first block time.

Usage:  python nvidia_latency.py MODEL MODE [N]
   MODE: whole   (1 streamed request for the 15 blocks)
         chunkK  (15 blocks split in requests of K blocks, sent in parallel within the rate limit)
"""
import json
import re
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

BASE = "https://integrate.api.nvidia.com/v1"
ENV_FILE = Path(__import__("os").environ.get("NVIDIA_ENV_FILE", ".env"))
HERE = Path(__file__).parent
USAGE = HERE / "nvidia-usage-2.json"
RESULTS = HERE / "nvidia-latency.json"
BUDGET = 150
MIN_INTERVAL_S = 3.0
HTTP_TIMEOUT_S = 60


def _load_key() -> str:
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*NVIDIA_API_KEY\s*=\s*(.+?)\s*$", line)
        if m:
            return m.group(1).strip().strip('"').strip("'")
    sys.exit("NVIDIA_API_KEY absent de .env")


_KEY = _load_key()
_lock = threading.Lock()
_state = {"last": 0.0}


def redact(text) -> str:
    return str(text).replace(_KEY, "***")


def _usage() -> dict:
    if USAGE.exists():
        return json.loads(USAGE.read_text(encoding="utf-8"))
    return {"budget": BUDGET, "requests": 0, "http_429": 0, "stopped": False, "log": []}


def _reserve(label: str) -> dict:
    """Rate limiter + budget: blocks until the request may start, counts it before sending."""
    with _lock:
        usage = _usage()
        if usage["stopped"]:
            sys.exit("Arrêt : 429 répétés.")
        if usage["requests"] >= BUDGET:
            sys.exit(f"Budget épuisé : {usage['requests']}/{BUDGET}.")
        # Sliding window: at most 20 request starts in any 60 s, >= 0.3 s apart (bursts of a screen's chunks).
        starts = _state.setdefault("starts", [])
        while True:
            now = time.time()
            starts[:] = [t for t in starts if now - t < 60]
            wait = max(0.3 - (now - _state["last"]), (starts[0] + 60 - now) if len(starts) >= 20 else 0)
            if wait <= 0:
                break
            time.sleep(wait)
        _state["last"] = time.time()
        starts.append(_state["last"])
        usage["requests"] += 1
        USAGE.write_text(json.dumps(usage, indent=1), encoding="utf-8")
        return usage


def _record(label: str, status: int, ttft: float | None, total: float):
    with _lock:
        usage = _usage()
        usage["log"].append({"t": time.strftime("%H:%M:%S"), "label": label, "status": status,
                             "ttft_ms": None if ttft is None else int(ttft * 1000), "ms": int(total * 1000)})
        if status == 429:
            usage["http_429"] += 1
            usage["stopped"] = usage["http_429"] >= 2
        USAGE.write_text(json.dumps(usage, indent=1), encoding="utf-8")


PROMPT = "Translate each line from {src} to {tgt}. Output only the translations, one per line, same order."
NAMES = {"he": "Hebrew", "fr": "French", "en": "English"}


def body(model: str, texts: list[str], src: str, tgt: str) -> dict:
    user = "\n".join(t.replace("\n", " ") for t in texts)
    # Output budget: ~2 tokens per source char is ample for fr (Hebrew is dense) + a little slack.
    max_tokens = min(1024, 24 + 3 * sum(len(t) for t in texts) // 2 + 8 * len(texts))
    msgs = [{"role": "system", "content": PROMPT.format(src=NAMES[src], tgt=NAMES[tgt])},
            {"role": "user", "content": user}]
    b = {"model": model, "messages": msgs, "temperature": 0, "max_tokens": max_tokens, "stream": True,
         "chat_template_kwargs": {"enable_thinking": False, "thinking": False}}
    if "plain" in sys.argv:  # probe: no extra parameter at all
        b.pop("chat_template_kwargs")
    if "nemotron" in model:
        msgs.insert(0, {"role": "system", "content": "/no_think"})
    return b


def stream_request(model: str, texts: list[str], label: str, t_screen: float) -> dict:
    _reserve(label)
    req = urllib.request.Request(
        BASE + "/chat/completions", method="POST",
        data=json.dumps(body(model, texts, "he", "fr")).encode(),
        headers={"Authorization": f"Bearer {_KEY}", "Content-Type": "application/json",
                 "Accept": "text/event-stream"},
    )
    t0 = time.time()
    ttft, content, status, err = None, [], 0, ""
    reasoning_chars = 0
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as r:
            status = r.status
            for raw in r:
                line = raw.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                chunk = json.loads(data)
                if not chunk.get("choices"):
                    if "error" in chunk or "status" in chunk:
                        err = redact(json.dumps(chunk, ensure_ascii=False))[:200]
                        break
                    continue  # usage / keep-alive chunk
                delta = chunk["choices"][0].get("delta", {})
                reasoning_chars += len(delta.get("reasoning_content") or "")
                piece = delta.get("content") or ""
                if piece and ttft is None:
                    ttft = time.time() - t0
                content.append(piece)
                if time.time() - t0 > HTTP_TIMEOUT_S:
                    err = "deadline"
                    break
    except urllib.error.HTTPError as e:
        status, err = e.code, redact(e.read().decode("utf-8", "replace")[:200])
    except Exception as e:  # noqa: BLE001
        status, err = -1, redact(type(e).__name__ + ": " + str(e))[:200]
    total = time.time() - t0
    _record(label, status, ttft, total)
    out = [l.strip() for l in "".join(content).splitlines() if l.strip()]
    ok = status == 200 and len(out) == len(texts) and not err
    print(f"  {label}: HTTP {status} ttft={ttft and round(ttft, 1)} s total={total:.1f} s "
          f"lines={len(out)}/{len(texts)} reasoning={reasoning_chars} {err}", flush=True)
    return {"ok": ok, "status": status, "ttft": ttft, "total": total, "done_at": time.time() - t_screen,
            "out": out, "err": err}


def screen(model: str, mode: str, idx: int) -> dict:
    corpus = json.load(open(HERE / "corpus.json", encoding="utf-8"))
    he = [c["src"] for c in corpus if c["pair"] == "he-fr"][:15]
    if mode.startswith("first"):  # only the first K blocks (probe)
        he = he[:int(mode[5:])]
    k = len(he) if mode == "whole" or mode.startswith("first") else int(mode[5:])
    chunks = [he[i:i + k] for i in range(0, len(he), k)]
    t_screen = time.time()
    with ThreadPoolExecutor(max_workers=len(chunks)) as pool:
        res = list(pool.map(lambda c: stream_request(model, c[1], f"{model} {mode} #{idx} part{c[0]}", t_screen),
                            enumerate(chunks)))
    ok = all(r["ok"] for r in res)
    first = min((r["done_at"] for r in res if r["ok"]), default=None)
    total = max(r["done_at"] for r in res)
    print(f"ECRAN {model} {mode} #{idx}: ok={ok} premier lot={first and round(first, 1)} s "
          f"écran complet={total:.1f} s", flush=True)
    return {"ok": ok, "first": first, "total": total, "parts": [{k2: v for k2, v in r.items() if k2 != "out"} for r in res],
            "out": [l for r in res for l in r["out"]]}


def quality(model: str):
    """chrF++ on the whole corpus with the overlay prompt (one streamed request per pair)."""
    import sacrebleu
    global body
    corpus = json.load(open(HERE / "corpus.json", encoding="utf-8"))
    results = json.loads(RESULTS.read_text(encoding="utf-8")) if RESULTS.exists() else {}
    base_body = body
    for pair in ("he-fr", "en-fr", "en-he"):
        key = f"{model}|quality|{pair}"
        items = [c for c in corpus if c["pair"] == pair]
        if not results.get(key, {}).get("out"):
            src, tgt = pair.split("-")
            body = lambda m, t, s_, t_: base_body(m, t, src, tgt)  # noqa: E731
            r = stream_request(model, [c["src"] for c in items], f"{model} quality {pair}", time.time())
            results[key] = {"ok": r["ok"], "total": r["total"], "ttft": r["ttft"], "out": r["out"] if r["ok"] else None}
            RESULTS.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
        out = results[key]["out"]
        if out:
            score = sacrebleu.corpus_chrf(out, [[c["ref"] for c in items]], word_order=2).score
            print(f"QUALITE {model} {pair}: chrF++={score:.1f} ({results[key]['total']:.1f} s)")
        else:
            print(f"QUALITE {model} {pair}: ECHEC")


if __name__ == "__main__" and sys.argv[2] == "quality":
    quality(sys.argv[1])
    print(f"Requêtes NVIDIA (ce tour) : {_usage()['requests']}/{BUDGET}")
    sys.exit(0)

if __name__ == "__main__":
    model, mode = sys.argv[1], sys.argv[2]
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    results = json.loads(RESULTS.read_text(encoding="utf-8")) if RESULTS.exists() else {}
    runs = results.setdefault(f"{model}|{mode}", [])
    for i in range(n):
        runs.append(screen(model, mode, len(runs) + 1))
        RESULTS.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    ok = [r["total"] for r in runs if r["ok"]]
    if ok:
        s = sorted(ok)
        print(f"{model} {mode}: n={len(s)}/{len(runs)} p50={statistics.median(s):.1f} s "
              f"p95={s[min(len(s) - 1, round(0.95 * (len(s) - 1)))]:.1f} s")
    print(f"Requêtes NVIDIA (ce tour) : {_usage()['requests']}/{BUDGET}")

