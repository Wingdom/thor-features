package com.thorfeatures.oledwash;

import android.app.Activity;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * The wash, on one panel. One instance per panel.
 *
 * <p>An ordinary Activity, which is the whole trick. The stock feature covers
 * the status and navigation bars, and this project already established in
 * October that {@code TYPE_APPLICATION_OVERLAY} cannot outrank them -- an
 * overlay was built, tested and rejected for exactly that. A fullscreen
 * Activity in immersive mode hides them with no privilege at all.
 *
 * <p><b>It deliberately does not hold the screen awake.</b> No
 * {@code FLAG_KEEP_SCREEN_ON}, no wakelock. The design is that this runs in a
 * window the device was already awake for, and never extends it. If the screen
 * goes off underneath, the wash ends.
 */
public final class WashActivity extends Activity implements WashSession.Listener {

    public static final String EXTRA_BAND = "band";
    public static final String EXTRA_CYCLE_MS = "cycleMs";
    public static final String EXTRA_CYCLES = "cycles";
    public static final String EXTRA_GRAIN = "grain";
    public static final String EXTRA_DIAGNOSTICS = "diagnostics";

    /**
     * Normalised stick deflection that counts as "somebody touched it".
     * The framework already applies the pad's own flat zone (500 of 32767 on
     * this unit, so a resting stick reports about 0.004), but a calibration can
     * leave a small standing offset and a wash that quit the instant it opened
     * would be a mystery rather than a feature.
     */
    private static final float STICK_THRESHOLD = 0.15f;
    private static final float TRIGGER_THRESHOLD = 0.10f;

    private WashView view;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        double band = getIntent().getDoubleExtra(EXTRA_BAND, Params.DEFAULT_BAND);
        long cycleMs = getIntent().getLongExtra(EXTRA_CYCLE_MS, Params.DEFAULT_CYCLE_MS);
        int cycles = getIntent().getIntExtra(EXTRA_CYCLES, Params.DEFAULT_CYCLES);
        int grain = getIntent().getIntExtra(EXTRA_GRAIN, Params.DEFAULT_GRAIN);
        boolean diag = getIntent().getBooleanExtra(EXTRA_DIAGNOSTICS, true);

        view = new WashView(this, band, cycleMs, cycles, grain, diag,
                () -> endWash(WashSession.REASON_COMPLETE));
        setContentView(view);
        goImmersive();

        // Join first, then register. A wash started by anything other than
        // MainActivity -- a root `am start`, which is exactly how the idle
        // trigger will do it -- would otherwise have no running session.
        WashSession.joinOrStart();
        WashSession.register(this);
    }

    /**
     * End the wash: tell the other panels, and finish this one regardless.
     *
     * <p>The second half is not belt-and-braces, it is the fix. Finishing used
     * to be conditional on {@link WashSession#stop} having something to stop,
     * so a wash with no session swallowed every input and could not be
     * dismissed at all.
     */
    private void endWash(String reason) {
        WashSession.stop(reason);
        finish();
    }

    private void goImmersive() {
        // No setDecorFitsSystemWindows: it is deprecated as of API 35, and an
        // app targeting 35 or later is already laid out edge to edge. The
        // insets controller is what actually hides the bars.
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        // Stated rather than assumed: this must never extend the awake window.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View decor = getWindow().getDecorView();
        decor.setKeepScreenOn(false);
    }

    // ---------------------------------------------------------------- input --
    // Any of these ends the wash on every panel, not just this one.

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        endWash(WashSession.REASON_INPUT);
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        endWash(WashSession.REASON_INPUT);
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (isDeliberateStickMotion(e)) {
            endWash(WashSession.REASON_INPUT);
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    /** Sticks, hats and triggers, past a threshold that resting jitter cannot reach. */
    static boolean isDeliberateStickMotion(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) return false;
        if (e.getAction() != MotionEvent.ACTION_MOVE) return false;

        float[] sticks = {
                e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
                e.getAxisValue(MotionEvent.AXIS_Z), e.getAxisValue(MotionEvent.AXIS_RZ),
                e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y),
        };
        for (float v : sticks) {
            if (Math.abs(v) >= STICK_THRESHOLD) return true;
        }
        float[] triggers = {
                e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                e.getAxisValue(MotionEvent.AXIS_BRAKE), e.getAxisValue(MotionEvent.AXIS_GAS),
        };
        for (float v : triggers) {
            if (Math.abs(v) >= TRIGGER_THRESHOLD) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ lifecycle --

    @Override
    public void onWashStopped(String reason) {
        runOnUiThread(this::finish);
    }

    /**
     * {@code onStop}, not {@code onPause}, and the distinction is load-bearing.
     *
     * <p>Two of these run at once, one per panel. Launching the second can
     * briefly disturb the first, and a pause-triggered stop would race that
     * launch and kill the wash the instant it started. {@code onStop} means
     * genuinely no longer visible -- screen off, or something else took the
     * panel -- which is the condition actually worth quitting for.
     */
    @Override
    protected void onStop() {
        super.onStop();
        WashSession.stop(WashSession.REASON_BACKGROUNDED);
    }

    @Override
    protected void onDestroy() {
        WashSession.unregister(this);
        super.onDestroy();
    }
}
