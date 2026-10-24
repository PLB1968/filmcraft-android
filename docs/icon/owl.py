# Rushcraft's owl, an original design: a round blue owl with sleepy, half-open light-blue eyes
# and a yellow beak. One set of shapes, written as Android vectors and drawn as PNGs:
#   python3 docs/icon/owl.py apps/rushcraft-android/android/app/src/main/res docs/icon
import sys
from PIL import Image, ImageDraw

BG = "#1E2A4A"       # night blue
FEATHER = "#2D45B5"  # deep blue
DISC = "#3FA9E0"     # bright blue face
LID = "#2B7FC4"      # eyelids, a shade darker than the face
WHITE = "#F4F7FB"
IRIS = "#A8DDFF"     # light blue eyes
PUPIL = "#14183A"
LASH = "#14183A"
BEAK = "#F5C518"     # yellow

# (kind, colour, data) on a 108 x 108 grid, inside the adaptive icon's 66 dp safe circle
def shapes():
    s = []
    s.append(("poly", FEATHER, [(35, 31), (48, 40), (40, 47)]))        # ear tufts
    s.append(("poly", FEATHER, [(73, 31), (60, 40), (68, 47)]))
    s.append(("circle", FEATHER, (54, 60, 26)))                         # head
    for cx in (44, 64):
        s.append(("circle", DISC, (cx, 59, 12)))                        # face
    for cx in (44, 64):
        s.append(("circle", WHITE, (cx, 60, 7.5)))                      # eye
        s.append(("circle", IRIS, (cx, 62, 5)))
        s.append(("circle", PUPIL, (cx, 62.5, 2.6)))
        s.append(("top", LID, (cx, 60, 8.2)))                           # half-closed lid
        s.append(("poly", LASH, [(cx - 8.2, 59.3), (cx + 8.2, 59.3), (cx + 8.2, 61), (cx - 8.2, 61)]))
    s.append(("poly", BEAK, [(51, 66), (57, 66), (54, 76)]))            # beak
    return s

def vector(with_bg):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- Rushcraft's owl (an original design): docs/icon/owl.py writes this file. -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp" android:height="108dp"',
           '    android:viewportWidth="108" android:viewportHeight="108">']
    if with_bg:
        out.append(f'    <path android:fillColor="{BG}" android:pathData="M0,0h108v108h-108z" />')
    for kind, col, d in shapes():
        if kind == "circle":
            cx, cy, r = d
            pd = f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1,0 {2 * r:g},0a{r:g},{r:g} 0 1,0 {-2 * r:g},0z"
        elif kind == "top":
            cx, cy, r = d
            pd = f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 0,1 {2 * r:g},0z"
        else:
            pd = "M" + "L".join(f"{x:g},{y:g}" for x, y in d) + "z"
        out.append(f'    <path android:fillColor="{col}" android:pathData="{pd}" />')
    out.append("</vector>")
    return "\n".join(out) + "\n"

def png(path, size, zoom=1.24):
    k = 4  # supersampled, then scaled down
    W = size * k
    img = Image.new("RGBA", (W, W), BG)
    d = ImageDraw.Draw(img)
    sc = W * zoom / 108
    off = (W - 108 * sc) / 2
    P = lambda x, y: (off + x * sc, off + y * sc)
    for kind, col, data in shapes():
        if kind in ("circle", "top"):
            cx, cy, r = data
            box = [*P(cx - r, cy - r), *P(cx + r, cy + r)]
            if kind == "circle":
                d.ellipse(box, fill=col)
            else:
                d.pieslice(box, 180, 360, fill=col)
        else:
            d.polygon([P(x, y) for x, y in data], fill=col)
    img.resize((size, size), Image.LANCZOS).save(path)

if __name__ == "__main__":
    res, docs = sys.argv[1], sys.argv[2]
    open(f"{res}/drawable/ic_owl.xml", "w").write(vector(False))
    open(f"{res}/drawable/ic_launcher_tile.xml", "w").write(vector(True))
    png(f"{docs}/rushcraft-icon-512.png", 512)
