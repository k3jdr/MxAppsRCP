# MxAppsRCP

The **MxAppsIOS** macOS apps as **Windows desktop apps** on the **Eclipse RCP platform** (e4, SWT/JFace), Java 21,
built with Maven Tycho. Each app keeps its macOS name, screens, commands, file formats and help texts; the macOS
repository ([k3jdr/MxAppsIOS](https://github.com/k3jdr/MxAppsIOS)) remains the specification and the home of the
Layer 3 services, the security master (SRM) schema and loaders, and the workflow samples the apps share.

| App | What it is | Windows app |
|-----|------------|-------------|
| **MxDataSQL** | Browse and edit PostgreSQL tables in workspaces; Run SQL; filters, paging, CSV, JSON cells; DBML schema diagrams | [MxDataSQL/](MxDataSQL/README.md) |
| **MxDataHUB** | The security reference master: securities, portfolios, benchmarks, data refresh, load history, Layer 3 services | [MxDataHUB/](MxDataHUB/README.md) |
| **MxFileMGR** | A dual-pane file manager in the spirit of Total Commander and ForkLift | [MxFileMGR/](MxFileMGR/README.md) |
| **MxJobsMGR** | Three-layer workflow (jobspace) designer and runner for the market-data operation | [MxJobsMGR/](MxJobsMGR/README.md) |
| **MxAcctMGR** | Personal-accounts IDE that teaches: banking registers, secure accounts, My Portfolio from holdings to attribution | [MxAcctMGR/](MxAcctMGR/README.md) |
| **MxNoteONE** | A digital notebook in the spirit of Microsoft OneNote | [MxNoteONE/](MxNoteONE/README.md) |

Each app folder has a `PARITY.md` listing every feature of the macOS app and how far the Windows app has it.

## Build and run

Requirements: JDK 21 and Maven 3.9 (the first build downloads the Eclipse 4.41 platform).

```bat
mvn clean install
```

Each app's launcher is then in
`<App>\com.k3jdr.<app>.product\target\products\com.k3jdr.<app>.product\win32\win32\x86_64\<App>\<App>.exe`, and a zip
of the whole app beside it (`<App>-win32-x86_64-win32.win32.x86_64.zip`) to unpack anywhere. The apps run on the Java
21 found on the PATH or in `JAVA_HOME`.

Settings and the instance area live in `%APPDATA%\MxAppsRCP\<App>`. Passwords and API keys go to Eclipse secure
storage, encrypted for your Windows account (in place of the macOS Keychain); MxAcctMGR's Secure Accounts and
MxJobsMGR's API subscriptions share it.

## Links between the apps

MxDataHUB, MxJobsMGR and MxAcctMGR hand work to each other with `mxdatahub://`, `mxjobsmgr://` and `mxacctmgr://`
links, as on macOS. **Help ▸ Register … Links** in each of them registers its scheme for your Windows account
(HKEY_CURRENT_USER), so the links open the app from anywhere; a link that arrives while the app runs goes to the
running copy.

## The shared services

The Layer 3 services (Python, Java, C++) and the SRM loaders are used from the MxAppsIOS checkout — set
`MXAPPSIOS_HOME` if it is not `D:\MyCodeGIT\MxAppsIOS`:

```bat
python D:\MyCodeGIT\MxAppsIOS\Services\mxservices.py start
```

See [DEVELOPING.md](DEVELOPING.md) for the project layout, the shared core bundle, conventions, importing into
Eclipse IDE and the smoke test.
