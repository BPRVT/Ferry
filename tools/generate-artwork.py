#!/usr/bin/env python3
"""
Generates Ferry's launcher icons and Fire TV banner.

The mark is a ferry silhouette — the name is the whole idea, and a literal boat
says what the app is faster than an abstract glyph does. The palette is the
brutalist mono one used in-app (see res/values/colors.xml): near-black ground, a
single terminal-green accent, monospace type, hard edges, hairline structure.

The accent rule doubles as the waterline, so the one graphic device that runs
through the whole UI is also what the boat sits on.

Flat shapes and high contrast throughout, because launcher art is scaled hard
and recompressed, and TV launchers sit on dark backgrounds where a light tile
glares.

Run:  python3 tools/generate-artwork.py app/src/main/res
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

# Palette — must track res/values/colors.xml.
GROUND = (10, 10, 10)       # background_dark
ACCENT = (0, 229, 160)      # accent_blue (terminal green)
HULL = (255, 255, 255)      # text_primary — the boat reads at any size
MUTED = (138, 138, 138)     # text_secondary
HAIRLINE = (46, 46, 46)     # divider
GRID = (20, 20, 20)         # sub-divider, only just visible

# Menlo.ttc: index 0 = Regular, 1 = Bold. Present on every macOS; the fallbacks
# cover Linux CI in case this is ever run there.
MONO_CANDIDATES = (
    ("/System/Library/Fonts/Menlo.ttc", 1),
    ("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf", 0),
    ("/usr/share/fonts/truetype/liberation/LiberationMono-Bold.ttf", 0),
)


def mono(size):
    for path, index in MONO_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size, index=index)
            except Exception:
                continue
    return ImageFont.load_default()


def ferry(d, cx, cy, scale, porthole_fill=ACCENT):
    """
    Ferry silhouette: hull (trapezoid), deckhouse, wheelhouse, funnel, portholes.

    Drawn around centre (cx, cy) where cy is the waterline; `scale` is roughly
    the hull half-width in px. Extends from cy - 0.78*scale (funnel top) to
    cy + 0.34*scale (hull bottom), which is what callers use to seat it.

    Squared off rather than curved — the rest of the identity has no radii, and
    a rounded hull next to the hard-edged wordmark looks like an accident.
    """
    s = scale
    # Hull — wider at deck, tapered toward the waterline.
    d.polygon([
        (cx - s,        cy),
        (cx + s,        cy),
        (cx + s * 0.72, cy + s * 0.34),
        (cx - s * 0.72, cy + s * 0.34),
    ], fill=HULL)
    # Deckhouse.
    d.rectangle([cx - s * 0.62, cy - s * 0.40, cx + s * 0.40, cy], fill=HULL)
    # Upper wheelhouse.
    d.rectangle([cx - s * 0.34, cy - s * 0.63, cx + s * 0.08, cy - s * 0.40], fill=HULL)
    # Funnel, raked slightly.
    d.polygon([
        (cx + s * 0.16, cy - s * 0.40),
        (cx + s * 0.40, cy - s * 0.40),
        (cx + s * 0.36, cy - s * 0.78),
        (cx + s * 0.22, cy - s * 0.78),
    ], fill=HULL)
    # Portholes — squares, not circles, and the only accent on the boat itself.
    r = max(1.0, s * 0.055)
    for i in range(3):
        px = cx - s * 0.44 + i * s * 0.30
        py = cy - s * 0.24
        d.rectangle([px - r, py - r, px + r, py + r], fill=porthole_fill)


def make_banner(w=1280, h=720):
    """
    Fire TV banner, drawn at full 1280x720 and returned for downscaling.

    Layout: wordmark left, ferry right, both seated on the accent waterline that
    spans the content width, subtitle beneath.

    Fire OS draws the app title and a selection border over this image and older
    launchers crop a few percent, so everything stays inside a 64px safe margin.
    """
    margin, pad = 64, 42
    left = margin + pad
    right = w - margin - pad
    content_w = right - left

    img = Image.new("RGB", (w, h), GROUND)
    d = ImageDraw.Draw(img)

    def tracked_width(text, font, tracking):
        if not text:
            return 0
        return sum(d.textlength(c, font=font) for c in text) + tracking * (len(text) - 1)

    def draw_tracked(x, y, text, font, fill, tracking):
        for ch in text:
            d.text((x, y), ch, font=font, fill=fill, anchor="lt")
            x += d.textlength(ch, font=font) + tracking

    word = "FERRY"
    boat_scale = 118
    boat_w = boat_scale * 2
    gap = 70

    # Grow the wordmark until it fills the width left over by the boat.
    budget = content_w - boat_w - gap
    size = 80
    while size < 320:
        probe = mono(size + 4)
        if tracked_width(word, probe, (size + 4) * 0.14) > budget:
            break
        size += 4
    f_word = mono(size)
    track = size * 0.14
    word_w = tracked_width(word, f_word, track)

    # Measured, not nominal: mono faces carry large internal leading, so the
    # visual cap height is what the vertical rhythm should be built on.
    bbox = d.textbbox((0, 0), word, font=f_word, anchor="lt")
    word_h = bbox[3] - bbox[1]

    f_sub = mono(38)
    sub = "AIRPLAY  MIRACAST  CAST"

    rule_h, gap_above, gap_sub, sub_h = 7, 30, 40, 38
    block_h = word_h + gap_above + rule_h + gap_sub + sub_h
    top = (h - block_h) / 2
    rule_y = top + word_h + gap_above

    # Structure: faint column grid and a hairline frame, echoing the 1dp borders
    # that carry the in-app layout.
    step = content_w // 8
    for i in range(9):
        gx = left + i * step
        d.line([(gx, margin), (gx, h - margin)], fill=GRID, width=1)
    d.rectangle([margin, margin, w - margin, h - margin], outline=HAIRLINE, width=1)

    # Wordmark, bottom-aligned to the waterline.
    draw_tracked(left, top - bbox[1], word, f_word, HULL, track)

    # Ferry, seated on the waterline at the right end of the content area.
    # Nudged down so the hull crosses the rule and reads as floating in it.
    ferry(d, right - boat_scale, rule_y - 4, boat_scale)

    # Waterline — drawn after the boat so the rule reads as passing in front of
    # the hull, which is what sells the boat as being *in* the water.
    d.rectangle([left, rule_y, right, rule_y + rule_h], fill=ACCENT)

    draw_tracked(left + 4, rule_y + rule_h + gap_sub, sub, f_sub, MUTED, 7)

    print(f"  banner  base {w}x{h} (wordmark {size}px)")
    return img


def make_icon(path, size):
    """
    Square launcher icon: the ferry on its waterline, no text.

    No wordmark — at the 48px mdpi bucket lettering turns to mush, and the
    silhouette survives the downscale where type does not.
    """
    img = Image.new("RGB", (size, size), GROUND)
    d = ImageDraw.Draw(img)

    # Hairline frame, but only where it survives: below ~72px it collapses
    # against the silhouette and just muddies the shape.
    if size >= 72:
        inset = max(1, round(size * 0.055))
        d.rectangle([inset, inset, size - inset - 1, size - inset - 1],
                    outline=HAIRLINE, width=max(1, round(size / 96)))

    # Centre the *drawn* extent, not the waterline. The silhouette runs from
    # 0.78*boat above the waterline to 0.34*boat below it, so seating the boat
    # by its waterline leaves a large hole above the funnel.
    boat = size * 0.34
    block_h = boat * 1.12 + max(2, round(size * 0.055))
    waterline = (size - block_h) / 2 + boat * 0.78

    # At the smallest bucket the 3 portholes merge into a smear; drop them and
    # let the accent waterline carry the colour instead.
    ferry(d, size * 0.5, waterline, boat,
          porthole_fill=ACCENT if size >= 72 else HULL)

    rule_h = max(2, round(size * 0.055))
    rule_w = size * 0.62
    d.rectangle([(size - rule_w) / 2, waterline + boat * 0.34,
                 (size + rule_w) / 2, waterline + boat * 0.34 + rule_h],
                fill=ACCENT)

    img.save(path, "PNG", optimize=True)
    print(f"  icon    {path} ({size}x{size})")


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: generate-artwork.py <res_dir>")
    res = sys.argv[1]

    # ONE banner, full size, density-independent.
    #
    # Fire OS asks for 1280x720 for the launcher banner — four times the linear
    # size of the Android TV 320x180-at-xhdpi figure. 1.1.1 split this into
    # density buckets sized to the Android TV spec, which handed a Fire TV stick
    # (xhdpi) a 320x180 image for a tile built to hold 1280x720, so the artwork
    # sat small inside the tile instead of filling it.
    #
    # drawable-nodpi is the right home for it: this is a fixed-size asset the
    # launcher scales into its own slot, not something that should shrink because
    # the panel reports a lower density. Every TV this runs on is 1080p or 4K, so
    # there is no small-screen case that a downscaled bucket would serve.
    base = make_banner()
    for stale in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        p = os.path.join(res, f"drawable-{stale}", "app_banner.png")
        if os.path.exists(p):
            os.remove(p)
            print(f"  removed {p}")
            d = os.path.dirname(p)
            if not os.listdir(d):
                os.rmdir(d)

    banner_dir = os.path.join(res, "drawable-nodpi")
    os.makedirs(banner_dir, exist_ok=True)
    banner_path = os.path.join(banner_dir, "app_banner.png")
    base.save(banner_path, "PNG", optimize=True)
    print(f"  banner  {banner_path} (1280x720)")

    # Standard launcher densities (mdpi baseline 48dp).
    for bucket, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                       ("xxhdpi", 144), ("xxxhdpi", 192)):
        dd = os.path.join(res, f"mipmap-{bucket}")
        os.makedirs(dd, exist_ok=True)
        make_icon(os.path.join(dd, "ic_launcher.png"), px)

    # README artwork. Kept as its own copy rather than pointing the README at
    # mipmap-xxxhdpi/ic_launcher.png: GitHub's raw CDN caches by URL, so a
    # redesign that reuses the old path can keep serving the old icon for days.
    # Regenerating alongside the launcher icons is what keeps the two in step.
    assets = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                          "docs", "assets")
    os.makedirs(assets, exist_ok=True)
    make_icon(os.path.join(assets, "ferry-icon.png"), 256)
    base.save(os.path.join(assets, "ferry-banner.png"), "PNG", optimize=True)
    print(f"  banner  {os.path.join(assets, 'ferry-banner.png')} (1280x720)")


if __name__ == "__main__":
    main()
