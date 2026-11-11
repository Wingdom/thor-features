package com.thorfeatures.stickcal;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;

import com.thorfeatures.screenpanel.RootProtocol;

/**
 * A persistent root shell for the one-shot commands.
 *
 * Framing comes from {@link RootProtocol}, which is thor-screenpanel's and is
 * compiled in rather than copied: it is one behaviour - how a long-lived
 * {@code su} process is fed commands and told where each reply ends - and a
 * second copy of it would be a second thing to keep right. The panel's
 * version is installed, in daily use and host-tested.
 *
 * <h3>Root is asserted before anything is concluded</h3>
 *
 * {@code su} can start and then be denied, leaving a live process that never
 * runs a command. Worse, MagiskSU on this device intermittently returns
 * genuinely non-root shells while {@code init} logs {@code (magiskd) ...
 * state: Z} zombies - which made a session look like an SELinux boundary
 * because {@code grep} succeeded where {@code ls} failed on the same file. So
 * {@code id -u} runs first and its answer, not the absence of an error, is
 * what makes the session usable.
 *
 * <h3>Magisk 31 never prompts</h3>
 *
 * Magisk 31.0 removed the interactive su request and flipped the default
 * policy to Deny. There is no dialog to wait for and no prompt to miss: an
 * app that has not been added in the Magisk app's Superuser tab is simply
 * refused, in the same millisecond, with nothing shown to the user. That is
 * why {@link #denied()} is reported as a first-class state with instructions
 * rather than as a transient failure.
 */
public final class RootShell implements AutoCloseable {

    private static final String TAG = "stickcal";
    /** Generous. This is a deadlock guard, not a performance budget. */
    private static final long TIMEOUT_MS = 10_000;

    public static final class Result {
        public final int code;
        public final String out;
        Result(int code, String out) { this.code = code; this.out = out == null ? "" : out; }
        public boolean ok() { return code == 0; }
        public String trimmed() { return out.trim(); }
    }

    private final Object lock = new Object();
    private Process proc;
    private Writer in;
    private BufferedReader out;
    private String sentinel;
    private boolean denied;
    private boolean fractionalSleep = true;
    private boolean canSeeAynParts;
    private String[] argv;

    /** True once su has been tried and did not give us uid 0. */
    public boolean denied() { synchronized (lock) { return denied; } }

    /** Whether this shell's sleep takes fractions of a second. */
    public boolean fractionalSleep() { synchronized (lock) { return fractionalSleep; } }

    /**
     * Whether AynParts' data directory is reachable from this shell.
     *
     * False means the calibration can be applied but cannot be made to
     * survive a reboot, which the user is told before they start rather than
     * after they have rolled the sticks and tapped Apply.
     */
    public boolean canSeeAynParts() { synchronized (lock) { return canSeeAynParts; } }

    /** How this shell was started, so a stream can match it. */
    public String[] argv() { synchronized (lock) { return argv == null ? null : argv.clone(); } }

    public boolean open() {
        synchronized (lock) {
            if (proc != null && proc.isAlive()) return true;
            if (denied) return false;
            closeLocked();
            // Most capable first. A build whose su lacks --mount-master still
            // gets everything that lives in /sys - which is the whole capture
            // and the node write - rather than no root at all.
            for (String[] candidate : DeviceCommands.suCandidates()) {
                if (tryOpen(candidate)) return true;
                closeLocked();
            }
            denied = true;
            return false;
        }
    }

    /** One attempt at a privileged shell. Caller holds the lock. */
    private boolean tryOpen(String[] candidate) {
        try {
            sentinel = RootProtocol.newSentinel();
            Process p = new ProcessBuilder(candidate).redirectErrorStream(true).start();
            Writer w = new OutputStreamWriter(p.getOutputStream());
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            w.write(RootProtocol.prelude());
            w.flush();
            proc = p; in = w; out = r;

            Result id = exchange(DeviceCommands.WHOAMI);
            if (!id.ok() || !"0".equals(id.trimmed())) {
                Log.w(TAG, String.join(" ", candidate) + " did not yield uid 0: '"
                        + id.trimmed() + "'");
                return false;
            }
            // Probed once per session rather than assumed: a sleep that
            // rejected 0.05 would fail every iteration of the capture loop
            // and spin a core instead of sampling.
            fractionalSleep = "frac".equals(
                    exchange(DeviceCommands.probeFractionalSleep()).trimmed());
            // And this one because an app's mount namespace hides other
            // packages' data directories, so a shell can be genuinely root
            // and still not see the file that makes a calibration persist.
            canSeeAynParts = "yes".equals(
                    exchange(DeviceCommands.canSeeAynParts()).trimmed());
            argv = candidate.clone();
            Log.i(TAG, "root session open via '" + String.join(" ", candidate)
                    + "', fractional sleep=" + fractionalSleep
                    + ", AynParts visible=" + canSeeAynParts);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not open root session via '"
                    + String.join(" ", candidate) + "': " + e);
            return false;
        }
    }

    /** Run one command. A failure retires the session rather than retrying. */
    public Result run(String command) {
        synchronized (lock) {
            if (!open()) return new Result(-1, "no root");
            try {
                return exchange(command);
            } catch (Exception e) {
                // After a timeout or a mid-command close the stream position
                // is unknown, and the next reply could belong to the previous
                // command.
                Log.w(TAG, "root command failed (" + e + ")");
                closeLocked();
                return new Result(-1, String.valueOf(e.getMessage()));
            }
        }
    }

    private Result exchange(String command) throws Exception {
        in.write(RootProtocol.wrap(command, sentinel));
        in.flush();
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("timed out: " + command);
            }
            String line = out.readLine();
            if (line == null) throw new IllegalStateException("session closed mid-command");
            if (RootProtocol.isSentinel(line, sentinel)) {
                return new Result(RootProtocol.exitCodeOf(line, sentinel), sb.toString());
            }
            sb.append(line).append('\n');
        }
    }

    @Override
    public void close() { synchronized (lock) { closeLocked(); } }

    private void closeLocked() {
        try { if (in != null) { in.write("exit\n"); in.flush(); } } catch (Exception ignored) { }
        try { if (in != null) in.close(); } catch (Exception ignored) { }
        try { if (out != null) out.close(); } catch (Exception ignored) { }
        if (proc != null) proc.destroy();
        proc = null; in = null; out = null;
    }
}
