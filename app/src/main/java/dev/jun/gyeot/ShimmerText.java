package dev.jun.gyeot;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.os.SystemClock;
import android.widget.TextView;

/**
 * The line that says an AI is thinking: its words in a quiet color with a soft band of light sweeping across them, left to
 * right, every 1.6 seconds, as chat apps show a model at work. The light is the stronger ink (the body text color), so it
 * reads in light and dark. It moves only while drawn (a hidden line costs nothing) and stands still when the phone's
 * animations are off.
 */
final class ShimmerText extends TextView {
    private static final long PERIOD = 1600;
    private final Matrix matrix = new Matrix();
    private LinearGradient band;
    private int base, light, bandWidth;
    private boolean on = true;

    ShimmerText(Context context, int base, int light) {
        super(context);
        this.base = base; this.light = light; setTextColor(base);
        bandWidth = Math.round(72 * context.getResources().getDisplayMetrics().density);
    }

    /** Sweeps or stands still (a finished line keeps its words without the light). */
    void shimmer(boolean value) { if (on != value) { on = value; invalidate(); } }
    boolean shimmering() { return on; }

    @Override protected void onDraw(Canvas canvas) {
        boolean moving = on && ValueAnimator.areAnimatorsEnabled() && getWidth() > 0 && getText().length() > 0;
        if (!moving) { getPaint().setShader(null); super.onDraw(canvas); return; }
        if (band == null) band = new LinearGradient(0, 0, bandWidth, 0, new int[]{base, light, base}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        float words = Math.min(getWidth() - getTotalPaddingLeft() - getTotalPaddingRight(), getPaint().measureText(getText().toString()));
        float travel = words + 2f * bandWidth, at = (SystemClock.uptimeMillis() % PERIOD) / (float) PERIOD;
        matrix.setTranslate(getTotalPaddingLeft() - bandWidth + at * travel, 0); band.setLocalMatrix(matrix);
        getPaint().setShader(band);
        super.onDraw(canvas);
        postInvalidateOnAnimation();
    }
}
