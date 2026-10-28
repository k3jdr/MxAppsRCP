# MxJobsMGR — parity with the macOS app

Every feature of [MxAppsIOS/MxJobsMGR/README.md](../../MxAppsIOS/MxJobsMGR/README.md) and its screens
(`c4/ui-screen-catalog.md`), and where the Windows port stands. **Ported** = same behaviour (Windows keys);
**Partial** = works with a noted gap; **Not yet** / **N/A**.

## Files and model

| Feature | State | Notes |
| --- | --- | --- |
| Jobspace JSON (`format`, `formatVersion`, subscriptions, nodes with kind settings, arguments, outcome, exceptions, links, run settings) | Ported | `model/`; missing or wrong-typed keys fall back to the Mac defaults; links to missing nodes dropped |
| Written exactly as the Mac app writes it | Ported | `AppleJson`: sorted keys, `" : "`, empty arrays `[\n\n]`, no escaped slashes. SRMDailyRefresh round-trips byte-identical; the other samples only gain the current `secureAccountName` default (as the Mac app's save does); no value lost or changed in any of the 9 jobspaces in MxAppsIOS |
| Plain `.json` jobspaces open | Ported | Saved back as `.jobspace` (Save As) |
| Step links (`link`: address, text, RTF tip, title) | Ported | Kept losslessly; tip shown as plain text |
| Keys never in the file | Ported | |

## Window and editing

| Feature | State | Notes |
| --- | --- | --- |
| Multi-document, New / Open / Open Recent / Save / Save As / Close / Revert, unsaved-changes prompts | Ported | Tabs instead of windows; Duplicate / Rename → Save As |
| New from Sample… (gallery with services needed and running state), New Sample ▸ | Ported | Samples shipped in the bundle; new ids per document |
| Palette: kinds by layer, drag onto canvas, double-click to add | Ported | |
| Palette: Layer 3 Services with state, double-click / endpoint menu, About… | Ported | |
| Palette element edits (title, description, Reset) | Ported | Kept in the app's preferences |
| Palette / layer / step links with hover text | Partial | Add / Edit / Open / Remove; hover card is a tooltip, hover text edited as plain text |
| Edit Step… | Ported | |
| Canvas: lanes, nodes in lane, 10-pt grid, links down solid / up dashed, labels | Ported | |
| Move, ● handle linking, click / Shift-click / rubber band, link selection, Delete, arrows (Shift = 100), Ctrl+D, Escape | Ported | |
| Zoom Ctrl+= / Ctrl+- / Ctrl+0, Ctrl+wheel (Mac: pinch), 40–200 %, scroll bars | Ported | |
| Node badges: run state, problem, breakpoint tag, link mark, disabled dimmed, current-step glow | Ported | |
| Context menus (node; canvas Run / Step / Stop / Save as PNG / SVG) | Ported | |
| Undo / Redo | Ported | Snapshot-based, typing in one field coalesced |
| Inspector — node (all kind sections, Arguments, Expected Outcome with value checks, Exception Handling, Checks, Last Run) | Ported | |
| Inspector — About This Service, Open Project in … | Ported | VS Code / IntelliJ by URL scheme, CLion from the PATH, Explorer; Xcode N/A |
| Inspector — link label; jobspace (name, description, layers, subscriptions with key status, checks) | Ported | |
| Problems (Ctrl+Shift+K), click to jump | Ported | Tool window instead of a popover; all the README's checks (`engine/Validator`) |
| Quick Help on every control, canvas nodes / handle / breakpoint / lanes, run bar, console, prompts, inspector sections, dialogs; float / dock | Ported | Texts from `JobsQuickHelp.swift` with Windows shortcuts |

## API subscriptions and keys

| Feature | State | Notes |
| --- | --- | --- |
| API Subscriptions (Ctrl+Alt+K): name, provider, base URL, plan, rate limit, docs link, auth styles, key name, headers, notes, source project + IDE | Ported | |
| + New Subscription / Local Python Service preset, − with confirmation | Ported | |
| Secure account picker (accounts service, fallback to stored keys), status, Open in MxAcctMGR, New in MxAcctMGR… | Ported | `Secrets.SECURE_ACCOUNTS`; key presence checked without decrypting |
| Linking subscriptions to accounts by name | Ported | On open and in the dialog |
| Provider guides, Get a Key…, ?, “expects … Use It” | Ported | `?` opens Help ▸ Adding API Keys at that provider |
| Test Key | Ported | Implemented, not exercised against the internet in tests |
| Help ▸ Adding API Keys | Ported | |
| Move Keys to Secure Accounts (old Keychain items) | N/A | No old keys on Windows |

## Run and debug

| Feature | State | Notes |
| --- | --- | --- |
| Run / Continue / Pause / Step / Stop, L1 / L2 / L3 menus saved in the jobspace | Ported | Continue is F8 |
| Breakpoints (toggle Ctrl+\\, on/off Ctrl+Y, read mid-run), options (stop on failure, step delay, Clear Run Results) | Ported | |
| Order (dependencies, calls down, agents after their calls), states, skipping, blocked downstream | Ported | Checked headlessly on all five samples in Mock mode |
| Exception handling: retry with (exponential) backoff, skip, fallback, ask (Retry / Skip / Abort), abort, notify | Ported | |
| User Approval, Execution Request (never sends), Outcome Report | Ported | Banner above the canvas (always, rather than inside the console) |
| Mock Layer 3 (required fields, mapped fields, values passing the checks) | Ported | |
| Live calls with `{{name}}`, `{{today}}`, `{{yesterday}}`, `{{in:…}}`, `{{json:…}}`, value checks, provider notices, masked key | Ported | Not run against the internet in tests |
| Decision following decisionContext | Ported | |
| Python Script step (stdin JSON, env keys, timeout, consent once per run, Run Script) | Ported | Windows interpreter lookup; no sandbox |
| Run console: log with level filter, click to select, step output JSON, Clear | Ported | |
| Test Call | Ported | Not invoked in tests |

## Export, services and links

| Feature | State | Notes |
| --- | --- | --- |
| Save as PNG (2×) | Ported | |
| Save as draw.io SVG (`*.drawio.svg` with draw.io XML) | Ported | Checked: valid SVG, swimlanes + nodes + connectors |
| Window ▸ Layer 3 Services (core ServicesView) with Add Step, New Jobspace | Ported | Add Step bar above the core view (it has no hook of its own) |
| Window ▸ Open MxDataHUB / Open MxAcctMGR | Ported | Falls back to the GitHub page |
| `mxjobsmgr://sample/<id>?portfolio=&benchmark=` and `mxjobswms://` (launch args and hand-over) | Ported | Portfolio / benchmark filled into name, description, notes, approval and arguments |
| Help ▸ Register mxjobsmgr:// Links | Ported | Windows-only addition (Info.plist on the Mac) |
| MxJobsMGR on GitHub | Ported | |
