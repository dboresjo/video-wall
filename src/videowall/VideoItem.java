// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A continuously looping, muted video. Frames are decoded by an ffmpeg child process
 * that writes raw BGR pixels at real-time pace to a pipe.
 */
final class VideoItem extends MediaItem {

    /** Longest side frames are decoded at; the item scales them to its on-screen size. */
    private static final int MAX_DECODE = 1280;

    private final int frameW, frameH;
    private final Object lock = new Object();
    private BufferedImage frame;   // guarded by lock
    private byte[] framePixels;    // guarded by lock
    private boolean hasFrame;      // guarded by lock
    private volatile boolean failed;
    private volatile Thread decoder;
    private volatile Process process;
    private Wall wall;

    private VideoItem(Path path, int w, int h) {
        super(path, w, h);
        double f = Math.min(1.0, MAX_DECODE / (double) Math.max(w, h));
        frameW = Math.max(2, (int) Math.round(w * f) & ~1);
        frameH = Math.max(2, (int) Math.round(h * f) & ~1);
    }

    @Override
    String kind() {
        return "video";
    }

    /** Returns null when ffprobe finds no decodable video stream. */
    static VideoItem probe(Path path) {
        Map<String, String> m = Ffmpeg.probe(List.of("ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height,sample_aspect_ratio:stream_side_data=rotation:stream_tags=rotate",
                "-of", "default=noprint_wrappers=1", path.toString()));
        int w = Ffmpeg.intValue(m, "width", -1);
        int h = Ffmpeg.intValue(m, "height", -1);
        if (w <= 0 || h <= 0) return null;

        String sar = m.getOrDefault("sample_aspect_ratio", "1:1");
        int colon = sar.indexOf(':');
        if (colon > 0) {
            try {
                double num = Double.parseDouble(sar.substring(0, colon));
                double den = Double.parseDouble(sar.substring(colon + 1));
                if (num > 0 && den > 0) w = (int) Math.round(w * num / den);
            } catch (NumberFormatException ignored) {
                // keep square pixels
            }
        }
        int rotation = Ffmpeg.intValue(m, "rotation", Ffmpeg.intValue(m, "rotate", 0));
        if (Math.abs(rotation) % 180 == 90) {
            int t = w;
            w = h;
            h = t;
        }
        return new VideoItem(path, w, h);
    }

    @Override
    void start(Wall wall) {
        init(wall);
        if (decoder != null) return;
        Thread t = new Thread(this::decodeLoop, "video " + path.getFileName());
        t.setDaemon(true);
        decoder = t;
        t.start();
    }

    /** Hidden videos only decode a single poster frame (for the hidden-items list). */
    @Override
    void startHidden(Wall wall) {
        init(wall);
        Thread t = new Thread(() -> {
            try {
                runFfmpeg(List.of("-ss", "1", "-i", path.toString(), "-frames:v", "1"), () -> false);
            } catch (IOException ignored) {
                // no poster; the placeholder is shown instead
            }
        }, "poster " + path.getFileName());
        t.setDaemon(true);
        t.start();
    }

    @Override
    void stop() {
        decoder = null;
        Process p = process;
        if (p != null) p.destroyForcibly();
    }

    @Override
    void dispose() {
        stop();
    }

    private void init(Wall wall) {
        this.wall = wall;
        synchronized (lock) {
            if (frame == null) {
                frame = new BufferedImage(frameW, frameH, BufferedImage.TYPE_3BYTE_BGR);
                framePixels = ((DataBufferByte) frame.getRaster().getDataBuffer()).getData();
            }
        }
    }

    private boolean stillDecoding() {
        return decoder == Thread.currentThread();
    }

    private void decodeLoop() {
        int failures = 0;
        while (stillDecoding()) {
            int frames = 0;
            try {
                frames = runFfmpeg(List.of("-stream_loop", "-1", "-re", "-i", path.toString()),
                        this::stillDecoding);
            } catch (IOException e) {
                // ffmpeg missing or pipe broken; handled by retry below
            }
            if (!stillDecoding()) break;
            // ffmpeg normally loops forever; if it exits, restart it (back off if it keeps failing).
            failures = frames > 0 ? 0 : failures + 1;
            if (failures >= 5) {
                failed = true;
                System.err.println("cannot play " + path);
                wall.repaint(this);
                break;
            }
            try {
                Thread.sleep(200L + 400L * failures);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * Runs ffmpeg with the given input options, copying each decoded frame into the display
     * buffer until EOF or until {@code keepGoing} turns false. Returns the frame count.
     */
    private int runFfmpeg(List<String> input, java.util.function.BooleanSupplier keepGoing) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error"));
        cmd.addAll(input);
        cmd.addAll(List.of("-an", "-sn", "-dn",
                "-vf", "scale=" + frameW + ":" + frameH + ":flags=bicubic,setsar=1",
                "-fps_mode", "vfr", "-pix_fmt", "bgr24", "-f", "rawvideo", "pipe:1"));
        byte[] buf = new byte[frameW * frameH * 3];
        int frames = 0;
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process = p;
        try (InputStream in = new BufferedInputStream(p.getInputStream(), buf.length)) {
            while (in.readNBytes(buf, 0, buf.length) == buf.length) {
                synchronized (lock) {
                    System.arraycopy(buf, 0, framePixels, 0, buf.length);
                    hasFrame = true;
                }
                frames++;
                wall.repaint(this);
                if (!keepGoing.getAsBoolean()) break;
            }
        } finally {
            p.destroy();
        }
        return frames;
    }

    @Override
    void paintContent(Graphics2D g, int w, int h, boolean interactive) {
        synchronized (lock) {
            if (!hasFrame) {
                paintPlaceholder(g, w, h, failed);
                return;
            }
            drawCover(g, frame, frameW, frameH, w, h);
        }
    }
}
