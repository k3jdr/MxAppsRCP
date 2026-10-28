# MxNoteONE for Windows

A digital notebook in the spirit of Microsoft OneNote, ported from the macOS app
(`D:\MyCodeGIT\MxAppsIOS\MxNoteONE`) to an Eclipse RCP (e4) desktop app, Java 21. Notebooks are folders in **the Mac
app's own format**, so a notebook kept in OneDrive or Dropbox opens on either system.

## Build and run

```bat
mvn -B -o -q -f MxNoteONE\pom.xml clean verify
MxNoteONE\com.k3jdr.mxnoteone.product\target\products\com.k3jdr.mxnoteone.product\win32\win32\x86_64\MxNoteONE\MxNoteONE.exe
```

The first launch opens the notebooks found in `%USERPROFILE%\Documents\MxNoteONE Notebooks` — or, when there are
none, creates **My Notebook** with a **Quick Notes** section and a Welcome page there, as the Mac does. After that it
reopens the notebooks that were open.

| Launch argument | What it does |
|---|---|
| `-notebooks <folder>` | Keeps notebooks in that folder instead (and remembers the open ones per folder). Use it for tests. |
| `-selftest` (with `-notebooks`) | Runs the in-process self-test: types, formats, makes lists, tags and a table, saves, reads the file back, reloads, protects a section with a password and unlocks it, draws the page for printing; writes `selftest.log` (and `capture.png`) into the folder. |
| `mxnoteone://…` | A link handed over to the running copy: opens the page, paragraph, section or notebook. |

Smoke test (isolated workspace, test notebooks under the git-ignored `.smoke` folder):

```powershell
& releng\tools\smoke-test.ps1 -App MxNoteONE -Seconds 30 -Shot D:\MyCodeGIT\MxAppsRCP\.smoke\mxnoteone-test\shot.png `
  -Arguments '-notebooks','D:\MyCodeGIT\MxAppsRCP\.smoke\mxnoteone-test','-selftest'
```

## What it does

* **Window** — Back / Forward, the ribbon (Home, Insert, View, and Table while a table is in use); the sidebar with
  the open notebooks, coloured sections and nesting section groups, the search field, + (section, group, notebook),
  Tag Summary and Recycle Bin, and Quick Help at its foot; the page list (dates and first lines, subpages on two
  levels that collapse, drag to reorder, search results while searching); the page.
* **Sections and groups** — right-click to rename, recolour, move up / down or into a group, copy a link, password
  protect, lock / unlock, delete (to the Recycle Bin). Drag a section or group to reorder it, onto a group to put it
  inside, onto the notebook to put it last; drop a page on a section to move it there.
* **Pages** — New Page / Subpage / from Template, Make Subpage, Promote, Collapse, Move or Copy… (any notebook),
  Duplicate, Copy Link to this Page, Export as PDF / Markdown / HTML, Print, Delete. Back / Forward and Go ▸ History
  follow pages moved to other sections and skip deleted ones.
* **The page** — title, created / Updated line, a free-form canvas that scrolls and zooms (Ctrl+wheel). Click
  anywhere to start a text container (or double-click, or not at all — right-click the page ▸ Clicking the Page).
  Containers move by their top bar, resize from their right edge; click the bar (or Escape while typing) to select
  one, then Delete removes it and the arrow keys nudge it (Shift: 10 points); right-click the bar for Cut, Copy,
  Delete, Bring to Front, Send to Back. A growing container pushes the one under it down. Snap to Grid. Page colour,
  rule lines and grids. Ink drawn on the Mac shows.
* **Text** — rich text in each container: fonts, sizes, bold, italic, underline, strikethrough, colours, highlight,
  superscript / subscript, alignment, indents, styles (Title, Heading 1–4, Normal, Quote, Code), clear formatting.
  Bullets and numbering are text at a paragraph's start as on the Mac, with a hanging indent as wide as the marker;
  Return continues a list (an empty item ends it), Tab / Shift+Tab indent items, Backspace at an item's start removes
  the marker. Tags (☐ To Do … 💬 Discuss, Ctrl+1–9, Ctrl+0 removes); click ☐ to check it. Shift+Enter breaks a line
  inside a paragraph. Links: Ctrl+K (web, email or a page), `[[Page Name]]`, Copy Link to this Paragraph, click to
  follow. Pictures and files dropped on the page are kept in the notebook. Undo / Redo for typing, formatting and
  container and table changes while the page is open.
* **Tables** — Tab after text turns the paragraph into a table; Tab / Return grow it; Backspace in the empty new
  column or row removes it; Alt+Return is a new line in a cell; Ctrl+Enter inserts a row. Cells: click to type, drag /
  Shift-click / Shift-arrows to select, Ctrl-click to add; typing replaces, Delete clears, Escape steps out. Rich text
  in cells (the Home ribbon formats the selected cells), insert / delete rows and columns (as many as selected), move
  rows (Alt+Shift+Up / Down), header row, shading of cells, rows, columns, header, banded rows or the table, text
  colour, alignment, sort A→Z / Z→A (numbers in order, blanks last, header stays), hide borders, merge / unmerge, column
  widths by dragging a border (double-click to fit), auto-fit, copy / cut / paste as tab-separated text (pasting cells on
  the page makes a table). Excel-style formulas with the Mac's function list, AutoSum, references that follow inserted
  and deleted rows and columns, and the Mac's number formats (number, currency, percent, dates) shown.
* **Search, Tag Summary, Recycle Bin** — search across open notebooks (title and text, accents ignored); every tagged
  paragraph in a window (filter by tag, unchecked To Dos); deleted pages and sections kept 60 days, restore / delete /
  empty.
* **Password-protected sections** — the Mac's crypto (PBKDF2-SHA256 200,000 rounds, AES-GCM in CryptoKit's combined
  layout, the same verifier): set, change, remove, lock, unlock, Lock All.
* **Export and print** — PDF (through Windows' *Microsoft Print to PDF*), Markdown (pictures and attachments in a folder
  beside it), HTML (pictures inside), Print.

## The notebook format

Exactly the Mac's: `Name.mxnotebook\notebook.json` (pretty-printed, sorted keys), `sections\<id>.json`,
`pages\<id>.json`, `assets\<id>.<ext>`; ISO 8601 dates without fractions, upper-case UUIDs, base64 data, Swift's
member names and enum values. Members and container kinds this port doesn't use — scores, maps, 3D models, DBML,
draw.io diagrams, videos, charts, ink, OneDrive links, conditional formatting, validation, filters, pivots, table
names and tags — are read and written back unchanged; row and column indexes it knows (hidden rows and columns, row
heights, number formats, row and cell tags, filters, formulas) follow inserted and deleted rows and columns.

Rich text: the Mac stores RTFD (Cocoa's flattened package). This port reads the RTF inside it — fonts, sizes, bold,
italic, underline, strikethrough, colours, background, super/subscript, alignment, indents, spacing, links, line
breaks, inline pictures, the document comment with links' hover texts and pictures' sizes — and keeps RTF control
words it doesn't interpret (kerning, tab stops, line spacing…) on their runs and paragraphs. It writes **plain RTF**,
which the Mac app reads where it expects RTFD (NoteRichText falls back to RTF). Lossy spots:

* A text box that holds **pictures inside its text** is kept byte for byte until it's edited; once edited on Windows,
  those pictures are moved out of the text into picture containers under the box (RTF can't carry them back).
* Control words naming colour-table entries (underline / strikethrough / stroke colours) and NSTextList list tables
  aren't kept; font names follow a table (PostScript name ↔ family), unknown faces are written by their name.
* Line spacing (the paragraph's line height multiple) is kept but not shown; space before a paragraph is shown.
* A cell font is written as "PostScriptName size" like the Mac; a Windows-only font written there shows on the Mac
  only if the Mac has it.

## Mac → Windows

| | macOS | Windows |
|---|---|---|
| Sizes | points | the same numbers: text is drawn so a Mac container wraps the same (14 pt Helvetica Neue ≈ 10.5 pt Arial) |
| Fonts | Helvetica Neue, Menlo, system fonts | drawn with Arial, Consolas, Segoe UI; the names are kept |
| Keys | ⌘ | Ctrl; ⌥ → Alt; ⌃⌘ → Ctrl+Shift or Ctrl+Alt (below) |
| Back / Forward | ⌃← ⌃→, ⌘[ ⌘] | Alt+Left / Alt+Right, Ctrl+[ / Ctrl+], mouse side buttons |
| Previous / Next Page, Section | ⌥⌘↑↓, ⌥⌘←→ | Ctrl+PageUp / PageDown, Ctrl+Shift+PageUp / PageDown |
| Superscript / Subscript / Highlight | ⌃⌘= / ⌃⇧⌘= / ⌃⌘H | Ctrl+Shift+= / Ctrl+= / Ctrl+Alt+H (as OneNote for Windows) |
| Strikethrough / Zoom | ⌃⌘- / ⌘= ⌘- ⌃⌘0 | Ctrl+- / Ctrl+Alt+= Ctrl+Alt+- Ctrl+Shift+0, Ctrl+wheel |
| Insert Table / Picture / Snap to Grid | ⌃⌘T / ⌃⌘P / ⌃⌘G | Ctrl+Alt+T / Ctrl+Alt+P / Ctrl+Alt+G |
| Full Page View, Find Next | ⌥⌘F, ⌘G | F11, F3 (Shift+F3 previous) |
| Paste as Plain Text | ⌥⇧⌘V | Ctrl+Shift+V (pasting into text is always plain on Windows) |
| Undo / Redo | ⌘Z / ⇧⌘Z | Ctrl+Z / Ctrl+Y |
| Finder, Touch ID | Show in Finder, Touch ID unlock | Show in Explorer; password only |
| Font panel, colour picker | macOS panels | the ribbon's palettes |

## Code

`model` (the JSON files, `SwiftJson` writes them as Swift does), `text` (RText, RTF reader / writer, RTFD, fonts,
paragraph markers and styles), `store` (NotebookStore, Disk — one thread for all file I/O, NoteCrypto, templates,
search index), `table` (formulas, values), `ui` (window, sidebar, page list, ribbon, page canvas, containers, table,
dialogs, export, self-test). See [PARITY.md](PARITY.md) for what is ported.
