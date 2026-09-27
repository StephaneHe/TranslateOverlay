"""Which COMBINATION of free online models (NVIDIA API catalog) translates real screens best? (2026-09-27)

Study only. Screens: combos_corpus.json (real ynet / Wikipedia screens, hand-made references).
Configurations: b0 = the app's 1.8.0 prompt; b1 = + screen context (app/URL/title) and specialised rules;
other models with b1; review pass (2nd model fixes the 1st translation); judge (picks A/B per block);
image pipeline (vision reading -> text model translation).

SAFETY (key shared with other uses of the account): read at run time from $NVIDIA_ENV_FILE, never
printed/written; >= 3.2 s between request starts (<= 20/min); HARD budget 80 requests (counted before
sending, nvidia-usage-combos.json); stop everything at the 2nd HTTP 429; a model is dropped after 3 HTTP 503.

Usage (venv python):
  combos_bench.py run CONFIG MODEL SCREEN...        CONFIG = b0 | b1
  combos_bench.py review REVIEWER BASE SCREEN...    BASE = result key prefix, e.g. b1|nvidia/nemotron-3-ultra-550b-a55b
  combos_bench.py judge JUDGE BASE_A BASE_B SCREEN...
  combos_bench.py image MODEL                        translate the vision readings (Llama 11B, Tesseract, reference)
  combos_bench.py score
"""
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent
SCREENS = {s["id"]: s for s in json.loads((HERE / "combos_corpus.json").read_text(encoding="utf-8"))["screens"]}
RESULTS = HERE / "combos-results.json"
USAGE = HERE / "nvidia-usage-combos.json"
BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
ENV_FILE = Path(__import__("os").environ.get("NVIDIA_ENV_FILE", ".env"))
BUDGET = 80
MIN_INTERVAL_S = 3.2
TIMEOUT_S = 75
LANG = {"he": ("Hebrew", "hébreu"), "fr": ("French", "français"), "en": ("English", "English")}
TARGET_NATIVE = {"fr": "French (français)", "he": "Hebrew (עברית)", "en": "English"}


def _key() -> str:
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*NVIDIA_API_KEY\s*=\s*(.+?)\s*$", line)
        if m:
            return m.group(1).strip().strip('"').strip("'")
    sys.exit("NVIDIA_API_KEY absent")


KEY = None
_last = [0.0]


def usage() -> dict:
    if USAGE.exists():
        return json.loads(USAGE.read_text(encoding="utf-8"))
    return {"budget": BUDGET, "requests": 0, "http_429": 0, "http_503": {}, "dropped": [], "stopped": False, "log": []}


def save_usage(u):
    USAGE.write_text(json.dumps(u, indent=1, ensure_ascii=False), encoding="utf-8")


def load_results() -> dict:
    return json.loads(RESULTS.read_text(encoding="utf-8")) if RESULTS.exists() else {}


def save_results(r):
    RESULTS.write_text(json.dumps(r, ensure_ascii=False, indent=1), encoding="utf-8")


# ------------------------------------------------------------------ prompts
def numbered(texts):
    return "\n".join(f"[{i + 1}] " + re.sub(r"\s*\n\s*", " ", t).strip() for i, t in enumerate(texts))


def prompt_b0(screen, texts):
    src, tgt = LANG[screen["source"]][0], LANG[screen["target"]][0]
    system = (f"Translate each numbered line from {src} into {tgt}. Answer in {tgt} only: one line per input, "
              "starting with the same [number], then the translation only.")
    return system, numbered(texts) + f"\n\nAnswer in {TARGET_NATIVE[screen['target']]}."


def prompt_b1(screen, texts):
    src, tgt = LANG[screen["source"]][0], LANG[screen["target"]][0]
    system = (
        f"You translate the text of a phone screen from {src} into {tgt} for an overlay that replaces each text block in place.\n"
        f"Screen: app {screen['app']}, page {screen['url']} — title « {screen['title']} ».\n"
        "The numbered blocks are the screen's texts, top to bottom (menus, headlines, article text, buttons, ads, small print): "
        "use them as context for each other, especially for short labels.\n"
        f"Rules: accurate and natural {tgt}, as a native news site or app would write it; menu items and buttons stay short "
        "(use the usual label of such sites); headlines keep a headline style; keep proper nouns (people, places, brands, "
        f"media) in their usual {tgt} form; keep numbers, dates, prices and quotes; add nothing, explain nothing; keep each "
        "translation about as long as the original.\n"
        "Answer: one line per block, starting with the same [number], then the translation only.")
    return system, numbered(texts) + f"\n\nAnswer in {TARGET_NATIVE[screen['target']]}."


def prompt_b2(screen, texts):
    """b1 + UI-label rule (no example taken from the references, to avoid biasing the measure)."""
    system, user = prompt_b1(screen, texts)
    tgt = LANG[screen["target"]][0]
    system = system.replace("Answer: one line per block", (
        f"For a menu item, tab, section name or button, do not translate literally: use the concise label that {tgt} "
        "websites and apps conventionally show for that function (a section name for a section, an infinitive or short "
        "imperative for an action button).\nAnswer: one line per block"))
    return system, user


def prompt_review(screen, texts, draft):
    tgt = LANG[screen["target"]][0]
    system = (
        f"You review a {tgt} translation of a phone screen (app {screen['app']}, page {screen['url']}). For each numbered block "
        "you get the source and the draft translation. Fix only real errors: wrong meaning, wrong word sense for the context, "
        f"missing or added content, untranslated words, unnatural {tgt}, wrong register for a menu/headline. If the draft is "
        "right, repeat it unchanged. Answer: one line per block, starting with the same [number], then the final translation only.")
    lines = [f"[{i + 1}] SOURCE: {re.sub(r'\s+', ' ', s)}\n    DRAFT: {d or ''}" for i, (s, d) in enumerate(zip(texts, draft))]
    return system, "\n".join(lines) + f"\n\nAnswer in {TARGET_NATIVE[screen['target']]}."


def prompt_judge(screen, texts, a, b):
    tgt = LANG[screen["target"]][0]
    system = (
        f"For each numbered block of a phone screen (page {screen['url']}), choose the better {tgt} translation: A or B "
        "(faithful meaning first, then natural and concise). Answer one line per block: [number] A or [number] B. Nothing else.")
    lines = [f"[{i + 1}] SOURCE: {re.sub(r'\s+', ' ', s)}\n    A: {x or ''}\n    B: {y or ''}" for i, (s, x, y) in enumerate(zip(texts, a, b))]
    return system, "\n".join(lines)


# ------------------------------------------------------------------ call (streamed, numbered lines)
LINE = re.compile(r"^\s*\[(\d{1,3})]\s*(.*)$|^\s*(\d{1,3})\s*[.):\-–]\s+(.*)$")


def call(model, system, user, n, label, max_tokens=None):
    global KEY
    u = usage()
    if u["stopped"] or u["requests"] >= BUDGET:
        sys.exit(f"Arrêt : budget {u['requests']}/{BUDGET} ou 429 répétés.")
    if model in u["dropped"]:
        print(f"   {model} écarté (503 répétés)")
        return None
    KEY = KEY or _key()
    wait = MIN_INTERVAL_S - (time.time() - _last[0])
    if wait > 0:
        time.sleep(wait)
    _last[0] = time.time()
    u["requests"] += 1
    save_usage(u)
    msgs = [{"role": "system", "content": system}, {"role": "user", "content": user}]
    body = {"model": model, "messages": msgs, "temperature": 0, "stream": True,
            "max_tokens": max_tokens or min(8192, 64 + 3 * len(user) + 16 * n),
            "chat_template_kwargs": {"enable_thinking": False, "thinking": False}}
    if "nemotron" in model:
        msgs.insert(0, {"role": "system", "content": "/no_think"})
    req = urllib.request.Request(BASE_URL, method="POST", data=json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json", "Accept": "text/event-stream"})
    t0 = time.time()
    status, err, ttft, first_block, content, reasoning = 0, "", None, None, [], 0
    buf = ""
    got = {}
    current = None
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as r:
            status = r.status
            for raw in r:
                line = raw.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                chunk = json.loads(data)
                if "error" in chunk:
                    status, err = chunk["error"].get("code", 500), str(chunk["error"].get("message"))[:160]
                    break
                if not chunk.get("choices"):
                    continue
                d = chunk["choices"][0].get("delta", {})
                reasoning += len(d.get("reasoning_content") or "")
                piece = d.get("content") or ""
                if piece and ttft is None:
                    ttft = time.time() - t0
                content.append(piece)
                buf += piece
                while "\n" in buf:
                    ln, buf = buf.split("\n", 1)
                    m = LINE.match(ln.strip())
                    if m:
                        k = int(m.group(1) or m.group(3))
                        if 1 <= k <= n and k not in got:
                            got[k] = (m.group(2) or m.group(4) or "").strip()
                            current = k
                            if first_block is None and got[k]:
                                first_block = time.time() - t0
                            continue
                    if current and ln.strip():
                        got[current] = (got[current] + " " + ln.strip()).strip()
                if time.time() - t0 > TIMEOUT_S:
                    err = "deadline"
                    break
    except urllib.error.HTTPError as e:
        status, err = e.code, e.read().decode("utf-8", "replace")[:200]
    except Exception as e:  # noqa: BLE001
        status, err = -1, (type(e).__name__ + ": " + str(e))[:160]
    if buf.strip():
        m = LINE.match(buf.strip())
        if m:
            k = int(m.group(1) or m.group(3))
            if 1 <= k <= n and k not in got:
                got[k] = (m.group(2) or m.group(4) or "").strip()
                first_block = first_block or (time.time() - t0)
        elif current:
            got[current] = (got[current] + " " + buf.strip()).strip()
    total = time.time() - t0
    err = err.replace(KEY, "***") if KEY else err
    err = re.sub(r"account '[^']+'", "account '***'", err)
    u = usage()
    u["log"].append({"t": time.strftime("%H:%M:%S"), "label": label, "status": status, "ttft": ttft and round(ttft, 2),
                     "first_block": first_block and round(first_block, 2), "total": round(total, 2)})
    if status == 429:
        u["http_429"] += 1
        u["stopped"] = u["http_429"] >= 2
    if status == 503:
        u["http_503"][model] = u["http_503"].get(model, 0) + 1
        if u["http_503"][model] >= 3 and model not in u["dropped"]:
            u["dropped"].append(model)
    save_usage(u)
    out = [got.get(i + 1) for i in range(n)]
    print(f"[{u['requests']}/{BUDGET}] {label}: HTTP {status} ttft={ttft and round(ttft, 1)} premier bloc={first_block and round(first_block, 1)} "
          f"total={total:.1f} s reçus={sum(x is not None for x in out)}/{n} raisonnement={reasoning} {err}", flush=True)
    return {"status": status, "ttft": ttft, "first_block": first_block, "total": total, "out": out,
            "raw": "".join(content)[:6000], "err": err, "reasoning_chars": reasoning}


# ------------------------------------------------------------------ commands
def cmd_run(config, model, screen_ids):
    res = load_results()
    for sid in screen_ids:
        key = f"{config}|{model}|{sid}"
        if res.get(key, {}).get("status") == 200 and all(x is not None for x in res[key]["out"]):
            continue
        s = SCREENS[sid]
        texts = [b["src"] for b in s["blocks"]]
        prompt = prompt_b0 if config.startswith("b0") else prompt_b2 if config.startswith("b2") else prompt_b1
        system, user = prompt(s, texts)  # "b1#2" = repeat of b1
        r = call(model, system, user, len(texts), f"{config} {model} {sid}")
        if r is None:
            return
        res[key] = r
        save_results(res)


def cmd_review(reviewer, base, screen_ids):
    res = load_results()
    for sid in screen_ids:
        draft = res[f"{base}|{sid}"]["out"]
        s = SCREENS[sid]
        texts = [b["src"] for b in s["blocks"]]
        system, user = prompt_review(s, texts, draft)
        r = call(reviewer, system, user, len(texts), f"review {reviewer} <- {base} {sid}")
        if r is None:
            return
        r["out"] = [o if o else d for o, d in zip(r["out"], draft)]  # missing line: keep the draft
        res[f"review[{reviewer}]<{base}|{sid}"] = r
        save_results(res)


def cmd_judge(judge, a, b, screen_ids):
    res = load_results()
    for sid in screen_ids:
        A, B = res[f"{a}|{sid}"]["out"], res[f"{b}|{sid}"]["out"]
        s = SCREENS[sid]
        texts = [b_["src"] for b_ in s["blocks"]]
        system, user = prompt_judge(s, texts, A, B)
        r = call(judge, system, user, len(texts), f"judge {judge} {sid}", max_tokens=16 * len(texts) + 32)
        if r is None:
            return
        picks = [(p or "A").strip().upper()[:1] for p in r["out"]]
        r["picks"] = picks
        r["out"] = [(y if p == "B" else x) or x or y for p, x, y in zip(picks, A, B)]
        res[f"judge[{judge}]<{a}+{b}|{sid}"] = r
        save_results(res)


def cmd_image(model):
    """Vision readings (vision-results.json) translated by a text model: one request for the 4 banners."""
    vres = json.loads((HERE / "vision-results.json").read_text(encoding="utf-8"))
    vcorp = {i["id"]: i for i in json.loads((HERE / "vision_corpus.json").read_text(encoding="utf-8"))["images"]}
    banners = ["b0_menoupais", "b1_kumu", "b3_kkl", "b4_geely"]
    res = load_results()
    screen = dict(SCREENS["S2_banners"])
    for reader in ["reference", "meta/llama-3.2-11b-vision-instruct", "tesseract"]:
        key = f"image[{reader}]>{model}"
        if res.get(key, {}).get("status") == 200:
            continue
        texts, owner = [], []
        for bid in banners:
            if reader == "reference":
                lines = vcorp[bid]["lines"]
            else:
                lines = sorted(vres[f"{reader}|{bid}"]["lines"] or [], key=lambda L: ((L.get("box") or [0, 0])[1] // 40, (L.get("box") or [0])[0]))
            for L in lines:
                t = L["text"].strip()
                if len(t) >= 2:
                    texts.append(t)
                    owner.append(bid)
        system, user = prompt_b1(screen, texts)
        r = call(model, system, user, len(texts), f"image {reader} -> {model}")
        if r is None:
            return
        r["owner"], r["texts"] = owner, texts
        res[key] = r
        save_results(res)


def cmd_score():
    import sacrebleu
    res = load_results()
    rows = []
    for key, r in sorted(res.items()):
        if key.startswith("image["):
            continue
        cfg, sid = key.rsplit("|", 1)
        s = SCREENS[sid]
        refs = [b["ref"] for b in s["blocks"]]
        hyp = [o or "" for o in r["out"]]
        tokenize = "13a" if s["target"] != "he" else "char"
        chrf = sacrebleu.corpus_chrf(hyp, [refs], word_order=2).score
        short = [i for i, b in enumerate(s["blocks"]) if b["t"] == "menu"]
        chrf_menu = sacrebleu.corpus_chrf([hyp[i] for i in short], [[refs[i] for i in short]], word_order=2).score if short else None
        rows.append({"config": cfg, "screen": sid, "chrF++": round(chrf, 1), "chrF++ menus": chrf_menu and round(chrf_menu, 1),
                     "missing": sum(o is None for o in r["out"]), "ttft": r.get("ttft") and round(r["ttft"], 1),
                     "first_block": r.get("first_block") and round(r["first_block"], 1), "total": round(r.get("total", 0), 1),
                     "status": r.get("status")})
    vcorp = {i["id"]: i for i in json.loads((HERE / "vision_corpus.json").read_text(encoding="utf-8"))["images"]}
    for key, r in sorted(res.items()):
        if not key.startswith("image["):
            continue
        per = {}
        for t, o in zip(r["owner"], r["out"]):
            per.setdefault(t, []).append(o or "")
        banners = sorted(per)
        hyp = [" ".join(per[b]) for b in banners]
        refs = [vcorp[b]["fr_full"] for b in banners]
        rows.append({"config": key, "screen": "S2 (lecture → traduction)", "chrF++": round(sacrebleu.corpus_chrf(hyp, [refs], word_order=2).score, 1),
                     "total": round(r.get("total", 0), 1), "status": r.get("status")})
    (HERE / "combos-scores.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    for row in rows:
        print(row)
    u = usage()
    print(f"Requêtes NVIDIA (étude combinaisons) : {u['requests']}/{BUDGET}, 429={u['http_429']}, 503={u['http_503']}, écartés={u['dropped']}")


if __name__ == "__main__":
    c = sys.argv[1]
    if c == "run":
        cmd_run(sys.argv[2], sys.argv[3], sys.argv[4:])
    elif c == "review":
        cmd_review(sys.argv[2], sys.argv[3], sys.argv[4:])
    elif c == "judge":
        cmd_judge(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5:])
    elif c == "image":
        cmd_image(sys.argv[2])
    elif c == "score":
        cmd_score()
