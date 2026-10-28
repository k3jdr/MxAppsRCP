# MxDataHUB for Windows

The **security reference master** (SRM) hub, a Windows rebuild on Eclipse RCP (e4) of the macOS app in
`D:\MyCodeGIT\MxAppsIOS\MxDataHUB` (see its README for the full story). It works on the PostgreSQL schema `srm`:
securities and their identifiers, portfolios and benchmarks, data refresh through the SRM loader service, load history,
and the Layer 3 services and workflows. The schema, the loaders (`SRM/srm.py`) and the services stay in the MxAppsIOS
checkout; this app only uses them.

## Build and run

```bat
mvn -B -o -q -f MxDataHUB\pom.xml clean verify
MxDataHUB\com.k3jdr.mxdatahub.product\target\products\com.k3jdr.mxdatahub.product\win32\win32\x86_64\MxDataHUB\MxDataHUB.exe
```

It needs a Java 21 runtime. Settings live in `%APPDATA%\MxAppsRCP\MxDataHUB\workspace`; a saved password in Eclipse
secure storage (Windows DPAPI) under `/com.k3jdr.mx/MxDataHUB/user@host:port/database`. One copy runs at a time: a
second launch (an `mxdatahub://` link, `-section …`) is handed to the running one.

Check a build without clicking through it (the dead endpoint keeps a stray Enter from reaching a real database):

```powershell
& releng\tools\smoke-test.ps1 -App MxDataHUB -Seconds 12 -Shot $env:TEMP\mxdatahub.png `
    -Arguments '-section','Portfolios','-host','127.0.0.1','-port','9','-dbUser','nobody'
```

## What it does

One window: a sidebar of seven screens (**Ctrl+1 … Ctrl+7**, also the **Hub** menu) with Quick Help docked at its
bottom, the screen with **‹ Back / Forward ›** and the **Database** drop-down above it, and the status bar below
(connection, server version, whether the database has the security master, the last message, a bar while working).

| Screen | |
|---|---|
| **DB Connection** | Host, port, user, database, password, SSL mode (`disable` / `prefer` / `require`, no certificate checks). The fields are remembered after a successful connect; **Save password** keeps the password in secure storage. **Connect** (Ctrl+Shift+K, or Enter in a field) checks for the `srm` schema and opens Securities. The toolbar's **Database** drop-down reconnects to another database on the same server; on failure the previous one is reconnected and the error shown. |
| **Securities** | Search by name or any identifier (ticker, `TICKER:MIC`, ISIN, CUSIP, SEDOL, FIGI, CIK, LEI) with Type, In (benchmark) and Active only, 1,000 to a page (Previous / Next). Click a column header to sort it or filter it by values or a condition — on the server, over every page. The right side shows the price chart (400 days, the close under the pointer), identifiers with validity and source (past ones grey), issuer and security facts, listings, classification, benchmark weights and corporate actions as one selectable text. |
| **Portfolios** | New… / Edit… (double-click) / Delete…, Workflows ▾ (Optimize, Track, Attribution in MxJobsMGR). Dated snapshots: **Holdings on**, **New Snapshot…** (copies the latest earlier one). **Add Holding…** (Ctrl+Shift+N) shows the securities an identifier matches as you type and stays open for the next; **Paste Holdings…** takes `identifier [type] shares|weight%` lines; **Copy Benchmark ▾**, **Normalize Weights**, **Remove** (or Delete). The snapshot is valued and checked (`srm.portfolio_validate`); **vs Benchmark** shows the active weights, overlap and active share. A personal account shows its type, approach, cash and maintainer, its **Tax Lots**, and **Open in MxAcctMGR** (`mxacctmgr://my-portfolio/CODE`). |
| **Benchmarks** | Members, weights and shares (filter by ticker, name or sector), weights by sector (bars), the index level (line), the PROXY note. **Build Custom…** (equal or cap weight, optionally one sector), **Availability…**, **Track…** (MxJobsMGR passive tracking), **Delete…** for custom ones. |
| **Data Refresh** | The SRM loader service's categories (`GET /categories`, 127.0.0.1:8770 or `-srmLoaderPort`) with state, data date, last load, cadence and loaders; **Refresh** / **Refresh All** (dependency order, skips optional ones, stops at a failure), **Preview only**, **Log detail** (remembered), **Automate in MxJobsMGR…**. **Activity** polls `GET /log` (0.7 s while running, else 3 s): a status strip (RUNNING with the loader, a bar and the time so far; the last refresh's outcome with Show Its Lines; Idle), filters by level, run and text, Follow, Copy, Clear, and a console with START / END lines, ▶ ✓ ✗ marks, WARN / ERR / DBG and a rule after each refresh. When nothing answers it shows the start command with Copy and Check Again. |
| **Load History** | Identifier coverage (with bars), sources and their terms, snapshot history (fewer than two snapshots or a gap over 35 days in orange), and the last 300 loads (`srm.load_run`) with status colours. |
| **Services & Workflows** | Workflows: the MxJobsMGR samples by area with each service's running state (`GET /health`), **Open in MxJobsMGR** for the portfolio selected in Portfolios. Layer 3 Services: the core `ServicesView`. |

Every change is one SQL statement committed on its own (autocommit). Every query runs off the UI thread. Tables select
rows (Shift/Ctrl-click, Ctrl+A); **Edit ▸ Copy** (Ctrl+C) and the context menu's **Copy Row / Copy All Rows** copy
them tab-separated under the column titles. Text in detail panes, placeholders and the Activity console can be
selected and copied. Quick Help (View ▸ Quick Help, Ctrl+Alt+/; View ▸ Float Quick Help) describes what is under the
pointer, else the selection, else the screen — including why a screen is empty.

**Back and Forward**: ‹ Back / Forward › in the toolbar (the arrow beside each lists the places before / after),
Go ▸ Back (Ctrl+[), Forward (Ctrl+]) and History, Alt+Left / Alt+Right, and a mouse's side buttons. A place is a
screen with the security, portfolio or benchmark selected on it; picks within a second on one screen count as one.

**Links on tickers and names** (`srm.security_link`, V014, shared with MxAcctMGR): Edit ▸ Link… (Ctrl+K — the column
under the pointer, else the ticker) or the row's context menu (Add / Edit / Open / Copy / Remove Link on Ticker or
Name). A linked cell shows its words in the link colour; a click opens it; the pointer shows its hover text and
address. The Link dialog has Link on, Text to display, Address, hover text, pages about the security (Nasdaq.com, SEC
EDGAR, Yahoo Finance, a web search) and Use the Link Copied in MxNoteONE.

**Links into the app**: `mxdatahub://portfolio/<CODE>`, `mxdatahub://benchmark/<CODE>`, `mxdatahub://data-refresh`,
`mxdatahub://services`, as a launch argument or handed over by a second launch. A portfolio or benchmark named before
the connection is up is selected once the lists load. **Help ▸ Register mxdatahub:// Links** registers the scheme for
this user (HKCU) with this launcher.

**Launch arguments**: `-autoConnect YES` (connects at startup with the saved password — otherwise the app starts not
connected), `-section Portfolios` (or `"Data Refresh"`, `"Services & Workflows"`, …), `-servicesTab Services`,
`-query AAPL`, `-host`, `-port`, `-dbUser`, `-database`, `-sslMode`, `-srmLoaderPort 8790`, and for checking layouts
without a database `-layoutPreview YES` (the database screens show empty while not connected).

## Scheduled data refresh (the PC that hosts the database)

`schedule\srm_refresh.py` runs Data Refresh unattended from Windows Task Scheduler, on the PC with the `srm`
database (192.168.0.223). It uses the same SRM loader service and the same rules as **Refresh All**: categories in
dependency order, the optional Portfolio snapshots left out, stopping at the first category that fails. It refreshes
every daily category, and the weekly and monthly ones only when they are no longer fresh. When the service isn't
running it starts it (`Services\mxservices.py start srm-loader`, no window) and leaves it running for the app. It
copies every line the loaders print into its own log, then ends with the service's data-quality checks (`GET /status`).
While it runs, **Data Refresh ▸ Activity** shows it like any other refresh; **Load History** keeps it afterwards.

```bat
MxDataHUB\schedule\srm-refresh.cmd install                 :: weekdays at 18:30 (the Mac's SRM Daily Refresh time)
MxDataHUB\schedule\srm-refresh.cmd install --time 06:15 --days daily
MxDataHUB\schedule\srm-refresh.cmd status                  :: the task's next / last run, the last refresh, saved secrets
MxDataHUB\schedule\srm-refresh.cmd run --dry-run           :: what it would run now, without loading anything
MxDataHUB\schedule\srm-refresh.cmd run                     :: refresh now, in this console
MxDataHUB\schedule\srm-refresh.cmd uninstall
schtasks /run /tn "MxDataHUB SRM Refresh"                  :: start the scheduled task now
```

More run options (also accepted by `install`, which hands them to the task): `--all` (every category, fresh or not),
`--categories a,b` (exactly these), `--skip a,b`, `--include-optional`, `--keep-going` (go on after a failed
category), `--verbosity 1|2` (srm.py's `-v` / `-vv`), `--log-dir`, `--ios-repo`. Logs:
`%LOCALAPPDATA%\MxAppsRCP\MxDataHUB\refresh\refresh-<date>-<time>.log` (the last 90) and `last-run.json`. Exit code 0
means refreshed (some loaders may be partial); 1 means a category failed, or the service or database wasn't reachable.

**Secrets.** On a Mac, srm.py finds the database password and API keys in the Keychain. On Windows it looks in the
**Credential Manager**, under generic credentials named `service:account`; `status` lists which are saved. Each
`cmdkey` prompts for its value:

```bat
cmdkey /generic:SRM-postgres:postgres@192.168.0.223/srm /user:postgres /pass
cmdkey /generic:SRM-api:fmp /user:fmp /pass
cmdkey /generic:SRM-api:openfigi /user:openfigi /pass
cmdkey /generic:SRM-api:massive-s3-access /user:massive-s3-access /pass
cmdkey /generic:SRM-api:massive-s3-secret /user:massive-s3-secret /pass
```

SEC EDGAR wants a User-Agent with a contact e-mail. Put `"sec_user_agent": "Name you@example.com"` in
`MxAppsIOS\MxDataHUB\SRM\config.local.json`, which the loaders read on every load. Or set `SRM_SEC_USER_AGENT`
and restart srm-loader from a console opened after `setx`.

The task runs as you **while you are signed in** (a locked screen is fine). It wakes the PC, and a run missed while the
PC was off starts when it is back. To run it while signed out too, open it in Task Scheduler, choose *Run whether
user is logged on or not*, and give your Windows password there. That logon keeps the Credential Manager available.

## Differences from the Mac app

* ⌘ is Ctrl, ⌥⌘/ is Ctrl+Alt+/, ⌃← / ⌃→ are Alt+Left / Alt+Right; the Keychain is Windows secure storage.
* `-user` is taken by the Eclipse launcher (the user area), so the user is `-dbUser` (`-user` still works when it
  reaches the app). Debug builds' `PGPASSWORD` fallback is not ported.
* Back / Forward list their places from the arrow beside the button instead of holding it.
* The column headers' ▾ filter popover is a header menu (Sort Ascending / Descending, Clear Sort, Filter…, Clear
  Filter) and a Filter dialog: tick values (with counts) or give a condition; ≥, ≤ and *between* are not offered.
* The Link dialog edits the hover text as plain text (a formatted hover text made on the Mac is kept while the plain
  text is unchanged); the hover card is a tooltip plus the row's context menu, with no card preview.
* The Activity console has no find bar: Ctrl+F in it goes to the Filter field.
* Segmented pickers are toggle buttons; New Snapshot's popover is a small dialog with a date picker; confirmations are
  message boxes. "Not installed in Applications" reads "install it beside MxDataHUB, or register its links".
* The window's minimum is 1100 × 640.
* At startup the keyboard is in the sidebar, not in the Host field (Enter in a connection field connects).

## Source layout (`com.k3jdr.mxdatahub/src/com/k3jdr/mxdatahub`)

| File | Purpose | Mac counterpart |
|---|---|---|
| `LifeCycle.java` | One running copy, window bounds | `MxDataHUBApp.swift` |
| `MainPart.java` | Sidebar, toolbar, status bar, menus, Back / Forward, launch arguments and links | `ContentView.swift`, `MxDataHUBApp.swift`, `Navigation.swift` |
| `Hub.java` | Connection fields and status, shared lists, busy / errors | `SRMStore.swift` (connection) |
| `Db.java`, `Sql.java`, `Rows.java`, `GridOptions.java` | Every query (the Mac's SQL, `$n` rewritten for JDBC), row types, server-side sort and filters | `SRMStore.swift`, `PostgresService.swift`, `Shared/GridFilters` |
| `ConnectionScreen.java` | DB Connection | `ConnectionView.swift` |
| `SecuritiesScreen.java`, `FilterDialog.java`, `RichLines.java`, `LineChart.java` | Securities | `SRMSecuritiesView.swift` |
| `PortfoliosScreen.java`, `PortfolioDialogs.java` | Portfolios and their dialogs | `SRMPortfoliosView.swift` |
| `BenchmarksScreen.java`, `BarChart.java` | Benchmarks, Build Custom…, Availability… | `SRMBenchmarksView.swift` |
| `DataRefreshScreen.java`, `ActivityPanel.java`, `RefreshService.java` | Data Refresh and Activity | `DataRefreshView.swift` |
| `LoadsScreen.java` | Load History | `SRMLoadsView.swift` |
| `ServicesScreen.java` | Services & Workflows | `ServicesWorkflowsView.swift`, `Shared/ServicesView.swift` |
| `Links.java`, `LinkDialog.java` | Links on tickers and names | `HubLinks.swift`, `Shared/Links` |
| `HubHelp.java` | Quick Help texts | `HubQuickHelp.swift` |
| `Tables.java`, `Placeholder.java`, `Fmt.java`, `Section.java`, `Screen.java` | Helpers | `Formatting.swift`, `TableCopy.swift` |
