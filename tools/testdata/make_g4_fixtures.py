"""CCITT G4 test fixtures from an independent implementation (libtiff, through Pillow).

Usage:
    python tools/testdata/make_g4_fixtures.py app/src/test/resources/g4
    python tools/testdata/make_g4_fixtures.py --check FILE.g4 WIDTH HEIGHT REFERENCE.png

The first form writes, for each test image, ``<name>.png`` (1-bit, white = 1) and ``<name>.g4``
(the raw Group 4 data libtiff produced). The second decodes G4 data written by FoglioScan with
libtiff and compares it with the reference image.
"""
import io
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, TiffImagePlugin


def every_run_length(width=2700):
    """Rows of alternating runs covering every length 0..2600, both colours, plus offsets."""
    rows = []
    for start_black in (False, True):
        for base in range(0, 2601, 97):
            row = np.zeros(width, bool); x = 0; black = start_black; n = base
            while x < width:
                run = n % 2601; n += 131
                row[x:x + run] = black; x += run; black = not black
            rows.append(row)
    for k in range(1, 40):  # small shifts between rows: exercises vertical / pass modes
        row = np.zeros(width, bool)
        for s in range(0, width, 7 + k % 5):
            row[s + k % 3: s + k % 3 + 2 + k % 4] = True
        rows.append(row)
    return ~np.array(rows)  # True = white


def text_page():
    img = Image.new("1", (1240, 600), 1)
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 22)
    for i in range(18):
        d.text((40, 20 + i * 31), "Fattura 2026 total amount due %d,%02d EUR — invoice reference" % (i * 37, i), font=f, fill=0)
    d.rectangle([900, 100, 1180, 400], outline=0, width=3)
    d.line([(40, 590), (1200, 560)], fill=0, width=2)
    return np.array(img)


def noise():
    rng = np.random.default_rng(7)
    return rng.random((120, 333)) > 0.5


def g4_bytes(white: np.ndarray) -> bytes:
    TiffImagePlugin.STRIP_SIZE = 1 << 30  # one strip: the whole image
    buf = io.BytesIO()
    # libtiff's fax encoder codes 1-bits as black runs; Pillow saves BlackIsZero, so save the
    # inverted image (1 = ink): the coded runs then mean what a PDF's CCITTFaxDecode expects.
    Image.fromarray(~white).save(buf, format="TIFF", compression="group4")
    im = Image.open(io.BytesIO(buf.getvalue()))
    offsets, counts = im.tag_v2[273], im.tag_v2[279]
    assert len(offsets) == 1, "expected a single strip"
    return buf.getvalue()[offsets[0]:offsets[0] + counts[0]]


def decode_with_libtiff(data: bytes, w: int, h: int) -> np.ndarray:
    """Wraps raw G4 data in a minimal TIFF (WhiteIsZero) and decodes it with libtiff."""
    entries = [
        (256, 4, w), (257, 4, h), (258, 3, 1), (259, 3, 4), (262, 3, 0),
        (273, 4, 8), (277, 3, 1), (278, 4, h), (279, 4, len(data)),
    ]
    ifd_off = 8 + len(data) + (len(data) & 1)
    out = b"II*\x00" + struct.pack("<I", ifd_off) + data + (b"\x00" if len(data) & 1 else b"")
    out += struct.pack("<H", len(entries))
    for tag, typ, val in entries:
        out += struct.pack("<HHI", tag, typ, 1) + (struct.pack("<HH", val, 0) if typ == 3 else struct.pack("<I", val))
    out += struct.pack("<I", 0)
    return np.array(Image.open(io.BytesIO(out)).convert("1"))


if __name__ == "__main__":
    if sys.argv[1] == "--check":
        data = Path(sys.argv[2]).read_bytes(); w, h = int(sys.argv[3]), int(sys.argv[4])
        ref = np.array(Image.open(sys.argv[5]).convert("1"))
        got = decode_with_libtiff(data, w, h)
        bad = int((got != ref).sum())
        print(f"{sys.argv[2]}: libtiff decode {'matches' if bad == 0 else f'differs in {bad} pixels'}")
        sys.exit(1 if bad else 0)
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    for name, img in [("runs", every_run_length()), ("text", text_page()), ("noise", noise())]:
        Image.fromarray(img).save(out / f"{name}.png")
        data = g4_bytes(img)
        (out / f"{name}.g4").write_bytes(data)
        assert (decode_with_libtiff(data, img.shape[1], img.shape[0]) == img).all()
        print(name, img.shape[1], "x", img.shape[0], len(data), "bytes")
