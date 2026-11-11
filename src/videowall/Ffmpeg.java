// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Thin wrappers around the ffmpeg / ffprobe command line tools. */
final class Ffmpeg {

    private static volatile Boolean available;

    private Ffmpeg() {}

    static boolean available() {
        Boolean a = available;
        if (a == null) {
            a = runs("ffmpeg") && runs("ffprobe");
            available = a;
        }
        return a;
    }

    private static boolean runs(String tool) {
        try {
            Process p = new ProcessBuilder(tool, "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Runs ffprobe and returns its {@code key=value} output (tag prefixes stripped). */
    static Map<String, String> probe(List<String> args) {
        Map<String, String> out = new HashMap<>();
        try {
            Process p = new ProcessBuilder(args)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String text;
            try (InputStream in = p.getInputStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return out;
            }
            for (String line : text.split("\\R")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq);
                if (key.startsWith("TAG:")) key = key.substring(4);
                out.putIfAbsent(key, line.substring(eq + 1).trim());
            }
        } catch (IOException e) {
            // treated as "not probeable"
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    static int intValue(Map<String, String> m, String key, int def) {
        try {
            return (int) Math.round(Double.parseDouble(m.getOrDefault(key, "")));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
