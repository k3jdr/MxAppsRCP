package com.k3jdr.mxdatahub;

import org.eclipse.swt.widgets.Control;

import com.k3jdr.mx.core.ui.QuickHelpItem;

/** One of the window's screens: what MainPart asks of it to show it, follow its selection and go back to it. */
interface Screen {
    Control control();

    /** The screen became visible (load what it shows). */
    default void shown() {}

    /** The connection or the shared lists changed. */
    default void hubChanged() {}

    /** The security, portfolio or benchmark selected (a place for Back and Forward), or null. */
    default Long selectedItem() { return null; }

    /** Selects it again (Back, Forward, a link). */
    default void selectItem(long id) {}

    /** "AAPL Apple Inc." for the history menus, or null when it isn't on screen. */
    default String itemTitle(long id) { return null; }

    /** What Quick Help says about the selection, or null. */
    default QuickHelpItem selectionHelp() { return null; }
}
