package com.k3jdr.mxacctmgr;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;

import com.k3jdr.mx.core.prefs.Prefs;
import com.k3jdr.mx.core.ui.Ui;

/**
 * File ▸ Settings (PortSettings.swift): how much teaching shows (Guidance), what a new checking entry starts with
 * (Banking), and the size of the headings and of the numbers and tables (Text). Kept in the app's preferences.
 */
public final class Settings {
    private Settings() {}

    public static final Prefs PREFS = Prefs.of("com.k3jdr.mxacctmgr");

    public enum Guidance {
        FULL("Full", "Everything: each page's \"Why this step\" lesson and the line under its title, and the lesson-only pages."),
        BRIEF("Brief", "Hides the \"Why this step\" lessons on every page."),
        MINIMAL("Minimal", "Also hides the line under each page's title and the lesson-only pages — Welcome, Choose Your Path, Why Optimize?, Why Attribution? and Active vs Passive — in the sidebar, the Guide menu and Previous / Next Step.");

        public final String title, detail;

        Guidance(String title, String detail) {
            this.title = title;
            this.detail = detail;
        }
    }

    public static final String CHECK_EMPTY = "Empty", CHECK_NEXT = "The register's next number";
    public static final String DATE_PREVIOUS = "The date of the entry entered before", DATE_TODAY = "Today";

    private static final List<Runnable> LISTENERS = new ArrayList<>();

    public static void addListener(Runnable r) { LISTENERS.add(r); }

    private static void changed() { LISTENERS.forEach(Runnable::run); }

    public static Guidance guidance() {
        try {
            return Guidance.valueOf(PREFS.get("guidance", "FULL"));
        } catch (IllegalArgumentException e) {
            return Guidance.FULL;
        }
    }

    public static void setGuidance(Guidance g) {
        PREFS.put("guidance", g.name());
        changed();
    }

    public static boolean showsLessons() { return guidance() == Guidance.FULL; }
    public static boolean showsIntroductions() { return guidance() != Guidance.MINIMAL; }
    public static boolean shows(Screen s) { return guidance() != Guidance.MINIMAL || !s.lessonOnly(); }

    // Banking ▸ New entry
    public static String newEntryKind() { return PREFS.get("newEntryKind", "debit"); }
    public static void setNewEntryKind(String k) { PREFS.put("newEntryKind", k); changed(); }
    public static String newEntryCheckNumber() { return PREFS.get("newEntryCheckNumber", CHECK_EMPTY); }
    public static void setNewEntryCheckNumber(String v) { PREFS.put("newEntryCheckNumber", v); changed(); }
    public static String newEntryDate() { return PREFS.get("newEntryDate", DATE_PREVIOUS); }
    public static void setNewEntryDate(String v) { PREFS.put("newEntryDate", v); changed(); }

    // Text: a point size step for the headings, another for the numbers and tables (-4 … +8).
    public static int headingDelta() { return PREFS.getInt("headingDelta", 0); }
    public static int numbersDelta() { return PREFS.getInt("numbersDelta", 0); }

    public static void setHeadingDelta(int d) { PREFS.putInt("headingDelta", Math.max(-4, Math.min(8, d))); changed(); }
    public static void setNumbersDelta(int d) { PREFS.putInt("numbersDelta", Math.max(-4, Math.min(8, d))); changed(); }

    /** The page title's font. */
    public static Font pageTitle() { return Ui.font(6 + headingDelta(), SWT.BOLD); }
    /** The line under the page title. */
    public static Font pageSubtitle() { return Ui.font(1 + headingDelta(), SWT.NORMAL); }
    /** A section's heading (a box, a lesson card). */
    public static Font heading() { return Ui.font(1 + headingDelta(), SWT.BOLD); }
    /** A figure in a tile. */
    public static Font figure() { return Ui.font(5 + headingDelta(), SWT.BOLD); }
    /** Registers and tables. */
    public static Font table() { return Ui.font(numbersDelta(), SWT.NORMAL); }

    /** How many of a table's rows show at once — one choice for every table (RowsStepper.key). */
    public static int tableRows() { return PREFS.getInt("tableRows", 22); }

    private static final List<Runnable> ROWS_LISTENERS = new ArrayList<>();

    /** Told when the Rows choice changes (every table on the page follows it); remove it when the page goes. */
    public static void addRowsListener(Runnable r) { ROWS_LISTENERS.add(r); }
    public static void removeRowsListener(Runnable r) { ROWS_LISTENERS.remove(r); }

    public static void setTableRows(int rows) {
        PREFS.putInt("tableRows", Math.max(6, Math.min(40, rows)));
        List.copyOf(ROWS_LISTENERS).forEach(Runnable::run);
    }
}
