#!/usr/bin/env python3
"""Build the README gallery, the hero banners, the social preview and the store
screenshots from the ReadmeShots previews.

    ./gradlew :app:updateDebugScreenshotTest
    python3 scripts/readme_shots.py [--font path/to/Inter.ttf]

Everything comes from app/src/screenshotTest/.../ReadmeShots.kt, which renders
the real screens from an invented network (Showcase.kt) — no device, nobody's
real network. Needs Pillow; cairosvg draws the logo (docs/images/logo-hop.svg),
without it the launcher bitmap stands in. Inter (a variable TTF) makes the text
nicer: appctr/build.sh leaves one in NetBird's sources, which is used when it is
there; otherwise DejaVu.
"""
import argparse
import glob
import io
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

REF = "app/src/screenshotTestDebug/reference/io/github/bropines/birdsocks/ui/ReadmeShotsKt"
OUT = "docs/screenshots"
LOGO = "docs/images/logo-hop.svg"
LANGS = {"en": "en-US", "ru": "ru"}  # README language -> fastlane locale
# Preview -> gallery file. PeerSheet is not here: it is laid over Peers (peer_details).
NAMES = {
    "Main": "main", "MainLight": "main-light", "Peers": "peers", "PeersLight": "peers-light",
    "Networks": "networks", "Dns": "dns", "Diagnostics": "diagnostics", "Settings": "settings",
    "Connection": "server-connection", "Vpn": "vpn-mode", "AddAccount": "add-account",
    "SettingsWide": "settings-wide", "Tablet": "tablet",
}
# The store listing, in order: phones first, then the tablet.
STORE_PHONE = ["Main", "Peers", "PeerDetails", "Networks", "Dns", "Connection", "Vpn", "Diagnostics"]
STORE_TABLET = ["Tablet"]
# The tagline in its lines; the features are wrapped between items.
TAGLINE = {
    "en": ["Unofficial NetBird client", "for Android"],
    "ru": ["Неофициальный клиент", "NetBird для Android"],
}
FEATURES = {
    "en": ["SOCKS5", "DNS proxy", "VPN mode", "exit nodes", "ByeDPI"],
    "ru": ["SOCKS5", "DNS-прокси", "VPN-режим", "выходные узлы", "ByeDPI"],
}
FONT_CANDIDATES = [
    "appctr/netbird_orig/client/ui/frontend/src/assets/fonts/inter-variable.ttf",
    "appctr/netbird_src/client/ui/frontend/src/assets/fonts/inter-variable.ttf",
]
BG = (11, 8, 6)
GLOW = (190, 84, 24)
TEXT = (247, 244, 241)
ACCENT = (255, 185, 120)
MUTED = (170, 150, 135)


def shot(name, lang):
    if name == "PeerDetails":
        return peer_details(lang)
    found = glob.glob(f"{REF}/Readme{name}_{lang}_*.png")
    if not found:
        sys.exit(f"missing render Readme{name}_{lang}; run ./gradlew :app:updateDebugScreenshotTest first")
    return Image.open(found[0]).convert("RGB")


def peer_details(lang):
    """The details sheet over the dimmed Peers list.

    The sheet is a window of its own, and the renderer draws it over a blank
    window with Material's scrim (32 % black) rather than over the screen
    beneath. The sheet is cut out of its render and laid on the Peers render,
    dimmed the same way.
    """
    under = shot("Peers", lang)
    sheet = shot("PeerSheet", lang)
    w, h = sheet.size
    grey = sheet.getpixel((4, 4))
    top = next(
        (y for y in range(h) if max(abs(a - b) for a, b in zip(sheet.getpixel((w // 6, y)), grey)) > 12),
        None,
    )
    if top is None:
        sys.exit("ReadmePeerSheet shows no sheet")
    dimmed = Image.blend(under, Image.new("RGB", under.size, (0, 0, 0)), 0.32)
    # The sheet's top corners: Material's extraLarge, 28 dp at the render's 420 dpi.
    radius = round(28 * 420 / 160)
    scale = 4
    mask = Image.new("L", (w * scale, h * scale), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, top * scale, w * scale, (h + radius) * scale], radius * scale, fill=255)
    mask = mask.resize((w, h), Image.LANCZOS)
    dimmed.paste(sheet, (0, 0), mask)
    return dimmed


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def device(im, width, bezel=10):
    """A screen at `width`, in a thin dark bezel with the corners of a phone."""
    h = round(im.height * width / im.width)
    screen = im.resize((width, h), Image.LANCZOS)
    r = max(12, int(min(width, h) * 0.09))
    frame = rounded(Image.new("RGB", (width + 2 * bezel, h + 2 * bezel), (40, 36, 33)), r + bezel)
    frame.alpha_composite(rounded(screen, r), (bezel, bezel))
    return frame


def with_shadow(layer, blur=36, offset=(0, 28), alpha=170):
    pad = blur * 3
    canvas = Image.new("RGBA", (layer.width + 2 * pad, layer.height + 2 * pad), (0, 0, 0, 0))
    shade = Image.new("RGBA", layer.size, (0, 0, 0, alpha))
    shade.putalpha(Image.eval(layer.getchannel("A"), lambda a: a * alpha // 255))
    canvas.alpha_composite(shade, (pad + offset[0], pad + offset[1]))
    canvas = canvas.filter(ImageFilter.GaussianBlur(blur))
    canvas.alpha_composite(layer, (pad, pad))
    return canvas, pad


def backdrop(w, h, glow_at=0.62):
    """Near-black with a soft orange glow behind the phones — the bird's colour."""
    base = Image.new("RGB", (w, h), BG)
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    cx = w * glow_at
    ImageDraw.Draw(glow).ellipse([cx - w * 0.30, -h * 0.35, cx + w * 0.30, h * 0.75], fill=GLOW)
    glow = glow.filter(ImageFilter.GaussianBlur(min(w, h) // 4))
    return Image.blend(base, glow, 0.30).convert("RGBA")


def place(canvas, layer, cx, top):
    shadowed, pad = with_shadow(layer)
    canvas.alpha_composite(shadowed, (int(cx - shadowed.width / 2), int(top - pad)))


WEIGHTS = {b"Regular": 400, b"Medium": 500, b"SemiBold": 600, b"Bold": 700}


def font(path, size, weight):
    try:
        f = ImageFont.truetype(path, size)
        try:
            axes = [a["name"] for a in f.get_variation_axes()]
            if b"Optical size" in axes:
                # Inter's display cut for large text, its text cut for small.
                opsz = 32 if size >= 48 else max(14, min(32, size * 0.6))
                f.set_variation_by_axes([opsz if a == b"Optical size" else WEIGHTS.get(weight, 400) for a in axes])
            else:
                f.set_variation_by_name(weight)
        except Exception:
            pass
        return f
    except Exception:
        bold = weight in (b"Bold", b"Black", b"SemiBold")
        return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans" + ("-Bold" if bold else "") + ".ttf", size)


def wrap(draw, items, f, width, sep=" · "):
    """`items` joined by `sep` into lines no wider than `width`, broken between items."""
    lines, line = [], ""
    for item in items:
        trial = f"{line}{sep}{item}" if line else item
        if line and draw.textlength(trial, font=f) > width:
            lines.append(line)
            line = item
        else:
            line = trial
    return lines + [line] if line else lines


def logo(size):
    """docs/images/logo-hop.svg when cairosvg is available, else the launcher bitmap."""
    try:
        import cairosvg
        png = cairosvg.svg2png(url=LOGO, output_width=size * 2, output_height=size * 2)
        return Image.open(io.BytesIO(png)).convert("RGBA").resize((size, size), Image.LANCZOS)
    except Exception:
        path = "app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp"
        return Image.open(path).convert("RGBA").resize((size, size), Image.LANCZOS) if os.path.exists(path) else None


def title_block(c, lang, font_path, x, y, logo_size, name_size, tag_size, feat_size, width):
    """The logo, the name, the tagline and the features line, from (x, y) down."""
    d = ImageDraw.Draw(c)
    icon = logo(logo_size)
    if icon is not None:
        shadowed, pad = with_shadow(icon, blur=logo_size // 8, offset=(0, logo_size // 12), alpha=140)
        c.alpha_composite(shadowed, (x - pad, y - pad))
    y += logo_size + round(name_size * 0.45)
    d.text((x - round(name_size * 0.04), y), "BirdSocks", font=font(font_path, name_size, b"Bold"), fill=TEXT)
    y += round(name_size * 1.25)
    tag = font(font_path, tag_size, b"Medium")
    for line in TAGLINE[lang]:
        d.text((x, y), line, font=tag, fill=ACCENT)
        y += round(tag_size * 1.3)
    y += round(feat_size * 0.9)
    feat = font(font_path, feat_size, b"Regular")
    for line in wrap(d, FEATURES[lang], feat, width):
        d.text((x, y), line, font=feat, fill=MUTED)
        y += round(feat_size * 1.35)


def hero(lang, font_path):
    """2400x1180: the name on the left, three phones on the right."""
    w, h = 2400, 1180
    c = backdrop(w, h)
    title_block(c, lang, font_path, x=150, y=300, logo_size=190, name_size=150, tag_size=56, feat_size=36, width=820)
    side = [device(shot("Peers", lang), 470), device(shot("Networks", lang), 470)]
    front = device(shot("Main", lang), 540)
    place(c, side[0], 1290, 190)
    place(c, side[1], 2110, 190)
    place(c, front, 1700, 80)
    return c.convert("RGB")


def social(font_path):
    """1280x640, what GitHub shows when the repository link is shared."""
    w, h = 1280, 640
    c = backdrop(w, h, glow_at=0.75)
    title_block(c, "en", font_path, x=84, y=120, logo_size=112, name_size=88, tag_size=34, feat_size=24, width=660)
    for img, cx, top in ((shot("Peers", "en"), 920, 120), (shot("Main", "en"), 1110, 70)):
        place(c, device(img, 270, 6), cx, top)
    return c.convert("RGB")


def store(im):
    """A store screenshot: PNG, and no taller than twice its width (Play's limit)."""
    if im.height > 2 * im.width:
        im = im.crop((0, 0, im.width, 2 * im.width))
    return im


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", default=next((f for f in FONT_CANDIDATES if os.path.exists(f)), ""),
                    help="variable Inter TTF for the banners")
    args = ap.parse_args()

    for lang, locale in LANGS.items():
        out = f"{OUT}/{lang}"
        os.makedirs(out, exist_ok=True)
        gallery = {**NAMES, "PeerDetails": "peer-details"}
        for name, file in gallery.items():
            im = shot(name, lang)
            width = 1080 if im.width > im.height else 540
            im.resize((width, round(im.height * width / im.width)), Image.LANCZOS).save(f"{out}/{file}.webp", quality=90, method=6)
        hero(lang, args.font).save(f"{out}/hero.webp", quality=90, method=6)

        # F-Droid / IzzyOnDroid / Play listing: PNG, phones first.
        for folder, names in (("phoneScreenshots", STORE_PHONE), ("tenInchScreenshots", STORE_TABLET)):
            d = f"fastlane/metadata/android/{locale}/images/{folder}"
            os.makedirs(d, exist_ok=True)
            for old in glob.glob(f"{d}/*.png"):
                os.remove(old)
            for i, name in enumerate(names, 1):
                store(shot(name, lang)).save(f"{d}/{i}.png", optimize=True)

    social(args.font).save("docs/social-preview.png", optimize=True)
    print("gallery, hero banners, store screenshots and docs/social-preview.png written")


if __name__ == "__main__":
    main()
