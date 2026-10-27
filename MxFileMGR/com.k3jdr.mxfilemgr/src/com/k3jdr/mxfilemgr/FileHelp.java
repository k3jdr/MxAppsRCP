package com.k3jdr.mxfilemgr;

import java.nio.file.Path;
import java.util.List;

import com.k3jdr.mx.core.ui.QuickHelpItem;
import com.k3jdr.mxfilemgr.model.FileItem;
import com.k3jdr.mxfilemgr.model.Fmt;
import com.k3jdr.mxfilemgr.model.SortKey;
import com.k3jdr.mxfilemgr.model.SortOrder;
import com.k3jdr.mxfilemgr.workspace.BrowserTab;

/**
 * What Quick Help says in MxFileMGR (FileQuickHelp.swift), with Windows keys: the main window and its dialogs,
 * the marked items, the file-list row and column header under the pointer, the sidebar, and the tool windows.
 */
public final class FileHelp {
    private FileHelp() {}

    // MARK: Main window

    public static final QuickHelpItem MAIN = new QuickHelpItem("MxFileMGR",
            "Two panes, each with tabs; the active one has the accent line on top. Tab switches panes, Enter opens, Backspace goes up, "
                    + "Space shows Quick View and typing jumps to a name. Mark items with click, Shift-click or Ctrl-click; F5 copies and F6 moves them "
                    + "to the other pane, F2 renames, F7 makes a folder, F8 (Delete) moves to the Recycle Bin. The sidebar has Favorites, cloud storage, drives and servers.");

    public static QuickHelpItem transfer(boolean move) {
        return new QuickHelpItem(move ? "Move" : "Copy",
                "The destination is the other pane's folder unless you type another (relative paths and ~ work; a single item can get a new name). "
                        + "If an item already exists: Ask shows both and offers Replace, Skip, Keep Both or Stop, with Apply to all. Folders that exist are merged.",
                move ? "F6" : "F5");
    }

    public static QuickHelpItem pattern(boolean select) {
        return new QuickHelpItem(select ? "Select Group" : "Deselect Group",
                "Marks (or unmarks) the items whose names match: * matches anything and ? one character; separate several patterns with ;, e.g. *.jpg;*.png.",
                select ? "Ctrl+=" : "Ctrl+-");
    }

    public static final QuickHelpItem PROPERTIES = new QuickHelpItem("Properties",
            "Kind, total size (counted in the background), dates, attributes and owner. Change the attributes — for everything inside a folder too — or the modification date, then Apply.",
            "Ctrl+I");

    public static final QuickHelpItem CONNECT = new QuickHelpItem("Connect to Server",
            "Connects a Windows share (\\\\server\\share) or a WebDAV folder (https://…) with net use — optionally as a drive letter — and opens it in the active pane. "
                    + "The address is kept under Servers.",
            "Ctrl+K");

    /** The marked items in the active pane, or its folder. */
    public static QuickHelpItem selection(BrowserTab tab) {
        if (tab == null) return null;
        List<FileItem> marked = tab.selectedItems();
        if (marked.isEmpty()) {
            return new QuickHelpItem(tab.title(), (tab.url() == null ? "The drives of this PC" : tab.url().toString()) + " — "
                    + Fmt.count(tab.items().size(), "item") + ". Nothing is marked: click an item, or Ctrl+= to select by pattern.");
        }
        return markedItems(marked, tab);
    }

    public static QuickHelpItem markedItems(List<FileItem> marked, BrowserTab tab) {
        if (marked.size() == 1) return describe(marked.get(0), tab);
        long bytes = 0;
        int folders = 0;
        for (FileItem i : marked) {
            if (i.isDirectory()) {
                folders++;
                Long s = tab == null ? null : tab.folderSize(i.path());
                if (s != null) bytes += s;
            } else {
                bytes += i.size();
            }
        }
        return new QuickHelpItem(marked.size() + " Items Marked", Fmt.size(bytes)
                + (folders > 0 ? " (" + Fmt.count(folders, "folder") + " not counted until Calculate Folder Sizes, Ctrl+Alt+S)" : "") + ". "
                + "F5 copies or F6 moves them to the other pane; F2 renames them together with Multi-Rename; F8 moves them to the Recycle Bin.");
    }

    public static QuickHelpItem describe(FileItem item, BrowserTab tab) {
        StringBuilder facts = new StringBuilder();
        if (item.isDrive()) {
            return new QuickHelpItem(item.name(), item.kind() + ", " + Fmt.size(item.free()) + " free of " + Fmt.size(item.size())
                    + ". Enter opens it; right-click for more.");
        }
        if (item.kind() != null && !item.kind().isEmpty()) facts.append(item.kind());
        if (item.isDirectory()) {
            Long s = tab == null ? null : tab.folderSize(item.path());
            if (s != null) facts.append(", ").append(Fmt.size(s));
        } else {
            facts.append(", ").append(Fmt.size(item.size()));
        }
        if (item.modified() > 0) facts.append(", modified ").append(Fmt.date(item.modified()));
        if (item.isLink()) facts.append(item.isDirectory() ? ", a junction or symbolic link" : ", a symbolic link");
        if (item.isHidden()) facts.append(", hidden");
        if (item.isOnlineOnly()) facts.append(", stored online (downloads when opened)");
        String action = item.isDirectory() ? "Enter opens it" : "Enter opens it, Space shows Quick View, F3 views it, F4 edits it";
        return new QuickHelpItem(item.name(), facts + ". " + action + "; right-click for more.");
    }

    public static QuickHelpItem parentRow(Path parent) {
        return new QuickHelpItem("..", "The enclosing folder, " + (parent == null ? "This PC" : parent.toString())
                + ". Enter or double-click goes up; so does Backspace or Alt+Up anywhere in the list.", "Backspace");
    }

    public static QuickHelpItem column(SortKey key, SortOrder sorted) {
        String what = switch (key) {
            case NAME -> "The item's name; hidden items are dimmed and symbolic links and junctions are in italics.";
            case EXT -> "The file extension.";
            case SIZE -> "File sizes. Folders show <DIR> until you use Calculate Folder Sizes (Ctrl+Alt+S).";
            case MODIFIED -> "When the item's contents last changed.";
            case KIND -> "The kind of file, as Explorer names it.";
        };
        String order = sorted.key() == key ? " Sorted by this column, " + (sorted.ascending() ? "ascending" : "descending") + "; click again to reverse."
                : " Click to sort by it.";
        int n = key.ordinal() + 1;
        return new QuickHelpItem(key.title + " Column", what + order + " Drag to resize or reorder; both are remembered.", "Ctrl+Alt+" + n);
    }

    // MARK: Sidebar and panes

    public static final QuickHelpItem FAVORITES = new QuickHelpItem("Favorites",
            "Cloud storage on this PC (OneDrive, SharePoint, Teams, Google Drive, Dropbox, Box, iCloud Drive) and the cloud object stores, then your folders. "
                    + "Click one to open it in the active pane. Drop a folder here to add it; drag to reorder; right-click to remove. Ctrl+B adds the current folder.");

    public static String cloudFolder(String name, Path p) {
        if (name.startsWith("Teams Chat Files")) {
            return name + ": files shared in Microsoft Teams chats, kept by Teams in your OneDrive at " + p + ". "
                    + "Channel files are in the team's SharePoint site and show under SharePoint once synced.";
        }
        if (name.startsWith("SharePoint")) {
            return name + ": the SharePoint document libraries the OneDrive app syncs for this organisation, one “Site - Library” folder each, at " + p + ". "
                    + "Files stored only online download when opened; changes sync back to SharePoint.";
        }
        return name + ", kept on this PC by its app at " + p + ". Opens in the active pane like any folder; files stored only online download when opened.";
    }

    public static final QuickHelpItem PATH_BAR = new QuickHelpItem("Path",
            "The folder's path: click any part to go there. Right-click to copy the path or type one with Go to Folder (Ctrl+Shift+G).");

    public static final QuickHelpItem FILTER = new QuickHelpItem("Filter",
            "Shows only the items whose name contains this text, or matches wildcards such as *.txt. Escape clears it.", "Esc");

    public static final QuickHelpItem TABS = new QuickHelpItem("Tabs",
            "Each tab keeps its own folder, history, sort and filter. Click to switch; × closes (Ctrl+W); + opens a new one (Ctrl+T). Ctrl+Tab and Ctrl+Shift+Tab move between them.");

    public static final QuickHelpItem STATUS = new QuickHelpItem("Status",
            "How many items the pane lists and their size, or what's marked, and the space available on the drive.");

    public static final QuickHelpItem TRANSFERS = new QuickHelpItem("Transfers",
            "Copies, moves, deletes, archives and cloud transfers running in the background, with their progress. Stop ends one; jobs with problems stay listed until dismissed.");

    public static final QuickHelpItem FUNCTION_KEYS = new QuickHelpItem("Function Keys",
            "The Total Commander keys, for the marked items: F2 Rename, F3 View, F4 Edit, F5 Copy, F6 Move, F7 New Folder, F8 Recycle Bin, Alt+F7 Find. "
                    + "View ▸ Function Key Bar hides the row.");

    public static final QuickHelpItem LOCATIONS = new QuickHelpItem("Locations",
            "The drives of this PC with their free space; network drives and removable ones can be disconnected or ejected (⏏). Click one to open it in the active pane.");

    public static final QuickHelpItem SERVERS = new QuickHelpItem("Servers",
            "Connect to Server… and the addresses used recently. Click one to connect again; right-click to remove it.");

    public static final QuickHelpItem SHAREPOINT_SETUP = new QuickHelpItem("SharePoint",
            "SharePoint document libraries appear here once the OneDrive app syncs them (work or school account; in SharePoint, a library's Sync or Add shortcut to My files). "
                    + "None are synced yet: click for how, and to open OneDrive.");

    public static final QuickHelpItem TEAMS_SETUP = new QuickHelpItem("Microsoft Teams",
            "Teams files live in OneDrive (chat files, in “Microsoft Teams Chat Files”) and in the team's SharePoint site (channel files, which show under SharePoint once synced). "
                    + "Neither is on this PC yet: click for how, and to open Teams.");

    public static final QuickHelpItem S3 = new QuickHelpItem("Amazon S3",
            "Opens a window on your S3 buckets through the AWS CLI and your .aws profiles (SSO sign-in included): browse, download to and upload from the active pane, rename, delete, copy URIs and presigned links.");

    public static final QuickHelpItem AZURE = new QuickHelpItem("Azure Blob Storage",
            "Opens a window on your storage accounts through the Azure CLI after az login: browse containers, download to and upload from the active pane, rename files, delete, copy URLs and SAS links.");

    public static final QuickHelpItem GCS = new QuickHelpItem("Google Cloud Storage",
            "Opens a window on your Cloud Storage buckets through the Google Cloud CLI after gcloud auth login: pick a project and bucket, browse, download to and upload from the active pane, rename, delete, copy gs:// URIs and browser links.");

    public static final QuickHelpItem ADD_SFTP = new QuickHelpItem("Add SFTP Account",
            "Saves an SFTP server in Favorites — from your .ssh\\config hosts or typed in — signing in with your SSH key or agent, as ssh does.");

    public static final QuickHelpItem SFTP_SHEET = new QuickHelpItem("SFTP Account",
            "A name for the sidebar and the server: a host name or an alias from .ssh\\config, with an optional user, port and starting folder. "
                    + "Sign in with your SSH key or agent, as ssh in a terminal does. Test Connection tries it.");

    public static final QuickHelpItem SFTP_SCREEN = new QuickHelpItem("SFTP",
            "The server's folder, through Windows' OpenSSH sftp. Double-click a folder to open it or a file to download it to the active pane; "
                    + "Upload sends what's marked there, or drop files on the list. Errors from ssh, such as a refused key, show in red.");

    public static final QuickHelpItem CLIPBOARD = new QuickHelpItem("Clipboard",
            "What's on the clipboard: files (a preview of the selected one, Paste into the active pane, Move Here, Show in Explorer; double-click opens the Viewer), "
                    + "a picture, styled or plain text, a link, and the data formats underneath. It updates while the window is open; Clear Clipboard empties it.",
            "Ctrl+Shift+V");

    public static final String MEDIA_METADATA_DETAIL = "Edits the Title and Description tags of this MP4, M4V, MOV, WebM, MKV, AVI, MP3 or M4A file (AVI: Description goes in the comment tag; MP3: in a TXXX frame) with ffmpeg, copying every stream without re-encoding "
            + "(-map 0 -c copy); the other tags are kept and an empty field removes its tag. Apply replaces the original once the new copy is written, "
            + "or saves a new file. Progress shows in the transfer panel; Command shows the equivalent ffmpeg command.";

    // MARK: Cloud storage windows

    public static final String UPLOAD_DETAIL = "Uploads the items marked in the active pane into this folder (folders with everything in them), after asking before replacing anything. You can also drop files on the list.";
    public static final String DOWNLOAD_DETAIL = "Downloads the selected items into the active pane's folder; folders come with everything in them. Double-clicking a file does the same.";

    public static QuickHelpItem remoteList(String service) {
        return new QuickHelpItem(service + " Items",
                "Folders first, then files with their size and date. Double-click a folder to open it or a file to download it; right-click to copy links, rename or delete. Drop files here to upload them.");
    }

    public static final QuickHelpItem S3_SCREEN = new QuickHelpItem("Amazon S3",
            "Choose a profile from .aws and a bucket, then browse with the path, Up and Filter. Download copies to the active pane's folder; Upload sends what's marked there. "
                    + "When an SSO session expires, Sign In runs aws sso login in your browser.");

    public static final QuickHelpItem AZURE_SCREEN = new QuickHelpItem("Azure Blob Storage",
            "Choose a subscription, storage account and container, then browse with the path, Up and Filter. Download copies to the active pane's folder; Upload sends what's marked there. "
                    + "Access uses your Entra sign-in, falling back to the account key when the account refuses it.");

    public static final QuickHelpItem GCS_SCREEN = new QuickHelpItem("Google Cloud Storage",
            "Choose a project and bucket, then browse with the path, Up and Filter. Download copies to the active pane's folder; Upload sends what's marked there. "
                    + "Sign In runs gcloud auth login in your browser.");

    // MARK: Tool windows

    public static final QuickHelpItem SEARCH = new QuickHelpItem("Find Files",
            "Searches from a folder by name (wildcards such as *.java;*.md, or a regular expression), by text inside files, and by size and age, optionally into subfolders and hidden items. "
                    + "Double-click a result to show it in the active pane.",
            "Ctrl+F, Alt+F7");

    public static final QuickHelpItem MULTI_RENAME = new QuickHelpItem("Multi-Rename",
            "Builds new names from masks: [N] name, [N2-5] characters 2–5, [E] extension, [C] counter, [P] parent folder, [YMD] and [hms] dates. "
                    + "Search and replace (or regex) and case changes; the preview flags duplicates and taken names. Rename applies it; Undo reverts the last batch.",
            "Ctrl+M");

    public static final QuickHelpItem SYNC = new QuickHelpItem("Synchronize Folders",
            "Compare lists what differs between two folder trees, by date and size or by content. Each row gets a direction (→, ← or skip) you can change; Synchronize copies, replacing older files.",
            "Ctrl+Alt+Y");

    public static final QuickHelpItem VIEWER = new QuickHelpItem("Viewer",
            "Preview shows pictures as they are; Text shows the file as text (Ctrl+F finds, Wrap lines folds long ones); Hex shows its bytes.", "F3");

    public static final QuickHelpItem DIFF = new QuickHelpItem("Compare Files",
            "Lines removed from the left file are red, lines added in the right one green. Only changes hides the rest. Binary files are compared byte for byte.",
            "Ctrl+Alt+D");
}
