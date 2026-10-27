package com.k3jdr.mx.core.ui;

/** What Quick Help says about one thing: a title, a sentence or two, and the shortcut (null for none). */
public record QuickHelpItem(String title, String detail, String shortcut) {
    public QuickHelpItem(String title, String detail) {
        this(title, detail, null);
    }
}
