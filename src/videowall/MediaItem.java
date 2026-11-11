// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** Something shown on the wall. Bounds are in global (virtual desktop) coordinates. */
abstract class MediaItem {

    private static final Color PLACEHOLDER = new Color(0x22, 0x22, 0x22);
    private static final Color FAILED = new Color(0x40, 0x18, 0x18);

    final Path path;
    /** Display size of the media in pixels, after EXIF/stream rotation but before {@link #turns}. */
    final int naturalWidth, naturalHeight;
    /** On-screen bounds, i.e. after rotation by {@link #turns}. Mutated only on the EDT. */
    final Rectangle bounds = new Rectangle();
    /** Bounds before "fit to screen", or null. */
    Rectangle restoreBounds;
    /** User rotation in clockwise quarter turns, 0-3. */
    int turns;

    MediaItem(Path path, int naturalWidth, int naturalHeight) {
        this.path = path;
        this.naturalWidth = naturalWidth;
        this.naturalHeight = naturalHeight;
    }

    /** On-screen aspect ratio (width / height), taking the user rotation into account. */
    double aspect() {
        return (turns & 1) == 0
                ? naturalWidth / (double) naturalHeight
                : naturalHeight / (double) naturalWidth;
    }

    abstract String kind();

    /** Begin loading / playback. Called on the EDT; may be called again after {@link #stop()}. */
    abstract void start(Wall wall);

    /** Prepare an item that starts out hidden (enough to show a thumbnail). */
    void startHidden(Wall wall) {
        start(wall);
    }

    /** Pause expensive work (video decoding) while hidden. */
    void stop() {}

    abstract void dispose();

    /**
     * Paints the unrotated content into the box (0, 0, w, h). {@code interactive} is true while
     * the item is being dragged or when painting a one-off thumbnail.
     */
    abstract void paintContent(Graphics2D g, int w, int h, boolean interactive);

    /** Paints the item, rotated by {@link #turns}, into {@code r}. */
    final void paint(Graphics2D g, Rectangle r, boolean interactive) {
        AffineTransform saved = g.getTransform();
        int cw = r.width, ch = r.height;
        switch (turns & 3) {
            case 1 -> { g.translate(r.x + r.width, r.y); g.transform(AffineTransform.getQuadrantRotateInstance(1)); cw = r.height; ch = r.width; }
            case 2 -> { g.translate(r.x + r.width, r.y + r.height); g.transform(AffineTransform.getQuadrantRotateInstance(2)); }
            case 3 -> { g.translate(r.x, r.y + r.height); g.transform(AffineTransform.getQuadrantRotateInstance(3)); cw = r.height; ch = r.width; }
            default -> g.translate(r.x, r.y);
        }
        paintContent(g, cw, ch, interactive);
        g.setTransform(saved);
    }

    /** Width of the unrotated content box for the current bounds. */
    int contentWidth() {
        return (turns & 1) == 0 ? bounds.width : bounds.height;
    }

    int contentHeight() {
        return (turns & 1) == 0 ? bounds.height : bounds.width;
    }

    /** A small picture of the item that fits in a {@code size} x {@code size} box. */
    BufferedImage thumbnail(int size) {
        double a = aspect();
        int w = a >= 1 ? size : Math.max(1, (int) Math.round(size * a));
        int h = a >= 1 ? Math.max(1, (int) Math.round(size / a)) : size;
        // Render at 4x then downscale, which looks far better than one big bilinear step.
        BufferedImage big = new BufferedImage(w * 4, h * 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        paint(g, new Rectangle(0, 0, w * 4, h * 4), true);
        g.dispose();
        return ImageItem.scaleCover(big, w, h);
    }

    static void paintPlaceholder(Graphics2D g, int w, int h, boolean failed) {
        g.setColor(failed ? FAILED : PLACEHOLDER);
        g.fillRect(0, 0, w, h);
    }

    /** Draws {@code img} scaled to cover (0, 0, w, h), cropping the overflow equally on both sides. */
    static void drawCover(Graphics2D g, Image img, int iw, int ih, int w, int h) {
        int[] c = coverCrop(iw, ih, w, h);
        g.drawImage(img, 0, 0, w, h, c[0], c[1], c[0] + c[2], c[1] + c[3], null);
    }

    /** Source crop {x, y, w, h} of an iw x ih image that fills a w x h box. */
    static int[] coverCrop(int iw, int ih, int w, int h) {
        double s = Math.max(w / (double) iw, h / (double) ih);
        int cw = Math.max(1, Math.min(iw, (int) Math.round(w / s)));
        int ch = Math.max(1, Math.min(ih, (int) Math.round(h / s)));
        return new int[] {(iw - cw) / 2, (ih - ch) / 2, cw, ch};
    }
}
