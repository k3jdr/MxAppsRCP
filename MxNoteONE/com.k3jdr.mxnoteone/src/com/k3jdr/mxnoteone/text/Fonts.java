package com.k3jdr.mxnoteone.text;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Font names between the Mac and Windows. The Mac's rich text names a face by its PostScript name
 * ("HelveticaNeue-Bold", "Menlo-Regular", "ArialMT"); notes keep the family ("Helvetica Neue") with bold and
 * italic, write the face's PostScript name back, and draw with a Windows font that looks alike where the Mac's
 * font isn't installed (Helvetica Neue → Arial, Menlo → Consolas).
 */
public final class Fonts {
    private Fonts() {}

    /** A face: its family as notes keep it, and its traits. */
    public record Face(String family, boolean bold, boolean italic) {}

    /** Family → PostScript names of its regular, bold, italic and bold italic faces. */
    private static final Map<String, String[]> FACES = new LinkedHashMap<>();
    private static final Map<String, Face> BY_PS = new LinkedHashMap<>();

    private static void family(String family, String regular, String bold, String italic, String boldItalic) {
        FACES.put(family, new String[] { regular, bold, italic, boldItalic });
        BY_PS.put(regular, new Face(family, false, false));
        BY_PS.put(bold, new Face(family, true, false));
        BY_PS.put(italic, new Face(family, false, true));
        BY_PS.put(boldItalic, new Face(family, true, true));
    }

    static {
        family("Helvetica Neue", "HelveticaNeue", "HelveticaNeue-Bold", "HelveticaNeue-Italic", "HelveticaNeue-BoldItalic");
        family("Helvetica", "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique");
        family("Menlo", "Menlo-Regular", "Menlo-Bold", "Menlo-Italic", "Menlo-BoldItalic");
        family("Monaco", "Monaco", "Monaco-Bold", "Monaco-Italic", "Monaco-BoldItalic");
        family("Times", "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic");
        family("Courier", "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique");
        family("Arial", "ArialMT", "Arial-BoldMT", "Arial-ItalicMT", "Arial-BoldItalicMT");
        family("Times New Roman", "TimesNewRomanPSMT", "TimesNewRomanPS-BoldMT", "TimesNewRomanPS-ItalicMT", "TimesNewRomanPS-BoldItalicMT");
        family("Courier New", "CourierNewPSMT", "CourierNewPS-BoldMT", "CourierNewPS-ItalicMT", "CourierNewPS-BoldItalicMT");
        family("Georgia", "Georgia", "Georgia-Bold", "Georgia-Italic", "Georgia-BoldItalic");
        family("Verdana", "Verdana", "Verdana-Bold", "Verdana-Italic", "Verdana-BoldItalic");
        family("Trebuchet MS", "TrebuchetMS", "TrebuchetMS-Bold", "TrebuchetMS-Italic", "Trebuchet-BoldItalic");
        family("Tahoma", "Tahoma", "Tahoma-Bold", "Tahoma-Italic", "Tahoma-BoldItalic");
        family("Calibri", "Calibri", "Calibri-Bold", "Calibri-Italic", "Calibri-BoldItalic");
        family("Cambria", "Cambria", "Cambria-Bold", "Cambria-Italic", "Cambria-BoldItalic");
        family("Consolas", "Consolas", "Consolas-Bold", "Consolas-Italic", "Consolas-BoldItalic");
        family("Segoe UI", "SegoeUI", "SegoeUI-Bold", "SegoeUI-Italic", "SegoeUI-BoldItalic");
        family("Aptos", "Aptos", "Aptos-Bold", "Aptos-Italic", "Aptos-BoldItalic");
        family("Avenir Next", "AvenirNext-Regular", "AvenirNext-Bold", "AvenirNext-Italic", "AvenirNext-BoldItalic");
        family("Palatino", "Palatino-Roman", "Palatino-Bold", "Palatino-Italic", "Palatino-BoldItalic");
        family("Comic Sans MS", "ComicSansMS", "ComicSansMS-Bold", "ComicSansMS-Italic", "ComicSansMS-BoldItalic");
    }

    private static final Set<String> STYLE_SUFFIXES = Set.of("regular", "roman", "bold", "italic", "oblique", "bolditalic",
            "boldoblique", "boldmt", "italicmt", "bolditalicmt", "book");

    /** The family and traits of a face named in RTF (a PostScript name, or a family name). */
    public static Face parse(String name) {
        if (name == null || name.isBlank()) return new Face(CharStyle.DEFAULT_FONT, false, false);
        String n = name.trim();
        Face known = BY_PS.get(n);
        if (known != null) return known;
        if (FACES.containsKey(n)) return new Face(n, false, false);
        int dash = n.lastIndexOf('-');
        if (dash > 0) {
            String suffix = n.substring(dash + 1).toLowerCase(Locale.ROOT);
            if (STYLE_SUFFIXES.contains(suffix)) {
                String base = n.substring(0, dash);
                boolean b = suffix.contains("bold"), i = suffix.contains("italic") || suffix.contains("oblique");
                for (Map.Entry<String, String[]> e : FACES.entrySet()) {
                    if (e.getValue()[0].equals(base) || e.getValue()[0].startsWith(base + "-")) return new Face(e.getKey(), b, i);
                }
                return new Face(base, b, i);
            }
        }
        return new Face(n, false, false);
    }

    /** The name written into RTF for a family's face. */
    public static String rtfName(String family, boolean bold, boolean italic) {
        String[] faces = FACES.get(family);
        if (faces == null) return family;
        return faces[(bold ? 1 : 0) + (italic ? 2 : 0)];
    }

    /** True when the family is a known face family (so bold and italic are faces of their own in RTF). */
    public static boolean hasFaces(String family) { return FACES.containsKey(family); }

    /** A family's name for the font box (the Mac's system fonts by a readable name). */
    public static String displayName(String family) {
        if (family == null) return CharStyle.DEFAULT_FONT;
        if (family.startsWith(".AppleSystemUIFontMonospaced") || family.startsWith(".SFNSMono") || family.startsWith(".SFMono")) return "System Monospaced";
        if (family.startsWith(".")) return "System Font";
        return family;
    }

    private static Predicate<String> installed = f -> true;

    /** Tells the mapping which fonts Windows has (set by the UI from the display's font list). */
    public static void setInstalled(Predicate<String> isInstalled) { installed = isInstalled; }

    /** The installed font text in this family is drawn with. */
    public static String windowsFamily(String family) {
        if (family == null) return "Arial";
        if (!family.startsWith(".") && installed.test(family)) return family;
        String f = family.toLowerCase(Locale.ROOT).replace(" ", "");
        if (isMonospaced(family)) return f.startsWith("courier") ? "Courier New" : "Consolas";
        if (f.startsWith("times") || f.startsWith("baskerville") || f.startsWith("hoefler") || f.startsWith("didot")
                || f.startsWith("newyork") || f.startsWith("charter")) return "Times New Roman";
        if (f.startsWith("palatino") && installed.test("Palatino Linotype")) return "Palatino Linotype";
        if (f.startsWith("georgia")) return "Georgia";
        if (f.startsWith("markerfelt") || f.startsWith("chalkboard") || f.startsWith("noteworthy")) return "Comic Sans MS";
        if (f.startsWith(".") || f.startsWith("sfpro") || f.startsWith("sfns") || f.startsWith("system")) return "Segoe UI";
        if (f.startsWith("avenir") || f.startsWith("gillsans") || f.startsWith("optima")) return "Segoe UI";
        String spaced = family.replaceAll("([a-z])([A-Z])", "$1 $2");
        if (!spaced.equals(family) && installed.test(spaced)) return spaced;
        return "Arial";
    }

    public static boolean isMonospaced(String family) {
        if (family == null) return false;
        String f = family.toLowerCase(Locale.ROOT).replace(" ", "");
        return f.startsWith("menlo") || f.startsWith("monaco") || f.contains("mono") || f.startsWith("courier")
                || f.startsWith("consolas") || f.startsWith("cascadia") || f.startsWith("andale") || f.startsWith("sfmono");
    }

    /** A table cell's own font as the Mac saves it: "PostScriptName size". */
    public static String savedCellFont(String family, double size, boolean bold, boolean italic) {
        return rtfName(family, bold, italic) + " " + (size == Math.rint(size) ? (long) size + ".0" : Double.toString(size));
    }

    /** A table cell's saved font back: {face, size}; null when it can't be read. */
    public static Object[] parseCellFont(String saved) {
        if (saved == null) return null;
        int space = saved.lastIndexOf(' ');
        if (space <= 0) return null;
        try {
            double size = Double.parseDouble(saved.substring(space + 1));
            return new Object[] { parse(saved.substring(0, space)), size };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Families offered first in the font box (as the Mac's short list), then every installed font. */
    public static final String[] SHORT_LIST = { "Helvetica Neue", "Calibri", "Aptos", "Segoe UI", "Arial", "Cambria", "Georgia",
            "Times New Roman", "Verdana", "Consolas", "Menlo", "Courier New" };
}
