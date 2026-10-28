# MxDataHUB — parity with the macOS app

Every feature of `D:\MyCodeGIT\MxAppsIOS\MxDataHUB\README.md`, and how far the Windows app has it.
**Ported** = same behaviour (with Windows keys), **Partial** = works with the differences noted, **Not yet** = missing.

The database screens use the Mac's SQL unchanged (`SRMStore.swift`; `$n` placeholders rewritten to JDBC's `?` by
`Sql.java`, checked by a unit run) and were read against `data-model/ddl` and `SRM/migrations`. By rule they were not
run against the user's databases during the port, except once by accident: a stray Enter in the focused Host field
connected to `localhost/srm` with the saved password, and that run showed Securities working (search, 45,328
results paged, detail, chart, identifiers, facts, linked tickers) with read-only queries. Writes (portfolio changes,
Build Custom, links) have not been run. Data Refresh was exercised against a local mock of the service's read
endpoints; POST /refresh was never called.

## Window

| Feature | State | Notes |
|---|---|---|
| Sidebar of seven screens, Ctrl+1 … Ctrl+7, Hub menu | Ported | Core `Sidebar`; the Hub menu also has Connect / Reconnect (Ctrl+Shift+K) and Disconnect |
| Status bar: connection, version, security master, last message, busy | Ported | |
| Database drop-down in the toolbar (same server, same user and password; previous one back on failure) | Ported | |
| "Not Connected" / "No Security Master Here" screens with DB Connection… | Ported | |
| Window minimum (1280 × 720) | Partial | 1100 × 640 |
| Panes in SplitPanes, divider remembered per screen | Ported | Core `SplitPanes` |
| Back / Forward (toolbar, hold for places), Go ▸ Back Ctrl+[ / Forward Ctrl+] / History, ⌃← ⌃→, mouse side buttons, picks within a second coalesce | Partial | Arrow beside the button lists the places (no hold); Alt+Left / Alt+Right |
| Selecting and copying: text selectable, rows Ctrl+C / Copy Row / Copy All Rows, Ctrl+A | Ported | Core `TableCopy`, `EditActions`; Securities' results select one row, as on the Mac |
| Error dialog with selectable text and Copy; a service's `{"error": …}` shown as its message | Ported | Core `ErrorDialog`; `Hub.readable` |
| Quick Help docked at the sidebar's bottom, Float, remembered; controls, tables, selection, screen, why empty | Ported | Core `QuickHelp`; texts from `HubQuickHelp.swift` |

## DB Connection

| Feature | State | Notes |
|---|---|---|
| Host, port, user, database, password, SSL disable / prefer / require (no certificate checks) | Ported | Core `Pg` |
| Fields remembered; password saved ("Save password in Keychain") | Ported | Secure storage via `Pg.savePassword("MxDataHUB", …)`; turning it off forgets it; other fields load their own saved password |
| Connect (⇧⌘K → Ctrl+Shift+K) checks the srm schema, then Securities opens | Ported | Enter in a field connects too (the Mac's default action) |
| Disconnect | Ported | |
| Connected / failure message (selectable), no-srm note | Ported | |

## Securities

| Feature | State | Notes |
|---|---|---|
| Search by name or any identifier (ISIN, CUSIP, SEDOL, FIGI, composite / share-class FIGI, ticker, TICKER:MIC, CIK, LEI) | Ported | |
| Type, In (benchmark), Active only | Ported | |
| 1,000 to a page, Previous / Next, "1–1,000 of N securities" | Ported | |
| Excel-style column sort and filters on the server, sort / filter line with Clear Sort / Clear Filters | Partial | Header click opens a menu; Filter dialog with values (counts, other filters applied) or a condition; no ≥ ≤ *between* |
| Detail: price chart, identifiers with validity and source (past ones grey), issuer facts, listings, classification, benchmark weights, corporate actions | Ported | Chart drawn with SWT GC (close under the pointer); facts as one selectable text |
| Links on Ticker / Name | Partial | See *Links* |

## Portfolios

| Feature | State | Notes |
|---|---|---|
| List; New… / Edit… (double-click) with code, name, currency, benchmark, description and its checks; Delete… after asking | Ported | |
| Workflows menu: Optimize / Track / Attribution in MxJobsMGR | Ported | `AppLink.Sample(…, code, benchmark)` |
| Holdings on (dated snapshots); New Snapshot… copies the latest earlier one | Partial | A small dialog with a date picker instead of the popover |
| Add Holding… (⇧⌘N → Ctrl+Shift+N): matches as you type, Detect / identifier type, shares or weight %, checks, stays open | Ported | |
| Paste Holdings… (`identifier [type] shares|weight%`) with ready / not understood counts, Added N, failures | Ported | |
| Copy Benchmark, Normalize Weights, Remove | Ported | Remove also with Delete |
| Valuation with latest prices; checks listed (8 + "and N more") or "Valid…" | Ported | |
| vs Benchmark: portfolio / benchmark / active weights, overlap, active share | Ported | |
| Personal account (V011): type, approach, cash, maintained in; Tax Lots; Open in MxAcctMGR | Ported | `AppLinks.open(new AppLink.MyPortfolio(code))` |
| Holdings' Ticker / Name links | Partial | See *Links* |
| Every change one SQL statement committed on its own | Ported | Autocommit |

## Benchmarks

| Feature | State | Notes |
|---|---|---|
| List (code, name, members, as of); header with method, source, members, snapshots, PROXY and its note | Ported | |
| Weights by sector (bars), index level (line) | Ported | SWT GC |
| Constituents with Filter constituents, totals line | Ported | |
| Build Custom… (equal / market cap, one sector) with its checks | Ported | |
| Availability… | Ported | |
| Track… (MxJobsMGR passive tracking for the benchmark) | Ported | |
| Delete… (custom only, after asking) | Ported | |

## Data Refresh

| Feature | State | Notes |
|---|---|---|
| Categories with state, data date, last load, cadence, loaders (by status), Optional | Ported | |
| Refresh / Refresh All (dependency order, skips optional, stops at a failure, "Refreshing i of n…") | Ported | Not run against the real service (by rule) |
| Preview only (X-SRM-Dry-Run), Log detail Normal / -v / -vv (remembered) | Ported | |
| Service not running: start command, Copy, Check Again | Ported | |
| Automate in MxJobsMGR… (`srm-daily-refresh`) | Ported | |
| Activity: `GET /log` polling (0.7 s / 3 s), restart detection, 20,000 lines | Ported | Checked against a mock |
| Status strip: RUNNING / PREVIEWING with loader, bar, latest line, time; last refresh with Show Its Lines; Idle | Ported | |
| Running category highlighted (also loads started elsewhere) | Ported | |
| Console: START / END lines, ▶ ✓ ✗ · ! marks, WARN / ERR / DBG, colours, rule after each refresh, wrapped lines under the text column | Ported | `StyledText` |
| Filters: level, run, text; Follow; Copy (selection or all shown, tab-separated); Clear; Load History | Ported | |
| ⌘F find in the console | Partial | Ctrl+F goes to the Filter field |
| `-srmLoaderPort` | Ported | |

## Load History, Services & Workflows

| Feature | State | Notes |
|---|---|---|
| Identifier coverage (with bars), sources (terms as tooltip), snapshot history (orange flags, V010 note), loads (status colours), Refresh, Data Refresh | Ported | |
| Workflows: samples by area, services' running state, Open in MxJobsMGR for the selected portfolio | Ported | |
| Layer 3 Services | Ported | Core `ServicesView` |
| `-servicesTab Services` | Ported | |

## Links

| Feature | State | Notes |
|---|---|---|
| `mxdatahub://portfolio/X`, `benchmark/X`, `data-refresh`, `services` (launch or hand-over) | Ported | Selected once the lists load |
| Register the scheme | Ported | Help ▸ Register mxdatahub:// Links (`AppLinks.registerSchemes`); not invoked during the port |
| Links on tickers and names in `srm.security_link`: Ctrl+K (column under the pointer), context menu, link colour, click opens, Link dialog (Link on, Text to display, Address, pages about the security, Use the Link Copied in MxNoteONE, Remove Link) | Partial | Hover text edited as plain text (the Mac's RTF kept while unchanged); hover card is a tooltip + context menu (Edit / Open / Copy / Remove); no card preview. Reading links was seen working; saving not yet run |

## Launch arguments

| Feature | State | Notes |
|---|---|---|
| `-autoConnect YES`, `-section`, `-query`, `-host`, `-port`, `-database`, `-sslMode` | Ported | Without `-autoConnect` the app starts not connected |
| `-user` | Partial | Taken by the Eclipse launcher: use `-dbUser` |
| `-srmLoaderPort`, `-servicesTab` | Ported | |
| `PGPASSWORD` in Debug builds | Not yet | No debug build |
| `-linkHoverTest` (Debug self-test) | Not yet | |
| `-layoutPreview YES` | New | Shows the database screens empty while not connected |
