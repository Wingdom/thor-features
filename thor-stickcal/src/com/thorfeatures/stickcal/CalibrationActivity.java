package com.thorfeatures.stickcal;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The wizard: measure both gates, solve the limits, write them, check them.
 *
 * All the arithmetic lives in {@link GateSolver}, {@link GateMap},
 * {@link RestAnalysis} and {@link Report}, which are host-tested. What is
 * here is sequencing, root plumbing and drawing - the parts that need a
 * device anyway.
 *
 * <h3>Why the rolls come before the centre is known</h3>
 *
 * The centre is the midpoint of the hysteresis range, and the far end of that
 * range can only be measured after the sticks have been deflected. So the
 * rolls are accumulated against the cold rest position and the gates are
 * re-based through {@link GateMap#shifted} once the second hands-off phase
 * finishes. The alternative - asking the user to roll the sticks twice - is
 * worse for a 20-count correction.
 *
 * <h3>Nothing is written before the review</h3>
 *
 * The capture phases only read. The first write happens on an explicit tap in
 * REVIEW, after the user has seen the headroom table and what it will cost in
 * travel, and the value found beforehand is recorded first and never
 * overwritten.
 */
public final class CalibrationActivity extends Activity implements RawStream.Listener {

    private static final String TAG = "stickcal";

    private TextView title, instruction, progress, status;
    private TextView verdictLeft, verdictRight, tableLeft, tableRight;
    private GateView gateViewLeft, gateViewRight;
    private Button back, next, restore, toggleAdvanced;
    private View advanced;
    private SeekBar deadzoneBar, marginBar;
    private TextView deadzoneLabel, marginLabel;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private RootShell shell;
    private RawStream stream;
    private Store store;

    private Phase phase = Phase.INTRO;
    private boolean busy;
    /** Set when root or the node is unavailable: the wizard cannot start. */
    private String blocked;

    private final RestMeasure restBefore = new RestMeasure();
    private final RestMeasure restAfter = new RestMeasure();
    /** Gates as captured, relative to the cold rest position. */
    private GateMap rawGateLeft = new GateMap(), rawGateRight = new GateMap();
    /** Gates re-based onto the final centre, and their solutions. */
    private GateMap gateLeft, gateRight;
    private RestAnalysis rest;
    private GateSolver.Solution solutionLeft, solutionRight;

    private Calibration base;
    private String appliedValue;

    private final VerifyMap verifyLeft = new VerifyMap();
    private final VerifyMap verifyRight = new VerifyMap();
    private AxisMap axisMap;

    private int deadzone = GateSolver.DEFAULT_DEADZONE;
    private double target = GateSolver.DEFAULT_TARGET;

    // ---------------------------------------------------------- lifecycle ---

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.calibrate);

        title = findViewById(R.id.title);
        instruction = findViewById(R.id.instruction);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        verdictLeft = findViewById(R.id.verdict_left);
        verdictRight = findViewById(R.id.verdict_right);
        tableLeft = findViewById(R.id.table_left);
        tableRight = findViewById(R.id.table_right);
        gateViewLeft = findViewById(R.id.gate_left);
        gateViewRight = findViewById(R.id.gate_right);
        back = findViewById(R.id.back);
        next = findViewById(R.id.next);
        restore = findViewById(R.id.restore);
        toggleAdvanced = findViewById(R.id.toggle_advanced);
        advanced = findViewById(R.id.advanced);
        deadzoneBar = findViewById(R.id.deadzone_bar);
        marginBar = findViewById(R.id.margin_bar);
        deadzoneLabel = findViewById(R.id.deadzone_label);
        marginLabel = findViewById(R.id.margin_label);

        gateViewLeft.setCaption(Stick.LEFT.label);
        gateViewRight.setCaption(Stick.RIGHT.label);

        // A minute of rolling a stick is a minute of not touching the screen,
        // and the screen going out mid-capture would end it.
        findViewById(R.id.title).getRootView().setKeepScreenOn(true);

        next.setOnClickListener(v -> onNext());
        back.setOnClickListener(v -> onBack());
        restore.setOnClickListener(v -> confirmRestore());
        toggleAdvanced.setOnClickListener(v -> {
            advanced.setVisibility(advanced.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
        });

        deadzoneBar.setMax(GateSolver.MAX_DEADZONE - GateSolver.MIN_DEADZONE);
        deadzoneBar.setProgress(deadzone - GateSolver.MIN_DEADZONE);
        marginBar.setMax((int) Math.round((GateSolver.MAX_TARGET - GateSolver.MIN_TARGET) * 100));
        marginBar.setProgress((int) Math.round((target - GateSolver.MIN_TARGET) * 100));
        SeekBar.OnSeekBarChangeListener resolve = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                deadzone = GateSolver.MIN_DEADZONE + deadzoneBar.getProgress();
                target = GateSolver.MIN_TARGET + marginBar.getProgress() / 100.0;
                // Re-solving is closed form, so this is instant and needs no
                // new capture: that is the whole reason the controls are here.
                resolveFromCapture();
                render();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        };
        deadzoneBar.setOnSeekBarChangeListener(resolve);
        marginBar.setOnSeekBarChangeListener(resolve);

        store = new Store(this);
        shell = new RootShell();

        render();
        status.setText(R.string.reading);
        io.execute(this::readCurrentState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStream();
        io.execute(() -> { if (shell != null) shell.close(); });
        io.shutdown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // A capture that kept running behind another app would accumulate
        // samples nobody is producing deliberately, and would hold a root
        // process open indefinitely.
        if (phase.needsStream()) stopStream();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (phase.needsStream() && stream == null && blocked == null) startStream();
    }

    // ------------------------------------------------------ initial read ---

    /** Runs on the io thread. Reads, never writes. */
    private void readCurrentState() {
        if (!shell.open()) {
            post(() -> { blocked = getString(R.string.no_root); render(); });
            return;
        }
        RootShell.Result cal = shell.run(DeviceCommands.readCalibration());
        Calibration parsed = Calibration.parse(cal.trimmed());
        if (!cal.ok() || parsed == null) {
            Log.w(TAG, "calibration read failed: " + cal.code + " '" + cal.trimmed() + "'");
            post(() -> { blocked = getString(R.string.no_node); render(); });
            return;
        }
        // Recorded before anything is written, and only if this is the first
        // run: on a second run the "current" value is this app's own output.
        boolean first = store.saveOriginalCalibration(cal.trimmed());
        RootShell.Result prefs = shell.run(DeviceCommands.readPrefs());
        if (prefs.ok() && !prefs.out.trim().isEmpty()) store.saveOriginalPrefs(prefs.out);

        final boolean persists = shell.canSeeAynParts();
        post(() -> {
            base = parsed;
            String saved = store.originalCalibration();
            // Said now, not after Apply. Without AynParts' directory in view
            // the calibration can still be applied and still takes effect -
            // it just will not come back after a reboot, and that is worth
            // knowing before spending a minute rolling the sticks.
            String persistence = persists ? ""
                    : "  — WARNING: AynParts is not reachable from this shell,"
                      + " so a calibration will not survive a reboot";
            status.setText("found " + cal.trimmed()
                    + (first ? "  (recorded as the original)"
                             : "  (original on file: " + saved + ")")
                    + persistence);
            render();
        });
    }

    // ---------------------------------------------------------- the flow ---

    private void onNext() {
        if (busy) return;
        switch (phase) {
            case INTRO:
                if (blocked != null || base == null) return;
                phase = Phase.REST_BEFORE;
                startStream();
                break;
            case REVIEW:
                apply();
                return;
            case VERIFY:
                // Not blocked, but not glossed over either: a direction that
                // does not saturate is the original fault, and the user is the
                // one who decides whether to live with it. The note
                // distinguishes that from a check that was simply not
                // finished, which is the far more common reason to arrive
                // here without a pass.
                String note = Report.verifyExitNote(verifyLeft, verifyRight);
                if (note != null) status.setText(note);
                phase = Phase.DONE;
                break;
            case DONE:
                finish();
                return;
            default:
                // The capture phases advance themselves. Next is a manual
                // override for a stick that cannot satisfy the criteria -
                // a worn gate, say - and it solves from what it has.
                advanceCapture();
                return;
        }
        render();
    }

    private void onBack() {
        if (busy) return;
        switch (phase) {
            case INTRO:
                finish();
                return;
            case REST_BEFORE:
                stopStream();
                phase = Phase.INTRO;
                break;
            case ROLL_LEFT:
                rawGateLeft = new GateMap();
                gateViewLeft.reset();
                phase = Phase.REST_BEFORE;
                break;
            case ROLL_RIGHT:
                rawGateRight = new GateMap();
                gateViewRight.reset();
                phase = Phase.ROLL_LEFT;
                break;
            case REST_AFTER:
                phase = Phase.ROLL_RIGHT;
                break;
            case REVIEW:
                // Back from the review means redo the capture, not unpick the
                // solve: the solve is a function of the capture.
                restart();
                return;
            case VERIFY:
                phase = Phase.REVIEW;
                break;
            case DONE:
                phase = Phase.VERIFY;
                break;
        }
        if (phase.needsStream() && stream == null) startStream();
        render();
    }

    private void restart() {
        stopStream();
        phase = Phase.INTRO;
        rawGateLeft = new GateMap(); rawGateRight = new GateMap();
        gateLeft = null; gateRight = null;
        solutionLeft = null; solutionRight = null;
        rest = null;
        gateViewLeft.reset(); gateViewRight.reset();
        gateViewLeft.setCaption(Stick.LEFT.label);
        gateViewRight.setCaption(Stick.RIGHT.label);
        status.setText("");
        render();
    }

    /** Move past a capture phase, whether it finished itself or was skipped. */
    private void advanceCapture() {
        switch (phase) {
            case REST_BEFORE:
                phase = Phase.ROLL_LEFT;
                break;
            case ROLL_LEFT:
                phase = Phase.ROLL_RIGHT;
                break;
            case ROLL_RIGHT:
                phase = Phase.REST_AFTER;
                break;
            case REST_AFTER:
                stopStream();
                phase = Phase.REVIEW;
                resolveFromCapture();
                // Said here rather than in render(), which cannot tell a
                // status line it should replace from one it should leave -
                // the read's "found ..." line is still sitting there.
                status.setText(Report.rest(rest));
                break;
            default:
                return;
        }
        render();
    }

    /**
     * Turn the capture into two solutions. Pure, and cheap enough to redo on
     * every drag of the advanced sliders.
     */
    private void resolveFromCapture() {
        rest = new RestAnalysis(restBefore, restAfter);
        // The rolls were accumulated about the cold rest position; re-base
        // them onto the hysteresis midpoint.
        double dxL = rest.center[Stick.LEFT.xAxis] - restBefore.median(Stick.LEFT.xAxis);
        double dyL = rest.center[Stick.LEFT.yAxis] - restBefore.median(Stick.LEFT.yAxis);
        double dxR = rest.center[Stick.RIGHT.xAxis] - restBefore.median(Stick.RIGHT.xAxis);
        double dyR = rest.center[Stick.RIGHT.yAxis] - restBefore.median(Stick.RIGHT.yAxis);
        gateLeft = rawGateLeft.shifted(dxL, dyL);
        gateRight = rawGateRight.shifted(dxR, dyR);

        solutionLeft = GateSolver.solve(Stick.LEFT, gateLeft,
                rest.center[Stick.LEFT.xAxis], rest.center[Stick.LEFT.yAxis], deadzone, target);
        solutionRight = GateSolver.solve(Stick.RIGHT, gateRight,
                rest.center[Stick.RIGHT.xAxis], rest.center[Stick.RIGHT.yAxis], deadzone, target);
    }

    // ------------------------------------------------------- the raw feed ---

    private void startStream() {
        if (stream != null) return;
        io.execute(() -> {
            boolean ok = shell.open();
            boolean frac = ok && shell.fractionalSleep();
            String[] argv = shell.argv();
            post(() -> {
                if (!phase.needsStream()) return;
                stream = new RawStream(this, frac, argv);
                if (!stream.start()) stream = null;
            });
        });
    }

    private void stopStream() {
        if (stream != null) { stream.close(); stream = null; }
        gateViewLeft.clearLive();
        gateViewRight.clearLive();
    }

    @Override
    public void onSample(RawSample s) {
        if (!phase.samples) return;
        long t = SystemClock.uptimeMillis();

        // The live cursor is drawn about the cold rest position until a better
        // centre exists, so that something sensible is on screen from the
        // first sample rather than after the fourth phase.
        int cx = restBefore.size() > 0 ? restBefore.median(Stick.LEFT.xAxis) : 0;
        int cy = restBefore.size() > 0 ? restBefore.median(Stick.LEFT.yAxis) : 0;
        int cx2 = restBefore.size() > 0 ? restBefore.median(Stick.RIGHT.xAxis) : 0;
        int cy2 = restBefore.size() > 0 ? restBefore.median(Stick.RIGHT.yAxis) : 0;
        gateViewLeft.setLive(s.lx - cx, s.ly - cy);
        gateViewRight.setLive(s.rx - cx2, s.ry - cy2);

        switch (phase) {
            case REST_BEFORE:
                restBefore.add(t, s);
                if (restBefore.settled()) { advanceCapture(); return; }
                break;
            case ROLL_LEFT:
                rawGateLeft.add(t, s.lx - cx, s.ly - cy);
                gateViewLeft.setGate(rawGateLeft, null);
                if (rawGateLeft.complete()) { advanceCapture(); return; }
                break;
            case ROLL_RIGHT:
                rawGateRight.add(t, s.rx - cx2, s.ry - cy2);
                gateViewRight.setGate(rawGateRight, null);
                if (rawGateRight.complete()) { advanceCapture(); return; }
                break;
            case REST_AFTER:
                restAfter.add(t, s);
                if (restAfter.settled()) { advanceCapture(); return; }
                break;
            default:
                return;
        }
        renderProgress();
    }

    @Override
    public void onStreamFailed(String why) {
        stopStream();
        blocked = why;
        render();
    }

    // -------------------------------------------------------- the writing ---

    private void apply() {
        if (solutionLeft == null || solutionRight == null) return;
        if (solutionLeft.degenerate || solutionRight.degenerate) {
            status.setText("not enough of the gate was measured — roll both sticks again");
            return;
        }
        final String value = GateSolver.apply(base, solutionLeft, solutionRight).toString();
        busy = true;
        status.setText(R.string.applying);
        render();
        io.execute(() -> {
            String outcome = write(value);
            post(() -> {
                busy = false;
                status.setText(outcome);
                if (appliedValue != null) {
                    phase = Phase.VERIFY;
                    axisMap = discoverAxes();
                }
                render();
            });
        });
    }

    /**
     * Write the node, then the preference. Runs on the io thread.
     *
     * Two destinations because they do different jobs. The node takes effect
     * at once and is lost at the next boot; the preference is what AynParts'
     * BootCompletedReceiver restores, which is the only thing that makes the
     * calibration survive a reboot.
     *
     * @return a line to show the user, whatever happened
     */
    private String write(String value) {
        RootShell.Result w = shell.run(DeviceCommands.writeCalibration(value));
        if (!w.ok()) return "the node refused the write (" + w.code + "): " + w.trimmed();
        if (!value.equals(w.trimmed())) {
            return "the node read back something else: " + w.trimmed();
        }
        appliedValue = value;
        store.saveApplied(value);

        // AynParts caches its preferences in memory, so a running instance
        // would write its own copy back over ours. It has no persistent
        // service, so stopping it costs nothing.
        shell.run(DeviceCommands.forceStopAynParts());

        RootShell.Result read = shell.run(DeviceCommands.readPrefs());
        String current = read.ok() ? read.out : "";
        if (!current.trim().isEmpty()) store.saveOriginalPrefs(current);

        String updated;
        try {
            updated = PrefsXml.withCalibration(current.trim().isEmpty() ? null : current, value);
        } catch (IllegalArgumentException e) {
            return "applied to the node, but AynParts' preferences could not be patched: "
                    + e.getMessage() + " — it will not survive a reboot";
        }

        try {
            java.io.File staged = store.stagePrefs(updated);
            RootShell.Result inst = shell.run(DeviceCommands.installPrefs(staged.getAbsolutePath()));
            store.clearStaged();
            String err = DeviceCommands.installPrefsError(inst.code);
            if (err != null) return "applied to the node, but " + err
                    + " — it will not survive a reboot";
            if (!value.equals(PrefsXml.read(inst.out))) {
                return "applied to the node, but the preference did not read back"
                        + " — it will not survive a reboot";
            }
        } catch (IOException e) {
            return "applied to the node, but staging failed: " + e.getMessage();
        }
        return "applied, and stored where AynParts restores it at boot";
    }

    private void confirmRestore() {
        final String original = store.originalCalibration();
        if (original == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore)
                .setMessage("Write back the calibration found before this app ran?\n\n"
                        + original + "\n\nSaved " + store.originalsPath())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.restore, (d, i) -> {
                    busy = true;
                    render();
                    io.execute(() -> {
                        String outcome = write(original);
                        post(() -> {
                            busy = false;
                            status.setText("restore: " + outcome);
                            render();
                        });
                    });
                })
                .show();
    }

    // ------------------------------------------------------ verification ---

    /** Which MotionEvent axes this pad reports. */
    private AxisMap discoverAxes() {
        Set<Integer> present = new HashSet<>();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            if ((d.getSources() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
                continue;
            }
            for (InputDevice.MotionRange r : d.getMotionRanges()) present.add(r.getAxis());
        }
        AxisMap m = AxisMap.choose(present);
        if (!m.rightFound) Log.w(TAG, "no right-stick axes reported; verifying left only");
        return m;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if (phase != Phase.VERIFY || axisMap == null) return super.onGenericMotionEvent(e);
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return super.onGenericMotionEvent(e);
        }
        if (e.getAction() != MotionEvent.ACTION_MOVE) return super.onGenericMotionEvent(e);

        // Joystick events are batched, and the batched history is where most
        // of a fast roll actually is. Dropping it would leave sectors looking
        // unswept that the user did sweep.
        for (int h = 0; h < e.getHistorySize(); h++) {
            feedVerify(e.getHistoricalAxisValue(axisMap.leftX, h),
                       e.getHistoricalAxisValue(axisMap.leftY, h),
                       e.getHistoricalAxisValue(axisMap.rightX, h),
                       e.getHistoricalAxisValue(axisMap.rightY, h));
        }
        feedVerify(e.getAxisValue(axisMap.leftX), e.getAxisValue(axisMap.leftY),
                   e.getAxisValue(axisMap.rightX), e.getAxisValue(axisMap.rightY));
        renderVerify();
        return true;
    }

    private void feedVerify(float lx, float ly, float rx, float ry) {
        // The framework's y is positive-down on these axes while the plot is
        // drawn positive-up; negated so a push forwards draws upwards and the
        // picture matches the hand.
        verifyLeft.add(lx, -ly);
        verifyRight.add(rx, -ry);
        gateViewLeft.setLive(lx, -ly);
        gateViewRight.setLive(rx, -ry);
    }

    /**
     * Swallow gamepad buttons while the pad is being used as an instrument.
     *
     * A stray B press during a roll is Back on this platform, which would end
     * the phase. Only during the phases that ask the user to hold the pad:
     * elsewhere the pad should still navigate normally.
     */
    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (KeyEvent.isGamepadButton(code) && (phase == Phase.VERIFY || phase.stick != null)) {
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (KeyEvent.isGamepadButton(code) && (phase == Phase.VERIFY || phase.stick != null)) {
            return true;
        }
        return super.onKeyUp(code, e);
    }

    // ----------------------------------------------------------- the view ---

    private void render() {
        title.setText(titleFor(phase));
        instruction.setText(blocked != null ? blocked : instructionFor(phase));
        progress.setText(phase.captureStep() > 0
                ? "step " + phase.captureStep() + " of " + Phase.CAPTURE_STEPS
                : "");

        boolean capture = phase.stick != null;
        gateViewLeft.setMode(modeOf());
        gateViewRight.setMode(modeOf());
        gateViewLeft.setAlpha(capture && phase.stick == Stick.RIGHT ? 0.35f : 1f);
        gateViewRight.setAlpha(capture && phase.stick == Stick.LEFT ? 0.35f : 1f);

        boolean review = phase == Phase.REVIEW;
        toggleAdvanced.setVisibility(review ? View.VISIBLE : View.GONE);
        if (!review) advanced.setVisibility(View.GONE);
        restore.setVisibility(store.hasOriginal() && (review || phase == Phase.DONE)
                ? View.VISIBLE : View.GONE);

        next.setEnabled(!busy && blocked == null
                && !(phase == Phase.INTRO && base == null));
        back.setEnabled(!busy);
        next.setText(nextLabel());

        if (review) {
            deadzoneLabel.setText("deadzone " + deadzone + " counts"
                    + (rest != null && deadzone < rest.recommendedDeadzone()
                        ? " (below the " + rest.recommendedDeadzone() + " this unit needs)" : ""));
            marginLabel.setText(String.format(java.util.Locale.US, "target margin %.2f", target));
        }

        if (phase == Phase.REVIEW || phase == Phase.DONE) {
            showSolution(solutionLeft, verdictLeft, tableLeft);
            showSolution(solutionRight, verdictRight, tableRight);
            if (solutionLeft != null) {
                gateViewLeft.setGate(gateLeft, solutionLeft);
                gateViewRight.setGate(gateRight, solutionRight);
            }
        } else if (phase == Phase.VERIFY) {
            gateViewLeft.setVerify(verifyLeft);
            gateViewRight.setVerify(verifyRight);
            renderVerify();
        } else {
            renderProgress();
        }
    }

    private GateView.Mode modeOf() {
        if (phase == Phase.VERIFY) return GateView.Mode.VERIFY;
        if (phase == Phase.REVIEW || phase == Phase.DONE) return GateView.Mode.RESULT;
        return GateView.Mode.CAPTURE;
    }

    private void showSolution(GateSolver.Solution s, TextView verdict, TextView table) {
        if (s == null) { verdict.setText(""); table.setText(""); return; }
        verdict.setText(s.stick.label + " — " + Report.summary(s));
        table.setText(s.degenerate ? "" : Report.headroomTable(s));
    }

    private void renderProgress() {
        switch (phase) {
            case REST_BEFORE:
                verdictLeft.setText("settling: " + restBefore.worstSpanInWindow() + " counts of movement");
                verdictRight.setText("");
                tableLeft.setText(""); tableRight.setText("");
                break;
            case ROLL_LEFT:
                verdictLeft.setText(Report.coverage(rawGateLeft));
                verdictRight.setText("");
                break;
            case ROLL_RIGHT:
                verdictRight.setText(Report.coverage(rawGateRight));
                break;
            case REST_AFTER:
                verdictLeft.setText("settling: " + restAfter.worstSpanInWindow() + " counts of movement");
                verdictRight.setText("");
                break;
            default:
                break;
        }
    }

    private void renderVerify() {
        verdictLeft.setText(Stick.LEFT.label + " — " + Report.verify(verifyLeft));
        verdictRight.setText(Stick.RIGHT.label + " — " + Report.verify(verifyRight));
        tableLeft.setText(""); tableRight.setText("");
        boolean both = verifyLeft.passed() && verifyRight.passed();
        if (both) status.setText("every direction saturates — this is what a game will see");
    }

    private String nextLabel() {
        switch (phase) {
            case INTRO:  return getString(R.string.start);
            case REVIEW: return getString(R.string.apply);
            case VERIFY: return getString(R.string.finish);
            case DONE:   return getString(R.string.close);
            default:     return getString(R.string.next);
        }
    }

    private String titleFor(Phase p) {
        switch (p) {
            case REST_BEFORE: return getString(R.string.title_rest_before);
            case ROLL_LEFT:   return getString(R.string.title_roll_left);
            case ROLL_RIGHT:  return getString(R.string.title_roll_right);
            case REST_AFTER:  return getString(R.string.title_rest_after);
            case REVIEW:      return getString(R.string.title_review);
            case VERIFY:      return getString(R.string.title_verify);
            case DONE:        return getString(R.string.title_done);
            default:          return getString(R.string.title_intro);
        }
    }

    private String instructionFor(Phase p) {
        switch (p) {
            case REST_BEFORE: return getString(R.string.rest_before);
            case ROLL_LEFT:
            case ROLL_RIGHT:  return getString(R.string.roll_instruction);
            case REST_AFTER:  return getString(R.string.rest_after);
            case REVIEW:      return getString(R.string.review);
            case VERIFY:      return getString(R.string.verify);
            case DONE:        return appliedValue == null
                                     ? getString(R.string.review)
                                     : "Applied and stored: " + appliedValue;
            default:          return getString(R.string.intro);
        }
    }

    private void post(Runnable r) { runOnUiThread(r); }
}
