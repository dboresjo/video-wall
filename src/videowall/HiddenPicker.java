// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;

/** Overlay listing the hidden items as thumbnails; picking items puts them back on the wall. */
@SuppressWarnings("serial")
final class HiddenPicker extends JPanel {

    private static final int THUMB = 120;
    private static final Color BG = new Color(0x1c, 0x1c, 0x1e);
    private static final Color LIST_BG = new Color(0x12, 0x12, 0x14);
    private static final Color FG = new Color(0xe6, 0xe6, 0xe6);
    private static final Color DIM = new Color(0x9a, 0x9a, 0xa0);
    private static final Color SELECTED = new Color(0x2a, 0x5d, 0x8f);
    private static final Color BUTTON = new Color(0x34, 0x34, 0x38);

    private final Wall wall;
    private final JList<MediaItem> list;
    private final Map<MediaItem, Icon> thumbs = new HashMap<>();

    HiddenPicker(Wall wall, List<MediaItem> hidden) {
        super(new BorderLayout(0, 12));
        this.wall = wall;
        setBackground(BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x44, 0x44, 0x48)),
                BorderFactory.createEmptyBorder(16, 16, 16, 16)));
        addMouseListener(new MouseAdapter() {}); // don't let clicks fall through to the wall

        JLabel title = new JLabel(hidden.isEmpty() ? "No hidden items" : "Hidden items (" + hidden.size() + ")");
        title.setForeground(FG);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JLabel hint = new JLabel("Double-click or Enter to show  ·  Ctrl+A selects all  ·  Esc closes");
        hint.setForeground(DIM);
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setOpaque(false);
        header.add(title, BorderLayout.NORTH);
        header.add(hint, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        DefaultListModel<MediaItem> model = new DefaultListModel<>();
        model.addAll(hidden);
        list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        list.setVisibleRowCount(-1);
        list.setFixedCellWidth(THUMB + 24);
        list.setFixedCellHeight(THUMB + 40);
        list.setBackground(LIST_BG);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(l, value, index, selected, focused);
                MediaItem it = (MediaItem) value;
                setIcon(thumbs.computeIfAbsent(it, i -> new ImageIcon(i.thumbnail(THUMB))));
                setText(it.path.getFileName().toString());
                setToolTipText(wall.displayName(it));
                setHorizontalAlignment(SwingConstants.CENTER);
                setHorizontalTextPosition(SwingConstants.CENTER);
                setVerticalTextPosition(SwingConstants.BOTTOM);
                setBackground(selected ? SELECTED : LIST_BG);
                setForeground(selected ? Color.WHITE : FG);
                setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
                return this;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && list.locationToIndex(e.getPoint()) >= 0) showSelected();
            }
        });
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "show");
        list.getActionMap().put("show", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { showSelected(); }
        });
        if (!hidden.isEmpty()) list.setSelectedIndex(0);

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(LIST_BG);
        scroll.getVerticalScrollBar().setUnitIncrement(THUMB / 3);
        add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(button("Show selected", this::showSelected, !hidden.isEmpty()));
        buttons.add(button("Show all", () -> wall.unhide(new ArrayList<>(hidden)), !hidden.isEmpty()));
        buttons.add(button("Close", wall::closeOverlay, true));
        add(buttons, BorderLayout.SOUTH);
    }

    /** Gives the list keyboard focus once the panel is on screen. */
    void focusList() {
        list.requestFocusInWindow();
    }

    Dimension preferredFor(Dimension screen) {
        return new Dimension(Math.min(1100, screen.width * 7 / 10), Math.min(820, screen.height * 3 / 4));
    }

    private void showSelected() {
        List<MediaItem> sel = list.getSelectedValuesList();
        if (!sel.isEmpty()) wall.unhide(Collections.unmodifiableList(sel));
    }

    private static JButton button(String text, Runnable action, boolean enabled) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        b.setEnabled(enabled);
        b.setFocusPainted(false);
        b.setBackground(BUTTON);
        b.setForeground(FG);
        b.setOpaque(true);
        b.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        return b;
    }
}
