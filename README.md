# video-wall

Fullscreen media wall: opens a borderless fullscreen window on every screen and tiles
all images and videos found under a folder you choose (recursively) across them.
Videos loop continuously, without sound.

Requires JDK 21+ and `ffmpeg`/`ffprobe` on the PATH (for video). No other dependencies.

```sh
./run.sh                 # builds video-wall.jar on first run / when sources change
./run.sh --help          # options: --shuffle, --min-size N, --hide-cursor S, --windowed, --list, [folder]
./run.sh ~/some/folder   # show (or add) the configuration for that folder
./run.sh --list          # list the saved configurations of every screen setup
./run.sh --list ~/some/folder  # list the images and videos that would be shown
```

The mouse cursor (and the hover outline) disappears after 3 seconds without mouse
activity and comes back when you move the mouse; `--hide-cursor 10` changes the delay,
`--hide-cursor 0` turns it off.

## Controls

| Action | Effect |
| --- | --- |
| drag item | move it, including onto another screen; edges snap to screen edges and to nearby items |
| drag edge / corner | resize, keeping the aspect ratio (opposite edge stays put) |
| Shift + drag edge | resize freely (content is cropped to fill) |
| Ctrl + drag | move/resize without snapping |
| mouse wheel | zoom the item around the cursor |
| double-click | fit item to its screen / restore |
| right-click | rotate 90° clockwise (Shift + right-click: anticlockwise) |
| H | hide the item under the mouse pointer (hidden videos stop decoding) |
| A | list hidden items; double-click / Enter / "Show selected" puts them back where they were |
| R | reset layout: re-tile the visible items (rotation and hidden items are kept) |
| Space / Shift + Space | next / previous configuration |
| + | new configuration: pick its folder in the file chooser; it starts with the default layout |
| - | remove the configuration on screen (the last one can't be removed) |
| ? or / (or F1) | show / hide the shortcut list |
| Esc / Q | quit (Esc closes the hidden-items list first, if open) |

## Configurations and saving

A configuration is a media folder plus a complete arrangement of its images and videos:
positions, sizes, rotation, stacking order and which items are hidden. You can keep several
and flip between them with Space; a short message at the top of each screen says which one
is showing. + opens a file chooser for the new configuration's folder (Enter or "Use this
folder"; Esc cancels) and shows its images and videos in the default layout (everything
visible, unrotated, tiled). Several configurations can use the same folder. - deletes the
one on screen (permanently).

**Each screen setup has its own configurations.** A screen setup is the set of connected
monitors together with their resolutions; plugging in another monitor, unplugging one or
changing a resolution makes a different setup. A setup seen for the first time starts with a
single configuration: a folder picker opens (Esc quits), and the chosen folder is shown in the
default layout. A folder given on the command line is used instead of asking.
Monitors are recognised by the maker, model and serial number they report
(EDID, read from `/sys/class/drm`), so it doesn't matter which port they are plugged into;
monitors that report no serial number are told apart by port. Where the monitors sit
relative to each other is not part of the setup: positions are saved relative to each
monitor, so items stay on their monitor if you rearrange them.

Everything is saved automatically (shortly after each change, and on quit) to
`~/.config/video-wall/screens-<hash>.tsv`, one file per screen setup; the file starts
with the monitors it belongs to. Files added to a folder since are tiled on top in the
middle of the largest screen; removed files are dropped. If a configuration's folder is
missing (e.g. an unplugged drive), its saved arrangement is kept until the folder is back.
Pressing R first copies the current file to `*.tsv.before-reset`; copy it back over the
`.tsv` (with video-wall closed) to undo a reset. To start a setup from scratch, delete its
`.tsv` file. Layouts saved by earlier versions (`<folder>-<hash>.tsv`) are imported once,
into the first setup the new version sees, and left in place. (Earlier versions used
`~/.config/wall-viewer` or `~/.config/fine-art-viewer`; it is moved to `video-wall` automatically.)

## What counts as a thumbnail (skipped)

- files in hidden folders or folders named `thumbs`, `thumbnails`, `tn`, `previews`, `@eaDir`
- names containing `thumb`, or with a `tn_` / `_tn` / `th_` / `_th` prefix or suffix
- an image with the same name as a video in the same folder (poster frame)
- images whose longest side is under 256 px (`--min-size` changes this)

## License

Copyright (C) 2026 Dan Boresjö

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
the GNU General Public License for more details. The full text is in [LICENSE](LICENSE).
