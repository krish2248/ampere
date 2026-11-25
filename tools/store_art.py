"""Renders store/icon-512.png and store/feature-1024x500.png. Needs Pillow. Run from anywhere."""
import math, pathlib
from PIL import Image, ImageDraw, ImageFont

root = pathlib.Path(__file__).resolve().parent.parent
store = root / "store"
store.mkdir(exist_ok=True)
BG, LIT, DIM, INK, SUB = (11, 12, 14), (95, 224, 163), (46, 50, 57), (244, 245, 247), (151, 157, 168)
S = 4
BOLT = [(56.6, 40.0), (45.0, 56.8), (52.6, 56.8), (51.2, 67.6), (63.6, 50.7), (56.4, 50.7), (57.9, 40.0)]


def font(size, weight):
    f = ImageFont.truetype(str(root / "app/src/main/res/font/geist.ttf"), size)
    f.set_variation_by_axes([weight])
    return f


def dial(d, cx, cy, r, ticks, lit, length, width):
    for i in range(ticks):
        a = math.radians(-90 + 360 * i / ticks)
        p = [(cx + q * math.cos(a), cy + q * math.sin(a)) for q in (r - length, r)]
        col = LIT if i < lit else DIM
        d.line(p, fill=col, width=width)
        for x, y in p:
            d.ellipse([x - width / 2, y - width / 2, x + width / 2, y + width / 2], fill=col)


def bolt(d, cx, cy, k):
    d.polygon([(cx + (x - 54) * k, cy + (y - 53.8) * k) for x, y in BOLT], fill=INK)


im = Image.new("RGB", (512 * S, 512 * S), BG)
d = ImageDraw.Draw(im)
c = 256 * S
dial(d, c, c, 186 * S, 40, 30, 42 * S, 13 * S)
bolt(d, c, c, 6.6 * S)
im.resize((512, 512), Image.LANCZOS).save(store / "icon-512.png")

im = Image.new("RGB", (1024 * S, 500 * S), BG)
d = ImageDraw.Draw(im)
dial(d, 772 * S, 250 * S, 186 * S, 100, 92, 22 * S, 4 * S)
t = font(112 * S, 300)
w = d.textlength("92", font=t)
p = font(40 * S, 400)
d.text((772 * S - (w + 40 * S) / 2, 250 * S), "92", font=t, fill=INK, anchor="lm")
d.text((772 * S - (w + 40 * S) / 2 + w + 6 * S, 268 * S), "%", font=p, fill=SUB, anchor="lm")
d.text((772 * S, 336 * S), "real capacity", font=font(21 * S, 400), fill=SUB, anchor="mm")
d.text((72 * S, 176 * S), "Ampere", font=font(84 * S, 600), fill=INK, anchor="lm")
d.text((74 * S, 262 * S), "What 100% is", font=font(37 * S, 400), fill=SUB, anchor="lm")
d.text((74 * S, 310 * S), "really worth.", font=font(37 * S, 400), fill=SUB, anchor="lm")
im.resize((1024, 500), Image.LANCZOS).save(store / "feature-1024x500.png")
