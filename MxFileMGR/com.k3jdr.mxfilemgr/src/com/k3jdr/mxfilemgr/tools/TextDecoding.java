package com.k3jdr.mxfilemgr.tools;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Bytes to text for the Viewer and Compare Files: a byte-order mark decides (UTF-8, UTF-16 LE/BE); otherwise
 * valid UTF-8 is UTF-8, and anything else is read in Windows' ANSI code page (where the Mac app replaces the bad
 * bytes, Windows text files are usually ANSI).
 */
final class TextDecoding {
    private TextDecoding() {}

    record Decoded(String text, String encoding) {}

    static Decoded decode(byte[] d) {
        return decode(d, d.length);
    }

    static Decoded decode(byte[] d, int length) {
        if (length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) {
            return new Decoded(new String(d, 3, length - 3, StandardCharsets.UTF_8), "UTF-8");
        }
        if (length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) {
            return new Decoded(new String(d, 2, length - 2, StandardCharsets.UTF_16LE), "UTF-16");
        }
        if (length >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) {
            return new Decoded(new String(d, 2, length - 2, StandardCharsets.UTF_16BE), "UTF-16");
        }
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(d, 0, trimPartial(d, length))).toString();
            return new Decoded(s, "UTF-8");
        } catch (CharacterCodingException e) {
            Charset ansi = ansi();
            return new Decoded(new String(d, 0, length, ansi), "Not UTF-8; shown as " + ansi.displayName());
        }
    }

    /** A file cut off after its first megabytes may end in the middle of a UTF-8 sequence: leave that out. */
    private static int trimPartial(byte[] d, int length) {
        int i = length - 1, back = 0;
        while (i >= 0 && back < 3 && (d[i] & 0xC0) == 0x80) {
            i--;
            back++;
        }
        if (i < 0) return length;
        int lead = d[i] & 0xFF;
        int need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
        return need > back + 1 ? i : length;
    }

    static Charset ansi() {
        try {
            String n = System.getProperty("native.encoding");
            if (n != null) return Charset.forName(n);
        } catch (RuntimeException e) {
            // fall through
        }
        try {
            return Charset.forName("windows-1252");
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}
