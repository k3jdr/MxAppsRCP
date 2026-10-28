# MxAcctMGR for Windows

The macOS app **MxAcctMGR** (`D:\MyCodeGIT\MxAppsIOS\MxAcctMGR`, SwiftUI) re-built on the Eclipse RCP (e4) platform,
Java 21: a personal-accounts IDE that **teaches** — Banking registers and secure accounts, and an Investments path that
walks your own account (My Portfolio) through six steps, explaining *why* before showing the numbers. The macOS README
stays the specification; this file says how the Windows app is built and run and where it differs. `PARITY.md` lists
every area of the Mac app and how far it is ported.

> **Educational only.** Everything — accounts, securities, prices, benchmarks — is real data from the security master
> (MxDataHUB's `srm` schema) read through the Layer 3 **accounts** service; nothing in MxAcctMGR is investment, tax or
> financial advice. The status bar says so on every screen.

## Build and run

```bat
mvn -B -o -q -f D:\MyCodeGIT\MxAppsRCP\MxAcctMGR\pom.xml clean verify
MxAcctMGR\com.k3jdr.mxacctmgr.product\target\products\com.k3jdr.mxacctmgr.product\win32\win32\x86_64\MxAcctMGR\MxAcctMGR.exe
```

The shared core (`Shared\com.k3jdr.mx.core`) and the target platform must be installed first (`mvn clean install` at the
root, see `DEVELOPING.md`). A Java 21 runtime must be on the PATH.

Check a build without clicking through it:

```powershell
& releng\tools\smoke-test.ps1 -App MxAcctMGR -Seconds 12 -Shot D:\MyCodeGIT\MxAppsRCP\.smoke\mxacctmgr.png -Arguments '-screen','checking'
```

**Launch arguments** (as on the Mac): `-screen <id>` opens a screen (`checking`, `cards`, `expenseLedgers`, `expenses`
— Target Accounts —, `secureAccounts`, `welcome`, `choosePath`, `myPortfolio`, `myBenchmark`, `myRisk`, `myImprove`,
`myTrades`, `myAttribution`, `reportLibrary`, `reportWalkthrough`, `whyOptimize`, `whyAttribution`, `activeVsPassive`,
`samples`, `spending`, `securities`, `services`, …); `-sample CODE` selects a sample account; `-srmAccount CODE` sets My
Portfolio's security-master account; `-quickHelpVisible NO` hides Quick Help. An `mxacctmgr://` link on the command line
(or handed over by a second launch) is opened.

**The services.** Banking and My Portfolio live in the security master through the **accounts** service
(127.0.0.1:8774); risk inputs and attribution come from **analytics** (8772); the Layer 3 optimizers are
**portfolio-java** (8766) and **portfolio-cpp** (8767). Start them with
`python "D:\MyCodeGIT\MxAppsIOS\Services\mxservices.py" start`. When the accounts service doesn't answer, each Banking
page says *The accounts service is not running* with that command (Copy Command, Try Again, Layer 3 Services…) and the
other pages show the same in a banner; nothing is made up in its place.

## What it does

* **The frame** (`MainPart`): the sidebar guide in four collapsible groups — Banking, Investments, Knowledge Library,
  Reporting (collapsed groups remembered) — with Quick Help docked under it (View ▸ Quick Help Ctrl+Alt+/, Float);
  the toolbar's **‹ Back / Forward ›** (their arrows list the places before / after), the screen's title and the
  account, **Account** (the samples and My Portfolio; fixed to My Portfolio on the Investments steps) and **Engine**
  (*Layer 3 services* or *Built-in (Java)*); each screen's title, its line, the yellow **Why this step** lesson
  (folds away, remembered per screen) and **Previous / Next** step; the status bar (account, type, value, benchmark,
  last action, busy mark, educational notice). Menus: File (New / Open / Open Recent / Close / Save / Revert to Saved,
  Settings… Ctrl+,), Edit (Cut/Copy/Paste/Select All on fields and table rows, **Copy Page Text** Ctrl+Alt+Shift+C),
  Go (Back Ctrl+[, Forward Ctrl+], History ▸, Previous Step Ctrl+Alt+Up, Next Step Ctrl+Alt+Down; also Alt+Left /
  Alt+Right and a mouse's back / forward buttons), Guide (Welcome Ctrl+0, Choose Your Path, steps 1–6 Ctrl+Alt+1–6,
  Learn the Why ▸, Spending Analysis, SRM Table, Sample Portfolios Ctrl+Shift+L, Layer 3 Services Ctrl+Alt+L),
  Portfolio (Account ▸, Optimize My Portfolio / Build My Index Tracker Ctrl+R, Run Attribution Ctrl+Shift+A, Add
  Securities… Ctrl+Shift+N, Engine ▸, My Approach ▸, Copy Account into the Open Portfolio), Reports (Report Library
  Ctrl+Alt+R, Morning Briefing Ctrl+Alt+M, Earnings Calendar Ctrl+Alt+E, Earnings Today Ctrl+Alt+Shift+E, VIX
  Ctrl+Alt+V, The Five Most Important Ctrl+Shift+1–5, one submenu per tier, Compute Hyperscalers Tracking Ctrl+Alt+U),
  View, Help (Register mxacctmgr:// Links, About). **Settings**: Text (headings, numbers and tables: Step Down / Step Up
  / Restore Defaults), Banking (what a new checking entry starts with), Guidance (Full / Brief / Minimal).
* **Banking** (`bank\`), through the accounts service: **Checking Accounts** — the shared check register (Line, Date,
  Num, Target, Payment, Deposit, ✓, Balance; entries not cleared shaded yellow), the New entry line (date, kind or
  check number, target with its last four digits, amount; a known target fills its usual amount and category), Edit
  Entry…, the target's note, Move Up / Move Down (Alt+Up / Alt+Down), Mark Cleared / Not Cleared (Ctrl+Alt+K), Void /
  Restore, Copy Entry, Hide entries before / Recent, Rows, the bank's lines (Accept, Match…), Check PNC Downloads,
  New / Edit account, a refresh every 30 seconds; **Target Accounts** — the table (name, category, funding, this year,
  total paid, last paid), New Target Account… and Edit Target Details… (every field; the password and user keys in
  secure storage), Edit Target Account Name… (unique names), Add from Card Registers…, New Category…; **Secure
  Accounts** — the Service Product Table (product, status, service, user name, Keys stored), Renewing total, New Secure
  Account… / Edit Service Details… (product, service, owner, subscription payment and renewal; product key, service
  key and password in secure storage, masked unless shown, each with Copy), Edit Service Name…, Open the Service Page;
  **Expense Ledgers** — the ledger's tiles and alerts, Register (New item, Edit Entry…, Void / Restore, Mark Made,
  Delete… after a confirmation), Budget, Subscriptions, Assets, Training, Travel.
* **Investments** (`invest\`): the **My Portfolio bar** (the open portfolio, version, *Edited* with what changed, My
  approach, account type, Save, Revert, Open…, New…, Load Prices, Start From a Sample); **1. My Holdings** (holdings
  table, Edit Holding… — the tax lots editor —, Add Securities… searching the security master, remove, Copy Row / Copy
  Column; value, look-through, weighted fee, unrealised gain; Check before you go on; asset mix); **2. My Benchmark**
  (benchmark picker, members, overlap, active share, sectors and asset mix against it, largest members); **3. Risk
  Today**; **4. Optimize** (limits, decision, metrics, binding limits, largest changes, sector active weights) or
  **Build the Tracker** (names, selection, budget, the tracking-error-vs-names curve, sector gaps, three ways to be
  passive, trades); **5. Trades & Taxes** (turnover, cost, realised gains, tax, How far to go, trades, taxes by lot with
  FIFO / highest-cost-first and the rates) or **Rebalance Bands** (± points or ± 25%, to the target or the band's edge,
  each class on its band, the stock share through the past year, trades and taxes); **6. Attribution** (this account or
  another srm portfolio, Brinson-Fachler / Brinson-Hood-Beebower, effects by sector and month, contributors,
  assumptions, publish / review). One portfolio open at a time (File ▸ New / Open / Save / Revert / Close, Open Recent;
  leaving one with unsaved changes asks Save / Discard / Cancel; a save made from an old version is refused). The
  **engine** is the Layer 3 services or the Java port of the built-in Swift engine (`port\Engine.java`: active
  mean-variance with FISTA and λ bisection, the stratified tracker, the risk report).
* **Knowledge Library and Reporting** (`pages\`): Welcome, Choose Your Path, Why Optimize? (diversification and the
  stock / bond trade-off from the analytics service), Why Attribution? (the two-sector demo), Active vs Passive (fees
  and the comparison), the **Report Library** (top five, morning reading, every report's card by tier with its official
  source, the driver matrix), the Report Walkthrough's report and official sources, **Sample Portfolios**, the **SRM
  Table** (search, type, benchmark, active only, sorting, paging, the selected security's detail) and **Layer 3
  Services** (the core `ServicesView`). The other screens show their lesson and what they do, and say they aren't on
  Windows yet.
* **Links**: `mxacctmgr://my-portfolio/<CODE>` opens that security-master account as My Portfolio;
  `mxacctmgr://secure-accounts/<id>` opens its Edit Service Details; `mxacctmgr://secure-accounts/new?name=&api=&url=`
  opens New Secure Account filled in (or the account of that name). Help ▸ Register mxacctmgr:// Links registers the
  scheme for this user.

## Mac → Windows

| macOS | Windows |
| --- | --- |
| ⌘ / ⌥ / ⇧ / ⌃ shortcuts | Ctrl / Alt / Shift; ⌃⌘1–5 (the five reports) → Ctrl+Shift+1–5; ⌥⌘↑/↓ Move Up/Down in the register → Alt+Up/Down (Ctrl+Alt+Up/Down stay Previous / Next Step) |
| MxAcctMGR ▸ Settings… ⌘, | File ▸ Settings… Ctrl+, — Text is a size step for headings and for numbers and tables (no font names) |
| Keychain (`…secure-accounts`, `…target-accounts`) | Eclipse secure storage (Windows DPAPI): `/com.k3jdr.mx/secure-accounts/<id>` (`service key`, `product key`, `password` — MxJobsMGR reads `service key`) and `/com.k3jdr.mx/MxAcctMGR/target-accounts/<id>` (`password`, `user keys`) |
| ⌃← / ⌃→, hold ‹ › | Alt+Left / Alt+Right, mouse back / forward; the ‹ › buttons' arrows list the places |
| Excel-style column header filters | Click a column's title to sort (Clear Sort in the register); no filter menus |
| Swift Charts | Small SWT charts: horizontal bars, a line chart, band tracks |
| *Built-in (Swift)* engine | *Built-in (Java)* — the same algorithms, ported |
| `python3 Services/mxservices.py start` | `python "D:\MyCodeGIT\MxAppsIOS\Services\mxservices.py" start` (`ServiceCatalog.startCommand()`) |

## Source layout

```
com.k3jdr.mxacctmgr/src/com/k3jdr/mxacctmgr/
  MainPart, LifeCycle, App, Screen, Pages, Settings, SettingsDialog, ErrorAlert   the frame
  ui/      Page (title, line, lesson, Previous/Next), Parts (lesson card, tiles, boxes, service-down panel, tables,
           formats), FormDialog, Bars, LineChart, PageText (Copy Page Text, Copy Section)
  bank/    BankClient, CheckingPage, EditEntryDialog, TargetAccountsPage, TargetDialog, NameDialog,
           SecureAccountsPage, SecureDialog, KeyStore, ExpenseLedgersPage, J, Kinds
  invest/  PortfolioStore (accounts, the open portfolio, saves, runs), InvestPage (the My Portfolio bar), HoldingsPage,
           HoldingDialog, AddSecuritiesDialog, RiskPage, ImprovePage, TradesPage, AttributionPage, InvestParts,
           PortfolioDialogs, ConflictDialog
  pages/   LearnPage, LibraryPages (Report Library, Samples, Walkthrough), SecuritiesPage, ServicesPage, NotYetPage
  port/    the model, the built-in engine and the service clients, ported from Optimizer.swift, Attribution.swift,
           ServiceClient.swift, SRMData.swift, Portfolio.swift, Universe.swift, Samples.swift (no SWT)
  text/    the teaching texts verbatim: Lessons (every page's line and Why this step), Help (Quick Help),
           LearnTexts, ReportCatalog
```
