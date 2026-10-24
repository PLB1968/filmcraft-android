# Rushcraft's owl: one set of shapes, written as an Android vector and drawn as PNGs.
import sys
from PIL import Image, ImageDraw

BG = "#4B3A8C"      # twilight violet
FEATHER = "#8B5A2B" # brown
DISC = "#F3E1C0"
IRIS = "#F5C542"
PUPIL = "#1B1B1B"
BEAK = "#E8892B"

# (kind, colour, data) on a 108 x 108 grid, centred in the adaptive icon's 66 dp safe circle
def shapes():
    s = []
    s.append(("poly", FEATHER, [(36, 30), (47, 40), (40, 46)]))       # left ear tuft
    s.append(("poly", FEATHER, [(72, 30), (61, 40), (68, 46)]))       # right ear tuft
    s.append(("circle", FEATHER, (54, 60, 26)))                        # head
    for cx in (44, 64):
        s.append(("circle", DISC, (cx, 59, 11)))                       # facial disc
        s.append(("circle", IRIS, (cx, 59, 8)))
        s.append(("circle", PUPIL, (cx, 59, 4.5)))
        s.append(("circle", "#FFFFFF", (cx + 2, 57, 1.6)))             # catch light
    s.append(("poly", BEAK, [(50.5, 66), (57.5, 66), (54, 74)]))       # beak
    return s

def vector(with_bg):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<!-- Rushcraft\'s owl (an original design): docs/icon/owl.py writes this file. -->',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp" android:height="108dp"',
           '    android:viewportWidth="108" android:viewportHeight="108">']
    if with_bg:
        out.append(f'    <path android:fillColor="{BG}" android:pathData="M0,0h108v108h-108z" />')
    for kind, col, d in shapes():
        if kind == "circle":
            cx, cy, r = d
            pd = f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1,0 {2 * r:g},0a{r:g},{r:g} 0 1,0 {-2 * r:g},0z"
        else:
            pd = "M" + "L".join(f"{x:g},{y:g}" for x, y in d) + "z"
        out.append(f'    <path android:fillColor="{col}" android:pathData="{pd}" />')
    out.append('</vector>')
    return "\n".join(out) + "\n"

def png(path, size, inset=0.0):
    k = 4  # supersample
    W = size * k
    img = Image.new("RGBA", (W, W), BG)
    d = ImageDraw.Draw(img)
    # the owl drawn at (1 - 2*inset) of the tile
    sc = W * (1 - 2 * inset) / 108
    off = W * inset
    P = lambda x, y: (off + x * sc, off + y * sc)
    for kind, col, data in shapes():
        if kind == "circle":
            cx, cy, r = data
            x0, y0 = P(cx - r, cy - r); x1, y1 = P(cx + r, cy + r)
            d.ellipse([x0, y0, x1, y1], fill=col)
        else:
            d.polygon([P(x, y) for x, y in data], fill=col)
    img.resize((size, size), Image.LANCZOS).save(path)

if __name__ == "__main__":
    res, docs = sys.argv[1], sys.argv[2]
    open(f"{res}/drawable/ic_owl.xml", "w").write(vector(False))
    open(f"{res}/drawable/ic_launcher_tile.xml", "w").write(vector(True))
    png(f"{docs}/rushcraft-icon-512.png", 512, inset=-0.12)
    png(f"{docs}/fastlane-icon.png", 512, inset=-0.12)
