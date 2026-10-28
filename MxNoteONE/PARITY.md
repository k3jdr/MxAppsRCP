# MxNoteONE — parity with the macOS app

Every area of `D:\MyCodeGIT\MxAppsIOS\MxNoteONE\README.md`, and how far the Windows port goes.
**Ported** works as on the Mac; **Partial** works with the gaps noted; **Not yet** is not in this port (data of that
kind is still kept when a page is saved on Windows).

## Window

| Area | Status | Notes |
|---|---|---|
| Sidebar: notebooks, coloured sections, nesting section groups | Ported | OneDrive notebooks marked ☁ |
| Section / group menu: rename, recolour, move up / down, into a group, delete, copy link, password | Ported | Export Section as PDF not yet |
| Drag sections and groups to reorder, into a group, onto the notebook | Ported | groups open while hovered (SWT's expand feedback) |
| Drop a page on a section (moves it, any notebook) | Ported | |
| + section / group / notebook, Open Notebook, Tag Summary and Recycle Bin buttons | Ported | |
| Search field across notebooks, results in the page column | Ported | |
| Page list: dates, first line, subpages (two levels), collapse, To Do mark | Ported | |
| Drag pages to reorder (subpages move along, next to a subpage it becomes one, below the list goes last) | Ported | |
| Page dragged into a note becomes a link | Not yet | Copy Link to this Page and paste the address |
| Page menu: New Page / Subpage, Make Subpage, Promote, Move or Copy…, Duplicate, Copy Link, Export PDF / Markdown, Print, Delete | Ported | plus Export as HTML |
| Add New menu on empty space (incl. New Page from Template) | Ported | |
| Ribbon — Home | Ported | font and size boxes show the selection ("Mixed" / "–"), B I U S lit; line spacing menu not yet |
| Ribbon — Insert | Partial | table, picture, file, link, date / time, templates; DBML, video, screen clipping, audio recording not yet |
| Ribbon — Draw (draw.io) | Not yet | diagrams made on the Mac show their picture |
| Ribbon — View | Ported | page colour, rule lines, zoom, page width, full page view, snap to grid, Tag Summary, Recycle Bin |
| Ribbon — Table tab while a table is in use | Partial | rows, columns, delete, move rows, merge / split, shading, header row, borders, select, sort, AutoSum, formulas; Number, Conditional, Validation, Filter, Pivot, Chart, Freeze, Headings, Automation not yet |
| Page: title, created │ Updated line with tooltips | Ported | |
| Free-form canvas: click (double-click / never) starts a text box, right-click New Text Box Here / Paste Here / Insert Here / Clicking the Page | Ported | Insert Here: table, picture, file |
| Containers: drag by the bar, resize from the right edge, Cut / Copy / Delete / Bring to Front / Send to Back, Escape / Delete / arrows | Ported | |
| Drop pictures and files on the page | Ported | |
| Zoom | Ported | View menu, ribbon, Ctrl+wheel |
| Page always drawn as light paper | Ported | |

## Pages

| Area | Status | Notes |
|---|---|---|
| Text containers grow with their text | Ported | |
| Rich text: fonts, colours, highlights, inline pictures | Partial | inline pictures show; after editing a box on Windows they move out into picture containers (see README) |
| Bullets and numbering, Return / empty item / Tab / Shift+Tab, hanging indent | Ported | |
| Tags ⌘1–⌘9 / ⌘0, click ☐, Return after a To Do starts another | Ported | Ctrl+1–9 / Ctrl+0 |
| Tables, OneNote style (Tab, Return, Ctrl+Enter, Shift+Tab, Alt+Return, Backspace removes the new column / row, text after moves below) | Ported | ⌥Tab (a real tab) has no Windows key: Alt+Tab is Windows' own |
| Selecting cells (drag, Shift-click, Shift-arrows, Ctrl-click), typing replaces, Delete clears, Return edits, Ctrl+C / X / V, Ctrl+A, Escape | Ported | |
| Formatting cells (rich text, Home ribbon on selected cells, empty cell keeps its font) | Ported | |
| Shading for cells, rows, columns, header row, banded rows, table; text colour; white text on dark shades | Ported | More Colors… and Recent Colors not yet |
| Column letters / row numbers bars, Always Show Headings | Not yet | |
| Hide / unhide columns and rows | Partial | hidden ones made on the Mac stay hidden and keep their text; no commands to hide yet |
| Filter, sort and filter by colour | Not yet | filters made on the Mac are kept |
| Sorting (numbers in order, case ignored, blanks last, header stays, Sort Includes First Row) | Ported | |
| Insert rows / columns as many as selected | Ported | |
| Fill Down / Right, fill handle, series | Not yet | |
| Moving rows (menu, ribbon, Alt+Shift+Up / Down) | Ported | dragging selected rows not yet |
| Column widths: drag a border, double-click to auto-fit, Auto-Fit commands | Ported | in the table only (no letter bar) |
| Formulas (references, operators, the function list, errors, #CIRC!, '=) , AutoSum, Formulas menu, references follow inserted / deleted rows and columns, sorting takes relative refs along | Ported | |
| Alignment of cells | Ported | |
| Number formats | Partial | shown as the Mac formats them; no commands to set them yet |
| Dates typed in cells, date formats, date functions | Ported | US date order as on the Mac |
| Conditional formatting | Not yet | rules made on the Mac are kept (and not shifted when rows are inserted on Windows) |
| Data validation | Not yet | rules kept (not shifted) |
| Pivot tables, charts | Not yet | charts made on the Mac show as placeholders and are kept |
| Copy and paste with Excel | Partial | tab-separated text both ways (pasting TSV on the page makes a table); Excel's HTML with formatting not yet |
| Row heights (drag, auto-fit) | Partial | heights set on the Mac are kept and used; no dragging yet |
| Pictures in cells | Partial | shown from the cell's RTFD; resizing them not yet |
| Pointer shapes (resize, hand on links) | Ported | |
| Frozen header row, Freeze First Column | Not yet | kept |
| Merge and split cells | Partial | merge, unmerge; split into 2 rows / columns not yet |
| Tables print, export and keep shading | Ported | |
| Pictures: double-click opens, copy, save, reset size | Ported | |
| Attachments kept in the notebook, double-click opens | Ported | recordings show as attachments (no in-place player) |
| Videos, ffmpeg conversion, transcripts and recaps | Not yet | out of scope; video containers show their poster |
| Snap to Grid | Ported | Alt to place freely not yet |
| Diagrams (draw.io) | Not yet | out of scope; their picture shows |
| Copy links to page, section, group, notebook, paragraph | Ported | |
| Links from other apps (mxnoteone://) | Partial | handed to the running copy and followed; the scheme isn't registered with Windows yet |
| Links: [[Page Name]], Ctrl+K web and page links, Edit Link, Open / Copy / Remove Link | Ported | |
| Link hover card | Not yet | Quick Help shows where a link goes and its hover text |
| Hover text (ScreenTips) | Partial | kept and shown in Quick Help; not editable |
| Paste options | Partial | pasting into text is plain; Paste with Formatting not yet |
| Templates | Ported | |
| Saves automatically, Undo / Redo | Ported | Undo history per open page, as on the Mac |

## Other areas

| Area | Status | Notes |
|---|---|---|
| Music scores (all of it) | Not yet | out of scope; shown as placeholders, kept |
| 3D models (CAD) | Not yet | out of scope; kept |
| Maps | Not yet | out of scope; picture shown when the notebook has one; kept |
| DBML database diagrams | Not yet | kept |
| Microsoft Office fonts | Ported | Windows has them; Mac font names map to Windows fonts |
| Notebook Fonts… | Not yet | |
| Keys in notes (Return, Shift+Return, Backspace, arrows into the next container, Alt+Shift+Up / Down moves paragraphs, keys in tables) | Ported | |
| Code blocks with syntax colouring | Partial | Code style (monospaced on grey); no colouring, language detection or themes |
| Arranging the toolbar (wrap, collapse, palettes, compact) | Partial | the ribbon wraps; no collapsing or floating sections |
| Notebook scripts (Python) | Not yet | `scripts` folder left alone |
| Quick Help | Ported | core panel at the sidebar's foot, float / dock; tools, containers, cells, links, page |
| Clipboard window | Not yet | |
| Web links in a chosen browser | Partial | the Windows default browser |
| Page navigation history, Go ▸ History | Ported | |
| Spelling | Not yet | |
| Keyboard table | Ported | Windows keys in README.md |
| Importing from OneNote | Not yet | out of scope |
| OneDrive / OneNote sync | Not yet | out of scope; the OneDrive link in notebook.json is kept |
| Notebooks on disk (format), Open Notebook, Save Notebook As, Reload Notebooks | Ported | |
| Password-protected sections (same crypto), Lock All, hidden from search and tags | Ported | auto-lock after time away not yet |
| Touch ID / app lock | Not yet | |
| Recycle Bin (60 days, restore, delete, empty) | Ported | |
| Settings | Partial | Clicking the Page and sort option; default font, page colour and notebooks folder only by `-notebooks` |
| Permissions | n/a | |
