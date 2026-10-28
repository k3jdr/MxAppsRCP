# MxJobsMGR for Windows

The Eclipse RCP (e4) port of the macOS app [MxJobsMGR](../../MxAppsIOS/MxJobsMGR/README.md): design the day-to-day
financial market data operation as a **three-layer swim-lane diagram**, saved as JSON **jobspace** files
(`*.jobspace`), and run it — with approvals, breakpoints, exception handling and mock or live Layer 3 calls.

A jobspace saved here is byte-for-byte what the Mac app saves (pretty-printed, sorted keys, `format: "mxjobspace"`,
`formatVersion: 1`), so the same file opens on either system.

## Build and run

```bat
mvn -B -o -q -f MxJobsMGR\pom.xml clean verify
MxJobsMGR\com.k3jdr.mxjobsmgr.product\target\products\com.k3jdr.mxjobsmgr.product\win32\win32\x86_64\MxJobsMGR\MxJobsMGR.exe [file.jobspace | mxjobsmgr://sample/<id>?portfolio=&benchmark=]
```

Java 21 on the PATH (or `JAVA_HOME`). One copy runs at a time: a second launch (a double-clicked jobspace, an
`mxjobsmgr://` link) is handed to the running one. **Help ▸ Register mxjobsmgr:// Links** registers `mxjobsmgr://`
(and the old `mxjobswms://`) for this user, so MxDataHUB's *Workflows* and *Automate in MxJobsMGR…* open their samples
here.

Smoke test: `& releng\tools\smoke-test.ps1 -App MxJobsMGR -Seconds 12 -Shot $env:TEMP\mxjobsmgr.png -Arguments <file>`.
For checks without clicking, set the environment variable `MXJOBSMGR_SELFTEST` before launching: `run-mock` runs the
first opened jobspace in Mock mode, `export:<folder>` writes its PNG, draw.io SVG and jobspace text there, `save`
saves it back to its file, `edit:<file>` tries the editing commands and writes the results, `subscriptions` /
`services` open those windows. Without the variable nothing of this happens.

## The window

| Part | What it does |
| ---- | ------------ |
| **Palette** (left) | Node kinds by layer — drag one onto its lane, or double-click it to add it at the end of the lane; right-click for *Add to Layer N*, *Edit Element…* (also Enter), *Reset*, and *Add / Edit / Open / Remove Link*; a layer header takes a link too. **Layer 3 Services** with their running state: double-click adds a step, right-click picks an endpoint. *Layer 3 Services…* and *New from Sample…* below. Quick Help docks under it. |
| **Tabs** | One tab per open jobspace (`*` while it has unsaved changes); the tab's ✕, File ▸ Close (Ctrl+W) and closing the window ask to save. |
| **Run bar** | ▶ Run (Ctrl+R) / Continue (F8), ⏸ Pause, ↳ Step (F6), ■ Stop (Ctrl+.), the **L1 / L2 / L3** menus (saved in the jobspace), ⏵ Breakpoints (Ctrl+Y), ⚙ Options (stop on failure, step delay, Clear Run Results), the status — and the Mac toolbar's zoom (− 100% +), **Problems** (Ctrl+Shift+K), 🔑 API Subscriptions (Ctrl+Alt+K), ▤ console (Ctrl+Shift+Y) and ◨ inspector (Ctrl+Alt+I). |
| **Canvas** | Three coloured lanes; nodes stay in their lane on a 10-point grid. Drag to move, drag the ● handle onto another node to link (down = call, up = dashed result), click / Shift-click / rubber-band to select, click a link to select it, Delete, arrow keys nudge (Shift = 100), Ctrl+D duplicates, Escape deselects, Ctrl+wheel or Ctrl+= / Ctrl+- / Ctrl+0 zoom (40–200 %). Right-click a node for breakpoint, disable, *Edit Step…*, its link, duplicate, delete; anywhere for Run / Step / Stop and *Save as PNG… / draw.io SVG…*. A linked step shows its link's hover text as a tooltip. |
| **Approval banner** | While a run waits — *Approve / Reject*, *Send / Cancel*, *Retry / Skip / Abort*, *Run Scripts / Skip Scripts / Stop* — with *Details* (what is approved, the script). |
| **Run console** | The log, filtered by level, a click selects the entry's step; the selected step's output as JSON. |
| **Inspector** | Node: title, enabled, breakpoint, notes, its checks, *Last Run*, the kind's settings (schedule; agent / assembly instructions, model, output format, inputs with *Linked ▾*; approver, channel, expiry, comment, summary; target, mode, confirm; recipients; API call with *About This Service*, *Open Project in …*, method, path, query, headers, body, **Test Call**; Python interpreter, subscription, code, **Run Script**; field mappings), **Arguments**, **Expected Outcome** (output name, success status, required fields, value checks), **Exception Handling**. Link: from, to, label. Nothing: name, description, layer counts, subscriptions with key status, checks. |

Menus: **File** (New Ctrl+N, New from Sample… Ctrl+Alt+N, New Sample ▸, Open… Ctrl+O, Open Recent, Close, Save Ctrl+S,
Save As… Ctrl+Shift+S, Revert to Saved, Export Diagram as PNG / draw.io SVG, Exit), **Edit** (Undo Ctrl+Z, Redo
Ctrl+Shift+Z, Cut/Copy/Paste/Select All, Duplicate Nodes Ctrl+D, Delete Selection, Select All Nodes Ctrl+Alt+A),
**View** (Quick Help Ctrl+Alt+/, Float Quick Help), **Run**, **Diagram** (Add Node ▸, zoom, Show Problems, API
Subscriptions…, Inspector), **Window** (Layer 3 Services Ctrl+Alt+L, Open MxDataHUB, Open MxAcctMGR), **Help** (Adding
API Keys, Register mxjobsmgr:// Links, MxJobsMGR on GitHub, About).

## Keys

API keys are never written to a jobspace. A subscription names an **MxAcctMGR secure account**
(`secureAccountID`, `secureAccountName`); its key is that account's service key in Eclipse secure storage (Windows
DPAPI), `/com.k3jdr.mx/secure-accounts/<id>`, key `service key` — read only when a call is sent. API Subscriptions lists
the accounts through the accounts service (`GET 127.0.0.1:8774/secure-accounts`), or, while it isn't running, the
accounts whose service key is in secure storage. *New in MxAcctMGR…* / *Open in MxAcctMGR* hand over to MxAcctMGR
(`mxacctmgr://secure-accounts/…`); *Test Key* calls the provider once.

## Running

The order, states and exception handling are the Mac app's (`engine/RunEngine.java`, on a worker thread): a step waits
for the steps feeding it along its layer or from below, performs its action, then runs the steps it calls down the
layers; Layer 2 agents act after their calls return. Layer 3 **Mock** makes up outputs that pass the checks (no
network); **Live** sends the requests with `{{name}}`, `{{today}}`, `{{yesterday}}`, `{{in:name.path}}` and
`{{json:name.path}}` filled in and checks status, required fields and value checks. Execution Requests never send
anything. A Decision follows the most confident `decisionContext` of its inputs (its Inputs list first). Python Script
steps run `python` (the step's interpreter, else the first `python.exe` on the PATH, else the `py` launcher) with the
request on stdin, after asking once per run.

## Differences from the Mac app

| Mac | Windows |
| --- | ------- |
| One window per document (DocumentGroup) | One window, one tab per jobspace; Duplicate / Rename are Save As |
| ⌘ / ⌥ shortcuts | Ctrl / Alt; Continue (⌃⌘Y) is **F8**; Redo is Ctrl+Shift+Z (Ctrl+Y stays Breakpoints) |
| Toolbar | The zoom, Problems, Subscriptions and Inspector buttons sit at the end of the run bar |
| Problems popover | A small tool window under the Problems button |
| Keychain, sandbox | Eclipse secure storage; scripts run with the user's rights (the consent prompt says so) |
| Python from Homebrew / python.org / Xcode | `python.exe` on the PATH, `%LOCALAPPDATA%\Programs\Python`, or `py.exe` |
| Finder, Xcode, security-scoped bookmarks | Explorer; Xcode is macOS-only; no bookmarks needed. Mac project paths into `MxAppsIOS/` map to this PC's checkout |
| Rich hover cards for links | The link's words, title, hover text (plain) and address as a tooltip; the Link dialog edits hover text as plain text (Mac formatting is kept until changed) |
| Move Keys to Secure Accounts | Not needed: MxJobsMGR for Windows never kept keys of its own |
| SF Symbols | Unicode glyphs |

The five samples are in the bundle (`samples/`, copies of `MxAppsIOS/MxJobsMGR/Samples`). See [PARITY.md](PARITY.md).
