// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.Rectangle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Persists the configurations of one screen setup (see {@link Screens}) in
 * {@code $XDG_CONFIG_HOME/video-wall/screens-<hash of the setup>.tsv}. Each configuration
 * has its own media folder; its items are keyed by their path relative to that folder.
 *
 * <pre>
 * # video-wall layout v3
 * setup    key                          identifies the screen setup
 * monitor  index  id  WxH  label        the setup's screens (for reference)
 * current  1                            index of the configuration shown last
 * config   0  /abs/media/folder         starts a configuration; entry lines follow
 * monitor  x  y  width  height  quarter-turns  hidden  z  path
 * </pre>
 * Entry positions are relative to the top-left corner of the given monitor, so rearranging
 * the monitors keeps every item on the monitor it was on.
 */
final class LayoutStore {

    /** Bounds are in global desktop coordinates. */
    record Entry(Rectangle bounds, int turns, boolean hidden, int z) {}

    /** One configuration: a media folder and the state of its items (none yet = default layout). */
    static final class Config {
        final Path root;
        final Map<String, Entry> entries = new LinkedHashMap<>();

        Config(Path root) {
            this.root = normalize(root);
        }

        boolean isDefault() {
            return entries.isEmpty();
        }
    }

    /** {@code imported}: the old per-folder layout files read instead of a setup file (usually none). */
    record Saved(List<Config> configs, int current, List<Path> imported) {}

    private static final String HEADER = "# video-wall layout v3";

    private final Screens.Setup setup;
    private final Path dir;
    final Path file;

    LayoutStore(Screens.Setup setup) {
        this.setup = setup;
        dir = configDir();
        file = dir.resolve("screens-" + hash(setup.key()) + ".tsv");
    }

    /** {@code video-wall} in the config folder; a folder of earlier versions is moved there once. */
    private static Path configDir() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path config = xdg != null && !xdg.isBlank()
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        Path dir = config.resolve("video-wall");
        for (String earlier : new String[] {"wall-viewer", "fine-art-viewer"}) {
            Path old = config.resolve(earlier);
            if (Files.exists(dir) || !Files.isDirectory(old)) continue;
            try {
                Files.move(old, dir);
                System.err.println("moved " + old + " to " + dir);
            } catch (IOException e) {
                System.err.println("cannot move " + old + " to " + dir + ": " + e);
                return old;
            }
        }
        return dir;
    }

    static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
    }

    static String key(Path root, MediaItem it) {
        return root.relativize(normalize(it.path)).toString();
    }

    /**
     * The saved configurations of this screen setup; none if it is new. The very first time
     * (no setup file exists at all yet), the old per-folder layout files are imported instead,
     * so upgrading keeps the existing layouts.
     */
    Saved load() {
        if (Files.isRegularFile(file)) return read();
        if (!setupFiles().isEmpty()) return new Saved(List.of(), 0, List.of());
        List<Config> configs = new ArrayList<>();
        List<Path> imported = new ArrayList<>();
        int current = 0;
        for (Path legacy : legacyFiles()) { // most recently saved first; its current one is shown
            Path root = legacyRoot(legacy);
            if (root == null) continue;
            List<Config> read = new ArrayList<>();
            int c = readLegacy(legacy, root, read);
            if (read.isEmpty()) continue;
            if (configs.isEmpty()) current = c;
            configs.addAll(read);
            imported.add(legacy);
        }
        return new Saved(configs, current, imported);
    }

    /** For {@code --list}: every screen setup's configurations, with their folders and item counts. */
    static List<String> describeAll() {
        Path dir = configDir();
        List<String> out = new ArrayList<>();
        List<Path> files = setupFiles(dir);
        if (files.isEmpty()) {
            out.add("no saved configurations in " + dir);
            return out;
        }
        String home = System.getProperty("user.home");
        for (Path f : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                out.add(f + ": " + e);
                continue;
            }
            String label = "?";
            int current = 0;
            List<String[]> configs = new ArrayList<>(); // {root, shown, hidden}
            for (String line : lines) {
                if (line.startsWith("# screens\t")) {
                    label = line.substring(10);
                } else if (line.startsWith("current\t")) {
                    try {
                        current = Integer.parseInt(line.substring(8).trim());
                    } catch (NumberFormatException ignored) {
                        // keep 0
                    }
                } else if (line.startsWith("config\t")) {
                    String[] c = line.split("\t", 3);
                    configs.add(new String[] {c.length == 3 ? c[2] : "?", "0", "0"});
                } else if (!configs.isEmpty() && !line.isEmpty() && Character.isDigit(line.charAt(0))) {
                    String[] e = line.split("\t", 9);
                    String[] c = configs.get(configs.size() - 1);
                    int i = e.length > 6 && e[6].equals("1") ? 2 : 1;
                    c[i] = String.valueOf(Integer.parseInt(c[i]) + 1);
                }
            }
            if (!out.isEmpty()) out.add("");
            out.add("screens: " + label + "   (" + f.getFileName() + ")");
            for (int i = 0; i < configs.size(); i++) {
                String[] c = configs.get(i);
                String root = c[0].startsWith(home + "/") ? "~" + c[0].substring(home.length()) : c[0];
                String counts = c[1].equals("0") && c[2].equals("0")
                        ? "default layout"
                        : c[1] + " shown" + (c[2].equals("0") ? "" : ", " + c[2] + " hidden");
                out.add(String.format("  %s %2d  %s   (%s)", i == current ? "*" : " ", i + 1, root, counts));
            }
        }
        out.add("");
        out.add("* = shown last on that screen setup");
        return out;
    }

    private Saved read() {
        List<Config> configs = new ArrayList<>();
        int current = 0;
        List<Screens.Monitor> monitors = setup.monitors();
        try {
            Config cfg = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                try {
                    if (line.startsWith("setup\t")) {
                        if (!line.substring(6).equals(setup.key())) { // hash collision: not ours
                            System.err.println("ignoring " + file + ": it belongs to another screen setup");
                            return new Saved(List.of(), 0, List.of());
                        }
                        continue;
                    }
                    if (line.startsWith("monitor\t")) continue;
                    if (line.startsWith("current\t")) {
                        current = Integer.parseInt(line.substring(8).trim());
                        continue;
                    }
                    if (line.startsWith("config\t")) {
                        String[] f = line.split("\t", 3);
                        cfg = f.length == 3 && !f[2].isBlank() ? new Config(Path.of(f[2])) : null;
                        if (cfg != null) configs.add(cfg);
                        continue;
                    }
                    String[] f = line.split("\t", 9);
                    if (cfg == null || f.length < 9) continue;
                    int m = Integer.parseInt(f[0]);
                    if (m < 0 || m >= monitors.size()) continue;
                    Rectangle origin = monitors.get(m).bounds();
                    Rectangle r = new Rectangle(origin.x + Integer.parseInt(f[1]), origin.y + Integer.parseInt(f[2]),
                            Integer.parseInt(f[3]), Integer.parseInt(f[4]));
                    if (r.width <= 0 || r.height <= 0) continue;
                    cfg.entries.put(f[8], new Entry(r, Integer.parseInt(f[5]) & 3, "1".equals(f[6]),
                            Integer.parseInt(f[7])));
                } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                    // skip malformed line
                }
            }
        } catch (IOException e) {
            System.err.println("cannot read layout " + file + ": " + e);
        }
        return new Saved(configs, Math.max(0, Math.min(current, configs.size() - 1)), List.of());
    }

    /** Writes all configurations atomically. */
    void save(List<Config> configs, int current) throws IOException {
        List<Screens.Monitor> monitors = setup.monitors();
        List<String> lines = new ArrayList<>();
        lines.add(HEADER);
        lines.add("# screens\t" + setup.label());
        lines.add("setup\t" + setup.key());
        for (int i = 0; i < monitors.size(); i++) {
            Screens.Monitor m = monitors.get(i);
            lines.add("monitor\t" + i + "\t" + m.id() + "\t" + m.bounds().width + "x" + m.bounds().height
                    + "\t" + m.label());
        }
        lines.add("# monitor\tx\ty\twidth\theight\tquarter-turns\thidden\tz\tpath   (x, y relative to the monitor)");
        lines.add("current\t" + current);
        for (int i = 0; i < configs.size(); i++) {
            Config cfg = configs.get(i);
            lines.add("config\t" + i + "\t" + cfg.root);
            List<Map.Entry<String, Entry>> rows = new ArrayList<>(cfg.entries.entrySet());
            // visible items in stacking order, then hidden ones
            rows.sort(Comparator.comparing((Map.Entry<String, Entry> e) -> e.getValue().hidden())
                    .thenComparingInt(e -> e.getValue().z()));
            for (Map.Entry<String, Entry> row : rows) {
                Entry e = row.getValue();
                Rectangle r = e.bounds();
                int m = monitorFor(r);
                Rectangle origin = monitors.get(m).bounds();
                lines.add(m + "\t" + (r.x - origin.x) + "\t" + (r.y - origin.y) + "\t" + r.width + "\t" + r.height
                        + "\t" + e.turns() + "\t" + (e.hidden() ? 1 : 0) + "\t" + e.z() + "\t" + row.getKey());
            }
        }
        Files.createDirectories(dir);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The monitor showing most of {@code r}, or the nearest one if it is off screen. */
    private int monitorFor(Rectangle r) {
        List<Screens.Monitor> monitors = setup.monitors();
        int best = 0;
        long bestArea = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < monitors.size(); i++) {
            Rectangle s = monitors.get(i).bounds();
            Rectangle x = s.intersection(r);
            long area = x.isEmpty() ? 0 : (long) x.width * x.height;
            double dist = Math.hypot(s.getCenterX() - r.getCenterX(), s.getCenterY() - r.getCenterY());
            if (area > bestArea || (area == 0 && bestArea == 0 && dist < bestDist)) {
                best = i;
                bestArea = area;
                bestDist = dist;
            }
        }
        return best;
    }

    private List<Path> setupFiles() {
        return setupFiles(dir);
    }

    private static List<Path> setupFiles(Path dir) {
        return tsvFiles(dir, true);
    }

    /** The {@code .tsv} files in {@code dir} that are (or are not) setup files, most recently saved first. */
    private static List<Path> tsvFiles(Path dir, boolean setups) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".tsv") && n.startsWith("screens-") == setups && Files.isRegularFile(p);
                    })
                    .sorted(Comparator.comparing((Path p) -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ---------------------------------------------------------------- the pre-v3 per-folder files

    private List<Path> legacyFiles() {
        return tsvFiles(dir, false);
    }

    /** The media folder recorded in an old file's {@code # folder} header line, or null. */
    private static Path legacyRoot(Path legacy) {
        try (Stream<String> lines = Files.lines(legacy, StandardCharsets.UTF_8)) {
            return lines.limit(5)
                    .filter(l -> l.startsWith("# folder\t"))
                    .map(l -> normalize(Path.of(l.substring(9))))
                    .findFirst().orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Reads a v1/v2 file (global coordinates, one folder) into {@code out}; returns its current index. */
    private static int readLegacy(Path legacy, Path root, List<Config> out) {
        if (!Files.isRegularFile(legacy)) return 0;
        int current = 0;
        try {
            Config cfg = null;
            for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t", 8);
                try {
                    if (f[0].equals("current") && f.length >= 2) {
                        current = Integer.parseInt(f[1].trim());
                        continue;
                    }
                    if (f[0].equals("config")) {
                        cfg = new Config(root);
                        out.add(cfg);
                        continue;
                    }
                    if (f.length < 8) continue;
                    Rectangle r = new Rectangle(Integer.parseInt(f[0]), Integer.parseInt(f[1]),
                            Integer.parseInt(f[2]), Integer.parseInt(f[3]));
                    if (r.width <= 0 || r.height <= 0) continue;
                    if (cfg == null) { // v1: a single configuration without a config line
                        cfg = new Config(root);
                        out.add(cfg);
                    }
                    cfg.entries.put(f[7], new Entry(r, Integer.parseInt(f[4]) & 3, "1".equals(f[5]),
                            Integer.parseInt(f[6])));
                } catch (NumberFormatException ignored) {
                    // skip malformed line
                }
            }
        } catch (IOException e) {
            System.err.println("cannot read layout " + legacy + ": " + e);
        }
        out.removeIf(Config::isDefault);
        return Math.max(0, Math.min(current, out.size() - 1));
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
