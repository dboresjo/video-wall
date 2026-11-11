// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;

public final class Main {

    private static final String USAGE = """
            usage: video-wall [options] [folder]

              folder            show the configuration with this media folder (added if
                                there is none); without it, the configuration shown last,
                                or, on a new screen setup, a folder picker
              --shuffle         randomise the initial order
              --min-size N      images whose longest side is below N px are treated
                                as thumbnails and skipped (default: 256, 0 disables)
              --windowed        use undecorated screen-sized windows instead of
                                exclusive fullscreen
              --hide-cursor S   hide the mouse cursor after S seconds without mouse
                                activity (default: 3, 0 never hides it)
              --list            list the saved configurations of every screen setup and
                                exit; with a folder, list the media found in it instead

            controls:
              drag              move (snaps to screen and item edges)
              drag edge/corner  resize, keeping aspect ratio
              Shift + drag      resize freely (content is cropped to fill)
              Ctrl + drag       move/resize without snapping
              mouse wheel       zoom item around the cursor
              double-click      toggle fit-to-screen
              right-click       rotate 90 degrees clockwise (Shift: anticlockwise)
              H                 hide the item under the mouse pointer
              A                 list hidden items, to put them back
              R                 reset layout (re-tile the visible items)
              Space             next configuration (Shift+Space: previous)
              +                 new configuration: pick its folder; starts with the
                                default layout
              -                 remove the configuration on screen
              ? / or F1         show / hide the shortcut list
              Esc / Q           quit

            Each screen setup (the connected monitors and their resolutions) has its own
            configurations (folder, positions, sizes, rotation, stacking, hidden items).
            They are saved automatically to ~/.config/video-wall/ and restored the
            next time the same monitors are connected.
            """;

    public static void main(String[] args) throws Exception {
        Path root = null;
        boolean shuffle = false, list = false, exclusive = true;
        int minSize = 256;
        double hideCursorSeconds = 3;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--shuffle" -> shuffle = true;
                case "--list" -> list = true;
                case "--windowed" -> exclusive = false;
                case "--hide-cursor" -> {
                    if (i + 1 >= args.length) die("--hide-cursor needs a number of seconds");
                    hideCursorSeconds = Double.parseDouble(args[++i]);
                }
                case "--min-size" -> {
                    if (i + 1 >= args.length) die("--min-size needs a value");
                    minSize = Integer.parseInt(args[++i]);
                }
                case "-h", "--help" -> {
                    System.out.print(USAGE);
                    return;
                }
                default -> {
                    if (a.startsWith("-")) die("unknown option " + a + "\n\n" + USAGE);
                    root = Path.of(a.startsWith("~/") ? System.getProperty("user.home") + a.substring(1) : a);
                }
            }
        }

        if (list && root == null) {
            LayoutStore.describeAll().forEach(System.out::println);
            return;
        }
        if (root != null && !Files.isDirectory(root)) die("not a directory: " + root);
        if (!Ffmpeg.available()) {
            System.err.println("warning: ffmpeg/ffprobe not found on PATH - videos will be skipped");
        }

        if (list) {
            List<MediaItem> items = MediaScanner.scan(root, minSize);
            for (MediaItem it : items) {
                System.out.printf("%-5s %5d x %-5d %s%n", it.kind(), it.naturalWidth, it.naturalHeight,
                        root.relativize(it.path));
            }
            System.out.println(items.size() + " items");
            return;
        }
        if (GraphicsEnvironment.isHeadless()) die("no display available");

        int cursorIdleMillis = (int) Math.max(0, Math.round(hideCursorSeconds * 1000));
        Wall wall = new Wall(root, minSize, shuffle, exclusive, cursorIdleMillis);
        String error = wall.prepare();
        if (error != null) die(error);
        SwingUtilities.invokeLater(wall::start);
    }

    private static void die(String msg) {
        System.err.println(msg);
        System.exit(1);
    }
}
