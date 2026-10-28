package com.k3jdr.mxnoteone.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import com.k3jdr.mx.core.ui.EditActions;
import com.k3jdr.mx.core.ui.MenuBarBuilder;
import com.k3jdr.mx.core.ui.MenuBarBuilder.MenuBuilder;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mx.core.ui.SplitPanes;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxnoteone.model.Kinds.PageColor;
import com.k3jdr.mxnoteone.model.Kinds.RuleLines;
import com.k3jdr.mxnoteone.model.Kinds.TagKind;
import com.k3jdr.mxnoteone.model.PageMeta;
import com.k3jdr.mxnoteone.model.SectionNode;
import com.k3jdr.mxnoteone.store.Notebook;
import com.k3jdr.mxnoteone.store.NotebookStore;
import com.k3jdr.mxnoteone.store.Refs.PageRef;
import com.k3jdr.mxnoteone.store.Refs.SectionRef;
import com.k3jdr.mxnoteone.store.Templates;
import com.k3jdr.mxnoteone.text.ParaStyle;
import com.k3jdr.mxnoteone.text.Paragraphs;

/**
 * The window (MainView.swift): Back / Forward and the ribbon on top; the sidebar (with Quick Help at its foot),
 * the page list and the page below; and the menu bar (MainCommands.swift) with the Mac's commands on Windows keys.
 */
public final class MainWindow {
    public final PageView page;
    final SidebarView sidebar;
    final PageListView pages;
    final Ribbon ribbon;
    final Commands cmd;
    private final Shell shell;
    private final NotebookStore store = NotebookStore.get();
    private final Composite main;
    private final StackLayout mainStack = new StackLayout();
    private final SplitPanes split;
    private final SplitPanes inner;
    private final Composite full;
    private boolean fullPage;
    private ToolItem back, forward;

    public MainWindow(Composite parent) {
        shell = parent.getShell();
        parent.setLayout(Ui.grid(1, 0, 0));

        Composite top = new Composite(parent, SWT.NONE);
        top.setLayout(Ui.grid(2, 2, 4));
        top.setLayoutData(Ui.fillH());
        ToolBar nav = new ToolBar(top, SWT.FLAT);
        nav.setLayoutData(new GridData(SWT.BEGINNING, SWT.BEGINNING, false, false));
        back = new ToolItem(nav, SWT.PUSH);
        back.setText("◀");
        back.setToolTipText("Back (Alt+Left or Ctrl+[)");
        forward = new ToolItem(nav, SWT.PUSH);
        forward.setText("▶");
        forward.setToolTipText("Forward (Alt+Right or Ctrl+])");
        ToolItem fullItem = new ToolItem(nav, SWT.PUSH);
        fullItem.setText("⤢");
        fullItem.setToolTipText("Full Page View (F11)");
        back.addListener(SWT.Selection, e -> store.goBack());
        forward.addListener(SWT.Selection, e -> store.goForward());
        fullItem.addListener(SWT.Selection, e -> toggleFullPage());
        QuickHelp.provider(nav, (c, p) -> {
            ToolItem it = nav.getItem(p);
            if (it == back) return new QuickHelpItem("Back", "Returns to the page before, as a browser does. Go ▸ History lists the pages visited.", "Alt+Left, Ctrl+[");
            if (it == forward) return new QuickHelpItem("Forward", "Goes on to the page after (after going back).", "Alt+Right, Ctrl+]");
            if (it == fullItem) return new QuickHelpItem("Full Page View", "Hides the notebook and page lists so the page fills the window; again shows them.", "F11");
            return null;
        });

        main = new Composite(parent, SWT.NONE);
        main.setLayoutData(Ui.fill());
        main.setLayout(mainStack);
        split = new SplitPanes(main, "mxnoteone.sidebar", 170, 380, 236, 500);
        split.leading().setLayout(new FillLayout());
        inner = new SplitPanes(split.trailing(), "mxnoteone.pages", 180, 440, 260, 320);
        split.trailing().setLayout(new FillLayout());
        inner.leading().setLayout(new FillLayout());
        inner.trailing().setLayout(new FillLayout());
        full = new Composite(main, SWT.NONE);
        full.setLayout(new FillLayout());
        mainStack.topControl = split;

        page = new PageView(inner.trailing());
        cmd = new Commands(shell, page);
        ribbon = new Ribbon(top, cmd, page);
        ribbon.folder.setLayoutData(Ui.fillH());
        ribbon.fullPage = this::toggleFullPage;
        ribbon.tagSummary = () -> Windows.tagSummary(shell);
        ribbon.recycleBin = () -> Windows.recycleBin(shell);
        sidebar = new SidebarView(split.leading(), cmd);
        sidebar.showTags = () -> Windows.tagSummary(shell);
        sidebar.showTrash = () -> Windows.recycleBin(shell);
        QuickHelp.get().createDockedPanel(sidebar.root);
        pages = new PageListView(inner.leading(), cmd);
        sidebar.search.addListener(SWT.Modify, e -> pages.search(sidebar.search.getText()));

        page.onFormatting = ribbon::refresh;
        page.onTableActive = () -> ribbon.tableActive(page.activeTable() != null);
        store.flushCanvas = page::flush;
        store.addListener(c -> {
            if (c == NotebookStore.Change.SELECTION) page.load(store.currentPageRef());
            updateTitle();
        });
        store.history.addListener(this::updateNav);
        updateNav();
        QuickHelp.get().setScreen(() -> new QuickHelpItem("MxNoteONE",
                "Notebooks hold sections (the coloured tabs in the sidebar), and sections hold pages. Click anywhere on a page to write; drop pictures and files on it."));
        QuickHelp.get().setSelection(this::describeSelection);
        menus();
        keys();
    }

    /** The notebooks are loaded: show them. */
    public void ready() {
        sidebar.rebuild();
        pages.refresh();
        page.load(store.currentPageRef());
        updateTitle();
    }

    /** Runs the self-test (the -selftest launch argument, with -notebooks). */
    public void selfTest() {
        new SelfTest(this).run();
    }

    private void updateTitle() {
        if (shell.isDisposed()) return;
        SectionRef s = store.selectedSection();
        String title = store.windowTitle();
        String loc = s == null ? "" : store.location(s);
        String t = loc.isEmpty() ? title + " — MxNoteONE" : title + " — " + loc;
        if (!t.equals(shell.getText())) shell.setText(t);
    }

    private void updateNav() {
        if (back.isDisposed()) return;
        back.setEnabled(store.history.canGoBack());
        forward.setEnabled(store.history.canGoForward());
    }

    private QuickHelpItem describeSelection() {
        PageRef ref = store.currentPageRef();
        PageMeta m = store.meta(ref);
        if (m == null) return null;
        return new QuickHelpItem(m.displayTitle(), "The page in " + store.location(ref.section()) + ". Click anywhere on it to write; the Home tab formats text.");
    }

    void toggleFullPage() {
        fullPage = !fullPage;
        Control c = page.control();
        c.setParent(fullPage ? full : inner.trailing());
        mainStack.topControl = fullPage ? full : split;
        inner.trailing().layout(true, true);
        full.layout(true, true);
        main.layout(true, true);
        page.updateSize();
    }

    // MARK: Keys the menus don't take

    private void keys() {
        Display d = shell.getDisplay();
        Listener filter = (Event e) -> {
            if (e.widget instanceof Control c && c.getShell() != shell) return;
            if (e.type == SWT.KeyDown && (e.stateMask & SWT.MODIFIER_MASK) == SWT.ALT) {
                if (e.keyCode == SWT.ARROW_LEFT) {
                    e.doit = false;
                    store.goBack();
                } else if (e.keyCode == SWT.ARROW_RIGHT) {
                    e.doit = false;
                    store.goForward();
                }
            }
            if (e.type == SWT.MouseDown && (e.button == 4 || e.button == 5)) {
                if (e.button == 4) store.goBack();
                else store.goForward();
            }
        };
        d.addFilter(SWT.KeyDown, filter);
        d.addFilter(SWT.MouseDown, filter);
        shell.addListener(SWT.Dispose, e -> {
            d.removeFilter(SWT.KeyDown, filter);
            d.removeFilter(SWT.MouseDown, filter);
        });
    }

    // MARK: Menus

    private PageRef current() { return store.currentPageRef(); }

    private boolean hasPage() { return store.currentPageRef() != null; }

    private void menus() {
        MenuBarBuilder mb = new MenuBarBuilder(shell);
        int C = SWT.MOD1, S = SWT.MOD2, A = SWT.MOD3;

        MenuBuilder file = mb.menu("&File");
        file.item("New &Page", C | 'N', () -> cmd.newPage(false));
        file.item("New &Subpage", C | A | 'N', () -> cmd.newPage(true), this::hasPage);
        file.item("New &Quick Note", C | A | S | 'N', store::newQuickNote);
        MenuBuilder tpl = file.submenu("New Page from &Template");
        for (Templates t : Templates.choices()) tpl.item(t.title, () -> cmd.newPageFromTemplate(t));
        file.separator();
        file.item("New S&ection", C | 'T', store::addSectionToCurrentNotebook, () -> store.currentNotebook() != null);
        file.item("New Section &Group", 0, () -> {
            Notebook nb = store.currentNotebook();
            if (nb != null) cmd.newSectionGroup(nb.id(), null);
        });
        file.item("New &Notebook…", () -> Dialogs.newNotebook(shell, store));
        file.item("&Open Notebook…", C | 'O', cmd::openNotebook);
        file.item("Save Notebook &As…", C | S | 'S', () -> {
            Notebook nb = store.currentNotebook();
            if (nb != null) cmd.saveNotebookAs(nb.id());
        }, () -> store.currentNotebook() != null);
        file.item("&Close Notebook", 0, () -> {
            Notebook nb = store.currentNotebook();
            if (nb != null) store.closeNotebook(nb.id());
        }, () -> store.currentNotebook() != null);
        file.item("&Reload Notebooks", C | S | 'R', () -> store.reloadAll(shell.getDisplay(), () -> page.load(store.currentPageRef())));
        file.separator();
        file.item("Export Page as P&DF…", C | S | 'E', () -> cmd.exportPdf(current()), this::hasPage);
        file.item("Export Page as &Markdown…", 0, () -> cmd.exportMarkdown(current()), this::hasPage);
        file.item("Export Page as &HTML…", 0, () -> cmd.exportHtml(current()), this::hasPage);
        file.item("&Print Page…", C | 'P', () -> cmd.print(current()), this::hasPage);
        file.separator();
        file.item("E&xit", A | SWT.F4, shell::close);

        MenuBuilder edit = mb.menu("&Edit");
        edit.item("&Undo", C | 'Z', page::undo, () -> page.undo.canUndo());
        edit.item("&Redo", C | 'Y', page::redo, () -> page.undo.canRedo());
        edit.separator();
        EditActions.addStandardItems(edit, cmd::editTarget);
        edit.item("Paste as Plain Te&xt", C | S | 'V', () -> EditActions.paste(cmd.editTarget()));
        edit.separator();
        edit.item("Copy &Link to this Page", 0, () -> cmd.copyLink(current()), this::hasPage);
        edit.item("&Delete Page", 0, () -> cmd.deletePage(current()), this::hasPage);
        edit.separator();
        edit.item("&Find on Page…", C | 'F', () -> Dialogs.find(page), this::hasPage);
        edit.item("Find &Next", SWT.F3, () -> page.find(page.lastFind(), true), this::hasPage);
        edit.item("Find Pre&vious", S | SWT.F3, () -> page.find(page.lastFind(), false), this::hasPage);
        edit.item("&Search All Notebooks", C | 'E', () -> {
            if (fullPage) toggleFullPage();
            sidebar.search.setFocus();
            sidebar.search.selectAll();
        });

        MenuBuilder view = mb.menu("&View");
        QuickHelp.get().addMenuItems(view);
        view.item("F&ull Page View", SWT.F11, this::toggleFullPage);
        view.separator();
        view.item("Zoom &In", C | A | '=', page::zoomIn);
        view.item("Zoom &Out", C | A | '-', page::zoomOut);
        view.item("&Actual Size", C | S | '0', () -> page.zoomTo(1));
        view.item("Page &Width", page::zoomToPageWidth);
        MenuBuilder color = view.submenu("Page &Color");
        for (PageColor c : PageColor.values()) color.radio(c.title(), () -> page.content() != null && page.content().color == c, () -> page.setPageColor(c));
        MenuBuilder rules = view.submenu("&Rule Lines");
        for (RuleLines r : RuleLines.values()) rules.radio(r.title, () -> page.content() != null && page.content().rules == r, () -> page.setRuleLines(r));
        view.check("&Snap to Grid", C | A | 'G', () -> page.snapToGrid, v -> {
            page.snapToGrid = v;
            ribbon.setSnap(v);
        });
        view.separator();
        view.item("&Tag Summary", C | S | 'T', () -> Windows.tagSummary(shell));
        view.item("Recycle &Bin", () -> Windows.recycleBin(shell));

        MenuBuilder insert = mb.menu("&Insert");
        insert.item("&Table…", C | A | 'T', cmd::insertTable, this::hasPage);
        insert.item("&Picture…", C | A | 'P', page::choosePictures, this::hasPage);
        insert.item("&File Attachment…", 0, page::chooseAttachments, this::hasPage);
        insert.separator();
        insert.item("&Link…", C | 'K', cmd::insertLink);
        insert.item("&Remove Link", () -> {
            NoteText t = page.activeText();
            if (t != null) t.removeLink();
        });
        insert.separator();
        insert.item("&Date", A | S | 'D', cmd::insertDate);
        insert.item("T&ime", A | S | 'T', cmd::insertTime);
        insert.item("Date && Ti&me", A | S | 'F', cmd::insertDateTime);

        MenuBuilder format = mb.menu("F&ormat");
        format.item("&Bold", C | 'B', cmd::toggleBold);
        format.item("&Italic", C | 'I', cmd::toggleItalic);
        format.item("&Underline", C | 'U', cmd::toggleUnderline);
        format.item("&Strikethrough", C | '-', cmd::toggleStrike);
        format.item("Su&perscript", C | S | '=', () -> cmd.toggleScript(1));
        format.item("Subs&cript", C | '=', () -> cmd.toggleScript(-1));
        format.item("&Highlight", C | A | 'H', () -> cmd.setHighlight((org.eclipse.swt.graphics.RGB) Palettes.HIGHLIGHT[0][1]));
        format.item("Bi&gger", C | S | '.', () -> cmd.stepSize(2));
        format.item("S&maller", C | S | ',', () -> cmd.stepSize(-2));
        format.separator();
        MenuBuilder styles = format.submenu("St&yles");
        styles.item("Heading 1", C | A | '1', () -> cmd.style(Paragraphs.Style.HEADING1));
        styles.item("Heading 2", C | A | '2', () -> cmd.style(Paragraphs.Style.HEADING2));
        styles.item("Heading 3", C | A | '3', () -> cmd.style(Paragraphs.Style.HEADING3));
        styles.item("Heading 4", C | A | '4', () -> cmd.style(Paragraphs.Style.HEADING4));
        styles.item("Normal", C | A | '0', () -> cmd.style(Paragraphs.Style.NORMAL));
        styles.item("Page Title", () -> cmd.style(Paragraphs.Style.TITLE));
        styles.item("Quote", () -> cmd.style(Paragraphs.Style.QUOTE));
        styles.item("Code", C | A | 'C', () -> cmd.style(Paragraphs.Style.CODE));
        format.item("B&ullets", C | '.', cmd::bullets);
        format.item("&Numbering", C | '/', cmd::numbering);
        format.item("In&dent", () -> cmd.indent(1));
        format.item("&Outdent", () -> cmd.indent(-1));
        MenuBuilder align = format.submenu("&Alignment");
        align.item("Align Left", () -> cmd.align(ParaStyle.LEFT));
        align.item("Center", () -> cmd.align(ParaStyle.CENTER));
        align.item("Align Right", () -> cmd.align(ParaStyle.RIGHT));
        align.item("Justify", () -> cmd.align(ParaStyle.JUSTIFIED));
        format.separator();
        format.item("C&lear Formatting", C | S | 'N', cmd::clearFormatting);

        MenuBuilder tags = mb.menu("&Tags");
        for (TagKind k : TagKind.values()) tags.item(k.symbol + "  " + k.title, C | ('0' + k.digit()), () -> cmd.tag(k));
        tags.separator();
        tags.item("&Remove Tag", C | '0', cmd::removeTags);
        tags.separator();
        tags.item("Tag &Summary", () -> Windows.tagSummary(shell));

        MenuBuilder go = mb.menu("&Go");
        store.history.addMenuItems(go, store::showHistoryPlace);
        go.separator();
        go.item("&Previous Page", C | SWT.PAGE_UP, () -> store.selectAdjacentPage(-1));
        go.item("&Next Page", C | SWT.PAGE_DOWN, () -> store.selectAdjacentPage(1));
        go.item("Previous &Section", C | S | SWT.PAGE_UP, () -> store.selectAdjacentSection(-1));
        go.item("Next S&ection", C | S | SWT.PAGE_DOWN, () -> store.selectAdjacentSection(1));

        MenuBuilder nb = mb.menu("&Notebook");
        nb.item("Make &Subpage", C | A | ']', () -> {
            if (current() != null) store.changeLevel(current(), 1);
        }, this::hasPage);
        nb.item("&Promote Subpage", C | A | '[', () -> {
            if (current() != null) store.changeLevel(current(), -1);
        }, this::hasPage);
        nb.item("&Move or Copy Page…", C | S | 'M', () -> cmd.moveOrCopy(current()), this::hasPage);
        nb.item("&Duplicate Page", 0, () -> {
            if (current() != null) store.duplicatePage(current(), shell.getDisplay());
        }, this::hasPage);
        nb.separator();
        nb.item("&Rename Section…", 0, () -> {
            if (store.selectedSection() != null) cmd.renameNode(store.selectedSection());
        }, () -> store.selectedSection() != null);
        nb.item("Password &Protect Section…", 0, () -> {
            SectionRef s = store.selectedSection();
            if (s == null) return;
            SectionNode n = store.node(s);
            if (n != null && n.isProtected() && store.isLocked(s)) cmd.unlock(s);
            else cmd.protect(s);
        }, () -> store.selectedSection() != null);
        nb.item("&Lock All Protected Sections", C | S | 'L', store::lockAll);
        nb.separator();
        nb.item("Rename &Notebook…", 0, () -> {
            Notebook n = store.currentNotebook();
            if (n != null) cmd.renameNotebook(n.id());
        }, () -> store.currentNotebook() != null);
        nb.item("Show Notebook in &Explorer", 0, () -> {
            Notebook n = store.currentNotebook();
            if (n != null) org.eclipse.swt.program.Program.launch(n.folder.toString());
        }, () -> store.currentNotebook() != null);
        mb.install();
    }
}
