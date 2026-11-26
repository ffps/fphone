#!/usr/bin/env python3
"""Regenerate all raster app icons using the approved yellow-to-green gradient.

The source bird is not redrawn: its per-pixel coverage is recovered from the
existing PWA icon and composited over the new background. The foreground
silhouette in Android's adaptive icon is left untouched.
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "pwa/icon-512.png"
YELLOW = (245, 255, 46)   # bright yellow, top-left
GREEN = (0, 200, 83)      # rich green, bottom-right


def gradient(size):
    w, h = size
    im = Image.new("RGB", size)
    px = im.load()
    for y in range(h):
        for x in range(w):
            t = (x + y) / max(1, (w - 1) + (h - 1))
            px[x, y] = tuple(round(YELLOW[i] * (1 - t) + GREEN[i] * t) for i in range(3))
    return im


def recolor(source):
    source = source.convert("RGB")
    w, h = source.size
    src = source.load()
    out = Image.new("RGB", (w, h))
    dst = out.load()
    c0 = src[0, 0]
    c1 = src[w - 1, h - 1]
    white = (255.0, 255.0, 255.0)
    for y in range(h):
        for x in range(w):
            t = (x + y) / max(1, (w - 1) + (h - 1))
            old_bg = tuple(c0[i] * (1 - t) + c1[i] * t for i in range(3))
            new_bg = tuple(YELLOW[i] * (1 - t) + GREEN[i] * t for i in range(3))
            p = src[x, y]
            delta = tuple(white[i] - old_bg[i] for i in range(3))
            denom = sum(v * v for v in delta)
            alpha = sum((p[i] - old_bg[i]) * delta[i] for i in range(3)) / max(1.0, denom)
            alpha = max(0.0, min(1.0, alpha))
            dst[x, y] = tuple(round(alpha * 255 + (1 - alpha) * new_bg[i]) for i in range(3))
    return out


def save(image, path, size=None):
    if size:
        image = image.resize((size, size), Image.Resampling.LANCZOS)
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def main():
    original = Image.open(SOURCE)
    icon = recolor(original)
    save(icon, ROOT / "pwa/icon-512.png")
    save(icon, ROOT / "pwa/icon-192.png", 192)
    save(icon, ROOT / "app/src/main/res/drawable-nodpi/app_icon.png", 192)
    save(icon, ROOT / "art/icon.png", 96)

    save(gradient((432, 432)), ROOT / "app/src/main/res/drawable-nodpi/ic_launcher_background.png")
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            save(icon, ROOT / "app/src/main/res" / f"mipmap-{density}" / name, size)


if __name__ == "__main__":
    main()
