package com.k3jdr.mx.dbml;

import java.util.List;

import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Menu;

/**
 * The app around a {@link DbmlCanvas} (DBMLCanvasView.swift's DBMLCanvasHost): told of each change (for undo,
 * saving), and adding its own commands. Everything has a default but the Schema menu's export.
 */
public interface DbmlCanvasHost {
    /** An edit finished, as one step called {@code name}; {@code old} is the diagram before it. */
    default void didChange(DbmlCanvas canvas, DbmlDiagram old, String name) {}

    /** The diagram changes while something is dragged (the host may resize around it). */
    default void didChangeLive(DbmlCanvas canvas) {}

    default void selectionDidChange(DbmlCanvas canvas) {}

    /** The zoom changed on the canvas itself (Ctrl + mouse wheel). */
    default void zoomDidChange(DbmlCanvas canvas) {}

    /** A click on the canvas. */
    default void wasClicked(DbmlCanvas canvas) {}

    /** The app's own commands for the right-click menu (added after the canvas's, after a separator). */
    default void addMenuItems(DbmlCanvas canvas, Menu menu, DbmlPoint point, DbmlHit hit) {}

    /** The Schema menu's export: its title ("Export Schema…", "Export Schema to a New Page"); null for none. */
    default String schemaExportTitle() { return null; }

    default void exportSchema(DbmlCanvas canvas, DbmlDiagram part, String schema) {}

    /**
     * Copy Schema: the app may add its own types to what goes on the clipboard (the DBML as text and a picture are
     * already in {@code data} / {@code transfers}).
     */
    default void copySchema(DbmlCanvas canvas, DbmlDiagram part, String schema, List<Object> data, List<Transfer> transfers) {}

    /** Double-click a table or line: its DBML (where the app shows DBML). */
    default void showSourceLine(DbmlCanvas canvas, int line) {}

    /** The diagram's name, for a schema cut out of it. */
    default String diagramTitle() { return null; }

    /** The grid dragged tables and notes snap to right now (null: none — no grid, or snapping is off or held off). */
    default DbmlSnapGrid snapGrid(DbmlCanvas canvas) { return null; }
}
