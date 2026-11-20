"""Builds a small real-photo benchmark from SmartDoc 2015 Challenge 1 (CC BY 4.0).

Streams the 1 GB frames archive and keeps frames 30 and 90 of every video (284 frames,
5 backgrounds x 6 document types) plus normalized ground-truth corners.

Usage: python tools/fetch_smartdoc.py testdata/smartdoc
Attribution: J.-C. Burie et al., "ICDAR2015 Competition on Smartphone Document Capture and OCR
(SmartDoc)", ICDAR 2015. https://github.com/jchazalon/smartdoc15-ch1-dataset
"""
import csv, gzip, io, os, re, sys, tarfile, urllib.request

URL = "https://github.com/jchazalon/smartdoc15-ch1-dataset/releases/download/v2.0.0/frames.tar.gz"
FRAMES = {30, 90}
out = sys.argv[1]
os.makedirs(os.path.join(out, "images"), exist_ok=True)
meta, images = None, {}
with urllib.request.urlopen(URL) as r, tarfile.open(fileobj=r, mode="r|gz") as t:
    for m in t:
        if not m.isfile():
            continue
        if m.name == "metadata.csv.gz":
            meta = gzip.decompress(t.extractfile(m).read()).decode()
        elif m.name == "LICENCE":
            open(os.path.join(out, "LICENCE"), "wb").write(t.extractfile(m).read())
        else:
            mm = re.match(r"(background\d+)/(\w+?)(\d+)/frame_(\d+)\.jpe?g$", m.name)
            if mm and int(mm.group(4)) in FRAMES:
                flat = f"{mm.group(1)}_{mm.group(2)}{mm.group(3)}_f{int(mm.group(4)):04d}.jpg"
                open(os.path.join(out, "images", flat), "wb").write(t.extractfile(m).read())
                images[m.name] = flat

W, H = 1920.0, 1080.0
rows = []
for r in csv.DictReader(io.StringIO(meta)):
    flat = images.get(r["image_path"])
    if not flat:
        continue
    pts = [(float(r[f"{c}_x"]) / W, float(r[f"{c}_y"]) / H) for c in ("tl", "tr", "br", "bl")]
    rows.append([flat, r["bg_name"], r["modeltype_name"]] + [f"{v:.6f}" for p in pts for v in p])
with open(os.path.join(out, "groundtruth.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["image", "background", "doctype", "tl_x", "tl_y", "tr_x", "tr_y", "br_x", "br_y", "bl_x", "bl_y"])
    w.writerows(sorted(rows))
print(f"{len(rows)} frames with ground truth -> {out}")
