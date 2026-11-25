"""Synthetic photos of curved pages, with ground truth, for testing page flattening.

Usage:
    python tools/testdata/make_curved_pages.py OUT_DIR

For each scene writes ``curved_<name>.jpg`` (the photo), ``curved_<name>_flat.png`` (the page as
it is when flat, grayscale) and ``curved_<name>_corners.txt`` (the page corners in the photo,
normalized, TL TR BR BL as "x y" lines) and ``curved_<name>_uv.bin``: for every 8th photo
pixel (row-major, ⌈H/8⌉×⌈W/8⌉), the true page position (u across, v down, both in [0, 1]) as two
little-endian uint16 holding round(65535 × value); 65535, 65535 off the page. With it the
flattening error of any rendering can be measured exactly.

The page is an unstretched sheet (A4 proportions) bent along its width by an arc-length
parametrized profile, as a book page or a curled sheet is, lit with simple Lambertian shading
and seen by a pinhole camera. Every photo pixel is mapped back to page coordinates with
vectorized Newton iterations, so the rendering is exact (no mesh artefacts).
"""
import math
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

PAGE_W, PAGE_H = 1240, 1754  # flat page, 150 dpi A4
PHOTO_W, PHOTO_H = 1500, 2000  # portrait phone photo
WORDS = (
    "the of invoice total amount due payment terms customer order delivery address date "
    "quantity price description reference account bank transfer tax rate subtotal service "
    "fattura importo totale pagamento scadenza cliente ordine consegna indirizzo prezzo"
).split()


def page_image(seed: int) -> np.ndarray:
    rng = np.random.default_rng(seed)
    img = Image.new("L", (PAGE_W, PAGE_H), 250)
    d = ImageDraw.Draw(img)
    font_dir = Path("C:/Windows/Fonts")
    title = ImageFont.truetype(str(font_dir / "arialbd.ttf"), 54)
    body = ImageFont.truetype(str(font_dir / "arial.ttf"), 26)
    d.text((100, 90), "Invoice 2026-%03d" % seed, font=title, fill=20)
    y = 200
    for _ in range(9):
        line = " ".join(rng.choice(WORDS, 9))
        d.text((100, y), line.capitalize(), font=body, fill=25)
        y += 40
    # A table: straight rules make any leftover bending obvious.
    y += 30
    x0, x1 = 100, PAGE_W - 100
    cols = [x0, 420, 640, 860, x1]
    for r in range(13):
        d.line([(x0, y + r * 52), (x1, y + r * 52)], fill=40, width=3)
        if r < 12:
            for c in range(4):
                d.text((cols[c] + 12, y + r * 52 + 12), rng.choice(WORDS) if c == 0 else "%d.%02d" % (rng.integers(1, 999), rng.integers(0, 99)), font=body, fill=30)
    for c in cols:
        d.line([(c, y), (c, y + 12 * 52)], fill=40, width=3)
    y += 12 * 52 + 50
    for _ in range(5):
        d.text((100, y), " ".join(rng.choice(WORDS, 9)).capitalize(), font=body, fill=25)
        y += 40
    return np.asarray(img, np.float32)


def profile(phi_fn, n=4000):
    """Arc-length parametrized bending curve: s in [0, 1] (page widths) -> X, Z, slope angle."""
    s = np.linspace(0, 1, n)
    phi = phi_fn(s)
    ds = 1 / (n - 1)
    x = np.concatenate([[0], np.cumsum((np.cos(phi[1:]) + np.cos(phi[:-1])) / 2 * ds)])
    z = np.concatenate([[0], np.cumsum((np.sin(phi[1:]) + np.sin(phi[:-1])) / 2 * ds)])
    return s, x, z, phi


def render(name, phi_fn, yaw, pitch, roll, dist, seed, out: Path):
    aspect = PAGE_H / PAGE_W
    s_tab, x_tab, z_tab, phi_tab = profile(phi_fn)
    x_tab = x_tab - (x_tab[0] + x_tab[-1]) / 2
    z_tab = z_tab - z_tab.mean()

    def rot(a, b, c):
        ca, sa, cb, sb, cc, sc = map(lambda t: t, (math.cos(a), math.sin(a), math.cos(b), math.sin(b), math.cos(c), math.sin(c)))
        ry = np.array([[ca, 0, sa], [0, 1, 0], [-sa, 0, ca]])
        rx = np.array([[1, 0, 0], [0, cb, -sb], [0, sb, cb]])
        rz = np.array([[cc, -sc, 0], [sc, cc, 0], [0, 0, 1]])
        return rz @ rx @ ry

    R = rot(yaw, pitch, roll)
    T = np.array([0, 0, dist])
    f = 0.78 * PHOTO_H

    def surface(s, v):
        X = np.interp(s, s_tab, x_tab)
        Z = np.interp(s, s_tab, z_tab)
        Y = (v - 0.5) * aspect
        return np.stack([X, Y, Z], -1)

    def project(P):
        C = P @ R.T + T
        return np.stack([f * C[..., 0] / C[..., 2] + PHOTO_W / 2, f * C[..., 1] / C[..., 2] + PHOTO_H / 2], -1)

    def F(s, v):
        return project(surface(s, v))

    # Corners, and a homography for the Newton starting point.
    corners_sv = np.array([[0, 0], [1, 0], [1, 1], [0, 1]], np.float64)
    corners = F(corners_sv[:, 0], corners_sv[:, 1])
    H = cv2.getPerspectiveTransform(corners.astype(np.float32), corners_sv.astype(np.float32))
    yy, xx = np.mgrid[0:PHOTO_H, 0:PHOTO_W].astype(np.float64)
    pts = cv2.perspectiveTransform(np.stack([xx, yy], -1).reshape(-1, 1, 2), H).reshape(PHOTO_H, PHOTO_W, 2)
    s, v = pts[..., 0].copy(), pts[..., 1].copy()
    target = np.stack([xx, yy], -1)
    eps = 1e-4
    for _ in range(25):
        s = np.clip(s, -0.2, 1.2); v = np.clip(v, -0.2, 1.2)
        p = F(s, v)
        ps = (F(s + eps, v) - p) / eps
        pv = (F(s, v + eps) - p) / eps
        r = target - p
        det = ps[..., 0] * pv[..., 1] - ps[..., 1] * pv[..., 0]
        det = np.where(np.abs(det) < 1e-9, 1e-9, det)
        s += (r[..., 0] * pv[..., 1] - r[..., 1] * pv[..., 0]) / det
        v += (ps[..., 0] * r[..., 1] - ps[..., 1] * r[..., 0]) / det
    err = np.linalg.norm(target - F(s, v), axis=-1)
    inside = (s >= 0) & (s <= 1) & (v >= 0) & (v <= 1) & (err < 0.5)

    flat = page_image(seed)
    page = cv2.remap(flat, (s * (PAGE_W - 1)).astype(np.float32), (v * (PAGE_H - 1)).astype(np.float32), cv2.INTER_LINEAR)
    # Lambertian shading from the surface normal (light from the camera side, slightly above).
    phi = np.interp(s, s_tab, phi_tab)
    normal = np.stack([-np.sin(phi), np.zeros_like(phi), np.cos(phi)], -1) @ R.T
    light = np.array([0.25, -0.35, -1.0]); light /= np.linalg.norm(light)
    shade = np.clip(np.abs(normal @ light), 0.25, 1) * 0.85 + 0.15
    rng = np.random.default_rng(seed + 100)
    bg = np.empty((PHOTO_H, PHOTO_W, 3), np.float32)
    bg[:] = rng.uniform(60, 140, 3)
    bg += cv2.GaussianBlur(rng.normal(0, 25, (PHOTO_H, PHOTO_W, 1)).astype(np.float32), (0, 0), 6)[..., None]
    warm = np.array([1.0, 0.97, 0.9], np.float32)
    photo = np.where(inside[..., None], (page * shade)[..., None] * warm, bg)
    photo += rng.normal(0, 2.5, photo.shape)
    photo = np.clip(photo, 0, 255).astype(np.uint8)
    cv2.imwrite(str(out / f"curved_{name}.jpg"), cv2.cvtColor(photo, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 90])
    cv2.imwrite(str(out / f"curved_{name}_flat.png"), cv2.resize(flat, (620, 877), interpolation=cv2.INTER_AREA).astype(np.uint8))
    uv = np.stack([s, v], -1)[::8, ::8]
    code = np.round(np.clip(uv, 0, 1) * 65535).astype("<u2")
    code[~inside[::8, ::8]] = 65535
    code.tofile(out / f"curved_{name}_uv.bin")
    with open(out / f"curved_{name}_corners.txt", "w") as fh:
        for x, y in corners:
            fh.write(f"{x / PHOTO_W:.5f} {y / PHOTO_H:.5f}\n")
    print(name, "page covers %.0f%% of the photo" % (100 * inside.mean()))


SCENES = [
    # name, slope profile phi(s), yaw, pitch, roll, distance, seed
    ("book", lambda s: 0.55 * np.cos(np.pi * s) - 0.1, 0.05, 0.12, 0.02, 1.4, 1),
    ("curl", lambda s: 0.9 * s ** 3, -0.05, 0.25, -0.04, 1.4, 2),
    ("wave", lambda s: 0.35 * np.sin(2 * np.pi * s), 0.1, 0.1, 0.06, 1.4, 3),
    ("gentle", lambda s: 0.22 * np.cos(np.pi * s), -0.15, 0.3, 0.0, 1.45, 4),
]

if __name__ == "__main__":
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    for scene in SCENES:
        render(*scene, out=out)
