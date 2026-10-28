package com.thorfeatures.appprofiles;

import com.thorfeatures.fanctl.FanMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-app profile table: one line per package per key, tab separated.
 *
 * <pre>
 *   com.example.game   fan   performance
 *   com.example.game   rgb   breathe
 *   com.example.emu    fan   balanced
 * </pre>
 *
 * This class edits the table in place rather than regenerating it. That is a
 * correctness requirement, not an optimisation: the format is deliberately
 * shared with per-app features that are not built yet, and this app manages
 * only the {@code fan} key. Rewriting the file from a parsed model of just the
 * fan entries would silently delete every {@code rgb} or {@code screen} line a
 * future version wrote. So unknown keys, blank lines, comments and even
 * malformed lines are carried through byte for byte, and only the one line
 * being changed is touched.
 *
 * Pure logic -- no Android dependencies -- so it is unit-tested on the host.
 */
public final class ProfileTable {

    /** The key this app owns. Lines carrying any other key are not ours. */
    public static final String FAN_KEY = "fan";

    private final List<String> lines;

    private ProfileTable(List<String> lines) { this.lines = lines; }

    /** Parse table text. Null or empty yields an empty table. */
    public static ProfileTable parse(String text) {
        List<String> ls = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            for (String line : text.split("\n", -1)) ls.add(line);
            // split("\n", -1) leaves a trailing "" for text ending in a
            // newline. Drop exactly that one, so render() does not grow a
            // blank line on every save.
            if (!ls.isEmpty() && ls.get(ls.size() - 1).isEmpty()) ls.remove(ls.size() - 1);
        }
        return new ProfileTable(ls);
    }

    /** Fields of a line, or null when it is not a well-formed 3-column row. */
    private static String[] fields(String line) {
        if (line == null) return null;
        String[] f = line.split("\t");
        if (f.length < 3) return null;
        if (f[0].isEmpty() || f[1].isEmpty()) return null;
        return f;
    }

    private static boolean isFanLineFor(String line, String pkg) {
        String[] f = fields(line);
        return f != null && f[0].equals(pkg) && f[1].equals(FAN_KEY);
    }

    /**
     * The tier recorded for a package, or null when it has no fan override.
     *
     * An unrecognised tier word reads as null -- no override -- rather than
     * being guessed at. The daemon applies the same rule, so the UI and the
     * thing doing the work agree about what a junk value means.
     */
    public FanMode fanTier(String pkg) {
        for (String line : lines) {
            if (isFanLineFor(line, pkg)) {
                FanMode m = FanMode.fromString(fields(line)[2]);
                return m.isActive() ? m : null;
            }
        }
        return null;
    }

    /** Every package with a usable fan override, in file order. */
    public Map<String, FanMode> fanOverrides() {
        Map<String, FanMode> out = new LinkedHashMap<>();
        for (String line : lines) {
            String[] f = fields(line);
            if (f == null || !f[1].equals(FAN_KEY)) continue;
            FanMode m = FanMode.fromString(f[2]);
            if (m.isActive() && !out.containsKey(f[0])) out.put(f[0], m);
        }
        return out;
    }

    /**
     * Set or clear a package's fan tier. A null tier clears it.
     *
     * Replaces the existing line in place so unrelated ordering is stable, and
     * appends only when the package has no fan line yet.
     */
    public void setFanTier(String pkg, FanMode tier) {
        if (pkg == null || pkg.isEmpty()) return;
        boolean clearing = tier == null || !tier.isActive();

        // Three explicit passes rather than index arithmetic during mutation.
        // The first version removed the line and then skipped the slot the
        // removal had shifted into, so a duplicate survived a clear -- the
        // kind of off-by-one that is easy to write and hard to read back.
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (isFanLineFor(lines.get(i), pkg)) hits.add(i);
        }

        // Drop every duplicate but the first, walking backwards so the
        // remaining indices stay valid. The daemon reads the first match, so a
        // later one is dead weight that would resurface if the first were
        // cleared.
        for (int k = hits.size() - 1; k >= 1; k--) lines.remove((int) hits.get(k));

        if (hits.isEmpty()) {
            if (!clearing) lines.add(row(pkg, tier));
        } else {
            int at = hits.get(0);
            if (clearing) lines.remove(at);
            else lines.set(at, row(pkg, tier));
        }
    }

    private static String row(String pkg, FanMode tier) {
        return pkg + "\t" + FAN_KEY + "\t" + tier.wire();
    }

    /** Table text, newline terminated, or "" when empty. */
    public String render() {
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    /** Line count, including lines this app does not manage. */
    public int size() { return lines.size(); }
}
