package com.thorfeatures.screenmove;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Thin wrapper over the screenmove binary installed by the Magisk module at
 * /system/bin/screenmove.
 *
 * The command runner is injectable so the whole class can be exercised on the
 * host with a fake, with no device and no root. The real runner is the only
 * part that touches ProcessBuilder.
 */
public class ScreenmoveClient {

    public interface Runner {
        Result run(String[] argv);
    }

    public static final class Result {
        public final int code;
        public final String out;
        public Result(int code, String out) {
            this.code = code;
            this.out = out == null ? "" : out;
        }
    }

    public static final String BINARY = "/system/bin/screenmove";

    private final Runner runner;

    public ScreenmoveClient(Runner runner) { this.runner = runner; }

    /**
     * Build the argv for a screenmove invocation under root.
     *
     * Pure and unit-tested. Arguments are fixed literals chosen by this class,
     * never user input or anything read back off the device, so there is
     * nothing to escape -- and a test asserts no shell metacharacter can reach
     * here.
     */
    static String[] suArgv(String arg) {
        return new String[] { "su", "-c", BINARY + " " + arg };
    }

    /** Current status, or an "unknown" status if the CLI is missing or fails. */
    public ScreenStatus status() {
        Result r = runner.run(suArgv("status"));
        if (r.code != 0) return ScreenStatus.unknown();
        return ScreenStatus.parse(r.out);
    }

    /** True when the CLI responds -- module installed and root granted. */
    public boolean available() {
        return runner.run(suArgv("status")).code == 0;
    }

    /** Move the foreground app to the other screen. True only on a clean exit. */
    public boolean toggle() {
        return runner.run(suArgv("toggle")).code == 0;
    }

    /** The real runner. Not exercised by host tests. */
    public static final Runner EXEC = new Runner() {
        @Override public Result run(String[] argv) {
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
        }
    };
}
