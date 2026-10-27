package com.k3jdr.mxnoteone.table;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Excel-style formulas in table cells (TableFormulas.swift): a cell whose text starts with "=" shows the result.
 * References use column letters and row numbers (A1, $B$2, B2:B9), with + - * / ^ &amp; % and comparisons, and
 * Excel's common functions. Errors show as Excel shows them (#DIV/0!, #REF!, #NAME?, #VALUE!, #NUM!), loops as
 * #CIRC!, unreadable formulas as #ERROR!. Dates are Excel serial day numbers.
 */
public final class Formula {
    private Formula() {}

    /** A formula's value. */
    public static final class V {
        public enum Kind { NUMBER, DATE, TEXT, BOOL, ERROR, EMPTY, RANGE }

        public final Kind kind;
        public final double num;
        public final String text;
        public final List<V> range;

        private V(Kind k, double n, String t, List<V> r) {
            kind = k;
            num = n;
            text = t;
            range = r;
        }

        public static V number(double d) { return new V(Kind.NUMBER, d, null, null); }
        public static V date(double d) { return new V(Kind.DATE, d, null, null); }
        public static V text(String s) { return new V(Kind.TEXT, 0, s, null); }
        public static V bool(boolean b) { return new V(Kind.BOOL, b ? 1 : 0, null, null); }
        public static V error(String e) { return new V(Kind.ERROR, 0, e, null); }
        public static final V EMPTY = new V(Kind.EMPTY, 0, null, null);
        public static V range(List<V> r) { return new V(Kind.RANGE, 0, null, r); }

        public boolean isError() { return kind == Kind.ERROR; }

        /** As shown in the cell. */
        public String display() {
            return switch (kind) {
                case NUMBER -> format(num);
                case DATE -> generalDate(num);
                case TEXT -> text;
                case BOOL -> num != 0 ? "TRUE" : "FALSE";
                case ERROR -> text;
                case EMPTY -> "";
                case RANGE -> range.isEmpty() ? "" : range.get(0).display();
            };
        }

        boolean same(V o) {
            if (kind != o.kind) return false;
            return switch (kind) {
                case TEXT, ERROR -> text.equals(o.text);
                case EMPTY -> true;
                default -> num == o.num;
            };
        }
    }

    public static boolean isFormula(String text) { return text != null && text.startsWith("=") && text.length() > 1; }

    /** Plain cell text as a value: numbers (1,234.5  $12  12%  (5)), dates, TRUE / FALSE, text, or empty. */
    public static V valueOfText(String raw) {
        String text = raw.strip();
        if (text.isEmpty()) return V.EMPTY;
        Double n = number(text);
        if (n != null) return V.number(n);
        Double d = dateSerial(text);
        if (d != null) return V.date(d);
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "TRUE" -> V.bool(true);
            case "FALSE" -> V.bool(false);
            default -> V.text(raw);
        };
    }

    private static final Pattern NUMBER = Pattern.compile("^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$");

    public static Double number(String text) {
        String s = text.strip();
        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")") && s.length() > 2) {
            negative = true;
            s = s.substring(1, s.length() - 1);
        }
        boolean percent = false;
        if (s.endsWith("%")) {
            percent = true;
            s = s.substring(0, s.length() - 1);
        }
        s = s.replace(",", "");
        for (String sym : new String[] { "$", "€", "£", "¥" }) if (s.startsWith(sym) || s.startsWith("-" + sym)) s = s.replace(sym, "");
        s = s.strip();
        if (s.isEmpty() || !NUMBER.matcher(s).matches()) return null;
        try {
            double d = Double.parseDouble(s);
            if (percent) d /= 100;
            return negative ? -d : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A number or a date typed in a cell, as a number. */
    public static Double numeric(String text) {
        Double n = number(text);
        return n != null ? n : dateSerial(text);
    }

    // MARK: Dates

    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);
    private static final String[] DATE_PATTERNS = { "yyyy-MM-dd", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "M/d/yyyy", "M/d/yyyy h:mm a",
            "M/d/yyyy H:mm", "M/d/yy", "d-MMM-yyyy", "d-MMM-yy", "d MMM yyyy", "MMM d, yyyy", "MMMM d, yyyy", "EEEE, MMMM d, yyyy",
            "EEE, MMM d, yyyy", "MMMM yyyy", "MMM yyyy", "h:mm a", "H:mm" };
    private static final List<DateTimeFormatter> PARSERS = new ArrayList<>();

    static {
        for (String p : DATE_PATTERNS) {
            PARSERS.add(new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p)
                    .parseDefaulting(ChronoField.DAY_OF_MONTH, 1).toFormatter(Locale.US).withResolverStyle(ResolverStyle.SMART));
        }
    }

    public static Double dateSerial(String text) {
        String s = text.strip();
        if (s.length() < 4 || s.length() > 40 || !s.chars().anyMatch(Character::isDigit)) return null;
        if (!s.chars().anyMatch(c -> "/-:, ".indexOf(c) >= 0 || Character.isLetter(c))) return null;
        for (int i = 0; i < PARSERS.size(); i++) {
            try {
                TemporalAccessor t = PARSERS.get(i).parse(s);
                boolean timeOnly = DATE_PATTERNS[i].startsWith("h:") || DATE_PATTERNS[i].startsWith("H:");
                LocalTime time = t.isSupported(ChronoField.HOUR_OF_DAY) ? LocalTime.of(t.get(ChronoField.HOUR_OF_DAY), t.get(ChronoField.MINUTE_OF_HOUR)) : LocalTime.MIDNIGHT;
                double frac = time.toSecondOfDay() / 86400.0;
                if (timeOnly) return frac;
                int year = t.get(ChronoField.YEAR);
                LocalDate d = LocalDate.of(year, t.get(ChronoField.MONTH_OF_YEAR), t.isSupported(ChronoField.DAY_OF_MONTH) ? t.get(ChronoField.DAY_OF_MONTH) : 1);
                return serial(d) + frac;
            } catch (RuntimeException e) {
                // try the next pattern
            }
        }
        return null;
    }

    public static double serial(LocalDate d) { return ChronoUnit.DAYS.between(EPOCH, d); }

    public static double serial(LocalDateTime t) {
        return serial(t.toLocalDate()) + t.toLocalTime().toSecondOfDay() / 86400.0;
    }

    public static LocalDateTime dateTime(double serial) {
        long days = (long) Math.floor(serial);
        long seconds = Math.round((serial - days) * 86400);
        return EPOCH.plusDays(days).atStartOfDay().plusSeconds(seconds);
    }

    public static String generalDate(double serial) {
        boolean hasTime = Math.abs(serial - Math.floor(serial)) > 1e-9;
        return formatDate(serial, hasTime ? (serial < 1 ? "h:mm a" : "M/d/yyyy h:mm a") : "M/d/yyyy");
    }

    public static String formatDate(double serial, String pattern) {
        return dateTime(serial).format(DateTimeFormatter.ofPattern(pattern, Locale.US));
    }

    /** Numbers as Excel's General format shows them. */
    public static String format(double d) {
        if (!Double.isFinite(d)) return "#NUM!";
        if (d == 0) return "0";
        if (Math.abs(d) >= 1e15 || Math.abs(d) < 1e-9) return String.format(Locale.ROOT, "%.6G", d);
        String s = String.format(Locale.ROOT, "%.10f", d);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.equals("-0") ? "0" : s;
    }

    // MARK: References

    public record Ref(int row, int column, boolean absRow, boolean absColumn) {
        public String text() { return (absColumn ? "$" : "") + columnName(column) + (absRow ? "$" : "") + (row + 1); }
    }

    public static String columnName(int c) {
        StringBuilder sb = new StringBuilder();
        int n = c + 1;
        while (n > 0) {
            int m = (n - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static final Pattern REF = Pattern.compile("^(\\$?)([A-Za-z]{1,3})(\\$?)([0-9]+)$");

    public static Ref parseRef(String s) {
        Matcher m = REF.matcher(s);
        if (!m.matches()) return null;
        int row;
        try {
            row = Integer.parseInt(m.group(4));
        } catch (NumberFormatException e) {
            return null;
        }
        if (row < 1) return null;
        int column = 0;
        for (char ch : m.group(2).toUpperCase(Locale.ROOT).toCharArray()) column = column * 26 + (ch - 'A' + 1);
        return new Ref(row - 1, column - 1, !m.group(3).isEmpty(), !m.group(1).isEmpty());
    }

    // MARK: Tokens

    enum T { NUMBER, TEXT, REF, NAME, OP, LPAREN, RPAREN, COMMA, COLON }

    record Token(T type, String text, double num, int start, int end) {}

    static List<Token> tokenize(String f) {
        List<Token> out = new ArrayList<>();
        int i = 0, n = f.length();
        while (i < n) {
            char c = f.charAt(i);
            int start = i;
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"') {
                StringBuilder s = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    if (f.charAt(i) == '"') {
                        if (i + 1 < n && f.charAt(i + 1) == '"') {
                            s.append('"');
                            i += 2;
                            continue;
                        }
                        i++;
                        closed = true;
                        break;
                    }
                    s.append(f.charAt(i++));
                }
                if (!closed) return null;
                out.add(new Token(T.TEXT, s.toString(), 0, start, i));
                continue;
            }
            if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(f.charAt(i + 1))) {
                while (i < n && (Character.isDigit(f.charAt(i)) || f.charAt(i) == '.')) i++;
                if (i < n && (f.charAt(i) == 'e' || f.charAt(i) == 'E')) {
                    int save = i;
                    i++;
                    if (i < n && (f.charAt(i) == '+' || f.charAt(i) == '-')) i++;
                    if (i < n && Character.isDigit(f.charAt(i))) {
                        while (i < n && Character.isDigit(f.charAt(i))) i++;
                    } else {
                        i = save;
                    }
                }
                try {
                    out.add(new Token(T.NUMBER, f.substring(start, i), Double.parseDouble(f.substring(start, i)), start, i));
                } catch (NumberFormatException e) {
                    return null;
                }
                continue;
            }
            if (Character.isLetter(c) || c == '$' || c == '_') {
                i++;
                while (i < n && (Character.isLetterOrDigit(f.charAt(i)) || "$_.".indexOf(f.charAt(i)) >= 0)) i++;
                String word = f.substring(start, i);
                boolean isRef = parseRef(word) != null;
                String name = word.toUpperCase(Locale.ROOT);
                out.add(new Token(isRef ? T.REF : T.NAME, isRef ? word : name.equals("REF_ERROR_") ? "#REF!" : name, 0, start, i));
                continue;
            }
            i++;
            switch (c) {
                case '(' -> out.add(new Token(T.LPAREN, "(", 0, start, i));
                case ')' -> out.add(new Token(T.RPAREN, ")", 0, start, i));
                case ',', ';' -> out.add(new Token(T.COMMA, ",", 0, start, i));
                case ':' -> out.add(new Token(T.COLON, ":", 0, start, i));
                case '<', '>' -> {
                    if (i < n && (f.charAt(i) == '=' || c == '<' && f.charAt(i) == '>')) i++;
                    out.add(new Token(T.OP, f.substring(start, i), 0, start, i));
                }
                case '+', '-', '*', '/', '^', '&', '=', '%' -> out.add(new Token(T.OP, String.valueOf(c), 0, start, i));
                default -> {
                    return null;
                }
            }
        }
        return out;
    }

    // MARK: Parsing

    sealed interface Node permits Num, Str, Bool, RefN, RangeN, Unary, Percent, Binary, Call, Err {}
    record Num(double d) implements Node {}
    record Str(String s) implements Node {}
    record Bool(boolean b) implements Node {}
    record RefN(Ref r) implements Node {}
    record RangeN(Ref a, Ref b) implements Node {}
    record Unary(Node n) implements Node {}
    record Percent(Node n) implements Node {}
    record Binary(String op, Node l, Node r) implements Node {}
    record Call(String name, List<Node> args) implements Node {}
    record Err(String e) implements Node {}

    private static final class Parser {
        final List<Token> t;
        int i;

        Parser(List<Token> t) { this.t = t; }

        Node parse() {
            Node n = comparison();
            return n != null && i == t.size() ? n : null;
        }

        Token next() { return i < t.size() ? t.get(i) : null; }

        boolean take(T type, String text) {
            Token k = next();
            if (k != null && k.type == type && (text == null || k.text.equals(text))) {
                i++;
                return true;
            }
            return false;
        }

        boolean isOp(String... ops) {
            Token k = next();
            if (k == null || k.type != T.OP) return false;
            for (String o : ops) if (o.equals(k.text)) return true;
            return false;
        }

        Node comparison() {
            Node left = concat();
            while (left != null && isOp("=", "<>", "<", ">", "<=", ">=")) {
                String o = t.get(i++).text;
                Node right = concat();
                if (right == null) return null;
                left = new Binary(o, left, right);
            }
            return left;
        }

        Node concat() {
            Node left = additive();
            while (left != null && take(T.OP, "&")) {
                Node right = additive();
                if (right == null) return null;
                left = new Binary("&", left, right);
            }
            return left;
        }

        Node additive() {
            Node left = multiplicative();
            while (left != null && isOp("+", "-")) {
                String o = t.get(i++).text;
                Node right = multiplicative();
                if (right == null) return null;
                left = new Binary(o, left, right);
            }
            return left;
        }

        Node multiplicative() {
            Node left = power();
            while (left != null && isOp("*", "/")) {
                String o = t.get(i++).text;
                Node right = power();
                if (right == null) return null;
                left = new Binary(o, left, right);
            }
            return left;
        }

        Node power() {
            Node left = unary();
            while (left != null && take(T.OP, "^")) {
                Node right = unary();
                if (right == null) return null;
                left = new Binary("^", left, right);
            }
            return left;
        }

        Node unary() {
            if (isOp("-", "+")) {
                String o = t.get(i++).text;
                Node operand = unary();
                if (operand == null) return null;
                return o.equals("-") ? new Unary(operand) : operand;
            }
            Node node = primary();
            while (node != null && take(T.OP, "%")) node = new Percent(node);
            return node;
        }

        Node primary() {
            Token k = next();
            if (k == null) return null;
            i++;
            switch (k.type) {
                case NUMBER -> {
                    return new Num(k.num);
                }
                case TEXT -> {
                    return new Str(k.text);
                }
                case REF -> {
                    Ref a = parseRef(k.text);
                    if (take(T.COLON, null)) {
                        Token e = next();
                        if (e == null || e.type != T.REF) return null;
                        i++;
                        return new RangeN(a, parseRef(e.text));
                    }
                    return new RefN(a);
                }
                case NAME -> {
                    if (take(T.LPAREN, null)) {
                        List<Node> args = new ArrayList<>();
                        if (take(T.RPAREN, null)) return new Call(k.text, args);
                        while (true) {
                            Node a = comparison();
                            if (a == null) return null;
                            args.add(a);
                            if (take(T.COMMA, null)) continue;
                            if (!take(T.RPAREN, null)) return null;
                            return new Call(k.text, args);
                        }
                    }
                    return switch (k.text) {
                        case "TRUE" -> new Bool(true);
                        case "FALSE" -> new Bool(false);
                        case "#REF!" -> new Err("#REF!");
                        default -> new Err("#NAME?");
                    };
                }
                case LPAREN -> {
                    Node inner = comparison();
                    return inner != null && take(T.RPAREN, null) ? inner : null;
                }
                default -> {
                    return null;
                }
            }
        }
    }

    static Node parse(String formula) {
        String body = formula.startsWith("=") ? formula.substring(1) : formula;
        // A reference made invalid by a deleted row or column.
        List<Token> tokens = tokenize(body.replace("#REF!", "REF_ERROR_"));
        return tokens == null ? null : new Parser(tokens).parse();
    }

    // MARK: Evaluating

    /** Evaluates a formula; {@code cell} gives a referenced cell's value (null when it's outside the table). */
    public static V evaluate(String formula, Function<Ref, V> cell) {
        Node node = parse(formula);
        if (node == null) return V.error("#ERROR!");
        V result = new Eval(cell).eval(node);
        if (result.kind == V.Kind.RANGE) return result.range.size() == 1 ? scalar(result.range.get(0)) : V.error("#VALUE!");
        return scalar(result);
    }

    private static V scalar(V v) { return v.kind == V.Kind.EMPTY ? V.number(0) : v; }

    private static final class Eval {
        final Function<Ref, V> cell;

        Eval(Function<Ref, V> cell) { this.cell = cell; }

        V eval(Node node) {
            if (node instanceof Num n) return V.number(n.d);
            if (node instanceof Str s) return V.text(s.s);
            if (node instanceof Bool b) return V.bool(b.b);
            if (node instanceof Err e) return V.error(e.e);
            if (node instanceof RefN r) {
                V v = cell.apply(r.r);
                return v == null ? V.error("#REF!") : v;
            }
            if (node instanceof RangeN rg) {
                List<V> values = new ArrayList<>();
                for (int row = Math.min(rg.a.row(), rg.b.row()); row <= Math.max(rg.a.row(), rg.b.row()); row++) {
                    for (int col = Math.min(rg.a.column(), rg.b.column()); col <= Math.max(rg.a.column(), rg.b.column()); col++) {
                        V v = cell.apply(new Ref(row, col, false, false));
                        if (v == null) return V.error("#REF!");
                        values.add(v);
                    }
                }
                return V.range(values);
            }
            if (node instanceof Unary u) {
                V v = single(eval(u.n));
                Double d = num(v);
                return d == null ? (v.isError() ? v : V.error("#VALUE!")) : V.number(-d);
            }
            if (node instanceof Percent p) {
                V v = single(eval(p.n));
                Double d = num(v);
                return d == null ? (v.isError() ? v : V.error("#VALUE!")) : V.number(d / 100);
            }
            if (node instanceof Binary b) return binary(b.op, single(eval(b.l)), single(eval(b.r)));
            if (node instanceof Call c) return call(c.name, c.args);
            return V.error("#ERROR!");
        }

        V single(V v) {
            if (v.kind == V.Kind.RANGE) return v.range.size() == 1 ? v.range.get(0) : V.error("#VALUE!");
            return v;
        }

        Double num(V v) {
            return switch (v.kind) {
                case NUMBER, DATE, BOOL -> v.num;
                case EMPTY -> 0.0;
                case TEXT -> numeric(v.text);
                default -> null;
            };
        }

        String text(V v) { return v.kind == V.Kind.EMPTY ? "" : v.display(); }

        Boolean truth(V v) {
            return switch (v.kind) {
                case BOOL, NUMBER, DATE -> v.num != 0;
                case EMPTY -> false;
                case TEXT -> v.text.equalsIgnoreCase("TRUE") ? Boolean.TRUE : v.text.equalsIgnoreCase("FALSE") ? Boolean.FALSE : null;
                default -> null;
            };
        }

        boolean isDate(V v) {
            if (v.kind == V.Kind.DATE) return true;
            return v.kind == V.Kind.TEXT && number(v.text) == null && dateSerial(v.text) != null;
        }

        V binary(String op, V a, V b) {
            if (a.isError()) return a;
            if (b.isError()) return b;
            switch (op) {
                case "&" -> {
                    return V.text(text(a) + text(b));
                }
                case "=", "<>", "<", ">", "<=", ">=" -> {
                    int o = compare(a, b);
                    return V.bool(switch (op) {
                        case "=" -> o == 0;
                        case "<>" -> o != 0;
                        case "<" -> o < 0;
                        case ">" -> o > 0;
                        case "<=" -> o <= 0;
                        default -> o >= 0;
                    });
                }
                default -> {
                    Double x = num(a), y = num(b);
                    if (x == null || y == null) return V.error("#VALUE!");
                    boolean ad = isDate(a), bd = isDate(b);
                    return switch (op) {
                        case "+" -> ad != bd ? V.date(x + y) : V.number(x + y);
                        case "-" -> ad && !bd ? V.date(x - y) : V.number(x - y);
                        case "*" -> V.number(x * y);
                        case "/" -> y == 0 ? V.error("#DIV/0!") : V.number(x / y);
                        case "^" -> {
                            double p = Math.pow(x, y);
                            yield Double.isFinite(p) ? V.number(p) : V.error("#NUM!");
                        }
                        default -> V.error("#VALUE!");
                    };
                }
            }
        }

        int rank(V v) {
            return switch (v.kind) {
                case NUMBER, DATE, EMPTY -> 0;
                case TEXT -> 1;
                case BOOL -> 2;
                default -> 3;
            };
        }

        int compare(V a, V b) {
            if (a.kind == V.Kind.EMPTY) a = b.kind == V.Kind.TEXT ? V.text("") : V.number(0);
            if (b.kind == V.Kind.EMPTY) b = a.kind == V.Kind.TEXT ? V.text("") : V.number(0);
            if (rank(a) != rank(b)) return Integer.compare(rank(a), rank(b));
            if (rank(a) == 0) return Double.compare(num(a), num(b));
            if (a.kind == V.Kind.BOOL) return Double.compare(a.num, b.num);
            return text(a).compareToIgnoreCase(text(b));
        }

        List<V> flat(List<Node> args) {
            List<V> out = new ArrayList<>();
            for (Node n : args) {
                V v = eval(n);
                if (v.kind == V.Kind.RANGE) out.addAll(v.range);
                else out.add(v);
            }
            return out;
        }

        /** Numbers from the arguments (ranges give only their numbers); an error as its V. */
        Object numbers(List<Node> args) {
            List<Double> out = new ArrayList<>();
            for (Node n : args) {
                V v = eval(n);
                if (v.kind == V.Kind.RANGE) {
                    for (V item : v.range) {
                        if (item.isError()) return item;
                        if (item.kind == V.Kind.NUMBER || item.kind == V.Kind.DATE) out.add(item.num);
                    }
                } else if (v.isError()) {
                    return v;
                } else {
                    Double d = num(v);
                    if (d == null) return V.error("#VALUE!");
                    out.add(d);
                }
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        V withNumbers(List<Node> args, Function<List<Double>, V> body) {
            Object r = numbers(args);
            return r instanceof V v ? v : body.apply((List<Double>) r);
        }

        V arg(List<Node> args, int i) { return i < args.size() ? single(eval(args.get(i))) : V.EMPTY; }

        Double numArg(List<Node> args, int i) {
            V v = arg(args, i);
            return v.isError() ? null : num(v);
        }

        V call(String name, List<Node> args) {
            int n = args.size();
            switch (name) {
                case "SUM":
                    return withNumbers(args, l -> V.number(l.stream().mapToDouble(d -> d).sum()));
                case "AVERAGE":
                    return withNumbers(args, l -> l.isEmpty() ? V.error("#DIV/0!") : V.number(l.stream().mapToDouble(d -> d).average().orElse(0)));
                case "MIN", "MAX": {
                    List<V> numeric = flat(args).stream().filter(v -> v.kind == V.Kind.NUMBER || v.kind == V.Kind.DATE).toList();
                    boolean dates = !numeric.isEmpty() && numeric.stream().allMatch(v -> v.kind == V.Kind.DATE);
                    return withNumbers(args, l -> {
                        double v = l.isEmpty() ? 0 : name.equals("MIN") ? l.stream().mapToDouble(d -> d).min().getAsDouble()
                                : l.stream().mapToDouble(d -> d).max().getAsDouble();
                        return dates ? V.date(v) : V.number(v);
                    });
                }
                case "PRODUCT":
                    return withNumbers(args, l -> V.number(l.isEmpty() ? 0 : l.stream().mapToDouble(d -> d).reduce(1, (x, y) -> x * y)));
                case "MEDIAN":
                    return withNumbers(args, l -> {
                        if (l.isEmpty()) return V.error("#NUM!");
                        List<Double> s = new ArrayList<>(l);
                        s.sort(null);
                        int k = s.size();
                        return V.number(k % 2 == 1 ? s.get(k / 2) : (s.get(k / 2 - 1) + s.get(k / 2)) / 2);
                    });
                case "COUNT":
                    return V.number(flat(args).stream().filter(v -> v.kind == V.Kind.NUMBER || v.kind == V.Kind.DATE).count());
                case "COUNTA":
                    return V.number(flat(args).stream().filter(v -> v.kind != V.Kind.EMPTY).count());
                case "COUNTBLANK":
                    return V.number(flat(args).stream().filter(v -> v.kind == V.Kind.EMPTY || v.kind == V.Kind.TEXT && v.text.isEmpty()).count());
                case "ABS", "SQRT", "INT", "EXP", "LN", "LOG10": {
                    if (n != 1) return V.error("#VALUE!");
                    V a = arg(args, 0);
                    if (a.isError()) return a;
                    Double x = num(a);
                    if (x == null) return V.error("#VALUE!");
                    return switch (name) {
                        case "ABS" -> V.number(Math.abs(x));
                        case "SQRT" -> x < 0 ? V.error("#NUM!") : V.number(Math.sqrt(x));
                        case "INT" -> V.number(Math.floor(x));
                        case "EXP" -> V.number(Math.exp(x));
                        case "LN" -> x <= 0 ? V.error("#NUM!") : V.number(Math.log(x));
                        default -> x <= 0 ? V.error("#NUM!") : V.number(Math.log10(x));
                    };
                }
                case "ROUND", "ROUNDUP", "ROUNDDOWN": {
                    if (n < 1 || n > 2) return V.error("#VALUE!");
                    Double x = numArg(args, 0);
                    if (x == null) return V.error("#VALUE!");
                    Double dg = n > 1 ? numArg(args, 1) : Double.valueOf(0);
                    double scale = Math.pow(10, dg == null ? 0 : (double) (long) (double) dg);
                    double v = x * scale;
                    double r = switch (name) {
                        case "ROUND" -> Math.signum(v) * Math.floor(Math.abs(v) + 0.5);
                        case "ROUNDUP" -> Math.signum(v) * Math.ceil(Math.abs(v) - 1e-12);
                        default -> Math.signum(v) * Math.floor(Math.abs(v) + 1e-12);
                    };
                    return V.number(r / scale);
                }
                case "MOD": {
                    Double x = numArg(args, 0), y = numArg(args, 1);
                    if (n != 2 || x == null || y == null) return V.error("#VALUE!");
                    if (y == 0) return V.error("#DIV/0!");
                    return V.number(x - y * Math.floor(x / y));
                }
                case "POWER": {
                    Double x = numArg(args, 0), y = numArg(args, 1);
                    if (n != 2 || x == null || y == null) return V.error("#VALUE!");
                    double p = Math.pow(x, y);
                    return Double.isFinite(p) ? V.number(p) : V.error("#NUM!");
                }
                case "PI":
                    return V.number(Math.PI);
                case "IF": {
                    if (n < 2 || n > 3) return V.error("#VALUE!");
                    V c = arg(args, 0);
                    if (c.isError()) return c;
                    Boolean tv = truth(c);
                    if (tv == null) return V.error("#VALUE!");
                    if (tv) return single(eval(args.get(1)));
                    return n > 2 ? single(eval(args.get(2))) : V.bool(false);
                }
                case "IFERROR": {
                    if (n != 2) return V.error("#VALUE!");
                    V v = arg(args, 0);
                    return v.isError() ? arg(args, 1) : v;
                }
                case "AND", "OR": {
                    List<Boolean> values = new ArrayList<>();
                    for (V v : flat(args)) {
                        if (v.isError()) return v;
                        if (v.kind == V.Kind.TEXT || v.kind == V.Kind.EMPTY) continue;
                        Boolean tv = truth(v);
                        if (tv != null) values.add(tv);
                    }
                    if (values.isEmpty()) return V.error("#VALUE!");
                    return V.bool(name.equals("AND") ? !values.contains(false) : values.contains(true));
                }
                case "NOT": {
                    Boolean tv = n == 1 ? truth(arg(args, 0)) : null;
                    return tv == null ? V.error("#VALUE!") : V.bool(!tv);
                }
                case "CONCAT", "CONCATENATE": {
                    StringBuilder sb = new StringBuilder();
                    for (V v : flat(args)) {
                        if (v.isError()) return v;
                        sb.append(text(v));
                    }
                    return V.text(sb.toString());
                }
                case "LEN", "UPPER", "LOWER", "TRIM": {
                    if (n != 1) return V.error("#VALUE!");
                    V v = arg(args, 0);
                    if (v.isError()) return v;
                    String s = text(v);
                    return switch (name) {
                        case "LEN" -> V.number(s.codePointCount(0, s.length()));
                        case "UPPER" -> V.text(s.toUpperCase(Locale.ROOT));
                        case "LOWER" -> V.text(s.toLowerCase(Locale.ROOT));
                        default -> V.text(String.join(" ", s.trim().split(" +")));
                    };
                }
                case "LEFT", "RIGHT": {
                    if (n < 1 || n > 2) return V.error("#VALUE!");
                    String s = text(arg(args, 0));
                    Double k = n > 1 ? numArg(args, 1) : Double.valueOf(1);
                    if (k == null || k < 0) return V.error("#VALUE!");
                    int c = (int) Math.min(s.length(), (long) (double) k);
                    return V.text(name.equals("LEFT") ? s.substring(0, c) : s.substring(s.length() - c));
                }
                case "MID": {
                    Double start = numArg(args, 1), count = numArg(args, 2);
                    if (n != 3 || start == null || count == null || start < 1 || count < 0) return V.error("#VALUE!");
                    String s = text(arg(args, 0));
                    int from = (int) Math.min((long) (double) start - 1, s.length());
                    return V.text(s.substring(from, (int) Math.min(s.length(), from + (long) (double) count)));
                }
                case "SUMIF", "COUNTIF", "AVERAGEIF": {
                    if (name.equals("COUNTIF") ? n != 2 : n < 2 || n > 3) return V.error("#VALUE!");
                    V tested = eval(args.get(0));
                    if (tested.kind != V.Kind.RANGE) return V.error("#VALUE!");
                    V criterion = arg(args, 1);
                    List<V> summed = tested.range;
                    if (n > 2) {
                        V other = eval(args.get(2));
                        if (other.kind == V.Kind.RANGE) summed = other.range;
                    }
                    double total = 0;
                    int count = 0;
                    for (int i = 0; i < tested.range.size(); i++) {
                        if (!matches(tested.range.get(i), criterion)) continue;
                        count++;
                        if (i < summed.size() && summed.get(i).kind == V.Kind.NUMBER) total += summed.get(i).num;
                    }
                    return switch (name) {
                        case "COUNTIF" -> V.number(count);
                        case "SUMIF" -> V.number(total);
                        default -> count == 0 ? V.error("#DIV/0!") : V.number(total / count);
                    };
                }
                case "TODAY", "NOW": {
                    double now = serial(LocalDateTime.now());
                    return V.date(name.equals("NOW") ? now : Math.floor(now));
                }
                case "DATE": {
                    Double y = numArg(args, 0), m = numArg(args, 1), d = numArg(args, 2);
                    if (n != 3 || y == null || m == null || d == null) return V.error("#VALUE!");
                    try {
                        LocalDate date = LocalDate.of((int) (double) y, 1, 1).plusMonths((long) (double) m - 1).plusDays((long) (double) d - 1);
                        return V.date(serial(date));
                    } catch (RuntimeException e) {
                        return V.error("#VALUE!");
                    }
                }
                case "YEAR", "MONTH", "DAY", "WEEKDAY": {
                    Double s = n == 1 ? numArg(args, 0) : null;
                    if (s == null) return V.error("#VALUE!");
                    LocalDate d = dateTime(s).toLocalDate();
                    return switch (name) {
                        case "YEAR" -> V.number(d.getYear());
                        case "MONTH" -> V.number(d.getMonthValue());
                        case "DAY" -> V.number(d.getDayOfMonth());
                        default -> V.number(d.getDayOfWeek().getValue() % 7 + 1);
                    };
                }
                case "EDATE", "EOMONTH": {
                    Double s = numArg(args, 0), months = numArg(args, 1);
                    if (n != 2 || s == null || months == null) return V.error("#VALUE!");
                    LocalDate d = dateTime(s).toLocalDate().plusMonths((long) (double) months);
                    if (name.equals("EOMONTH")) d = d.withDayOfMonth(d.lengthOfMonth());
                    return V.date(serial(d));
                }
                case "DAYS": {
                    Double end = numArg(args, 0), start = numArg(args, 1);
                    if (n != 2 || end == null || start == null) return V.error("#VALUE!");
                    return V.number(Math.floor(end) - Math.floor(start));
                }
                case "DATEDIF": {
                    Double start = numArg(args, 0), end = numArg(args, 1);
                    if (n != 3 || start == null || end == null || start > end) return V.error("#NUM!");
                    String unit = text(arg(args, 2)).toUpperCase(Locale.ROOT);
                    LocalDate a = dateTime(start).toLocalDate(), b = dateTime(end).toLocalDate();
                    long months = ChronoUnit.MONTHS.between(a, b);
                    return switch (unit) {
                        case "D" -> V.number(Math.floor(end) - Math.floor(start));
                        case "M" -> V.number(months);
                        case "Y" -> V.number(months / 12);
                        default -> V.error("#NUM!");
                    };
                }
                default:
                    return V.error("#NAME?");
            }
        }

        /** COUNTIF-style criteria: "&gt;5", "&lt;=3", "&lt;&gt;x", "=x", "x" (text ignores case; * and ? are wildcards). */
        boolean matches(V value, V criterion) {
            String op = "=";
            V operand = criterion;
            if (criterion.kind == V.Kind.TEXT) {
                for (String c : new String[] { "<=", ">=", "<>", "<", ">", "=" }) {
                    if (criterion.text.startsWith(c)) {
                        op = c;
                        operand = valueOfText(criterion.text.substring(c.length()));
                        break;
                    }
                }
            }
            if (operand.kind == V.Kind.TEXT && (op.equals("=") || op.equals("<>")) && (operand.text.contains("*") || operand.text.contains("?"))) {
                String regex = "(?i)^" + Pattern.quote(operand.text).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q") + "$";
                boolean hit = text(value).matches(regex);
                return op.equals("=") == hit;
            }
            if (value.kind == V.Kind.EMPTY && op.equals("=") && operand.kind == V.Kind.TEXT && operand.text.isEmpty()) return true;
            if (value.kind == V.Kind.EMPTY) return op.equals("<>") && operand.kind != V.Kind.EMPTY;
            if (operand.kind == V.Kind.NUMBER && (num(value) == null || value.kind == V.Kind.TEXT)) return op.equals("<>");
            int o = compare(value, operand);
            return switch (op) {
                case "=" -> o == 0;
                case "<>" -> o != 0;
                case "<" -> o < 0;
                case ">" -> o > 0;
                case "<=" -> o <= 0;
                default -> o >= 0;
            };
        }
    }

    // MARK: Rewriting references

    /** References after a row (rows) or column was inserted (+1) or removed (-1) at an index, as Excel moves them. */
    public static String shift(String formula, boolean rows, int index, int delta) {
        if (!isFormula(formula)) return formula;
        String body = formula.substring(1);
        List<Token> tokens = tokenize(body);
        if (tokens == null) return formula;
        StringBuilder out = new StringBuilder();
        int cursor = 0, k = 0;
        while (k < tokens.size()) {
            Token tk = tokens.get(k);
            if (tk.type != T.REF) {
                k++;
                continue;
            }
            Ref ref = parseRef(tk.text);
            out.append(body, cursor, tk.start);
            if (k + 2 < tokens.size() && tokens.get(k + 1).type == T.COLON && tokens.get(k + 2).type == T.REF) {
                Ref end = parseRef(tokens.get(k + 2).text);
                boolean lowFirst = (rows ? ref.row() : ref.column()) <= (rows ? end.row() : end.column());
                Ref lo = lowFirst ? ref : end, hi = lowFirst ? end : ref;
                boolean both = (rows ? lo.row() : lo.column()) == index && (rows ? hi.row() : hi.column()) == index;
                Ref a = moved(lo, rows, index, delta, false, both), b = moved(hi, rows, index, delta, true, both);
                if (a != null && b != null && (rows ? b.row() : b.column()) >= (rows ? a.row() : a.column())) out.append(a.text()).append(':').append(b.text());
                else out.append("#REF!");
                cursor = tokens.get(k + 2).end;
                k += 3;
                continue;
            }
            Ref m = moved(ref, rows, index, delta, false, true);
            out.append(m == null ? "#REF!" : m.text());
            cursor = tk.end;
            k++;
        }
        out.append(body.substring(cursor));
        return "=" + out;
    }

    private static Ref moved(Ref r, boolean rows, int index, int delta, boolean isEnd, boolean partnerRemoved) {
        int value = rows ? r.row() : r.column();
        int nv = value;
        if (delta > 0) {
            if (value >= index) nv += delta;
        } else {
            if (value > index) nv -= 1;
            else if (value == index) {
                if (partnerRemoved) return null;
                if (isEnd) nv -= 1;
            }
        }
        if (nv < 0) return null;
        return rows ? new Ref(nv, r.column(), r.absRow(), r.absColumn()) : new Ref(r.row(), nv, r.absRow(), r.absColumn());
    }

    /** A formula moved to another row (sorting): its relative row references move with it; $-anchored ones stay. */
    public static String offset(String formula, int rowDelta, int columnDelta) {
        if (rowDelta == 0 && columnDelta == 0 || !isFormula(formula)) return formula;
        String body = formula.substring(1);
        List<Token> tokens = tokenize(body);
        if (tokens == null) return formula;
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (Token tk : tokens) {
            if (tk.type != T.REF) continue;
            Ref r = parseRef(tk.text);
            out.append(body, cursor, tk.start);
            int row = r.absRow() ? r.row() : r.row() + rowDelta, col = r.absColumn() ? r.column() : r.column() + columnDelta;
            out.append(row >= 0 && col >= 0 ? new Ref(row, col, r.absRow(), r.absColumn()).text() : "#REF!");
            cursor = tk.end;
        }
        out.append(body.substring(cursor));
        return "=" + out;
    }

    /** Functions offered in the Formulas menu (name, description). */
    public static final String[][] MENU_FUNCTIONS = { { "SUM", "Adds numbers" }, { "AVERAGE", "Average of numbers" }, { "COUNT", "Counts numbers" },
            { "COUNTA", "Counts filled cells" }, { "MIN", "Smallest number" }, { "MAX", "Largest number" }, { "ROUND", "Rounds to digits" },
            { "IF", "Chooses by a condition" }, { "SUMIF", "Adds where a condition holds" }, { "COUNTIF", "Counts where a condition holds" },
            { "AVERAGEIF", "Averages where a condition holds" }, { "IFERROR", "A value instead of an error" }, { "CONCAT", "Joins text" },
            { "MEDIAN", "Middle number" }, { "PRODUCT", "Multiplies numbers" }, { "ABS", "Absolute value" }, { "TODAY", "Today's date" },
            { "DATE", "A date from year, month, day" }, { "EDATE", "A date months later" }, { "DATEDIF", "Days, months or years between" } };
}
