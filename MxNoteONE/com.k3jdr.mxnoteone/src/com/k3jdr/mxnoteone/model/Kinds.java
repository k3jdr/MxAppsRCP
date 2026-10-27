package com.k3jdr.mxnoteone.model;

import java.util.Locale;
import java.util.UUID;

import org.eclipse.swt.graphics.RGB;

/**
 * The notebook format's small value types (Models.swift): colours of notebooks, sections and pages, rule lines and
 * tags, with the raw values the Mac app writes in its JSON files.
 */
public final class Kinds {
    private Kinds() {}

    /** A new ID as Swift's UUID().uuidString writes it (upper case). */
    public static String newId() {
        return UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
    }

    static RGB rgb(double r, double g, double b) {
        return new RGB((int) Math.round(r * 255), (int) Math.round(g * 255), (int) Math.round(b * 255));
    }

    static String capitalized(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Colour of a notebook or section tab. */
    public enum NoteColor {
        BLUE(0.22, 0.46, 0.84), TEAL(0.10, 0.60, 0.62), GREEN(0.30, 0.62, 0.27), YELLOW(0.88, 0.70, 0.10),
        ORANGE(0.93, 0.48, 0.16), RED(0.84, 0.24, 0.24), PINK(0.86, 0.36, 0.62), PURPLE(0.47, 0.30, 0.70),
        INDIGO(0.30, 0.32, 0.70), GRAY(0.50, 0.52, 0.56);

        public final RGB rgb;

        NoteColor(double r, double g, double b) { rgb = rgb(r, g, b); }

        public String key() { return name().toLowerCase(Locale.ROOT); }
        public String title() { return capitalized(key()); }

        public static NoteColor of(String key) {
            for (NoteColor c : values()) if (c.key().equals(key)) return c;
            return BLUE;
        }
    }

    /** Background colour of a page. */
    public enum PageColor {
        NONE(1, 1, 1), CREAM(1.00, 0.98, 0.92), YELLOW(1.00, 0.97, 0.78), GREEN(0.90, 0.97, 0.88), BLUE(0.88, 0.94, 1.00),
        PURPLE(0.94, 0.91, 0.99), PINK(1.00, 0.91, 0.94), ORANGE(1.00, 0.93, 0.86), GRAY(0.94, 0.94, 0.95);

        public final RGB rgb;

        PageColor(double r, double g, double b) { rgb = rgb(r, g, b); }

        public String key() { return name().toLowerCase(Locale.ROOT); }
        public String title() { return this == NONE ? "No Color" : capitalized(key()); }

        public static PageColor of(String key) {
            for (PageColor c : values()) if (c.key().equals(key)) return c;
            return NONE;
        }
    }

    /** Rule lines or a grid on a page. */
    public enum RuleLines {
        NONE("none", "None", 0), NARROW("narrow", "Narrow Ruled", 18), COLLEGE("college", "College Ruled", 24),
        WIDE("wide", "Wide Ruled", 32), SMALL_GRID("smallGrid", "Small Grid", 12), MEDIUM_GRID("mediumGrid", "Medium Grid", 24),
        LARGE_GRID("largeGrid", "Large Grid", 40);

        public final String key, title;
        /** Spacing in points; 0 for none. */
        public final int spacing;

        RuleLines(String key, String title, int spacing) {
            this.key = key;
            this.title = title;
            this.spacing = spacing;
        }

        public boolean isGrid() { return this == SMALL_GRID || this == MEDIUM_GRID || this == LARGE_GRID; }

        public static RuleLines of(String key) {
            for (RuleLines r : values()) if (r.key.equals(key)) return r;
            return NONE;
        }
    }

    /** Note tags, written as a symbol at the start of a paragraph (Ctrl+1 – Ctrl+9). */
    public enum TagKind {
        TODO("todo", "To Do", "☐"), IMPORTANT("important", "Important", "⭐"), QUESTION("question", "Question", "❓"),
        CRITICAL("critical", "Critical", "❗"), IDEA("idea", "Idea", "💡"),
        REMEMBER("remember", "Remember for Later", "📌"), DEFINITION("definition", "Definition", "📖"),
        CONTACT("contact", "Phone Number", "📞"), DISCUSS("discuss", "Discuss with Team", "💬");

        /** The checked To Do box. */
        public static final String TODO_DONE = "☑";

        public final String key, title, symbol;

        TagKind(String key, String title, String symbol) {
            this.key = key;
            this.title = title;
            this.symbol = symbol;
        }

        /** 1 – 9, the Ctrl+digit shortcut. */
        public int digit() { return ordinal() + 1; }

        public static TagKind of(String key) {
            for (TagKind t : values()) if (t.key.equals(key)) return t;
            return null;
        }
    }
}
