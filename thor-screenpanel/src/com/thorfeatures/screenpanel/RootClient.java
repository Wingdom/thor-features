package com.thorfeatures.screenpanel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Runs the screenblank / screenmove commands as root.
 *
 * The runner is injectable so every caller can be exercised on the host with a
 * fake - no device, no root. The real runner is the only part that touches
 * ProcessBuilder, and it is the only part the host tests do not cover.
 *
 * Same shape as thor-screenmove-tile's ScreenmoveClient, deliberately: that
 * one is installed and working, and this is the second user of the pattern.
 */
public final class RootClient {

    public interface Runner { Result run(String[] argv); }

    public static final class Result {
        public final int code;
        public final String out;
        public Result(int code, String out) { this.code = code; this.out = out == null ? "" : out; }
    }

    private final Runner runner;

    public RootClient(Runner runner) { this.runner = runner; }

    public Result run(String command) { return runner.run(PanelCommands.suArgv(command)); }

    /**
     * Whether screenblank answered, and what it said.
     *
     * These arrive together because they come from one command. An earlier
     * version asked twice - `available()` ran `screenblank status` purely to
     * see whether it exited 0, then `blankState()` ran it again for the
     * answer. That command is the panel's most expensive, so the redundant
     * call cost more than any optimisation since has saved.
     */
    public static final class BlankState {
        /** False when the command did not run: module missing or root denied. */
        public final boolean available;
        public final boolean topDark;
        public final boolean bottomDark;

        BlankState(boolean available, boolean topDark, boolean bottomDark) {
            this.available = available;
            this.topDark = topDark;
            this.bottomDark = bottomDark;
        }
    }

    /** Read screenblank once, for both availability and state. */
    public BlankState blankState() {
        Result r = run(PanelCommands.SCREENBLANK + " status");
        if (r.code != 0) return new BlankState(false, false, false);
        boolean[] dark = PanelCommands.parseBlankStatus(r.out);
        return new BlankState(true, dark[0], dark[1]);
    }

    /** Which screens have something worth moving, as {top, bottom}. */
    public boolean[] movableState() {
        Result r = run(PanelCommands.SCREENMOVE + " status");
        if (r.code != 0) return new boolean[] { false, false };
        return PanelCommands.parseMovable(r.out);
    }

    /**
     * The state a quick setting's CLI reports, trimmed, or null when the call
     * fails.
     *
     * null and "the CLI reported something unrecognised" are deliberately NOT
     * the same thing. A missing module means the control cannot be offered at
     * all; an unrecognised state means the control is present but idle, which
     * is a normal reading - `fanctl get` says "default" whenever no tier is
     * applied.
     */
    public String quickState(QuickSetting q) {
        Result r = run(q.statusCommand());
        if (r.code != 0) return null;
        String out = r.out.trim();
        return out.isEmpty() ? null : out;
    }

    /** Select a state on a quick setting. True only on a clean exit. */
    public boolean quickSet(QuickSetting q, String wire) {
        return run(q.setCommand(wire)).code == 0;
    }

    /**
     * Run a plan's commands in order, stopping at the first failure.
     *
     * Stopping rather than pressing on is deliberate. The orderings in
     * ScreenMode exist so that a screen is never blanked while it still holds
     * the foreground app; continuing past a failed move would blank it anyway
     * and do exactly the damage the ordering prevents.
     *
     * @return the command that failed, or null if all of them succeeded.
     */
    public String apply(List<String> commands) { return apply(commands, null); }

    /**
     * As {@link #apply(List)}, but also hands back the failing command's
     * output in {@code outHolder[0]} so the caller can show the reason
     * rather than a generic failure.
     */
    public String apply(List<String> commands, String[] outHolder) {
        for (String c : commands) {
            Result r = run(c);
            if (r.code != 0) {
                if (outHolder != null && outHolder.length > 0) outHolder[0] = r.out;
                return c;
            }
        }
        return null;
    }

    /** The real runner. Not exercised by host tests. */
    public static final Runner EXEC = argv -> {
        Process p = null;
        try {
            p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            return new Result(p.waitFor(), sb.toString());
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e.getMessage()));
        } finally {
            if (p != null) p.destroy();
        }
    };
}
