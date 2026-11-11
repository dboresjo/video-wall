// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** The "?" (or "/") overlay: a list of mouse and keyboard shortcuts. Any click or Esc / ? closes it. */
@SuppressWarnings("serial")
final class HelpOverlay extends JPanel {

    private static final Color BG = new Color(0x1c, 0x1c, 0x1e);
    private static final Color FG = new Color(0xe6, 0xe6, 0xe6);
    private static final Color DIM = new Color(0x9a, 0x9a, 0xa0);
    private static final Color KEY_BG = new Color(0x34, 0x34, 0x38);
    private static final Color KEY_EDGE = new Color(0x55, 0x55, 0x5a);

    private static final String[][] MOUSE = {
            {"Drag", "Move (snaps to screen and item edges)"},
            {"Drag edge or corner", "Resize, keeping proportions"},
            {"Shift + drag edge", "Resize freely (picture is cropped to fill)"},
            {"Ctrl + drag", "Move or resize without snapping"},
            {"Mouse wheel", "Zoom the item around the pointer"},
            {"Double-click", "Fill the screen / restore"},
            {"Right-click", "Rotate 90° clockwise"},
            {"Shift + right-click", "Rotate 90° anticlockwise"},
    };

    private static final String[][] KEYS = {
            {"H", "Hide the item under the pointer"},
            {"A", "Show hidden items, to put them back"},
            {"R", "Reset layout (re-tile the visible items)"},
            {"Space", "Next configuration (Shift + Space: previous)"},
            {"+", "New configuration: choose its folder (starts with the default layout)"},
            {"-", "Remove the configuration on screen"},
            {"?  /  F1", "Show / hide this list"},
            {"Esc or Q", "Quit (Esc closes this list first)"},
    };

    private int row;

    HelpOverlay(Wall wall) {
        super(new GridBagLayout());
        setBackground(BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x44, 0x44, 0x48)),
                BorderFactory.createEmptyBorder(20, 24, 20, 24)));
        // Clicking anywhere on the panel closes it (and keeps the click off the wall below).
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { wall.closeOverlay(); }
        });

        JLabel title = new JLabel("Shortcuts");
        title.setForeground(FG);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        add(title, cell(0, 2, new Insets(0, 0, 8, 0)));

        section("Mouse", MOUSE);
        section("Keyboard", KEYS);

        JLabel footer = new JLabel("Each screen setup has its own configurations, saved automatically.  Click or press Esc to close.");
        footer.setForeground(DIM);
        add(footer, cell(0, 2, new Insets(18, 0, 0, 0)));
    }

    private void section(String name, String[][] rows) {
        JLabel heading = new JLabel(name.toUpperCase());
        heading.setForeground(DIM);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 12f));
        add(heading, cell(0, 2, new Insets(16, 0, 6, 0)));
        for (String[] r : rows) {
            JLabel key = new JLabel(r[0]);
            key.setForeground(FG);
            key.setFont(key.getFont().deriveFont(Font.BOLD, 14f));
            key.setOpaque(true);
            key.setBackground(KEY_BG);
            key.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(KEY_EDGE),
                    BorderFactory.createEmptyBorder(3, 8, 3, 8)));
            GridBagConstraints k = cell(0, 1, new Insets(3, 0, 3, 18));
            k.anchor = GridBagConstraints.LINE_END;
            k.fill = GridBagConstraints.NONE;
            add(key, k);
            row--; // same row for the description

            JLabel what = new JLabel(r[1]);
            what.setForeground(FG);
            what.setFont(what.getFont().deriveFont(Font.PLAIN, 14f));
            add(what, cell(1, 1, new Insets(3, 0, 3, 0)));
        }
    }

    private GridBagConstraints cell(int x, int width, Insets insets) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = row++;
        c.gridwidth = width;
        c.insets = insets;
        c.anchor = GridBagConstraints.LINE_START;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }
}
