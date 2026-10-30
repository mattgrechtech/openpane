#!/usr/bin/env python3
"""Generates the TV banner and launcher icons from code, so they can be redone when the name changes.

    python tools/make_assets.py "OpenPane"

Needs Pillow (pip install pillow). Writes:
  app/src/main/res/drawable-xhdpi/banner.png        320x180 (Google Play TV rules TV-LB/TV-BN: must show the name)
  app/src/main/res/mipmap-*/ic_launcher.png          48-192 px
"""
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"

BG_TOP = (18, 24, 38)
BG_BOTTOM = (6, 9, 16)
ACCENT = (3, 169, 244)
WHITE = (255, 255, 255)

FONT_CANDIDATES = [
    "C:/Windows/Fonts/segoeuib.ttf",
    "C:/Windows/Fonts/arialbd.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
]


def font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def gradient(w, h, scale=1):
    img = Image.new("RGB", (w * scale, h * scale))
    draw = ImageDraw.Draw(img)
    for y in range(h * scale):
        t = y / max(1, h * scale - 1)
        colour = tuple(round(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM))
        draw.line([(0, y), (w * scale, y)], fill=colour)
    return img


def tv_glyph(draw, x, y, size, width):
    """A TV outline with a small house inside, in a size x size box at (x, y)."""
    s = size
    screen = [x, y + s * 0.08, x + s, y + s * 0.74]
    draw.rounded_rectangle(screen, radius=s * 0.08, outline=ACCENT, width=width)
    draw.line([(x + s * 0.5, y + s * 0.74), (x + s * 0.5, y + s * 0.86)], fill=ACCENT, width=width)
    draw.line([(x + s * 0.28, y + s * 0.88), (x + s * 0.72, y + s * 0.88)], fill=ACCENT, width=width)
    cx, top = x + s * 0.5, y + s * 0.22
    house = [
        (cx, top), (cx + s * 0.2, top + s * 0.16), (cx + s * 0.2, top + s * 0.4),
        (cx - s * 0.2, top + s * 0.4), (cx - s * 0.2, top + s * 0.16),
    ]
    draw.polygon(house, fill=WHITE)


def banner(name):
    scale = 4  # draw large, scale down for smooth edges
    w, h = 320, 180
    img = gradient(w, h, scale)
    draw = ImageDraw.Draw(img)
    glyph = 104 * scale
    tv_glyph(draw, 22 * scale, (h * scale - glyph) // 2, glyph, 6 * scale)

    text_x = 140 * scale
    max_w = (w - 140 - 14) * scale
    size = 44 * scale
    while size > 14 * scale:
        f = font(size)
        lines = wrap(draw, name, f, max_w)
        if len(lines) <= 2:
            break
        size -= 2 * scale
    line_h = f.getbbox("Ag")[3] + 4 * scale
    total = line_h * len(lines)
    y = (h * scale - total) // 2
    for line in lines:
        draw.text((text_x, y), line, font=f, fill=WHITE)
        y += line_h
    out = RES / "drawable-xhdpi" / "banner.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.resize((w, h), Image.LANCZOS).save(out)
    print("wrote", out.relative_to(ROOT))


def wrap(draw, text, f, max_w):
    words, lines, current = text.split(), [], ""
    for word in words:
        trial = (current + " " + word).strip()
        if draw.textlength(trial, font=f) <= max_w:
            current = trial
        else:
            if current:
                lines.append(current)
            current = word
    if current:
        lines.append(current)
    return lines


def icons():
    for folder, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        scale = 4
        size = px * scale
        img = gradient(px, px, scale)
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.22, fill=255)
        draw = ImageDraw.Draw(img)
        tv_glyph(draw, size * 0.16, size * 0.14, size * 0.68, max(2, round(size * 0.05)))
        rgba = img.convert("RGBA")
        rgba.putalpha(mask)
        out = RES / f"mipmap-{folder}" / "ic_launcher.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        rgba.resize((px, px), Image.LANCZOS).save(out)
        print("wrote", out.relative_to(ROOT))


if __name__ == "__main__":
    app_name = sys.argv[1] if len(sys.argv) > 1 else "OpenPane"
    banner(app_name)
    icons()
