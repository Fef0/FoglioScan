"""Generates synthetic 'phone photos' of a printed page for pipeline tests.

Each photo has: perspective tilt, a wooden desk, a colour cast, a lighting gradient,
a soft hand shadow and sensor noise. Usage: python make_test_photos.py <out_dir>
"""
import sys, numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

out = sys.argv[1]
rng = np.random.default_rng(7)

def page(w=1240, h=1754):
    img = Image.new("RGB", (w, h), (250, 249, 246)); d = ImageDraw.Draw(img)
    try: big = ImageFont.truetype("arialbd.ttf", 54); body = ImageFont.truetype("arial.ttf", 26)
    except OSError: big = body = ImageFont.load_default()
    d.text((110, 120), "Invoice #2026-1116", fill=(20, 20, 20), font=big)
    d.rectangle((110, 210, 1130, 216), fill=(0, 105, 107))
    words = "Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt ut labore".split()
    y = 270
    for i in range(34):
        line = " ".join(rng.choice(words, size=9))
        d.text((110, y), line.capitalize(), fill=(35, 35, 35), font=body); y += 40
        if i == 14:
            d.rectangle((110, y + 10, 600, y + 260), fill=(200, 60, 50)); y += 290  # a red stamp/photo block
    d.text((800, 1620), "Signature", fill=(20, 40, 160), font=body)
    return img

def find_coeffs(dst, src):
    m = []
    for (x, y), (u, v) in zip(dst, src):
        m.append([x, y, 1, 0, 0, 0, -u * x, -u * y]); m.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    return np.linalg.solve(np.array(m, float), np.array(src, float).reshape(8))

def photo(pg, quad, size=(3000, 4000), name="photo"):
    W, H = size
    yy, xx = np.mgrid[0:H, 0:W]
    grain = np.sin(xx / 37.0 + np.sin(yy / 230.0) * 3) * 18 + rng.normal(0, 6, (H, W))
    desk = np.stack([120 + grain, 78 + grain * 0.7, 45 + grain * 0.4], -1).clip(0, 255).astype(np.uint8)
    canvas = Image.fromarray(desk)
    pw, ph = pg.size
    coeffs = find_coeffs(quad, [(0, 0), (pw, 0), (pw, ph), (0, ph)])
    warped = pg.transform((W, H), Image.PERSPECTIVE, coeffs, Image.BICUBIC)
    mask = Image.new("L", (W, H), 0); ImageDraw.Draw(mask).polygon(quad, fill=255)
    canvas.paste(warped, (0, 0), mask)
    a = np.asarray(canvas).astype(np.float32)
    light = 0.62 + 0.38 * (1 - ((xx - W * 0.2) ** 2 + (yy - H * 0.1) ** 2) ** 0.5 / (W * 1.3))   # vignette/gradient
    shadow = Image.new("L", (W, H), 0); ImageDraw.Draw(shadow).ellipse((W * 0.55, H * 0.62, W * 1.3, H * 1.25), fill=255)
    shadow = np.asarray(shadow.filter(ImageFilter.GaussianBlur(160))).astype(np.float32) / 255
    a *= (light * (1 - 0.45 * shadow))[..., None]
    a *= np.array([1.0, 0.96, 0.84])  # warm tungsten cast
    a += rng.normal(0, 4, a.shape)
    img = Image.fromarray(a.clip(0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.1))
    img.save(f"{out}/{name}.jpg", quality=92)

def projected_a4(rx, ry, rz, dist, f=0.95 * 4000, size=(3000, 4000)):
    """Corners of an A4 sheet (mm) rotated in 3D and seen by a pinhole camera."""
    def R(a, axis):
        c, s = np.cos(np.radians(a)), np.sin(np.radians(a))
        return {"x": np.array([[1, 0, 0], [0, c, -s], [0, s, c]]),
                "y": np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
                "z": np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])}[axis]
    rot = R(rz, "z") @ R(ry, "y") @ R(rx, "x")
    pts = []
    for x, y in [(-105, -148.5), (105, -148.5), (105, 148.5), (-105, 148.5)]:
        X = rot @ np.array([x, y, 0.0]) + np.array([0, 0, dist])
        pts.append((size[0] / 2 + f * X[0] / X[2], size[1] / 2 + f * X[1] / X[2]))
    return pts

pg = page()
q = projected_a4(rx=28, ry=-12, rz=6, dist=420)
print("projected A4 corners", [(round(x), round(y)) for x, y in q])
photo(pg, q, name="projected")
photo(pg, [(520, 610), (2480, 760), (2620, 3520), (330, 3330)], name="tilted")
photo(pg, [(780, 420), (2350, 520), (2500, 3700), (600, 3560)], name="upright")
print("ok")
