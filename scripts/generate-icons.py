#!/usr/bin/env python3
"""Generates the raster brand icon used by the desktop window and README.

The project has no image tooling in CI or on the Termux workstation, so the
squircle, gradient, cloud and arrow are rasterised with the standard library
only. Run `python3 scripts/generate-icons.py` after changing the brand palette
or the mark geometry.

Outputs:
  composeApp/src/desktopMain/resources/icon.png   (512 x 512, window icon)
  androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png        (192 x 192)
  androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png  (192 x 192)
"""

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Brand palette (kept in sync with ui/theme/Color.kt).
INDIGO_DEEP = (67, 56, 202)
INDIGO = (79, 70, 229)
TEAL = (8, 145, 178)
ARROW = (49, 46, 129)
WHITE = (255, 255, 255)

# Geometry in normalised 0..1 coordinates.
CORNER = 0.225
CLOUD_CIRCLES = (
    (0.355, 0.515, 0.150),
    (0.505, 0.445, 0.185),
    (0.650, 0.520, 0.145),
)
CLOUD_BASE = (0.215, 0.520, 0.785, 0.700, 0.090)
ARROW_POINTS = (
    (0.500, 0.360),
    (0.618, 0.505),
    (0.552, 0.505),
    (0.552, 0.655),
    (0.448, 0.655),
    (0.448, 0.505),
    (0.382, 0.505),
)


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[float, float, float]:
    t = max(0.0, min(1.0, t))
    return (
        a[0] + (b[0] - a[0]) * t,
        a[1] + (b[1] - a[1]) * t,
        a[2] + (b[2] - a[2]) * t,
    )


def gradient_at(x: float, y: float) -> tuple[int, int, int]:
    t = (x + y) * 0.5
    if t < 0.5:
        color = mix(INDIGO_DEEP, INDIGO, t * 2.0)
    else:
        color = mix(INDIGO, TEAL, (t - 0.5) * 2.0)
    return int(color[0] + 0.5), int(color[1] + 0.5), int(color[2] + 0.5)


def in_rounded_rect(x: float, y: float, left: float, top: float, right: float, bottom: float, radius: float) -> bool:
    if x < left or x > right or y < top or y > bottom:
        return False
    cx = min(max(x, left + radius), right - radius)
    cy = min(max(y, top + radius), bottom - radius)
    dx = x - cx
    dy = y - cy
    return dx * dx + dy * dy <= radius * radius


def in_cloud(x: float, y: float) -> bool:
    for cx, cy, r in CLOUD_CIRCLES:
        dx = x - cx
        dy = y - cy
        if dx * dx + dy * dy <= r * r:
            return True
    left, top, right, bottom, radius = CLOUD_BASE
    return in_rounded_rect(x, y, left, top, right, bottom, radius)


def in_polygon(x: float, y: float, points: tuple[tuple[float, float], ...]) -> bool:
    inside = False
    j = len(points) - 1
    for i, (xi, yi) in enumerate(points):
        xj, yj = points[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def sample(x: float, y: float, round_icon: bool = False) -> tuple[int, int, int, int]:
    if round_icon:
        dx = x - 0.5
        dy = y - 0.5
        if dx * dx + dy * dy > 0.25:
            return 0, 0, 0, 0
    elif not in_rounded_rect(x, y, 0.0, 0.0, 1.0, 1.0, CORNER):
        return 0, 0, 0, 0

    r, g, b = gradient_at(x, y)

    # Soft light from the top-left keeps the flat gradient from feeling dull.
    glow = max(0.0, 1.0 - math.hypot(x - 0.18, y - 0.12) / 0.9)
    if glow > 0.0:
        alpha = glow * glow * 0.10
        r += (255 - r) * alpha
        g += (255 - g) * alpha
        b += (255 - b) * alpha

    # Inner hairline ring.
    if round_icon:
        distance = math.hypot(x - 0.5, y - 0.5)
        ring = 0.494 <= distance <= 0.500
    else:
        ring = in_rounded_rect(x, y, 0.0, 0.0, 1.0, 1.0, CORNER) and not in_rounded_rect(
            x, y, 0.006, 0.006, 0.994, 0.994, CORNER - 0.006
        )
    if ring:
        r += (255 - r) * 0.16
        g += (255 - g) * 0.16
        b += (255 - b) * 0.16

    # A one-percent drop shadow separates the white cloud from the gradient.
    if in_cloud(x, y - 0.012):
        r *= 0.72
        g *= 0.72
        b *= 0.72

    if in_cloud(x, y):
        r, g, b = WHITE

    if in_polygon(x, y, ARROW_POINTS):
        r, g, b = ARROW

    return int(r + 0.5), int(g + 0.5), int(b + 0.5), 255


def render(size: int, supersample: int = 2, round_icon: bool = False) -> bytes:
    hi = size * supersample
    step = 1.0 / hi
    rows: list[bytes] = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            red = green = blue = alpha = 0
            for sy in range(supersample):
                for sx in range(supersample):
                    x = (px * supersample + sx + 0.5) * step
                    y = (py * supersample + sy + 0.5) * step
                    sr, sg, sb, sa = sample(x, y, round_icon)
                    red += sr
                    green += sg
                    blue += sb
                    alpha += sa
            count = supersample * supersample
            row += bytes((red // count, green // count, blue // count, alpha // count))
        rows.append(bytes(row))
    return b"".join(b"\x00" + row for row in rows)


def write_png(path: Path, size: int, round_icon: bool = False) -> None:
    raw = render(size, round_icon=round_icon)
    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)
    print(f"wrote {path.relative_to(ROOT)} ({size}x{size}, {len(png) // 1024} KB)")


def main() -> None:
    write_png(ROOT / "composeApp/src/desktopMain/resources/icon.png", 512)
    write_png(ROOT / "androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher.png", 192)
    write_png(ROOT / "androidApp/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png", 192, round_icon=True)


if __name__ == "__main__":
    main()
