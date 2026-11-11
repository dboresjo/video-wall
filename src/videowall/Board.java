// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import javax.swing.AbstractAction;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.KeyStroke;

/** The fullscreen window for one screen; paints the part of the wall that falls on it. */
@SuppressWarnings("serial")
final class Board extends JComponent {

    private static final Color BACKGROUND = new Color(0x0b, 0x0b, 0x0b);
    private static final Cursor BLANK_CURSOR = Toolkit.getDefaultToolkit().createCustomCursor(
            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(0, 0), "blank");

    final Rectangle screen;
    private final Wall wall;
    private final GraphicsDevice device;
    private JFrame frame;
    private Cursor wantedCursor = Cursor.getDefaultCursor();
    private boolean cursorVisible = true;

    Board(Wall wall, GraphicsDevice device) {
        this.wall = wall;
        this.device = device;
        this.screen = device.getDefaultConfiguration().getBounds();
        setOpaque(true);
        setDoubleBuffered(true);
        setFocusable(true);

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { wall.mousePressed(e); }
            @Override public void mouseDragged(MouseEvent e) { wall.mouseDragged(e); }
            @Override public void mouseReleased(MouseEvent e) { wall.mouseReleased(e); }
            @Override public void mouseMoved(MouseEvent e) { wall.mouseMoved(e); }
            @Override public void mouseExited(MouseEvent e) { wall.mouseExited(); }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { wall.mouseWheel(e); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);

        InputMap keys = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        // While an overlay (hidden-items list or help) is open, only Esc, A, ? and / do anything;
        // while the folder picker is open, only Esc.
        bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "escape", wall::escape);
        bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_Q, 0), "quit", () -> { if (!wall.overlayOpen()) wall.quit(); });
        bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "relayout", () -> { if (!wall.overlayOpen()) wall.resetLayout(); });
        bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_H, 0), "hide", wall::hideUnderPointer);
        bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_A, 0), "hidden-list", () -> wall.togglePicker(this));
        bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "next-config", () -> wall.cycleConfig(1));
        bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.SHIFT_DOWN_MASK), "prev-config", () -> wall.cycleConfig(-1));
        bind(keys, KeyStroke.getKeyStroke('+'), "add-config", () -> wall.addConfig(this));
        bind(keys, KeyStroke.getKeyStroke('-'), "remove-config", wall::removeConfig);
        // '?' and '/' as typed characters work whatever the keyboard layout; F1 as a fallback.
        bind(keys, KeyStroke.getKeyStroke('?'), "help", () -> wall.toggleHelp(this));
        bind(keys, KeyStroke.getKeyStroke('/'), "help", () -> wall.toggleHelp(this));
        bind(keys, KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "help", () -> wall.toggleHelp(this));
    }

    private void bind(InputMap keys, KeyStroke stroke, String name, Runnable action) {
        keys.put(stroke, name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (wall.overlayTakesKeys() && !name.equals("escape")) return;
                action.run();
            }
        });
    }

    void open(boolean exclusive) {
        frame = new JFrame("Video Wall", device.getDefaultConfiguration());
        frame.setUndecorated(true);
        frame.setBackground(BACKGROUND);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { wall.quit(); }
        });
        frame.setContentPane(this);
        frame.setBounds(screen);
        if (exclusive && device.isFullScreenSupported()) {
            device.setFullScreenWindow(frame);
        } else {
            frame.setVisible(true);
        }
        frame.toFront();
        requestFocusInWindow();
    }

    /** Sets the cursor for the wall area; applied now, or when the cursor is next shown. */
    void setWantedCursor(Cursor c) {
        wantedCursor = c;
        if (cursorVisible) setCursor(c);
    }

    void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        setCursor(visible ? wantedCursor : BLANK_CURSOR);
    }

    /** Shows a panel centred on this screen, above the wall. */
    void showOverlay(JComponent c, Dimension size) {
        c.setBounds((getWidth() - size.width) / 2, (getHeight() - size.height) / 2, size.width, size.height);
        frame.getLayeredPane().add(c, JLayeredPane.POPUP_LAYER);
        frame.getLayeredPane().revalidate();
        c.repaint();
    }

    void removeOverlay(JComponent c) {
        frame.getLayeredPane().remove(c);
        frame.getLayeredPane().repaint(c.getBounds());
        repaint();
    }

    /** Repaints a rectangle given in global coordinates, if it touches this screen. */
    void repaintGlobal(Rectangle r) {
        if (r.intersects(screen)) repaint(r.x - screen.x, r.y - screen.y, r.width, r.height);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            Rectangle clip = g.getClipBounds();
            if (clip == null) clip = new Rectangle(0, 0, getWidth(), getHeight());
            g.setColor(BACKGROUND);
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.translate(-screen.x, -screen.y);
            clip.translate(screen.x, screen.y);
            wall.paint(g, clip);
        } finally {
            g.dispose();
        }
    }
}
