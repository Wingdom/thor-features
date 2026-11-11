package com.thorfeatures.stickcal;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;

import com.thorfeatures.screenpanel.RootProtocol;

/**
 * Polls {@code raw} continuously in its own root process.
 *
 * Deliberately not run over {@link RootShell}'s command session. That
 * session's framing is one reply per command delimited by a sentinel, and a
 * loop that never ends never emits one - it would wedge the session for the
 * life of the app. Stopping a separate process is also a process kill rather
 * than a half-read stream this app would have to resynchronise.
 *
 * Samples are delivered on the main thread, because they drive a view.
 */
public final class RawStream implements AutoCloseable {

    private static final String TAG = "stickcal";

    public interface Listener {
        void onSample(RawSample s);
        /** Called once if the stream could not be started or died early. */
        void onStreamFailed(String why);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final boolean fractionalSleep;
    /**
     * Started the same way the command session was.
     *
     * Nothing this stream reads lives outside {@code /sys}, so the mount
     * namespace makes no difference to it - but a second way of starting
     * {@code su} is a second thing that can diverge, and the one that already
     * worked is the one to copy.
     */
    private final String[] argv;

    private Process proc;
    private Thread reader;
    private volatile boolean stopping;

    public RawStream(Listener listener, boolean fractionalSleep, String[] argv) {
        this.listener = listener;
        this.fractionalSleep = fractionalSleep;
        this.argv = argv == null || argv.length == 0 ? new String[] { "su" } : argv.clone();
    }

    /** Milliseconds between samples, so callers can reason about rates. */
    public int intervalMs() { return DeviceCommands.intervalMs(fractionalSleep); }

    public boolean start() {
        if (proc != null) return true;
        try {
            Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
            Writer w = new OutputStreamWriter(p.getOutputStream());
            w.write(RootProtocol.prelude());
            w.write(DeviceCommands.streamRaw(fractionalSleep));
            w.write("\n");
            w.flush();
            proc = p;
            stopping = false;
            reader = new Thread(() -> pump(p), "stickcal-raw");
            reader.setDaemon(true);
            reader.start();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not start raw stream: " + e);
            fail("could not start the capture: " + e.getMessage());
            return false;
        }
    }

    private void pump(Process p) {
        int bad = 0, good = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (stopping) return;
                RawSample s = RawSample.parse(line);
                if (s == null) {
                    // One stray line is shell noise and must not end a
                    // capture. A stream that is ALL noise is a denial or a
                    // missing node, and saying so beats a plot that never
                    // moves.
                    if (++bad >= 20 && good == 0) {
                        fail("the joystick node returned nothing usable");
                        return;
                    }
                    continue;
                }
                good++;
                main.post(() -> listener.onSample(s));
            }
        } catch (Exception e) {
            if (!stopping) Log.w(TAG, "raw stream ended: " + e);
        }
        if (!stopping && good == 0) fail("the capture produced no samples");
    }

    private void fail(String why) { main.post(() -> listener.onStreamFailed(why)); }

    @Override
    public void close() {
        stopping = true;
        // Kill the process rather than the pattern. `pkill -f` on a pattern
        // that appears in our own command line matches and kills the caller,
        // which has cost this project three round trips in one session.
        if (proc != null) { proc.destroy(); proc = null; }
        reader = null;
    }
}
