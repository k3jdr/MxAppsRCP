package com.k3jdr.mx.dbml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;

/**
 * Colours for DBML being typed (DBMLLanguage.swift's DBMLSyntax): keywords, settings, numbers, relationships,
 * colours, strings and comments, later rules over earlier ones.
 */
public final class DbmlSyntax {
    private DbmlSyntax() {}

    private record Rule(Pattern pattern, RGB color) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("\\b(Table|Ref|Enum|Note|Project|TableGroup|TablePartial|Records|indexes|as)\\b", Pattern.CASE_INSENSITIVE), DbmlColor.hex("#AF00DB")),
            new Rule(Pattern.compile("\\[[^\\]\\n]*\\]"), DbmlColor.hex("#267F99")),
            new Rule(Pattern.compile("(?<![\\w.])-?\\d+(\\.\\d+)?\\b"), DbmlColor.hex("#098658")),
            new Rule(Pattern.compile("\\s(<>|<|>|-)\\s"), DbmlColor.hex("#E07B24")),
            new Rule(Pattern.compile("#[0-9A-Fa-f]{3,8}\\b"), DbmlColor.hex("#795E26")),
            new Rule(Pattern.compile("\"[^\"\\n]*\""), DbmlColor.hex("#001080")),
            new Rule(Pattern.compile("`[^`]*`"), DbmlColor.hex("#795E26")),
            new Rule(Pattern.compile("'''[\\s\\S]*?'''|'[^'\\n]*'"), DbmlColor.hex("#A31515")),
            new Rule(Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/"), DbmlColor.hex("#008000")));

    /** The text's colours, as style ranges in order (the text's own colour left out). */
    public static StyleRange[] styles(String text) {
        RGB[] colors = new RGB[text.length()];
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) for (int k = m.start(); k < m.end(); k++) colors[k] = rule.color();
        }
        List<StyleRange> out = new ArrayList<>();
        int k = 0;
        while (k < colors.length) {
            if (colors[k] == null) {
                k++;
                continue;
            }
            int start = k;
            RGB c = colors[k];
            while (k < colors.length && c.equals(colors[k])) k++;
            out.add(new StyleRange(start, k - start, new Color(c), null));
        }
        return out.toArray(new StyleRange[0]);
    }

    /** Colours the DBML in a StyledText in place. */
    public static void highlight(StyledText text) {
        text.setStyleRanges(styles(text.getText()));
    }
}
