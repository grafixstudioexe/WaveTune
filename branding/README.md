# WaveTune brand assets

Everything in this folder is generated, so the mark can be regenerated at any
size without a design tool.

- `make_assets.py` — draws the WaveTune "W" waveform emblem and writes:
  - `app/src/main/res/mipmap-*/ic_launcher{,_round}.png` (48–192 px legacy icons)
  - `app/src/main/res/drawable-nodpi/ic_launcher_foreground.png` / `ic_launcher_monochrome.png` (adaptive icon layers, 432 px)
  - `app/src/main/res/drawable-nodpi/ic_launcher_logo.png` (512 px in-app mark)
  - `app/src/main/res/drawable-nodpi/ic_widget_logo_mark.png`
  - `wavetune_logo.png` (repo readme mark) and `desktop/src/main/resources/wavetune_logo.png` (desktop window/tray mark, 1254 px)
  - `startup-logo.mp4` plus the two copies Android plays (`app/src/main/assets/`, `app/src/main/res/raw/`)
  - `wavetune-logo.svg` (flat vector, no baked background)

## Palette

| Role | Colour |
| --- | --- |
| Lime (start of the gradient) | `#C6F135` |
| Mid | `#48DC96` |
| Teal (end of the gradient) | `#19C2A6` |
| Icon background | `#0C1215` |

The splash screen colour (`splash_background`) and the adaptive icon
background use the same `#0C1215` so the launch never flashes a different
shade.

## Regenerating

```bash
python3 branding/make_assets.py
```

Requires Python 3 with Pillow and `ffmpeg` on `PATH`. The script is
deterministic: the same input produces the same PNGs and MP4.

The splash clip is 1280×720, H.264, no audio, and ends on the same emblem the
in-app lockup draws, so the hand-off from video to Compose has no visible jump.

## File names

Resource file names use the `wavetune` spelling
(`ic_wavetune_logo_mono.png`, `desktop/src/main/resources/wavetune_logo.png`)
because they are referenced from Kotlin and Gradle. They are internal
identifiers; every user-visible label reads **WaveTune**.
