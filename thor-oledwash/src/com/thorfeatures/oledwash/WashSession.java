package com.thorfeatures.oledwash;

import java.util.ArrayList;
import java.util.List;

/**
 * One stop signal shared by every panel's wash.
 *
 * <p>The wash runs as a separate Activity on each panel, and this device has
 * {@code config_perDisplayFocusEnabled = true}, so each one independently
 * receives its own key and motion events. That is what makes "stop on any
 * interaction" reachable at all -- but it also means a button press is seen by
 * one Activity and has to end both. They share a process, so a static rendezvous
 * is the whole of what is needed; no broadcast, no service, no IPC.
 *
 * <p>Deliberately free of Android imports so the fan-out and the
 * stop-once semantics are host-testable. Listeners are called on whatever
 * thread calls {@link #stop}, which will be an input thread -- every listener
 * here is responsible for getting itself back onto its own UI thread.
 */
public final class WashSession {

    /** Told once, when the wash should end. */
    public interface Listener {
        void onWashStopped(String reason);
    }

    public static final String REASON_INPUT = "input";
    public static final String REASON_COMPLETE = "complete";
    public static final String REASON_BACKGROUNDED = "backgrounded";
    public static final String REASON_SCREEN_OFF = "screen off";

    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static boolean running = false;
    private static String reason = null;

    private WashSession() {}

    public static synchronized void start() {
        running = true;
        reason = null;
    }

    public static synchronized boolean isRunning() {
        return running;
    }

    /** Why the last wash ended, or null if it has not. */
    public static synchronized String reason() {
        return reason;
    }

    public static synchronized void register(Listener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static synchronized void unregister(Listener l) {
        LISTENERS.remove(l);
    }

    public static synchronized int listenerCount() {
        return LISTENERS.size();
    }

    /**
     * End the wash. The first call wins and later ones are ignored, so the
     * recorded reason is the thing that actually stopped it rather than
     * whichever panel finished unwinding last.
     */
    public static synchronized void stop(String why) {
        if (!running) return;
        running = false;
        reason = why;
        // Copy: a listener is very likely to unregister itself from inside this.
        for (Listener l : new ArrayList<>(LISTENERS)) {
            l.onWashStopped(why);
        }
    }

    /** Test seam only. Drops every listener and clears the state. */
    public static synchronized void resetForTests() {
        LISTENERS.clear();
        running = false;
        reason = null;
    }
}
