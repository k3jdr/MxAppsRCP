package com.k3jdr.mx.dbml;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.k3jdr.mx.dbml.DbmlSchema.Column;
import com.k3jdr.mx.dbml.DbmlSchema.End;
import com.k3jdr.mx.dbml.DbmlSchema.EnumType;
import com.k3jdr.mx.dbml.DbmlSchema.EnumValue;
import com.k3jdr.mx.dbml.DbmlSchema.Group;
import com.k3jdr.mx.dbml.DbmlSchema.LineRange;
import com.k3jdr.mx.dbml.DbmlSchema.Problem;
import com.k3jdr.mx.dbml.DbmlSchema.Ref;
import com.k3jdr.mx.dbml.DbmlSchema.StickyNote;
import com.k3jdr.mx.dbml.DbmlSchema.Table;

/**
 * Reads DBML (dbml.dbdiagram.io) with a forgiving parser (DBMLLanguage.swift's DBMLParser): what it can't read is
 * listed in the schema's problems by line; the rest still shows.
 */
public final class DbmlParser {
    private DbmlParser() {}

    public enum Kind { WORD, QUOTED, STRING, EXPR, NUMBER, COLOR, SYMBOL, NEWLINE }

    public record Token(Kind kind, String text, int line) {}

    public static final Set<String> KEYWORDS = Set.of("table", "ref", "enum", "note", "project", "tablegroup", "indexes",
            "as", "tablepartial", "records");

    private static final Token NO_TOKEN = null;

    // MARK: Tokens

    /** Splits DBML into tokens (comments dropped; newlines kept, since a table has a column per line). */
    public static List<Token> tokens(String source) {
        int[] cs = source.codePoints().toArray();
        int n = cs.length;
        List<Token> result = new ArrayList<>();
        int i = 0, line = 1;
        while (i < n) {
            int c = cs[i];
            if (c == '\n') {
                result.add(new Token(Kind.NEWLINE, "\n", line));
                line++;
                i++;
            } else if (isWhitespace(c)) {
                i++;
            } else if (c == '/' && at(cs, i + 1) == '/') {
                while (i < n && cs[i] != '\n') i++;
            } else if (c == '/' && at(cs, i + 1) == '*') {
                i += 2;
                while (i < n && !(cs[i] == '*' && at(cs, i + 1) == '/')) {
                    if (cs[i] == '\n') line++;
                    i++;
                }
                i += 2;
            } else if (c == '\'') {
                int start = line;
                if (at(cs, i + 1) == '\'' && at(cs, i + 2) == '\'') {
                    // '''multi-line''': its lines lose the indent they share.
                    i += 3;
                    StringBuilder s = new StringBuilder();
                    while (i < n && !(cs[i] == '\'' && at(cs, i + 1) == '\'' && at(cs, i + 2) == '\'')) {
                        if (cs[i] == '\n') line++;
                        if (!(cs[i] == '\r' && at(cs, i + 1) == '\n')) s.appendCodePoint(cs[i]);
                        i++;
                    }
                    i += 3;
                    result.add(new Token(Kind.STRING, dedent(s.toString()), start));
                } else {
                    i++;
                    StringBuilder s = new StringBuilder();
                    while (i < n && cs[i] != '\'' && cs[i] != '\n') {
                        if (cs[i] == '\\' && i + 1 < n) {
                            int next = cs[i + 1];
                            s.appendCodePoint(next == 'n' ? '\n' : next);
                            i += 2;
                            continue;
                        }
                        s.appendCodePoint(cs[i]);
                        i++;
                    }
                    i++;
                    result.add(new Token(Kind.STRING, s.toString(), start));
                }
            } else if (c == '"') {
                i++;
                StringBuilder s = new StringBuilder();
                while (i < n && cs[i] != '"' && cs[i] != '\n') {
                    s.appendCodePoint(cs[i]);
                    i++;
                }
                i++;
                result.add(new Token(Kind.QUOTED, s.toString(), line));
            } else if (c == '`') {
                i++;
                StringBuilder s = new StringBuilder();
                while (i < n && cs[i] != '`') {
                    s.appendCodePoint(cs[i]);
                    i++;
                }
                i++;
                result.add(new Token(Kind.EXPR, s.toString(), line));
            } else if (c == '#') {
                StringBuilder s = new StringBuilder("#");
                i++;
                while (i < n && isHexDigit(cs[i])) {
                    s.appendCodePoint(cs[i]);
                    i++;
                }
                result.add(new Token(Kind.COLOR, s.toString(), line));
            } else if (isNumber(c) || (c == '-' && i + 1 < n && isNumber(cs[i + 1]) && !result.isEmpty()
                    && result.get(result.size() - 1).kind() == Kind.SYMBOL && result.get(result.size() - 1).text().equals(":"))) {
                StringBuilder s = new StringBuilder().appendCodePoint(c);
                i++;
                while (i < n && (isNumber(cs[i]) || cs[i] == '.')) {
                    s.appendCodePoint(cs[i]);
                    i++;
                }
                result.add(new Token(Kind.NUMBER, s.toString(), line));
            } else if (isLetter(c) || c == '_') {
                StringBuilder s = new StringBuilder();
                while (i < n && (isLetter(cs[i]) || isNumber(cs[i]) || cs[i] == '_')) {
                    s.appendCodePoint(cs[i]);
                    i++;
                }
                result.add(new Token(Kind.WORD, s.toString(), line));
            } else if (c == '<' && at(cs, i + 1) == '>') {
                result.add(new Token(Kind.SYMBOL, "<>", line));
                i += 2;
            } else {
                result.add(new Token(Kind.SYMBOL, new String(Character.toChars(c)), line));
                i++;
            }
        }
        return result;
    }

    private static int at(int[] cs, int k) {
        return k >= 0 && k < cs.length ? cs[k] : -1;
    }

    static boolean isWhitespace(int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    static boolean isNumber(int c) {
        int t = Character.getType(c);
        return t == Character.DECIMAL_DIGIT_NUMBER || t == Character.LETTER_NUMBER || t == Character.OTHER_NUMBER;
    }

    static boolean isLetter(int c) {
        return Character.isAlphabetic(c);
    }

    static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Spaces and tabs (CharacterSet.whitespaces). */
    static boolean isBlank(String s) {
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            if (ch != ' ' && ch != '\t' && Character.getType(ch) != Character.SPACE_SEPARATOR) return false;
        }
        return true;
    }

    private static int leadingIndent(String s) {
        int k = 0;
        while (k < s.length() && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) k++;
        return k;
    }

    private static String dedent(String s) {
        List<String> lines = new ArrayList<>(List.of(s.split("\n", -1)));
        if (!lines.isEmpty() && isBlank(lines.get(0))) lines.remove(0);
        if (!lines.isEmpty() && isBlank(lines.get(lines.size() - 1))) lines.remove(lines.size() - 1);
        int indent = Integer.MAX_VALUE;
        for (String l : lines) if (!isBlank(l)) indent = Math.min(indent, leadingIndent(l));
        if (indent == Integer.MAX_VALUE) indent = 0;
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < lines.size(); k++) {
            String l = lines.get(k);
            if (k > 0) out.append('\n');
            out.append(l.substring(Math.min(indent, leadingIndent(l))));
        }
        return out.toString();
    }

    // MARK: Parsing

    /** The last few DBML texts read (a diagram is laid out again on every move while dragging). */
    private static final Map<String, DbmlSchema> PARSED = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, DbmlSchema> eldest) {
            return size() > 6;
        }
    };

    /** Reads DBML. What it can't read is listed in {@code problems()} (by line); the rest still shows. */
    public static DbmlSchema parse(String source) {
        if (source == null) source = "";
        synchronized (PARSED) {
            DbmlSchema hit = PARSED.get(source);
            if (hit != null) return hit;
        }
        DbmlSchema schema = read(source);
        synchronized (PARSED) {
            PARSED.put(source, schema);
        }
        return schema;
    }

    private static DbmlSchema read(String source) {
        Reader p = new Reader(tokens(source));
        p.run();
        // Aliases and the public schema name the same table.
        Map<String, String> aliases = new HashMap<>();
        for (Table t : p.tables) if (t.alias() != null) aliases.put(t.alias(), t.name());
        List<Ref> refs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Ref r : p.refs) {
            Ref resolved = r.withEnds(resolve(r.from(), aliases), resolve(r.to(), aliases));
            if (ids.add(resolved.id())) refs.add(resolved);
        }
        Map<String, Integer> seen = new HashMap<>();
        List<Problem> problems = p.problems;
        for (int i = 0; i < refs.size(); i++) {
            Ref r = refs.get(i);
            r = r.withEnds(resolve(r.from(), aliases), resolve(r.to(), aliases));
            String key = r.from().text() + " " + r.op() + " " + r.to().text();
            int count = seen.merge(key, 1, Integer::sum);
            if (count > 1) key += " #" + count;
            r = r.withKey(key);
            refs.set(i, r);
            for (End end : List.of(r.from(), r.to())) {
                Table t = p.table(end.table());
                if (t != null) {
                    for (String c : end.columns()) {
                        if (!t.hasColumn(c)) problems.add(new Problem(r.line(), "Table “" + t.name() + "” has no column “" + c + "”"));
                    }
                } else {
                    problems.add(new Problem(r.line(), "No table “" + end.table() + "” (in Ref " + r.from().text() + " " + r.op() + " " + r.to().text() + ")"));
                }
            }
        }
        List<Group> groups = new ArrayList<>();
        for (Group g : p.groups) {
            List<String> tables = new ArrayList<>();
            for (String t : g.tables()) tables.add(aliases.containsKey(t) ? aliases.get(t) : t.startsWith("public.") ? t.substring(7) : t);
            groups.add(new Group(g.name(), List.copyOf(tables), g.color(), g.line(), g.endLine()));
        }
        return new DbmlSchema(p.project, p.projectLines, p.tables, p.enums, refs, groups, p.notes, problems);
    }

    private static End resolve(End end, Map<String, String> aliases) {
        End e = end;
        String real = aliases.get(e.table());
        if (real != null) e = e.withTable(real);
        if (e.table().startsWith("public.")) e = e.withTable(e.table().substring(7));
        return e;
    }

    /** A setting in [ … ]: its name (lowercased words) and the tokens of its value. */
    record Setting(String name, List<Token> value) {
        String first() { return value.isEmpty() ? null : value.get(0).text(); }
    }

    private static final class Reader {
        final List<Token> tokens;
        int i = 0;
        String project;
        LineRange projectLines;
        final List<Table> tables = new ArrayList<>();
        final List<EnumType> enums = new ArrayList<>();
        final List<Ref> refs = new ArrayList<>();
        final List<Group> groups = new ArrayList<>();
        final List<StickyNote> notes = new ArrayList<>();
        final List<Problem> problems = new ArrayList<>();

        Reader(List<Token> tokens) {
            this.tokens = tokens;
        }

        Table table(String name) {
            for (Table t : tables) if (t.name().equals(name)) return t;
            return null;
        }

        Token current() { return i < tokens.size() ? tokens.get(i) : NO_TOKEN; }

        /** The line of the current token (the last one at the end). */
        int line() {
            Token t = current();
            return t != null ? t.line() : tokens.isEmpty() ? 1 : tokens.get(tokens.size() - 1).line();
        }

        /** The line of the last token read (a statement's closing brace). */
        int lastLine() { return i > 0 && i - 1 < tokens.size() ? tokens.get(i - 1).line() : line(); }

        boolean isSymbol(String s) { return isSymbol(s, 0); }

        boolean isSymbol(String s, int offset) {
            int k = i + offset;
            return k < tokens.size() && tokens.get(k).kind() == Kind.SYMBOL && tokens.get(k).text().equals(s);
        }

        boolean isWord(String w) {
            Token t = current();
            return t != null && t.kind() == Kind.WORD && t.text().toLowerCase().equals(w);
        }

        static boolean isSym(Token t, String s) { return t != null && t.kind() == Kind.SYMBOL && t.text().equals(s); }

        static boolean isNameToken(Token t) { return t != null && (t.kind() == Kind.WORD || t.kind() == Kind.QUOTED); }

        void skipNewlines() { while (current() != null && current().kind() == Kind.NEWLINE) i++; }

        void skipLine() { while (current() != null && current().kind() != Kind.NEWLINE) i++; }

        void problem(String message) { problems.add(new Problem(line(), message)); }

        /** A name: words or "quoted" parts joined by dots (schema.table). */
        String name() {
            List<String> parts = new ArrayList<>();
            while (isNameToken(current())) {
                parts.add(current().text());
                i++;
                if (isSymbol(".") && i + 1 < tokens.size() && isNameToken(tokens.get(i + 1))) i++;
                else break;
            }
            return parts.isEmpty() ? null : String.join(".", parts);
        }

        /** Skips a { … } block (nested blocks too). */
        void skipBlock() {
            if (!isSymbol("{")) return;
            int depth = 0;
            while (current() != null) {
                Token t = current();
                i++;
                if (isSym(t, "{")) depth++;
                if (isSym(t, "}")) {
                    depth--;
                    if (depth == 0) return;
                }
            }
        }

        /** The settings in [ … ], as (name, value) pairs: pk, not null, note: '…', ref: > a.b… */
        List<Setting> settings() {
            if (!isSymbol("[")) return List.of();
            i++;
            List<Setting> result = new ArrayList<>();
            List<String> name = new ArrayList<>();
            List<Token> value = new ArrayList<>();
            boolean inValue = false;
            int depth = 0;
            while (current() != null) {
                Token t = current();
                if (isSym(t, "]") && depth == 0) {
                    i++;
                    break;
                }
                if (t.kind() == Kind.NEWLINE) {
                    i++;
                    continue;
                }
                if (t.kind() == Kind.SYMBOL && (t.text().equals("(") || t.text().equals("["))) depth++;
                if (t.kind() == Kind.SYMBOL && (t.text().equals(")") || (t.text().equals("]") && depth > 0))) depth--;
                if (isSym(t, ",") && depth == 0) {
                    flush(result, name, value);
                    inValue = false;
                    i++;
                    continue;
                }
                if (!inValue && isSym(t, ":")) {
                    inValue = true;
                    i++;
                    continue;
                }
                if (inValue) value.add(t);
                else if (t.kind() == Kind.WORD) name.add(t.text());
                else value.add(t);
                i++;
            }
            flush(result, name, value);
            return result;
        }

        private static void flush(List<Setting> result, List<String> name, List<Token> value) {
            String n = String.join(" ", name).toLowerCase();
            if (!n.isEmpty() || !value.isEmpty()) result.add(new Setting(n, List.copyOf(value)));
            name.clear();
            value.clear();
        }

        static String text(List<Token> value) {
            StringBuilder s = new StringBuilder();
            for (Token t : value) {
                String x = t.text();
                if (t.kind() == Kind.SYMBOL && (x.equals("(") || x.equals(")") || x.equals(",") || x.equals("."))) {
                    s.append(x);
                } else {
                    String sofar = s.toString();
                    boolean tight = sofar.isEmpty() || sofar.endsWith("(") || sofar.endsWith(".") || sofar.endsWith(",");
                    s.append(tight ? "" : " ").append(x);
                }
            }
            return s.toString();
        }

        /** One end of a relationship: table.column, schema.table.column or table.(a, b). */
        End end() {
            List<String> parts = new ArrayList<>();
            while (isNameToken(current())) {
                parts.add(current().text());
                i++;
                if (!isSymbol(".")) break;
                i++;
                if (isSymbol("(")) {
                    i++;
                    List<String> columns = new ArrayList<>();
                    while (current() != null && !isSym(current(), ")")) {
                        Token c = current();
                        if (isNameToken(c)) columns.add(c.text());
                        if (c.kind() == Kind.NEWLINE) break;
                        i++;
                    }
                    if (isSymbol(")")) i++;
                    return parts.isEmpty() ? null : new End(String.join(".", parts), List.copyOf(columns));
                }
            }
            if (parts.size() < 2) return null;
            return new End(String.join(".", parts.subList(0, parts.size() - 1)), List.of(parts.get(parts.size() - 1)));
        }

        static boolean isOp(Token t) {
            return t != null && t.kind() == Kind.SYMBOL
                    && (t.text().equals("<") || t.text().equals(">") || t.text().equals("-") || t.text().equals("<>"));
        }

        void refBody(String name, int line) {
            End from = end();
            if (from == null) {
                problem("A Ref needs table.column on each side (users.id < posts.user_id)");
                skipLine();
                return;
            }
            Token op = current();
            if (!isOp(op)) {
                problem("A Ref needs <, >, - or <> between its ends");
                skipLine();
                return;
            }
            i++;
            End to = end();
            if (to == null) {
                problem("A Ref needs table.column on each side");
                skipLine();
                return;
            }
            List<Setting> s = settings();
            List<String> written = new ArrayList<>();
            for (Setting x : s) written.add(x.name() + (x.value().isEmpty() ? "" : ": " + text(x.value())));
            String w = String.join(", ", written);
            refs.add(new Ref(name, from, to, op.text(), line, false, w.isEmpty() ? null : "[" + w + "]", ""));
        }

        void run() {
            while (current() != null) {
                skipNewlines();
                Token t = current();
                if (t == null) break;
                int startLine = t.line();
                if (t.kind() != Kind.WORD) {
                    problem("Unexpected “" + t.text() + "”");
                    i++;
                    skipLine();
                    continue;
                }
                switch (t.text().toLowerCase()) {
                    case "table" -> {
                        i++;
                        table(startLine);
                    }
                    case "enum" -> {
                        i++;
                        enumeration(startLine);
                    }
                    case "ref" -> {
                        i++;
                        String refName = isSymbol(":") || isSymbol("{") ? null : name();
                        if (isSymbol(":")) {
                            i++;
                            refBody(refName, startLine);
                        } else if (isSymbol("{")) {
                            i++;
                            while (current() != null && !isSymbol("}")) {
                                skipNewlines();
                                if (isSymbol("}")) break;
                                refBody(refName, line());
                                skipNewlines();
                            }
                            if (isSymbol("}")) i++;
                        } else {
                            problem("A Ref is written Ref: a.b > c.d (or Ref name { … })");
                            skipLine();
                        }
                    }
                    case "tablegroup" -> {
                        i++;
                        group(startLine);
                    }
                    case "note" -> {
                        i++;
                        stickyNote(startLine);
                    }
                    case "project" -> {
                        i++;
                        project = name();
                        settings();
                        skipNewlines();
                        skipBlock();
                        projectLines = new LineRange(startLine, Math.max(startLine, lastLine()));
                    }
                    case "tablepartial", "records" -> {
                        i++;
                        name();
                        while (current() != null && current().kind() != Kind.NEWLINE && !isSym(current(), "{")) i++;
                        skipBlock();
                    }
                    default -> {
                        problem("Unknown “" + t.text() + "” (expected Table, Enum, Ref, TableGroup, Note or Project)");
                        i++;
                        skipLine();
                    }
                }
            }
        }

        /** A table being read. */
        private static final class TableDraft {
            String name, alias, note, headerColor;
            final List<Column> columns = new ArrayList<>();
            int indexCount, line, endLine;

            int columnIndex(String c) {
                for (int k = 0; k < columns.size(); k++) if (columns.get(k).name().equals(c)) return k;
                return -1;
            }
        }

        void table(int line) {
            String tableName = name();
            if (tableName == null) {
                problem("A Table needs a name");
                skipLine();
                return;
            }
            if (tableName.startsWith("public.")) tableName = tableName.substring(7);
            TableDraft table = new TableDraft();
            table.name = tableName;
            table.line = line;
            if (isWord("as")) {
                i++;
                table.alias = name();
            }
            for (Setting s : settings()) {
                if (s.name().equals("headercolor")) table.headerColor = s.first();
                if (s.name().equals("note")) table.note = s.first();
            }
            skipNewlines();
            if (!isSymbol("{")) {
                problem("Table " + tableName + " needs { … }");
                skipLine();
                return;
            }
            i++;
            while (current() != null && !isSym(current(), "}")) {
                Token t = current();
                if (t.kind() == Kind.NEWLINE) {
                    i++;
                    continue;
                }
                if (t.kind() == Kind.WORD && t.text().toLowerCase().equals("note")) {
                    i++;
                    if (isSymbol(":")) {
                        i++;
                        table.note = current() != null ? current().text() : null;
                        skipLine();
                    } else if (isSymbol("{")) {
                        i++;
                        skipNewlines();
                        table.note = current() != null ? current().text() : null;
                        while (current() != null && !isSymbol("}")) i++;
                        if (isSymbol("}")) i++;
                    }
                    continue;
                }
                if (t.kind() == Kind.WORD && t.text().toLowerCase().equals("indexes")) {
                    i++;
                    skipNewlines();
                    indexes(table);
                    continue;
                }
                if (isSym(t, "~")) {
                    // A table partial (~name) brings in columns written elsewhere.
                    skipLine();
                    continue;
                }
                column(table);
            }
            if (isSymbol("}")) i++;
            else problem("Table " + tableName + " is missing its closing }");
            table.endLine = lastLine();
            if (table(table.name) != null) problems.add(new Problem(line, "Table “" + table.name + "” is defined twice"));
            tables.add(new Table(table.name, table.alias, List.copyOf(table.columns), table.note, table.headerColor,
                    table.indexCount, table.line, table.endLine));
        }

        void column(TableDraft table) {
            int columnLine = line();
            Token t = current();
            if (!isNameToken(t)) {
                problem("Expected a column name in table " + table.name);
                skipLine();
                return;
            }
            i++;
            List<Token> type = new ArrayList<>();
            while (current() != null) {
                Token c = current();
                if (c.kind() == Kind.NEWLINE) break;
                if (c.kind() == Kind.SYMBOL && (c.text().equals("}") || (c.text().equals("[") && !isSymbol("]", 1)))) break;
                if (isSym(c, "[")) {
                    type.add(c);
                    i++;
                    type.add(current());
                    i++;
                    continue;
                }
                type.add(c);
                i++;
            }
            String typeText = text(type).replace(" [ ]", "[]").replace(" []", "[]");
            String name = t.text();
            boolean pk = false, notNull = false, unique = false, increment = false;
            String note = null, defaultValue = null;
            if (typeText.isEmpty()) problems.add(new Problem(columnLine, "Column " + table.name + "." + name + " needs a type"));
            for (Setting s : settings()) {
                switch (s.name()) {
                    case "pk", "primary key" -> pk = true;
                    case "not null" -> notNull = true;
                    case "unique" -> unique = true;
                    case "increment" -> increment = true;
                    case "note" -> note = s.first();
                    case "default" -> defaultValue = text(s.value());
                    case "ref" -> {
                        List<Token> sub = new ArrayList<>(s.value());
                        sub.add(new Token(Kind.NEWLINE, "\n", columnLine));
                        Reader r = new Reader(sub);
                        Token op = r.current();
                        if (isOp(op)) {
                            r.i++;
                            End to = r.end();
                            if (to != null) {
                                refs.add(new Ref(null, new End(table.name, List.of(name)), to, op.text(), columnLine, true, null, ""));
                                continue;
                            }
                        }
                        problems.add(new Problem(columnLine, "An inline ref is written [ref: > table.column]"));
                    }
                    default -> { }
                }
            }
            table.columns.add(new Column(name, typeText, pk, notNull, unique, increment, defaultValue, note));
            Token c = current();
            if (c != null && c.kind() != Kind.NEWLINE && !isSym(c, "}")) skipLine();
        }

        /** indexes { … }: a primary key on a column (or columns) marks them. */
        void indexes(TableDraft table) {
            if (!isSymbol("{")) return;
            i++;
            while (current() != null && !isSym(current(), "}")) {
                Token t = current();
                if (t.kind() == Kind.NEWLINE) {
                    i++;
                    continue;
                }
                List<String> columns = new ArrayList<>();
                if (isSymbol("(")) {
                    i++;
                    while (current() != null && !isSym(current(), ")") && current().kind() != Kind.NEWLINE) {
                        Token c = current();
                        if (isNameToken(c)) columns.add(c.text());
                        i++;
                    }
                    if (isSymbol(")")) i++;
                } else if (isNameToken(t)) {
                    columns.add(t.text());
                    i++;
                } else {
                    i++;
                }
                List<Setting> s = settings();
                if (!columns.isEmpty()) table.indexCount++;
                if (s.stream().anyMatch(x -> x.name().equals("pk") || x.name().equals("primary key"))) {
                    for (String c : columns) {
                        int k = table.columnIndex(c);
                        if (k >= 0) table.columns.set(k, table.columns.get(k).withPk(true));
                    }
                }
                if (s.stream().anyMatch(x -> x.name().equals("unique")) && columns.size() == 1) {
                    int k = table.columnIndex(columns.get(0));
                    if (k >= 0) table.columns.set(k, table.columns.get(k).withUnique(true));
                }
                skipLine();
            }
            if (isSymbol("}")) i++;
        }

        void enumeration(int line) {
            String enumName = name();
            if (enumName == null) {
                problem("An Enum needs a name");
                skipLine();
                return;
            }
            String name = enumName.startsWith("public.") ? enumName.substring(7) : enumName;
            List<EnumValue> values = new ArrayList<>();
            skipNewlines();
            if (!isSymbol("{")) {
                problem("Enum " + enumName + " needs { … }");
                skipLine();
                return;
            }
            i++;
            while (current() != null && !isSym(current(), "}")) {
                Token t = current();
                if (t.kind() == Kind.NEWLINE) {
                    i++;
                    continue;
                }
                if (t.kind() == Kind.WORD || t.kind() == Kind.QUOTED || t.kind() == Kind.NUMBER) {
                    i++;
                    String note = null;
                    for (Setting s : settings()) {
                        if (s.name().equals("note")) {
                            note = s.first();
                            break;
                        }
                    }
                    values.add(new EnumValue(t.text(), note));
                } else {
                    i++;
                }
            }
            if (isSymbol("}")) i++;
            enums.add(new EnumType(name, List.copyOf(values), line, lastLine()));
        }

        void group(int line) {
            String groupName = name();
            if (groupName == null) {
                problem("A TableGroup needs a name");
                skipLine();
                return;
            }
            List<String> members = new ArrayList<>();
            String color = null;
            for (Setting s : settings()) if (s.name().equals("color")) color = s.first();
            skipNewlines();
            if (!isSymbol("{")) {
                problem("TableGroup " + groupName + " needs { … }");
                skipLine();
                return;
            }
            i++;
            while (current() != null && !isSym(current(), "}")) {
                Token t = current();
                if (t.kind() == Kind.NEWLINE) {
                    i++;
                    continue;
                }
                if (t.kind() == Kind.WORD && t.text().toLowerCase().equals("note")) {
                    skipLine();
                    continue;
                }
                String n = name();
                if (n != null) members.add(n);
                else i++;
            }
            if (isSymbol("}")) i++;
            groups.add(new Group(groupName, List.copyOf(members), color, line, lastLine()));
        }

        void stickyNote(int line) {
            String noteName = name();
            if (noteName == null) {
                problem("A sticky note is written Note name { '…' }");
                skipLine();
                return;
            }
            skipNewlines();
            if (!isSymbol("{")) {
                problem("Note " + noteName + " needs { '…' }");
                skipLine();
                return;
            }
            i++;
            skipNewlines();
            String text = current() != null && current().kind() == Kind.STRING ? current().text() : "";
            while (current() != null && !isSymbol("}")) i++;
            if (isSymbol("}")) i++;
            notes.add(new StickyNote(noteName, text, line, lastLine()));
        }
    }
}
