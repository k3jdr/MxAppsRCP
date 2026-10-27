package com.k3jdr.mxnoteone.text;

import org.eclipse.swt.graphics.RGB;

/**
 * The formatting of a run of text, as NSAttributedString keeps it on the Mac: font family and size (in the Mac's
 * points), bold, italic, underline, strikethrough, colour (null = automatic black), highlight (NSBackgroundColor),
 * superscript / subscript, a link with its hover text, an inline attachment, and RTF control words this port
 * doesn't interpret (kept as written so they go back to the Mac unchanged).
 */
public record CharStyle(String font, double size, boolean bold, boolean italic, boolean underline, boolean strike, RGB color,
        RGB background, int script, String link, String tip, Attachment attachment, String extras) {

    /** A picture or file inside the text (an NSTextAttachment), with its file from the RTFD package. */
    public record Attachment(String fileName, byte[] data, double width, double height) {}

    public static final String DEFAULT_FONT = "Helvetica Neue";
    public static final double DEFAULT_SIZE = 14;
    public static final CharStyle DEFAULT = new CharStyle(DEFAULT_FONT, DEFAULT_SIZE, false, false, false, false, null, null, 0, null,
            null, null, "");

    public CharStyle withFont(String f) { return new CharStyle(f, size, bold, italic, underline, strike, color, background, script, link, tip, attachment, extras); }
    public CharStyle withSize(double s) { return new CharStyle(font, s, bold, italic, underline, strike, color, background, script, link, tip, attachment, extras); }
    public CharStyle withBold(boolean b) { return new CharStyle(font, size, b, italic, underline, strike, color, background, script, link, tip, attachment, extras); }
    public CharStyle withItalic(boolean i) { return new CharStyle(font, size, bold, i, underline, strike, color, background, script, link, tip, attachment, extras); }
    public CharStyle withUnderline(boolean u) { return new CharStyle(font, size, bold, italic, u, strike, color, background, script, link, tip, attachment, extras); }
    public CharStyle withStrike(boolean s) { return new CharStyle(font, size, bold, italic, underline, s, color, background, script, link, tip, attachment, extras); }
    public CharStyle withColor(RGB c) { return new CharStyle(font, size, bold, italic, underline, strike, c, background, script, link, tip, attachment, extras); }
    public CharStyle withBackground(RGB c) { return new CharStyle(font, size, bold, italic, underline, strike, color, c, script, link, tip, attachment, extras); }
    public CharStyle withScript(int s) { return new CharStyle(font, size, bold, italic, underline, strike, color, background, s, link, tip, attachment, extras); }
    public CharStyle withLink(String l) { return new CharStyle(font, size, bold, italic, underline, strike, color, background, script, l, l == null ? null : tip, attachment, extras); }
    public CharStyle withTip(String t) { return new CharStyle(font, size, bold, italic, underline, strike, color, background, script, link, t, attachment, extras); }
    public CharStyle withAttachment(Attachment a) { return new CharStyle(font, size, bold, italic, underline, strike, color, background, script, link, tip, a, extras); }
    public CharStyle withExtras(String x) { return new CharStyle(font, size, bold, italic, underline, strike, color, background, script, link, tip, attachment, x == null ? "" : x); }

    /** The style for typing on after this text: no attachment. */
    public CharStyle forTyping() { return attachment == null ? this : withAttachment(null); }

    public boolean isCode() {
        return Fonts.isMonospaced(font);
    }
}
