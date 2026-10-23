package com.thorfeatures.screenpanel;

import java.util.ArrayList;
import java.util.List;

/**
 * The three screen modes stock's "Mode" tab offered, and the ordered plan of
 * operations that moves between them.
 *
 * Stock's Mode tab offered the same three, in this order and with this
 * wording:
 *
 *   "Dual-screen display"
 *   "Only top screen"
 *   "Only bottom screen"
 *
 * Why a plan object instead of just doing it:
 *
 *   The ordering is the part that can go wrong, and it can go wrong in a way
 *   the user experiences as losing an app. Blacking out a screen that still
 *   has the foreground app on it hides that app behind an opaque window; the
 *   app is still running and still focused, so touches land on the overlay and
 *   nothing appears to work. So moves must happen *before* covers, and
 *   uncovers must happen *before* moves when coming back. That is pure
 *   sequencing logic with no Android in it, which makes it host-testable -
 *   and this project requires that for exactly this kind of state machine.
 *
 *   It also means every transition is computed from the *recorded* current
 *   state rather than applied incrementally, which is the lesson thor-fanctl
 *   paid for: a plan derived from current values drifts on re-application.
 *   Re-applying a mode here is idempotent by construction.
 */
public final class ScreenMode {

    /** The main 1920x1080 panel. Always the default display. */
    public static final int TOP = 0;

    public enum Mode {
        /** Both panels visible. The stock default. */
        DUAL,
        /** Only the main panel; the bottom one is powered down. */
        TOP_ONLY,
        /** Only the 3.92" bottom panel; the main one is powered down. */
        BOTTOM_ONLY,
    }

    /** One step of a transition. Executed in list order. */
    public static final class Op {
        public enum Kind {
            /** `screenblank off` - bl_power=1 and the digitiser IRQ off. */
            BLANK,
            /** `screenblank on` - bl_power=0 and the digitiser IRQ back on. */
            UNBLANK,
            /** `screenmove to-top` - pull the foreground app off the bottom. */
            MOVE_TO_TOP,
            /** `screenmove to-bottom` - push the foreground app down. */
            MOVE_TO_BOTTOM,
        }

        public final Kind kind;
        /** Display id for BLANK/UNBLANK; -1 for the move ops. */
        public final int display;

        Op(Kind kind, int display) { this.kind = kind; this.display = display; }

        @Override public String toString() {
            return kind == Kind.BLANK || kind == Kind.UNBLANK
                    ? kind + ":" + display
                    : kind.toString();
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Op)) return false;
            Op other = (Op) o;
            return kind == other.kind && display == other.display;
        }

        @Override public int hashCode() { return kind.hashCode() * 31 + display; }
    }

    private ScreenMode() {}

    /**
     * Which mode a given pair of blanked panels represents.
     *
     * Anything that is not exactly "nothing dark", "bottom dark" or "top dark"
     * is reported as DUAL, because DUAL is the only mode whose plan unblanks
     * everything - so an unexpected state heals on the next
     * mode selection instead of persisting.
     */
    public static Mode current(boolean topDark, boolean bottomDark) {
        if (topDark && bottomDark) return Mode.DUAL;   // nonsense state; heal to DUAL
        if (bottomDark) return Mode.TOP_ONLY;
        if (topDark) return Mode.BOTTOM_ONLY;
        return Mode.DUAL;
    }

    /**
     * The ordered operations to reach {@code target} from the current blanked
     * state. Empty when already there.
     *
     * @param bottom the bottom display's id, resolved at runtime rather than
     *               assumed to be 1 - display ids are assigned at boot.
     */
    public static List<Op> plan(Mode target, boolean topDark, boolean bottomDark, int bottom) {
        List<Op> ops = new ArrayList<>();

        // Unblank first, always. Two reasons: a screen that is about to host
        // the app must be lit before the app arrives, and a panel left dark
        // and touch-dead would make the result unreachable.
        if (target != Mode.TOP_ONLY && bottomDark) ops.add(new Op(Op.Kind.UNBLANK, bottom));
        if (target != Mode.BOTTOM_ONLY && topDark) ops.add(new Op(Op.Kind.UNBLANK, TOP));

        switch (target) {
            case DUAL:
                break;

            case TOP_ONLY:
                // Rescue whatever is on the bottom screen, then blank it.
                // The move is bound to the blank, not emitted unconditionally:
                // it exists only to get an app off a screen that is *about to*
                // go dark, so if the bottom is already dark there is
                // nothing to rescue. Issuing it anyway would re-front a
                // backgrounded app every time the mode was re-selected, since
                // `am display move-stack` is not transparent - it brings the
                // task to the foreground.
                if (!bottomDark) {
                    ops.add(new Op(Op.Kind.MOVE_TO_TOP, -1));
                    ops.add(new Op(Op.Kind.BLANK, bottom));
                }
                break;

            case BOTTOM_ONLY:
                if (!topDark) {
                    ops.add(new Op(Op.Kind.MOVE_TO_BOTTOM, -1));
                    ops.add(new Op(Op.Kind.BLANK, TOP));
                }
                break;
        }

        return ops;
    }

    /** The blanked state a mode implies, as {top, bottom}. */
    public static boolean[] darkFor(Mode m) {
        switch (m) {
            case TOP_ONLY:    return new boolean[] { false, true  };
            case BOTTOM_ONLY: return new boolean[] { true,  false };
            case DUAL:
            default:          return new boolean[] { false, false };
        }
    }

    /**
     * The mode the AYN button's long press selects, given where we are.
     *
     * Stock bound the long press to toggling the bottom screen, and that is
     * also the way back when the bottom screen is dark - which is why it has
     * to be a toggle rather than a fixed mode. From BOTTOM_ONLY it returns to
     * DUAL rather than to TOP_ONLY, because the alternative blanks the screen
     * you are currently looking at.
     */
    public static Mode longPressTarget(Mode from) {
        switch (from) {
            case DUAL:        return Mode.TOP_ONLY;    // bottom off
            case TOP_ONLY:    return Mode.DUAL;        // bottom back on
            case BOTTOM_ONLY: return Mode.DUAL;        // never blank what you are looking at
            default:          return Mode.DUAL;
        }
    }
}
