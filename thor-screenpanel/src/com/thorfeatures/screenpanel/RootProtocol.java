package com.thorfeatures.screenpanel;

/**
 * Framing for a persistent root shell.
 *
 * A long-lived `su` process is fed one command at a time on stdin, so every
 * reply has to be delimited: there is no process exit to mark the end of
 * output, and no exit status unless we ask for one. Each command is therefore
 * wrapped so the shell emits a sentinel line carrying `$?` when it finishes.
 *
 * Why a persistent shell at all, measured on the device 2026-11-01:
 *
 *   separate `su` spawn per command   38 ms
 *   one persistent `su` shell         17 ms
 *
 *   The panel issued six commands on open and took 1109 ms. Spawn cost was
 *   only ~174 ms of that; the rest was the commands themselves, dominated by
 *   `screenblank status` at 581 ms because it walks /sys/class/input twice to
 *   resolve digitisers by name. A persistent shell is worth having for the
 *   spawn saving, but its larger prize is somewhere to *cache* work like that
 *   resolution across calls, which a fresh `su` can never do.
 *
 * Why framing is its own class: it is the only part of the transport that is
 * pure text, so it is the only part that can be tested without a device. The
 * process plumbing in {@link RootSession} is deliberately thin around it.
 */
public final class RootProtocol {

    /**
     * Marks the end of one command's output.
     *
     * Randomised per session rather than fixed. A command that prints the
     * sentinel would otherwise truncate its own reply and desynchronise every
     * command after it - a failure that would look like unrelated commands
     * returning each other's output. A caller cannot predict the value, so it
     * cannot produce it by accident.
     */
    public static String newSentinel() {
        return "__thor_" + Long.toHexString(System.nanoTime())
                + Integer.toHexString((int) (Math.random() * 0xffffff)) + "__";
    }

    private RootProtocol() {}

    /**
     * Wrap a command so the shell reports its end and its exit status.
     *
     * The command runs in a subshell so that a `cd`, a `set -e` or an `exit`
     * inside it cannot alter or kill the session shell. stdin is closed for
     * the command: a child that reads stdin would otherwise eat the next
     * command queued behind it, which is the shell-scripting equivalent of the
     * `adb` stdin bug this project already documents.
     */
    public static String wrap(String command, String sentinel) {
        return "( " + command + " ) </dev/null 2>&1; echo \"" + sentinel + " $?\"\n";
    }

    /** True when a line is this session's end-of-reply marker. */
    public static boolean isSentinel(String line, String sentinel) {
        return line != null && line.startsWith(sentinel + " ");
    }

    /**
     * Exit status carried by a sentinel line, or -1 if it cannot be read.
     *
     * -1 rather than 0: an unreadable status must never be mistaken for
     * success, because callers treat 0 as "the command worked".
     */
    public static int exitCodeOf(String sentinelLine, String sentinel) {
        if (!isSentinel(sentinelLine, sentinel)) return -1;
        String tail = sentinelLine.substring(sentinel.length() + 1).trim();
        try {
            int v = Integer.parseInt(tail);
            return v < 0 ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * The command out of a `su -c <command>` argv, or null if the argv is not
     * that exact shape.
     *
     * Over a session the `su -c` is already implied, so only the command
     * itself is sent. Refusing anything else is deliberate: an unrecognised
     * argv falls back to a real `su` rather than being silently
     * reinterpreted as something else.
     */
    public static String commandOf(String[] argv) {
        if (argv == null || argv.length != 3) return null;
        if (!"su".equals(argv[0]) || !"-c".equals(argv[1])) return null;
        return argv[2];
    }

    /**
     * The shell run once when a session opens.
     *
     * Two jobs. It makes the session's own behaviour predictable, and it is
     * the hook for caching work that is otherwise repeated on every call -
     * resolving a device node by name, for instance, which costs 138 ms each
     * time it is walked.
     *
     * Kept deliberately small. Anything expensive here is paid on every panel
     * open, which is the latency this transport exists to reduce.
     */
    public static String prelude() {
        return "export PATH=/system/bin:/system/xbin:$PATH\n"
             + "export LC_ALL=C\n"
             + "umask 022\n";
    }
}
