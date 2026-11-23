package com.thorfeatures.oledwash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/**
 * Draws the band.
 *
 * <p>Black everywhere except one horizontal band of per-frame random noise
 * sweeping from off the top to off the bottom. Black matters as much as the
 * band does: it is what keeps the average picture level at the band alone, and
 * the panel's own figures say display 0 sustains only half its peak luminance
 * at full field. A third of the screen lit is a third that can be driven hard.
 *
 * <p>The plan comes from {@link #getHeight()} rather than from any constant, so
 * a panel that is not 1080 tall needs no change here.
 */
public final class WashView extends View {

    /** Drop the label once the wash is nearly over, so it is not the last thing lit. */
    private static final long LABEL_HIDE_TAIL_MS = 400L;

    /** Told once, when the last sweep has left the panel. */
    public interface OnComplete {
        void onWashComplete();
    }

    private final int grain;
    private final long cycleMs;
    private final int cycles;
    private final double bandFraction;
    private final boolean showDiagnostics;
    private final OnComplete onComplete;

    private final Paint bandPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Noise noise = new Noise(System.nanoTime());

    private WashPlan plan;
    private Bitmap bandBitmap;
    private int[] pixels;
    private int noiseW, noiseH;

    private long startNanos = 0L;
    private long lastFrameNanos = 0L;
    private long meanFrameNanos = 0L;
    private boolean reportedComplete = false;

    public WashView(Context ctx, double bandFraction, long cycleMs, int cycles,
                    int grain, boolean showDiagnostics, OnComplete onComplete) {
        super(ctx);
        this.bandFraction = bandFraction;
        this.cycleMs = cycleMs;
        this.cycles = cycles;
        this.grain = Math.max(1, grain);
        this.showDiagnostics = showDiagnostics;
        this.onComplete = onComplete;

        setBackgroundColor(Color.BLACK);
        // Nearest-neighbour. At grain > 1 each noise pixel covers a block, and
        // filtering would average the block into a soft grey -- which is the
        // one thing the pattern must not become.
        bandPaint.setFilterBitmap(false);
        bandPaint.setAntiAlias(false);
        bandPaint.setDither(false);
        textPaint.setColor(0xFF9A9A9A);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w <= 0 || h <= 0) return;

        plan = WashPlan.of(h, bandFraction, cycleMs, cycles);

        noiseW = Math.max(1, (w + grain - 1) / grain);
        noiseH = Math.max(1, (plan.bandHeight + grain - 1) / grain);
        pixels = new int[noiseW * noiseH];
        if (bandBitmap != null) bandBitmap.recycle();
        bandBitmap = Bitmap.createBitmap(noiseW, noiseH, Bitmap.Config.ARGB_8888);

        textPaint.setTextSize(Math.max(14f, h / 36f));

        startNanos = 0L;
        lastFrameNanos = 0L;
        meanFrameNanos = 0L;
        reportedComplete = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (plan == null || bandBitmap == null) return;

        final long now = System.nanoTime();
        if (startNanos == 0L) startNanos = now;
        trackFrameInterval(now);

        final long elapsedMs = (now - startNanos) / 1_000_000L;

        canvas.drawColor(Color.BLACK);

        if (plan.finishedAt(elapsedMs)) {
            // Say so once, to whoever is listening. This view deliberately does
            // not reach for WashSession: the Activity owns ending the wash, and
            // routing completion through a static that may not be running is
            // what left a finished wash on screen with no way out.
            if (!reportedComplete) {
                reportedComplete = true;
                if (onComplete != null) onComplete.onWashComplete();
            }
            return;
        }

        drawBand(canvas, plan.bandTopAt(elapsedMs));

        if (elapsedMs + LABEL_HIDE_TAIL_MS < plan.totalMs()) {
            drawLabel(canvas, elapsedMs);
        }

        postInvalidateOnAnimation();
    }

    private void drawBand(Canvas canvas, int bandTop) {
        noise.fill(pixels, noiseW * noiseH, Params.NOISE_FLOOR);
        bandBitmap.setPixels(pixels, 0, noiseW, 0, 0, noiseW, noiseH);

        final Rect src = new Rect(0, 0, noiseW, noiseH);
        final RectF dst = new RectF(0f, bandTop, getWidth(), bandTop + plan.bandHeight);
        canvas.drawBitmap(bandBitmap, src, dst, bandPaint);
    }

    private void drawLabel(Canvas canvas, long elapsedMs) {
        final float cx = getWidth() / 2f;
        final float baseline = getHeight() - textPaint.getTextSize() * 1.2f;

        String line = getContext().getString(R.string.wash_hint)
                + "   ·   " + Summary.progress(plan, elapsedMs);
        canvas.drawText(line, cx, baseline, textPaint);

        if (showDiagnostics) {
            String diag = String.format(java.util.Locale.US, "%.0f fps · grain %d · %d px",
                    Summary.fps(meanFrameNanos), grain, plan.bandHeight);
            canvas.drawText(diag, cx, baseline - textPaint.getTextSize() * 1.3f, textPaint);
        }
    }

    /** Rolling mean, so the readout does not flicker on one slow frame. */
    private void trackFrameInterval(long now) {
        if (lastFrameNanos != 0L) {
            long delta = now - lastFrameNanos;
            meanFrameNanos = (meanFrameNanos == 0L) ? delta : (meanFrameNanos * 7 + delta) / 8;
        }
        lastFrameNanos = now;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (bandBitmap != null) {
            bandBitmap.recycle();
            bandBitmap = null;
        }
    }
}
