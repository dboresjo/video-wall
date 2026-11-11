// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.SwingUtilities;

/** A still image. Decoded in the background and cached pre-scaled at its on-screen size. */
final class ImageItem extends MediaItem {

    /** Longest side kept in memory for the decoded source image. */
    private static final int MAX_SOURCE = 2560;

    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2), r -> {
                Thread t = new Thread(r, "image-worker");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });

    private final int orientation;
    /** Set while loaded or loading; null after {@link #dispose()}, so the item can be started again. */
    private volatile Wall wall;
    private volatile BufferedImage source;
    private volatile boolean failed;
    // EDT-only state for the size-matched cache.
    private BufferedImage scaled;
    private int pendingW = -1, pendingH = -1;

    private ImageItem(Path path, int w, int h, int orientation) {
        super(path, w, h);
        this.orientation = orientation;
    }

    @Override
    String kind() {
        return "image";
    }

    /** Reads only the header (dimensions and EXIF orientation). Returns null if unreadable. */
    static ImageItem probe(Path path) {
        int w = -1, h = -1;
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> it = in == null ? null : ImageIO.getImageReaders(in);
            if (it != null && it.hasNext()) {
                ImageReader r = it.next();
                try {
                    r.setInput(in, true, true);
                    w = r.getWidth(0);
                    h = r.getHeight(0);
                } finally {
                    r.dispose();
                }
            }
        } catch (IOException | RuntimeException e) {
            w = h = -1;
        }
        if ((w <= 0 || h <= 0) && Ffmpeg.available()) {
            Map<String, String> m = Ffmpeg.probe(List.of("ffprobe", "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=width,height", "-of", "default=noprint_wrappers=1", path.toString()));
            w = Ffmpeg.intValue(m, "width", -1);
            h = Ffmpeg.intValue(m, "height", -1);
        }
        if (w <= 0 || h <= 0) return null;
        int o = Exif.orientation(path);
        return o >= 5 ? new ImageItem(path, h, w, o) : new ImageItem(path, w, h, o);
    }

    @Override
    void start(Wall wall) {
        if (this.wall != null) return; // already loading / loaded
        this.wall = wall;
        WORKERS.submit(() -> {
            try {
                BufferedImage img = load(path, orientation);
                if (this.wall == wall) source = img; // else disposed meanwhile
            } catch (Throwable t) {
                failed = true;
                System.err.println("cannot decode " + path + ": " + t);
            }
            wall.repaint(this);
        });
    }

    @Override
    void paintContent(Graphics2D g, int w, int h, boolean interactive) {
        BufferedImage src = source;
        if (src == null) {
            paintPlaceholder(g, w, h, failed);
            return;
        }
        BufferedImage c = scaled;
        if (c != null && c.getWidth() == w && c.getHeight() == h) {
            g.drawImage(c, 0, 0, null);
            return;
        }
        drawCover(g, src, src.getWidth(), src.getHeight(), w, h);
        if (!interactive) requestScaled(src, w, h);
    }

    private void requestScaled(BufferedImage src, int w, int h) {
        if (w <= 0 || h <= 0 || (pendingW == w && pendingH == h)) return;
        pendingW = w;
        pendingH = h;
        WORKERS.submit(() -> {
            BufferedImage out = scaleCover(src, w, h);
            SwingUtilities.invokeLater(() -> {
                if (pendingW == w && pendingH == h) {
                    pendingW = pendingH = -1;
                }
                Wall owner = wall;
                if (owner != null && contentWidth() == w && contentHeight() == h) {
                    scaled = out;
                    owner.repaint(this);
                }
            });
        });
    }

    @Override
    void dispose() {
        wall = null;
        source = null;
        scaled = null;
        pendingW = pendingH = -1;
        failed = false;
    }

    // ---------------------------------------------------------------- decoding

    private static BufferedImage load(Path path, int orientation) throws IOException {
        BufferedImage img = null;
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> it = in == null ? null : ImageIO.getImageReaders(in);
            if (it != null && it.hasNext()) {
                ImageReader r = it.next();
                try {
                    r.setInput(in, true, true);
                    int longest = Math.max(r.getWidth(0), r.getHeight(0));
                    int sub = Math.max(1, (int) Math.ceil(longest / (double) MAX_SOURCE));
                    ImageReadParam param = r.getDefaultReadParam();
                    param.setSourceSubsampling(sub, sub, 0, 0);
                    img = r.read(0, param);
                } catch (IOException | RuntimeException e) {
                    img = null; // e.g. CMYK JPEG - fall back to ffmpeg
                } finally {
                    r.dispose();
                }
            }
        }
        if (img == null) img = decodeWithFfmpeg(path);
        if (img == null) throw new IOException("unsupported image");
        return orient(toCompatible(img), orientation);
    }

    private static BufferedImage decodeWithFfmpeg(Path path) throws IOException {
        if (!Ffmpeg.available()) return null;
        String scale = "scale=w='min(iw," + MAX_SOURCE + ")':h='min(ih," + MAX_SOURCE
                + ")':force_original_aspect_ratio=decrease";
        Process p = new ProcessBuilder("ffmpeg", "-nostdin", "-v", "error", "-noautorotate",
                "-i", path.toString(), "-frames:v", "1", "-vf", scale,
                "-f", "image2pipe", "-vcodec", "png", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try (InputStream in = p.getInputStream()) {
            return ImageIO.read(in);
        } finally {
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static BufferedImage toCompatible(BufferedImage img) {
        int type = img.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (img.getType() == type) return img;
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), type);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Applies an EXIF orientation (1-8) so the image displays upright. */
    private static BufferedImage orient(BufferedImage img, int o) {
        if (o <= 1 || o > 8) return img;
        int w = img.getWidth(), h = img.getHeight();
        // AffineTransform(m00, m10, m01, m11, m02, m12): x' = m00*x + m01*y + m02, y' = m10*x + m11*y + m12
        AffineTransform t = switch (o) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // mirror horizontal
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // rotate 180
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // mirror vertical
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // transpose
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // rotate 90 CW
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // transverse
            default -> new AffineTransform(0, -1, 1, 0, 0, w);  // 8: rotate 90 CCW
        };
        boolean swap = o >= 5;
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, img.getType());
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(img, t, null);
        g.dispose();
        return out;
    }

    // ---------------------------------------------------------------- scaling

    /** High-quality "cover" scale: crop to the target aspect, then halve repeatedly before a final bilinear pass. */
    static BufferedImage scaleCover(BufferedImage src, int w, int h) {
        int[] c = coverCrop(src.getWidth(), src.getHeight(), w, h);
        BufferedImage cur = src.getSubimage(c[0], c[1], c[2], c[3]);
        int cw = c[2], ch = c[3];
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2;
            ch /= 2;
            cur = resize(cur, cw, ch, src.getType(), RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        Object interp = (w > cw || h > ch)
                ? RenderingHints.VALUE_INTERPOLATION_BICUBIC
                : RenderingHints.VALUE_INTERPOLATION_BILINEAR;
        return resize(cur, w, h, src.getType(), interp);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h, int type, Object interpolation) {
        BufferedImage out = new BufferedImage(w, h, type);
        Graphics2D g = out.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // ---------------------------------------------------------------- EXIF

    static final class Exif {
        private Exif() {}

        /** EXIF orientation (1-8) of a JPEG or TIFF file; 1 when absent. */
        static int orientation(Path path) {
            byte[] b;
            try (InputStream in = Files.newInputStream(path)) {
                b = in.readNBytes(256 * 1024);
            } catch (IOException e) {
                return 1;
            }
            try {
                if (b.length > 8 && ((b[0] == 'I' && b[1] == 'I') || (b[0] == 'M' && b[1] == 'M'))) {
                    return tiffOrientation(b, 0);
                }
                if (b.length < 4 || (b[0] & 0xff) != 0xFF || (b[1] & 0xff) != 0xD8) return 1;
                int i = 2;
                while (i + 4 <= b.length) {
                    if ((b[i] & 0xff) != 0xFF) return 1;
                    int marker = b[i + 1] & 0xff;
                    if (marker == 0xFF) { i++; continue; }                         // fill byte
                    if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) { i += 2; continue; }
                    if (marker == 0xDA || marker == 0xD9) return 1;               // image data reached
                    int len = ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
                    if (marker == 0xE1 && i + 10 <= b.length
                            && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f'
                            && b[i + 8] == 0 && b[i + 9] == 0) {
                        return tiffOrientation(b, i + 10);
                    }
                    i += 2 + len;
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                // truncated / malformed metadata
            }
            return 1;
        }

        private static int tiffOrientation(byte[] b, int base) {
            boolean le = b[base] == 'I';
            int ifd = base + u32(b, base + 4, le);
            int count = u16(b, ifd, le);
            for (int k = 0; k < count; k++) {
                int e = ifd + 2 + k * 12;
                if (u16(b, e, le) == 0x0112) {
                    int v = u16(b, e + 8, le);
                    return v >= 1 && v <= 8 ? v : 1;
                }
            }
            return 1;
        }

        private static int u16(byte[] b, int i, boolean le) {
            int x = b[i] & 0xff, y = b[i + 1] & 0xff;
            return le ? (y << 8) | x : (x << 8) | y;
        }

        private static int u32(byte[] b, int i, boolean le) {
            return le ? (u16(b, i + 2, true) << 16) | u16(b, i, true)
                      : (u16(b, i, false) << 16) | u16(b, i + 2, false);
        }
    }
}
