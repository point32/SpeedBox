# Third-party licenses

SpeedBox itself is MIT licensed (see `LICENSE`). This file lists the
third-party components used by, or ported into, SpeedBox. Full texts are in
[`app/src/main/assets/licenses/`](app/src/main/assets/licenses/).

| Component | Use | License | Copyright |
|---|---|---|---|
| osu!lazer | Java ports of UI/layout/easing/menu/scroll behaviour | MIT | © 2025 ppy Pty Ltd |
| osu!framework | Java ports of easing/scroll containers | MIT | © 2024 ppy Pty Ltd |
| OSU-Toolbox | chart time-scaling semantics | MIT | © 2026 Difficile |
| SoundTouch | native time-stretch (`jniLibs/*/libsoundtouch.so`) | LGPL-2.1 | © Olli Parviainen |
| jump3r | pure-Java LAME MP3 encoder | LGPL-2.1+ | © LAME project / Ken Händel / Vadym Vikulin / Hanns Holger Rutz |
| JOrbis / biniu | pure-Java Ogg Vorbis codec | LGPL-2.0+ | © 2000 ymnk, JCraft, Inc. |
| Xiph.Org libvorbis | Vorbis algorithm | BSD-3-Clause | © 2002-2020 Xiph.Org Foundation |
| AndroidX Core | Android support | Apache-2.0 | © The Android Open Source Project |
| Material Components | Android widgets | Apache-2.0 | © Google LLC |

## Notes

- **No osu! assets are used.** The UI is drawn entirely with Android Canvas;
  there are no osu! images, fonts, sounds or skin files in `res/` or `assets/`.
- **MIT components** (osu!lazer, osu!framework, OSU-Toolbox): the MIT notice
  and copyright are retained in `LICENSE` and in the in-app license screen.
- **LGPL components** (SoundTouch, jump3r, JOrbis): they are used as separate
  libraries (`.so`/jar) or their source is included in this repository.
  Source locations:
  - SoundTouch: https://www.surina.net/soundtouch/
  - jump3r: https://git.iem.at/sciss/jump3r
  - JOrbis: `app/src/main/java/biniu/` (source included)
- **Apache-2.0 components**: a copy of the license is included in the app
  assets.
- If you redistribute a modified version of an LGPL library, you must comply
  with the LGPL, including providing the modified source.
