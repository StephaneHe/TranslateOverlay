"""Does the numbered one-request prompt answer in the TARGET language on a mixed screen?

Seen on the V30T (1.8.0 dev, 2026-09-26): a ynet screen with Hebrew lines plus English OCR lines
("IT BETTER BE GEELY") came back entirely in English although the target was French.
Same safety rules as nvidia_latency.py (shared key, counted in nvidia-usage-2.json).
Usage:  python nvidia_target_lang.py VARIANT      VARIANT: v1 | v2
"""
import json
import sys
import urllib.request

import nvidia_latency as nl

LINES_DEVICE = ["ידיעות+", "דעות", "בחירות 2026", "חדשות", "מבזקים", "ו גו 2 |", "לוג-וון בש", "עם חוד", "הצעו",
                "לרכישת מנוי>", "המכירה אסורה למי שטרם סלאו לו 18 שנים. אזהרה: משרד הרווחה"]
LINES = [
    "כותרות", "מבזקים", "חדשות", "בחירות 2026", "דעות",
    "IT BETTER BE GEELY", "GEELY",
    "ימי מכירות חגיגיים עם מגוון הטבות", "בכל אולמות התצוגה",
    "Benefits subject to regulations on website",
    "מנהיגי \"גוש השינוי\" נפגשו ופרסמו \"מסמך עקרונות\"",
    "כך תוכלו לנהוג בקיה ספורטאז' ב-2,390 ₪ לחודש",
]


def body(variant: str) -> dict:
    numbered = "\n".join(f"[{i + 1}] {t}" for i, t in enumerate(LINES))
    if variant == "v1":  # 1.8.0 dev prompt
        system = ("Translate each numbered line into French, whatever its language. Answer in French only: "
                  "one line per input, starting with the same [number], then the translation only.")
        user = numbered
    else:  # v2: instruction in the target language + reminder after the lines
        system = ("Tu es traducteur vers le français. Traduis de l'hébreu vers le français chaque ligne numérotée. "
                  "Réponds uniquement en français : une ligne par entrée, avec le même [numéro] puis la traduction seule.")
        user = numbered + "\n\n(Réponds en français.)"
    return {"model": "nvidia/nemotron-3-ultra-550b-a55b", "temperature": 0, "max_tokens": 1024, "stream": False,
            "chat_template_kwargs": {"enable_thinking": False},
            "messages": [{"role": "system", "content": "/no_think"}, {"role": "system", "content": system},
                         {"role": "user", "content": user}]}


if __name__ == "__main__":
    v = sys.argv[1]
    nl._reserve(f"target-lang {v}")
    req = urllib.request.Request(nl.BASE + "/chat/completions", method="POST", data=json.dumps(body(v)).encode(),
                                 headers={"Authorization": f"Bearer {nl._KEY}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        out = json.loads(r.read().decode())["choices"][0]["message"]["content"]
    nl._record(f"target-lang {v}", 200, None, 0)
    print(out)
    print(f"Requêtes NVIDIA (compteur banc) : {nl._usage()['requests']}")
