package com.k3jdr.mxnoteone.text;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.graphics.RGB;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads RTF as the Mac's text system writes it (NSAttributedString's RTF, and the TXT.rtf inside RTFD) into
 * {@link RText}: fonts, sizes, bold, italic, underline, strikethrough, colours, background (highlight),
 * superscript / subscript, paragraph alignment and indents, links (HYPERLINK fields), line breaks, attachments
 * (\NeXTGraphic) and the MxNoteONE document comment with links' hover texts and pictures' sizes. Control words it
 * doesn't interpret are kept with the run or paragraph they belong to.
 */
public final class RtfReader {
    /** The document comment the Mac app writes link hover texts and picture sizes into (LinkTips.swift). */
    static final String TIPS_MARKER = "MxNoteONE-link-tips:", RICH_MARKER = "MxNoteONE-rich:";

    private static final Set<String> DOC_WORDS = Set.of("rtf", "ansi", "mac", "pc", "pca", "ansicpg", "cocoartf", "cocoasubrtf",
            "cocoatextscaling", "cocoaplatform", "paperw", "paperh", "margl", "margr", "margt", "margb", "vieww", "viewh", "viewkind",
            "viewscale", "deftab", "deff", "deflang", "deflangfe", "readonlydoc", "fet", "ftnbj", "aenddoc", "cocoascreenfonts",
            "facingp", "margmirror", "landscape", "titlepg", "sectd", "pgwsxn", "pghsxn", "marglsxn", "margrsxn", "adeflang",
            "noxlattoyen", "expshrtn", "noultrlspc", "dntblnsbdb", "nospaceforul", "formshade", "horzdoc", "dgmargin",
            "dghspace", "dgvspace", "dghorigin", "dgvorigin", "dghshow", "dgvshow", "jexpand", "viewbksp", "pgbrdrhead",
            "pgbrdrfoot", "splytwnine", "ftnlytwnine", "htmautsp", "nolnhtadjtbl", "useltbaln", "alntblind", "lytcalctblwd",
            "lyttblrtgr", "lnbrkrule", "nobrkwrptbl", "snaptogridincell", "allowfieldendsel", "wrppunct", "asianbrkrule",
            "rsidroot", "newtblstyruls", "nogrowautofit", "usenormstyforlist", "noindnmbrts", "felnbrelev", "nocxsptable",
            "indrlsweleven", "noafcnsttbl", "afelev", "utinl", "hwelev", "spltpgpar", "notcvasp", "notbrkcnstfrctbl",
            "notvatxbx", "krnprsnet", "cachedcolbal", "nouicompat", "fromtext", "fromhtml", "htmlrtf", "sectdefaultcl",
            "linex", "endnhere", "sftnbj", "headery", "footery", "gutter", "ltrsect", "ltrpar", "ltrch", "rtlch", "loch", "hich",
            "dbch", "lang", "langfe", "langnp", "langfenp", "insrsid", "charrsid", "pararsid", "rsid", "sectrsid", "tblrsid",
            "alang", "cgrid", "noproof", "afs", "af", "uc");

    private static final Set<String> PARA_WORDS = Set.of("tx", "tqr", "tqc", "tqdec", "tb", "ri", "sl", "slmult", "slleading",
            "slmaximum", "slminimum", "pardeftab", "pardirnatural", "partightenfactor", "ls", "ilvl", "itap", "nowidctlpar",
            "widctlpar", "hyphpar", "keep", "keepn", "lin", "rin", "sbauto", "saauto", "qd", "aspalpha", "aspnum", "faauto",
            "adjustright", "intbl", "outlinelevel", "contextualspace", "hyphenationfactor");

    private static final Set<String> SKIP_DESTINATIONS = Set.of("stylesheet", "pict", "listtext", "header", "footer", "headerl",
            "headerr", "footerl", "footerr", "object", "themedata", "colorschememapping", "latentstyles", "datastore",
            "xmlnstbl", "listtable", "listoverridetable", "rsidtbl", "generator", "mmathPr", "pgdsctbl", "filetbl", "revtbl",
            "fldtype", "bkmkstart", "bkmkend", "shppict", "nonshppict", "shp", "footnote", "annotation", "atnid", "atnauthor");

    /** Words naming a colour by its index in the colour table (written anew, so they aren't kept). */
    private static final Set<String> COLOR_INDEX_WORDS = Set.of("ulc", "strikec", "strokec", "shadc", "chcfpat", "chshdng");

    private enum Dest { TEXT, FONTTBL, COLORTBL, SKIP, FLDINST, INFO, DOCCOMM, GRAPHIC }

    private static final class Field {
        final StringBuilder inst = new StringBuilder();
    }

    private static final class State implements Cloneable {
        Dest dest = Dest.TEXT;
        int font = -1;
        int halfPoints = 24;
        boolean bold, italic, underline, strike;
        int fg, bg, script;
        String link;
        Field field;
        Map<String, String> extras = new LinkedHashMap<>();
        int uc = 1;
        // paragraph
        int align;
        double li, fi, sb, sa;
        StringBuilder pextras = new StringBuilder();

        @Override
        public State clone() {
            try {
                State s = (State) super.clone();
                s.extras = new LinkedHashMap<>(extras);
                s.pextras = new StringBuilder(pextras);
                return s;
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final String rtf;
    private final Map<String, byte[]> files;
    private int pos;
    private State st = new State();
    private final Deque<State> stack = new ArrayDeque<>();
    private final Map<Integer, String> fonts = new HashMap<>();
    private final Map<Integer, Integer> fontCharsets = new HashMap<>();
    private final List<RGB> colors = new ArrayList<>();
    private boolean colorHasValue;
    private int red, green, blue;
    private int fontEntry = -1;
    private int fontEntryCharset = 0;
    private final StringBuilder fontName = new StringBuilder();
    private Charset codepage = Charset.forName("windows-1252");
    private final RText.Builder out = new RText.Builder();
    private final List<ParaStyle> paras = new ArrayList<>();
    private final StringBuilder comment = new StringBuilder();
    private final ByteArrayOutputStream pendingBytes = new ByteArrayOutputStream();
    private int skipChars;
    private boolean swallowAttachmentChar;
    private String graphicName = "";
    private double graphicW, graphicH;
    private boolean pendingStar;
    private ParaStyle endPara;

    private RtfReader(String rtf, Map<String, byte[]> files) {
        this.rtf = rtf;
        this.files = files == null ? Map.of() : files;
    }

    /** Reads RTF (as its bytes); the RTFD package's other files give attachments their data. */
    public static RText read(byte[] rtfBytes, Map<String, byte[]> files) {
        return new RtfReader(new String(rtfBytes, StandardCharsets.ISO_8859_1), files).parse();
    }

    public static RText read(String rtf) {
        return new RtfReader(rtf, null).parse();
    }

    private RText parse() {
        int n = rtf.length();
        while (pos < n) {
            char c = rtf.charAt(pos);
            if (c == '{') {
                flushBytes();
                pos++;
                stack.push(st);
                st = st.clone();
                pendingStar = false;
            } else if (c == '}') {
                flushBytes();
                pos++;
                endGroup();
            } else if (c == '\\') {
                controlWord();
            } else if (c == '\r' || c == '\n') {
                pos++;
            } else if (c >= 0x80 && c <= 0xFF) {
                // A byte that should have been escaped: read it in the document's code page.
                pos++;
                if (skipChars > 0) skipChars--;
                else pendingBytes.write(c);
            } else {
                flushBytes();
                pos++;
                plainChar(c);
            }
        }
        flushBytes();
        paras.add(endPara != null ? endPara : paraStyle());
        RText text = out.build(paras.get(paras.size() - 1));
        // The builder adds the last paragraph's style; set them all from what was read.
        text = new RText(text.text, text.spans, paras);
        return applyComment(text);
    }

    private void endGroup() {
        State closing = st;
        // The document's group ends: its last paragraph has the style in effect now.
        if (stack.size() <= 1 && endPara == null) endPara = paraStyle();
        if (stack.isEmpty()) return;
        st = stack.pop();
        if (closing.dest == Dest.FONTTBL && st.dest != Dest.FONTTBL) finishFontEntry();
        if (closing.dest == Dest.GRAPHIC && st.dest != Dest.GRAPHIC) {
            String name = graphicName.trim();
            byte[] data = files.get(name);
            CharStyle.Attachment a = new CharStyle.Attachment(name, data, graphicW / 20.0, graphicH / 20.0);
            if (st.dest == Dest.TEXT) {
                out.append(String.valueOf(RText.ATTACHMENT), charStyle(st).withAttachment(a));
                swallowAttachmentChar = true;
            }
            graphicName = "";
        }
    }

    private void controlWord() {
        int n = rtf.length();
        pos++;
        if (pos >= n) return;
        char c = rtf.charAt(pos);
        if (!Character.isLetter(c)) {
            pos++;
            switch (c) {
                case '\'' -> {
                    if (pos + 2 <= n) {
                        try {
                            int b = Integer.parseInt(rtf.substring(pos, pos + 2), 16);
                            pos += 2;
                            if (skipChars > 0) {
                                skipChars--;
                            } else if (st.dest == Dest.TEXT || st.dest == Dest.FONTTBL || st.dest == Dest.FLDINST
                                    || st.dest == Dest.DOCCOMM || st.dest == Dest.GRAPHIC) {
                                pendingBytes.write(b);
                            }
                        } catch (NumberFormatException e) {
                            pos += 2;
                        }
                    }
                }
                case '\\', '{', '}' -> {
                    flushBytes();
                    plainChar(c);
                }
                case '~' -> {
                    flushBytes();
                    text(' ');
                }
                case '_' -> {
                    flushBytes();
                    text('‑');
                }
                case '-' -> flushBytes();
                case '*' -> pendingStar = true;
                case '\n', '\r' -> {
                    flushBytes();
                    word("par", 0, false, "\\par");
                }
                case '\t' -> {
                    flushBytes();
                    text('\t');
                }
                default -> flushBytes();
            }
            return;
        }
        int start = pos;
        while (pos < n && Character.isLetter(rtf.charAt(pos))) pos++;
        String word = rtf.substring(start, pos);
        boolean hasParam = false;
        int param = 0;
        int paramStart = pos;
        if (pos < n && (rtf.charAt(pos) == '-' || Character.isDigit(rtf.charAt(pos)))) {
            int s = pos;
            pos++;
            while (pos < n && Character.isDigit(rtf.charAt(pos))) pos++;
            try {
                param = Integer.parseInt(rtf.substring(s, pos));
                hasParam = true;
            } catch (NumberFormatException e) {
                param = 0;
            }
        }
        String raw = "\\" + rtf.substring(start, pos);
        if (pos < n && rtf.charAt(pos) == ' ') pos++;
        if (!"u".equals(word) && !word.equals("bin")) flushBytes();
        if (paramStart == pos) hasParam = false;
        if ("bin".equals(word)) {
            pos += Math.max(0, param);
            return;
        }
        word(word, param, hasParam, raw);
    }

    private void word(String word, int param, boolean hasParam, String raw) {
        boolean star = pendingStar;
        pendingStar = false;
        if (st.dest == Dest.SKIP) return;
        // Destinations
        switch (word) {
            case "fonttbl" -> { st.dest = Dest.FONTTBL; return; }
            case "colortbl" -> { st.dest = Dest.COLORTBL; colorHasValue = false; return; }
            case "info" -> { st.dest = Dest.INFO; return; }
            case "doccomm" -> { st.dest = st.dest == Dest.INFO ? Dest.DOCCOMM : Dest.SKIP; return; }
            case "comment" -> { st.dest = star ? Dest.DOCCOMM : Dest.SKIP; return; }
            case "fldinst" -> {
                if (st.field != null) st.dest = Dest.FLDINST; else st.dest = Dest.SKIP;
                return;
            }
            case "field" -> { st.field = new Field(); return; }
            case "fldrslt" -> {
                if (st.field != null) st.link = hyperlink(st.field.inst.toString());
                return;
            }
            case "NeXTGraphic" -> {
                st.dest = Dest.GRAPHIC;
                graphicName = "";
                graphicW = graphicH = 0;
                return;
            }
            default -> {}
        }
        if (star || SKIP_DESTINATIONS.contains(word)) {
            st.dest = Dest.SKIP;
            return;
        }
        if (st.dest == Dest.GRAPHIC) {
            if ("width".equals(word)) graphicW = param;
            else if ("height".equals(word)) graphicH = param;
            return;
        }
        if (st.dest == Dest.FONTTBL) {
            switch (word) {
                case "f" -> {
                    finishFontEntry();
                    fontEntry = param;
                    fontEntryCharset = 0;
                }
                case "fcharset" -> fontEntryCharset = param;
                case "u" -> unicode(param);
                default -> {}
            }
            return;
        }
        if (st.dest == Dest.COLORTBL) {
            switch (word) {
                case "red" -> { red = param; colorHasValue = true; }
                case "green" -> { green = param; colorHasValue = true; }
                case "blue" -> { blue = param; colorHasValue = true; }
                default -> {}
            }
            return;
        }
        if (st.dest == Dest.INFO) return;
        if (st.dest == Dest.FLDINST || st.dest == Dest.DOCCOMM) {
            if ("u".equals(word)) unicode(param);
            else if ("uc".equals(word)) st.uc = param;
            else if ("tab".equals(word)) text('\t');
            return;
        }
        // Text
        switch (word) {
            case "ansicpg" -> codepage = charset(param, codepage);
            case "mac" -> codepage = charset(10000, codepage);
            case "uc" -> st.uc = Math.max(0, param);
            case "u" -> unicode(param);
            case "par" -> {
                paras.add(paraStyle());
                emit("\n");
            }
            case "line" -> emit(String.valueOf(RText.LINE_BREAK));
            case "tab" -> emit("\t");
            case "emdash" -> emit("—");
            case "endash" -> emit("–");
            case "bullet" -> emit("•");
            case "lquote" -> emit("‘");
            case "rquote" -> emit("’");
            case "ldblquote" -> emit("“");
            case "rdblquote" -> emit("”");
            case "emspace", "enspace", "qmspace" -> emit(" ");
            case "plain" -> {
                st.font = -1;
                st.halfPoints = 24;
                st.bold = st.italic = st.underline = st.strike = false;
                st.fg = st.bg = st.script = 0;
                st.extras.clear();
            }
            case "f" -> st.font = param;
            case "fs" -> st.halfPoints = hasParam ? param : 24;
            case "b" -> st.bold = !hasParam || param != 0;
            case "i" -> st.italic = !hasParam || param != 0;
            case "ul", "uld", "uldb", "uldash", "uldashd", "uldashdd", "ulth", "ulw", "ulwave", "ulhwave", "ululdbwave",
                    "ulthd", "ulthdash", "ulldash", "ulthldash", "ulthdashd", "ulthdashdd" -> {
                st.underline = !hasParam || param != 0;
                if (!"ul".equals(word)) st.extras.put("ulstyle", raw);
            }
            case "ulnone" -> {
                st.underline = false;
                st.extras.remove("ulstyle");
                st.extras.remove("ulc");
            }
            case "strike", "striked" -> st.strike = !hasParam || param != 0;
            case "cf" -> st.fg = param;
            case "cb", "highlight", "chcbpat" -> st.bg = param;
            case "super" -> st.script = 1;
            case "sub" -> st.script = -1;
            case "nosupersub" -> st.script = 0;
            case "up" -> {
                if (param != 0) st.extras.put("up", raw); else st.extras.remove("up");
                if (param > 0 && st.script == 0) st.script = 1;
            }
            case "dn" -> {
                if (param != 0) st.extras.put("dn", raw); else st.extras.remove("dn");
                if (param > 0 && st.script == 0) st.script = -1;
            }
            case "pard" -> {
                st.align = 0;
                st.li = st.fi = st.sb = st.sa = 0;
                st.pextras.setLength(0);
            }
            case "ql" -> st.align = ParaStyle.LEFT;
            case "qc" -> st.align = ParaStyle.CENTER;
            case "qr" -> st.align = ParaStyle.RIGHT;
            case "qj" -> st.align = ParaStyle.JUSTIFIED;
            case "li" -> st.li = param / 20.0;
            case "fi" -> st.fi = param / 20.0;
            case "sb" -> st.sb = param / 20.0;
            case "sa" -> st.sa = param / 20.0;
            default -> {
                if (DOC_WORDS.contains(word) || COLOR_INDEX_WORDS.contains(word)) return;
                if (PARA_WORDS.contains(word)) {
                    if (!"ls".equals(word) && !"ilvl".equals(word)) st.pextras.append(raw);
                } else {
                    st.extras.put(word, raw);
                }
            }
        }
    }

    private static String hyperlink(String inst) {
        Matcher m = Pattern.compile("HYPERLINK\\s+(?:\\\\l\\s+)?\"([^\"]*)\"").matcher(inst);
        if (m.find()) return m.group(1);
        m = Pattern.compile("HYPERLINK\\s+(\\S+)").matcher(inst);
        return m.find() ? m.group(1) : null;
    }

    private void unicode(int param) {
        char ch = (char) (param < 0 ? param + 65536 : param);
        flushBytes();
        text(ch);
        skipChars = st.uc;
    }

    private void emit(String s) {
        if (st.dest != Dest.TEXT) return;
        out.append(s, charStyle(st));
    }

    /** A character written as itself (or escaped) in the RTF: skipped while a \\u's fallback is being passed over. */
    private void plainChar(char c) {
        if (skipChars > 0) {
            skipChars--;
            return;
        }
        text(c);
    }

    private void text(char c) {
        switch (st.dest) {
            case TEXT -> {
                if (swallowAttachmentChar) {
                    swallowAttachmentChar = false;
                    if (c == '¬') return;
                }
                out.append(String.valueOf(c), charStyle(st));
            }
            case FONTTBL -> {
                if (c == ';') finishFontEntry();
                else fontName.append(c);
            }
            case COLORTBL -> {
                if (c == ';') {
                    colors.add(colorHasValue ? new RGB(red, green, blue) : null);
                    colorHasValue = false;
                    red = green = blue = 0;
                }
            }
            case FLDINST -> st.field.inst.append(c);
            case DOCCOMM -> comment.append(c);
            case GRAPHIC -> graphicName += c;
            default -> {}
        }
    }

    private void flushBytes() {
        if (pendingBytes.size() == 0) return;
        byte[] bytes = pendingBytes.toByteArray();
        pendingBytes.reset();
        Charset cs = codepage;
        Integer fcs = st.dest == Dest.TEXT ? fontCharsets.get(st.font) : null;
        if (fcs != null && fcs != 0 && fcs != 1) cs = charsetForFontCharset(fcs, cs);
        String s = new String(bytes, cs);
        for (int i = 0; i < s.length(); i++) text(s.charAt(i));
    }

    private void finishFontEntry() {
        if (fontEntry >= 0) {
            String name = fontName.toString().trim();
            if (name.endsWith(";")) name = name.substring(0, name.length() - 1);
            fonts.put(fontEntry, name);
            fontCharsets.put(fontEntry, fontEntryCharset);
        }
        fontEntry = -1;
        fontName.setLength(0);
    }

    private static Charset charset(int cp, Charset fallback) {
        try {
            return switch (cp) {
                case 10000 -> Charset.forName("x-MacRoman");
                case 65001 -> StandardCharsets.UTF_8;
                default -> Charset.forName("windows-" + cp);
            };
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static Charset charsetForFontCharset(int fcharset, Charset fallback) {
        int cp = switch (fcharset) {
            case 77 -> 10000;
            case 128 -> 932;
            case 129 -> 949;
            case 134 -> 936;
            case 136 -> 950;
            case 161 -> 1253;
            case 162 -> 1254;
            case 177 -> 1255;
            case 178 -> 1256;
            case 186 -> 1257;
            case 204 -> 1251;
            case 222 -> 874;
            case 238 -> 1250;
            default -> 0;
        };
        if (cp == 0) return fallback;
        try {
            return cp == 10000 ? Charset.forName("x-MacRoman") : cp == 932 ? Charset.forName("Shift_JIS") : Charset.forName("windows-" + cp);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private CharStyle charStyle(State s) {
        Fonts.Face face = Fonts.parse(fonts.getOrDefault(s.font, fonts.isEmpty() ? "Helvetica" : fonts.get(fonts.keySet().iterator().next())));
        RGB fg = s.fg > 0 && s.fg < colors.size() ? colors.get(s.fg) : null;
        RGB bg = s.bg > 0 && s.bg < colors.size() ? colors.get(s.bg) : null;
        // White behind text is the page's paper (Cocoa's colour table always starts with white), not a highlight.
        if (bg != null && bg.red == 255 && bg.green == 255 && bg.blue == 255) bg = null;
        String extras = String.join("", s.extras.values());
        return new CharStyle(face.family(), s.halfPoints / 2.0, s.bold || face.bold(), s.italic || face.italic(), s.underline, s.strike,
                fg, bg, s.script, s.link, null, null, extras);
    }

    private ParaStyle paraStyle() {
        return new ParaStyle(st.align, st.li + st.fi, st.li, st.sb, st.sa, st.pextras.toString());
    }

    /** Puts the Mac app's link hover texts and picture sizes from the document comment back on the text. */
    private RText applyComment(RText text) {
        String c = comment.toString().trim();
        JsonArray tips = null, sizes = null;
        try {
            if (c.startsWith(RICH_MARKER)) {
                JsonObject o = JsonParser.parseString(c.substring(RICH_MARKER.length())).getAsJsonObject();
                tips = o.has("tips") ? o.getAsJsonArray("tips") : null;
                sizes = o.has("sizes") ? o.getAsJsonArray("sizes") : null;
            } else if (c.startsWith(TIPS_MARKER)) {
                tips = JsonParser.parseString(c.substring(TIPS_MARKER.length())).getAsJsonArray();
            }
        } catch (RuntimeException e) {
            return text;
        }
        if (tips == null && sizes == null) return text;
        List<RText.Span> spans = new ArrayList<>(text.spans);
        if (tips != null) {
            for (JsonElement e : tips) {
                JsonObject t = e.getAsJsonObject();
                int at = t.get("at").getAsInt(), len = t.get("length").getAsInt();
                String rtf = t.get("rtf").getAsString();
                spans = restyle(spans, at, at + len, s -> s.withTip(rtf));
            }
        }
        if (sizes != null) {
            for (JsonElement e : sizes) {
                JsonObject t = e.getAsJsonObject();
                int at = t.get("at").getAsInt();
                double w = t.get("w").getAsDouble(), h = t.get("h").getAsDouble();
                spans = restyle(spans, at, at + 1, s -> s.attachment() == null ? s
                        : s.withAttachment(new CharStyle.Attachment(s.attachment().fileName(), s.attachment().data(), w, h)));
            }
        }
        return new RText(text.text, spans, text.paras);
    }

    /** Changes the style of [start, end), splitting runs as needed. */
    static List<RText.Span> restyle(List<RText.Span> spans, int start, int end, java.util.function.UnaryOperator<CharStyle> f) {
        List<RText.Span> out = new ArrayList<>();
        for (RText.Span s : spans) {
            if (s.end() <= start || s.start() >= end) {
                out.add(s);
                continue;
            }
            if (s.start() < start) out.add(new RText.Span(s.start(), start, s.style()));
            out.add(new RText.Span(Math.max(s.start(), start), Math.min(s.end(), end), f.apply(s.style())));
            if (s.end() > end) out.add(new RText.Span(end, s.end(), s.style()));
        }
        return out;
    }
}
