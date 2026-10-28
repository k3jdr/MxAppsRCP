# MxFileMGR — parity with the macOS app

Every feature of `D:\MyCodeGIT\MxAppsIOS\MxFileMGR\README.md`, and where the Windows port stands.
**Ported** = works the same way (with Windows keys and names); **Partial** = works, with the noted gaps;
**Not yet** = still to do; **N/A** = no Windows counterpart.

"Tried" notes what was exercised by hand in the built app (in test folders under `.smoke\mxfilemgr-test`).

## Window

| Feature | Status | Notes |
|---|---|---|
| Sidebar: Favorites with cloud storage first | Ported | OneDrive (personal, "OneDrive – Org"), SharePoint libraries ("SharePoint – Org"), Teams Chat Files, Google Drive, Dropbox (personal/team), Box, iCloud Drive for Windows. Read from OneDrive's registry, Google Drive's virtual drive, Dropbox's info.json. Re-scanned every 5 s in the background. Tried (OneDrive shown). |
| SharePoint / Microsoft Teams entries when nothing is synced (explain, open OneDrive / Teams) | Ported | Teams opens with `msteams:` (or teams.microsoft.com). |
| Go ▸ iCloud Drive / OneDrive / SharePoint / Microsoft Teams / Google Drive / Dropbox / Box | Ported | "isn't set up on this PC" messages when missing. |
| WebDAV accounts in the sidebar (mount, edit, disconnect) | Not yet | Connect to Server covers WebDAV addresses with `net use`; no saved WebDAV accounts with passwords. |
| SFTP accounts (add/edit/remove/reorder, Test Connection, own window) | Partial | Windows OpenSSH `sftp.exe` in batch mode, hosts from `.ssh\config`; key/agent sign-in only (no saved password). Browse, download, upload, new folder, rename, delete, copy sftp:// link. Not tried against a server. |
| iCloud Photos window | N/A | No Photos library on Windows. |
| Amazon S3 / Azure Blob / Google Cloud Storage entries | Ported | S3 shown when `aws` and a profile exist; Azure and GCS always (install state). Tried: Azure shows the install state. |
| Your folders: add (drop, Ctrl+B), reorder, remove | Ported | Drop folders on the sidebar, drag to reorder, or right-click Move Up / Move Down / Remove. |
| Locations: drives with free space, eject | Ported | Removable and CD drives eject (Explorer's Eject verb), network drives disconnect (`net use /delete`); ⏏ column and context menu. "This PC" entry. |
| Servers: Connect to Server… and recent servers | Ported | |
| Two panes with tabs (strip with 2+ tabs, Ctrl+T / Ctrl+W, next/previous) | Ported | Tried. |
| Back / Forward / Up | Ported | Up from a drive root goes to "This PC". |
| Clickable breadcrumb; right-click Copy Path / Go to Folder… | Ported | Leading parts give way to "…". Tried. |
| Quick filter (substring or wildcards, Escape clears) | Ported | |
| Status line (items, selection size, free space) | Ported | Tried. |
| Active pane accent line | Ported | Tried. |
| Transfer panel (progress, Stop, problems kept until dismissed) | Ported | Tried (copy, move, compress, extract jobs). |
| Function key bar (F2–F8, Alt+F7), View ▸ Function Key Bar | Ported | |
| Toolbar (New Tab, Swap, Same Folder, Hidden Files, Find, Multi-Rename, Compare, Synchronize, Terminal, Connect) | Ported | |
| Open tabs and settings restored on next launch | Ported | Plus `-left` / `-right` launch arguments. |
| Quick Help (docked at the bottom of the sidebar, float, every control, file rows and headers, marked items, tool windows) | Ported | Tried. |

## File list

| Feature | Status | Notes |
|---|---|---|
| Columns Name, Ext, Size, Date Modified, Kind | Ported | Kind is the shell's type name ("Text Document"); shell icons. |
| Sort by header click and Ctrl+Alt+1–5 (again reverses), sort arrow | Ported | Natural name order. Tried. |
| Column reorder and resize remembered (per pane) | Ported | |
| `<DIR>` until Calculate Folder Sizes (Ctrl+Alt+S) | Ported | |
| `..` row | Ported | |
| Hidden items dimmed, symlinks/junctions in italics | Ported | Hidden = Hidden attribute. Tried. |
| Folders update by themselves | Ported | WatchService, 200 ms coalescing. Tried. |
| Keyboard: Enter, Backspace / Alt+Up, Tab, Space, Escape, type-to-jump, Shift+arrows | Ported | Tried. |
| Insert marks and moves down (Total Commander) | Ported | Extra on Windows. |
| Select Group / Deselect Group / Invert (Ctrl+= / Ctrl+- / Ctrl+Shift+I, Num + − *) | Ported | Tried. |
| Context menu (Open, Open With…, Quick View, View, Edit, Copy/Move, Rename, Duplicate, Compress, ffmpeg, Extract, Cut/Copy, Copy Path, Show in Explorer, Properties, Recycle Bin, New Folder/File, Paste, Terminal, Add to Favorites) | Ported | "Open With" uses Windows' chooser rather than a list of apps. |
| Show Package Contents | N/A | No packages on Windows. |
| Thumbnail / column (Miller) views | Not yet | Not in the Mac app either. |

## File operations

| Feature | Status | Notes |
|---|---|---|
| Copy F5 / Move F6 with target prompt (other pane default, relative, ~, single item under new name) | Ported | Tried. |
| Conflict policy Ask (sizes and dates, Apply to all) / Replace / Replace if older / Skip / Keep both | Ported | Tried (prompt shown). |
| Folder merge | Ported | |
| Copy with progress, metadata kept | Ported | CopyFileEx (attributes, dates, alternate streams); symlinks copied as links when Windows allows. |
| Move within a drive is a rename; across drives copy then remove | Ported | Tried. |
| Copying into its own folder makes a duplicate | Ported | Explorer-style "name - Copy". |
| Drag and drop between panes, with Explorer and other apps | Ported | Same drive moves, else copies; Ctrl copies, Shift moves; drop on a folder row. Not tried by hand. |
| Move to Recycle Bin (F8 / Delete), ask first option | Ported | SHFileOperation with undo. Drives without a Recycle Bin always ask before deleting for good. Tried. |
| Delete Immediately (Shift+Delete, always asks) | Ported | Tried. |
| Compress (name.zip / Archive.zip) | Ported | java.util.zip. Tried. |
| Extract zip, tar, tgz, tbz, txz, 7z, rar, cpio, xar, iso into the other pane | Ported | zip in Java (zip-slip guarded), others with Windows' tar.exe. Tried (zip). |
| New Folder F7 / New File Shift+F4 (opens the editor) / Rename F2 / Duplicate Ctrl+D | Ported | Tried. |
| Clipboard copy/paste of files (Explorer compatible), Move Item Here | Ported | CF_HDROP with Preferred DropEffect; Ctrl+X cut added. Tried (copy). |
| Copy Path | Ported | |
| Properties (kind, size, dates, owner, change modified date, permissions recursively) | Partial | Windows attributes (Read-only, Hidden, Archive, System) instead of Unix permission bits; Windows Properties… for ACLs. Tried. |
| ffmpeg Meta-data… (Title/Description, same command lines, per-container rules, progress, Command) | Ported | ffmpeg/ffprobe from the PATH (`winget install ffmpeg`). |
| Show Clipboard window (files with preview, picture, styled/plain text, link, formats, Clear) | Ported | Polls the clipboard sequence number. Tried. |

## Tools

| Feature | Status | Notes |
|---|---|---|
| Find Files (wildcards/regex, containing text, size/age, subfolders, hidden; show in pane, open, Explorer, copy paths) | Ported | Tried. |
| Multi-Rename ([N] [N2-5] [E] [C] [P] [YMD] [hms], search/replace, case, preview with duplicates, Undo) | Ported | Tried. |
| Compare Folders (select differences) | Ported | |
| Synchronize Folders (date/size or content, per-row direction, Synchronize) | Ported | Tried (compare). |
| Compare Files (line diff, binary byte compare) | Ported | "Open both in Editor" instead of FileMerge. |
| Viewer F3 (preview, text with find and wrap, hex) | Partial | Preview shows pictures; other files fall back to text or hex (no Quick Look plug-ins on Windows). |
| Edit F4 with a configurable editor | Ported | Notepad by default. |
| Open Terminal Here (configurable) | Ported | Windows Terminal, PowerShell, pwsh, Command Prompt or any program. |
| Show in Finder → Show in Explorer | Ported | |
| Connect to Server (smb/afp/nfs/ftp/WebDAV with NetFS) | Partial | `net use` for `\\server\share` (and smb://) and WebDAV http(s); AFP, NFS and FTP aren't offered. Not tried (no mapping during tests). |
| Settings (hidden files, folders first, function key bar, confirm Recycle Bin, editor, terminal) | Ported | |
| Amazon S3 window (profiles incl. SSO, browse, download/upload, new folder, rename, delete, copy URI, presigned link, sign in) | Ported | Through `aws`; not installed here, so only the install state was seen. |
| Azure Blob Storage window (subscription/account/container, SAS link, Entra with key fallback) | Ported | Through `az`; install state tried. |
| Google Cloud Storage window (project/bucket, browser link) | Ported | Through `gcloud`; not installed here. |
| iCloud Photos (albums, duplicates, export, organize) | N/A | No Windows equivalent of the Photos library. |
| Renaming from MxDeskMGR (old preferences) | N/A | Mac-only history. |
