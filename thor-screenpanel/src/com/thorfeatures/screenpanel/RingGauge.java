package com.thorfeatures.screenpanel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * One circular gauge: a coloured arc with its reading written inside.
 *
 * Modelled on the ring gauges stock's own telemetry overlay used, which is the
 * look this panel is being matched to. A ring earns its place over the thin
 * bar it replaces because these four readings are the ones watched while
 * something else is happening on the other screen - an arc's fill is legible
 * from the corner of an eye in a way a 6dp line is not.
 *
 * <h3>Why a custom View rather than a circular ProgressBar</h3>
 *
 * The platform's circular progress drawable is sized by its own bitmap and
 * cannot carry text, so the label and the value would have to be separate
 * overlaid TextViews measured against a circle they know nothing about. This
 * draws all of it in one pass against one set of bounds, which is both
 * simpler and the only way the text stays centred when the cell width changes.
 *
 * <h3>It carries no logic</h3>
 *
 * Everything here is presentation: the caller passes a label, the strings to
 * print and a fraction between 0 and 1. The mapping from a reading to that
 * fraction lives in {@link Telemetry}, where it is host-tested - this class
 * cannot be, because it only draws.
 *
 * The reading is also published as the content description, which is not
 * decoration: text drawn on a Canvas is invisible to {@code uiautomator dump},
 * and that dump is how this panel gets verified on the device. Without it a
 * ring would be unreadable to the only tool that can check it.
 */
public final class RingGauge extends View {

    /** A fraction this gauge has no reading for. */
    public static final float UNKNOWN = -1f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private float fraction = UNKNOWN;
    private String label = "";
    private String value = Telemetry.NONE;
    private String sub   = "";
    private int labelColor;
    private int valueColor;

    public RingGauge(Context c) { this(c, null); }

    public RingGauge(Context c, AttributeSet a) {
        super(c, a);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        labelColor = getContext().getColor(R.color.panel_on_surface_dim);
        valueColor = getContext().getColor(R.color.panel_on_surface);
        track.setColor(getContext().getColor(R.color.gauge_track));
        arc.setColor(getContext().getColor(R.color.gauge_cpu));
    }

    /**
     * Set everything this gauge shows.
     *
     * @param label    the metric's name, drawn small at the top of the ring
     * @param value    the reading, drawn large in the middle
     * @param sub      a second line under it, or "" for none
     * @param fraction 0..1 of the ring to fill, or {@link #UNKNOWN}
     * @param color    the arc colour
     */
    public void set(String label, String value, String sub, float fraction, int color) {
        this.label = label == null ? "" : label;
        this.value = value == null ? Telemetry.NONE : value;
        this.sub   = sub == null ? "" : sub;
        this.fraction = fraction;
        arc.setColor(color);
        setContentDescription(this.label + " " + this.value
                + (this.sub.isEmpty() ? "" : " " + this.sub));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        // The ring is square and centred, so a cell that is wider than it is
        // tall - which every cell here is, four across a 620dp screen - still
        // draws a circle rather than an ellipse.
        float size = Math.min(w, h);
        if (size <= 0) return;

        // Everything scales off the ring's size rather than from dimensions,
        // so one layout works on both screens and at any cell width.
        float stroke = size * 0.075f;
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);

        float cx = w / 2f, cy = h / 2f;
        float r = (size - stroke) / 2f - size * 0.02f;
        bounds.set(cx - r, cy - r, cx + r, cy + r);

        c.drawCircle(cx, cy, r, track);
        if (fraction > 0f) {
            // From twelve o'clock, clockwise, like every other gauge a person
            // has ever read.
            c.drawArc(bounds, -90f, Math.min(fraction, 1f) * 360f, false, arc);
        }

        text.setColor(labelColor);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(size * 0.125f);
        c.drawText(label, cx, cy - r * 0.42f, text);

        text.setColor(valueColor);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextSize(size * 0.23f);
        // Centre the value on the ring's middle rather than on the baseline,
        // so it sits optically centred between label and sub-line.
        float mid = sub.isEmpty() ? cy + size * 0.08f : cy + size * 0.04f;
        c.drawText(value, cx, mid, text);

        if (!sub.isEmpty()) {
            text.setColor(labelColor);
            text.setTypeface(Typeface.DEFAULT);
            text.setTextSize(size * 0.105f);
            c.drawText(sub, cx, cy + r * 0.52f, text);
        }
    }
}
