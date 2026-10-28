package com.thorfeatures.appprofiles;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Runs the appwatch / fanctl commands as root.
 *
 * The runner is injectable so every caller can be exercised on the host with a
 * fake. The real runner is the only part that touches ProcessBuilder, and the
 * only part the host tests do not cover. Same shape as thor-screenpanel's
 * RootClient and thor-screenmove-tile's ScreenmoveClient, deliberately.
 */
public final class RootClient {

    public interface Runner { Result run(String[] argv); }

    public static final class Result {
        public final int code;
        public final String out;
        public Result(int code, String out) { this.code = code; this.out = out == null ? "" : out; }
        public boolean ok() { return code == 0; }
    }

    private final Runner runner;

    public RootClient(Runner runner) { this.runner = runner; }

    public Result run(String command) { return runner.run(AppwatchCommands.suArgv(command)); }

    /** True when appwatch answers: module installed and root granted. */
    public boolean available() { return run(AppwatchCommands.STATUS).ok(); }

    /** True when fanctl answers. Without it nothing can be applied. */
    public boolean fanctlAvailable() { return run(AppwatchCommands.FANCTL_GET).ok(); }

    public Result status() { return run(AppwatchCommands.STATUS); }

    /**
     * The table as text, or null when it cannot be read.
     *
     * null and "" are deliberately different: "" is a readable empty table,
     * null means we could not look. Overwriting a table we failed to read
     * would destroy whatever is in it, including keys this app does not
     * manage, so the caller must refuse to save on null.
     */
    public String readTable() {
        Result r = run(AppwatchCommands.READ_TABLE);
        return r.ok() ? r.out : null;
    }

    /** Copy a staged file into the table. */
    public boolean writeTable(String stagedPath) {
        return run(AppwatchCommands.writeTable(stagedPath)).ok();
    }

    public boolean setEnabled(boolean on) {
        return run(on ? AppwatchCommands.ENABLE : AppwatchCommands.DISABLE).ok();
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
