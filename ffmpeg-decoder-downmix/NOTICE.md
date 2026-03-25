This module is based on the AndroidX Media3 `decoder_ffmpeg` extension.

Additional downmix behavior in this module was adapted from Kodi (`xbmc/xbmc`),
including the FFmpeg/AudioEngine handling of:

- center mix defaults and metadata offsets
- explicit output channel layout selection for downmix
- downmix normalization behavior

Provenance summary:

- Base implementation: AndroidX Media3 FFmpeg decoder extension
- Downmix behavior source: Kodi (`xbmc/xbmc`), licensed `GPL-2.0-or-later`

This project is distributed under GPL-3.0, which is compatible with code
available under `GPL-2.0-or-later`.
