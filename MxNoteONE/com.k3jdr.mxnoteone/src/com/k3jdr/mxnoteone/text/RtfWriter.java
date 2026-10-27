package com.k3jdr.mxnoteone.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.eclipse.swt.graphics.RGB;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.k3jdr.mxnoteone.model.SwiftJson;

/**
 * Writes {@link RText} as RTF the Mac's text system reads back the same: Cocoa's header, a font table of PostScript
 * names, a colour table, \pard paragraphs with their alignment and indents, runs with their formatting, links as
 * HYPERLINK fields, U+2028 as \line, and the MxNoteONE document comment carrying links' hover texts. The Mac app
 * reads plain RTF where it expects RTFD (NoteRichText.text falls back to RTF), so this is what Windows saves.
 * Attachments inside text aren't written here (see {@link RichCodec}).
 */
public final class RtfWriter {
    private RtfWriter() {}

    private record Run(int font, int halfPoints, boolean bold, boolean italic, boolean underline, boolean strike, int fg, int bg,
            int script, String extras) {}

    public static String write(RText t) {
        Map<String, Integer> fonts = new LinkedHashMap<>();
        List<RGB> colors = new ArrayList<>();
        // The fonts and colours used, in order.
        for (RText.Span s : t.spans) {
            fonts.computeIfAbsent(faceName(s.style()), k -> fonts.size());
            if (s.style().color() != null && !colors.contains(s.style().color())) colors.add(s.style().color());
            if (s.style().background() != null && !colors.contains(s.style().background())) colors.add(s.style().background());
        }
        if (fonts.isEmpty()) fonts.put(Fonts.rtfName(CharStyle.DEFAULT_FONT, false, false), 0);

        StringBuilder sb = new StringBuilder(t.length() * 2 + 400);
        sb.append("{\\rtf1\\ansi\\ansicpg1252\\cocoartf2822\n\\cocoatextscaling0\\cocoaplatform0{\\fonttbl");
        for (Map.Entry<String, Integer> f : fonts.entrySet()) {
            sb.append("\\f").append(f.getValue()).append(Fonts.isMonospaced(f.getKey()) ? "\\fmodern" : "\\fnil").append("\\fcharset0 ");
            escape(sb, f.getKey());
            sb.append(';');
        }
        sb.append("}\n{\\colortbl;");
        for (RGB c : colors) sb.append("\\red").append(c.red).append("\\green").append(c.green).append("\\blue").append(c.blue).append(';');
        sb.append("}\n");
        String comment = comment(t);
        if (comment != null) {
            sb.append("{\\info\n{\\doccomm ");
            escape(sb, comment);
            sb.append("}}");
        }
        sb.append("\\uc0\n");

        Run cur = null;
        String link = null;
        Run beforeLink = null;
        int para = 0;
        ParaStyle lastPara = null;
        String text = t.text;
        boolean paraStart = true;
        List<RText.Span> cover = spansCovering(t);
        int si = 0;
        for (int k = 0; k <= text.length(); k++) {
            if (paraStart) {
                ParaStyle ps = para < t.paras.size() ? t.paras.get(para) : ParaStyle.DEFAULT;
                if (!ps.equals(lastPara)) {
                    writePara(sb, ps);
                    lastPara = ps;
                }
                paraStart = false;
            }
            if (k == text.length()) break;
            while (si < cover.size() - 1 && cover.get(si).end() <= k) si++;
            CharStyle cs = cover.isEmpty() ? CharStyle.DEFAULT : cover.get(si).style();
            char c = text.charAt(k);
            if (c == '\n') {
                if (link != null) {
                    sb.append("}}");
                    cur = beforeLink;
                    link = null;
                }
                if (cur == null) {
                    writeRun(sb, null, run(cs, fonts, colors));
                    cur = run(cs, fonts, colors);
                }
                sb.append("\\\n");
                para++;
                paraStart = true;
                continue;
            }
            if (c == RText.ATTACHMENT) continue;
            if (!Objects.equals(link, cs.link())) {
                if (link != null) {
                    sb.append("}}");
                    cur = beforeLink;
                }
                link = cs.link();
                if (link != null) {
                    beforeLink = cur;
                    sb.append("{\\field{\\*\\fldinst{HYPERLINK \"");
                    escape(sb, link.replace("\"", "%22"));
                    sb.append("\"}}{\\fldrslt ");
                }
            }
            Run run = run(cs, fonts, colors);
            if (!run.equals(cur)) {
                writeRun(sb, cur, run);
                cur = run;
            }
            escapeChar(sb, c);
        }
        if (link != null) sb.append("}}");
        if (cur == null) {
            // An empty text still says its font, so what's typed into it on the Mac takes it.
            writeRun(sb, null, run(t.spans.isEmpty() ? CharStyle.DEFAULT : t.spans.get(0).style(), fonts, colors));
        }
        sb.append('}');
        return sb.toString();
    }

    /** The spans, with any gap (text without a span) given the default style. */
    private static List<RText.Span> spansCovering(RText t) {
        List<RText.Span> out = new ArrayList<>();
        int at = 0;
        for (RText.Span s : t.spans) {
            if (s.start() > at) out.add(new RText.Span(at, s.start(), CharStyle.DEFAULT));
            if (s.end() > s.start()) out.add(s);
            at = Math.max(at, s.end());
        }
        if (at < t.length()) out.add(new RText.Span(at, t.length(), out.isEmpty() ? CharStyle.DEFAULT : out.get(out.size() - 1).style()));
        return out;
    }

    private static String faceName(CharStyle s) {
        return Fonts.hasFaces(s.font()) ? Fonts.rtfName(s.font(), s.bold(), s.italic()) : s.font();
    }

    private static Run run(CharStyle s, Map<String, Integer> fonts, List<RGB> colors) {
        int f = fonts.getOrDefault(faceName(s), 0);
        int fg = s.color() == null ? 0 : colors.indexOf(s.color()) + 1;
        int bg = s.background() == null ? 0 : colors.indexOf(s.background()) + 1;
        return new Run(f, (int) Math.round(s.size() * 2), s.bold(), s.italic(), s.underline(), s.strike(), fg, bg, s.script(),
                s.extras() == null ? "" : s.extras());
    }

    private static void writeRun(StringBuilder sb, Run cur, Run run) {
        int len = sb.length();
        if (cur == null || cur.font != run.font) sb.append("\\f").append(run.font);
        if (cur == null || cur.halfPoints != run.halfPoints) sb.append("\\fs").append(run.halfPoints);
        if (cur == null ? run.bold : cur.bold != run.bold) sb.append(run.bold ? "\\b" : "\\b0");
        if (cur == null ? run.italic : cur.italic != run.italic) sb.append(run.italic ? "\\i" : "\\i0");
        if (cur == null ? run.underline : cur.underline != run.underline) sb.append(run.underline ? "\\ul\\ulc0" : "\\ulnone");
        if (cur == null ? run.strike : cur.strike != run.strike) sb.append(run.strike ? "\\strike\\strikec0" : "\\strike0\\striked0");
        if (cur == null || cur.fg != run.fg) sb.append("\\cf").append(run.fg);
        if (cur == null ? run.bg != 0 : cur.bg != run.bg) sb.append("\\cb").append(run.bg);
        if (cur == null ? run.script != 0 : cur.script != run.script) sb.append(run.script > 0 ? "\\super" : run.script < 0 ? "\\sub" : "\\nosupersub");
        if (!run.extras.isEmpty() && (cur == null || !cur.extras.equals(run.extras))) sb.append(run.extras);
        if (sb.length() > len) sb.append(' ');
    }

    private static void writePara(StringBuilder sb, ParaStyle p) {
        sb.append("\\pard");
        String extras = p.extras() == null ? "" : p.extras();
        sb.append(extras);
        long li = Math.round(p.headIndent() * 20), fi = Math.round((p.firstIndent() - p.headIndent()) * 20);
        if (li != 0) sb.append("\\li").append(li);
        if (fi != 0) sb.append("\\fi").append(fi);
        if (p.spaceBefore() != 0) sb.append("\\sb").append(Math.round(p.spaceBefore() * 20));
        if (p.spaceAfter() != 0) sb.append("\\sa").append(Math.round(p.spaceAfter() * 20));
        switch (p.align()) {
            case ParaStyle.CENTER -> sb.append("\\qc");
            case ParaStyle.RIGHT -> sb.append("\\qr");
            case ParaStyle.JUSTIFIED -> sb.append("\\qj");
            default -> {}
        }
        if (!extras.contains("\\pardirnatural")) sb.append("\\pardirnatural");
        if (!extras.contains("\\partightenfactor")) sb.append("\\partightenfactor0");
        sb.append('\n');
    }

    static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) escapeChar(sb, s.charAt(i));
    }

    private static void escapeChar(StringBuilder sb, char c) {
        switch (c) {
            case '\\' -> sb.append("\\\\");
            case '{' -> sb.append("\\{");
            case '}' -> sb.append("\\}");
            case '\t' -> sb.append("\\tab ");
            case RText.LINE_BREAK -> sb.append("\\line ");
            case '\r' -> sb.append("\\line ");
            case '\n' -> sb.append("\\\n");
            default -> {
                if (c >= 0x20 && c < 0x80) sb.append(c);
                else if (c < 0x20) { /* other control characters aren't text */ }
                else sb.append("\\u").append((int) (short) c).append(' ');
            }
        }
    }

    /** The document comment with the links' hover texts (positions as the Mac counts them: without attachments). */
    private static String comment(RText t) {
        JsonArray tips = new JsonArray();
        String text = t.text;
        int removedBefore = 0;
        int[] shift = new int[text.length() + 1];
        for (int i = 0; i < text.length(); i++) {
            shift[i] = removedBefore;
            if (text.charAt(i) == RText.ATTACHMENT) removedBefore++;
        }
        shift[text.length()] = removedBefore;
        RText.Span open = null;
        int openStart = 0;
        List<RText.Span> spans = new ArrayList<>(t.spans);
        spans.add(new RText.Span(text.length(), text.length(), CharStyle.DEFAULT));
        for (RText.Span s : spans) {
            String tip = s.style().tip();
            if (open != null && !Objects.equals(open.style().tip(), tip)) {
                int at = openStart - shift[openStart], end = s.start() - shift[s.start()];
                JsonObject o = new JsonObject();
                o.addProperty("at", at);
                o.addProperty("length", end - at);
                o.addProperty("rtf", open.style().tip());
                if (end > at) tips.add(o);
                open = null;
            }
            if (tip != null && open == null) {
                open = s;
                openStart = s.start();
            }
        }
        if (tips.isEmpty()) return null;
        return RtfReader.TIPS_MARKER + SwiftJson.write(tips, false, false).replace("\\/", "/");
    }

    /** The hover text RTF for a plain string (the Edit Link dialog's text), base64 as the comment keeps it. */
    public static String tipRtf(String plain) {
        if (plain == null || plain.isBlank()) return null;
        String rtf = write(RText.plain(plain, CharStyle.DEFAULT.withSize(12)));
        return SwiftJson.base64(rtf.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** The plain text of a hover text (its RTF, base64). */
    public static String tipText(String base64Rtf) {
        if (base64Rtf == null) return "";
        try {
            return RichCodec.decode(SwiftJson.unbase64(base64Rtf)).text;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
