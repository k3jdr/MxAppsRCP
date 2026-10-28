# MxDataSQL for Windows

The Windows port of the macOS app MxDataSQL (`D:\MyCodeGIT\MxAppsIOS\MxDataSQL`, whose README is the
specification): browse and edit PostgreSQL tables in **workspaces** (`*.mxworkspace`), on the Eclipse RCP (e4)
platform, Java 21, SWT/JFace and pgjdbc. Workspace files and `.dbml` diagrams are read and written in the macOS app's
formats, so the same file opens on either system.

## Build and run

```bat
mvn -B -o -q -f MxDataSQL\pom.xml clean verify
```

The app is `MxDataSQL\com.k3jdr.mxdatasql.product\target\products\com.k3jdr.mxdatasql.product\win32\win32\x86_64\MxDataSQL\MxDataSQL.exe`
(and a zip next to it); it needs a Java 21 runtime on the PATH or in `JAVA_HOME`. Pass a `.mxworkspace` or `.dbml`
path on the command line to open it; a second launch hands its files to the running copy.

```powershell
& releng\tools\smoke-test.ps1 -App MxDataSQL -Seconds 12 -Shot D:\MyCodeGIT\MxAppsRCP\.smoke\mxdatasql.png
```

**Checking the screens without a database.** Set the environment variable `MXDATASQL_DEMO` to `grid`, `tables` or
`error` before launching: the first window then shows 1,000 made-up rows (with pending edits, a sort and a filter), a
made-up table list with a `\d` description, or a psql-style SQL error. Nothing is connected. With
`MXDATASQL_DEMO_SHOTS=<folder>` as well (and `MXDATASQL_DEMO=grid`), the app opens the Update, View JSON, Table View
and filter panels one after another, prints each into a PNG in that folder and closes it; `MXDATASQL_DEMO_DBML=<file>`
adds the diagram window of that `.dbml` file. These checks send no keyboard or mouse input.

## Bundles

| Bundle | What it holds |
|---|---|
| `com.k3jdr.mxdatasql` | The app: workspace document, the three forms, the grid, dialogs, the PostgreSQL service |
| `com.k3jdr.mx.dbml` | The DBML library (exported, for MxNoteONE too): parser, diagram model and `// @mxdatasql-layout` trailer, auto layout, the SWT diagram canvas, the diagram window |

Main classes of `com.k3jdr.mxdatasql`:

| Class | macOS counterpart |
|---|---|
| `Workspace`, `TableRef` | `WorkspaceDocument.swift` (JSON as JSONEncoder writes it: pretty, sorted keys) |
| `Session` | `WorkspaceSession.swift` (connection, list, grid, pending edits, Run SQL, SQL files) |
| `PgService`, `DatabaseError` | `PostgresService.swift` (pgjdbc; calls serialized like the Swift actor) |
| `Catalog`, `GridResult`, `GridFilters`, `TableUpdate`, `TextFormats` | `DatabaseModels.swift`, `GridFilters.swift`, `TableUpdate.swift` |
| `Sql`, `SqlHighlighter`, `SqlEditor`, `SqlLibrary` | pre-flight checks, `SQLHighlighter.swift`, `SQLTextView.swift`, `SQLFiles.swift` |
| `WorkspaceWindow`, `ConnectionForm`, `TableSelectionForm`, `TableForm`, `StatusBar` | `ContentView.swift` and the three forms |
| `DataGrid`, `FilterPopup` | `DataGridView.swift`, `ColumnFilterPopover.swift` |
| `UpdateDialog`, `ViewSettingsDialog`, `JsonViewerDialog`, `JsonFormatter`, `CsvExport` | the dialogs and sheets |
| `TableWindow` | `TableWindow.swift` |
| `DbmlWriter`, `DbmlExport` | `DBMLWriter.swift`, `DiagramCommands.swift` |
| `Help` | `SQLQuickHelp.swift` |

## What it does

Everything in the macOS README: DB Connection, DB Table Selection (row estimates, Count Rows, sizes, psql `\d`
descriptions, view definitions, function code), the Database drop-down that reconnects to another database on the
same server, Run SQL with the pre-flight checks (allowed statements, pending edits, server-side `PREPARE`, psql-style
error with a caret), read-only transactions for queries and confirmation for writes, CALL and DDL, the editable grid
(cell selection, in-place editing, pending edits highlighted, copy and paste as tab-separated text, Set to NULL,
Revert), editability rules (full column layout with `ctid`, or the primary key), paging, server-side sorting and
filters from the header buttons, Update… with typed casts, View…, Export CSV (loaded or all rows, streamed), View
JSON…, SQL files, Open in New Window, Write DBML and the DBML diagram window, and Quick Help on every control.
`PARITY.md` lists each feature and how far it is ported.

## Differences from the Mac app

* **Keys**: ⌘ → Ctrl, ⌥ → Alt. Run SQL is Ctrl+Enter (also on the new **Table ▸ Run SQL** item); View… Ctrl+Alt+L,
  Update… Ctrl+Alt+U, Export CSV… Ctrl+Shift+E, Write DBML… Ctrl+Alt+D, Open DBML… Ctrl+Alt+O. View ▸ DB Connection /
  DB Table Selection / DB Table switch forms with Ctrl+1 / 2 / 3. (Ctrl+Alt combinations are AltGr on some keyboard
  layouts; the menu takes them first.)
* **Passwords** live in Eclipse secure storage (Windows DPAPI) under `/com.k3jdr.mx/MxDataSQL/user@host:port/database`
  instead of the login Keychain; the check box is "Save password". The workspace key keeps its macOS name
  (`savePasswordInKeychain`). A password saved under the app's old name is not migrated (there is none on Windows).
* **Windows**: every workspace has its own window with its own menu bar; the first one is the main window, and
  closing it quits the app after each open workspace has asked to save. Quick Help is docked in the main window only;
  other windows use it, or View ▸ Float Quick Help.
* **Save panels with options** become a small dialog before the standard Save dialog: Export CSV asks "Loaded Rows /
  All Rows", Write DBML asks for the schemas and "Open the diagram after writing".
* **Finder → Explorer**: Show in Explorer, Show SQL Folder in Explorer. The SQL folder and the recent SQL files are
  plain paths in the app's settings (no sandbox bookmarks).
* **Colours**: the SQL box, View JSON and the diagram use VS Code's Light+ colours (no dark appearance).
* **One statement at a time** is checked before sending (pgjdbc would otherwise split a script into several
  statements); a literal `?` (jsonb operators) is escaped for the driver, so it reaches the server as written.
* **Query result editability** uses pgjdbc's base table and column of each result column instead of the table OID
  and attnum; the rules are the same.
* The grid adds Delete (clear the selected cells as pending edits, as Quick Help describes), Page Up / Page Down /
  Home / End; the SQL box adds Undo / Redo (Ctrl+Z / Ctrl+Y).
