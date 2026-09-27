"""Vision-model OCR benchmark (NVIDIA API catalog) vs the app's local OCR — study only (2026-09-27).

Can a multimodal model read the text of an image (Hebrew ad banners where Tesseract/ML Kit fail),
give usable boxes and translate it, in one request per image?

SAFETY (the key is shared with other uses of the account): read at run time from $NVIDIA_ENV_FILE,
never printed/written; >= 3.2 s between request starts (<= 20/min); HARD budget of 20 requests for
this study, counter persisted in nvidia-usage-vision.json (counted before sending); stop at the
second 429 or the third 503.

Usage (venv python):
  vision_bench.py prep                  crop + JPEG-encode the corpus (no request)
  vision_bench.py ocr                   local Tesseract baseline (heb best = the app's model; no request)
  vision_bench.py run MODEL [IMG ...]   1 request per image
  vision_bench.py score                 tables (no request)
"""
import base64
import io
import json
import re
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent
ROOT = HERE.parent.parent
CORPUS = json.loads((HERE / "vision_corpus.json").read_text(encoding="utf-8"))["images"]
OUT = HERE / "vision"
RESULTS = HERE / "vision-results.json"
USAGE = HERE / "nvidia-usage-vision.json"
BASE = "https://integrate.api.nvidia.com/v1"
ENV_FILE = Path(__import__("os").environ.get("NVIDIA_ENV_FILE", ".env"))
BUDGET = 20
MIN_INTERVAL_S = 3.2
HTTP_TIMEOUT_S = int(__import__("os").environ.get("VISION_TIMEOUT", "90"))
MAX_SIDE = 800          # px, longest side sent
JPEG_QUALITY = 80
TESSERACT = Path.home() / "scoop/apps/tesseract/current/tesseract.exe"
TESSDATA = Path("I:/tmp/vision/tessdata")  # heb.traineddata copied from the app (tessdata_best) + eng best

PROMPT = (
    "Find every piece of text visible in this image. For each text line return its exact text as written "
    "(original language, do not translate it there), its bounding box [x0, y0, x1, y1] with coordinates from 0 to "
    "1000 relative to the image width and height, and its French translation. Do not invent text that is not "
    "visible. Answer ONLY with a JSON array like "
    '[{"text": "...", "box": [x0, y0, x1, y1], "fr": "..."}].'
)


# ---------------------------------------------------------------- corpus
def image_bytes(img) -> bytes:
    return (OUT / f"{img['id']}.jpg").read_bytes()


def cmd_prep():
    from PIL import Image
    OUT.mkdir(exist_ok=True)
    for img in CORPUS:
        if not img.get("crop"):
            continue  # derived image, already in vision/
        im = Image.open(ROOT / img["src"]).convert("RGB").crop(tuple(img["crop"]))
        scale = MAX_SIDE / max(im.size)
        if scale < 1:
            im = im.resize((round(im.width * scale), round(im.height * scale)), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=JPEG_QUALITY)
        (OUT / f"{img['id']}.jpg").write_bytes(buf.getvalue())
        print(f"{img['id']}: {im.size[0]}x{im.size[1]} jpeg {len(buf.getvalue()) / 1024:.0f} KB, base64 "
              f"{len(base64.b64encode(buf.getvalue())) / 1024:.0f} KB")


# ---------------------------------------------------------------- local OCR baseline
def cmd_ocr():
    from PIL import Image
    results = json.loads(RESULTS.read_text(encoding="utf-8")) if RESULTS.exists() else {}
    for img in CORPUS:
        src = OUT / f"{img['id']}.jpg"
        w, h = Image.open(src).size
        lang = "heb+eng" if img["lang"] == "he" else "eng"
        t0 = time.time()
        tsv = subprocess.run([str(TESSERACT), str(src), "stdout", "--tessdata-dir", str(TESSDATA), "-l", lang, "--psm", "11", "-c", "tessedit_create_tsv=1"],
                             capture_output=True, text=True, encoding="utf-8").stdout
        ms = (time.time() - t0) * 1000
        lines = {}
        for row in tsv.splitlines()[1:]:
            c = row.split("\t")
            if len(c) < 12 or not c[11].strip() or float(c[10]) < 0:
                continue
            key = (c[2], c[3], c[4])
            x, y, bw, bh = map(int, c[6:10])
            L = lines.setdefault(key, {"words": [], "box": [x, y, x + bw, y + bh]})
            L["words"].append(c[11])
            b = L["box"]
            L["box"] = [min(b[0], x), min(b[1], y), max(b[2], x + bw), max(b[3], y + bh)]
        out = [{"text": " ".join(L["words"]), "box": [L["box"][0] / w * 1000, L["box"][1] / h * 1000, L["box"][2] / w * 1000, L["box"][3] / h * 1000]}
               for L in lines.values()]
        results[f"tesseract|{img['id']}"] = {"lines": out, "ms": int(ms), "raw": ""}
        print(f"{img['id']}: {len(out)} lines in {ms:.0f} ms")
    RESULTS.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")


# ---------------------------------------------------------------- NVIDIA calls
def _key() -> str:
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        m = re.match(r"\s*NVIDIA_API_KEY\s*=\s*(.+?)\s*$", line)
        if m:
            return m.group(1).strip().strip('"').strip("'")
    sys.exit("NVIDIA_API_KEY absent")


def _usage() -> dict:
    if USAGE.exists():
        return json.loads(USAGE.read_text(encoding="utf-8"))
    return {"budget": BUDGET, "requests": 0, "http_429": 0, "http_503": 0, "stopped": False, "log": []}


_last = [0.0]


def call(body: dict, label: str, key: str) -> tuple[int, dict | None, float, str]:
    usage = _usage()
    if usage["stopped"] or usage["requests"] >= BUDGET:
        sys.exit(f"Arrêt : budget {usage['requests']}/{BUDGET} ou 429/503 répétés.")
    wait = MIN_INTERVAL_S - (time.time() - _last[0])
    if wait > 0:
        time.sleep(wait)
    _last[0] = time.time()
    usage["requests"] += 1
    USAGE.write_text(json.dumps(usage, indent=1), encoding="utf-8")  # counted before sending
    req = urllib.request.Request(BASE + "/chat/completions", method="POST", data=json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json", "Accept": "application/json"})
    t0 = time.time()
    status, data, err = 0, None, ""
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT_S) as r:
            status = r.status
            data = json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        status, err = e.code, e.read().decode("utf-8", "replace")[:300].replace(key, "***")
    except Exception as e:  # noqa: BLE001
        status, err = -1, (type(e).__name__ + ": " + str(e))[:200].replace(key, "***")
    ms = (time.time() - t0) * 1000
    usage = _usage()
    usage["log"].append({"t": time.strftime("%H:%M:%S"), "label": label, "status": status, "ms": int(ms)})
    if status == 429:
        usage["http_429"] += 1
        usage["stopped"] = usage["http_429"] >= 2
    if status == 503:
        usage["http_503"] += 1
        usage["stopped"] = usage["stopped"] or usage["http_503"] >= 3
    USAGE.write_text(json.dumps(usage, indent=1), encoding="utf-8")
    print(f"[{usage['requests']}/{BUDGET}] {label}: HTTP {status} {ms / 1000:.1f} s {err[:160]}", flush=True)
    return status, data, ms, err


def body_for(model: str, b64: str) -> dict:
    content = [{"type": "text", "text": PROMPT}, {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}}]
    b = {"model": model, "messages": [{"role": "user", "content": content}], "temperature": 0, "max_tokens": 1500, "stream": False}
    if "nemotron" in model:  # reasoning model: try to switch reasoning off
        b["messages"].insert(0, {"role": "system", "content": "/no_think"})
        b["chat_template_kwargs"] = {"enable_thinking": False}
    return b


def parse(content: str) -> list[dict] | None:
    """Last JSON array of objects in the answer (models often write drafts before it)."""
    content = re.sub(r"(?s)<think>.*?</think>", "", content)
    arr = None
    for m in reversed(list(re.finditer(r"\[\s*\{", content))):
        chunk = content[m.start():]
        end = chunk.rfind("]")
        for candidate in (chunk[:end + 1], re.sub(r",\s*([\]}])", r"\1", chunk[:end + 1])):
            try:
                arr = json.loads(candidate)
                break
            except json.JSONDecodeError:
                # cut at the first "}]" that closes a valid array
                for k in [i for i in range(len(candidate)) if candidate.startswith("}]", i)]:
                    try:
                        arr = json.loads(candidate[:k + 2])
                        break
                    except json.JSONDecodeError:
                        pass
                if arr is not None:
                    break
        if arr is not None:
            break
    if not isinstance(arr, list):
        return parse_prose(content)
    out = []
    for it in arr:
        if not isinstance(it, dict):
            continue
        box = it.get("box") or it.get("bbox")
        ok = isinstance(box, list) and len(box) == 4 and all(isinstance(v, (int, float)) for v in box)
        box = [float(v) for v in box] if ok else None
        out.append({"text": str(it.get("text", "")), "fr": str(it.get("fr", "")), "box": box})
    # Boxes given in 0..1 instead of 0..1000 (seen with Llama 3.2 11B): rescale.
    for L in out:  # boxes given in 0..1 instead of 0..1000 (Llama 3.2 11B): rescale each
        if L["box"] and max(L["box"]) <= 1.0:
            L["box"] = [v * 1000 for v in L["box"]]
    return out


def parse_prose(content: str) -> list[dict] | None:
    """Fallback for answers in prose (format NOT respected): 'reads "X". ... box is [..] ... translation is "Y"'
    or 'Text: X / Box: [..] / French Translation: Y'. Marked with prose=True."""
    out = []
    for m in re.finditer(r'reads\s+"(.+?)"\.?.{0,80}?\[([\d.,\s]+)\].{0,80}?translation is\s+"(.+?)"', content, flags=re.S):
        out.append((m.group(1), m.group(2), m.group(3)))
    for m in re.finditer(r'Text:\s*(.+?)\s*\n\s*\*?\s*Box:\s*\[([\d.,\s]+)\]\s*\n\s*\*?\s*French Translation:\s*(.+?)\s*\n', content + "\n"):
        out.append((m.group(1), m.group(2), m.group(3)))
    if not out:
        return None
    lines = []
    for text, box, fr in out:
        vals = [float(v) for v in box.split(",") if v.strip()]
        lines.append({"text": text.strip(), "fr": fr.strip(), "box": vals if len(vals) == 4 else None, "prose": True})
    for L in lines:
        if L["box"] and max(L["box"]) <= 1.0:
            L["box"] = [v * 1000 for v in L["box"]]
    return lines


def cmd_reparse():
    results = json.loads(RESULTS.read_text(encoding="utf-8"))
    for k, r in results.items():
        if not k.startswith("tesseract|") and r.get("raw"):
            r["lines"] = parse(r["raw"])
    RESULTS.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")


def cmd_run(model: str, ids: list[str]):
    key = _key()
    results = json.loads(RESULTS.read_text(encoding="utf-8")) if RESULTS.exists() else {}
    for img in CORPUS:
        if ids and img["id"] not in ids:
            continue
        rk = f"{model}|{img['id']}"
        if rk in results and results[rk].get("lines") is not None:
            continue
        raw = image_bytes(img)
        status, data, ms, err = call(body_for(model, base64.b64encode(raw).decode()), f"{model} {img['id']}", key)
        content = ""
        if status == 200 and data:
            msg = data["choices"][0]["message"]
            content = msg.get("content") or ""
        lines = parse(content) if content else None
        results[rk] = {"status": status, "ms": int(ms), "bytes": len(raw), "lines": lines, "raw": content[:4000], "err": err,
                       "usage": (data or {}).get("usage")}
        RESULTS.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"   -> {len(lines) if lines is not None else 'réponse illisible'} lignes", flush=True)
        if status in (404, 400, 422):
            print("   (modèle indisponible / format refusé : arrêt pour ce modèle)")
            return


# ---------------------------------------------------------------- scoring
def norm(s: str) -> str:
    return re.sub(r"\s+", " ", s.replace('"', '"')).strip()


def ordered_text(lines: list[dict], key: str = "text") -> str:
    def k(L):
        b = L.get("box") or [0, 0, 0, 0]
        return (round(b[1] / 40), b[0])
    return " ".join(norm(L.get(key, "")) for L in sorted(lines, key=k) if L.get(key))


def cer(hyp: str, ref: str) -> float:
    a, b = ref, hyp
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1] / max(1, len(a))


def iou(a, b) -> float:
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0.0


def cmd_score():
    import sacrebleu
    results = json.loads(RESULTS.read_text(encoding="utf-8"))
    methods = sorted({k.split("|")[0] for k in results})
    table = {}
    for m in methods:
        rows = []
        for img in CORPUS:
            r = results.get(f"{m}|{img['id']}")
            if not r:
                continue
            lines = r.get("lines")
            ref_lines = [{"text": L["text"], "box": [v * 1000 for v in L["box"]]} for L in img["lines"]]
            ref_src = ordered_text(ref_lines)
            row = {"img": img["id"], "ms": r.get("ms"), "bytes": r.get("bytes"), "status": r.get("status"),
                   "json_ok": bool(r.get("lines")) and not any(L.get("prose") for L in r.get("lines") or [])}
            if lines is None:
                row["fail"] = True
                rows.append(row)
                continue
            hyp_src = ordered_text(lines)
            row["src_chrf"] = sacrebleu.corpus_chrf([hyp_src], [[ref_src]]).score
            row["cer"] = cer(hyp_src, ref_src)
            if any(L.get("fr") for L in lines):
                row["fr_chrf"] = sacrebleu.corpus_chrf([ordered_text(lines, "fr")], [[img["fr_full"]]], word_order=2).score
            boxes = [L["box"] for L in lines if L.get("box")]
            if boxes:
                best = [max(iou(rl["box"], b) for b in boxes) for rl in ref_lines]
                row["iou"] = statistics.mean(best)
                row["iou50"] = sum(v >= 0.5 for v in best) / len(best)
            # invented: predicted line matching no reference line (chrF < 25 against every one)
            inv = []
            for L in lines:
                t = norm(L.get("text", ""))
                if len(t) >= 3 and max(sacrebleu.sentence_chrf(t, [rl["text"]]).score for rl in img["lines"]) < 25:
                    inv.append(t)
            row["invented"] = inv
            rows.append(row)
        table[m] = rows
    # app 1.3.0 (OCR + ML Kit) translation as displayed
    app_rows = [{"img": i["id"], "fr_chrf": sacrebleu.corpus_chrf([i["app_130"]], [[i["fr_full"]]], word_order=2).score}
                for i in CORPUS if i.get("app_130")]
    table["app 1.3.0 (OCR+ML Kit, affiché)"] = app_rows
    (HERE / "vision-scores.json").write_text(json.dumps(table, ensure_ascii=False, indent=1), encoding="utf-8")
    for m, rows in table.items():
        print(f"\n== {m}")
        for r in rows:
            print("  " + ", ".join(f"{k}={v:.2f}" if isinstance(v, float) else f"{k}={v}" for k, v in r.items()))
    print(f"\nRequêtes NVIDIA (étude vision) : {_usage()['requests']}/{BUDGET}")


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "prep":
        cmd_prep()
    elif cmd == "ocr":
        cmd_ocr()
    elif cmd == "run":
        cmd_run(sys.argv[2], sys.argv[3:])
    elif cmd == "reparse":
        cmd_reparse()
    elif cmd == "score":
        cmd_score()
