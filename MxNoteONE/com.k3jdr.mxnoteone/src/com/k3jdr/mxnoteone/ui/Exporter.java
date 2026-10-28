package com.k3jdr.mxnoteone.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.printing.PrintDialog;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.ui.ErrorDialog;
import com.k3jdr.mxnoteone.model.PageContent;
import com.k3jdr.mxnoteone.model.PageElement;
import com.k3jdr.mxnoteone.model.SwiftJson;
import com.k3jdr.mxnoteone.model.TableData;
import com.k3jdr.mxnoteone.store.Disk;
import com.k3jdr.mxnoteone.store.NotebookStore;
import com.k3jdr.mxnoteone.store.Refs.PageRef;
import com.k3jdr.mxnoteone.table.TableValues;
import com.k3jdr.mxnoteone.text.CharStyle;
import com.k3jdr.mxnoteone.text.Paragraphs;
import com.k3jdr.mxnoteone.text.RText;
import com.k3jdr.mxnoteone.text.RichCodec;

/**
 * Export Page as PDF, Markdown or HTML, and Print (PageExporter.swift). PDF and printing draw the page as it shows
 * on screen (through Windows' "Microsoft Print to PDF" printer for PDF); Markdown and HTML write its text, tables
 * and pictures (Markdown with the pictures and attachments in a folder beside it, HTML with pictures inside).
 */
final class Exporter {
    private Exporter() {}

    // MARK: Markdown and HTML

    static void markdown(Shell shell, PageRef ref, PageContent page) {
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setText("Export Page as Markdown");
        fd.setOverwrite(true);
        fd.setFilterExtensions(new String[] { "*.md" });
        fd.setFileName(safeName(page.title) + ".md");
        String target = fd.open();
        if (target == null) return;
        Path file = Path.of(target);
        Path filesDir = file.resolveSibling(stripExt(file.getFileName().toString()) + " files");
        NotebookStore store = NotebookStore.get();
        Disk.submit(() -> {
            try {
                StringBuilder md = new StringBuilder();
                md.append("# ").append(page.title.isBlank() ? "Untitled Page" : page.title).append("\n\n");
                for (PageElement e : ordered(page)) {
                    switch (e.kind) {
                        case PageElement.TEXT -> md.append(markdownText(RichCodec.decode(e.rtfd))).append("\n\n");
                        case PageElement.TABLE -> md.append(markdownTable(e.table)).append("\n\n");
                        case PageElement.IMAGE, PageElement.FILE, PageElement.AUDIO -> {
                            byte[] data = store.assetData(e.asset, ref);
                            if (data == null) break;
                            Files.createDirectories(filesDir);
                            String name = e.fileName != null ? e.fileName : e.asset;
                            Files.write(filesDir.resolve(name), data);
                            String link = (filesDir.getFileName() + "/" + name).replace(" ", "%20");
                            md.append(PageElement.IMAGE.equals(e.kind) ? "![" + name + "](" + link + ")" : "[" + name + "](" + link + ")").append("\n\n");
                        }
                        default -> md.append("*[").append(e.kindTitle()).append("]*\n\n");
                    }
                }
                Files.writeString(file, md.toString(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                shell.getDisplay().asyncExec(() -> ErrorDialog.show(shell, "Couldn't export the page.", ex));
            }
        });
    }

    static void html(Shell shell, PageRef ref, PageContent page) {
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setText("Export Page as HTML");
        fd.setOverwrite(true);
        fd.setFilterExtensions(new String[] { "*.html" });
        fd.setFileName(safeName(page.title) + ".html");
        String target = fd.open();
        if (target == null) return;
        NotebookStore store = NotebookStore.get();
        Disk.submit(() -> {
            try {
                StringBuilder h = new StringBuilder("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>").append(esc(page.title))
                        .append("</title>\n<style>body{font-family:'Helvetica Neue',Arial,sans-serif;font-size:14px;max-width:900px;margin:32px auto;background:#")
                        .append(hex(page.color.rgb)).append("}table{border-collapse:collapse;margin:8px 0}td,th{border:1px solid #a3a3a3;padding:4px 6px;vertical-align:top}"
                                + "p{margin:0}.box{margin:0 0 18px}</style></head><body>\n<h1 style=\"font-weight:300\">")
                        .append(esc(page.title.isBlank() ? "Untitled Page" : page.title)).append("</h1>\n");
                for (PageElement e : ordered(page)) {
                    switch (e.kind) {
                        case PageElement.TEXT -> h.append("<div class=\"box\">").append(htmlText(RichCodec.decode(e.rtfd))).append("</div>\n");
                        case PageElement.TABLE -> h.append(htmlTable(e.table)).append('\n');
                        case PageElement.IMAGE -> {
                            byte[] data = store.assetData(e.asset, ref);
                            if (data != null) {
                                h.append("<div class=\"box\"><img style=\"width:").append((int) e.width).append("px\" src=\"data:image/")
                                        .append(ext(e.asset)).append(";base64,").append(SwiftJson.base64(data)).append("\"></div>\n");
                            }
                        }
                        case PageElement.FILE, PageElement.AUDIO -> h.append("<p class=\"box\">\uD83D\uDCCE ").append(esc(e.fileName == null ? "File" : e.fileName)).append("</p>\n");
                        default -> h.append("<p class=\"box\"><i>[").append(esc(e.kindTitle())).append("]</i></p>\n");
                    }
                }
                h.append("</body></html>\n");
                Files.writeString(Path.of(target), h.toString(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                shell.getDisplay().asyncExec(() -> ErrorDialog.show(shell, "Couldn't export the page.", ex));
            }
        });
    }

    /** Containers top to bottom, then left to right. */
    static List<PageElement> ordered(PageContent page) {
        List<PageElement> els = new ArrayList<>(page.elements);
        els.sort(Comparator.comparingDouble((PageElement e) -> e.y).thenComparingDouble(e -> e.x));
        return els;
    }

    static String markdownText(RText rt) {
        StringBuilder out = new StringBuilder();
        String[] paras = rt.text.split("\n", -1);
        int at = 0;
        for (String para : paras) {
            CharStyle first = rt.styleAt(at);
            Paragraphs.Style heading = para.isBlank() ? null : Paragraphs.Style.headingOf(first);
            String prefix = "";
            Paragraphs.Info info = Paragraphs.parse(para);
            int skip = 0;
            if (heading != null) {
                prefix = switch (heading) {
                    case TITLE, HEADING1 -> "# ";
                    case HEADING2 -> "## ";
                    case HEADING3 -> "### ";
                    default -> "#### ";
                };
            } else if (info.isBullet()) {
                prefix = "  ".repeat(info.indent) + "- ";
                skip = info.indent + info.listLength;
            } else if (info.isNumber()) {
                prefix = "  ".repeat(info.indent) + info.list + ". ";
                skip = info.indent + info.listLength;
            }
            StringBuilder line = new StringBuilder(prefix);
            for (RText.Span s : rt.spans) {
                int a = Math.max(s.start(), at + skip), b = Math.min(s.end(), at + para.length());
                if (a >= b) continue;
                String t = rt.text.substring(a, b).replace(RText.LINE_BREAK, ' ').replace("\uFFFC", "");
                if (t.isBlank() || heading != null) {
                    line.append(t);
                    continue;
                }
                CharStyle cs = s.style();
                String lead = t.substring(0, t.length() - t.stripLeading().length()), trail = t.substring(t.stripTrailing().length());
                String core = t.strip();
                if (cs.strike()) core = "~~" + core + "~~";
                if (cs.italic()) core = "*" + core + "*";
                if (cs.bold()) core = "**" + core + "**";
                if (cs.isCode()) core = "`" + core + "`";
                if (cs.link() != null) core = "[" + core + "](" + cs.link() + ")";
                line.append(lead).append(core).append(trail);
            }
            out.append(line.toString().stripTrailing()).append(para.isBlank() ? "\n" : "  \n");
            at += para.length() + 1;
        }
        return out.toString().stripTrailing();
    }

    static String markdownTable(TableData t) {
        if (t == null || t.rowCount() == 0) return "";
        TableValues v = new TableValues(t);
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < t.rowCount(); r++) {
            sb.append('|');
            for (int c = 0; c < t.columnCount(); c++) sb.append(' ').append(v.display(r, c).replace("|", "\\|").replace('\n', ' ').replace(RText.LINE_BREAK, ' ')).append(" |");
            sb.append('\n');
            if (r == 0) {
                sb.append('|');
                for (int c = 0; c < t.columnCount(); c++) sb.append(" --- |");
                sb.append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    static String htmlText(RText rt) {
        StringBuilder out = new StringBuilder();
        String[] paras = rt.text.split("\n", -1);
        int at = 0, i = 0;
        for (String para : paras) {
            var ps = i < rt.paras.size() ? rt.paras.get(i) : null;
            out.append("<p style=\"");
            if (ps != null && ps.alignName() != null) out.append("text-align:").append(ps.alignName().equals("justified") ? "justify" : ps.alignName()).append(';');
            if (ps != null && ps.headIndent() > 0) out.append("padding-left:").append((int) ps.headIndent()).append("px;text-indent:")
                    .append((int) (ps.firstIndent() - ps.headIndent())).append("px;");
            out.append("\">");
            if (para.isEmpty()) out.append("&nbsp;");
            for (RText.Span s : rt.spans) {
                int a = Math.max(s.start(), at), b = Math.min(s.end(), at + para.length());
                if (a >= b) continue;
                CharStyle cs = s.style();
                String t = esc(rt.text.substring(a, b).replace("\uFFFC", "")).replace(String.valueOf(RText.LINE_BREAK), "<br>").replace("\t", "&emsp;");
                StringBuilder st = new StringBuilder("font-family:'").append(cs.font()).append("';font-size:").append(cs.size()).append("px;");
                if (cs.bold()) st.append("font-weight:bold;");
                if (cs.italic()) st.append("font-style:italic;");
                if (cs.underline() || cs.strike()) st.append("text-decoration:").append(cs.underline() ? "underline " : "").append(cs.strike() ? "line-through" : "").append(';');
                if (cs.color() != null) st.append("color:#").append(hex(cs.color())).append(';');
                if (cs.background() != null) st.append("background:#").append(hex(cs.background())).append(';');
                String span = "<span style=\"" + st + "\">" + t + "</span>";
                if (cs.script() != 0) span = (cs.script() > 0 ? "<sup>" : "<sub>") + span + (cs.script() > 0 ? "</sup>" : "</sub>");
                if (cs.link() != null) span = "<a href=\"" + esc(cs.link()) + "\">" + span + "</a>";
                out.append(span);
            }
            out.append("</p>\n");
            at += para.length() + 1;
            i++;
        }
        return out.toString();
    }

    static String htmlTable(TableData t) {
        if (t == null) return "";
        TableValues v = new TableValues(t);
        StringBuilder sb = new StringBuilder("<table>");
        for (int r = 0; r < t.rowCount(); r++) {
            if (t.isRowHidden(r)) continue;
            sb.append("<tr>");
            for (int c = 0; c < t.columnCount(); c++) {
                if (t.isColumnHidden(c) || t.isCovered(r, c)) continue;
                boolean head = t.headerRow && r == 0;
                sb.append(head ? "<th" : "<td");
                int[] span = t.span(r, c);
                if (span != null) sb.append(" rowspan=\"").append(span[2]).append("\" colspan=\"").append(span[3]).append('"');
                double[] shade = t.shade(r, c);
                sb.append(" style=\"width:").append((int) Math.round(t.columnWidths.get(c))).append("px;");
                if (shade != null) sb.append("background:#").append(hex(Gfx.rgb(shade))).append(';');
                String a = t.cellAlign(r, c);
                if (a != null) sb.append("text-align:").append(a.equals("justified") ? "justify" : a).append(';');
                sb.append("\">");
                byte[] rich = t.richData(r, c);
                String shown = v.display(r, c);
                if (rich != null && shown.equals(t.text(r, c))) sb.append(htmlText(RichCodec.decode(rich)));
                else sb.append(esc(shown).replace("\n", "<br>").replace(String.valueOf(RText.LINE_BREAK), "<br>"));
                sb.append(head ? "</th>" : "</td>");
            }
            sb.append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String hex(org.eclipse.swt.graphics.RGB c) {
        return String.format("%02x%02x%02x", c.red, c.green, c.blue);
    }

    private static String ext(String name) {
        String e = name == null || !name.contains(".") ? "png" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return e.equals("jpg") ? "jpeg" : e;
    }

    static String safeName(String title) {
        String t = title == null || title.isBlank() ? "Untitled Page" : title;
        return t.replaceAll("[\\\\/:*?\"<>|]", "-").strip();
    }

    private static String stripExt(String n) {
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // MARK: PDF and printing (the page as it shows)

    /** Export Page as PDF: the page printed to "Microsoft Print to PDF" into the chosen file. */
    static void pdf(Shell shell, PageView page) {
        PrinterData pdf = null;
        for (PrinterData p : Printer.getPrinterList()) if (p.name != null && p.name.toLowerCase(Locale.ROOT).contains("print to pdf")) pdf = p;
        if (pdf == null) {
            ErrorDialog.show(shell, "Can't export a PDF", "Windows' \u201CMicrosoft Print to PDF\u201D printer isn't installed. Turn it on in Windows Features, or use Print\u2026 with another PDF printer.");
            return;
        }
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setText("Export Page as PDF");
        fd.setOverwrite(true);
        fd.setFilterExtensions(new String[] { "*.pdf" });
        fd.setFileName(safeName(page.content() == null ? "" : page.content().title) + ".pdf");
        String target = fd.open();
        if (target == null) return;
        pdf.printToFile = true;
        pdf.fileName = target;
        printPage(shell, page, pdf);
    }

    static void print(Shell shell, PageView page) {
        PrintDialog dialog = new PrintDialog(shell, SWT.NONE);
        dialog.setText("Print Page");
        PrinterData data = dialog.open();
        if (data != null) printPage(shell, page, data);
    }

    /** Draws the page as it shows (without frames and selections) across as many sheets as it needs. */
    static void printPage(Shell shell, PageView page, PrinterData data) {
        Image image = page.capture();
        if (image == null) return;
        Printer printer = new Printer(data);
        try {
            Rectangle area = printer.getClientArea();
            Rectangle trim = printer.computeTrim(0, 0, 0, 0);
            var dpi = printer.getDPI();
            int margin = dpi.x / 2;
            int width = area.width - 2 * margin, height = area.height - 2 * margin;
            Rectangle ib = image.getBounds();
            double scale = Math.min(1.0 * dpi.x / 96, (double) width / ib.width);
            int sliceH = (int) Math.floor(height / scale);
            if (!printer.startJob(page.content() == null ? "MxNoteONE" : page.content().title)) return;
            GC gc = new GC(printer);
            for (int y = 0; y < ib.height; y += sliceH) {
                printer.startPage();
                int h = Math.min(sliceH, ib.height - y);
                gc.drawImage(image, 0, y, ib.width, h, margin + trim.x * 0, margin, (int) (ib.width * scale), (int) (h * scale));
                printer.endPage();
            }
            gc.dispose();
            printer.endJob();
        } catch (RuntimeException ex) {
            ErrorDialog.show(shell, "Couldn't print the page.", ex);
        } finally {
            printer.dispose();
            image.dispose();
        }
    }
}
