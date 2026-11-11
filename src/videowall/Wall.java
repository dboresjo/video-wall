// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Owns the items and one {@link Board} (fullscreen window) per screen. All geometry is in
 * global desktop coordinates, so items can be dragged across and straddle screens.
 * Everything except {@link #repaint(MediaItem)} runs on the event dispatch thread.
 */
final class Wall {

    static final int SNAP = 16;      // snap distance, px
    static final int MIN_SIZE = 48;  // smallest item side, px
    static final int GRIP = 12;      // width of the resize zone along item borders, px

    static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;

    private static final Color GUIDE = new Color(0, 200, 255, 220);
    private static final Color HOVER = new Color(255, 255, 255, 70);
    private static final Color ACTIVE = new Color(255, 255, 255, 150);
    private static final Color TOAST_BG = new Color(20, 20, 22, 225);
    private static final Color TOAST_EDGE = new Color(255, 255, 255, 60);

    /** Z-ordered, topmost last. */
    final List<MediaItem> items = new ArrayList<>();
    /** All media of the configuration on screen, in scan order (used by {@link #layout()}). */
    private volatile List<MediaItem> order = List.of();
    /** Hidden items, in the order they were hidden. */
    private final List<MediaItem> hidden = new ArrayList<>();
    /** Scanned media folders; configurations sharing a folder share its items. */
    private final Map<Path, List<MediaItem>> library = new HashMap<>();
    /** The folder given on the command line, or null. */
    private final Path startRoot;
    private Screens.Setup setup;
    private final int minSize;
    private final boolean shuffle;
    private final boolean exclusive;
    /** Hide the mouse cursor after this long without mouse activity; 0 = never. */
    private final int cursorIdleMillis;
    private javax.swing.Timer cursorTimer;
    private boolean cursorHidden;
    private Point cursorHiddenAt;
    private LayoutStore store;
    private javax.swing.Timer saveTimer;
    private volatile boolean dirty;
    /** The configurations of this screen setup; the one at {@link #current} is stale while on screen (the live state is authoritative). */
    private final List<LayoutStore.Config> configs = new ArrayList<>();
    private int current;
    /** A folder is being scanned; input is ignored meanwhile. */
    private boolean loading;
    private String toast;
    private javax.swing.Timer toastTimer;
    /** The panel shown over the wall (hidden-items list, help or folder picker), or null. */
    private JComponent overlay;
    private Board overlayBoard;
    private volatile List<Board> boards = List.of();
    private List<Rectangle> screens = List.of();
    private boolean started;

    // drag state
    private MediaItem active, hover;
    private int dragEdges;
    private Point pressPoint;
    private Rectangle startBounds;
    private boolean dragged;
    private final List<int[]> guides = new ArrayList<>(); // {x1, y1, x2, y2}

    /**
     * @param root folder given on the command line, whose configuration is shown (or added);
     *             null to show the last one, or to ask for a folder on a new screen setup
     */
    Wall(Path root, int minSize, boolean shuffle, boolean exclusive, int cursorIdleMillis) {
        this.startRoot = root == null ? null : LayoutStore.normalize(root);
        this.minSize = minSize;
        this.shuffle = shuffle;
        this.exclusive = exclusive;
        this.cursorIdleMillis = cursorIdleMillis;
    }

    /**
     * Before the windows open (any thread): identifies the screen setup, loads its configurations
     * and scans the folder of the one shown first. Returns an error message if there is nothing
     * to show, else null.
     */
    String prepare() {
        setup = Screens.detect();
        store = new LayoutStore(setup);
        System.err.println("screens: " + setup.label());
        LayoutStore.Saved saved = store.load();
        configs.addAll(saved.configs());
        current = saved.current();
        boolean added = false;
        if (configs.isEmpty() && startRoot == null) {
            System.err.println("new screen setup: choose a folder; it will be saved to " + store.file);
            return null; // start() asks for a folder
        }
        if (configs.isEmpty()) {
            configs.add(new LayoutStore.Config(startRoot)); // a new screen setup: the default layout
            current = 0;
            added = true;
        } else if (startRoot != null) {
            current = -1;
            for (int i = 0; i < configs.size() && current < 0; i++) {
                if (configs.get(i).root.equals(startRoot)) current = i;
            }
            if (current < 0) {
                configs.add(new LayoutStore.Config(startRoot));
                current = configs.size() - 1;
                added = true;
            }
        }
        if (!saved.imported().isEmpty()) {
            System.err.println("imported the layout from " + saved.imported());
            dirty = true; // write the setup file
        } else if (!saved.configs().isEmpty()) {
            System.err.println("restored " + saved.configs().size() + " configuration(s) from " + store.file);
        } else {
            System.err.println("new screen setup; saving to " + store.file);
        }
        Path root = configs.get(current).root;
        List<MediaItem> media = scan(root);
        library.put(root, media);
        return added && media.isEmpty() ? "no images or videos found in " + root : null;
    }

    /** On the EDT, after {@link #prepare()}. */
    void start() {
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        // Left-to-right, then top-to-bottom, so the initial order reads across the screens.
        Arrays.sort(devices, Comparator
                .comparingInt((GraphicsDevice d) -> d.getDefaultConfiguration().getBounds().x)
                .thenComparingInt(d -> d.getDefaultConfiguration().getBounds().y));
        List<Board> bs = new ArrayList<>();
        List<Rectangle> sc = new ArrayList<>();
        for (GraphicsDevice d : devices) {
            Board b = new Board(this, d);
            bs.add(b);
            sc.add(b.screen);
        }
        boards = List.copyOf(bs);
        screens = List.copyOf(sc);

        saveTimer = new javax.swing.Timer(800, e -> saveNow());
        saveTimer.setRepeats(false);
        if (cursorIdleMillis > 0) {
            cursorTimer = new javax.swing.Timer(cursorIdleMillis, e -> hideCursor());
            cursorTimer.setRepeats(false);
            cursorTimer.start();
        }
        toastTimer = new javax.swing.Timer(1800, e -> {
            toast = null;
            repaintAll();
        });
        toastTimer.setRepeats(false);

        for (Board b : boards) b.open(exclusive);
        started = true;
        if (configs.isEmpty()) {
            askFolder(); // first time with these screens
        } else {
            activate(current, configs.size() > 1 ? configLabel(current) : null);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (dirty) { // e.g. killed or logged out: best effort
                try {
                    captureCurrent();
                    store.save(new ArrayList<>(configs), current);
                } catch (Exception ignored) {
                    // nothing more we can do while exiting
                }
            }
            order.forEach(MediaItem::dispose);
        }));
    }

    /** Spreads the visible items over the screens in proportion to screen area, then tiles each screen. */
    void layout() {
        List<MediaItem> visible = new ArrayList<>(order);
        visible.removeAll(hidden);
        items.clear();
        items.addAll(visible);
        long total = 0;
        for (Rectangle s : screens) total += (long) s.width * s.height;
        int n = visible.size(), idx = 0;
        for (int i = 0; i < screens.size(); i++) {
            Rectangle s = screens.get(i);
            int count = i == screens.size() - 1
                    ? n - idx
                    : Math.min(n - idx, (int) Math.round(n * (double) s.width * s.height / total));
            JustifiedLayout.apply(visible.subList(idx, idx + count), s);
            idx += count;
        }
        for (MediaItem it : items) it.restoreBounds = null;
        changed();
    }

    /** R key: re-tile, keeping a copy of the previous layout file just in case. */
    void resetLayout() {
        if (busy()) return;
        saveNow();
        try {
            if (Files.exists(store.file)) {
                Files.copy(store.file, store.file.resolveSibling(store.file.getFileName() + ".before-reset"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("cannot back up layout: " + e);
        }
        layout();
    }

    // ------------------------------------------------------------------ media folders

    private List<MediaItem> scan(Path root) {
        List<MediaItem> found;
        try {
            found = new ArrayList<>(MediaScanner.scan(root, minSize));
        } catch (IOException | RuntimeException e) {
            System.err.println("cannot scan " + root + ": " + e);
            found = new ArrayList<>();
        }
        if (shuffle) Collections.shuffle(found);
        System.err.println(found.size() + " item(s) in " + root);
        return List.copyOf(found);
    }

    /** Runs {@code then} once {@code root} has been scanned: now if it has been, else after a background scan. */
    private void withMedia(Path root, Runnable then) {
        if (library.containsKey(root)) {
            then.run();
            return;
        }
        loading = true;
        toast = "Loading " + folderName(root) + " …";
        toastTimer.stop();
        repaintAll();
        Thread t = new Thread(() -> {
            List<MediaItem> media = scan(root);
            SwingUtilities.invokeLater(() -> {
                library.put(root, media);
                loading = false;
                toast = null;
                repaintAll();
                then.run();
            });
        }, "scan " + root.getFileName());
        t.setDaemon(true);
        t.start();
    }

    /** A path short enough for a message: ~ for the home folder, shortened to its last parts if long. */
    private static String shortPath(Path p) {
        Path home = Path.of(System.getProperty("user.home"));
        String s = p.startsWith(home) ? "~/" + home.relativize(p) : p.toString();
        int n = p.getNameCount();
        for (int k = Math.min(3, n); s.length() > 48 && k >= 1; k--) s = "\u2026/" + p.subpath(n - k, n);
        return s;
    }

    private static String folderName(Path root) {
        return root.getFileName() == null ? root.toString() : root.getFileName().toString();
    }

    // ------------------------------------------------------------------ configurations

    /**
     * Shows configuration {@code index}, whose folder must have been scanned. The configuration on
     * screen before must already have been captured (or removed). Videos start and stop, and the
     * images of a folder that is no longer shown are released.
     */
    private void activate(int index, String message) {
        LayoutStore.Config cfg = configs.get(index);
        List<MediaItem> media = library.get(cfg.root);
        boolean sameMedia = media == order;
        Set<MediaItem> wasVisible = sameMedia ? new HashSet<>(items) : Set.of();
        Set<MediaItem> wasHidden = sameMedia ? new HashSet<>(hidden) : Set.of();
        if (!sameMedia) {
            for (MediaItem it : order) it.dispose();
            items.clear();
            hidden.clear();
        }
        order = media;
        current = index;
        apply(cfg);
        for (MediaItem it : hidden) {
            if (wasVisible.contains(it)) it.stop();
            else if (!wasHidden.contains(it)) it.startHidden(this);
        }
        for (MediaItem it : items) if (!wasVisible.contains(it)) it.start(this);
        if (order.isEmpty()) {
            showToast("No images or videos in " + shortPath(cfg.root) + " \u00b7 press - to remove this configuration");
        } else if (message != null) {
            showToast(message);
        }
    }

    /**
     * Makes {@code cfg} the live state of the items in {@link #order}. A configuration without
     * entries means the default layout (everything visible, unrotated, tiled).
     */
    private void apply(LayoutStore.Config cfg) {
        active = null;
        hover = null;
        for (MediaItem it : order) it.restoreBounds = null;

        if (cfg.isDefault()) {
            hidden.clear();
            for (MediaItem it : order) it.turns = 0;
            layout();
            return;
        }
        List<MediaItem> visible = new ArrayList<>(), hid = new ArrayList<>(), fresh = new ArrayList<>();
        Map<MediaItem, Integer> z = new HashMap<>();
        for (MediaItem it : order) {
            LayoutStore.Entry e = cfg.entries.get(LayoutStore.key(cfg.root, it));
            if (e == null) {
                fresh.add(it);
                continue;
            }
            it.turns = e.turns();
            it.bounds.setBounds(e.bounds());
            z.put(it, e.z());
            (e.hidden() ? hid : visible).add(it);
        }
        visible.sort(Comparator.comparing(z::get));
        hid.sort(Comparator.comparing(z::get));
        items.clear();
        items.addAll(visible);
        hidden.clear();
        hidden.addAll(hid);
        for (MediaItem it : visible) keepOnScreen(it);

        if (!fresh.isEmpty()) {
            // Files new since this configuration was saved: tile them on top, mid-screen.
            Rectangle s = screens.stream().max(Comparator.comparingLong(r -> (long) r.width * r.height)).orElseThrow();
            double f = Math.max(0.3, Math.min(1.0, Math.sqrt(fresh.size() / (double) order.size())));
            int w = (int) (s.width * f), h = (int) (s.height * f);
            for (MediaItem it : fresh) it.turns = 0;
            JustifiedLayout.apply(fresh, new Rectangle(s.x + (s.width - w) / 2, s.y + (s.height - h) / 2, w, h));
            items.addAll(fresh);
            System.err.println(fresh.size() + " new item(s) placed in the middle of the screen");
        }
        changed();
    }

    /**
     * Stores the live state into the configuration on screen. Skipped while its folder shows
     * nothing (e.g. an unplugged drive), so the saved arrangement survives.
     */
    private void captureCurrent() {
        if (order.isEmpty() || current >= configs.size()) return;
        LayoutStore.Config c = new LayoutStore.Config(configs.get(current).root);
        for (int z = 0; z < items.size(); z++) {
            MediaItem it = items.get(z);
            c.entries.put(LayoutStore.key(c.root, it), new LayoutStore.Entry(new Rectangle(it.bounds), it.turns, false, z));
        }
        for (int z = 0; z < hidden.size(); z++) {
            MediaItem it = hidden.get(z);
            c.entries.put(LayoutStore.key(c.root, it), new LayoutStore.Entry(new Rectangle(it.bounds), it.turns, true, z));
        }
        configs.set(current, c);
    }

    private boolean busy() {
        return active != null || overlay != null || loading;
    }

    private String configLabel(int index) {
        return "Configuration " + (index + 1) + " of " + configs.size() + " \u00b7 " + folderName(configs.get(index).root);
    }

    /** Space / Shift+Space: show the next (dir = 1) or previous (dir = -1) configuration. */
    void cycleConfig(int dir) {
        if (busy() || configs.isEmpty()) return;
        if (configs.size() == 1) {
            showToast("Only one configuration \u00b7 press + to add one");
            return;
        }
        int next = Math.floorMod(current + dir, configs.size());
        withMedia(configs.get(next).root, () -> {
            captureCurrent();
            activate(next, configLabel(next));
        });
    }

    /** +: asks for a folder (see {@link #folderChosen}) for a new configuration. */
    void addConfig(Board board) {
        if (busy()) return;
        openFolderPicker(board, false);
    }

    /** A screen setup without configurations: ask for the folder of its first one, on the screen with the pointer. */
    private void askFolder() {
        Point p = pointer();
        Board board = boards.get(0);
        for (Board b : boards) if (p != null && b.screen.contains(p)) board = b;
        openFolderPicker(board, true);
    }

    private void openFolderPicker(Board board, boolean first) {
        Path start = configs.isEmpty() ? null : configs.get(current).root;
        FolderPicker picker = new FolderPicker(this, start, first ? setup.label() : null);
        openOverlay(board, picker, picker.preferredFor(board.screen.getSize()));
        picker.focusChooser();
    }

    /** Esc or Cancel in the folder picker. Without any configuration there is nothing to show, so quit. */
    void folderCancelled() {
        if (configs.isEmpty()) quit();
        else closeOverlay();
    }

    /** Esc: closes the open panel, else quits. */
    void escape() {
        if (overlay instanceof FolderPicker) folderCancelled();
        else if (overlay != null) closeOverlay();
        else quit();
    }

    /** The folder picker's choice: add a configuration with the default layout of {@code folder} and show it. */
    void folderChosen(Path folder) {
        closeOverlay();
        Path root = LayoutStore.normalize(folder);
        if (!Files.isDirectory(root)) {
            showToast("Not a folder: " + shortPath(root));
            return;
        }
        withMedia(root, () -> {
            if (library.get(root).isEmpty()) {
                if (library.get(root) != order) library.remove(root); // rescan if tried again
                showToast("No images or videos in " + shortPath(root));
                if (configs.isEmpty()) askFolder(); // still nothing to show: ask again
                return;
            }
            captureCurrent();
            configs.add(new LayoutStore.Config(root));
            int index = configs.size() - 1;
            activate(index, "New configuration " + (index + 1) + " of " + configs.size() + " \u00b7 " + folderName(root));
        });
    }

    /** -: delete the configuration on screen and show the next one. */
    void removeConfig() {
        if (busy() || configs.isEmpty()) return;
        if (configs.size() == 1) {
            showToast("Can’t remove the only configuration");
            return;
        }
        int removed = current + 1;
        LayoutStore.Config gone = configs.get(current);
        LayoutStore.Config next = configs.get(current == configs.size() - 1 ? current - 1 : current + 1);
        withMedia(next.root, () -> {
            configs.remove(gone);
            int index = configs.indexOf(next);
            activate(index, "Removed configuration " + removed + " \u00b7 showing " + (index + 1) + " of "
                    + configs.size() + " \u00b7 " + folderName(next.root));
        });
    }

    private void showToast(String message) {
        toast = message;
        toastTimer.restart();
        repaintAll();
    }

    private void paintToast(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 22f));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(toast) + 48, h = fm.getHeight() + 24;
        for (Rectangle s : screens) { // on every screen, since a configuration spans them all
            int x = s.x + (s.width - w) / 2, y = s.y + 48;
            g.setColor(TOAST_BG);
            g.fillRoundRect(x, y, w, h, 16, 16);
            g.setColor(TOAST_EDGE);
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(x, y, w, h, 16, 16);
            g.setColor(Color.WHITE);
            g.drawString(toast, x + 24, y + 12 + fm.getAscent());
        }
    }

    // ------------------------------------------------------------------ persistence

    /** Records that the layout changed; it is saved shortly after the changes stop. */
    private void changed() {
        dirty = true;
        if (saveTimer != null) saveTimer.restart();
        repaintAll();
    }

    private void saveNow() {
        if (!dirty || configs.isEmpty()) return;
        try {
            captureCurrent();
            store.save(configs, current);
            dirty = false;
        } catch (IOException e) {
            System.err.println("cannot save layout to " + store.file + ": " + e);
        }
    }

    void quit() {
        if (saveTimer != null) saveTimer.stop();
        saveNow();
        System.exit(0);
    }

    String displayName(MediaItem it) {
        return configs.isEmpty() ? it.path.toString() : LayoutStore.key(configs.get(current).root, it);
    }

    // ------------------------------------------------------------------ rotate / hide / unhide

    /** Rotates by a quarter turn (clockwise for {@code dir} = 1) about the item's centre. */
    private void rotate(MediaItem it, int dir) {
        it.turns = (it.turns + dir + 4) & 3;
        rotateBox(it.bounds);
        if (it.restoreBounds != null) {
            rotateBox(it.restoreBounds);
            fitToScreen(it); // stay fitted, with the new orientation
        }
        changed();
    }

    private static void rotateBox(Rectangle r) {
        double cx = r.x + r.width / 2.0, cy = r.y + r.height / 2.0;
        int w = r.height, h = r.width;
        r.setBounds((int) Math.round(cx - w / 2.0), (int) Math.round(cy - h / 2.0), w, h);
    }

    /** H key: hides the item under the mouse pointer. */
    void hideUnderPointer() {
        if (busy()) return;
        Point p = pointer();
        MediaItem it = p == null ? null : itemAt(p);
        if (it == null) return;
        items.remove(it);
        hidden.add(it);
        it.stop();
        hover = p == null ? null : itemAt(p);
        changed();
    }

    /** Puts hidden items back where they were, on top of everything. */
    void unhide(List<MediaItem> list) {
        for (MediaItem it : List.copyOf(list)) {
            if (!hidden.remove(it)) continue;
            items.add(it);
            keepOnScreen(it);
            it.start(this);
        }
        closeOverlay();
        changed();
    }

    /** A key: opens (or closes) the hidden-item list on the given screen. */
    void togglePicker(Board board) {
        if (loading || closeOverlay() instanceof HiddenPicker) return;
        List<MediaItem> list = new ArrayList<>(order); // original (path) order is easiest to browse
        list.retainAll(hidden);
        HiddenPicker picker = new HiddenPicker(this, list);
        openOverlay(board, picker, picker.preferredFor(board.screen.getSize()));
        picker.focusList();
    }

    /** ? key: opens (or closes) the shortcut list on the given screen. */
    void toggleHelp(Board board) {
        if (closeOverlay() instanceof HelpOverlay) return;
        HelpOverlay help = new HelpOverlay(this);
        openOverlay(board, help, help.getPreferredSize());
    }

    private void openOverlay(Board board, JComponent c, Dimension size) {
        overlay = c;
        overlayBoard = board;
        board.showOverlay(c, size);
    }

    /** Closes the open overlay, if any, and returns it. */
    JComponent closeOverlay() {
        JComponent c = overlay;
        if (c == null) return null;
        overlayBoard.removeOverlay(c);
        overlayBoard.requestFocusInWindow();
        overlay = null;
        overlayBoard = null;
        if (cursorTimer != null) cursorTimer.restart();
        return c;
    }

    boolean overlayOpen() {
        return overlay != null;
    }

    /** The folder picker is open: keys are for its text field and list, not shortcuts (except Esc). */
    boolean overlayTakesKeys() {
        return overlay instanceof FolderPicker;
    }

    private static Point pointer() {
        PointerInfo info = MouseInfo.getPointerInfo();
        return info == null ? null : info.getLocation();
    }

    // ------------------------------------------------------------------ painting

    void paint(Graphics2D g, Rectangle clip) {
        for (MediaItem it : items) {
            if (it.bounds.intersects(clip)) it.paint(g, it.bounds, it == active && dragged);
        }
        MediaItem outlined = active != null ? active : hover;
        if (outlined != null) {
            Rectangle r = outlined.bounds;
            g.setColor(active != null ? ACTIVE : HOVER);
            g.setStroke(new BasicStroke(2f));
            g.drawRect(r.x + 1, r.y + 1, r.width - 2, r.height - 2);
        }
        if (!guides.isEmpty()) {
            g.setColor(GUIDE);
            g.setStroke(new BasicStroke(2f));
            for (int[] l : guides) g.drawLine(l[0], l[1], l[2], l[3]);
        }
        if (toast != null) paintToast(g);
    }

    /** Thread-safe: may be called from decoder threads. */
    void repaint(MediaItem it) {
        Rectangle r = new Rectangle(it.bounds);
        for (Board b : boards) b.repaintGlobal(r);
    }

    void repaintAll() {
        for (Board b : boards) b.repaint();
    }

    // ------------------------------------------------------------------ mouse

    void mousePressed(MouseEvent e) {
        mouseActivity(e, true);
        e.getComponent().requestFocusInWindow(); // so the keyboard shortcuts reach this screen
        Point p = e.getLocationOnScreen();
        MediaItem it = itemAt(p);
        if (it == null || active != null || loading) return;
        if (SwingUtilities.isRightMouseButton(e)) {
            rotate(it, e.isShiftDown() ? -1 : 1);
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(e)) return;
        bringToFront(it);
        if (e.getClickCount() == 2) {
            toggleFit(it);
            return;
        }
        active = it;
        dragEdges = edgesAt(it, p);
        pressPoint = p;
        startBounds = new Rectangle(it.bounds);
        dragged = false;
        repaintAll();
    }

    void mouseDragged(MouseEvent e) {
        mouseActivity(e, true);
        if (active == null) return;
        Point p = e.getLocationOnScreen();
        int dx = p.x - pressPoint.x, dy = p.y - pressPoint.y;
        if (!dragged && Math.abs(dx) + Math.abs(dy) < 3) return;
        dragged = true;
        guides.clear();
        boolean snap = !e.isControlDown();
        Rectangle r = dragEdges == 0
                ? moved(dx, dy, snap)
                : resized(dx, dy, e.isShiftDown(), snap);
        active.bounds.setBounds(r);
        active.restoreBounds = null;
        repaintAll();
    }

    void mouseReleased(MouseEvent e) {
        if (active == null) return;
        if (dragged) {
            keepOnScreen(active);
            changed();
        }
        active = null;
        dragged = false;
        guides.clear();
        repaintAll();
        mouseMoved(e);
    }

    void mouseMoved(MouseEvent e) {
        if (!mouseActivity(e, false) || active != null) return;
        Point p = e.getLocationOnScreen();
        MediaItem it = itemAt(p);
        if (it != hover) {
            hover = it;
            repaintAll();
        }
        ((Board) e.getComponent()).setWantedCursor(cursorFor(it == null ? 0 : edgesAt(it, p)));
    }

    void mouseExited() {
        if (active == null && hover != null) {
            hover = null;
            repaintAll();
        }
    }

    /** Zooms the item under the cursor, keeping the point under the cursor fixed. */
    void mouseWheel(MouseWheelEvent e) {
        mouseActivity(e, true);
        if (active != null || loading) return;
        Point p = e.getLocationOnScreen();
        MediaItem it = itemAt(p);
        if (it == null) return;
        double f = Math.pow(1.1, -e.getPreciseWheelRotation());
        Rectangle r = it.bounds;
        double a = r.width / (double) r.height;
        double w = Math.max(r.width * f, MIN_SIZE * Math.max(1, a));
        f = w / r.width;
        int nw = (int) Math.round(w), nh = (int) Math.round(w / a);
        int nx = (int) Math.round(p.x - (p.x - r.x) * f), ny = (int) Math.round(p.y - (p.y - r.y) * f);
        r.setBounds(nx, ny, nw, nh);
        it.restoreBounds = null;
        changed();
    }

    // ------------------------------------------------------------------ idle cursor

    /** Idle timer fired: hide the pointer (and the hover outline) unless something is in progress. */
    private void hideCursor() {
        if (active != null || overlay != null) {
            cursorTimer.restart(); // try again later
            return;
        }
        cursorHidden = true;
        cursorHiddenAt = pointer();
        hover = null;
        for (Board b : boards) b.setCursorVisible(false);
        repaintAll();
    }

    /**
     * Shows the cursor again and restarts the idle timer. Returns false for a tiny movement
     * while hidden (mouse jitter, a bumped desk), which is ignored.
     */
    private boolean mouseActivity(MouseEvent e, boolean deliberate) {
        if (cursorHidden) {
            if (!deliberate && cursorHiddenAt != null && e.getLocationOnScreen().distance(cursorHiddenAt) < 4) {
                return false;
            }
            cursorHidden = false;
            for (Board b : boards) b.setCursorVisible(true);
        }
        if (cursorTimer != null) cursorTimer.restart();
        return true;
    }

    // ------------------------------------------------------------------ geometry

    private MediaItem itemAt(Point p) {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).bounds.contains(p)) return items.get(i);
        }
        return null;
    }

    private void bringToFront(MediaItem it) {
        items.remove(it);
        items.add(it);
    }

    private static int edgesAt(MediaItem it, Point p) {
        Rectangle r = it.bounds;
        int grip = Math.max(3, Math.min(GRIP, Math.min(r.width, r.height) / 4));
        int edges = 0;
        if (p.x < r.x + grip) edges |= LEFT;
        else if (p.x >= r.x + r.width - grip) edges |= RIGHT;
        if (p.y < r.y + grip) edges |= TOP;
        else if (p.y >= r.y + r.height - grip) edges |= BOTTOM;
        return edges;
    }

    private static Cursor cursorFor(int edges) {
        return Cursor.getPredefinedCursor(switch (edges) {
            case LEFT | TOP -> Cursor.NW_RESIZE_CURSOR;
            case RIGHT | TOP -> Cursor.NE_RESIZE_CURSOR;
            case LEFT | BOTTOM -> Cursor.SW_RESIZE_CURSOR;
            case RIGHT | BOTTOM -> Cursor.SE_RESIZE_CURSOR;
            case LEFT -> Cursor.W_RESIZE_CURSOR;
            case RIGHT -> Cursor.E_RESIZE_CURSOR;
            case TOP -> Cursor.N_RESIZE_CURSOR;
            case BOTTOM -> Cursor.S_RESIZE_CURSOR;
            default -> Cursor.DEFAULT_CURSOR;
        });
    }

    private Rectangle moved(int dx, int dy, boolean snap) {
        Rectangle r = new Rectangle(startBounds);
        r.translate(dx, dy);
        if (!snap) return r;
        Snap sx = snap(true, r, r.x, r.x + r.width);
        Snap sy = snap(false, r, r.y, r.y + r.height);
        if (sx != null) r.x += sx.delta;
        if (sy != null) r.y += sy.delta;
        addGuide(sx, r);
        addGuide(sy, r);
        return r;
    }

    private Rectangle resized(int dx, int dy, boolean free, boolean snap) {
        boolean l = (dragEdges & LEFT) != 0, rt = (dragEdges & RIGHT) != 0;
        boolean t = (dragEdges & TOP) != 0, b = (dragEdges & BOTTOM) != 0;
        int left = startBounds.x, top = startBounds.y;
        int right = left + startBounds.width, bottom = top + startBounds.height;

        if (free) {
            if (l) left = Math.min(left + dx, right - MIN_SIZE);
            if (rt) right = Math.max(right + dx, left + MIN_SIZE);
            if (t) top = Math.min(top + dy, bottom - MIN_SIZE);
            if (b) bottom = Math.max(bottom + dy, top + MIN_SIZE);
            Rectangle r = new Rectangle(left, top, right - left, bottom - top);
            if (snap && (l || rt)) {
                Snap s = snap(true, r, l ? left : right);
                if (s != null && (l ? right - s.pos : s.pos - left) >= MIN_SIZE) {
                    if (l) left = s.pos; else right = s.pos;
                    r = new Rectangle(left, top, right - left, bottom - top);
                    addGuide(s, r);
                }
            }
            if (snap && (t || b)) {
                Snap s = snap(false, r, t ? top : bottom);
                if (s != null && (t ? bottom - s.pos : s.pos - top) >= MIN_SIZE) {
                    if (t) top = s.pos; else bottom = s.pos;
                    r = new Rectangle(left, top, right - left, bottom - top);
                    addGuide(s, r);
                }
            }
            return r;
        }

        // Aspect-locked: the edges opposite the dragged ones stay put.
        double a = startBounds.width / (double) startBounds.height;
        int anchorX = l ? right : left;
        int anchorY = t ? bottom : top;
        double wFromX = l ? right - (left + dx) : (right + dx) - left;
        double wFromY = (t ? bottom - (top + dy) : (bottom + dy) - top) * a;
        boolean hx = l || rt, hy = t || b;
        double w = hx && hy ? Math.max(wFromX, wFromY) : hx ? wFromX : wFromY;
        double minW = Math.max(MIN_SIZE, MIN_SIZE * a);
        Rectangle r = anchored(Math.max(w, minW), a, anchorX, anchorY, l, t);
        if (!snap) return r;

        // Snap whichever moving edge (horizontal or vertical) is closest to a target.
        Snap sx = snap(true, r, l ? r.x : r.x + r.width);
        Snap sy = snap(false, r, t ? r.y : r.y + r.height);
        Snap best = sx == null ? sy : sy == null ? sx : Math.abs(sx.delta) <= Math.abs(sy.delta) ? sx : sy;
        if (best != null) {
            double nw = best == sx
                    ? Math.abs(anchorX - sx.pos)
                    : Math.abs(anchorY - sy.pos) * a;
            if (nw >= minW) {
                r = anchored(nw, a, anchorX, anchorY, l, t);
                addGuide(best, r);
            }
        }
        return r;
    }

    private static Rectangle anchored(double w, double a, int anchorX, int anchorY, boolean growLeft, boolean growUp) {
        int iw = (int) Math.round(w), ih = (int) Math.round(w / a);
        return new Rectangle(growLeft ? anchorX - iw : anchorX, growUp ? anchorY - ih : anchorY, iw, ih);
    }

    /** A snap of one of the dragged item's edges onto a target edge at {@code pos}. */
    private record Snap(boolean vertical, int delta, int pos, Rectangle target) {}

    /**
     * Finds the closest target edge for any of {@code edges} (x positions when {@code vertical},
     * else y positions). Targets are every screen and every other item that is near the item
     * along the other axis.
     */
    private Snap snap(boolean vertical, Rectangle r, int... edges) {
        Snap best = null;
        int lo = vertical ? r.y : r.x;
        int hi = vertical ? r.y + r.height : r.x + r.width;
        List<Rectangle> targets = new ArrayList<>(screens);
        for (MediaItem it : items) if (it != active) targets.add(it.bounds);
        for (Rectangle t : targets) {
            int tlo = vertical ? t.y : t.x;
            int thi = vertical ? t.y + t.height : t.x + t.width;
            if (hi < tlo - SNAP || lo > thi + SNAP) continue; // not adjacent along the other axis
            int a = vertical ? t.x : t.y;
            int b = vertical ? t.x + t.width : t.y + t.height;
            for (int target : new int[] {a, b}) {
                for (int edge : edges) {
                    int d = target - edge;
                    if (Math.abs(d) <= SNAP && (best == null || Math.abs(d) < Math.abs(best.delta))) {
                        best = new Snap(vertical, d, target, t);
                    }
                }
            }
        }
        return best;
    }

    private void addGuide(Snap s, Rectangle r) {
        if (s == null) return;
        Rectangle t = s.target;
        if (s.vertical) {
            guides.add(new int[] {s.pos, Math.min(r.y, t.y), s.pos, Math.max(r.y + r.height, t.y + t.height)});
        } else {
            guides.add(new int[] {Math.min(r.x, t.x), s.pos, Math.max(r.x + r.width, t.x + t.width), s.pos});
        }
    }

    /** Pulls an item back onto the nearest screen if it was dropped mostly off-screen. */
    private void keepOnScreen(MediaItem it) {
        Rectangle r = it.bounds;
        Point c = new Point(r.x + r.width / 2, r.y + r.height / 2);
        for (Rectangle s : screens) if (s.contains(c)) return;
        Rectangle s = nearestScreen(c);
        r.x = r.width >= s.width ? s.x : Math.max(s.x, Math.min(r.x, s.x + s.width - r.width));
        r.y = r.height >= s.height ? s.y : Math.max(s.y, Math.min(r.y, s.y + s.height - r.height));
    }

    private Rectangle nearestScreen(Point p) {
        Rectangle best = screens.get(0);
        double bestD = Double.MAX_VALUE;
        for (Rectangle s : screens) {
            double dx = Math.max(Math.max(s.x - p.x, 0), p.x - (s.x + s.width));
            double dy = Math.max(Math.max(s.y - p.y, 0), p.y - (s.y + s.height));
            double d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    /** Double-click: fit the item to its screen (keeping aspect), or restore it. */
    private void toggleFit(MediaItem it) {
        if (it.restoreBounds != null) {
            it.bounds.setBounds(it.restoreBounds);
            it.restoreBounds = null;
        } else {
            it.restoreBounds = new Rectangle(it.bounds);
            fitToScreen(it);
        }
        changed();
    }

    private void fitToScreen(MediaItem it) {
        Rectangle r = it.bounds;
        Rectangle s = nearestScreen(new Point(r.x + r.width / 2, r.y + r.height / 2));
        double a = r.width / (double) r.height;
        int w = s.width, h = (int) Math.round(w / a);
        if (h > s.height) {
            h = s.height;
            w = (int) Math.round(h * a);
        }
        r.setBounds(s.x + (s.width - w) / 2, s.y + (s.height - h) / 2, w, h);
    }
}
