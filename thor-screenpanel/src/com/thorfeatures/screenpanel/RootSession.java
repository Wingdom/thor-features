package com.thorfeatures.screenpanel;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * A persistent root shell, used as a {@link RootClient.Runner}.
 *
 * One `su` process is opened on first use and kept for the life of the panel,
 * with commands written to its stdin and replies read back delimited by a
 * per-session sentinel. This is the panel's "root daemon": it is a daemon in
 * the sense that matters - a long-lived privileged process that holds state -
 * without a socket, an SELinux policy or a new way for other apps to reach
 * root. Magisk authorises this app once, exactly as it does today.
 *
 * <h3>It always falls back</h3>
 *
 * Every failure path returns to {@link RootClient#EXEC}, the one-shot `su`
 * this replaced. A dead session therefore degrades the panel to its previous
 * behaviour - slower, no live drag - rather than breaking it. That matters
 * because a shared privileged process is otherwise a single point of failure
 * for every control at once, and this project has already had a root daemon
 * die silently and go unnoticed (aynkeyd, which left the AYN button working
 * exactly once per boot).
 *
 * <h3>Adding features</h3>
 *
 * Nothing here knows what the panel does. Commands are plain shell strings, so
 * a new feature needs no change to this class: build its command, hand it to
 * {@link RootClient}, and it runs over the same session. If a feature needs
 * expensive setup repeated on every call - resolving a device node by name,
 * say - cache it in {@link RootProtocol#prelude()} and read the variable back,
 * which is the one thing a one-shot `su` can never do.
 *
 * <h3>Threading</h3>
 *
 * A session is one shell with one stdin and one stdout, so two commands in
 * flight would interleave. Every call is serialised on this object. The panel
 * already issues its work from a single-threaded executor; the lock is there
 * so that a future caller which does not cannot corrupt the stream.
 */
public final class RootSession implements RootClient.Runner, AutoCloseable {

    private static final String TAG = "screenpanel";

    /**
     * How long to wait for one command's reply before giving up on the
     * session. Generous: `screenblank status` measured 581 ms on this device,
     * and a cold `cmd display` call can be slower still. This is a deadlock
     * guard, not a performance budget.
     */
    private static final long TIMEOUT_MS = 10_000;

    private final Object lock = new Object();

    private Process proc;
    private Writer in;
    private BufferedReader out;
    private String sentinel;
    /** Set once the session has failed; it is not retried for this instance. */
    private boolean dead;

    /** Opens on first use. Never throws: a failure just means fallback. */
    private boolean ensureOpen() {
        if (dead) return false;
        if (proc != null && proc.isAlive()) return true;

        close();
        dead = false;
        try {
            sentinel = RootProtocol.newSentinel();
            Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
            Writer w = new OutputStreamWriter(p.getOutputStream());
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));

            w.write(RootProtocol.prelude());
            w.flush();

            proc = p; in = w; out = r;

            // Prove the shell is actually root and actually answering before
            // trusting it. `su` can start and then be denied, in which case
            // the process exists but no command ever runs.
            RootClient.Result probe = exchange("id -u");
            if (probe.code != 0 || !probe.out.trim().equals("0")) {
                Log.w(TAG, "root session did not come up as uid 0 (" + probe.out.trim()
                        + "); falling back to one-shot su");
                close();
                dead = true;
                return false;
            }
            Log.i(TAG, "root session open");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not open root session: " + e + "; falling back to one-shot su");
            close();
            dead = true;
            return false;
        }
    }

    /** Write one command and read its reply. Caller holds the lock. */
    private RootClient.Result exchange(String command) throws Exception {
        in.write(RootProtocol.wrap(command, sentinel));
        in.flush();

        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("timed out waiting for: " + command);
            }
            String line = out.readLine();
            if (line == null) throw new IllegalStateException("root session closed mid-command");
            if (RootProtocol.isSentinel(line, sentinel)) {
                return new RootClient.Result(RootProtocol.exitCodeOf(line, sentinel), sb.toString());
            }
            sb.append(line).append('\n');
        }
    }

    @Override
    public RootClient.Result run(String[] argv) {
        // RootClient hands out `su -c <command>`; over a session the `su -c`
        // is already implied, so only the command itself is sent.
        String command = RootProtocol.commandOf(argv);
        if (command == null) return RootClient.EXEC.run(argv);

        synchronized (lock) {
            if (ensureOpen()) {
                try {
                    return exchange(command);
                } catch (Exception e) {
                    // One failure retires the session rather than retrying on
                    // it: after a timeout or a mid-command close, the stream's
                    // position is unknown and the next reply could belong to
                    // the previous command.
                    Log.w(TAG, "root session failed (" + e + "); falling back to one-shot su");
                    close();
                    dead = true;
                }
            }
        }
        return RootClient.EXEC.run(argv);
    }

    /** True when commands are currently going over the session. For logging. */
    public boolean isLive() {
        synchronized (lock) { return !dead && proc != null && proc.isAlive(); }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try { if (in != null) { in.write("exit\n"); in.flush(); } } catch (Exception ignored) { }
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            if (proc != null) proc.destroy();
            proc = null; in = null; out = null;
        }
    }
}
