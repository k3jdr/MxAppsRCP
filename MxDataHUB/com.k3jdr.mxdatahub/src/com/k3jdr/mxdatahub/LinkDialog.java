package com.k3jdr.mxdatahub;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.k3jdr.mx.core.ui.Clipboards;
import com.k3jdr.mx.core.ui.QuickHelp;
import com.k3jdr.mx.core.ui.Ui;
import com.k3jdr.mxdatahub.Rows.SecurityLink;

/**
 * Link / Edit Link on a security's ticker or name (Shared/Links' SecurityLinkSheet): Link on Ticker or Name, Text to
 * display, Address, hover text, pages about the security, Use the Link Copied in MxNoteONE, and Remove Link. The hover
 * text is plain text here (the Mac edits it formatted, with links of its own).
 */
final class LinkDialog extends Dialog {
    private static final int REMOVE = IDialogConstants.CLIENT_ID + 1;
    private final Links.Target target;
    private final SecurityLink tickerLink, nameLink;
    private String field;
    private Button onTicker, onName;
    private Text words, address, hover;
    private Combo pages;
    private Label preview;
    private String title;
    private SecurityLink result;

    LinkDialog(Shell parent, Links.Target target, String field, SecurityLink tickerLink, SecurityLink nameLink) {
        super(parent);
        this.target = target;
        this.field = field == null ? "ticker" : field;
        this.tickerLink = tickerLink;
        this.nameLink = nameLink;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    String field() { return field; }

    /** The link to save, or null to remove it. */
    SecurityLink result() { return result; }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText((existing() == null ? "Link — " : "Edit Link — ") + (target.ticker().isEmpty() ? target.name() : target.ticker()));
    }

    private SecurityLink existing() { return field.equals("name") ? nameLink : tickerLink; }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(Ui.grid(2, 14, 8));
        Ui.label(area, "Link on:");
        Composite on = new Composite(area, SWT.NONE);
        on.setLayout(Ui.grid(2, 0, 12));
        onTicker = new Button(on, SWT.RADIO);
        onTicker.setText(Ui.escape("Ticker (" + (target.ticker().isEmpty() ? "—" : target.ticker()) + ")"));
        onName = new Button(on, SWT.RADIO);
        onName.setText(Ui.escape("Name (" + abbreviate(target.name(), 40) + ")"));
        onTicker.setSelection(!field.equals("name"));
        onName.setSelection(field.equals("name"));
        onTicker.addListener(SWT.Selection, e -> switchField("ticker"));
        onName.addListener(SWT.Selection, e -> switchField("name"));

        Ui.label(area, "Text to display:");
        words = new Text(area, SWT.BORDER);
        words.setLayoutData(Ui.fillH());
        QuickHelp.help(words, "Text to Display", "The words the cell shows for the link; empty shows the ticker or the name itself.");

        Ui.label(area, "Address:");
        address = new Text(area, SWT.BORDER);
        address.setMessage("https://…, an email address, or mxnoteone://…");
        address.setLayoutData(Ui.fillH());
        QuickHelp.help(address, "Address", "A web address (https://…), an email address, or a link copied in MxNoteONE (Copy Link to Page, Paragraph, Section or Notebook).");

        Ui.label(area, "Pages:");
        pages = new Combo(area, SWT.READ_ONLY);
        pages.setLayoutData(Ui.fillH());
        pages.add("Pages about " + (target.ticker().isEmpty() ? target.name() : target.ticker()) + "…");
        List<String[]> list = pages(target);
        for (String[] p : list) pages.add(p[0]);
        pages.select(0);
        pages.addListener(SWT.Selection, e -> {
            int i = pages.getSelectionIndex();
            if (i > 0) {
                address.setText(list.get(i - 1)[1]);
                title = null;
            }
        });
        QuickHelp.help(pages, "Pages About the Security", "Fills the address with a page about the security: Nasdaq.com, SEC EDGAR, Yahoo Finance, or a web search for its investor relations.");

        new Label(area, SWT.NONE);
        Button noteOne = Ui.button(area, "Use the Link Copied in MxNoteONE", () -> {
            String copied = Clipboards.text(getShell().getDisplay());
            if (copied != null && copied.trim().toLowerCase().startsWith("mxnoteone://")) {
                address.setText(copied.trim());
            } else {
                preview.setText("The clipboard holds no MxNoteONE link: in MxNoteONE, Copy Link to a page, section or paragraph first.");
            }
        });
        QuickHelp.help(noteOne, "Use the Link Copied in MxNoteONE", "Takes the mxnoteone:// link on the clipboard, copied in MxNoteONE with Copy Link to Page, Paragraph, Section or Notebook.");

        Label hl = Ui.label(area, "Hover text:");
        hl.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        hover = new Text(area, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData hg = Ui.fill();
        hg.heightHint = 90;
        hover.setLayoutData(hg);
        QuickHelp.help(hover, "Hover Text", "What resting the pointer on the link shows, above where it goes. Plain text here; a formatted hover text made on the Mac shows as its plain text.");

        new Label(area, SWT.NONE);
        preview = new Label(area, SWT.WRAP);
        preview.setForeground(Ui.SECONDARY);
        GridData pg = new GridData(SWT.FILL, SWT.TOP, true, false);
        pg.widthHint = 380;
        preview.setLayoutData(pg);

        load();
        address.addListener(SWT.Modify, e -> updatePreview());
        hover.addListener(SWT.Modify, e -> updatePreview());
        return area;
    }

    private void switchField(String f) {
        if (f.equals(field)) return;
        field = f;
        load();
        getShell().setText((existing() == null ? "Link — " : "Edit Link — ") + (target.ticker().isEmpty() ? target.name() : target.ticker()));
        Button remove = getButton(REMOVE);
        if (remove != null) remove.setEnabled(existing() != null);
    }

    private void load() {
        SecurityLink l = existing();
        words.setText(l == null || l.text() == null ? "" : l.text());
        address.setText(l == null ? "" : l.address());
        hover.setText(l == null || l.hover() == null ? "" : l.hover());
        title = l == null ? null : l.title();
        updatePreview();
    }

    private void updatePreview() {
        String a = Links.address(address.getText());
        preview.setText(a == null ? "Enter an address, or pick a page about the security." : Ui.escape("Hover card: " + Links.tip(
                new SecurityLink(a, words.getText(), hover.getText(), title))));
        preview.getParent().layout(true);
        Button ok = getButton(IDialogConstants.OK_ID);
        if (ok != null) ok.setEnabled(a != null);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        Button remove = createButton(parent, REMOVE, "Remove Link", false);
        remove.setEnabled(existing() != null);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        createButton(parent, IDialogConstants.OK_ID, IDialogConstants.OK_LABEL, true);
        updatePreview();
    }

    @Override
    protected void buttonPressed(int id) {
        if (id == REMOVE) {
            result = null;
            setReturnCode(OK);
            close();
            return;
        }
        if (id == IDialogConstants.OK_ID) {
            String a = Links.address(address.getText());
            if (a == null) return;
            if (title != null && !a.equals(existing() == null ? null : existing().address()) && !a.startsWith("mxnoteone://")) title = null;
            result = new SecurityLink(a, words.getText().trim(), hover.getText().trim(), title);
        }
        super.buttonPressed(id);
    }

    @Override
    protected Point getInitialSize() {
        Point p = super.getInitialSize();
        return new Point(Math.max(p.x, 560), p.y);
    }

    private static String abbreviate(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Pages about a security to link to (SecurityLinks.swift's SecurityPage.pages). */
    static List<String[]> pages(Links.Target t) {
        List<String[]> out = new ArrayList<>();
        String kind = switch (t.type()) {
            case "ETF", "ETN" -> "etf";
            case "Mutual Fund", "Closed-End Fund" -> "fund";
            case "Collective Investment Trust" -> "other";
            default -> "stock";
        };
        String symbol = t.ticker().toUpperCase();
        String nasdaq = symbol.toLowerCase().replace("-", ".").replace("/", ".");
        String dashed = symbol.replace(".", "-").replace("/", "-");
        if (!symbol.isEmpty()) {
            switch (kind) {
                case "stock" -> {
                    out.add(new String[] { "Nasdaq.com — " + symbol + " quote", "https://www.nasdaq.com/market-activity/stocks/" + nasdaq });
                    out.add(new String[] { "Nasdaq.com — " + symbol + " earnings", "https://www.nasdaq.com/market-activity/stocks/" + nasdaq + "/earnings" });
                    out.add(new String[] { "SEC EDGAR — " + symbol + " filings", "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=" + dashed + "&owner=include&count=40" });
                }
                case "etf" -> out.add(new String[] { "Nasdaq.com — " + symbol + " ETF", "https://www.nasdaq.com/market-activity/etf/" + nasdaq });
                case "fund" -> out.add(new String[] { "Nasdaq.com — " + symbol + " fund", "https://www.nasdaq.com/market-activity/mutual-fund/" + nasdaq });
                default -> { }
            }
            if (!kind.equals("other")) out.add(new String[] { "Yahoo Finance — " + symbol, "https://finance.yahoo.com/quote/" + dashed + "/" });
        }
        String search = URLEncoder.encode(t.name() + (kind.equals("stock") ? " investor relations" : ""), StandardCharsets.UTF_8);
        out.add(new String[] { kind.equals("stock") ? t.name() + " — investor relations (web search)" : t.name() + " (web search)",
                "https://www.google.com/search?q=" + search });
        return out;
    }
}
