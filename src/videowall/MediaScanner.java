// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Finds displayable images and videos below a folder, skipping thumbnails. */
final class MediaScanner {

    static final Set<String> IMAGE_EXT = Set.of(
            "jpg", "jpeg", "jpe", "jfif", "png", "gif", "bmp", "tif", "tiff", "webp");
    static final Set<String> VIDEO_EXT = Set.of(
            "mp4", "m4v", "mov", "avi", "3gp", "3g2", "mkv", "webm", "wmv", "asf", "flv",
            "mpg", "mpeg", "mpe", "m2v", "vob", "ts", "mts", "m2ts", "ogv", "vcd", "dat", "divx");

    /** Directories that only hold thumbnails / previews. */
    private static final Pattern THUMB_DIR = Pattern.compile(
            "(?i)thumbs?|thumbnails?|tn|@eaDir|previews?");
    /** File names (without extension) that mark a thumbnail. */
    private static final Pattern THUMB_NAME = Pattern.compile(
            "(?i).*thumb.*|tn[_-].*|.*[_-]tn|th[_-].*|.*[_-]th");

    private MediaScanner() {}

    static List<MediaItem> scan(Path root, int minSize) throws IOException {
        List<Path> images = new ArrayList<>();
        List<Path> videos = new ArrayList<>();
        Map<Path, Set<String>> videoStems = new HashMap<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                String name = dir.getFileName().toString();
                if (name.startsWith(".") || THUMB_DIR.matcher(name).matches()) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.startsWith(".") || !attrs.isRegularFile() || attrs.size() == 0) {
                    return FileVisitResult.CONTINUE;
                }
                String ext = extension(name);
                if (IMAGE_EXT.contains(ext)) {
                    images.add(file);
                } else if (VIDEO_EXT.contains(ext)) {
                    videos.add(file);
                    videoStems.computeIfAbsent(file.getParent(), k -> new HashSet<>())
                            .add(stem(name).toLowerCase(Locale.ROOT));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        List<Path> candidates = new ArrayList<>();
        for (Path p : images) {
            String stem = stem(p.getFileName().toString());
            if (THUMB_NAME.matcher(stem).matches()) continue;
            // "clip.jpg" next to "clip.mp4" is a poster frame, not artwork.
            if (videoStems.getOrDefault(p.getParent(), Set.of()).contains(stem.toLowerCase(Locale.ROOT))) {
                continue;
            }
            candidates.add(p);
        }
        boolean haveFfmpeg = Ffmpeg.available();
        for (Path p : videos) {
            if (!haveFfmpeg) break;
            if (THUMB_NAME.matcher(stem(p.getFileName().toString())).matches()) continue;
            candidates.add(p);
        }
        candidates.sort(Comparator.comparing((Path p) -> root.relativize(p).toString(), MediaScanner::natural));

        // Probe in parallel; parallel streams preserve encounter order.
        return candidates.parallelStream()
                .map(p -> {
                    String ext = extension(p.getFileName().toString());
                    MediaItem item = IMAGE_EXT.contains(ext) ? ImageItem.probe(p) : VideoItem.probe(p);
                    if (item == null) {
                        System.err.println("skipping (unreadable): " + p);
                        return null;
                    }
                    if (item instanceof ImageItem
                            && Math.max(item.naturalWidth, item.naturalHeight) < minSize) {
                        return null; // too small: a thumbnail
                    }
                    return item;
                })
                .filter(Objects::nonNull)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** Case-insensitive comparison that orders embedded numbers numerically. */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                int c = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
