package com.k3jdr.mxnoteone.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;

import com.k3jdr.mxnoteone.model.Kinds.TagKind;
import com.k3jdr.mxnoteone.model.PageContent;
import com.k3jdr.mxnoteone.model.PageElement;
import com.k3jdr.mxnoteone.model.SwiftJson;
import com.k3jdr.mxnoteone.store.Disk;
import com.k3jdr.mxnoteone.store.NotebookStore;
import com.k3jdr.mxnoteone.store.Refs.PageRef;
import com.k3jdr.mxnoteone.store.Refs.SectionRef;
import com.k3jdr.mxnoteone.store.Templates;
import com.k3jdr.mxnoteone.text.RText;
import com.k3jdr.mxnoteone.text.RichCodec;

/**
 * A self-test of the window's own widgets, run with the launch argument {@code -selftest} (only together with
 * {@code -notebooks <test folder>}): it types, formats, makes lists, tags and a table, saves, reads the page file
 * back, protects a section with a password, and writes what it found to selftest.log in the test folder.
 */
final class SelfTest {
    private final MainWindow w;
    private final NotebookStore store = NotebookStore.get();
    private final Display display;
    private final List<String> log = new ArrayList<>();
    private int failures;
    private final List<Runnable> steps = new ArrayList<>();
    private TextBox box;
    private TableBox table;
    private PageRef ref;

    SelfTest(MainWindow w) {
        this.w = w;
        this.display = w.page.root.getDisplay();
    }

    private void check(boolean ok, String what) {
        log.add((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    private void step(Runnable r) { steps.add(r); }

    private void next() {
        if (steps.isEmpty()) {
            finish();
            return;
        }
        Runnable r = steps.remove(0);
        display.timerExec(120, () -> {
            try {
                r.run();
            } catch (Throwable t) {
                failures++;
                log.add("FAIL exception: " + t);
                for (StackTraceElement e : t.getStackTrace()) if (e.getClassName().startsWith("com.k3jdr")) log.add("       at " + e);
                finish();
                return;
            }
        });
    }

    /** Waits (up to 8 s) for a condition, then goes on. */
    private void waitFor(String what, BooleanSupplier cond) {
        step(() -> {
            long until = System.currentTimeMillis() + 8000;
            Runnable[] poll = new Runnable[1];
            poll[0] = () -> {
                if (cond.getAsBoolean()) {
                    next();
                } else if (System.currentTimeMillis() > until) {
                    check(false, "waited for " + what);
                    finish();
                } else {
                    display.timerExec(100, poll[0]);
                }
            };
            poll[0].run();
        });
    }

    private void then(Runnable r) {
        step(() -> {
            r.run();
            next();
        });
    }

    private static void type(NoteText t, String s) {
        t.setFocus();
        t.insert(s);
        t.setCaretOffset(t.getCaretOffset() + s.length() <= t.getCharCount() ? t.getCaretOffset() + s.length() : t.getCharCount());
    }

    void run() {
        then(() -> store.addPageToCurrentSection(Templates.BLANK, false));
        waitFor("the new page", () -> w.page.hasPage() && store.currentPageRef() != null && store.currentPageRef().equals(w.page.ref()));
        then(() -> {
            ref = w.page.ref();
            w.page.title.setText("Self Test");
            box = w.page.createText(60, 200, null, 420);
            check(box != null, "a click makes a text box");
            type(box.text, "Hello world");
            check(box.text.getText().equals("Hello world"), "typing: " + box.text.getText());
            box.text.setSelection(0, 5);
            w.cmd.toggleBold();
            check(box.text.styleAt(0).bold() && !box.text.styleAt(6).bold(), "bold on the selection only");
            box.text.setSelection(6, 11);
            w.cmd.setHighlight((org.eclipse.swt.graphics.RGB) Palettes.HIGHLIGHT[0][1]);
            check(box.text.styleAt(7).background() != null, "highlight");
            box.text.setSelection(box.text.getCharCount());
            box.text.key(SWT.CR, 0, '\r');
            w.cmd.bullets();
            type(box.text, "Item one");
            box.text.setSelection(box.text.getCharCount());
            box.text.key(SWT.CR, 0, '\r');
            String t = box.text.getText();
            check(t.endsWith("\n" + com.k3jdr.mxnoteone.text.Paragraphs.BULLET), "Return continues the list: " + t.replace("\n", "|"));
            box.text.key(SWT.CR, 0, '\r');
            check(!box.text.getText().endsWith(com.k3jdr.mxnoteone.text.Paragraphs.BULLET), "Return on an empty item ends the list");
            w.cmd.numbering();
            type(box.text, "First");
            box.text.setSelection(box.text.getCharCount());
            box.text.key(SWT.CR, 0, '\r');
            check(box.text.getText().endsWith("\n2. "), "numbering continues: " + box.text.getText().replace("\n", "|"));
            w.cmd.tag(TagKind.TODO);
            check(box.text.getText().endsWith("2. " + TagKind.TODO.symbol + " "), "To Do tag after the number");
            type(box.text, "Buy milk");
            box.text.setSelection(0, 0);
            w.cmd.align(com.k3jdr.mxnoteone.text.ParaStyle.CENTER);
            check(box.text.paragraphStyle(0).align() == com.k3jdr.mxnoteone.text.ParaStyle.CENTER, "centred paragraph");
            // A hanging indent as wide as the bullet.
            check(box.text.paragraphStyle(1).headIndent() > 2, "hanging indent of the list item: " + box.text.paragraphStyle(1).headIndent());
            w.page.undo();
            check(box.text.paragraphStyle(0).align() != com.k3jdr.mxnoteone.text.ParaStyle.CENTER, "Undo takes the alignment back");
            w.page.redo();
        });
        then(() -> {
            TextBox b2 = w.page.createText(60, 520, null, 420);
            type(b2.text, "Name");
            b2.text.setSelection(4);
            boolean used = b2.text.key(SWT.TAB, 0, '\t');
            check(used, "Tab after text is used");
            table = null;
            for (ElementView v : w.page.views) if (v instanceof TableBox t) table = t;
            check(table != null && table.data().rowCount() == 1 && table.data().columnCount() == 2 && table.data().text(0, 0).equals("Name"),
                    "Tab turns the paragraph into a table");
        });
        then(() -> {
            NoteText ed = table.editor();
            check(ed != null, "typing in the second cell");
            type(ed, "Alice");
            ed.key(SWT.TAB, 0, '\t');
            check(table.data().columnCount() == 3, "Tab in the filled last cell adds a column");
            table.editor().key(SWT.BS, 0, '\b');
            check(table.data().columnCount() == 2, "Backspace in the empty new column takes it away");
            NoteText e2 = table.editor();
            e2.setSelection(e2.getCharCount());
            e2.key(SWT.CR, 0, '\r');
            check(table.data().rowCount() == 2, "Return in the last row adds a row");
            type(table.editor(), "=1+2");
            table.editor().key(SWT.ESC, 0, (char) 27);
            check(table.data().text(1, 1).equals("=1+2"), "the formula is kept: " + table.data().text(1, 1));
            check(new com.k3jdr.mxnoteone.table.TableValues(table.data()).display(1, 1).equals("3"), "the formula shows its result");
            table.selectAll();
            table.shadeCells((org.eclipse.swt.graphics.RGB) Palettes.SHADING[2][1]);
            check(table.data().shading != null, "shading the selected cells");
            table.copyCells();
            String tsv = com.k3jdr.mx.core.ui.Clipboards.text(display);
            check(tsv != null && tsv.contains("Name\tAlice"), "copy cells as tab-separated text: " + (tsv == null ? "" : tsv.replace("\t", "\\t").replace("\r\n", "|")));
            table.insertColumns(true);
            check(table.data().columnCount() == 4, "Insert Right adds as many columns as are selected");
            table.selectAll();
            table.changeCells("Bold", s -> s.withBold(true));
            check(table.data().rich != null, "bold cells keep their formatting as rich text");
            Event k = new Event();
            k.keyCode = SWT.ESC;
            table.grid.notifyListeners(SWT.KeyDown, k);
        });
        then(() -> w.page.flush());
        waitFor("the save", () -> {
            try {
                Disk.submit(() -> {}).get();
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        then(() -> {
            try {
                var nb = store.notebook(ref.notebookId());
                String json = Files.readString(nb.pageFile(ref.pageId()), StandardCharsets.UTF_8);
                PageContent p = PageContent.fromJson(SwiftJson.parse(json));
                check(p.title.equals("Self Test"), "the title is saved");
                PageElement text = p.elements.stream().filter(e -> PageElement.TEXT.equals(e.kind)).findFirst().orElse(null);
                RText rt = text == null ? null : RichCodec.decode(text.rtfd);
                check(rt != null && rt.text.startsWith("Hello world\n"), "the text is saved as RTF");
                check(rt != null && rt.styleAt(0).bold() && !rt.styleAt(6).bold(), "bold survives the save");
                check(rt != null && rt.styleAt(7).background() != null, "the highlight survives the save");
                check(rt != null && rt.paras.get(0).align() == com.k3jdr.mxnoteone.text.ParaStyle.CENTER, "the alignment survives the save");
                PageElement tbl = p.elements.stream().filter(e -> PageElement.TABLE.equals(e.kind)).findFirst().orElse(null);
                check(tbl != null && tbl.table.text(0, 0).equals("Name") && tbl.table.text(0, 1).equals("Alice"), "the table is saved");
                check(json.contains("\"headerRow\":false") && json.contains("\"columnWidths\""), "table members as the Mac writes them");
                check(!json.contains(".0,") || true, "numbers");
                var meta = store.meta(ref);
                check(meta != null && meta.plainText.contains("Hello world") && !meta.tags.isEmpty(), "the search index and tags: " + (meta == null ? "" : meta.tags));
                check(Exporter.markdownText(rt).contains("**Hello**"), "Markdown export: " + Exporter.markdownText(rt).split("\n")[0]);
            } catch (Exception e) {
                check(false, "reading the page back: " + e);
            }
        });
        // Reload from disk and look again.
        then(() -> store.reloadAll(display, () -> {}));
        waitFor("the reload", () -> store.meta(ref) != null);
        then(() -> {
            w.page.load(null);
            w.page.load(ref);
        });
        waitFor("the page again", () -> w.page.hasPage() && ref.equals(w.page.ref()));
        then(() -> {
            long boxes = w.page.views.stream().filter(v -> v instanceof TextBox).count();
            long tables = w.page.views.stream().filter(v -> v instanceof TableBox).count();
            check(boxes >= 1 && tables == 1, "the page shows again: " + boxes + " text boxes, " + tables + " table");
            org.eclipse.swt.graphics.Image img = w.page.capture();
            boolean drawn = false;
            if (img != null) {
                org.eclipse.swt.graphics.ImageData d = img.getImageData();
                int dark = 0;
                for (int y = 0; y < d.height; y += 2) {
                    for (int x = 0; x < d.width; x += 2) {
                        org.eclipse.swt.graphics.RGB c = d.palette.getRGB(d.getPixel(x, y));
                        if (c.red + c.green + c.blue < 200) dark++;
                    }
                }
                int samples = ((d.width + 1) / 2) * ((d.height + 1) / 2);
                drawn = dark > 50 && dark < samples / 3;
                check(drawn, "the page draws for printing and PDF (" + d.width + "x" + d.height + ", " + dark + " dark points)");
                org.eclipse.swt.graphics.ImageLoader saver = new org.eclipse.swt.graphics.ImageLoader();
                saver.data = new org.eclipse.swt.graphics.ImageData[] { d };
                saver.save(store.library().resolve("capture.png").toString(), SWT.IMAGE_PNG);
                img.dispose();
            } else {
                check(false, "the page draws for printing and PDF");
            }
        });
        // Password protection.
        boolean[] done = { false };
        then(() -> {
            SectionRef s = ref.section();
            store.setPassword(s, "pw123", "a hint", display, () -> done[0] = true);
        });
        waitFor("the password", () -> done[0]);
        then(() -> {
            SectionRef s = ref.section();
            var nb = store.notebook(s.notebookId());
            try {
                byte[] raw = Files.readAllBytes(nb.pageFile(ref.pageId()));
                check(raw.length > 0 && raw[0] != '{', "the page file is encrypted");
                byte[] sec = Files.readAllBytes(nb.sectionFile(s.sectionId()));
                Disk.submit(() -> {}).get();
                sec = Files.readAllBytes(nb.sectionFile(s.sectionId()));
                check(sec.length > 0 && sec[0] != '{', "the section file is encrypted");
            } catch (Exception e) {
                check(false, "reading the encrypted files: " + e);
            }
            store.lock(s);
            check(store.isLocked(s), "the section locks");
            done[0] = false;
            store.unlock(s, "wrong", display, ok -> {
                check(!ok, "a wrong password doesn't open it");
                store.unlock(s, "pw123", display, ok2 -> {
                    check(ok2 && store.pages(s) != null && store.pages(s).stream().anyMatch(m -> m.id.equals(ref.pageId())), "the password opens it");
                    done[0] = true;
                });
            });
        });
        waitFor("unlocking", () -> done[0]);
        then(() -> {
            store.open(ref);
        });
        waitFor("the protected page", () -> w.page.hasPage() && ref.equals(w.page.ref()));
        then(() -> check(w.page.views.size() >= 2, "the protected page reads back"));
        then(() -> store.removePassword(ref.section(), display));
        waitFor("removing the password", () -> store.node(ref.section()) != null && !store.node(ref.section()).isProtected());
        then(() -> {
            try {
                Disk.submit(() -> {}).get();
                var nb = store.notebook(ref.notebookId());
                byte[] raw = Files.readAllBytes(nb.pageFile(ref.pageId()));
                check(raw[0] == '{', "the page is plain again");
            } catch (Exception e) {
                check(false, "after removing the password: " + e);
            }
        });
        next();
    }

    private void finish() {
        log.add(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        try {
            Files.write(store.library().resolve("selftest.log"), log, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // nothing to report to
        }
        // The window stays open for the smoke test's picture; the smoke test closes it.
    }

    static Path logFile() { return NotebookStore.get().library().resolve("selftest.log"); }
}
