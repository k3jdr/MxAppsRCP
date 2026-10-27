package com.k3jdr.mxfilemgr.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matches names against {@code *.java;*.md} style wildcards, or a regular expression (FileModels.swift's
 * NameMatcher). Patterns are separated by {@code ;}. With {@code substringIfPlain}, a pattern without wildcards
 * matches anywhere in the name (the quick filter).
 */
public final class NameMatcher {
    private final List<Pattern> patterns = new ArrayList<>();
    private final List<String> substrings = new ArrayList<>();
    private final boolean caseSensitive;
    private final boolean invalid;

    public NameMatcher(String text) {
        this(text, false, false, false);
    }

    public NameMatcher(String text, boolean substringIfPlain, boolean regex, boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        boolean bad = false;
        if (regex) {
            try {
                patterns.add(Pattern.compile(text, flags));
            } catch (PatternSyntaxException e) {
                bad = true;
            }
            invalid = bad;
            return;
        }
        for (String raw : (text == null ? "" : text).split(";")) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            if (substringIfPlain && p.indexOf('*') < 0 && p.indexOf('?') < 0) {
                substrings.add(caseSensitive ? p : p.toLowerCase(Locale.ROOT));
                continue;
            }
            StringBuilder re = new StringBuilder("^");
            for (char c : p.toCharArray()) {
                if (c == '*') re.append(".*");
                else if (c == '?') re.append('.');
                else re.append(Pattern.quote(String.valueOf(c)));
            }
            // "*.*" also matches names without a dot, as on Windows.
            String compiled = p.equals("*.*") ? "^.*$" : re.append('$').toString();
            patterns.add(Pattern.compile(compiled, flags | Pattern.DOTALL));
        }
        invalid = false;
    }

    public boolean isEmpty() { return patterns.isEmpty() && substrings.isEmpty(); }

    /** A regular expression that didn't compile. */
    public boolean isInvalid() { return invalid; }

    public boolean matches(String name) {
        if (invalid) return false;
        if (isEmpty()) return true;
        String folded = caseSensitive ? name : name.toLowerCase(Locale.ROOT);
        for (String s : substrings) if (folded.contains(s)) return true;
        for (Pattern p : patterns) if (p.matcher(name).find()) return true;
        return false;
    }
}
