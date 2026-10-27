package com.k3jdr.mx.dbml;

/** Helpers for .dbml files as MxDataSQL writes them. */
public final class DbmlFiles {
    private DbmlFiles() {}

    /**
     * Write DBML over an existing arranged diagram keeps its notes and layout (DiagramCommands.swift write()): if
     * {@code oldFileText} has an MxDataSQL layout section, {@code newSource} with that section's notes and layout;
     * otherwise {@code newSource} unchanged. {@code oldFileText} may be null.
     */
    public static String keepLayout(String newSource, String oldFileText) {
        if (oldFileText == null || DbmlFileLayout.split(oldFileText).section() == null) return newSource;
        DbmlDiagram previous = DbmlDiagram.fromFileText(oldFileText);
        previous.source = newSource;
        return previous.fileText();
    }
}
