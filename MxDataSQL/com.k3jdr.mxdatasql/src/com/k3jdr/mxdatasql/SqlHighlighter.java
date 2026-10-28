package com.k3jdr.mxdatasql;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;

/**
 * Syntax colouring for the SQL box in VS Code's Light+ colours (SQLHighlighter.swift): a small scanner that knows
 * PostgreSQL's lexical rules — '...' and E'...' strings, $tag$ … $tag$ dollar quoting, "quoted identifiers",
 * -- and nested /* *&#47; comments, numbers, $1 parameters, keywords, data types (also after ::) and function calls.
 */
public final class SqlHighlighter {
    private SqlHighlighter() {}

    public enum Token { KEYWORD, TYPE, FUNCTION, STRING, NUMBER, COMMENT, QUOTED_IDENTIFIER, PARAMETER }

    public record Range(int start, int length, Token token) {}

    private static final Color[] COLORS = {
        new Color(new RGB(0x00, 0x00, 0xFF)),   // keyword
        new Color(new RGB(0x26, 0x7F, 0x99)),   // type
        new Color(new RGB(0x79, 0x5E, 0x26)),   // function
        new Color(new RGB(0xA3, 0x15, 0x15)),   // string
        new Color(new RGB(0x09, 0x86, 0x58)),   // number
        new Color(new RGB(0x00, 0x80, 0x00)),   // comment
        new Color(new RGB(0x00, 0x10, 0x80)),   // quoted identifier
        new Color(new RGB(0xAF, 0x00, 0xDB)),   // parameter
    };

    public static Color color(Token t) { return COLORS[t.ordinal()]; }

    static final Set<String> KEYWORDS = words("""
        abort absolute access action add admin after aggregate all also alter always analyse analyze and any array as
        asc assertion assignment asymmetric at attach authorization backward before begin between both by cache call
        called cascade cascaded case cast catalog chain characteristics check checkpoint class close cluster coalesce
        collate collation column columns comment comments commit committed concurrently configuration conflict
        connection constraint constraints content continue conversion copy cost create cross csv cube current cursor
        cycle data database day deallocate declare default defaults deferrable deferred definer delete delimiter
        delimiters depends desc detach dictionary disable discard distinct do document domain drop each else enable
        encoding encrypted end enum escape event except exclude excluding exclusive execute exists explain expression
        extension external extract false family fetch filter first following for force foreign forward freeze from
        full function functions generated global grant granted greatest group grouping groups handler having header
        hold hour identity if ilike immediate immutable implicit import in include including increment index indexes
        inherit inherits initially inline inner inout input insensitive insert instead intersect into invoker is
        isnull isolation join key label language large last lateral leading leakproof least left level like limit
        listen load local localtime localtimestamp location lock locked logged mapping match matched materialized
        maxvalue merge method minute minvalue mode month move name names natural new next nfc nfd nfkc nfkd no none
        normalize not nothing notify notnull nowait null nullif nulls object of off offset oids old on only operator
        option options or order ordinality others out outer over overlaps overlay overriding owned owner parallel
        parser partial partition passing password placing plans policy position preceding prepare prepared preserve
        primary prior privileges procedural procedure procedures program publication quote range read reassign
        recheck recursive ref references referencing refresh reindex relative release rename repeatable replace
        replica reset restart restrict return returning returns revoke right role rollback rollup routine routines
        row rows rule savepoint schema schemas scroll search second security select sequence sequences serializable
        server session session_user set setof sets share show similar simple skip snapshot some sql stable standalone
        start statement statistics stdin stdout storage stored strict strip subscription substring support symmetric
        sysid system table tables tablesample tablespace temp template temporary then ties to trailing transaction
        transform treat trigger trim true truncate trusted type types uescape unbounded uncommitted unencrypted union
        unique unknown unlisten unlogged until update user using vacuum valid validate validator value values
        variadic verbose version view views volatile when where whitespace window with within without work wrapper
        write year yes zone current_date current_time current_timestamp current_user current_role current_schema
        returns language plpgsql loop exit raise notice exception perform elsif while foreach reverse found strict
        """);

    static final Set<String> TYPES = words("""
        bigint bigserial bit bool boolean bpchar bytea char character cidr circle date decimal double float float4
        float8 inet int int2 int4 int8 integer interval json jsonb lseg macaddr macaddr8 money numeric oid
        polygon precision real record regclass regproc regtype serial serial2 serial4 serial8 smallint
        smallserial text time timestamp timestamptz timetz tsquery tsvector txid_snapshot uuid varbit varchar varying
        void xml anyelement anyarray cstring tid xid
        """);

    /** Keywords that stay keywords when followed by "(" (otherwise "name(" is a function call). */
    private static final Set<String> KEYWORDS_BEFORE_PAREN = words("""
        in exists values any all some array as on using over filter within and or not from join select where returns
        table row cast case when then else into partition by with lateral unique primary key check references default
        is like ilike between set update insert delete call coalesce nullif greatest least extract substring trim
        position overlay
        """);

    private static Set<String> words(String text) {
        return new HashSet<>(Arrays.asList(text.trim().split("\\s+")));
    }

    /** The coloured ranges of {@code s} (UTF-16 indexes, as StyledText counts). */
    public static List<Range> tokens(String s) {
        List<Range> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        boolean afterCast = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '-' && Sql.at(s, i + 1) == '-') {
                int k = i + 2;
                while (k < n && s.charAt(k) != '\n') k++;
                add(out, i, k, Token.COMMENT);
                i = k;
                continue;
            }
            if (c == '/' && Sql.at(s, i + 1) == '*') {
                int depth = 1, k = i + 2;
                while (k < n && depth > 0) {
                    if (s.charAt(k) == '/' && Sql.at(s, k + 1) == '*') { depth++; k += 2; }
                    else if (s.charAt(k) == '*' && Sql.at(s, k + 1) == '/') { depth--; k += 2; }
                    else k++;
                }
                add(out, i, Math.min(k, n), Token.COMMENT);
                i = k;
                continue;
            }
            if (c == '\'') {
                int k = Sql.endOfQuoted(s, i, '\'', false);
                add(out, i, k, Token.STRING);
                i = k;
                afterCast = false;
                continue;
            }
            if ((c == 'E' || c == 'e') && Sql.at(s, i + 1) == '\'' && (i == 0 || !Sql.isIdentChar(s.charAt(i - 1)))) {
                int k = Sql.endOfQuoted(s, i + 1, '\'', true);
                add(out, i, Math.min(k, n), Token.STRING);
                i = k;
                afterCast = false;
                continue;
            }
            if (c == '"') {
                int k = Sql.endOfQuoted(s, i, '"', false);
                add(out, i, k, afterCast ? Token.TYPE : Token.QUOTED_IDENTIFIER);
                i = k;
                afterCast = false;
                continue;
            }
            if (c == '$') {
                if (Character.isDigit(Sql.at(s, i + 1))) {
                    int k = i + 1;
                    while (k < n && Character.isDigit(s.charAt(k))) k++;
                    add(out, i, k, Token.PARAMETER);
                    i = k;
                    continue;
                }
                int tagEnd = Sql.dollarTagEnd(s, i);
                if (tagEnd > 0) {
                    String tag = s.substring(i, tagEnd);
                    int close = s.indexOf(tag, tagEnd);
                    int k = close < 0 ? n : close + tag.length();
                    add(out, i, k, Token.STRING);
                    i = k;
                    continue;
                }
            }
            if (Character.isDigit(c) || (c == '.' && Character.isDigit(Sql.at(s, i + 1)))) {
                int k = i;
                while (k < n && (Character.isDigit(s.charAt(k)) || s.charAt(k) == '.')) k++;
                char e = Sql.at(s, k);
                if ((e == 'e' || e == 'E') && (Character.isDigit(Sql.at(s, k + 1))
                        || ((Sql.at(s, k + 1) == '+' || Sql.at(s, k + 1) == '-') && Character.isDigit(Sql.at(s, k + 2))))) {
                    k += 2;
                    while (k < n && Character.isDigit(s.charAt(k))) k++;
                }
                add(out, i, k, Token.NUMBER);
                i = k;
                afterCast = false;
                continue;
            }
            if (Sql.isIdentStart(c)) {
                int k = i;
                while (k < n && Sql.isIdentChar(s.charAt(k))) k++;
                String word = s.substring(i, k).toLowerCase();
                int j = k;
                while (j < n && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) j++;
                boolean call = Sql.at(s, j) == '(';
                boolean previousIsDot = i > 0 && s.charAt(i - 1) == '.';
                if (afterCast || (TYPES.contains(word) && !previousIsDot)) {
                    add(out, i, k, Token.TYPE);
                } else if (KEYWORDS.contains(word) && !previousIsDot && !(call && !KEYWORDS_BEFORE_PAREN.contains(word))) {
                    add(out, i, k, Token.KEYWORD);
                } else if (call) {
                    add(out, i, k, Token.FUNCTION);
                }
                i = k;
                afterCast = false;
                continue;
            }
            if (c == ':' && Sql.at(s, i + 1) == ':') {
                afterCast = true;
                i += 2;
                continue;
            }
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') afterCast = false;
            i++;
        }
        return out;
    }

    private static void add(List<Range> out, int start, int end, Token token) {
        if (end > start) out.add(new Range(start, end - start, token));
    }

    /**
     * Colours a StyledText's whole text. With {@code sqlBodies}, the body of pg_get_functiondef's
     * {@code AS $function$ … $function$} is coloured as SQL rather than as one string (LANGUAGE sql and plpgsql).
     * {@code base} shifts every range (text that follows other text).
     */
    public static List<StyleRange> styles(String text, int base, boolean sqlBodies) {
        List<StyleRange> out = new ArrayList<>();
        for (Range r : tokens(text)) {
            String quoted = text.substring(r.start(), r.start() + r.length());
            String tag = null;
            if (sqlBodies && r.token() == Token.STRING) {
                for (String t : new String[] { "$function$", "$procedure$" }) {
                    if (quoted.startsWith(t) && quoted.endsWith(t) && quoted.length() >= 2 * t.length()) tag = t;
                }
            }
            if (tag != null) {
                int len = tag.length();
                out.add(style(base + r.start(), len, Token.STRING));
                String body = quoted.substring(len, quoted.length() - len);
                for (Range inner : tokens(body)) out.add(style(base + r.start() + len + inner.start(), inner.length(), inner.token()));
                out.add(style(base + r.start() + r.length() - len, len, Token.STRING));
            } else {
                out.add(style(base + r.start(), r.length(), r.token()));
            }
        }
        return out;
    }

    private static StyleRange style(int start, int length, Token token) {
        StyleRange sr = new StyleRange(start, length, color(token), null);
        return sr;
    }

    /** Recolours all of {@code text}. */
    public static void apply(StyledText text) {
        List<StyleRange> ranges = styles(text.getText(), 0, false);
        text.setStyleRanges(ranges.toArray(StyleRange[]::new));
    }
}
