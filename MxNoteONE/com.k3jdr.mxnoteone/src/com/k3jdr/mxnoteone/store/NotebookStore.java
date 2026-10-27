package com.k3jdr.mxnoteone.store;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.crypto.SecretKey;

import org.eclipse.swt.widgets.Display;

import com.google.gson.JsonObject;
import com.k3jdr.mx.core.prefs.Prefs;
import com.k3jdr.mx.core.ui.NavigationHistory;
import com.k3jdr.mxnoteone.model.Kinds;
import com.k3jdr.mxnoteone.model.Kinds.NoteColor;
import com.k3jdr.mxnoteone.model.Kinds.PageColor;
import com.k3jdr.mxnoteone.model.Kinds.RuleLines;
import com.k3jdr.mxnoteone.model.NotebookManifest;
import com.k3jdr.mxnoteone.model.NotebookManifest.TrashedItem;
import com.k3jdr.mxnoteone.model.PageContent;
import com.k3jdr.mxnoteone.model.PageElement;
import com.k3jdr.mxnoteone.model.PageMeta;
import com.k3jdr.mxnoteone.model.PageMeta.TagItem;
import com.k3jdr.mxnoteone.model.SectionNode;
import com.k3jdr.mxnoteone.model.SwiftJson;
import com.k3jdr.mxnoteone.store.Notebook.SectionContent;
import com.k3jdr.mxnoteone.store.Refs.PageRef;
import com.k3jdr.mxnoteone.store.Refs.SectionRef;

/**
 * Open notebooks, the current selection (section and page), the pages visited, and every change to notebooks,
 * sections and pages (NotebookStore.swift). The model lives on the UI thread; files are written in the
 * background ({@link Disk}) as soon as something changes, in the Mac app's formats.
 */
public final class NotebookStore {
    public static final String LINK_SCHEME = "mxnoteone";

    public enum Change { TREE, PAGES, SELECTION }

    private static NotebookStore instance;

    public static NotebookStore get() {
        if (instance == null) instance = new NotebookStore();
        return instance;
    }

    public final List<Notebook> notebooks = new ArrayList<>();
    public final Set<String> expandedNotebooks = new HashSet<>();
    public final Set<String> expandedGroups = new HashSet<>();
    private SectionRef selectedSection;
    private SectionRef selectedGroup;
    private String selectedPageId;
    private final Map<String, String> lastPageInSection = new HashMap<>();
    public final NavigationHistory<PageRef> history = new NavigationHistory<>((a, b) -> false);
    private final List<Consumer<Change>> listeners = new ArrayList<>();
    /** Shows a message: (title, message). */
    public Consumer<String[]> alerts = m -> {};
    /** Saves the page being edited before the selection moves on (the canvas). */
    public Runnable flushCanvas = () -> {};
    /** Put the cursor in the title of the page that opens next (new pages). */
    public boolean focusTitleOnLoad;
    private Path library;
    private boolean customLibrary;
    private final Prefs prefs = Prefs.of("com.k3jdr.mxnoteone");

    private NotebookStore() {}

    // MARK: Listeners

    public void addListener(Consumer<Change> l) { listeners.add(l); }

    public void fire(Change c) {
        for (Consumer<Change> l : List.copyOf(listeners)) l.accept(c);
    }

    private void alert(String title, String message) { alerts.accept(new String[] { title, message }); }

    // MARK: Library folder

    /** Where notebooks are created: -notebooks &lt;folder&gt;, else Documents\MxNoteONE Notebooks. */
    public Path library() {
        if (library == null) {
            String home = System.getProperty("user.home");
            library = Path.of(home, "Documents", "MxNoteONE Notebooks");
        }
        return library;
    }

    public void setLibrary(Path folder) {
        library = folder;
        customLibrary = true;
    }

    private String openKey() {
        return customLibrary ? "openNotebooks@" + library().toAbsolutePath().toString().toLowerCase(Locale.ROOT) : "openNotebooks";
    }

    private void persistOpenNotebooks() {
        List<String> paths = new ArrayList<>();
        for (Notebook nb : notebooks) paths.add(nb.folder.toAbsolutePath().toString());
        prefs.putList(openKey(), paths);
    }

    // MARK: Lookup

    public Notebook notebook(String id) {
        if (id == null) return null;
        for (Notebook nb : notebooks) if (nb.id().equals(id)) return nb;
        return null;
    }

    public SectionNode node(SectionRef ref) {
        Notebook nb = ref == null ? null : notebook(ref.notebookId());
        return nb == null ? null : SectionNode.find(nb.manifest.sections, ref.sectionId());
    }

    public boolean isLocked(SectionRef ref) {
        Notebook nb = notebook(ref.notebookId());
        SectionNode n = node(ref);
        return nb != null && n != null && n.isProtected() && !nb.unlocked.contains(ref.sectionId());
    }

    public List<PageMeta> pages(SectionRef ref) {
        Notebook nb = ref == null ? null : notebook(ref.notebookId());
        SectionContent c = nb == null ? null : nb.sections.get(ref.sectionId());
        return c == null ? null : c.pages;
    }

    /** Pages shown in the page list (subpages of collapsed pages are hidden). */
    public List<PageMeta> visiblePages(SectionRef ref) {
        List<PageMeta> out = new ArrayList<>();
        List<PageMeta> all = pages(ref);
        if (all == null) return out;
        Integer hideDeeperThan = null;
        for (PageMeta p : all) {
            if (hideDeeperThan != null) {
                if (p.level > hideDeeperThan) continue;
                hideDeeperThan = null;
            }
            out.add(p);
            if (p.collapsed) hideDeeperThan = p.level;
        }
        return out;
    }

    public boolean hasSubpages(SectionRef ref, String pageId) {
        List<PageMeta> pages = pages(ref);
        if (pages == null) return false;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id.equals(pageId)) return i + 1 < pages.size() && pages.get(i + 1).level > pages.get(i).level;
        }
        return false;
    }

    public PageMeta meta(PageRef ref) {
        List<PageMeta> pages = ref == null ? null : pages(ref.section());
        if (pages == null) return null;
        for (PageMeta p : pages) if (p.id.equals(ref.pageId())) return p;
        return null;
    }

    public SectionRef selectedSection() { return selectedSection; }
    public SectionRef selectedGroup() { return selectedGroup; }
    public String selectedPageId() { return selectedPageId; }

    public PageRef currentPageRef() {
        if (selectedSection == null || selectedPageId == null) return null;
        PageRef ref = new PageRef(selectedSection.notebookId(), selectedSection.sectionId(), selectedPageId);
        return meta(ref) != null ? ref : null;
    }

    public Notebook currentNotebook() {
        if (selectedSection != null) return notebook(selectedSection.notebookId());
        if (selectedGroup != null) return notebook(selectedGroup.notebookId());
        return notebooks.isEmpty() ? null : notebooks.get(0);
    }

    /** "Notebook › Group › Section". */
    public String location(SectionRef ref) {
        Notebook nb = notebook(ref.notebookId());
        SectionNode n = node(ref);
        if (nb == null || n == null) return "";
        List<String> parts = new ArrayList<>();
        parts.add(nb.name());
        List<String> groups = SectionNode.path(nb.manifest.sections, ref.sectionId());
        if (groups != null) parts.addAll(groups);
        parts.add(n.name);
        return String.join(" › ", parts);
    }

    public String windowTitle() {
        PageRef ref = currentPageRef();
        PageMeta m = meta(ref);
        return m != null ? m.displayTitle() : "MxNoteONE";
    }

    // MARK: Files

    private static String manifestJson(Notebook nb) {
        return SwiftJson.write(nb.manifest.toJson(), true, true);
    }

    public void saveManifest(Notebook nb) {
        Disk.writeText(nb.manifestFile(), manifestJson(nb), null, "Couldn't save the notebook “" + nb.name() + "”.");
    }

    public void saveSection(Notebook nb, String sectionId) {
        SectionContent c = nb.sections.get(sectionId);
        if (c == null) return;
        SectionNode n = SectionNode.find(nb.manifest.sections, sectionId);
        SecretKey key = nb.keys.get(sectionId);
        if (n != null && n.isProtected() && key == null) return;
        Disk.writeText(nb.sectionFile(sectionId), SwiftJson.write(c.toJson(), false, false), key, "Couldn't save the section.");
    }

    private static SectionContent readSection(Notebook nb, String id, SecretKey key) {
        try {
            byte[] data = Disk.readNow(nb.sectionFile(id), key);
            return SectionContent.fromJson(SwiftJson.parse(new String(data, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return new SectionContent();
        }
    }

    /** Reads a notebook folder (on the disk thread). */
    static Notebook readNotebook(Path folder) throws IOException {
        String json = Files.readString(folder.resolve("notebook.json"), StandardCharsets.UTF_8);
        NotebookManifest m = NotebookManifest.fromJson(SwiftJson.parse(json));
        Notebook nb = new Notebook(folder.toAbsolutePath().normalize(), m);
        for (SectionNode n : SectionNode.sections(m.sections)) {
            if (!n.isProtected()) nb.sections.put(n.id, readSection(nb, n.id, null));
        }
        return nb;
    }

    // MARK: Notebooks

    /** Opens the notebooks open last time (or those in the library folder), else creates My Notebook. */
    public void loadLibrary(Display display, Runnable done) {
        List<String> remembered = prefs.getList(openKey());
        Path lib = library();
        Disk.submit(() -> {
            List<Path> folders = new ArrayList<>();
            for (String p : remembered) folders.add(Path.of(p));
            if (folders.isEmpty() && Files.isDirectory(lib)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(lib, "*.mxnotebook")) {
                    for (Path p : ds) if (Files.isRegularFile(p.resolve("notebook.json"))) folders.add(p);
                } catch (IOException e) {
                    // an unreadable folder: nothing to open from it
                }
                folders.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
            }
            List<Notebook> loaded = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (Path f : folders) {
                try {
                    loaded.add(readNotebook(f));
                } catch (Exception e) {
                    failed.add(f.toString());
                }
            }
            display.asyncExec(() -> {
                for (Notebook nb : loaded) {
                    if (notebook(nb.id()) != null) continue;
                    notebooks.add(nb);
                    expandedNotebooks.add(nb.id());
                    purgeOldTrash(nb);
                }
                if (notebooks.isEmpty()) createNotebook("My Notebook", NoteColor.PURPLE, lib, "Quick Notes", Templates.WELCOME);
                persistOpenNotebooks();
                if (selectedSection == null) selectFirstSection(notebooks.isEmpty() ? null : notebooks.get(0));
                fire(Change.TREE);
                done.run();
            });
        });
    }

    private void selectFirstSection(Notebook nb) {
        if (nb == null) return;
        List<SectionNode> sections = SectionNode.sections(nb.manifest.sections);
        if (!sections.isEmpty()) selectSection(new SectionRef(nb.id(), sections.get(0).id));
    }

    /** File ▸ Open Notebook…: a notebook folder (read in the background). */
    public void openNotebook(Path folder, Display display) {
        Path f = folder.toAbsolutePath().normalize();
        for (Notebook nb : notebooks) {
            if (nb.folder.equals(f)) {
                selectFirstSection(nb);
                return;
            }
        }
        Disk.submit(() -> {
            try {
                Notebook nb = readNotebook(f);
                display.asyncExec(() -> {
                    if (notebook(nb.id()) != null) {
                        alert("Notebook already open", "A copy of “" + nb.name() + "” is already open.");
                        return;
                    }
                    notebooks.add(nb);
                    purgeOldTrash(nb);
                    expandedNotebooks.add(nb.id());
                    persistOpenNotebooks();
                    selectFirstSection(nb);
                    fire(Change.TREE);
                });
            } catch (Exception e) {
                display.asyncExec(() -> alert("Couldn't open the notebook", f.getFileName()
                        + " is not an MxNoteONE notebook, or it can't be read.\n\n" + e.getMessage()));
            }
        });
    }

    public Notebook createNotebook(String name, NoteColor color, Path folder, String firstSection, Templates template) {
        String safe = name.replace('/', '-').replace(':', '-').replace('\\', '-');
        Path dir = folder.resolve(safe + ".mxnotebook");
        int n = 2;
        while (Files.exists(dir)) dir = folder.resolve(safe + " " + n++ + ".mxnotebook");
        NotebookManifest m = new NotebookManifest();
        m.name = name;
        m.color = color;
        Notebook nb = new Notebook(dir.toAbsolutePath().normalize(), m);
        Path d = dir;
        Disk.submit(() -> {
            try {
                for (String sub : new String[] { "sections", "pages", "assets" }) Files.createDirectories(d.resolve(sub));
            } catch (IOException e) {
                Disk.errors.accept("Couldn't create the notebook.", e);
            }
        });
        saveManifest(nb);
        notebooks.add(nb);
        expandedNotebooks.add(nb.id());
        persistOpenNotebooks();
        if (firstSection != null) addSection(nb.id(), null, firstSection, template);
        fire(Change.TREE);
        return nb;
    }

    public void closeNotebook(String id) {
        Notebook nb = notebook(id);
        if (nb == null) return;
        flushCanvas.run();
        if (selectedSection != null && selectedSection.notebookId().equals(id) || selectedGroup != null && selectedGroup.notebookId().equals(id)) {
            selectedSection = null;
            selectedGroup = null;
            selectedPageId = null;
        }
        notebooks.remove(nb);
        persistOpenNotebooks();
        if (selectedSection == null) selectFirstSection(notebooks.isEmpty() ? null : notebooks.get(0));
        fire(Change.TREE);
        fire(Change.SELECTION);
    }

    public void renameNotebook(String id, String name) {
        Notebook nb = notebook(id);
        if (nb == null || name.isBlank()) return;
        nb.manifest.name = name;
        saveManifest(nb);
        fire(Change.TREE);
    }

    public void setNotebookColor(String id, NoteColor color) {
        Notebook nb = notebook(id);
        if (nb == null) return;
        nb.manifest.color = color;
        saveManifest(nb);
        fire(Change.TREE);
    }

    /** File ▸ Save Notebook As…: copies the notebook folder and keeps working with the copy. */
    public void saveNotebookAs(String id, Path destination, Display display) {
        Notebook nb = notebook(id);
        if (nb == null) return;
        flushCanvas.run();
        Path dest = destination.getFileName().toString().endsWith(".mxnotebook") ? destination
                : destination.resolveSibling(destination.getFileName() + ".mxnotebook");
        Path src = nb.folder;
        Disk.submit(() -> {
            try {
                if (Files.exists(dest)) throw new IOException(dest.getFileName() + " already exists there.");
                try (var walk = Files.walk(src)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        Path target = dest.resolve(src.relativize(p).toString());
                        if (Files.isDirectory(p)) Files.createDirectories(target);
                        else Files.copy(p, target);
                    }
                }
                display.asyncExec(() -> {
                    nb.folder = dest.toAbsolutePath().normalize();
                    persistOpenNotebooks();
                    fire(Change.TREE);
                });
            } catch (IOException e) {
                display.asyncExec(() -> alert("Couldn't save the notebook.", e.getMessage()));
            }
        });
    }

    /** Re-reads every notebook from disk (for notebooks kept in a synced folder). */
    public void reloadAll(Display display, Runnable done) {
        flushCanvas.run();
        List<Notebook> current = new ArrayList<>(notebooks);
        Disk.submit(() -> {
            Map<String, NotebookManifest> manifests = new HashMap<>();
            Map<String, Map<String, SectionContent>> contents = new HashMap<>();
            for (Notebook nb : current) {
                try {
                    NotebookManifest m = NotebookManifest.fromJson(SwiftJson.parse(Files.readString(nb.manifestFile(), StandardCharsets.UTF_8)));
                    Map<String, SectionContent> secs = new HashMap<>();
                    for (SectionNode n : SectionNode.sections(m.sections)) {
                        if (n.isProtected()) {
                            SecretKey key = nb.keys.get(n.id);
                            if (key != null) secs.put(n.id, readSection(nb, n.id, key));
                        } else {
                            secs.put(n.id, readSection(nb, n.id, null));
                        }
                    }
                    manifests.put(nb.id(), m);
                    contents.put(nb.id(), secs);
                } catch (Exception e) {
                    // keeps what's in memory
                }
            }
            display.asyncExec(() -> {
                for (Notebook nb : current) {
                    NotebookManifest m = manifests.get(nb.id());
                    if (m == null) continue;
                    nb.manifest = m;
                    nb.sections.clear();
                    nb.sections.putAll(contents.get(nb.id()));
                }
                if (selectedSection != null && node(selectedSection) == null) {
                    selectedSection = null;
                    selectedPageId = null;
                    selectFirstSection(currentNotebook());
                }
                fire(Change.TREE);
                fire(Change.PAGES);
                done.run();
            });
        });
    }

    // MARK: Selection and history

    public void selectSection(SectionRef ref) {
        if (ref != null && ref.equals(selectedSection) && selectedGroup == null) return;
        flushCanvas.run();
        selectedSection = ref;
        selectedGroup = null;
        List<PageMeta> pages = ref == null ? null : pages(ref);
        if (pages != null) {
            String remembered = lastPageInSection.get(ref.sectionId());
            selectedPageId = remembered != null && pages.stream().anyMatch(p -> p.id.equals(remembered)) ? remembered
                    : pages.isEmpty() ? null : pages.get(0).id;
        } else {
            selectedPageId = null;
        }
        if (ref != null) expandedNotebooks.add(ref.notebookId());
        recordHistory();
        fire(Change.PAGES);
        fire(Change.SELECTION);
    }

    public void selectGroup(SectionRef ref) {
        flushCanvas.run();
        selectedGroup = ref;
        selectedSection = null;
        selectedPageId = null;
        fire(Change.PAGES);
        fire(Change.SELECTION);
    }

    public void selectPage(String id) {
        if (id != null && id.equals(selectedPageId)) return;
        flushCanvas.run();
        selectedPageId = id;
        if (selectedSection != null && id != null) lastPageInSection.put(selectedSection.sectionId(), id);
        recordHistory();
        fire(Change.SELECTION);
    }

    /** Opens a page anywhere. */
    public void open(PageRef ref) {
        navigate(ref);
        recordHistory();
        fire(Change.PAGES);
        fire(Change.SELECTION);
    }

    private void navigate(PageRef ref) {
        flushCanvas.run();
        expandedNotebooks.add(ref.notebookId());
        selectedSection = ref.section();
        selectedGroup = null;
        selectedPageId = ref.pageId();
        lastPageInSection.put(ref.sectionId(), ref.pageId());
    }

    private void recordHistory() {
        PageRef cur = currentPageRef();
        if (cur != null) history.visit(cur, historyTitle(cur));
    }

    /** "Page — Section" for the history menus. */
    public String historyTitle(PageRef ref) {
        PageMeta m = meta(ref);
        String page = m == null ? "Untitled Page" : m.displayTitle();
        SectionNode n = node(ref.section());
        return n == null ? page : page + " — " + n.name;
    }

    /** The page's current place (it may have moved to another section); null when it's gone. */
    public PageRef relocate(PageRef ref) {
        if (meta(ref) != null) return ref;
        return findPage(ref.pageId(), ref.notebookId());
    }

    public void goBack() { travel(true); }
    public void goForward() { travel(false); }

    /** Shows a place from the history (Back, Forward, Go ▸ History), skipping pages since deleted. */
    public void showHistoryPlace(PageRef place) {
        PageRef found = relocate(place);
        if (found == null) return;
        navigate(found);
        fire(Change.PAGES);
        fire(Change.SELECTION);
    }

    private void travel(boolean back) {
        for (int guard = 0; guard < 100; guard++) {
            var next = back ? history.back() : history.forward();
            if (next.isEmpty()) return;
            PageRef found = relocate(next.get());
            if (found != null) {
                navigate(found);
                fire(Change.PAGES);
                fire(Change.SELECTION);
                return;
            }
        }
    }

    public void selectAdjacentPage(int delta) {
        if (selectedSection == null) return;
        List<PageMeta> pages = visiblePages(selectedSection);
        if (pages.isEmpty()) return;
        int i = 0;
        for (int k = 0; k < pages.size(); k++) if (pages.get(k).id.equals(selectedPageId)) i = k;
        int j = Math.max(0, Math.min(pages.size() - 1, i + delta));
        selectPage(pages.get(j).id);
    }

    public void selectAdjacentSection(int delta) {
        Notebook nb = currentNotebook();
        if (nb == null) return;
        List<SectionNode> sections = SectionNode.sections(nb.manifest.sections);
        if (sections.isEmpty()) return;
        int i = 0;
        for (int k = 0; k < sections.size(); k++) if (selectedSection != null && sections.get(k).id.equals(selectedSection.sectionId())) i = k;
        int j = ((i + delta) % sections.size() + sections.size()) % sections.size();
        selectSection(new SectionRef(nb.id(), sections.get(j).id));
    }

    // MARK: Pages

    /** Reads a page (call on the disk thread: after pending writes of it). */
    public PageContent readPage(PageRef ref) {
        Notebook nb = notebook(ref.notebookId());
        PageMeta m = meta(ref);
        if (nb == null || m == null) return null;
        try {
            byte[] data = Disk.readNow(nb.pageFile(ref.pageId()), nb.keys.get(ref.sectionId()));
            return PageContent.fromJson(SwiftJson.parse(new String(data, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            PageContent p = new PageContent(m.id);
            p.title = m.title;
            p.created = m.created;
            return p;
        }
    }

    /** Writes a page and refreshes its entry in the page list (the canvas, after edits). */
    public void savePage(PageContent content, PageRef ref, PageIndexer.Index index) {
        Notebook nb = notebook(ref.notebookId());
        SectionContent sc = nb == null ? null : nb.sections.get(ref.sectionId());
        if (sc == null || isLocked(ref.section())) return;
        PageMeta m = sc.find(ref.pageId());
        if (m == null) return;
        Disk.writeText(nb.pageFile(ref.pageId()), SwiftJson.write(content.toJson(), false, false), nb.keys.get(ref.sectionId()),
                "Couldn't save the page.");
        m.title = content.title;
        m.modified = SwiftJson.now();
        m.plainText = index.text();
        m.tags = new ArrayList<>(index.tags());
        saveSection(nb, ref.sectionId());
        fire(Change.PAGES);
    }

    /** Live title update while typing (saved with the page). */
    public void setTitle(PageRef ref, String title) {
        PageMeta m = meta(ref);
        if (m == null || title.equals(m.title)) return;
        m.title = title;
        fire(Change.PAGES);
    }

    private void writePage(PageContent content, Notebook nb, SecretKey key) {
        Disk.writeText(nb.pageFile(content.id), SwiftJson.write(content.toJson(), false, false), key, "Couldn't save the page.");
    }

    public PageRef addPage(SectionRef ref, String after, boolean subpage, Templates template, String title, boolean select) {
        Notebook nb = notebook(ref.notebookId());
        SectionContent sc = nb == null ? null : nb.sections.get(ref.sectionId());
        if (sc == null || isLocked(ref)) return null;
        Templates.Built built = template.build();
        PageMeta meta = new PageMeta();
        meta.title = title != null ? title : built.title();
        PageIndexer.Index index = PageIndexer.index(built.elements());
        meta.plainText = index.text();
        meta.tags = new ArrayList<>(index.tags());
        PageContent content = new PageContent(meta.id);
        content.title = meta.title;
        content.created = meta.created;
        content.color = built.color() != null ? built.color() : defaultPageColor();
        content.rules = built.rules() != null ? built.rules() : defaultRuleLines();
        content.elements.addAll(built.elements());
        int insertAt = sc.pages.size();
        int i = after == null ? -1 : sc.indexOf(after);
        if (i >= 0) {
            int base = sc.pages.get(i).level;
            meta.level = subpage ? Math.min(base + 1, 2) : base;
            int j = i + 1;
            while (j < sc.pages.size() && sc.pages.get(j).level > base) j++;
            insertAt = j;
            if (subpage) sc.pages.get(i).collapsed = false;
        }
        sc.pages.add(insertAt, meta);
        writePage(content, nb, nb.keys.get(ref.sectionId()));
        saveSection(nb, ref.sectionId());
        PageRef pageRef = new PageRef(ref.notebookId(), ref.sectionId(), meta.id);
        if (select) {
            focusTitleOnLoad = template == Templates.BLANK && title == null;
            if (!ref.equals(selectedSection)) {
                flushCanvas.run();
                selectedSection = ref;
                selectedGroup = null;
            }
            fire(Change.PAGES);
            selectPage(meta.id);
            fire(Change.PAGES);
        } else {
            fire(Change.PAGES);
        }
        return pageRef;
    }

    public void addPageToCurrentSection(Templates template, boolean subpage) {
        if (selectedSection == null) {
            Notebook nb = currentNotebook();
            if (nb != null) addSection(nb.id(), selectedGroup != null ? selectedGroup.sectionId() : null, null, template);
            return;
        }
        addPage(selectedSection, selectedPageId, subpage, template, null, true);
    }

    public void newQuickNote() {
        if (notebooks.isEmpty()) {
            createNotebook("My Notebook", NoteColor.PURPLE, library(), "Quick Notes", Templates.WELCOME);
            return;
        }
        Notebook nb = notebooks.get(0);
        for (SectionNode n : SectionNode.sections(nb.manifest.sections)) {
            SectionRef ref = new SectionRef(nb.id(), n.id);
            if (n.name.equals("Quick Notes") && !isLocked(ref)) {
                String lastTop = null;
                for (PageMeta p : pages(ref)) if (p.level == 0) lastTop = p.id;
                addPage(ref, lastTop, false, Templates.BLANK, null, true);
                return;
            }
        }
        addSection(nb.id(), null, "Quick Notes", Templates.BLANK);
    }

    public void deletePage(PageRef ref) {
        Notebook nb = notebook(ref.notebookId());
        SectionNode n = node(ref.section());
        SectionContent sc = nb == null ? null : nb.sections.get(ref.sectionId());
        if (sc == null || n == null) return;
        int i = sc.indexOf(ref.pageId());
        if (i < 0) return;
        flushCanvas.run();
        PageMeta meta = sc.pages.remove(i);
        for (int j = i; j < sc.pages.size() && sc.pages.get(j).level > meta.level; j++) sc.pages.get(j).level--;
        if (n.isProtected()) {
            meta.plainText = "";
            meta.tags = new ArrayList<>();
        }
        TrashedItem t = new TrashedItem();
        t.name = meta.displayTitle();
        t.page = meta;
        t.fromSectionId = n.id;
        t.fromSectionName = n.name;
        t.encrypted = n.isProtected();
        nb.manifest.trash.add(0, t);
        saveSection(nb, ref.sectionId());
        saveManifest(nb);
        if (ref.pageId().equals(selectedPageId)) {
            List<PageMeta> remaining = visiblePages(ref.section());
            selectedPageId = remaining.isEmpty() ? null : remaining.get(Math.min(i, remaining.size() - 1)).id;
            recordHistory();
            fire(Change.SELECTION);
        }
        fire(Change.PAGES);
        fire(Change.TREE);
    }

    /**
     * Drag-reordering in the page list: the pages (with their subpages) go before {@code beforeId}, or last when it
     * is null. Dropped next to a subpage, a page becomes one ({@code level}, -1 to keep its own).
     */
    public void movePages(SectionRef ref, List<String> movingIds, String beforeId, int level) {
        Notebook nb = notebook(ref.notebookId());
        SectionContent sc = nb == null ? null : nb.sections.get(ref.sectionId());
        if (sc == null) return;
        List<PageMeta> pages = new ArrayList<>(sc.pages);
        List<PageMeta> moving = new ArrayList<>();
        for (String id : movingIds) {
            int i = -1;
            for (int k = 0; k < pages.size(); k++) if (pages.get(k).id.equals(id)) i = k;
            if (i < 0) continue;
            int end = i + 1;
            while (end < pages.size() && pages.get(end).level > pages.get(i).level) end++;
            List<PageMeta> block = new ArrayList<>(pages.subList(i, end));
            if (level >= 0 && !block.isEmpty()) {
                int shift = level - block.get(0).level;
                for (PageMeta p : block) p.level = Math.max(0, Math.min(2, p.level + shift));
            }
            moving.addAll(block);
            pages.subList(i, end).clear();
        }
        int insertAt = pages.size();
        if (beforeId != null) for (int k = 0; k < pages.size(); k++) if (pages.get(k).id.equals(beforeId)) insertAt = k;
        pages.addAll(insertAt, moving);
        sc.pages.clear();
        sc.pages.addAll(normalizedLevels(pages));
        saveSection(nb, ref.sectionId());
        fire(Change.PAGES);
    }

    static List<PageMeta> normalizedLevels(List<PageMeta> pages) {
        for (int i = 0; i < pages.size(); i++) {
            int max = i == 0 ? 0 : Math.min(pages.get(i - 1).level + 1, 2);
            pages.get(i).level = Math.max(0, Math.min(pages.get(i).level, max));
        }
        return pages;
    }

    /** Make Subpage (+1) / Promote Subpage (-1). Subpages move with their page. */
    public void changeLevel(PageRef ref, int delta) {
        Notebook nb = notebook(ref.notebookId());
        SectionContent sc = nb == null ? null : nb.sections.get(ref.sectionId());
        if (sc == null) return;
        int i = sc.indexOf(ref.pageId());
        if (i < 0) return;
        List<PageMeta> pages = sc.pages;
        int base = pages.get(i).level;
        int end = i + 1;
        while (end < pages.size() && pages.get(end).level > base) end++;
        int max = i == 0 ? 0 : Math.min(pages.get(i - 1).level + 1, 2);
        int newLevel = Math.max(0, Math.min(base + delta, max));
        int shift = newLevel - base;
        if (shift == 0) return;
        for (int k = i; k < end; k++) pages.get(k).level = Math.max(0, Math.min(2, pages.get(k).level + shift));
        normalizedLevels(pages);
        saveSection(nb, ref.sectionId());
        fire(Change.PAGES);
    }

    public void toggleCollapsed(PageRef ref) {
        PageMeta m = meta(ref);
        if (m == null) return;
        m.collapsed = !m.collapsed;
        saveSection(notebook(ref.notebookId()), ref.sectionId());
        fire(Change.PAGES);
    }

    public void duplicatePage(PageRef ref, Display display) {
        Notebook nb = notebook(ref.notebookId());
        SectionContent sc = nb == null ? null : nb.sections.get(ref.sectionId());
        if (sc == null) return;
        flushCanvas.run();
        Disk.submit(() -> {
            PageContent content = readPage(ref);
            display.asyncExec(() -> {
                int i = sc.indexOf(ref.pageId());
                if (i < 0 || content == null) return;
                PageMeta meta = sc.pages.get(i).copy();
                meta.id = Kinds.newId();
                meta.created = SwiftJson.now();
                meta.modified = SwiftJson.now();
                meta.collapsed = false;
                content.id = meta.id;
                content.created = meta.created;
                writePage(content, nb, nb.keys.get(ref.sectionId()));
                int end = i + 1;
                while (end < sc.pages.size() && sc.pages.get(end).level > sc.pages.get(i).level) end++;
                sc.pages.add(end, meta);
                saveSection(nb, ref.sectionId());
                fire(Change.PAGES);
                selectPage(meta.id);
            });
        });
    }

    /** Moves or copies a page to another section, in this or another notebook. */
    public void movePage(PageRef ref, SectionRef dest, boolean copy, Display display) {
        Notebook src = notebook(ref.notebookId()), dst = notebook(dest.notebookId());
        PageMeta meta0 = meta(ref);
        if (src == null || dst == null || meta0 == null || isLocked(ref.section()) || isLocked(dest) || dst.sections.get(dest.sectionId()) == null) return;
        if (!copy && ref.section().equals(dest)) return;
        flushCanvas.run();
        SecretKey srcKey = src.keys.get(ref.sectionId()), dstKey = dst.keys.get(dest.sectionId());
        boolean sameStorage = src == dst && (ref.sectionId().equals(dest.sectionId()) || srcKey == null && dstKey == null);
        PageMeta meta = meta0.copy();
        if (copy) meta.id = Kinds.newId();
        meta.level = 0;
        meta.collapsed = false;
        Disk.submit(() -> {
            try {
                PageContent content = readPage(ref);
                content.id = meta.id;
                if (!sameStorage) {
                    for (PageElement e : content.elements) {
                        if (e.asset != null) e.asset = copyAsset(src, srcKey, e.asset, dst, dstKey);
                        if (e.preview != null) e.preview = copyAsset(src, srcKey, e.preview, dst, dstKey);
                    }
                }
                Disk.writeNow(dst.pageFile(meta.id), SwiftJson.write(content.toJson(), false, false).getBytes(StandardCharsets.UTF_8), dstKey);
                display.asyncExec(() -> {
                    dst.sections.get(dest.sectionId()).pages.add(meta);
                    saveSection(dst, dest.sectionId());
                    if (!copy) {
                        SectionContent sc = src.sections.get(ref.sectionId());
                        int i = sc == null ? -1 : sc.indexOf(ref.pageId());
                        if (i >= 0) {
                            int level = sc.pages.get(i).level;
                            sc.pages.remove(i);
                            for (int j = i; j < sc.pages.size() && sc.pages.get(j).level > level; j++) sc.pages.get(j).level--;
                            saveSection(src, ref.sectionId());
                        }
                        if (src != dst) Disk.submit(() -> {
                            try {
                                Files.deleteIfExists(src.pageFile(ref.pageId()));
                            } catch (IOException e) {
                                // the old file stays; the page list no longer has it
                            }
                        });
                        if (ref.pageId().equals(selectedPageId)) open(new PageRef(dest.notebookId(), dest.sectionId(), meta.id));
                    }
                    fire(Change.PAGES);
                });
            } catch (Exception e) {
                display.asyncExec(() -> alert(copy ? "Couldn't copy the page." : "Couldn't move the page.", e.getMessage()));
            }
        });
    }

    private static String copyAsset(Notebook src, SecretKey srcKey, String name, Notebook dst, SecretKey dstKey) throws Exception {
        byte[] data = Disk.readNow(src.assetFile(name), srcKey);
        String ext = extension(name);
        String fresh = Kinds.newId() + (ext.isEmpty() ? "" : "." + ext.toLowerCase(Locale.ROOT));
        Disk.writeNow(dst.assetFile(fresh), data, dstKey);
        return fresh;
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    // MARK: Sections and section groups

    public SectionRef addSection(String notebookId, String groupId, String name, Templates template) {
        Notebook nb = notebook(notebookId);
        if (nb == null) return null;
        int count = SectionNode.sections(nb.manifest.sections).size();
        SectionNode node = SectionNode.section(name != null ? name : "New Section " + (count + 1),
                NoteColor.values()[count % NoteColor.values().length]);
        SectionNode.append(nb.manifest.sections, node, groupId);
        nb.sections.put(node.id, new SectionContent());
        saveManifest(nb);
        saveSection(nb, node.id);
        SectionRef ref = new SectionRef(notebookId, node.id);
        expandedNotebooks.add(notebookId);
        if (groupId != null) expandedGroups.add(groupId);
        fire(Change.TREE);
        selectSection(ref);
        addPage(ref, null, false, template == null ? Templates.BLANK : template, null, true);
        return ref;
    }

    public void addSectionToCurrentNotebook() {
        Notebook nb = currentNotebook();
        if (nb == null) return;
        String group = selectedGroup != null ? selectedGroup.sectionId() : null;
        if (selectedSection != null) {
            SectionNode parent = SectionNode.parentGroup(nb.manifest.sections, selectedSection.sectionId());
            group = parent == null ? null : parent.id;
        }
        addSection(nb.id(), group, null, Templates.BLANK);
    }

    public SectionRef addSectionGroup(String notebookId, String groupId, String name) {
        Notebook nb = notebook(notebookId);
        if (nb == null) return null;
        SectionNode node = SectionNode.group(name == null ? "New Section Group" : name);
        SectionNode.append(nb.manifest.sections, node, groupId);
        saveManifest(nb);
        expandedNotebooks.add(notebookId);
        if (groupId != null) expandedGroups.add(groupId);
        fire(Change.TREE);
        return new SectionRef(notebookId, node.id);
    }

    public void renameNode(SectionRef ref, String name) {
        Notebook nb = notebook(ref.notebookId());
        SectionNode n = node(ref);
        if (nb == null || n == null || name.isBlank()) return;
        n.name = name;
        saveManifest(nb);
        fire(Change.TREE);
    }

    public void setColor(SectionRef ref, NoteColor color) {
        Notebook nb = notebook(ref.notebookId());
        SectionNode n = node(ref);
        if (nb == null || n == null) return;
        n.color = color;
        saveManifest(nb);
        fire(Change.TREE);
    }

    public void moveNode(SectionRef ref, int delta) {
        Notebook nb = notebook(ref.notebookId());
        if (nb == null) return;
        SectionNode.moveSibling(nb.manifest.sections, ref.sectionId(), delta);
        saveManifest(nb);
        fire(Change.TREE);
    }

    /** Moves a section or group into a group (null = top level of the notebook), last there. */
    public void moveNodeToGroup(SectionRef ref, String groupId) {
        Notebook nb = notebook(ref.notebookId());
        SectionNode n = node(ref);
        if (nb == null || n == null) return;
        if (groupId != null && (groupId.equals(n.id) || n.children != null && SectionNode.find(n.children, groupId) != null)) return;
        SectionNode removed = SectionNode.remove(nb.manifest.sections, ref.sectionId());
        if (removed == null) return;
        SectionNode.append(nb.manifest.sections, removed, groupId);
        if (groupId != null) expandedGroups.add(groupId);
        saveManifest(nb);
        fire(Change.TREE);
    }

    /** Drag in the sidebar: puts a section or group just before or after another one (in its group). */
    public void moveNodeBeside(SectionRef ref, String targetId, boolean after) {
        Notebook nb = notebook(ref.notebookId());
        SectionNode n = node(ref);
        if (nb == null || n == null || ref.sectionId().equals(targetId)) return;
        if (n.children != null && SectionNode.find(n.children, targetId) != null) return;
        SectionNode removed = SectionNode.remove(nb.manifest.sections, ref.sectionId());
        if (removed == null) return;
        if (!SectionNode.insertBeside(nb.manifest.sections, removed, targetId, after)) nb.manifest.sections.add(removed);
        saveManifest(nb);
        fire(Change.TREE);
    }

    public void deleteNode(SectionRef ref) {
        Notebook nb = notebook(ref.notebookId());
        SectionNode n = node(ref);
        if (nb == null || n == null) return;
        Set<String> contained = new LinkedHashSet<>();
        n.forEach(x -> contained.add(x.id));
        if (selectedSection != null && contained.contains(selectedSection.sectionId())
                || selectedGroup != null && contained.contains(selectedGroup.sectionId())) {
            flushCanvas.run();
            selectedSection = null;
            selectedGroup = null;
            selectedPageId = null;
        }
        SectionNode.remove(nb.manifest.sections, ref.sectionId());
        TrashedItem t = new TrashedItem();
        t.section = true;
        t.name = n.name;
        t.node = n;
        nb.manifest.trash.add(0, t);
        for (String id : contained) {
            nb.sections.remove(id);
            nb.keys.remove(id);
            nb.unlocked.remove(id);
        }
        saveManifest(nb);
        if (selectedSection == null && selectedGroup == null) selectFirstSection(nb);
        fire(Change.TREE);
        fire(Change.PAGES);
        fire(Change.SELECTION);
    }

    // MARK: Recycle Bin

    public void restore(TrashedItem item, Notebook nb, Display display) {
        if (!item.section) {
            PageMeta meta = item.page;
            if (meta == null) return;
            String target = item.fromSectionId != null && SectionNode.find(nb.manifest.sections, item.fromSectionId) != null ? item.fromSectionId : null;
            if (item.encrypted) {
                if (target == null) {
                    alert("Can't restore the page", "The page came from a password-protected section that no longer exists. Restore the section first.");
                    return;
                }
                if (isLocked(new SectionRef(nb.id(), target))) {
                    alert("Section is locked", "Unlock the section “" + item.fromSectionName + "” to restore this page.");
                    return;
                }
            } else if (target == null || isLocked(new SectionRef(nb.id(), target))) {
                target = null;
                for (SectionNode s : SectionNode.sections(nb.manifest.sections)) {
                    if (!s.isProtected()) {
                        target = s.id;
                        break;
                    }
                }
                if (target == null) {
                    SectionNode node = SectionNode.section("Restored Pages", NoteColor.GRAY);
                    nb.manifest.sections.add(node);
                    nb.sections.put(node.id, new SectionContent());
                    target = node.id;
                }
            }
            SecretKey key = nb.keys.get(target);
            if (!item.encrypted && key != null) {
                Path file = nb.pageFile(meta.id);
                Disk.submit(() -> {
                    try {
                        Disk.writeNow(file, Disk.readNow(file, null), key);
                    } catch (Exception e) {
                        Disk.errors.accept("Couldn't restore the page.", e);
                    }
                });
            }
            meta.level = 0;
            SectionContent sc = nb.sections.get(target);
            if (sc != null) sc.pages.add(meta);
            saveSection(nb, target);
        } else {
            SectionNode node = item.node;
            if (node == null) return;
            nb.manifest.sections.add(node);
            List<SectionNode> secs = SectionNode.sections(List.of(node));
            for (SectionNode s : secs) if (!s.isProtected()) nb.sections.put(s.id, new SectionContent());
            Disk.submit(() -> {
                Map<String, SectionContent> loaded = new HashMap<>();
                for (SectionNode s : secs) if (!s.isProtected()) loaded.put(s.id, readSection(nb, s.id, null));
                display.asyncExec(() -> {
                    nb.sections.putAll(loaded);
                    fire(Change.PAGES);
                    fire(Change.TREE);
                });
            });
        }
        nb.manifest.trash.removeIf(t -> t.id.equals(item.id));
        saveManifest(nb);
        fire(Change.TREE);
        fire(Change.PAGES);
    }

    public void deletePermanently(TrashedItem item, Notebook nb, boolean save) {
        List<Path> files = new ArrayList<>();
        if (!item.section) {
            if (item.page != null) files.add(nb.pageFile(item.page.id));
        } else if (item.node != null) {
            for (SectionNode s : SectionNode.sections(List.of(item.node))) {
                if (!s.isProtected()) {
                    for (PageMeta p : readSection(nb, s.id, null).pages) files.add(nb.pageFile(p.id));
                }
                files.add(nb.sectionFile(s.id));
            }
        }
        Disk.submit(() -> {
            for (Path f : files) {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    // a file that can't be removed stays behind, unused
                }
            }
        });
        nb.manifest.trash.removeIf(t -> t.id.equals(item.id));
        if (save) {
            saveManifest(nb);
            fire(Change.TREE);
        }
    }

    public void emptyTrash(Notebook nb) {
        for (TrashedItem t : new ArrayList<>(nb.manifest.trash)) deletePermanently(t, nb, false);
        nb.manifest.trash.clear();
        saveManifest(nb);
        fire(Change.TREE);
    }

    private void purgeOldTrash(Notebook nb) {
        Instant cutoff = Instant.now().minus(60, ChronoUnit.DAYS);
        List<TrashedItem> old = new ArrayList<>();
        for (TrashedItem t : nb.manifest.trash) if (t.deleted.isBefore(cutoff)) old.add(t);
        if (old.isEmpty()) return;
        for (TrashedItem t : old) deletePermanently(t, nb, false);
        saveManifest(nb);
    }

    // MARK: Password protection

    /** Sets or changes a section's password (the section must be unlocked); the key is worked out in the background. */
    public void setPassword(SectionRef ref, String password, String hint, Display display, Runnable done) {
        Notebook nb = notebook(ref.notebookId());
        if (nb == null || node(ref) == null || isLocked(ref) || password.isEmpty()) return;
        flushCanvas.run();
        SecretKey oldKey = nb.keys.get(ref.sectionId());
        List<String> pageIds = pageIdsWithTrash(nb, ref.sectionId(), oldKey != null);
        Disk.submit(() -> {
            Object[] made = NoteCrypto.makeProtection(password, hint);
            JsonObject protection = (JsonObject) made[0];
            SecretKey key = (SecretKey) made[1];
            try {
                reencrypt(nb, pageIds, oldKey, key);
            } catch (Exception e) {
                display.asyncExec(() -> alert("Couldn't protect the section.", e.getMessage()));
                return;
            }
            display.asyncExec(() -> {
                SectionNode n = node(ref);
                if (n == null) return;
                n.protection = protection;
                nb.keys.put(ref.sectionId(), key);
                nb.unlocked.add(ref.sectionId());
                for (TrashedItem t : nb.manifest.trash) {
                    if (!t.section && ref.sectionId().equals(t.fromSectionId) && t.page != null && pageIds.contains(t.page.id)) {
                        t.encrypted = true;
                        t.page.plainText = "";
                        t.page.tags = new ArrayList<>();
                    }
                }
                saveSection(nb, ref.sectionId());
                saveManifest(nb);
                fire(Change.TREE);
                done.run();
            });
        });
    }

    private static List<String> pageIdsWithTrash(Notebook nb, String sectionId, boolean encrypted) {
        List<String> ids = new ArrayList<>();
        SectionContent sc = nb.sections.get(sectionId);
        if (sc != null) for (PageMeta p : sc.pages) ids.add(p.id);
        for (TrashedItem t : nb.manifest.trash) {
            if (!t.section && sectionId.equals(t.fromSectionId) && t.page != null && t.encrypted == encrypted) ids.add(t.page.id);
        }
        return ids;
    }

    public void removePassword(SectionRef ref, Display display) {
        Notebook nb = notebook(ref.notebookId());
        SecretKey key = nb == null ? null : nb.keys.get(ref.sectionId());
        if (key == null) return;
        flushCanvas.run();
        List<String> pageIds = pageIdsWithTrash(nb, ref.sectionId(), true);
        Disk.submit(() -> {
            try {
                reencrypt(nb, pageIds, key, null);
            } catch (Exception e) {
                display.asyncExec(() -> alert("Couldn't remove the password.", e.getMessage()));
                return;
            }
            display.asyncExec(() -> {
                SectionNode n = node(ref);
                if (n != null) n.protection = null;
                nb.keys.remove(ref.sectionId());
                nb.unlocked.remove(ref.sectionId());
                for (TrashedItem t : nb.manifest.trash) {
                    if (!t.section && ref.sectionId().equals(t.fromSectionId) && t.page != null && pageIds.contains(t.page.id)) t.encrypted = false;
                }
                saveSection(nb, ref.sectionId());
                saveManifest(nb);
                fire(Change.TREE);
            });
        });
    }

    /** Re-writes pages and their assets (and the section file) with another key (on the disk thread). */
    private static void reencrypt(Notebook nb, List<String> pageIds, SecretKey oldKey, SecretKey newKey) throws Exception {
        for (String id : pageIds) {
            Path file = nb.pageFile(id);
            if (!Files.exists(file)) continue;
            byte[] data = Disk.readNow(file, oldKey);
            try {
                PageContent content = PageContent.fromJson(SwiftJson.parse(new String(data, StandardCharsets.UTF_8)));
                for (PageElement e : content.elements) {
                    for (String asset : new String[] { e.asset, e.preview }) {
                        if (asset == null || !Files.exists(nb.assetFile(asset))) continue;
                        Disk.writeNow(nb.assetFile(asset), Disk.readNow(nb.assetFile(asset), oldKey), newKey);
                    }
                }
            } catch (RuntimeException e) {
                // a page that can't be read still gets its file re-sealed
            }
            Disk.writeNow(file, data, newKey);
        }
    }

    /** Unlocks with the password (worked out in the background); {@code done} gets whether it was right. */
    public void unlock(SectionRef ref, String password, Display display, Consumer<Boolean> done) {
        SectionNode n = node(ref);
        Notebook nb = notebook(ref.notebookId());
        if (n == null || nb == null || n.protection == null) {
            done.accept(false);
            return;
        }
        JsonObject protection = n.protection.deepCopy();
        Disk.submit(() -> {
            SecretKey key = NoteCrypto.unlock(protection, password);
            SectionContent content = key == null ? null : readSection(nb, ref.sectionId(), key);
            display.asyncExec(() -> {
                if (key == null) {
                    done.accept(false);
                    return;
                }
                nb.keys.put(ref.sectionId(), key);
                nb.sections.put(ref.sectionId(), content);
                nb.unlocked.add(ref.sectionId());
                if (ref.equals(selectedSection) && selectedPageId == null) {
                    List<PageMeta> pages = pages(ref);
                    selectedPageId = pages == null || pages.isEmpty() ? null : pages.get(0).id;
                    recordHistory();
                }
                fire(Change.TREE);
                fire(Change.PAGES);
                fire(Change.SELECTION);
                done.accept(true);
            });
        });
    }

    public void lock(SectionRef ref) {
        Notebook nb = notebook(ref.notebookId());
        SectionNode n = node(ref);
        if (nb == null || n == null || !n.isProtected()) return;
        if (selectedSection != null && selectedSection.equals(ref)) flushCanvas.run();
        nb.keys.remove(ref.sectionId());
        nb.sections.remove(ref.sectionId());
        nb.unlocked.remove(ref.sectionId());
        if (ref.equals(selectedSection)) selectedPageId = null;
        fire(Change.TREE);
        fire(Change.PAGES);
        fire(Change.SELECTION);
    }

    public void lockAll() {
        for (Notebook nb : notebooks) for (String id : new ArrayList<>(nb.unlocked)) lock(new SectionRef(nb.id(), id));
    }

    public boolean hasUnlockedSections() {
        for (Notebook nb : notebooks) if (!nb.unlocked.isEmpty()) return true;
        return false;
    }

    // MARK: Assets

    /** Stores a picture or attachment for a page; returns the asset's name. */
    public String writeAsset(byte[] data, String ext, PageRef ref) {
        Notebook nb = notebook(ref.notebookId());
        if (nb == null) return null;
        String name = Kinds.newId() + (ext == null || ext.isEmpty() ? "" : "." + ext.toLowerCase(Locale.ROOT));
        Disk.write(nb.assetFile(name), data, nb.keys.get(ref.sectionId()), "Couldn't store the file in the notebook.");
        return name;
    }

    /** An asset's data (call on the disk thread). */
    public byte[] assetData(String name, PageRef ref) {
        Notebook nb = notebook(ref.notebookId());
        if (nb == null || name == null) return null;
        try {
            return Disk.readNow(nb.assetFile(name), nb.keys.get(ref.sectionId()));
        } catch (Exception e) {
            return null;
        }
    }

    /** A temporary copy of an attachment under its original name, for opening (call on the disk thread). */
    public Path temporaryCopy(String name, String fileName, PageRef ref) {
        byte[] data = assetData(name, ref);
        if (data == null) return null;
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "MxNoteONE", Kinds.newId());
            Files.createDirectories(dir);
            Path f = dir.resolve(fileName != null && !fileName.isBlank() ? fileName.replaceAll("[\\\\/:*?\"<>|]", "-") : name);
            Files.write(f, data);
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    // MARK: Search and tags

    public record SearchHit(PageRef ref, String title, String snippet, String location, boolean titleMatch, Instant modified) {}

    public record TagHit(PageRef ref, String pageTitle, String location, TagItem item, Instant modified) {}

    private static String fold(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    public List<SearchHit> search(String query) {
        List<String> words = new ArrayList<>();
        for (String w : query.trim().split("\\s+")) if (!w.isEmpty()) words.add(fold(w));
        List<SearchHit> hits = new ArrayList<>();
        if (words.isEmpty()) return hits;
        for (Notebook nb : notebooks) {
            for (SectionNode n : SectionNode.sections(nb.manifest.sections)) {
                SectionContent sc = nb.sections.get(n.id);
                if (sc == null) continue;
                SectionRef sref = new SectionRef(nb.id(), n.id);
                String loc = location(sref);
                for (PageMeta p : sc.pages) {
                    String hay = fold(p.title + "\n" + p.plainText);
                    if (!words.stream().allMatch(hay::contains)) continue;
                    String title = fold(p.title);
                    boolean titleMatch = words.stream().allMatch(title::contains);
                    hits.add(new SearchHit(new PageRef(nb.id(), n.id, p.id), p.displayTitle(), snippet(p.plainText, words.get(0)), loc,
                            titleMatch, p.modified));
                }
            }
        }
        hits.sort(Comparator.comparing((SearchHit h) -> h.titleMatch ? 1 : 0).thenComparing(SearchHit::modified).reversed());
        return hits;
    }

    static String snippet(String text, String word) {
        String flat = text.replace('\n', ' ');
        int at = fold(flat).indexOf(word);
        if (at < 0) return flat.length() > 120 ? flat.substring(0, 120) : flat;
        int start = Math.max(0, at - 50), end = Math.min(flat.length(), at + word.length() + 80);
        return (start > 0 ? "…" : "") + flat.substring(start, end) + (end < flat.length() ? "…" : "");
    }

    public List<TagHit> allTags() {
        List<TagHit> hits = new ArrayList<>();
        for (Notebook nb : notebooks) {
            for (SectionNode n : SectionNode.sections(nb.manifest.sections)) {
                SectionContent sc = nb.sections.get(n.id);
                if (sc == null) continue;
                String loc = location(new SectionRef(nb.id(), n.id));
                for (PageMeta p : sc.pages) {
                    for (TagItem t : p.tags) hits.add(new TagHit(new PageRef(nb.id(), n.id, p.id), p.displayTitle(), loc, t, p.modified));
                }
            }
        }
        return hits;
    }

    // MARK: Links

    public static String link(PageRef ref) {
        return LINK_SCHEME + "://page/" + ref.notebookId() + "/" + ref.pageId();
    }

    public static String sectionLink(SectionRef ref) {
        return LINK_SCHEME + "://section/" + ref.notebookId() + "/" + ref.sectionId();
    }

    public static String notebookLink(String id) {
        return LINK_SCHEME + "://notebook/" + id;
    }

    public PageRef findPage(String pageId, String notebookId) {
        Notebook nb = notebook(notebookId);
        if (nb == null) return null;
        for (Map.Entry<String, SectionContent> e : nb.sections.entrySet()) {
            if (e.getValue().find(pageId) != null) return new PageRef(notebookId, e.getKey(), pageId);
        }
        return null;
    }

    /** Path parts of an mxnoteone:// link: {host, part, part…}. */
    static List<String> linkParts(String url) {
        List<String> out = new ArrayList<>();
        try {
            URI u = URI.create(url);
            if (!LINK_SCHEME.equalsIgnoreCase(u.getScheme())) return out;
            out.add(u.getHost() == null ? "" : u.getHost());
            for (String p : (u.getPath() == null ? "" : u.getPath()).split("/")) if (!p.isEmpty()) out.add(p);
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    public PageRef resolve(String url) {
        List<String> parts = linkParts(url);
        if (parts.size() < 3 || !"page".equals(parts.get(0))) return null;
        return findPage(parts.get(2).toUpperCase(Locale.ROOT), parts.get(1).toUpperCase(Locale.ROOT));
    }

    /** Goes where an mxnoteone:// link leads: a page (paragraph), section or notebook. */
    public PageRef openLink(String url) {
        List<String> parts = linkParts(url);
        if (parts.isEmpty()) return null;
        switch (parts.get(0)) {
            case "section" -> {
                Notebook nb = parts.size() >= 3 ? notebook(parts.get(1).toUpperCase(Locale.ROOT)) : null;
                SectionNode n = nb == null ? null : SectionNode.find(nb.manifest.sections, parts.get(2).toUpperCase(Locale.ROOT));
                if (n == null) {
                    alert("Section not found", "The linked section may have been deleted, or its notebook isn't open.");
                    return null;
                }
                expandedNotebooks.add(nb.id());
                List<SectionNode> inside = SectionNode.sections(List.of(n));
                if (!inside.isEmpty()) selectSection(new SectionRef(nb.id(), inside.get(0).id));
                else selectGroup(new SectionRef(nb.id(), n.id));
                return null;
            }
            case "notebook" -> {
                Notebook nb = parts.size() >= 2 ? notebook(parts.get(1).toUpperCase(Locale.ROOT)) : null;
                if (nb == null) {
                    alert("Notebook not open", "Open the notebook first (File ▸ Open Notebook…).");
                    return null;
                }
                selectFirstSection(nb);
                return null;
            }
            default -> {
                PageRef ref = resolve(url);
                if (ref == null) {
                    alert("Page not found", "The linked page may have been deleted, or it is in a locked section or a notebook that isn't open.");
                    return null;
                }
                open(ref);
                return ref;
            }
        }
    }

    /** Link for [[Page Name]]: an existing page in the current notebook, or a new page in the current section. */
    public String linkForPage(String title) {
        Notebook nb = currentNotebook();
        if (nb == null) return null;
        for (Map.Entry<String, SectionContent> e : nb.sections.entrySet()) {
            for (PageMeta p : e.getValue().pages) {
                if (p.title.equalsIgnoreCase(title)) return link(new PageRef(nb.id(), e.getKey(), p.id));
            }
        }
        if (selectedSection == null || !selectedSection.notebookId().equals(nb.id())) return null;
        PageRef ref = addPage(selectedSection, null, false, Templates.BLANK, title, false);
        return ref == null ? null : link(ref);
    }

    // MARK: Settings

    public PageColor defaultPageColor() { return PageColor.of(prefs.get("defaultPageColor", "none")); }
    public RuleLines defaultRuleLines() { return RuleLines.of(prefs.get("defaultRuleLines", "none")); }
    public Prefs prefs() { return prefs; }
}
