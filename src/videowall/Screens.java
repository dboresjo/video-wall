// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Works out which monitors are connected, so that each screen setup gets its own set of
 * configurations.
 *
 * <p>A monitor is identified by the maker, model and serial number from its EDID (read from
 * {@code /sys/class/drm}), so it is recognised whichever port it is plugged into. Java's screens
 * are matched to monitor ports by their position and size as reported by {@code xrandr}. Without
 * EDID the port name is used, and without {@code xrandr} Java's own screen name.
 *
 * <p>A setup is the set of (monitor, resolution) pairs; where the monitors are placed relative
 * to each other is not part of it. Item positions are saved relative to their monitor instead.
 */
final class Screens {

    /** One Java screen; mirrored monitors showing the same picture form a single screen. */
    record Monitor(String id, String label, Rectangle bounds) {
        String keyPart() {
            return id + "@" + bounds.width + "x" + bounds.height;
        }

        String describe() {
            return label + " " + bounds.width + "×" + bounds.height;
        }
    }

    /** The connected screens, sorted by {@link Monitor#keyPart()}; {@code key} identifies the setup. */
    record Setup(List<Monitor> monitors, String key, String label) {}

    private record Output(String port, Rectangle bounds) {}

    private record Identity(String id, String label, boolean unique) {}

    private static final Pattern XRANDR_OUTPUT = Pattern.compile(
            "^(\\S+) connected (?:primary )?(\\d+)x(\\d+)\\+(-?\\d+)\\+(-?\\d+)");
    private static final Path DRM = Path.of("/sys/class/drm");

    private Screens() {}

    static Setup detect() {
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        List<Output> outputs = xrandrOutputs();
        Map<String, Path> drm = drmConnectors();

        List<String[]> found = new ArrayList<>(); // {id, label, port}
        List<Monitor> monitors = new ArrayList<>();
        int n = 0;
        for (GraphicsDevice d : devices) {
            n++;
            Rectangle b = d.getDefaultConfiguration().getBounds();
            List<Identity> ids = new ArrayList<>();
            List<String> ports = new ArrayList<>();
            for (Output o : outputs) {
                if (o.bounds().equals(b)) {
                    ids.add(identify(o.port(), drm.get(normalizePort(o.port()))));
                    ports.add(o.port());
                }
            }
            String id, label;
            if (ids.isEmpty()) {
                id = "screen-" + sanitize(d.getIDstring());
                label = "Screen " + n;
            } else {
                List<String> parts = new ArrayList<>();
                for (int i = 0; i < ids.size(); i++) {
                    Identity it = ids.get(i);
                    parts.add(it.unique() ? it.id() : it.id() + "-" + sanitize(ports.get(i)));
                }
                id = parts.stream().sorted().collect(Collectors.joining("="));
                label = ids.stream().map(Identity::label).collect(Collectors.joining(" / "));
            }
            found.add(new String[] {id, label, String.join("=", ports)});
            monitors.add(new Monitor(id, label, new Rectangle(b)));
        }

        // Two identical monitors reporting the same serial number: tell them apart by port.
        Map<String, Integer> counts = new HashMap<>();
        for (Monitor m : monitors) counts.merge(m.id(), 1, Integer::sum);
        for (int i = 0; i < monitors.size(); i++) {
            Monitor m = monitors.get(i);
            if (counts.get(m.id()) > 1) {
                String port = found.get(i)[2].isEmpty() ? String.valueOf(i) : found.get(i)[2];
                monitors.set(i, new Monitor(m.id() + "-" + sanitize(port), m.label(), m.bounds()));
            }
        }

        monitors.sort(Comparator.comparing(Monitor::keyPart));
        String key = monitors.stream().map(Monitor::keyPart).collect(Collectors.joining("+"));
        String label = monitors.stream()
                .sorted(Comparator.comparingInt((Monitor m) -> m.bounds().x).thenComparingInt(m -> m.bounds().y))
                .map(Monitor::describe)
                .collect(Collectors.joining(" + "));
        return new Setup(List.copyOf(monitors), key, label);
    }

    /** Connected outputs with their desktop geometry, from {@code xrandr --query}. */
    private static List<Output> xrandrOutputs() {
        List<Output> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("xrandr", "--query")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = XRANDR_OUTPUT.matcher(line);
                    if (m.find()) {
                        out.add(new Output(m.group(1), new Rectangle(Integer.parseInt(m.group(4)),
                                Integer.parseInt(m.group(5)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)))));
                    }
                }
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (IOException e) {
            System.err.println("xrandr not available; screens are identified by Java's screen names");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    /** Connected DRM connectors ({@code card1-HDMI-A-1} etc.) by normalised port name. */
    private static Map<String, Path> drmConnectors() {
        Map<String, Path> map = new HashMap<>();
        if (!Files.isDirectory(DRM)) return map;
        try (Stream<Path> s = Files.list(DRM)) {
            for (Path dir : (Iterable<Path>) s::iterator) {
                String name = dir.getFileName().toString();
                int dash = name.indexOf('-');
                if (!name.startsWith("card") || dash < 0) continue;
                try {
                    if (!Files.readString(dir.resolve("status")).trim().equals("connected")) continue;
                } catch (IOException e) {
                    continue;
                }
                map.put(normalizePort(name.substring(dash + 1)), dir);
            }
        } catch (IOException e) {
            // no EDID; ports are used instead
        }
        return map;
    }

    /** {@code HDMI-A-1} (kernel), {@code HDMI-1} (Xorg modesetting) and {@code HDMI1} (Xorg intel) are the same port. */
    static String normalizePort(String port) {
        return port.toUpperCase().replaceFirst("^(HDMI|DVI)-[A-Z]-", "$1-").replace("-", "");
    }

    private static Identity identify(String port, Path connector) {
        byte[] edid = null;
        if (connector != null) {
            try (InputStream in = Files.newInputStream(connector.resolve("edid"))) {
                edid = in.readAllBytes(); // sysfs reports size 0, so read to the end
            } catch (IOException e) {
                edid = null;
            }
        }
        boolean builtIn = port.toUpperCase().matches("(EDP|LVDS|DSI).*");
        if (edid == null || edid.length < 128 || (edid[0] & 0xff) != 0 || (edid[1] & 0xff) != 0xff) {
            return new Identity("port-" + sanitize(port), builtIn ? "Built-in display" : port, true);
        }
        int m = ((edid[8] & 0xff) << 8) | (edid[9] & 0xff);
        String maker = new String(new char[] {
                (char) ('A' - 1 + ((m >> 10) & 31)), (char) ('A' - 1 + ((m >> 5) & 31)), (char) ('A' - 1 + (m & 31))});
        int product = (edid[10] & 0xff) | ((edid[11] & 0xff) << 8);
        long serial = (edid[12] & 0xffL) | ((edid[13] & 0xffL) << 8) | ((edid[14] & 0xffL) << 16) | ((edid[15] & 0xffL) << 24);
        String name = null, serialText = null;
        for (int d = 54; d + 18 <= 126; d += 18) {
            if (edid[d] != 0 || edid[d + 1] != 0 || edid[d + 2] != 0) continue; // a detailed timing, not text
            int type = edid[d + 3] & 0xff;
            if (type != 0xfc && type != 0xff) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = d + 5; i < d + 18 && edid[i] != 0x0a && edid[i] != 0; i++) sb.append((char) (edid[i] & 0x7f));
            String text = sb.toString().trim();
            if (text.isEmpty()) continue;
            if (type == 0xfc) name = text; else serialText = text;
        }

        String id = maker + "-" + String.format("%04X", product);
        boolean unique = true;
        if (serialText != null) {
            id += "-" + sanitize(serialText);
        } else if (serial != 0) {
            id += "-" + String.format("%08X", serial);
        } else {
            unique = false; // no serial number: the port tells two of the same model apart
        }
        String label = name != null ? name : builtIn ? "Built-in display" : maker + " " + String.format("%04X", product);
        return new Identity(id, label, unique);
    }

    /** Keeps ids free of the separators used in setup keys and the layout file. */
    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
