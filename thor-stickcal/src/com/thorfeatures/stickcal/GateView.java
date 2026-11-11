package com.thorfeatures.stickcal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * One stick's gate, drawn as a polar plot.
 *
 * The point of the picture is to make the thing that per-axis calibration
 * cannot express immediately visible: the measured gate is an irregular
 * blob, not a circle, and it is narrowest at a cardinal. Once the limits are
 * solved, the saturation contour is drawn over it - the radius at which
 * output reaches full scale, at every angle. Where the contour sits inside
 * the gate, that direction saturates with travel to spare; anywhere it pokes
 * outside, that direction cannot reach full scale at all, which is exactly
 * the failure this tool was built to remove.
 *
 * <h3>Orientation</h3>
 *
 * Drawn with +x right and +y up, the mathematical convention, which is not
 * necessarily how the stick is oriented in the hand - the sign convention of
 * the raw axes is not documented and does not affect any of the arithmetic.
 * It does not need to be known, either: during a roll the live cursor tracks
 * the stick, so the user can see within a second which way the plot is
 * turning.
 */
public final class GateView extends View {

    public enum Mode {
        /** Mapping the gate: measured shape, thin sectors flagged. */
        CAPTURE,
        /** Solved: measured shape with the saturation contour over it. */
        RESULT,
        /** Checking downstream: framework magnitude against the unit circle. */
        VERIFY
    }

    private static final int COLOR_GATE      = 0xFF4FC3F7;   // measured shape
    private static final int COLOR_CONTOUR   = 0xFFFFA62B;   // saturation radius
    private static final int COLOR_THIN      = 0xFFFF4D6D;   // a sector not swept
    private static final int COLOR_PASS      = 0xFF5DD67A;
    private static final int COLOR_CURSOR    = 0xFFFFD54F;
    private static final int COLOR_GRID      = 0x33FFFFFF;
    private static final int COLOR_LABEL     = 0x99FFFFFF;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Mode mode = Mode.CAPTURE;
    private String caption = "";

    private GateMap gate;
    private GateSolver.Solution solution;
    private VerifyMap verify;
    private boolean hasLive;
    private double liveX, liveY;

    /**
     * The kernel's own nominal full range, and the plot's minimum scale.
     *
     * Normalising purely to the largest radius seen so far would rescale the
     * picture on every sample early in a roll, so the gate would appear to
     * fill the view from the first flick and the user could not see
     * themselves pushing further out. Anchoring to the range the kernel
     * defaults to - its untouched calibration is -1350:1350 - gives a stable
     * frame that the measured gate grows into, and still expands if a unit
     * reaches further than that.
     */
    private static final double NOMINAL_RANGE = 1350;

    public GateView(Context c) { this(c, null); }

    public GateView(Context c, AttributeSet a) {
        super(c, a);
        stroke.setStyle(Paint.Style.STROKE);
        grid.setStyle(Paint.Style.STROKE);
        grid.setColor(COLOR_GRID);
        text.setColor(COLOR_LABEL);
        setWillNotDraw(false);
    }

    /** Square, because a polar plot of a round-ish thing should be. */
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        super.onMeasure(wSpec, hSpec);
        int side = Math.min(getMeasuredWidth(), getMeasuredHeight());
        if (side <= 0) side = Math.max(getMeasuredWidth(), getMeasuredHeight());
        setMeasuredDimension(side, side);
    }

    public void setMode(Mode m) { mode = m; invalidate(); }

    public void setCaption(String s) { caption = s == null ? "" : s; invalidate(); }

    public void setGate(GateMap g, GateSolver.Solution s) {
        gate = g; solution = s;
        invalidate();
    }

    public void setVerify(VerifyMap v) { verify = v; invalidate(); }

    /** The stick's current position. Raw counts in CAPTURE/RESULT, -1..1 in VERIFY. */
    public void setLive(double x, double y) {
        liveX = x; liveY = y; hasLive = true;
        invalidate();
    }

    public void clearLive() { hasLive = false; invalidate(); }

    public void reset() {
        gate = null; solution = null; verify = null; hasLive = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) / 2f - dp(14);
        if (r <= 0) return;

        text.setTextSize(dp(10));
        grid.setStrokeWidth(dp(1));

        // Reference circles. In VERIFY the outer one is full scale, which is
        // the pass line; in the others it is the widest radius measured, so
        // the plot fills the view whatever the gate's size.
        canvas.drawCircle(cx, cy, r, grid);
        canvas.drawCircle(cx, cy, r * 0.5f, grid);
        canvas.drawLine(cx - r, cy, cx + r, cy, grid);
        canvas.drawLine(cx, cy - r, cx, cy + r, grid);

        if (mode == Mode.VERIFY) {
            drawVerify(canvas, cx, cy, r);
        } else {
            drawGate(canvas, cx, cy, r);
        }

        if (!caption.isEmpty()) {
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(caption, cx, h - dp(2), text);
        }
    }

    // ------------------------------------------------------------ capture ---

    private void drawGate(Canvas canvas, float cx, float cy, float r) {
        if (gate == null || gate.maxRadius() <= 0) {
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("roll the stick", cx, cy, text);
            drawCursor(canvas, cx, cy, r, NOMINAL_RANGE);
            return;
        }

        double[] radii = gate.bucketRadii();
        double scale = Math.max(gate.maxRadius(), NOMINAL_RANGE);

        // The measured gate, as a closed polygon through the 24 bucket maxima.
        Path p = new Path();
        boolean started = false;
        for (int b = 0; b < GateMap.BUCKETS; b++) {
            if (radii[b] <= 0) continue;
            double ang = GateMap.bucketAngle(b);
            float px = cx + (float) (r * radii[b] / scale * Math.cos(ang));
            float py = cy - (float) (r * radii[b] / scale * Math.sin(ang));
            if (!started) { p.moveTo(px, py); started = true; } else { p.lineTo(px, py); }
        }
        if (started) p.close();

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(COLOR_GATE);
        fill.setAlpha(48);
        canvas.drawPath(p, fill);
        stroke.setColor(COLOR_GATE);
        stroke.setStrokeWidth(dp(2));
        canvas.drawPath(p, stroke);

        // Flag the sectors that have not been swept far enough. These are
        // what stops the phase advancing, so the user needs to see which.
        if (mode == Mode.CAPTURE) {
            stroke.setColor(COLOR_THIN);
            stroke.setStrokeWidth(dp(3));
            for (int b = 0; b < GateMap.BUCKETS; b++) {
                if (gate.bucketOk(b)) continue;
                double a0 = Math.toRadians(b * GateMap.BUCKET_DEGREES);
                double a1 = Math.toRadians((b + 1) * GateMap.BUCKET_DEGREES);
                canvas.drawLine(cx + (float) (r * Math.cos(a0)), cy - (float) (r * Math.sin(a0)),
                                cx + (float) (r * Math.cos(a1)), cy - (float) (r * Math.sin(a1)), stroke);
            }
        }

        // The solved saturation contour. Inside the gate everywhere is the
        // result being looked for.
        if (mode == Mode.RESULT && solution != null && !solution.degenerate) {
            Path c = new Path();
            for (int i = 0; i <= 180; i++) {
                double ang = Math.toRadians(i * 2);
                double rad = solution.saturationRadius(ang);
                float px = cx + (float) (r * rad / scale * Math.cos(ang));
                float py = cy - (float) (r * rad / scale * Math.sin(ang));
                if (i == 0) c.moveTo(px, py); else c.lineTo(px, py);
            }
            c.close();
            stroke.setColor(COLOR_CONTOUR);
            stroke.setStrokeWidth(dp(2));
            canvas.drawPath(c, stroke);
        }

        drawCursor(canvas, cx, cy, r, scale);
    }

    // ------------------------------------------------------------- verify ---

    private void drawVerify(Canvas canvas, float cx, float cy, float r) {
        // Full scale is the pass line, drawn at half radius so that the 1.41
        // a saturated diagonal legitimately reaches still fits in the view.
        float unit = r / 1.45f;
        stroke.setColor(COLOR_PASS);
        stroke.setStrokeWidth(dp(2));
        stroke.setPathEffect(new android.graphics.DashPathEffect(
                new float[] { dp(6), dp(5) }, 0));
        canvas.drawCircle(cx, cy, unit, stroke);
        stroke.setPathEffect(null);

        if (verify != null) {
            double[] m = verify.magnitudes();
            for (int b = 0; b < GateMap.BUCKETS; b++) {
                if (!verify.covered(b)) continue;
                double ang = GateMap.bucketAngle(b);
                double a0 = Math.toRadians(b * GateMap.BUCKET_DEGREES + 1);
                double a1 = Math.toRadians((b + 1) * GateMap.BUCKET_DEGREES - 1);
                boolean under = m[b] < VerifyMap.PASS;
                stroke.setColor(under ? COLOR_THIN : COLOR_PASS);
                stroke.setStrokeWidth(dp(6));
                float rad = (float) Math.min(r, unit * m[b]);
                canvas.drawLine(cx + (float) (rad * Math.cos(a0)), cy - (float) (rad * Math.sin(a0)),
                                cx + (float) (rad * Math.cos(a1)), cy - (float) (rad * Math.sin(a1)), stroke);
                // A tick out to the bucket's reading, so a bar that is short
                // reads as short rather than as missing.
                stroke.setStrokeWidth(dp(1));
                stroke.setColor(COLOR_GRID);
                canvas.drawLine(cx, cy,
                        cx + (float) (rad * Math.cos(ang)), cy - (float) (rad * Math.sin(ang)), stroke);
            }
        }

        if (hasLive) {
            fill.setColor(COLOR_CURSOR);
            fill.setAlpha(255);
            fill.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx + (float) (unit * liveX), cy - (float) (unit * liveY), dp(4), fill);
        }
    }

    private void drawCursor(Canvas canvas, float cx, float cy, float r, double scale) {
        if (!hasLive || scale <= 0) return;
        fill.setColor(COLOR_CURSOR);
        fill.setAlpha(255);
        fill.setStyle(Paint.Style.FILL);
        float px = cx + (float) (r * liveX / scale);
        float py = cy - (float) (r * liveY / scale);
        canvas.drawCircle(px, py, dp(4), fill);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
