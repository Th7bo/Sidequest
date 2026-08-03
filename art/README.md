# Art sources

`logo.png` is the full-resolution original, 1254×1254.

What ships is `src/main/resources/assets/sidequest/icon.png`, a 256×256 copy — the size mod
launchers actually draw, and a thirtieth of the bytes. Regenerate it after editing the original:

```
magick art/logo.png -resize 256x256 -strip -define png:compression-level=9 \
  src/main/resources/assets/sidequest/icon.png
```

The source is kept rather than discarded because the packaged copy cannot be scaled back up, and
kept out of `resources` because everything under there is nested into the jar every player installs.
