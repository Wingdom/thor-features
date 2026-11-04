package com.thorfeatures.screenpanel;

/**
 * What a tap on the launcher icon does: make sure the button daemon is
 * running, then open the panel.
 *
 * Why this exists at all. The AYN button is delivered by aynkeyd, a root shell
 * daemon, and when that daemon dies the button goes completely dead - no
 * panel, and no long press to turn the bottom screen back on. The only way
 * back was adb, which is no use away from a host. This is the way back from
 * the device itself.
 *
 * It deliberately does NOT reimplement what a press means. The short-press
 * path already exists, as /data/adb/thor-aynkey/action.sh, and it already
 * handles the part that matters - unblank the bottom screen first, then start
 * the panel on the resolved bottom display. Running that same script is what
 * keeps a launcher tap and a button press from drifting apart; building a
 * second "open the panel" path here is exactly the mistake this project
 * already regretted once with screenmove's rules.
 *
 * Pure text in, pure text out, above the RootClient seam - so all of it runs
 * on the host with a fake runner and no device.
 */
public final class Recovery {

    /** The daemon's CLI. `start` is idempotent: it reports an existing one. */
    static final String AYNKEYD = "/system/bin/aynkeyd";

    /**
     * The button's own action script, installed by install.sh. Not a path this
     * app owns - it is the join between thor-aynkey and this app, and running
     * it is the whole point.
     */
    static final String ACTION = "/data/adb/thor-aynkey/action.sh";

    static final String START_COMMAND = AYNKEYD + " start";

    /**
     * The same argument aynkeyd passes for a short press. Not "open the
     * panel": a short press is what the user asked a tap to mean.
     */
    static final String OPEN_COMMAND = ACTION + " short";

    private Recovery() {}

    public enum Outcome {
        /** The daemon was already up; the panel was asked to open. */
        OPENED,
        /** The daemon was dead, was restarted, and the panel was asked to open. */
        RESTARTED_AND_OPENED,
        /** `aynkeyd start` would not run or would not start: no root, or no module. */
        DAEMON_FAILED,
        /** The daemon is up but the action script failed - most likely absent. */
        PANEL_FAILED
    }

    /**
     * Whether `aynkeyd start` had to start a new daemon.
     *
     * Matching is on the daemon's two exact phrasings, and the default is
     * "did not restart". That direction matters: claiming a restart that did
     * not happen would tell the user the daemon had died when it had not,
     * which is the one fact this whole feature exists to report accurately.
     *
     *   aynkeyd: started (pid 8032)
     *   aynkeyd: already running (pid 8032)
     */
    static boolean restarted(String startOut) {
        if (startOut == null) return false;
        if (startOut.contains("already running")) return false;
        return startOut.contains("started (pid");
    }

    /**
     * Run the tap. Two root calls, not one combined command, because a
     * recovery tool that cannot say which half failed is not much of a
     * recovery tool.
     */
    public static Outcome tap(RootClient root) {
        RootClient.Result start = root.run(START_COMMAND);
        if (start.code != 0) return Outcome.DAEMON_FAILED;
        boolean wasDead = restarted(start.out);

        RootClient.Result open = root.run(OPEN_COMMAND);
        if (open.code != 0) return Outcome.PANEL_FAILED;

        return wasDead ? Outcome.RESTARTED_AND_OPENED : Outcome.OPENED;
    }

    /**
     * Whether the outcome is worth saying out loud.
     *
     * A plain OPENED says nothing: the panel appearing on the bottom screen is
     * its own feedback, and a toast on the top screen for a success the user
     * can already see is noise. The other three are all things the user cannot
     * otherwise tell apart from "nothing happened".
     */
    public static boolean needsMessage(Outcome o) { return o != Outcome.OPENED; }
}
