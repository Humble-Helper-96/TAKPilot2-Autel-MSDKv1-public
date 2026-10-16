#!/usr/bin/env python3
"""
Render TAKPilot2-Autel's launcher icon at every density Android asks for.

The mark is concept A: a top-down quadcopter -- an X frame, four rotor rings -- with an aiming
reticle punched through the middle. PMS 281 navy behind PMS 116 gold.

GEOMETRY IS DEFINED ON THE 108x108 ADAPTIVE-ICON GRID and scaled from there, so the vector
adaptive icon and these raster fallbacks are the same drawing rather than two that drifted apart.
Everything lives inside the 72dp safe circle (radius 36 from centre): on API 26+ the launcher
masks the outer ring away and applies parallax, so anything beyond that is not merely cropped on
some devices, it is cropped on all of them, differently.

Supersampled 8x and downsampled with LANCZOS. PIL has no antialiasing of its own, and at 48x48
an aliased rotor ring reads as a smudge.
"""
from PIL import Image, ImageDraw

NAVY = (0x00, 0x20, 0x5B, 255)   # navy, PMS 281
GOLD = (0xFF, 0xCD, 0x00, 255)   # gold, PMS 116

SS = 8          # supersample factor
GRID = 108.0    # adaptive-icon design grid

# How much of the safe circle the mark actually fills.
#
# The safe zone permits 1.0, and that is what this first shipped as — but seen in a real app
# drawer it read as cramped, the rotors nearly touching the tile edge while every neighbouring
# icon had visible margin. "The most the launcher allows" is a constraint, not a design. 0.85
# leaves the mark comfortably inside its own tile.
#
# Mirrored by the group scale in ic_launcher_foreground.xml — change both together.
MARK_SCALE = 0.85

# Density buckets Android expects for a legacy launcher icon.
DENSITIES = {
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}


def draw_mark(d: ImageDraw.ImageDraw, s: float) -> None:
    """The gold quadcopter + reticle. `s` scales the 108-unit grid to pixels."""
    def u(v):                      # grid units -> pixels, scaled about the centre
        return (54.0 + (v - 54.0) * MARK_SCALE) * s

    def w(width):                  # stroke widths shrink with the mark
        return max(1, int(round(width * MARK_SCALE * s)))

    def ring(cx, cy, r, sw):
        d.ellipse([u(cx - r), u(cy - r), u(cx + r), u(cy + r)],
                  outline=GOLD, width=w(sw))

    def bar(x1, y1, x2, y2, sw):
        d.line([u(x1), u(y1), u(x2), u(y2)], fill=GOLD, width=w(sw), joint="curve")

    # X frame. Drawn first so the rotor rings and the reticle sit on top of it.
    bar(36, 36, 72, 72, 6)
    bar(72, 36, 36, 72, 6)

    # Four rotors. Centres 18 units off each axis -> 25.5 from the middle; plus radius 8 and half
    # the 4-unit stroke that is 35.5, just inside the 36 safe radius.
    for cx, cy in ((36, 36), (72, 36), (36, 72), (72, 72)):
        # Knock the arm out of the ring's interior before stroking it, exactly as the reticle
        # does. Without this the arm terminates inside the ring and leaves a blob there, which
        # at 48px stops reading as a rotor and starts reading as noise. Fill to 6 = the stroke's
        # inner edge, so the ring keeps its full 4 units of weight.
        d.ellipse([u(cx - 6), u(cy - 6), u(cx + 6), u(cy + 6)], fill=NAVY)
        ring(cx, cy, 8, 4)

    # Reticle. The navy disc punches the frame out from under it so the ring reads as an
    # aperture rather than a bead sitting on two crossed sticks.
    d.ellipse([u(54 - 12), u(54 - 12), u(54 + 12), u(54 + 12)], fill=NAVY)
    ring(54, 54, 10, 5)
    bar(54, 35, 54, 43, 5)         # top tick
    bar(54, 65, 54, 73, 5)         # bottom tick
    d.ellipse([u(54 - 2.5), u(54 - 2.5), u(54 + 2.5), u(54 + 2.5)], fill=GOLD)


def render(px: int, shape: str) -> Image.Image:
    big = px * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = big / GRID

    if shape == "round":
        d.ellipse([0, 0, big - 1, big - 1], fill=NAVY)
    else:
        # Rounded square for the legacy icon. Pre-API-26 launchers do NOT mask, so the icon has
        # to carry its own silhouette or it renders as a hard-edged tile.
        d.rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.22), fill=NAVY)

    draw_mark(d, s)
    return img.resize((px, px), Image.LANCZOS)


def main() -> None:
    import sys
    res = sys.argv[1]
    for bucket, px in DENSITIES.items():
        out = f"{res}/mipmap-{bucket}"
        render(px, "square").save(f"{out}/ic_launcher.png")
        render(px, "round").save(f"{out}/ic_launcher_round.png")
        print(f"  {bucket:8s} {px}x{px}  ic_launcher.png + ic_launcher_round.png")


if __name__ == "__main__":
    main()
