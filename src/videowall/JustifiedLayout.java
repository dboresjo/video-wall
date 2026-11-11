// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Dan Boresjö
package videowall;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Packs items edge-to-edge into rows that span the full screen width (like a photo grid),
 * choosing the row height so that everything fits on the screen.
 */
final class JustifiedLayout {

    private JustifiedLayout() {}

    static void apply(List<MediaItem> items, Rectangle area) {
        if (items.isEmpty()) return;
        double lo = 8, hi = area.height;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            if (totalHeight(rows(items, mid, area.width)) <= area.height) lo = mid; else hi = mid;
        }
        List<Row> rows = rows(items, lo, area.width);
        double total = totalHeight(rows);
        // Should already fit; shrink uniformly if the search bottomed out.
        double fit = Math.min(1.0, area.height / total);

        double y = area.y + (area.height - total * fit) / 2;
        for (Row row : rows) {
            double h = row.height * fit;
            double rowWidth = row.aspectSum * h;
            double x = area.x + (area.width - rowWidth) / 2;
            int top = (int) Math.round(y), bottom = (int) Math.round(y + h);
            for (MediaItem it : row.items) {
                double w = it.aspect() * h;
                int left = (int) Math.round(x), right = (int) Math.round(x + w);
                it.bounds.setBounds(left, top, Math.max(1, right - left), Math.max(1, bottom - top));
                x += w;
            }
            y += h;
        }
    }

    private static final class Row {
        final List<MediaItem> items = new ArrayList<>();
        double aspectSum;
        double height;
    }

    /** Greedy rows: close a row when adding the next item would move its height further from {@code target}. */
    private static List<Row> rows(List<MediaItem> items, double target, int width) {
        List<Row> rows = new ArrayList<>();
        Row row = new Row();
        for (MediaItem it : items) {
            double a = it.aspect();
            if (!row.items.isEmpty()) {
                double without = width / row.aspectSum;
                double with = width / (row.aspectSum + a);
                if (without <= target || (with < target && without - target <= target - with)) {
                    row.height = without;
                    rows.add(row);
                    row = new Row();
                }
            }
            row.items.add(it);
            row.aspectSum += a;
        }
        // The last row keeps the target height (unless that would overflow the width).
        row.height = Math.min(target, width / row.aspectSum);
        rows.add(row);
        return rows;
    }

    private static double totalHeight(List<Row> rows) {
        double t = 0;
        for (Row r : rows) t += r.height;
        return t;
    }
}
