package com.k3jdr.mxnoteone.store;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.k3jdr.mxnoteone.model.Kinds.PageColor;
import com.k3jdr.mxnoteone.model.Kinds.RuleLines;
import com.k3jdr.mxnoteone.model.Kinds.TagKind;
import com.k3jdr.mxnoteone.model.PageElement;
import com.k3jdr.mxnoteone.model.TableData;
import com.k3jdr.mxnoteone.text.CharStyle;
import com.k3jdr.mxnoteone.text.ParaStyle;
import com.k3jdr.mxnoteone.text.Paragraphs;
import com.k3jdr.mxnoteone.text.Paragraphs.Style;
import com.k3jdr.mxnoteone.text.RText;
import com.k3jdr.mxnoteone.text.RichCodec;

/** Ready-made pages: New Page from Template and Insert ▸ Page Templates (PageTemplates.swift). */
public enum Templates {
    BLANK("Blank Page"), TODO("To Do List"), MEETING("Meeting Notes"), PROJECT("Project Overview"), LECTURE("Lecture Notes (Cornell)"),
    JOURNAL("Daily Journal"), WEEKLY("Weekly Planner"), DECISIONS("Decision Log"), WELCOME("Welcome to MxNoteONE");

    public final String title;

    Templates(String title) { this.title = title; }

    /** Templates offered in the menus (Welcome is only used for a new library). */
    public static List<Templates> choices() {
        List<Templates> out = new ArrayList<>();
        for (Templates t : values()) if (t != BLANK && t != WELCOME) out.add(t);
        return out;
    }

    public static final double MARGIN = 48, CONTENT_TOP = 110;

    public record Built(String title, List<PageElement> elements, PageColor color, RuleLines rules) {}

    private static final class Text {
        final RText.Builder b = new RText.Builder();

        Text add(String line, Style style) {
            b.line(line, style.apply(CharStyle.DEFAULT), ParaStyle.DEFAULT);
            return this;
        }

        Text add(String line) { return add(line, Style.NORMAL); }

        PageElement element(double x, double y, double width) {
            PageElement e = new PageElement(PageElement.TEXT, x, y, width);
            e.rtfd = RichCodec.encode(b.build());
            return e;
        }
    }

    public Built build() {
        double left = MARGIN, top = CONTENT_TOP + 10;
        String today = LocalDate.now().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG));
        String todo = TagKind.TODO.symbol + " ", bullet = Paragraphs.BULLET;
        List<PageElement> els = new ArrayList<>();
        switch (this) {
            case BLANK -> {
                return new Built("", els, null, null);
            }
            case TODO -> {
                Text t = new Text().add("Tasks", Style.HEADING1);
                for (int i = 0; i < 5; i++) t.add(todo);
                t.add("").add("Done", Style.HEADING2).add(TagKind.TODO_DONE + " Create this list");
                els.add(t.element(left, top, 560));
                return new Built("To Do List", els, null, null);
            }
            case MEETING -> {
                Text t = new Text().add("Meeting Details", Style.HEADING2).add("Date: " + today).add("Location: ").add("")
                        .add("Attendees", Style.HEADING2).add(bullet).add("").add("Agenda", Style.HEADING2).add("1. ").add("")
                        .add("Notes", Style.HEADING2).add(bullet).add("").add("Action Items", Style.HEADING2).add(todo);
                els.add(t.element(left, top, 600));
                return new Built("Meeting Notes – " + today, els, null, null);
            }
            case PROJECT -> {
                Text t = new Text().add("Goal", Style.HEADING2).add("").add("Scope", Style.HEADING2).add(bullet).add("")
                        .add("Risks", Style.HEADING2).add(TagKind.CRITICAL.symbol + " ").add("").add("Open Questions", Style.HEADING2)
                        .add(TagKind.QUESTION.symbol + " ");
                els.add(t.element(left, top, 620));
                PageElement table = new PageElement(PageElement.TABLE, left, top + 400, 620);
                table.table = new TableData(5, 4, 620);
                table.table.rows.set(0, new ArrayList<>(List.of("Milestone", "Owner", "Due", "Status")));
                els.add(table);
                return new Built("Project Overview", els, null, null);
            }
            case LECTURE -> {
                els.add(new Text().add("Cues", Style.HEADING3).add(TagKind.QUESTION.symbol + " ").element(left, top, 200));
                els.add(new Text().add("Notes", Style.HEADING3).add(bullet).element(left + 230, top, 470));
                els.add(new Text().add("Summary", Style.HEADING3).add("").element(left, top + 520, 700));
                return new Built("Lecture – " + today, els, PageColor.NONE, RuleLines.COLLEGE);
            }
            case JOURNAL -> {
                Text t = new Text().add("Grateful for", Style.HEADING2).add("1. ").add("").add("Today's focus", Style.HEADING2).add(todo)
                        .add("").add("Notes", Style.HEADING2).add("").add("Reflection", Style.HEADING2).add("");
                els.add(t.element(left, top, 560));
                return new Built(today, els, PageColor.CREAM, RuleLines.NONE);
            }
            case WEEKLY -> {
                LocalDate start = LocalDate.now().with(TemporalAdjusters.previousOrSame(WeekFields.of(Locale.getDefault()).getFirstDayOfWeek()));
                TableData table = new TableData(8, 3, 700);
                table.rows.set(0, new ArrayList<>(List.of("Day", "Plans", "Done")));
                DateTimeFormatter day = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault());
                for (int d = 0; d < 7; d++) table.rows.get(d + 1).set(0, start.plusDays(d).format(day));
                table.columnWidths = new ArrayList<>(List.of(170.0, 330.0, 200.0));
                PageElement e = new PageElement(PageElement.TABLE, left, top, 700);
                e.table = table;
                els.add(e);
                return new Built("Week of " + start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), els, null, null);
            }
            case DECISIONS -> {
                TableData table = new TableData(6, 5, 760);
                table.rows.set(0, new ArrayList<>(List.of("Date", "Decision", "Options Considered", "Reason", "Owner")));
                table.columnWidths = new ArrayList<>(List.of(100.0, 180.0, 180.0, 200.0, 100.0));
                PageElement e = new PageElement(PageElement.TABLE, left, top, 760);
                e.table = table;
                els.add(e);
                return new Built("Decision Log", els, null, null);
            }
            case WELCOME -> {
                Text t = new Text().add("Your notes, organized like a binder", Style.HEADING1)
                        .add("Notebooks hold sections (the coloured tabs in the sidebar), and sections hold pages. Group sections into section groups, and indent pages to make subpages.")
                        .add("").add("Write anywhere", Style.HEADING2)
                        .add(bullet + "Click anywhere on a page and start typing. Drag a container by its top bar; resize it from its right edge.")
                        .add(bullet + "Drop pictures and files onto a page, or use the Insert tab for tables, pictures and file attachments.")
                        .add(bullet + "Type some text and press Tab: the paragraph becomes a table.")
                        .add("").add("Tags", Style.HEADING2)
                        .add(TagKind.TODO.symbol + " Press Ctrl+1 for a To Do checkbox – click the box to check it off")
                        .add(TagKind.IMPORTANT.symbol + " Ctrl+2 Important, Ctrl+3 Question, Ctrl+4 Critical, Ctrl+5 Idea … see the Tags menu")
                        .add(TagKind.IDEA.symbol + " View ▸ Tag Summary collects tagged notes from every notebook")
                        .add("").add("Find and link", Style.HEADING2)
                        .add(bullet + "Ctrl+E searches all open notebooks; Ctrl+F finds on the current page.")
                        .add(bullet + "Type [[Page Name]] to link to a page (it's created if it doesn't exist), or use Ctrl+K.")
                        .add("").add("Keep things safe", Style.HEADING2)
                        .add(bullet + "Right-click a section ▸ Password Protect Section… to encrypt it.")
                        .add(bullet + "Deleted pages and sections wait in the Recycle Bin for 60 days.")
                        .add(bullet + "Notebooks are folders, so you can keep them in OneDrive or Dropbox and open them on a Mac too.");
                els.add(t.element(left, top, 680));
                return new Built("Welcome to MxNoteONE", els, null, null);
            }
            default -> {
                return new Built("", els, null, null);
            }
        }
    }
}
