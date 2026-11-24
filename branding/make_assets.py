#!/usr/bin/env python3
"""Generate the WaveTune brand assets: launcher icons, splash video, wordmark-free marks."""
import math
import os
import shutil
import subprocess
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "branding")
RES = os.path.join(ROOT, "app/src/main/res")

LIME = (198, 241, 53)
TEAL = (25, 194, 166)
MID = (72, 220, 150)
DARK = (12, 18, 21)
DARKER = (5, 8, 10)

# Normalised polyline for the "W" waveform.
PTS = [(0.10, 0.30), (0.30, 0.72), (0.50, 0.40), (0.70, 0.72), (0.90, 0.30)]
STROKE = 0.125  # of canvas


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def grad_color(t):
    return lerp(LIME, TEAL, t) if t < 0.5 else lerp(MID, TEAL, (t - 0.5) * 2)


def poly_points(size, scale=1.0, cx=0.5, cy=0.5):
    w = 0.78 * scale
    h = 0.50 * scale
    out = []
    for x, y in PTS:
        out.append(((cx + (x - 0.5) * w) * size, (cy + (y - 0.5) * h) * size))
    return out


def emblem(size, scale=1.0, mono=False, glow=True, glow_strength=1.0):
    """RGBA emblem at the requested pixel size."""
    S = size * 4  # supersample
    mask = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(mask)
    pts = poly_points(S, scale)
    width = int(0.105 * S * scale)
    d.line(pts, fill=255, width=width, joint="curve")
    r = width / 2
    for p in (pts[0], pts[-1]):
        d.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)

    if mono:
        out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
        out.putalpha(mask)
        return out

    # horizontal gradient inside the stroke
    grad = Image.new("RGBA", (size, size))
    gd = ImageDraw.Draw(grad)
    for x in range(size):
        c = grad_color(x / max(1, size - 1))
        gd.line([(x, 0), (x, size)], fill=c + (255,))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)

    if glow:
        halo = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        halo.paste(Image.new("RGBA", (size, size), TEAL + (255,)), (0, 0), mask)
        halo = halo.filter(ImageFilter.GaussianBlur(size * 0.035))
        alpha = halo.getchannel("A").point(lambda v: int(v * 0.55 * glow_strength))
        halo.putalpha(alpha)
        base = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        base.alpha_composite(halo)
        base.alpha_composite(out)
        out = base
    return out


def rounded_bg(size, radius=0.22, circle=False):
    S = size * 4
    bg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(bg)
    # radial-ish two-tone: draw concentric rectangles
    d.rectangle([0, 0, S, S], fill=DARKER + (255,))
    for i in range(40):
        t = i / 39
        c = lerp(DARK, DARKER, t)
        pad = int(S * 0.5 * t)
        d.ellipse([pad - S * 0.25, pad - S * 0.25, S - pad + S * 0.25, S - pad + S * 0.25],
                  fill=c + (255,))
    if circle:
        m = Image.new("L", (S, S), 0)
        ImageDraw.Draw(m).ellipse([0, 0, S, S], fill=255)
    else:
        m = Image.new("L", (S, S), 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, S, S], radius=int(S * radius), fill=255)
    bg.putalpha(m)
    return bg.resize((size, size), Image.LANCZOS)


def app_icon(size, circle=False):
    bg = rounded_bg(size, circle=circle)
    mark = emblem(size, scale=1.0, glow=False)
    bg.alpha_composite(mark, ((size - mark.width) // 2, (size - mark.height) // 2))
    return bg


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print("wrote", os.path.relpath(path, ROOT), img.size)


def main():
    os.makedirs(os.path.join(OUT, "frames"), exist_ok=True)

    # --- Android launcher icons -------------------------------------------------
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for d, s in densities.items():
        save(app_icon(s), f"{RES}/mipmap-{d}/ic_launcher.png")
        save(app_icon(s, circle=True), f"{RES}/mipmap-{d}/ic_launcher_round.png")

    # adaptive foreground (108dp canvas, mark inside the 66dp safe zone)
    fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    mark = emblem(432, scale=0.95, glow=False)
    fg.alpha_composite(mark, (0, 0))
    save(fg, f"{RES}/drawable-nodpi/ic_launcher_foreground.png")

    mono = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    mono.alpha_composite(emblem(432, scale=0.95, mono=True, glow=False), (0, 0))
    save(mono, f"{RES}/drawable-nodpi/ic_launcher_monochrome.png")

    save(emblem(512, scale=1.12), f"{RES}/drawable-nodpi/ic_launcher_logo.png")
    save(emblem(512, scale=1.05, mono=True, glow=False), f"{RES}/drawable-nodpi/ic_wavetune_logo_mono.png")
    save(emblem(288, scale=1.0, glow=False), f"{RES}/drawable-nodpi/ic_widget_logo_mark.png")

    # --- Desktop / repo marks ---------------------------------------------------
    big = Image.new("RGBA", (1254, 1254), (0, 0, 0, 0))
    big.alpha_composite(emblem(1254, scale=1.12), (0, 0))
    save(big, os.path.join(ROOT, "wavetune_logo.png"))
    # The desktop module loads this file by name at runtime, so keep its path.
    save(big, os.path.join(ROOT, "desktop/src/main/resources/wavetune_logo.png"))
    save(emblem(1024, scale=1.12), os.path.join(OUT, "wavetune-mark.png"))

    # --- Splash video ------------------------------------------------------------
    W, H, FPS = 1280, 720, 30
    frames = os.path.join(OUT, "frames")
    for f in os.listdir(frames):
        os.remove(os.path.join(frames, f))

    total = int(2.6 * FPS)
    for i in range(total):
        t = i / FPS
        img = Image.new("RGBA", (W, H), (0, 0, 0, 255))
        # soft teal bloom
        bloom = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        bd = ImageDraw.Draw(bloom)
        bd.ellipse([W / 2 - 260, H / 2 - 260, W / 2 + 260, H / 2 + 260], fill=TEAL + (26,))
        bloom = bloom.filter(ImageFilter.GaussianBlur(90))
        img.alpha_composite(bloom)

        # progressive draw 0.0 -> 0.9s, then pulse
        if t < 0.9:
            p = 1 - (1 - min(1, t / 0.9)) ** 3
        else:
            p = 1.0
        pulse = 1.0
        if 0.9 <= t < 1.35:
            pulse = 1.0 + 0.035 * math.sin((t - 0.9) / 0.45 * math.pi)
        elif t >= 2.2:
            pulse = 1.0 + 0.02 * math.sin((t - 2.2) / 0.4 * math.pi)

        size = int(340 * pulse)
        mark = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        if p >= 1.0:
            mark = emblem(size, scale=1.12)
        else:
            # draw a partial stroke
            S = size * 4
            pts = poly_points(S, 1.12)
            segs = []
            total_len = 0.0
            for a, b in zip(pts, pts[1:]):
                l = math.dist(a, b)
                segs.append((a, b, l))
                total_len += l
            target = total_len * p
            mmask = Image.new("L", (S, S), 0)
            md = ImageDraw.Draw(mmask)
            width = int(0.105 * S * 0.92)
            acc = 0.0
            tip = pts[0]
            for a, b, l in segs:
                if acc + l <= target:
                    md.line([a, b], fill=255, width=width)
                    acc += l
                    tip = b
                else:
                    k = max(0.0, (target - acc) / l)
                    end = (a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k)
                    md.line([a, end], fill=255, width=width)
                    tip = end
                    break
            r = width / 2
            md.ellipse([pts[0][0] - r, pts[0][1] - r, pts[0][0] + r, pts[0][1] + r], fill=255)
            mmask = mmask.resize((size, size), Image.LANCZOS)
            grad = Image.new("RGBA", (size, size))
            gd = ImageDraw.Draw(grad)
            for x in range(size):
                gd.line([(x, 0), (x, size)], fill=grad_color(x / max(1, size - 1)) + (255,))
            mark = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            mark.paste(grad, (0, 0), mmask)
            glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            glow.paste(Image.new("RGBA", (size, size), TEAL + (255,)), (0, 0), mmask)
            glow = glow.filter(ImageFilter.GaussianBlur(size * 0.04))
            glow.putalpha(glow.getchannel("A").point(lambda v: int(v * 0.5)))
            mark.alpha_composite(glow)
            # tip spark
            sp = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            sd = ImageDraw.Draw(sp)
            tx, ty = tip[0] / 4, tip[1] / 4
            sd.ellipse([tx - 14, ty - 14, tx + 14, ty + 14], fill=(255, 255, 255, 150))
            sp = sp.filter(ImageFilter.GaussianBlur(8))
            mark.alpha_composite(sp)

        img.alpha_composite(mark, ((W - size) // 2, (H - size) // 2))
        img.convert("RGB").save(os.path.join(frames, f"frame_{i:03d}.png"))

    mp4 = os.path.join(OUT, "startup-logo.mp4")
    subprocess.run([
        "ffmpeg", "-y", "-loglevel", "error", "-framerate", str(FPS),
        "-i", os.path.join(frames, "frame_%03d.png"),
        "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p",
        "-crf", "18", "-movflags", "+faststart", "-an", mp4,
    ], check=True)
    shutil.copyfile(mp4, os.path.join(ROOT, "app/src/main/assets/startup-logo.mp4"))
    shutil.copyfile(mp4, os.path.join(RES, "raw/startup_logo.mp4"))
    print("wrote", mp4, os.path.getsize(mp4), "bytes")

    # --- SVG ---------------------------------------------------------------------
    path = "M " + " L ".join(f"{x*100:.1f},{y*100:.1f}" for x, y in PTS)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="512" height="512">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#C6F135"/>
      <stop offset="1" stop-color="#19C2A6"/>
    </linearGradient>
  </defs>
  <path d="{path}" fill="none" stroke="url(#g)" stroke-width="8"
        stroke-linecap="round" stroke-linejoin="round"/>
</svg>
'''
    with open(os.path.join(OUT, "wavetune-logo.svg"), "w") as fh:
        fh.write(svg)
    print("wrote branding/wavetune-logo.svg")

    shutil.rmtree(frames, ignore_errors=True)


if __name__ == "__main__":
    main()
