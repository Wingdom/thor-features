package com.thorfeatures.appprofiles;

/**
 * The exact commands this app sends to root, and the parsing of what comes
 * back.
 *
 * Every command is built from fixed literals plus, for the table write, a path
 * this app owns. Nothing a user types and nothing read back off the device ever
 * reaches a command string -- the same rule the Quick Settings tiles follow,
 * with a test asserting no shell metacharacter can get through.
 *
 * Writing the table deserves a note. The obvious approach -- interpolating the
 * table's contents into a `su -c "echo ... > file"` -- would put package names
 * inside a shell string. Instead the app writes the new table to its own
 * private file and asks root only to copy it into place, so the command line
 * carries two fixed paths and no content at all.
 *
 * Pure logic -- no Android dependencies -- so it is unit-tested on the host.
 */
public final class AppwatchCommands {

    private AppwatchCommands() {}

    /** Installed by the thor-appwatch Magisk module. */
    public static final String APPWATCH = "/system/bin/appwatch";
    /** Installed by the thor-fanctl Magisk module. */
    public static final String FANCTL = "/system/bin/fanctl";
    /** The profile table the daemon reads. */
    public static final String TABLE = "/data/adb/thor-appwatch/profiles";

    public static final String STATUS      = APPWATCH + " status";
    public static final String ENABLE      = APPWATCH + " enable";
    public static final String DISABLE     = APPWATCH + " disable";
    /**
     * Read the table, treating a missing file as an EMPTY table rather than a
     * failure.
     *
     * A plain `cat` exits 1 when the file does not exist, and the module
     * creates its state directory without a profiles file until something
     * writes one -- so a fresh install read as "could not read", which makes
     * the app refuse to save. The guard distinguishes the two cases that
     * actually differ: missing (exit 0, no output, an empty table) from
     * present-but-unreadable (cat fails, exit non-zero, refuse to save).
     */
    public static final String READ_TABLE =
            "if [ -e " + TABLE + " ]; then cat " + TABLE + "; fi";
    public static final String FANCTL_GET  = FANCTL + " get";

    /** `su -c <command>`. The command is always one of the fixed strings here. */
    public static String[] suArgv(String command) {
        return new String[] { "su", "-c", command };
    }

    /**
     * Copy a file this app wrote into the table's place, creating the state
     * directory if the module has not yet.
     *
     * @throws IllegalArgumentException if the path is not a plain absolute path.
     *         This is the only command built from a non-literal, so it is the
     *         only one that needs checking.
     */
    public static String writeTable(String stagedPath) {
        requirePlainPath(stagedPath);
        return "mkdir -p /data/adb/thor-appwatch"
             + " && cat " + stagedPath + " > " + TABLE
             + " && chmod 600 " + TABLE;
    }

    /** Absolute, and free of anything a shell would treat as syntax. */
    static void requirePlainPath(String p) {
        if (p == null || p.isEmpty() || p.charAt(0) != '/') {
            throw new IllegalArgumentException("not an absolute path: " + p);
        }
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                      || (c >= '0' && c <= '9')
                      || c == '/' || c == '.' || c == '_' || c == '-';
            if (!ok) throw new IllegalArgumentException("unsafe character in path: " + p);
        }
        if (p.contains("..")) throw new IllegalArgumentException("path traversal: " + p);
    }

    /**
     * Whether the master switch is on, from `appwatch status`.
     *
     * Defaults to true when the line is missing: the module enables itself at
     * install time, so absent output means "cannot tell", and showing the
     * switch off when it is actually on would invite the user to turn it "on"
     * and change nothing.
     */
    public static boolean parseEnabled(String statusOut) {
        String v = field(statusOut, "enabled:");
        if (v == null) return true;
        return !v.equalsIgnoreCase("no");
    }

    /** Whether the daemon is running, from `appwatch status`. */
    public static boolean parseDaemonRunning(String statusOut) {
        String v = field(statusOut, "daemon:");
        return v != null && v.toLowerCase().startsWith("running");
    }

    /** The baseline tier `appwatch status` reports, or null when none is held. */
    public static String parseBaseline(String statusOut) {
        String v = field(statusOut, "baseline:");
        return (v == null || v.isEmpty()) ? null : v;
    }

    private static String field(String out, String label) {
        if (out == null) return null;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (!t.startsWith(label)) continue;
            return t.substring(label.length()).trim();
        }
        return null;
    }
}
