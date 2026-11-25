"""Builds app/src/main/assets/{models,specs}.tsv.

usage: python build_assets.py supported_devices.csv
The csv is Google's public Play device catalogue (UTF-16):
https://storage.googleapis.com/play_public/supported_devices.csv
"""
import csv, io, pathlib, re, sys

root = pathlib.Path(__file__).resolve().parent
out = root.parent / "app/src/main/assets"
CC = dict(Samsung="KR", Google="US", OnePlus="CN", Xiaomi="CN", Redmi="CN", POCO="CN", Nothing="GB", Motorola="US",
          Sony="JP", ASUS="TW", OPPO="CN", realme="CN", vivo="CN", HONOR="CN", Huawei="CN", Fairphone="NL", LG="KR",
          Nokia="FI", Infinix="HK", TECNO="HK", REDMAGIC="CN")
PREFIXES = ["motorola", "samsung", "huawei", "honor", "oneplus", "poco", "xiaomi", "realme", "vivo", "oppo",
            "infinix", "tecno", "nothing", "sony", "asus"]


def norm(n):
    """Must stay in step with DeviceDb.norm() in Kotlin."""
    n = n.lower().replace("+", " plus ")
    for b in PREFIXES:
        if n.startswith(b + " "):
            n = n[len(b) + 1:]
    n = re.sub(r"(?<!\()\b5g\b", "", n)
    return re.sub(r"[^a-z0-9]", "", n)


rows = {}
txt = open(sys.argv[1], encoding="utf-16").read()
for r in list(csv.reader(io.StringIO(txt)))[1:]:
    if len(r) < 4:
        continue
    brand, name, _, model = (x.strip().replace("\t", " ") for x in r[:4])
    if not model or not name or name.lower() == model.lower():
        continue
    rows.setdefault(model, (brand, name))
out.mkdir(parents=True, exist_ok=True)
(out / "models.tsv").write_text("\n".join(f"{m}\t{b}\t{n}" for m, (b, n) in sorted(rows.items())),
                                encoding="utf-8", newline="\n")
known = {norm(n) for _, n in rows.values()}
specs, brand, miss = [], "", []
for line in (root / "specs.txt").read_text(encoding="utf-8").splitlines():
    if line.startswith("#") or not line.strip():
        continue
    if line.startswith("@"):
        brand = line[1:]
        continue
    n, mah, w, wl, y = line.split("|")
    if norm(n) not in known:
        miss.append(n)
    specs.append("\t".join([norm(n), brand, CC[brand], n, mah, w, wl, y]))
(out / "specs.tsv").write_text("\n".join(specs), encoding="utf-8", newline="\n")
print(len(rows), "models;", len(specs), "specs; names not in catalogue:", miss)
