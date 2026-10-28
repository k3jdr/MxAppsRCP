# MxFileMGR for Windows

A dual-pane file manager in the spirit of **Total Commander** and **ForkLift**: the Windows port of the macOS app
`D:\MyCodeGIT\MxAppsIOS\MxFileMGR` (which stays the specification), built on the Eclipse RCP (e4) platform with SWT,
Java 21 and JNA for the Windows shell APIs.

## Build and run

```bat
mvn -B -o -q -f MxFileMGR\pom.xml clean verify
MxFileMGR\com.k3jdr.mxfilemgr.product\target\products\com.k3jdr.mxfilemgr.product\win32\win32\x86_64\MxFileMGR\MxFileMGR.exe
```

Launch arguments: `-left <folder>` and `-right <folder>` open those folders instead of the saved tabs (the smoke test
uses them); a folder given to a second launch opens in a new tab of the running window.

```powershell
& releng\tools\smoke-test.ps1 -App MxFileMGR -Seconds 12 -Shot .smoke\mxfilemgr.png -Arguments '-left','D:\some\folder','-right','D:\other'
```

Settings, open tabs, column layouts, favorites, SFTP accounts and window sizes are kept in instance preferences under
`%APPDATA%\MxAppsRCP\MxFileMGR\workspace`.

## Code

| Package | What's in it | macOS counterpart |
|---|---|---|
| `com.k3jdr.mxfilemgr` | `MainPart` (window, menus, toolbar, keys), `Settings`, `FileHelp` (Quick Help texts) | `MainView`, `MainCommands`, `AppSettings`, `FileQuickHelp` |
| `model` | `FileItem`, `SortOrder` (natural sort), `NameMatcher` (wildcards/regex), `DirectoryLoader` (one FindFirstFileEx pass), `Places` (known folders, drives, cloud folders), `FileOps`, `Fmt` | `FileModels`, `AppSettings` cloud locations, `VolumeMonitor` |
| `workspace` | `Workspace` (both panes and every command), `Pane`, `BrowserTab` (folder, history, sort, filter, selection), `FolderWatcher` (WatchService) | `Workspace`, `WorkspaceActions`, `Pane`, `DirectoryWatcher` |
| `ops` | `TransferManager` jobs, `CopyEngine` (CopyFileEx with progress), `ConflictPrompt`, `Archives` (zip / tar.exe), `CLITool` | `FileOperations`, `CLITool` |
| `ui` | `PaneView` (virtual file table, tabs, path bar, filter, status), `SidebarView`, `TransferPanel`, `FunctionKeyBar`, `PathBar`, `Icons` | `PaneView`, `FileListView`, `SidebarView` |
| `dialogs` | Copy/Move, name prompts, Select Group, Properties, Settings, Connect to Server | `Sheets`, `SettingsView` |
| `tools` | Find Files, Multi-Rename, Synchronize, Compare Files, Viewer / Quick View, Clipboard, ffmpeg Meta-data | `SearchView`, `MultiRenameView`, `SyncView`, `DiffView`, `ViewerView`, `ClipboardView`, `MediaMetadata` |
| `cloud` | One remote browser window with back ends for Amazon S3 (`aws`), Azure Blob (`az`), Google Cloud Storage (`gcloud`) and SFTP (`sftp.exe`) | `S3View`, `AzureView`, `GCSView`, `SFTP` |
| `win` | JNA: `Win32` (FindFirstFileEx, CopyFileEx, SHGetFileInfo, clipboard), `ShellInfo` (type names, icons, open, Explorer, Recycle Bin), `ClipboardFiles` (CF_HDROP + Preferred DropEffect) | NSWorkspace, NSPasteboard, copyfile(3) |

## Keyboard

In the file list: **Enter** opens (enters a folder, opens a file), **Backspace** or **Alt+Up** / **Ctrl+Up** goes up,
**Tab** switches panes, **Space** shows Quick View (Space again or Esc closes it), **Esc** clears the filter, typing a
name jumps to it, **Insert** marks the item and moves down (Total Commander), **Num +** / **Num −** / **Num \***
select, deselect or invert by pattern. Select with click, Shift-click, Ctrl-click or Shift+arrows.

| Command | Keys |
|---|---|
| New Tab / Close Tab | Ctrl+T / Ctrl+W |
| Next / Previous Tab | Ctrl+Tab / Ctrl+Shift+Tab (also Ctrl+Shift+] / Ctrl+Shift+[) |
| Rename (several items: Multi-Rename) | F2 |
| View / Edit | F3 / F4 |
| Copy / Move to other pane | F5 / F6 |
| New Folder / New File | F7 / Shift+F4 |
| Move to Recycle Bin / Delete Immediately | F8 or Delete / Shift+Delete |
| Quick View | Space or Ctrl+Y |
| Duplicate | Ctrl+D |
| Properties / Windows Properties | Ctrl+I / Alt+Enter |
| Cut / Copy / Paste files (Explorer compatible) / Move Item Here | Ctrl+X / Ctrl+C / Ctrl+V / Ctrl+Alt+V |
| Copy Path | Ctrl+Alt+C |
| Show Clipboard | Ctrl+Shift+V |
| Select Group / Deselect Group / Invert Selection | Ctrl+= / Ctrl+- / Ctrl+Shift+I |
| Show Hidden Files | Ctrl+Shift+. |
| Sort by Name, Ext, Size, Date, Kind | Ctrl+Alt+1 – Ctrl+Alt+5 (again to reverse) |
| Calculate Folder Sizes | Ctrl+Alt+S |
| Refresh | Ctrl+R |
| Back / Forward / Enclosing Folder | Ctrl+[ or Alt+Left / Ctrl+] or Alt+Right / Alt+Up |
| Home, Desktop, Program Files, This PC | Ctrl+Shift+H, Ctrl+Shift+D, Ctrl+Shift+A, Ctrl+Shift+C |
| Documents, Downloads | Ctrl+Alt+O, Ctrl+Alt+L |
| Go to Folder… | Ctrl+Shift+G or Ctrl+L |
| Swap Panes | Ctrl+U |
| Same Folder in Other Pane | Ctrl+Alt+= |
| Open Selected Folder in Other Pane | Ctrl+Alt+Right |
| Add to Favorites | Ctrl+B |
| Find Files… | Ctrl+F or Alt+F7 |
| Multi-Rename… | Ctrl+M |
| Compare Folders (select differences) | Ctrl+Alt+K |
| Synchronize Folders… | Ctrl+Alt+Y |
| Compare Files by Content… | Ctrl+Alt+D |
| Extract to Other Pane | Alt+F9 |
| Open Terminal Here | Ctrl+Alt+T |
| Show in Explorer | Ctrl+Alt+R |
| Connect to Server… | Ctrl+K |
| Settings… | Ctrl+, |
| Quick Help / Float Quick Help | Ctrl+Alt+/ (View menu) |

## How it differs from the Mac app

* **Keys:** ⌘ → Ctrl, ⌥ → Alt. Where the Mac uses both ⌘ and ⌃ for different commands, the ⌃ one moved:
  Add to Favorites is **Ctrl+B** (⌃D; Ctrl+D stays Duplicate), Close Tab **Ctrl+W**, Multi-Rename **Ctrl+M**, sorting
  **Ctrl+Alt+1–5**. Move to Recycle Bin is **Delete / F8** and Delete Immediately **Shift+Delete**, as in Explorer.
  Enclosing Folder is Alt+Up (Explorer), with Ctrl+Up and Backspace in the list.
* **Finder → Explorer, Trash → Recycle Bin.** Items on drives without a Recycle Bin (network shares, USB sticks) are
  deleted for good only after asking.
* **Quick Look → Quick View:** a small viewer window (text, hex, pictures) that follows the selection.
* **"This PC":** going up from a drive root lists the drives (Mac: the `/` root).
* **Hidden items** are those with the Hidden attribute (dot files are ordinary files on Windows).
* **Copying** uses CopyFileEx (attributes, dates and alternate data streams kept, with progress); duplicates are named
  Explorer-style "name - Copy.ext"; new items "New folder" and "New Text Document.txt".
* **Properties** change the Windows attributes (Read-only, Hidden, Archive, System) instead of the Unix permission
  bits; Windows Properties… opens Explorer's own dialog (security, sharing, previous versions).
* **Compress / Extract:** zip with java.util.zip; tar, tgz, tbz, txz, 7z, rar, cpio, xar and iso with Windows' own
  `tar.exe`.
* **Edit (F4)** uses Notepad and **Open Terminal Here** Windows Terminal (else Command Prompt) unless Settings say otherwise.
* **Connect to Server** maps `\\server\share` or a WebDAV address (`https://…`, via the WebClient service) with
  `net use`, optionally on a drive letter; a UNC share Windows can already reach just opens.
* **Cloud folders:** OneDrive personal and work/school, SharePoint libraries and Teams chat files are read from
  OneDrive's registry settings; Google Drive from its virtual drive; Dropbox from its `info.json`; Box and iCloud for
  Windows from their folders in the user profile.
* **Cloud windows** use the same CLIs as on the Mac (`aws`, `az`, `gcloud`; install with winget) and Windows'
  OpenSSH `sftp.exe` (key or agent sign-in; password sign-in isn't offered).
* **Compare Files** has "Open both in Editor" in place of Xcode's FileMerge.
* Not on Windows: iCloud Photos (no Photos library API), Show Package Contents (no packages), WebDAV accounts in the
  sidebar (use Connect to Server).

See `PARITY.md` for the feature-by-feature list.
