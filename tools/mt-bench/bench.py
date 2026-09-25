"""Translation engine benchmark on real TranslateOverlay failures (ynet, Wikipedia).

Usage: .venv/Scripts/python bench.py  (llama-server with TranslateGemma on :8091 for the "tgemma" column)
Writes results.json and prints a chrF++ table. References are human translations (single reference).
"""
import json
import time
import urllib.parse
import urllib.request

import sacrebleu
import torch
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

CORPUS = json.load(open("corpus.json", encoding="utf-8"))
DEV = "cuda" if torch.cuda.is_available() else "cpu"
NAMES = {"he": "Hebrew", "fr": "French", "en": "English"}


def load(name):
    try:
        tok = AutoTokenizer.from_pretrained(name)
    except Exception:  # noqa: BLE001 - older Marian repos only ship a SentencePiece (slow) tokenizer
        tok = AutoTokenizer.from_pretrained(name, use_fast=False)
    model = AutoModelForSeq2SeqLM.from_pretrained(name).to(DEV).eval()
    return tok, model


_cache = {}


def marian(name, text):
    if name not in _cache:
        _cache[name] = load(name)
    tok, model = _cache[name]
    batch = tok([text], return_tensors="pt").to(DEV)
    with torch.no_grad():
        out = model.generate(**batch, num_beams=4, max_new_tokens=256)
    return tok.decode(out[0], skip_special_tokens=True)


NLLB = {"he": "heb_Hebr", "fr": "fra_Latn", "en": "eng_Latn"}


def nllb(name, text, src, tgt):
    if name not in _cache:
        tok = AutoTokenizer.from_pretrained(name, src_lang=NLLB[src])
        model = AutoModelForSeq2SeqLM.from_pretrained(name, torch_dtype=torch.float16).to(DEV).eval()
        _cache[name] = (tok, model)
    tok, model = _cache[name]
    tok.src_lang = NLLB[src]
    batch = tok([text], return_tensors="pt").to(DEV)
    with torch.no_grad():
        out = model.generate(**batch, forced_bos_token_id=tok.convert_tokens_to_ids(NLLB[tgt]), num_beams=4, max_new_tokens=256)
    return tok.decode(out[0], skip_special_tokens=True)


def opus(text, src, tgt):
    direct = {("he", "fr"): "Helsinki-NLP/opus-mt-he-fr", ("en", "fr"): "Helsinki-NLP/opus-mt-tc-big-en-fr",
              ("en", "he"): "Helsinki-NLP/opus-mt-en-he"}
    return marian(direct[(src, tgt)], text)


def opus_pivot(text, src, tgt):
    if src != "he":
        return opus(text, src, tgt)
    en = marian("Helsinki-NLP/opus-mt-tc-big-he-en", text)
    return marian("Helsinki-NLP/opus-mt-tc-big-en-fr", en)


def tgemma(text, src, tgt):
    p = (f"You are a professional {NAMES[src]} ({src}) to {NAMES[tgt]} ({tgt}) translator. Your goal is to accurately "
         f"convey the meaning and nuances of the original {NAMES[src]} text while adhering to {NAMES[tgt]} grammar, "
         f"vocabulary, and cultural sensitivities.\nProduce only the {NAMES[tgt]} translation, without any additional "
         f"explanations or commentary. Please translate the following {NAMES[src]} text into {NAMES[tgt]}:\n\n\n{text}")
    body = json.dumps({"prompt": f"<start_of_turn>user\n{p}<end_of_turn>\n<start_of_turn>model\n", "temperature": 0,
                       "n_predict": 200, "stop": ["<end_of_turn>"]}).encode()
    req = urllib.request.Request("http://localhost:8091/completion", body, {"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req))["content"].strip()


def google_gtx(text, src, tgt):
    """Unofficial endpoint (no key): measured for reference only, may answer HTTP 429."""
    url = ("https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=%s&tl=%s&q=%s"
           % (src, tgt, urllib.parse.quote(text)))
    for attempt in range(3):
        try:
            data = json.load(urllib.request.urlopen(url, timeout=15))
            return "".join(seg[0] for seg in data[0] if seg[0])
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(5 * (attempt + 1))
    return f"<erreur: {last}>"


SYSTEMS = {
    "opus": opus,
    "opus_pivot": opus_pivot,
    "nllb600": lambda t, s, g: nllb("facebook/nllb-200-distilled-600M", t, s, g),
    "nllb1.3b": lambda t, s, g: nllb("facebook/nllb-200-distilled-1.3B", t, s, g),
    "tgemma4b": tgemma,
    "google": google_gtx,
}


def main():
    results = []
    for item in CORPUS:
        src, tgt = item["pair"].split("-")
        row = dict(item)
        row["out"] = {"mlkit": item.get("mlkit")}
        row["ms"] = {}
        for name, fn in SYSTEMS.items():
            t0 = time.time()
            try:
                row["out"][name] = fn(item["src"], src, tgt)
            except Exception as e:  # noqa: BLE001
                row["out"][name] = f"<erreur: {e}>"
            row["ms"][name] = int((time.time() - t0) * 1000)
            if name == "google":
                time.sleep(2)
        results.append(row)
        print(item["id"], {k: v for k, v in row["out"].items()}, flush=True)
    json.dump(results, open("results.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    systems = ["mlkit"] + list(SYSTEMS)
    print("\nchrF++ (0-100, reference humaine unique)")
    for pair in ["he-fr", "en-fr", "en-he"]:
        rows = [r for r in results if r["pair"] == pair]
        line = [pair]
        for s in systems:
            sub = [r for r in rows if r["out"].get(s) and not str(r["out"][s]).startswith("<erreur")]
            if not sub:
                line.append(f"{s}=n/a")
                continue
            score = sacrebleu.corpus_chrf([r["out"][s] for r in sub], [[r["ref"] for r in sub]], word_order=2).score
            line.append(f"{s}={score:.1f}(n={len(sub)})")
        print("  ".join(line))


if __name__ == "__main__":
    main()
