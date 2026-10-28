# Developing MxAppsRCP

The MxAppsIOS macOS apps (SwiftUI / AppKit) re-built as Windows desktop apps on the **Eclipse RCP platform (e4)**,
Java 21, built with **Maven Tycho 5**. The macOS sources stay the specification: `D:\MyCodeGIT\MxAppsIOS\<App>\README.md`
says what each app does, and `D:\MyCodeGIT\MxAppsIOS\<App>\<App>\*.swift` how.

## Layout

```
MxAppsRCP/
  pom.xml                         the reactor (Tycho, Windows x86_64 only)
  .mvn/extensions.xml             pomless Tycho: bundles and features need no pom.xml
  releng/com.k3jdr.mx.target/     the target platform: Eclipse 4.41 (2026-09), pgjdbc, Gson, JNA
  releng/tools/smoke-test.ps1     start a built app, take a picture of its window, show its log
  Shared/
    com.k3jdr.mx.core/            shared by every app (see below)
    com.k3jdr.mx.shared.feature/  core + pgjdbc + Gson + secure storage + JFace text + JNA
  <App>/                          MxDataSQL, MxDataHUB, MxFileMGR, MxJobsMGR, MxAcctMGR, MxNoteONE
    pom.xml                       aggregator of the three below
    com.k3jdr.<app>/              the app's plug-in: Application.e4xmi, plugin.xml (the product extension), src/
    com.k3jdr.<app>.feature/      the app's feature
    com.k3jdr.<app>.product/      <app>.product and pom.xml: the Windows launcher <App>.exe and the zip
```

Each app is an e4 application (`org.eclipse.e4.ui.workbench.swt.E4Application`): `Application.e4xmi` holds the window
and its main part; `LifeCycle` keeps one running copy (a second launch — an `mx…://` link, a document — is handed to
the running one) and restores the window's bounds; `MainPart` builds the window's content in SWT/JFace and its menu
bar with `MenuBarBuilder`. The model is not persisted (`persistState=false`); settings live in instance-scope
preferences (`Prefs`) under `%APPDATA%\MxAppsRCP\<App>\workspace`.

## Build

```bat
mvn clean install                         :: everything (first time: also downloads the Eclipse platform)
mvn -o -f MxDataSQL\pom.xml clean verify  :: one app, offline, once Shared and the target are installed
```

The products are in `<App>\com.k3jdr.<app>.product\target\products\`:
`com.k3jdr.<app>.product\win32\win32\x86_64\<App>\<App>.exe`, and `<App>-win32-x86_64-win32.win32.x86_64.zip` to copy
elsewhere. They need a Java 21 runtime on the PATH (or `JAVA_HOME`).

Run or debug one app from its folder: `<App>\run.bat [--build] [args…]` starts `<App>.exe`;
`<App>\debug.bat [--build] [--port N] [--suspend] [--osgi-console] [args…]` starts the console launcher
`<App>c.exe` with `-consoleLog` and a JDWP agent on the app's port (MxDataSQL 8001, MxDataHUB 8002, MxFileMGR 8003,
MxJobsMGR 8004, MxAcctMGR 8005, MxNoteONE 8006), appended to the `.ini`'s VM arguments
(`--launcher.appendVmargs`). Both build the app when it has no product yet (offline first; online with the target
and Shared installed when that fails) and use `JAVA_HOME`'s JVM when it is set. The logic is in
`releng\tools\mxapp.cmd`.

Check a build without clicking through it:

```powershell
& releng\tools\smoke-test.ps1 -App MxDataSQL -Seconds 10 -Shot $env:TEMP\mxdatasql.png
```

It stops a running copy of that app, starts the built one, saves a picture of its window, prints new entries of its
log (`%APPDATA%\MxAppsRCP\<App>\workspace\.metadata\.log`) and closes it. The warning *Theme
'org.eclipse.e4.ui.css.theme.e4_default' not found* is expected (the apps use the native look, no CSS theme).

Import into Eclipse IDE for RCP and RAP Developers: *File ▸ Import ▸ Existing Projects into Workspace*, root
`MxAppsRCP`, search nested projects; then open `releng/com.k3jdr.mx.target/com.k3jdr.mx.target.target` and *Set as
Active Target Platform*. Run an app from its `.product` file (*Launch an Eclipse application*).

## The shared core (`com.k3jdr.mx.core`)

| Package | What it gives the apps | macOS counterpart |
|---|---|---|
| `ui.MenuBarBuilder` | The menu bar in code: items with accelerators and enablement, labels that change when the menu opens (Run / Continue, Show / Hide), check and radio items, submenus, menus rebuilt on opening | `CommandMenu`s |
| `ui.EditActions` | Cut / Copy / Paste / Select All that act on the focused text field, else on the app's target | the responder chain |
| `ui.QuickHelp` | The Quick Help panel: `QuickHelp.help(control, title, detail, shortcut)`, `QuickHelp.provider(control, …)`, `setSelection` / `setScreen` (per window with a `Shell` for apps with several windows — the window in front speaks), `createDockedPanel` (one per window), View ▸ Quick Help / Float | `Shared/QuickHelp` |
| `ui.SplitPanes` | Two panes with a divider whose leading width is remembered | `Shared/SplitPanes.swift` |
| `ui.Sidebar` | A sidebar of screens in groups, with Quick Help | the apps' sidebars |
| `ui.TableCopy` | Copy rows as tab-separated columns under the titles; Copy Row menu items | `Shared/Copy/TableCopy.swift` |
| `ui.NavigationHistory` | Back / Forward / History with coalescing, and the Go menu items | `Navigation.swift` |
| `ui.ServicesView` | The Layer 3 services and their samples; `selectedService()` and `onSelect(…)` for tools that act on the selection (MxJobsMGR's Add Step) | `Shared/ServicesView.swift` |
| `ui.ErrorDialog`, `ui.Background`, `ui.Ui`, `ui.Clipboards`, `ui.WindowState` | Selectable error text with Copy; work off the UI thread; layout, fonts, colours; clipboard text; window bounds | |
| `db.Pg`, `db.PgSettings` | PostgreSQL connections (sslmode disable / prefer / require without certificate checks, plus any pgjdbc properties), helpers, saved passwords | `PostgresService.swift` |
| `secure.Secrets` | Eclipse secure storage (Windows DPAPI) in place of the Keychain, shared by all apps: `/com.k3jdr.mx/secure-accounts/<id>` holds the service keys and, unencrypted, the account's name. `has` and `children` answer without decrypting; `getUnencrypted` reads only plain values | `Keychain.swift`, `SharedKeys.swift` |
| `json.AppleJson` | JSON byte for byte as the Mac apps' `JSONEncoder` writes it (pretty, sorted keys, slashes escaped or not), so jobspaces, workspaces and notebooks round-trip between the systems | `JSONEncoder` |
| `links.AppLink`, `AppLinks`, `SingleInstance` | `mxdatahub://`, `mxjobsmgr://`, `mxacctmgr://` links: parse, build, open, register with Windows (HKCU), hand over to the running copy | `Shared/AppLinks.swift`, Info.plist URL types |
| `services.ServiceCatalog` | The Layer 3 services and sample workflows, and whether each runs | `Shared/ServiceCatalog.swift` |
| `prefs.Prefs`, `json.Json`, `http.Http`, `Mx` | Settings, JSON as the Mac apps write it, HTTP for the services, the family's names and folders | `UserDefaults`, `JSONEncoder`, `URLSession` |

The Layer 3 services (`Services/`), the SRM schema and loaders (`MxDataHUB/SRM`) and the MxJobsMGR samples are not
copied: the apps use them from the MxAppsIOS checkout (`Mx.iosRepository()`: `MXAPPSIOS_HOME`, else
`D:\MyCodeGIT\MxAppsIOS`).

## Conventions

* **Same behaviour, Windows manners.** Keep each screen, command, dialog and text of the macOS app; map ⌘ to Ctrl,
  ⌥ to Alt, Finder to Explorer, Trash to the Recycle Bin, the Keychain to secure storage. Menu accelerators: use
  modifiers or function keys only — plain Delete, Space or Return go to the widget's key listener.
* **Files stay compatible.** Workspaces, jobspaces and notebooks are read and written in the macOS apps' formats, so
  the same file opens on either system.
* **Quick Help everywhere.** Every control gets `QuickHelp.help(...)` (or a provider for tables and canvases), with the
  same sentences as the app's `*QuickHelp.swift`.
* **Selecting and copying.** Text is selectable (`Ui.selectable`), tables copy rows (`TableCopy`).
* **Never block the UI thread** on the database, HTTP or the disk: `Background.run(owner, work, done)`.
* **Each app's code stays in its folder.** Shared code goes to `Shared/` only when two apps use it.
* Each app keeps a `PARITY.md`: every feature of the macOS app, and whether it is ported, partly ported, or not yet.
