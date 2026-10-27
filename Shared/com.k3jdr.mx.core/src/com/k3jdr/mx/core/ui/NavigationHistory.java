package com.k3jdr.mx.core.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * Back and Forward through the places visited, as in a browser and the MxAppsIOS apps (Navigation.swift): going
 * somewhere new after going back drops the places ahead; places visited within a second of each other on the same
 * screen (arrowing down a table) count as one.
 *
 * @param <T> a place — a record with equals, e.g. {@code record Place(String screen, String selection)}
 */
public final class NavigationHistory<T> {
    public record Entry<T>(T place, String title, long time) {}

    private final List<Entry<T>> entries = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final BiPredicate<T, T> sameScreen;
    private int index = -1;
    private static final int MAX = 100;

    /** {@code sameScreen} says whether two places are on one screen (their visits coalesce within a second). */
    public NavigationHistory(BiPredicate<T, T> sameScreen) {
        this.sameScreen = sameScreen;
    }

    /** Records a visit; ignored when it is the current place (as after Back or Forward). */
    public void visit(T place, String title) {
        long now = System.currentTimeMillis();
        if (index >= 0) {
            Entry<T> current = entries.get(index);
            if (Objects.equals(current.place(), place)) return;
            while (entries.size() > index + 1) entries.remove(entries.size() - 1);
            if (now - current.time() < 1000 && sameScreen.test(current.place(), place)) {
                entries.set(index, new Entry<>(place, title, now));
                fire();
                return;
            }
        }
        entries.add(new Entry<>(place, title, now));
        if (entries.size() > MAX) entries.remove(0);
        index = entries.size() - 1;
        fire();
    }

    public boolean canGoBack() { return index > 0; }
    public boolean canGoForward() { return index >= 0 && index < entries.size() - 1; }

    /** Moves back one place and returns it; the caller shows it (its visit is then ignored). */
    public Optional<T> back() { return go(index - 1); }
    public Optional<T> forward() { return go(index + 1); }

    public Optional<T> go(int i) {
        if (i < 0 || i >= entries.size() || i == index) return Optional.empty();
        index = i;
        fire();
        return Optional.of(entries.get(i).place());
    }

    public List<Entry<T>> entries() { return List.copyOf(entries); }
    public int index() { return index; }
    public Optional<T> current() { return index < 0 ? Optional.empty() : Optional.of(entries.get(index).place()); }

    /** Up to 15 places before the current one, nearest first (for holding the Back button). */
    public List<Integer> before() {
        List<Integer> out = new ArrayList<>();
        for (int i = index - 1; i >= 0 && out.size() < 15; i--) out.add(i);
        return out;
    }

    public List<Integer> after() {
        List<Integer> out = new ArrayList<>();
        for (int i = index + 1; i < entries.size() && out.size() < 15; i++) out.add(i);
        return out;
    }

    public void addListener(Runnable r) { listeners.add(r); }

    private void fire() { listeners.forEach(Runnable::run); }

    /** Adds Go ▸ Back, Forward and History to a menu (Ctrl+[ / Ctrl+], and Alt+Left / Alt+Right as on Windows). */
    public void addMenuItems(MenuBarBuilder.MenuBuilder go, java.util.function.Consumer<T> show) {
        go.item("&Back", org.eclipse.swt.SWT.MOD1 | '[', () -> back().ifPresent(show), this::canGoBack);
        go.item("&Forward", org.eclipse.swt.SWT.MOD1 | ']', () -> forward().ifPresent(show), this::canGoForward);
        go.dynamic("&History", m -> {
            for (int i = entries.size() - 1; i >= 0; i--) {
                final int at = i;
                m.check(entries.get(i).title(), 0, () -> at == index, v -> go(at).ifPresent(show));
            }
        });
    }
}
