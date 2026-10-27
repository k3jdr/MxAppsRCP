package com.k3jdr.mxnoteone.text;

import org.eclipse.swt.graphics.RGB;

import com.k3jdr.mxnoteone.model.Kinds.TagKind;

/**
 * Bullets, numbers and tags are text at the start of a paragraph ("• ", "1. ", "☐ "), as on the Mac
 * (TextFormatter.parseParagraph); and the paragraph styles of Home ▸ Styles (TextStyleKind).
 */
public final class Paragraphs {
    private Paragraphs() {}

    public static final String BULLET = "• ";

    /** Structure of a paragraph's start: indent (tabs and spaces), list marker, tag. */
    public static final class Info {
        public int indent;
        /** 0 none, -1 bullet, n &gt; 0 the number. */
        public int list;
        public int listLength;
        public TagKind tag;
        public boolean tagDone;
        public int tagLength;

        /** Where the paragraph's own text starts. */
        public int contentStart() { return indent + listLength + tagLength; }
        public boolean isBullet() { return list == -1; }
        public boolean isNumber() { return list > 0; }
    }

    public static Info parse(String text) {
        Info info = new Info();
        int i = 0;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        info.indent = i;
        String rest = text.substring(i);
        if (rest.startsWith(BULLET)) {
            info.list = -1;
            info.listLength = BULLET.length();
        } else {
            int d = 0;
            while (d < rest.length() && d < 5 && rest.charAt(d) >= '0' && rest.charAt(d) <= '9') d++;
            if (d > 0 && d < 5 && rest.startsWith(". ", d)) {
                info.list = Integer.parseInt(rest.substring(0, d));
                info.listLength = d + 2;
            }
        }
        String afterList = text.substring(info.indent + info.listLength);
        outer:
        for (TagKind k : TagKind.values()) {
            String[] symbols = k == TagKind.TODO ? new String[] { k.symbol, TagKind.TODO_DONE } : new String[] { k.symbol };
            for (String s : symbols) {
                if (afterList.startsWith(s + " ")) {
                    info.tag = k;
                    info.tagDone = s.equals(TagKind.TODO_DONE);
                    info.tagLength = s.length() + 1;
                    break outer;
                }
            }
        }
        return info;
    }

    /** Home ▸ Styles. */
    public enum Style {
        TITLE("Page Title"), HEADING1("Heading 1"), HEADING2("Heading 2"), HEADING3("Heading 3"), HEADING4("Heading 4"),
        NORMAL("Normal"), QUOTE("Quote"), CODE("Code");

        public final String title;

        Style(String title) { this.title = title; }

        static final RGB HEADING = new RGB(41, 79, 143);

        /** The style's character formatting on the notebook's base font. */
        public CharStyle apply(CharStyle base) {
            CharStyle b = base.withAttachment(null).withScript(0).withBackground(null).withUnderline(false).withStrike(false)
                    .withFont(CharStyle.DEFAULT_FONT).withSize(CharStyle.DEFAULT_SIZE).withBold(false).withItalic(false).withColor(null);
            return switch (this) {
                case TITLE -> b.withSize(26);
                case HEADING1 -> b.withSize(20).withBold(true).withColor(HEADING);
                case HEADING2 -> b.withSize(17).withBold(true).withColor(HEADING);
                case HEADING3 -> b.withSize(15).withBold(true).withColor(new RGB(61, 110, 179));
                case HEADING4 -> b.withSize(14).withBold(true).withItalic(true).withColor(new RGB(89, 89, 89));
                case NORMAL -> b;
                case QUOTE -> b.withItalic(true).withColor(new RGB(97, 97, 97));
                case CODE -> b.withFont("Menlo").withSize(Math.max(11, CharStyle.DEFAULT_SIZE - 1)).withBackground(new RGB(240, 240, 240));
            };
        }

        /** The heading style text is in, if any (sizes and colours as the styles give them). */
        public static Style headingOf(CharStyle s) {
            for (Style k : new Style[] { TITLE, HEADING1, HEADING2, HEADING3, HEADING4 }) {
                CharStyle h = k.apply(CharStyle.DEFAULT);
                if (Math.abs(h.size() - s.size()) < 0.5 && h.bold() == s.bold() && near(h.color(), s.color())) return k;
            }
            return null;
        }

        private static boolean near(RGB a, RGB b) {
            RGB x = a == null ? new RGB(0, 0, 0) : a, y = b == null ? new RGB(0, 0, 0) : b;
            return Math.abs(x.red - y.red) < 6 && Math.abs(x.green - y.green) < 6 && Math.abs(x.blue - y.blue) < 6;
        }
    }
}
