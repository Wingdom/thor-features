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

    /**
     * Join the running wash, or begin one if there is not already.
     *
     * <p>Every panel's Activity calls this for itself, so that a wash is
     * properly formed however it was launched. The alternative -- having
     * whoever launches the wash call {@link #start} first -- was the original
     * design and it was wrong: a {@code WashActivity} started any other way,
     * which is precisely what a root {@code am start} does, then had no running
     * session, so {@link #stop} was a no-op and the wash could neither finish
     * nor be dismissed. See the scratchpad entry for 2026-11-23.
     *
     * @return true if this call began the session rather than joining one
     */
    public static synchronized boolean joinOrStart() {
        if (running) return false;
        running = true;
        reason = null;
        return true;
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
     *
     * <p><b>Returning false does not excuse a caller from ending itself.</b>
     * This tells the <i>other</i> panels to stop; an Activity finishing itself
     * must not be conditional on it, which is what made the no-session case
     * unrecoverable.
     *
     * @return true if this call ended a running wash
     */
    public static synchronized boolean stop(String why) {
        if (!running) return false;
        running = false;
        reason = why;
        // Copy: a listener is very likely to unregister itself from inside this.
        for (Listener l : new ArrayList<>(LISTENERS)) {
            l.onWashStopped(why);
        }
        return true;
    }

    /** Test seam only. Drops every listener and clears the state. */
    public static synchronized void resetForTests() {
        LISTENERS.clear();
        running = false;
        reason = null;
    }
}
