package com.thorfeatures.stickcal;

/**
 * Every shell command this app runs, as pure strings.
 *
 * Separated out so the commands can be asserted on the host. They are the
 * part of a root feature most likely to be wrong and least likely to be
 * noticed: a command that quietly runs somewhere else, or quietly as the
 * wrong user, returns plausible output.
 *
 * <h3>Three rules all of these follow, each from a mistake</h3>
 *
 * <b>One line, always.</b> A multi-line string handed to {@code su -c} never
 * reaches {@code su} at all - it answers {@code sh: -c: requires an argument},
 * exits, and the remainder runs in the calling shell as uid 2000, whose
 * {@code EACCES} looks exactly like an SELinux denial. These go over a
 * persistent session whose framing is one line per command, so the rule is
 * also structural here.
 *
 * <b>Absolute paths, never {@code cd}.</b> {@code cd /dir && cmd} inside a
 * root shell has silently failed the {@code cd} and run the command elsewhere
 * on this device, twice in one session.
 *
 * <b>Root is asserted, not assumed.</b> MagiskSU here intermittently returns
 * non-root shells while {@code init} logs {@code (magiskd) ... state: Z}
 * zombies, so {@link #WHOAMI} runs before anything is concluded from a
 * permission error. The real denials name themselves - this node is labelled
 * {@code vendor_sysfs_joystick}, which denies {@code u:r:shell:s0} but
 * permits {@code u:r:magisk:s0}, so genuine root reads and writes both
 * {@code raw} and {@code calibration}.
 */
public final class DeviceCommands {

    public static final String DIR = "/sys/class/moorechip-joystick/joystick";
    public static final String NODE_CALIBRATION = DIR + "/calibration";
    public static final String NODE_RAW = DIR + "/raw";

    /** Run first in any session. Anything else is read in its light. */
    public static final String WHOAMI = "id -u";

    /**
     * How to start the privileged shell, most capable first.
     *
     * <h3>Why {@code --mount-master} is not optional here</h3>
     *
     * {@code su} inherits the <b>mount namespace of whoever called it</b>, and
     * an Android app's namespace hides every other package's data directory.
     * Measured on this device 2026-11-11: the shell's namespace lists 223
     * entries under {@code /data/user_de/0/}, and the namespace this app runs
     * in lists <b>two</b> - itself and GMS. AynParts is not merely
     * unreadable there, it does not exist, so {@code [ -f <its prefs> ]} is
     * simply false.
     *
     * That failed silently and in the worst possible way: the calibration
     * reached the node and took effect, so everything looked right, while the
     * one write that makes it survive a reboot never happened. There was no
     * SELinux denial to find, because nothing was denied.
     *
     * {@code --mount-master} runs in the global namespace instead, where the
     * path is there. The plain {@code su} fallback exists so that a build
     * without the flag still gets everything that lives in {@code /sys} -
     * which is the whole of the capture and the node write - rather than
     * nothing at all.
     *
     * No other component here needed this, and that is not luck: they only
     * ever touch {@code /sys} and {@code /system} binaries, which an app's
     * namespace shows in full.
     */
    public static String[][] suCandidates() {
        return new String[][] {
            { "su", "--mount-master" },
            { "su" },
        };
    }

    /**
     * Whether this shell can see AynParts at all.
     *
     * Checked once when the session opens, so the app can say up front that a
     * calibration will not survive a reboot - rather than after the user has
     * spent a minute rolling the sticks and tapped Apply.
     */
    public static String canSeeAynParts() {
        return "[ -d /data/user_de/0/" + PrefsXml.PACKAGE + " ] && echo yes || echo no";
    }

    private DeviceCommands() {}

    public static String readCalibration() { return "cat " + NODE_CALIBRATION; }

    public static String readRaw() { return "cat " + NODE_RAW; }

    /**
     * Whether this shell's {@code sleep} takes a fraction of a second.
     *
     * Probed rather than assumed. Toybox's {@code sleep} accepts decimals and
     * the device's does, but a shell whose {@code sleep} rejected
     * {@code 0.05} would fail on every iteration of the capture loop and spin
     * a core instead of sampling - a capture that produces nothing while
     * heating the device. The fallback is the 10 Hz the gate was originally
     * measured at, which is known to work.
     */
    public static String probeFractionalSleep() {
        return "sleep 0.05 >/dev/null 2>&1 && echo frac || echo whole";
    }

    /**
     * Poll {@code raw} forever, one sample per line.
     *
     * {@code read -r L < node} rather than {@code cat}: it is one open and one
     * line, where {@code cat} on a sysfs attribute re-reads to EOF. The loop
     * runs in its own {@code su} process, not on the command session, so that
     * stopping it is a process kill rather than a stream this app has to
     * resynchronise - and so a capture cannot leave the session wedged
     * half-way through a reply.
     */
    public static String streamRaw(boolean fractionalSleep) {
        String delay = fractionalSleep ? "0.05" : "0.1";
        return "while :; do read -r L < " + NODE_RAW
                + "; echo \"$L\"; sleep " + delay + "; done";
    }

    /** Sampling interval the above actually achieves, in milliseconds. */
    public static int intervalMs(boolean fractionalSleep) {
        return fractionalSleep ? 50 : 100;
    }

    /**
     * Write the calibration node and echo back what it reads.
     *
     * The read-back is part of the same command so there is no window
     * between them, and {@code printf} rather than {@code echo} so no
     * trailing newline is appended to a value that is then compared
     * byte-for-byte.
     *
     * A readback that matches is still not proof the calibration is in
     * effect - that is what the verify phase is for, reading the framework's
     * own axis values back through MotionEvent.
     */
    public static String writeCalibration(String value) {
        require(value);
        return "printf '%s' '" + value + "' > " + NODE_CALIBRATION
                + " && cat " + NODE_CALIBRATION;
    }

    /**
     * Read AynParts' preferences, or nothing if it has none yet.
     *
     * Exits 0 either way: a missing file is a normal state, not a failure. It
     * means the panel has never been opened, which was this device's state
     * before the wizard was first run.
     */
    public static String readPrefs() {
        return "[ -f " + PrefsXml.PATH + " ] && cat " + PrefsXml.PATH + "; exit 0";
    }

    /**
     * Stop AynParts so it is not holding its preferences in memory.
     *
     * It caches them, and an in-memory copy written back later would discard
     * ours. It has no persistent service, so this costs nothing: the next time
     * its panel is opened it reads the file fresh.
     */
    public static String forceStopAynParts() {
        return "am force-stop " + PrefsXml.PACKAGE;
    }

    /**
     * Install a staged preferences file as AynParts' own.
     *
     * Ownership and mode are cloned from its data directory rather than
     * hardcoded, because an app's uid is assigned at install time and differs
     * between devices and between installs. {@code restorecon} then supplies
     * the SELinux label, which carries per-app categories that cannot be
     * written down here either.
     *
     * Staged, labelled and only then moved into place, so a failure part way
     * through cannot leave AynParts with a truncated file - that file also
     * holds its RGB configuration, gamepad layout and trigger settings.
     *
     * {@code $P.bak} is removed, and this is not housekeeping.
     * SharedPreferences renames the live file to {@code .bak} before writing
     * a new one and, on load, restores {@code .bak} over the main file if it
     * finds one. A stale backup would therefore undo this write the next time
     * AynParts read its preferences.
     */
    public static String installPrefs(String stagedPath) {
        String app = "/data/user_de/0/" + PrefsXml.PACKAGE;
        String p = PrefsXml.PATH;
        return "[ -d " + app + " ] || exit 20"
                + "; OWN=$(stat -c %u:%g " + app + ") || exit 21"
                + "; mkdir -p " + PrefsXml.DIR + " || exit 22"
                + "; chown $OWN " + PrefsXml.DIR
                + "; chmod 771 " + PrefsXml.DIR
                + "; cat " + stagedPath + " > " + p + ".new || exit 23"
                + "; chown $OWN " + p + ".new"
                + "; chmod 660 " + p + ".new"
                + "; restorecon " + p + ".new 2>/dev/null"
                + "; mv -f " + p + ".new " + p + " || exit 24"
                + "; rm -f " + p + ".bak"
                + "; restorecon " + PrefsXml.DIR + " " + p + " 2>/dev/null"
                + "; cat " + p;
    }

    /** What an {@link #installPrefs} exit code means, for the UI. */
    public static String installPrefsError(int code) {
        switch (code) {
            case 0:  return null;
            case 20: return "AynParts is not installed";
            case 21: return "could not read AynParts' ownership";
            case 22: return "could not create its shared_prefs directory";
            case 23: return "could not write the preferences file";
            case 24: return "could not move the preferences file into place";
            default: return "root command failed (" + code + ")";
        }
    }

    private static void require(String value) {
        if (!Calibration.isSafe(value)) {
            throw new IllegalArgumentException("refusing to shell-quote: " + value);
        }
    }
}
