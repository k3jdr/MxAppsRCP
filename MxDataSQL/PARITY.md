# MxDataSQL — parity with the macOS app

Every feature of `D:\MyCodeGIT\MxAppsIOS\MxDataSQL\README.md`, and how far the Windows app has it.
**Ported** = same behaviour (with Windows keys and manners); **Partial** = works with the noted gaps; **Not yet**.

How it was checked: the build; the not-connected UI and the `MXDATASQL_DEMO` screens (grid, table list, SQL error,
Update / View JSON / Table View / filter panels, the diagram window) in screenshots; pure-Java checks of the generated
SQL (filters, UPDATEs, `$n` → JDBC placeholders, row-ID statement, caret errors), the record / CSV / TSV parsers, the
JSON formatter, the workspace JSON round trip and the DBML writer and layout trailer. **Nothing was run against a
PostgreSQL server** (by rule), so everything that talks to the database is compiled and reviewed but not exercised.

## Workspaces and files

| Feature | Status | Notes |
|---|---|---|
| `*.mxworkspace` JSON in the Mac format | Ported | Same keys, pretty-printed with sorted keys like JSONEncoder (`"key" : value`, `\/`); old files with missing keys read with defaults |
| File ▸ New / Open… / Save / Save As… / Duplicate / Revert / Open Recent | Ported | Each workspace in its own window; Open reuses an unused window |
| Title shows the file and "— Edited"; ask to save on close | Ported | Main window close asks every workspace, then quits |
| Password never in the file; "Save password" | Ported | Eclipse secure storage (DPAPI) via `Pg.savePassword("MxDataSQL", …)`; turning it off forgets it |
| Password saved before the app was renamed is migrated | Not yet | Nothing to migrate on Windows |
| Workspace remembers connection, last table, view settings, column widths | Ported | |
| Double-click / second launch opens a workspace or `.dbml` | Ported | Through the core's single-instance hand-over |

## DB Connection

| Feature | Status | Notes |
|---|---|---|
| Host, port, user, database, password, SSL (disable / prefer / require, no certificate check) | Ported | `Pg.connect` |
| Connect / Reconnect (Enter), Disconnect, status (version or error) | Ported | Error text selectable |
| Database drop-down: other databases on the same server, same user and password | Ported | Keeps the old connection until the new one works (Mac reconnects the previous one: same outcome); confirms discarding pending edits; keeps the same schema.table highlighted |

## DB Table Selection

| Feature | Status | Notes |
|---|---|---|
| Tables, partitioned tables, views, materialized views, functions, procedures; Show (All / Tables / …) and Filter | Ported | |
| Row estimates (`~`), Count Rows (`count(*)`, views skipped), sizes | Ported | Counts appear table by table |
| psql `\d` description, view definition coloured, function code (`\sf`) with SQL bodies coloured | Ported | |
| Open Table / Open View (Enter, double-click), Open in New Window | Ported | Row count line ("56 tables, 7 views, …"); Copy Row / Copy All Rows added |

## DB Table and Run SQL

| Feature | Status | Notes |
|---|---|---|
| SQL box with the opened table's SELECT; Run SQL (Ctrl+Enter) | Ported | Also Table ▸ Run SQL |
| Pre-flight: allowed statements, pending edits, server `PREPARE` (syntax, unknown names, `$1`) | Ported | Several statements detected before sending (pgjdbc would split them) |
| psql-style error in red with LINE and caret | Ported | Checked with a pure-Java test |
| Queries in a READ ONLY transaction; writes confirmed, own transaction, reload after | Ported | |
| CALL (no PREPARE, autocommit, confirm) and CREATE / ALTER / DROP (confirm, warnings, refresh list / databases / open table) | Ported | |
| Editable results: full column layout with `ctid`, or primary key present | Ported | Uses pgjdbc base table/column metadata instead of table OID / attnum |
| Unchanged default SQL reloads the whole table | Ported | |
| SQL colouring (keywords, types also after `::`, functions, strings incl. `E''` and `$$`, numbers, comments incl. nested, quoted identifiers, `$1`) | Ported | Light+ colours only (no dark appearance) |
| Resize bar between SQL box and grid (drag, double-click resets, remembered, clamped) | Ported | |
| Grid: columns span the width (default 3), user widths kept and saved per table, Reset Column Widths | Ported | |
| Rows to display set the grid's minimum height (up to 10) | Ported | |
| 1,000+ rows, cell selection (click, shift-click, drag, arrows, Tab, # for rows, header for columns, Ctrl+A) | Ported | Custom-drawn canvas; adds Page Up/Down, Home/End |
| Edit in place (double-click, Enter), edited cells highlighted, "Was:" in Quick Help | Ported | |
| Copy (one value, or TSV), Copy with Column Names, Paste (editable grids only, one value fills the selection) | Ported | |
| Set to NULL, Revert, Delete clears cells | Ported | Delete as Quick Help describes |
| Header and footer texts, pending-edit count, read-only notes | Ported | |
| Paging: Previous / Next, "Rows 1,001–2,000 of 5,432", # across pages, no paging of write results | Ported | |

## Update… and View…

| Feature | Status | Notes |
|---|---|---|
| Changed cells (old → new) | Ported | Copyable table |
| Row key: primary key / ctid / chosen columns | Ported | ctid default without a primary key |
| Optimistic check, empty as NULL, one transaction or row by row | Ported | Each statement must hit exactly one row |
| SQL preview with parameters; typed-cast UPDATE (`$1::text::type`) | Ported | Checked with a pure-Java test |
| Discard Edits, Apply Updates, reload afterwards keeping sort / filters / page | Ported | |
| Table ▸ View…: rows, columns, rows to fetch; reload when the fetch limit changes | Ported | |

## Sorting and filtering

| Feature | Status | Notes |
|---|---|---|
| ▾ header button: Sort (labels by column type), Clear Sort | Ported | A pop-up window in place of the popover |
| Filter by Values (distinct values with counts, search, Select All, NULL, other filters honoured, first 1,000) | Ported | |
| Filter by Condition (13 operators; text ignores case; typed comparisons) | Ported | |
| Funnel / arrow in headers, status line with "(filtered from …)", Clear Filters | Ported | |
| Header right-click: Clear Sort, Clear All Filters, Reset Column Widths | Ported | |
| On the server, values as parameters; blocked with pending edits; not on write results or duplicate names | Ported | |

## Export, JSON, SQL files

| Feature | Status | Notes |
|---|---|---|
| Export CSV (RFC 4180, NULL empty, `""` empty string), loaded rows with edits | Ported | |
| Export all rows, streamed, read-only | Ported | Choice asked before the Save dialog |
| View JSON…: VS Code format and colours, edit, 2 / 4 spaces (remembered), Format, Copy, Apply to Cell | Ported | |
| SQL box menu: Run SQL, New SQL, Open SQL File…, Open Recent (10, Clear Menu), SQL Folder (files, show, choose / change), Save SQL / Save SQL As…, Revert to Saved, Show in Explorer | Ported | Folder and recent files as paths in the settings |
| File name and "— Edited" under the box; ask to save before replacing | Ported | Undo / Redo added |

## Table windows

| Feature | Status | Notes |
|---|---|---|
| Open in New Window: the full DB Table form, own connection, keeps working when the workspace closes | Ported | Not checked against a server |
| View settings copied; column widths go back to the workspace while it is open | Ported | |
| Not reopened at launch; password in memory only | Ported | |
| Quick Help docked in the table window | Partial | Uses the main window's panel or the floating one (the core keeps one docked panel) |

## Schema diagrams (`com.k3jdr.mx.dbml`)

| Feature | Status | Notes |
|---|---|---|
| Write DBML… (schemas, open after writing; tables, columns, pk / increment / defaults / notes, indexes incl. expression / GIN / partial notes, checks and exclusions as comments, Refs with ON DELETE, TableGroups) | Ported | Schema choice in a dialog before the Save dialog; writer checked with a pure-Java test |
| Rewriting an arranged diagram keeps its layout and notes | Ported | `DbmlFiles.keepLayout`, checked |
| Open DBML… diagram window: tables, keys, enums, schema colours and panels, crow's feet, auto layout, Arrange | Ported | Screenshot checked |
| Drag / multi-select tables, schema select, arrow nudge, Ctrl+A; relationship curves (automatic, straight, quadratic, cubic) with handles, sides, colours, All Lines | Ported | Built per the Swift canvas; interactive behaviour not exercised (no synthetic input allowed) |
| Notes: add, drag, resize, edit, colour, delete; foreign sticky notes read-only | Ported | As above |
| Schemas: Select / Copy (DBML text and picture) / Export Schema… | Ported | As above |
| Zoom (Ctrl+=, Ctrl+-, Ctrl+0), Save / Save As in the same `// @mxdatasql-layout {…}` trailer, ask to save on close | Ported | Trailer marker identical to the Swift source |

## Quick Help, menus

| Feature | Status | Notes |
|---|---|---|
| Quick Help on every control, grid cells and headers, selection, screen | Ported | Texts from `SQLQuickHelp.swift` with Windows keys |
| View ▸ Quick Help (Ctrl+Alt+/), Float Quick Help | Ported | Core `QuickHelp` |
| Window minimum height grows with the docked panel | Partial | Fixed minimum size that includes the panel |
| Menus File / Edit / View / Table / Diagram | Ported | Edit ▸ Cut / Copy / Paste / Select All act on text fields or the grid; diagram items live in the diagram window's own Diagram menu |
| Selectable / copyable text | Ported | Errors, descriptions, previews; TableCopy on lists |
