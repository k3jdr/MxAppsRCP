package com.k3jdr.mxacctmgr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.swt.widgets.Shell;

import com.k3jdr.mx.core.links.AppLink;
import com.k3jdr.mx.core.ui.NavigationHistory;
import com.k3jdr.mxacctmgr.invest.PortfolioStore;
import com.k3jdr.mxacctmgr.port.PortfolioStyle;

/**
 * The window's state (PortStore.swift's frame part): the screen shown and the places visited, My approach, the
 * engine, the lessons folded away, the last action's message; the Investments state is in {@link PortfolioStore}.
 * Everything here is changed on the UI thread only.
 */
public final class App {
    public enum Engine {
        SERVICES("Layer 3 services"), BUILT_IN("Built-in (Java)");

        public final String title;

        Engine(String title) { this.title = title; }
    }

    /** A place in the window, for Back and Forward (Navigation.swift's Place). */
    public record Place(String screen) {}

    public final Shell shell;
    public final PortfolioStore port;
    public final NavigationHistory<Place> history = new NavigationHistory<>((a, b) -> a.screen().equals(b.screen()));

    private Screen screen;
    private Engine engine = Engine.SERVICES;
    private PortfolioStyle approach;
    private final Set<String> collapsed;
    private String message;
    private int busy;
    /** An mxacctmgr://secure-accounts link for Secure Accounts to act on once its list is loaded. */
    public AppLink secureRequest;

    private final List<Consumer<Screen>> screenListeners = new ArrayList<>();
    private final List<Runnable> stateListeners = new ArrayList<>();

    public App(Shell shell) {
        this.shell = shell;
        this.approach = "Active".equals(Settings.PREFS.get("myApproach", "")) ? PortfolioStyle.ACTIVE : PortfolioStyle.PASSIVE;
        this.collapsed = new LinkedHashSet<>(Settings.PREFS.getList("collapsedLessons"));
        Screen saved = Screen.of(Settings.PREFS.get("screen", Screen.MY_PORTFOLIO.id));
        this.screen = saved != null && Settings.shows(saved) ? saved : Screen.MY_PORTFOLIO;
        this.port = new PortfolioStore(this);
    }

    public Screen screen() { return screen; }

    /** Shows a screen (a lesson-only page Settings ▸ Guidance hides opens My Holdings instead). */
    public void go(Screen s) {
        if (s == null) return;
        if (!Settings.shows(s)) s = Screen.MY_PORTFOLIO;
        screen = s;
        Settings.PREFS.put("screen", s.id);
        // The Investments path always works on the user's own account (My Portfolio).
        if (s.group == Screen.Group.MINE && !"mine".equals(port.selectedID())) port.select("mine");
        history.visit(new Place(s.id), title(s, true));
        for (Consumer<Screen> l : List.copyOf(screenListeners)) l.accept(s);
        changed();
    }

    public void back() { history.back().ifPresent(p -> go(Screen.of(p.screen()))); }
    public void forward() { history.forward().ifPresent(p -> go(Screen.of(p.screen()))); }

    public void onScreen(Consumer<Screen> l) { screenListeners.add(l); }

    /** Told when anything the frame shows changes: the status bar, the toolbar, the sidebar's titles. */
    public void onChange(Runnable l) { stateListeners.add(l); }

    public void removeOnChange(Runnable l) { stateListeners.remove(l); }

    public void changed() {
        for (Runnable r : List.copyOf(stateListeners)) r.run();
    }

    // My approach: active (optimize, trades & taxes) or passive (track, rebalance bands).

    public PortfolioStyle approach() { return approach; }

    public boolean active() { return approach == PortfolioStyle.ACTIVE; }

    public void setApproach(PortfolioStyle a) {
        if (a == approach) return;
        approach = a;
        Settings.PREFS.put("myApproach", a == PortfolioStyle.ACTIVE ? "Active" : "Passive");
        port.clearResults();
        // Steps 4 and 5 change name and screen with the approach.
        if (screen == Screen.MY_IMPROVE || screen == Screen.MY_TRADES || screen.group == Screen.Group.MINE) {
            for (Consumer<Screen> l : List.copyOf(screenListeners)) l.accept(screen);
        }
        changed();
    }

    /** The passive reading of a shared screen: My Portfolio run passively. */
    public boolean passiveContext() { return screen.group == Screen.Group.MINE && approach == PortfolioStyle.PASSIVE; }

    public Engine engine() { return engine; }

    public void setEngine(Engine e) {
        if (e == engine) return;
        engine = e;
        port.clearResults();
        changed();
    }

    // Lessons folded away, remembered per screen.

    public boolean collapsed(String id) { return collapsed.contains(id); }

    public void setCollapsed(String id, boolean c) {
        if (c) collapsed.add(id); else collapsed.remove(id);
        Settings.PREFS.putList("collapsedLessons", new ArrayList<>(collapsed));
    }

    // The status bar's last action and busy mark.

    public String message() { return message; }

    public void setMessage(String m) {
        message = m;
        changed();
    }

    public boolean busy() { return busy > 0; }

    public void busy(boolean on) {
        busy = Math.max(0, busy + (on ? 1 : -1));
        changed();
    }

    /** A screen's title as shown: steps 4 and 5 follow the approach; {@code full} adds "Investments · n." to a step. */
    public String title(Screen s, boolean full) {
        String shortTitle;
        if (s == Screen.MY_IMPROVE) shortTitle = active() ? "Optimize" : "Build the Tracker";
        else if (s == Screen.MY_TRADES) shortTitle = active() ? "Trades & Taxes" : "Rebalance Bands";
        else shortTitle = s.title;
        if (s.group != Screen.Group.MINE) return shortTitle;
        return full ? "Investments · " + s.step() + ". " + shortTitle : s.step() + ". " + shortTitle;
    }

    /** The lesson key of a screen as it reads now (the passive variants of the Investments steps). */
    public String lessonKey(Screen s) {
        return s.group == Screen.Group.MINE && !active() ? s.id + ".passive" : s.id;
    }
}
