package com.thorfeatures.stickfx;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Thin wrapper over the stickfx binary installed by the Magisk module at
 * /system/bin/stickfx.
 *
 * The command runner is injectable so the whole class can be exercised on the
 * host with a fake, with no device and no root. The real runner is the only
 * part that touches Runtime.exec.
 */
public class StickfxClient {

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

    public static final String BINARY = "/system/bin/stickfx";

    /**
     * AynParts' RGB panel. exported=false, so it cannot be started by an
     * ordinary app -- root `am start` is the way in. Verified on device
     * 2026-10-22.
     */
    public static final String RGB_PANEL =
            "org.lineageos.settings.ayn/.lights.RGBLightingSettingsActivity";

    private final Runner runner;

    public StickfxClient(Runner runner) { this.runner = runner; }

    /**
     * Build the argv for a stickfx invocation under root.
     *
     * Pure and unit-tested. Arguments come from StickMode.wire() or a fixed
     * literal, never from user input, so there is nothing to escape -- and the
     * tests assert that no shell metacharacter can reach this.
     */
    static String[] suArgv(String stickfxArg) {
        return new String[] { "su", "-c", BINARY + " " + stickfxArg };
    }

    /** argv to open AynParts' RGB panel under root. */
    static String[] rgbPanelArgv() {
        return new String[] { "su", "-c", "am start -n " + RGB_PANEL };
    }

    /** Current mode, or OFF if stickfx is missing or fails. */
    public StickMode getMode() {
        Result r = runner.run(suArgv("get"));
        if (r.code != 0) return StickMode.OFF;
        return StickMode.fromString(r.out);
    }

    /** Apply a mode. Returns true only on a clean exit. */
    public boolean apply(StickMode mode) {
        return runner.run(suArgv(mode.wire())).code == 0;
    }

    /** True when the stickfx binary responds -- module installed and root granted. */
    public boolean available() {
        return runner.run(suArgv("get")).code == 0;
    }

    /** Open AynParts' RGB panel. Colour is AynParts' business, not ours. */
    public boolean openRgbPanel() {
        return runner.run(rgbPanelArgv()).code == 0;
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
