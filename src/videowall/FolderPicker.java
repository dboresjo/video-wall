// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Overlay for + : picks the media folder of a new configuration. The file chooser is embedded
 * in the fullscreen window rather than opened as a dialog, which fullscreen windows can hide.
 */
@SuppressWarnings("serial")
final class FolderPicker extends JPanel {

    private static final Color BG = new Color(0x1c, 0x1c, 0x1e);
    private static final Color FG = new Color(0xe6, 0xe6, 0xe6);
    private static final Color DIM = new Color(0x9a, 0x9a, 0xa0);

    private final JFileChooser chooser;

    /**
     * @param start      folder selected initially (the home folder if null)
     * @param firstSetup label of a screen setup seen for the first time, or null when adding a
     *                   configuration with +
     */
    FolderPicker(Wall wall, Path start, String firstSetup) {
        super(new BorderLayout(0, 12));
        setBackground(BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x44, 0x44, 0x48)),
                BorderFactory.createEmptyBorder(16, 16, 16, 16)));
        addMouseListener(new MouseAdapter() {}); // don't let clicks fall through to the wall

        JLabel title = new JLabel(firstSetup == null
                ? "New configuration: choose its folder"
                : "New screen setup (" + firstSetup + "): choose a folder to show");
        title.setForeground(FG);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JLabel hint = new JLabel("The images and videos in the folder and its subfolders are tiled in the"
                + " default layout.  " + (firstSetup == null ? "Esc cancels." : "Esc quits."));
        hint.setForeground(DIM);
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setOpaque(false);
        header.add(title, BorderLayout.NORTH);
        header.add(hint, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setApproveButtonText("Use this folder");
        chooser.setApproveButtonToolTipText("Create the configuration from the selected folder");
        if (start != null && Files.isDirectory(start)) {
            Path parent = start.getParent();
            chooser.setCurrentDirectory((parent != null ? parent : start).toFile());
            chooser.setSelectedFile(start.toFile());
        }
        chooser.addActionListener(e -> {
            if (JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand())) {
                File f = chooser.getSelectedFile();
                if (f == null) f = chooser.getCurrentDirectory();
                wall.folderChosen(f.toPath());
            } else {
                wall.folderCancelled();
            }
        });
        add(chooser, BorderLayout.CENTER);
    }

    Dimension preferredFor(Dimension screen) {
        return new Dimension(Math.min(960, screen.width * 7 / 10), Math.min(640, screen.height * 7 / 10));
    }

    /** Puts the keyboard focus in the folder name field once the panel is on screen. */
    void focusChooser() {
        Component field = find(chooser, JTextField.class);
        (field != null ? field : chooser).requestFocusInWindow();
    }

    private static Component find(Container c, Class<?> type) {
        for (Component child : c.getComponents()) {
            if (type.isInstance(child)) return child;
            if (child instanceof Container k) {
                Component f = find(k, type);
                if (f != null) return f;
            }
        }
        return null;
    }
}
