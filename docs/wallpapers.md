# Wallpapers

NemuiTV plays looping video wallpapers behind the home screen. Until you add some, it shows a built-in dark gradient and a small hint on Home. The hint goes away when you dismiss it or add a wallpaper.

We ship no wallpapers. You bring your own.

## The format

The TV plays these smoothly. Others may stutter or show wrong colors.

| Setting | Value |
|---|---|
| Container | `.mp4` |
| Video codec | H.264 |
| Size | 1920x1080 or smaller |
| Frame rate | 30 fps or less |
| Color tags | BT.709 (primaries, transfer, matrix all set) |
| Bitrate | about 8 to 12 Mbps |
| Length | 20 seconds to 2 minutes, one that loops well |
| Audio | none needed |

The color tags matter. Without them, the TV may guess the wrong color space and the picture looks washed out or too dark.

### Chromecast with Google TV

It is slower than the Google TV Streamer, so use smaller files:

- 4K model: 1080p at 6 Mbps or less.
- HD model: 720p.

## Where to find videos

Free stock-video sites:

- Pexels: https://www.pexels.com (license: https://www.pexels.com/license/)
- Pixabay: https://pixabay.com (license: https://pixabay.com/service/license-summary/)

Read the license on any clip you use. Slow nature scenes, clouds, water and abstract loops work best.

## Convert with fix-wallpapers

`fix-wallpapers` turns almost any video into the format above. It needs `ffmpeg`. It never installs anything; if ffmpeg is missing, it tells you where to get it (Mac: `brew install ffmpeg`; Windows and others: https://ffmpeg.org/download.html).

1. In the unzipped setup folder, make a folder called `wallpapers` and drop your videos in it (`.mp4`, `.mov`, `.m4v`, `.webm` or `.mkv`).
2. Run the converter.

   Mac or Linux:

   ```
   bash scripts/fix-wallpapers.sh
   ```

   Windows (PowerShell):

   ```
   powershell -ExecutionPolicy Bypass -File scripts\fix-wallpapers.ps1
   ```

   What it does: re-encodes each video to H.264, at most 1080p, 30 fps, about 10 Mbps, BT.709 color tags, no audio. It moves your originals to `wallpapers/originals/`.

3. For a Chromecast with Google TV, lower the size and bitrate yourself (see above). The script's output is made for the Streamer.

## Send them to the TV

Your TV needs to be connected with `adb` (the setup script did that; if the connection dropped, turn on Wireless debugging and run `adb connect <ip-address:port>`).

Mac or Linux:

```
bash scripts/push-wallpapers.sh
```

Windows:

```
powershell -ExecutionPolicy Bypass -File scripts\push-wallpapers.ps1
```

What it does: copies new or changed `.mp4` files from `wallpapers/` to the TV and deletes the ones you removed from your folder. So your folder is the source of truth. If you have more than one device connected, add `--serial <address>` (Windows: `-Serial <address>`).

If `ffprobe` (part of ffmpeg) is installed, the script checks each file first. It warns about files that may stutter, but it still sends them.

## Order and next wallpaper

- `wallpaper.order` in `config.json` is `shuffle` (default) or `sequential`. See [configuration.md](configuration.md).
- Hold Home, then pick "Next wallpaper" in the Control Center to skip ahead.
- The Setup page shows how many wallpapers the TV found.

## Troubleshooting

- **Stutters:** run `fix-wallpapers`, then `push-wallpapers` again.
- **Colors look washed out:** the color tags are missing. Run `fix-wallpapers`.
- **Nothing shows up:** go back Home after pushing. If the Setup page says "Using the built-in one", the TV found no `.mp4` files in its wallpapers folder.
