"""Writes a compact ICC v2 sRGB display profile for the PDF/A output intent.

Usage:
    python tools/pdf/make_srgb_icc.py app/src/main/resources/pdf/srgb.icc

Built from the published sRGB colorimetry (IEC 61966-2-1): primaries adapted to the D50 PCS with
the Bradford transform, the sRGB tone curve sampled at 1024 points. Checked with littleCMS
(through Pillow): converting colours with this profile matches littleCMS's own sRGB.
"""
import struct
import sys
from pathlib import Path


def s15f16(v):
    return struct.pack(">i", round(v * 65536))


def xyz_tag(x, y, z):
    return b"XYZ \0\0\0\0" + s15f16(x) + s15f16(y) + s15f16(z)


def desc_tag(text):
    a = text.encode("ascii") + b"\0"
    return b"desc\0\0\0\0" + struct.pack(">I", len(a)) + a + struct.pack(">II", 0, 0) + struct.pack(">HB", 0, 0) + b"\0" * 67


def text_tag(text):
    return b"text\0\0\0\0" + text.encode("ascii") + b"\0"


def curve_tag(n=1024):
    def srgb(v):
        return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
    vals = [round(srgb(i / (n - 1)) * 65535) for i in range(n)]
    return b"curv\0\0\0\0" + struct.pack(">I", n) + struct.pack(f">{n}H", *vals)


def profile():
    curve = curve_tag()
    tags = [
        (b"desc", desc_tag("sRGB IEC61966-2.1")),
        (b"cprt", text_tag("Public domain")),
        (b"wtpt", xyz_tag(0.9642, 1.0, 0.8249)),  # D50: the PCS white (adapted)
        (b"rXYZ", xyz_tag(0.4361, 0.2225, 0.0139)),
        (b"gXYZ", xyz_tag(0.3851, 0.7169, 0.0971)),
        (b"bXYZ", xyz_tag(0.1431, 0.0606, 0.7141)),
        (b"rTRC", curve), (b"gTRC", curve), (b"bTRC", curve),
    ]
    table_len = 4 + 12 * len(tags)
    offset = 128 + table_len
    data = b""; entries = []; placed = {}
    for sig, body in tags:
        if body in placed:  # the three tone curves share one copy
            entries.append((sig, placed[body], len(body))); continue
        while (offset + len(data)) % 4: data += b"\0"
        placed[body] = offset + len(data)
        entries.append((sig, placed[body], len(body)))
        data += body
    size = offset + len(data)
    header = struct.pack(">I", size) + b"\0\0\0\0" + struct.pack(">I", 0x02100000) + b"mntrRGB XYZ "
    header += struct.pack(">6H", 2026, 1, 1, 0, 0, 0) + b"acsp" + b"\0" * 4 + struct.pack(">I", 0)
    header += b"\0" * 8 + b"\0" * 8 + struct.pack(">I", 0)
    header += s15f16(0.9642) + s15f16(1.0) + s15f16(0.8249) + b"\0" * 4
    header += b"\0" * (128 - len(header))
    table = struct.pack(">I", len(tags)) + b"".join(sig + struct.pack(">II", o, n) for sig, o, n in entries)
    return header + table + data


if __name__ == "__main__":
    icc = profile()
    out = Path(sys.argv[1]); out.parent.mkdir(parents=True, exist_ok=True); out.write_bytes(icc)
    import io
    from PIL import Image, ImageCms
    ours = ImageCms.ImageCmsProfile(io.BytesIO(icc))
    ref = ImageCms.createProfile("sRGB")
    t = ImageCms.buildTransform(ours, ref, "RGB", "RGB")
    img = Image.new("RGB", (6, 1)); img.putdata([(255, 0, 0), (0, 255, 0), (0, 0, 255), (128, 128, 128), (250, 240, 200), (10, 20, 30)])
    got = list(ImageCms.applyTransform(img, t).getdata())
    worst = max(abs(a - b) for p, q in zip(img.getdata(), got) for a, b in zip(p, q))
    print(f"{out}: {len(icc)} bytes, '{ImageCms.getProfileDescription(ours).strip()}', max difference vs littleCMS sRGB: {worst}/255")
    assert worst <= 2
