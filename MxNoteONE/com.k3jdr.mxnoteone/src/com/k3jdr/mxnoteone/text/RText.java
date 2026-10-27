package com.k3jdr.mxnoteone.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Rich text as notes keep it (an NSAttributedString): the characters — "\n" ends a paragraph, U+2028 breaks a line
 * inside one (Shift+Enter), U+FFFC stands for an attachment — runs of {@link CharStyle} covering them, and one
 * {@link ParaStyle} per paragraph.
 */
public final class RText {
    public static final char LINE_BREAK = ' ';
    public static final char ATTACHMENT = '￼';

    public record Span(int start, int end, CharStyle style) {}

    public final String text;
    public final List<Span> spans;
    public final List<ParaStyle> paras;

    public RText(String text, List<Span> spans, List<ParaStyle> paras) {
        this.text = text;
        this.spans = Collections.unmodifiableList(spans);
        List<ParaStyle> p = new ArrayList<>(paras);
        int count = paragraphCount(text);
        while (p.size() < count) p.add(p.isEmpty() ? ParaStyle.DEFAULT : p.get(p.size() - 1));
        while (p.size() > count) p.remove(p.size() - 1);
        this.paras = Collections.unmodifiableList(p);
    }

    public static int paragraphCount(String text) {
        int n = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    public static RText plain(String text, CharStyle style) {
        List<Span> spans = new ArrayList<>();
        if (!text.isEmpty()) spans.add(new Span(0, text.length(), style));
        return new RText(text, spans, List.of(ParaStyle.DEFAULT));
    }

    public static RText empty() { return plain("", CharStyle.DEFAULT); }

    public int length() { return text.length(); }

    public boolean isBlank() {
        return text.replace(ATTACHMENT, 'x').isBlank();
    }

    public boolean hasAttachments() { return text.indexOf(ATTACHMENT) >= 0; }

    public CharStyle styleAt(int i) {
        for (Span s : spans) if (i >= s.start && i < s.end) return s.style;
        return spans.isEmpty() ? CharStyle.DEFAULT : spans.get(spans.size() - 1).style;
    }

    /** The text as the Mac's NSString.string gives it (line breaks as they are). */
    public String string() { return text; }

    /** Builds rich text piece by piece (the RTF reader, templates). */
    public static final class Builder {
        private final StringBuilder text = new StringBuilder();
        private final List<Span> spans = new ArrayList<>();
        private final List<ParaStyle> paras = new ArrayList<>();

        public int length() { return text.length(); }

        public Builder append(String s, CharStyle style) {
            if (s.isEmpty()) return this;
            int start = text.length();
            text.append(s);
            Span last = spans.isEmpty() ? null : spans.get(spans.size() - 1);
            if (last != null && last.end == start && last.style.equals(style) && style.attachment() == null) {
                spans.set(spans.size() - 1, new Span(last.start, text.length(), style));
            } else {
                spans.add(new Span(start, text.length(), style));
            }
            return this;
        }

        /** Ends the paragraph being built with this style ("\n" is added). */
        public Builder endParagraph(ParaStyle style, CharStyle newlineStyle) {
            paras.add(style);
            append("\n", newlineStyle);
            return this;
        }

        /** Adds a line as its own paragraph (templates): a newline first unless it's the first. */
        public Builder line(String s, CharStyle style, ParaStyle para) {
            if (started) endParagraph(pendingPara, style);
            started = true;
            append(s, style);
            pendingPara = para;
            return this;
        }

        private ParaStyle pendingPara = ParaStyle.DEFAULT;
        private boolean started;

        public RText build(ParaStyle last) {
            List<ParaStyle> p = new ArrayList<>(paras);
            p.add(last != null ? last : pendingPara);
            return new RText(text.toString(), spans, p);
        }

        public RText build() { return build(null); }
    }

    /** A copy of part of the text, [start, end). */
    public RText slice(int start, int end) {
        start = Math.max(0, Math.min(start, text.length()));
        end = Math.max(start, Math.min(end, text.length()));
        List<Span> out = new ArrayList<>();
        for (Span s : spans) {
            int a = Math.max(s.start, start), b = Math.min(s.end, end);
            if (a < b) out.add(new Span(a - start, b - start, s.style));
        }
        int firstPara = paragraphCount(text.substring(0, start)) - 1;
        List<ParaStyle> p = new ArrayList<>(paras.subList(Math.min(firstPara, paras.size() - 1), paras.size()));
        return new RText(text.substring(start, end), out, p);
    }

    /** This text followed by another. */
    public RText concat(RText other) {
        List<Span> out = new ArrayList<>(spans);
        for (Span s : other.spans) out.add(new Span(s.start + text.length(), s.end + text.length(), s.style));
        List<ParaStyle> p = new ArrayList<>(paras);
        if (!other.paras.isEmpty()) {
            p.remove(p.size() - 1);
            p.add(other.paras.get(0));
            p.addAll(other.paras.subList(1, other.paras.size()));
        }
        return new RText(text + other.text, out, p);
    }

    /** The same text with every run's style changed. */
    public RText mapStyles(java.util.function.UnaryOperator<CharStyle> f) {
        List<Span> out = new ArrayList<>();
        for (Span s : spans) out.add(new Span(s.start, s.end, f.apply(s.style)));
        return new RText(text, out, paras);
    }
}
