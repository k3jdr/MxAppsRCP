package com.k3jdr.mxdatahub;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;

import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Rows.NameValue;

/**
 * Sections of facts as one read-only text (the Mac's RichLines in a SelectableText), so a drag selects across
 * sections and rows: headings, name / value rows, columns separated by tabs (they copy tab-separated) and notes.
 */
final class RichLines {
    /** One cell of a column row: its text, bold or not, and its colour (null for the text colour). */
    record Cell(String text, boolean bold, Color color) {}

    private final StringBuilder text = new StringBuilder();
    private final List<StyleRange> ranges = new ArrayList<>();
    private final List<int[]> tabs = new ArrayList<>();   // line, stops…
    private final List<int[]> indents = new ArrayList<>(); // line, indent
    private int line;

    RichLines heading(String title) {
        if (text.length() > 0) add("\n", null, false, null);
        add(title + "\n", Ui.subheading(), false, null);
        return this;
    }

    /** Name / value rows: names in grey, values after a tab; wrapped values continue under the values. */
    RichLines facts(List<NameValue> rows, int nameWidth) {
        for (NameValue nv : rows) {
            tabs.add(new int[] { line, nameWidth });
            indents.add(new int[] { line, nameWidth });
            add(nv.name() + "\t", null, false, Ui.SECONDARY);
            add(nv.value() + "\n", null, false, null);
        }
        return this;
    }

    /** Rows of cells at the given tab stops (pixels). */
    RichLines columns(List<List<Cell>> rows, int... stops) {
        for (List<Cell> row : rows) {
            int[] t = new int[stops.length + 1];
            t[0] = line;
            System.arraycopy(stops, 0, t, 1, stops.length);
            tabs.add(t);
            for (int i = 0; i < row.size(); i++) {
                Cell c = row.get(i);
                add(c.text() + (i < row.size() - 1 ? "\t" : "\n"), null, c.bold(), c.color());
            }
        }
        return this;
    }

    RichLines note(String note) {
        add(note + "\n", null, false, Ui.SECONDARY);
        return this;
    }

    private void add(String s, Font font, boolean bold, Color color) {
        int start = text.length();
        text.append(s);
        if (font != null || bold || color != null) {
            StyleRange r = new StyleRange();
            r.start = start;
            r.length = s.length();
            r.font = font;
            if (bold && font == null) r.fontStyle = SWT.BOLD;
            r.foreground = color;
            ranges.add(r);
        }
        for (char ch : s.toCharArray()) if (ch == '\n') line++;
    }

    /** Puts the lines into {@code st}. */
    void applyTo(StyledText st) {
        st.setText(text.toString());
        st.setStyleRanges(ranges.toArray(StyleRange[]::new));
        int count = st.getLineCount();
        for (int[] t : tabs) {
            if (t[0] < count) st.setLineTabStops(t[0], 1, java.util.Arrays.copyOfRange(t, 1, t.length));
        }
        for (int[] i : indents) {
            if (i[0] < count) st.setLineWrapIndent(i[0], 1, i[1]);
        }
    }
}
