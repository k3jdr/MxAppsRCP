package com.k3jdr.mxnoteone.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The page's Undo and Redo: typing, formatting, moving, resizing, inserting and deleting containers, and table
 * changes — kept while the page is open, as on the Mac.
 */
public final class UndoStack {
    private record Step(String name, Runnable undo, Runnable redo) {}

    private final Deque<Step> undos = new ArrayDeque<>();
    private final Deque<Step> redos = new ArrayDeque<>();
    private boolean running;

    public void push(String name, Runnable undo, Runnable redo) {
        if (running) return;
        undos.push(new Step(name, undo, redo));
        while (undos.size() > 200) undos.removeLast();
        redos.clear();
    }

    public boolean canUndo() { return !undos.isEmpty(); }
    public boolean canRedo() { return !redos.isEmpty(); }
    public String undoName() { return undos.isEmpty() ? "" : undos.peek().name; }
    public String redoName() { return redos.isEmpty() ? "" : redos.peek().name; }
    String lastName() { return undoName(); }

    public void undo() {
        if (undos.isEmpty()) return;
        Step s = undos.pop();
        running = true;
        try {
            s.undo.run();
        } finally {
            running = false;
        }
        redos.push(s);
    }

    public void redo() {
        if (redos.isEmpty()) return;
        Step s = redos.pop();
        running = true;
        try {
            s.redo.run();
        } finally {
            running = false;
        }
        undos.push(s);
    }

    public void clear() {
        undos.clear();
        redos.clear();
    }
}
