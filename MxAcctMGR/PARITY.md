# MxAcctMGR — parity with the macOS app

Every area of `D:\MyCodeGIT\MxAppsIOS\MxAcctMGR\README.md`, and how far the Windows app has it. **Ported**: works as on
the Mac (with Windows keys); **Partial**: the main part works, what is missing is named; **Not yet**: the screen shows
its lesson and Quick Help and says it isn't on Windows yet.

Verified by building, by `-screen` smoke tests in the service-down state (the accounts, analytics and optimizer
services were not running), and by a pure-Java check of the ported engine. No screen has been exercised against live
data yet.

## The window

| Area | State | Notes |
| --- | --- | --- |
| Sidebar guide: Banking, Investments, Knowledge Library, Reporting, collapsible, remembered | Ported | Steps 4 and 5 rename with My approach |
| Welcome / Choose Your Path in the Guide menu only | Ported | |
| Right-click a sidebar page for its link (Links on sidebar pages) | Not yet | |
| Quick Help docked under the sidebar, float / dock | Ported | Core QuickHelp; every control has help; tables per row |
| Toolbar Back / Forward with the places before / after | Ported | Hold → the buttons' drop-down arrows |
| Toolbar Account (fixed to My Portfolio on Investments) and Engine | Ported | Engine: Layer 3 services / Built-in (Java) |
| Screen title, collapsible yellow "Why this step" (remembered per screen), Previous / Next | Ported | Texts verbatim (`text\Lessons.java`) |
| Status bar: account, type, value, benchmark, last action, educational banner | Ported | |
| Settings ▸ Text (fonts per kind, Step Down / Up, Restore) | Partial | A size step for Headings and for Numbers and Tables; no font names / styles |
| Settings ▸ Banking (new entry kind, check number, date) | Ported | |
| Settings ▸ Guidance (Full / Brief / Minimal) | Ported | Minimal also drops the lesson-only pages from sidebar, Guide menu, Previous / Next |
| Selecting and copying: selectable text, Copy Page Text, Copy Section, table rows | Ported | Copy Page Text Ctrl+Alt+Shift+C; Copy Section on a box's context menu; tables copy rows (TableCopy) |
| Data problem banner (Try Again, Layer 3 Services…) | Ported | Banking pages show their own "accounts service not running" panel with the start command |

## Menus

| Menu | State | Notes |
| --- | --- | --- |
| Settings… | Ported | File ▸ Settings… Ctrl+, |
| File: New / Open / Open Recent / Close / Save / Revert | Ported | Save / Discard / Cancel before leaving unsaved changes; conflict dialog on a refused save |
| File: Duplicate, Rename, Import Holdings, Export Portfolio | Not yet | |
| Edit: standard items, Copy Page Text | Ported | |
| Edit ▸ Link… Ctrl+K | Not yet | Links on symbols and names aren't ported |
| Go: Back, Forward, History, Previous / Next Step | Ported | Ctrl+[ / Ctrl+], Alt+Left / Right, mouse buttons; Ctrl+Alt+Up / Down |
| Guide | Ported | |
| Portfolio: Account ▸, Optimize / Build Tracker Ctrl+R, Run Attribution Ctrl+Shift+A, Engine ▸, Copy Account into the Open Portfolio | Ported | Also Add Securities… Ctrl+Shift+N and My Approach ▸ |
| Portfolio: Decisions…, Policy Benchmarks… | Not yet | |
| Reports: Report Library, Morning Briefing, Earnings Calendar / Today, VIX, the Five Most Important, tiers, Compute | Partial | Items open the screens; the walkthroughs open the report page (see below); Simulate the Long Run is disabled |
| View: Quick Help, Float Quick Help | Ported | |

## Banking

| Area | State | Notes |
| --- | --- | --- |
| Checking Accounts: register, kinds, check numbers, targets with last four, Payment / Deposit / ✓ / Balance | Ported | Not-cleared rows shaded yellow |
| New entry line (target fills its usual amount and category) | Ported | Ctrl+Enter or Return in Amount |
| Edit Entry…, target note, Void / Restore, Mark Cleared / Not Cleared (one or many), Move Up / Down, Copy Entry | Ported | Alt+Up / Down and Ctrl+Alt+K in the table |
| Hide entries before / Recent, Rows | Ported | Rows shared by every table, remembered |
| Column header sort and filter (Excel-style) | Partial | Sort by clicking a title; no filters |
| Bank lines: Accept, Match…; Check PNC Downloads; New / Edit account; 30-second refresh | Ported | |
| Target's link on the register (link button, hover card) | Not yet | The link's address shows in the target's Quick Help |
| Credit Card Accounts (registers, statements, Import File…, Remove…) | Not yet | |
| Expense Ledgers: tiles, alerts, register (New item, Edit, Void / Restore, Mark Made, Delete…), Budget, Subscriptions, Assets, Training, Travel | Partial | New / Edit ledger, Fund…, Add from card charges, Renew, Approve, line / asset / training / trip editors not yet |
| Target Accounts: table, show inactive, Paid in year | Ported | Sorting by column title |
| New Target Account… / Edit Target Details… (all fields, funding sources) | Ported | Password and user keys in secure storage |
| Edit Target Account Name… (unique names) | Ported | |
| Add from Card Registers…, New Category… | Ported | |
| Links on target accounts (Link dialog, hover card) | Partial | Open Link / Open the Logon Page from the row's menu; no Link dialog |
| Secure Accounts: table, Keys, Renewing, show inactive | Ported | Keys read without decrypting the values |
| New Secure Account… / Edit Service Details… / Edit Service Name… / Open the Service Page | Ported | Keys in `/com.k3jdr.mx/secure-accounts/<id>` (`service key` for MxJobsMGR) |
| `mxacctmgr://secure-accounts/<id>` and `/new?name=&api=&url=` | Ported | Also from a second launch (single instance) |
| Import Passwords and Keys… (from Excel) | Not yet | |

## Investments

| Area | State | Notes |
| --- | --- | --- |
| My Portfolio bar: open portfolio, version, Edited, My approach, account type, Save / Revert / Open / New, Load Prices, Start From a Sample | Ported | Decisions… not yet |
| 1. My Holdings: table, Edit Holding… (tax lots), remove, Copy Row / Column, tiles, checks, asset mix | Ported | Shares edited in Edit Holding (not in the cell) |
| Add Securities… (search, kind, sort in the database, Show More, details, add with a lot) | Partial | No filter row / facet menus; no links on its rows |
| Links on symbols and names (Ctrl+K, hover cards) | Not yet | |
| 2. My Benchmark: picker, summary, members, overlap, active share, held names, sectors, asset mix, largest members | Ported | Policy Benchmarks… not yet |
| 3. Risk Today: tiles, weight vs risk, risk by sector, tracking-error drivers; pending inputs | Ported | |
| 4. Optimize: limits, decision, metrics, binding limits, largest changes, sector active weights | Ported | |
| 4. Build the Tracker: names, selection, budget, curve, sector gaps, three ways to be passive, trades | Ported | |
| 5. Trades & Taxes: tiles, How far to go, trades, taxes by lot (FIFO / highest cost, rates) | Ported | |
| 5. Rebalance Bands: ± points / ± 25%, to target / edge, band tracks, the past year, trades and taxes | Ported | |
| 6. Attribution: source, method, tiles, reading, effects by sector and month, table, contributors, assumptions | Ported | Month by month as bars (no line overlay) |
| Engines: Layer 3 services and Built-in | Ported | `port\Engine.java` (active FISTA + bisection, tracker, risk); attribution only through analytics, as on the Mac |
| `mxacctmgr://my-portfolio/<CODE>` | Ported | |

## Knowledge Library and Reporting

| Area | State | Notes |
| --- | --- | --- |
| Report Library: top five, morning reading, cards by tier with official sources, driver matrix | Partial | Notes on reports (Add / Edit Notes, hover cards) not yet |
| Morning Briefing | Not yet | |
| Earnings Calendar, Earnings by Day | Not yet | |
| CBOE Volatility Index (VIX) | Not yet | |
| Compute Hyperscalers Tracking | Not yet | |
| Report Walkthrough (seven steps, Behaviour Lab simulation) | Partial | The report's card and official sources; the steps and simulation are not yet |
| Yield Curve Report's real curve | Not yet | |
| Why Optimize? (two demos), Why Attribution? (two-sector demo), Active vs Passive (fees, table) | Ported | Charts as SWT bars / lines |
| Sample Portfolios (cards, Use, Copy to My Portfolio) | Ported | |
| Spending Analysis | Not yet | |
| SRM Table: search, type, benchmark, active only, sort, paging, detail | Partial | No column filters, no closes chart (last close in the text), no links |
| Layer 3 Services | Ported | Core `ServicesView` |

## Build and run, launch arguments

| Area | State | Notes |
| --- | --- | --- |
| `-screen`, `-sample`, `-srmAccount`, `-quickHelpVisible NO` | Ported | |
| `-addSecurities`, `-report`, `-reportStep`, `-reaction`, `-earnings…`, `-holding`, debug self-tests | Not yet | |
