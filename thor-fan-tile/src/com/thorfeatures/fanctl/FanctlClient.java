package com.thorfeatures.fanctl;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Thin wrapper over the fanctl binary installed by the Magisk module at
 * /system/bin/fanctl.
 *
 * The command runner is injectable so the whole class can be exercised on the
 * host with a fake, with no device and no root. The real runner is the only
 * part that touches Runtime.exec.
 */
public class FanctlClient {

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

    public static final String BINARY = "/system/bin/fanctl";

    private final Runner runner;

    public FanctlClient(Runner runner) { this.runner = runner; }

    /**
     * Build the argv for a fanctl invocation under root.
     *
     * Pure and unit-tested. Arguments come from FanMode.wire() or a fixed
     * literal, never from user input, so there is nothing to escape -- and the
     * tests assert that no shell metacharacter can reach this.
     */
    static String[] suArgv(String fanctlArg) {
        return new String[] { "su", "-c", BINARY + " " + fanctlArg };
    }

    /** Current mode, or DEFAULT if fanctl is missing or fails. */
    public FanMode getMode() {
        Result r = runner.run(suArgv("get"));
        if (r.code != 0) return FanMode.STOCK;
        return FanMode.fromString(r.out);
    }

    /** Apply a mode. Returns true only on a clean exit. */
    public boolean apply(FanMode mode) {
        return runner.run(suArgv(mode.wire())).code == 0;
    }

    /** True when the fanctl binary responds -- i.e. the module is installed and root granted. */
    public boolean available() {
        return runner.run(suArgv("get")).code == 0;
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
