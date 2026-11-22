package dev.jun.gyeot;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * The edge handle's shape (EdgeButton): a flat trapezoid with rounded corners, its long side against the screen's edge and
 * its short side toward the screen. Pulled inward it stretches like melted cheese: the tip follows the finger, the middle
 * thins into a neck, and let go it springs back with a soft wobble.
 */
final class EdgeHandleView extends View {
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean right = true;
    /** How far the tip is pulled into the screen (px), and the height the finger is at (px, -1: the middle). */
    private float pull, fingerY = -1;
    private ValueAnimator settle;

    EdgeHandleView(Context context, int color) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        paint.setColor(color); paint.setStyle(Paint.Style.FILL); paint.setPathEffect(new CornerPathEffect(4 * density));
    }
    private float dp(float value) { return value * density; }
    void side(boolean right) { this.right = right; invalidate(); }
    float pull() { return pull; }
    /** The finger pulls the tip to this depth, at this height. */
    void pull(float depth, float y) { if (settle != null) settle.cancel(); pull = Math.max(0, depth); fingerY = y; invalidate(); }
    /** Let go: back to its flat shape with a damped wobble, like a strand of cheese snapping back. done runs at rest. */
    void release(Runnable done) {
        if (settle != null) settle.cancel();
        float from = pull;
        if (from <= 0.5f) { pull = 0; invalidate(); if (done != null) done.run(); return; }
        settle = ValueAnimator.ofFloat(0f, 1f); settle.setDuration(520);
        settle.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // A damped spring: overshoots past flat a little (the handle bulges outward), then settles.
            pull = (float) (from * Math.exp(-5.5 * t) * Math.cos(13 * t));
            invalidate();
        });
        settle.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) { pull = 0; fingerY = -1; invalidate(); if (done != null) done.run(); }
        });
        settle.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        // The resting trapezoid: 60dp along the edge, 38dp on the inner side, 6dp thick, in the window's middle.
        float base = Math.min(h - dp(8), dp(60)), thick = dp(6);
        float top = (h - base) / 2, bottom = top + base;
        float stretch = pull / dp(40);
        // Pulled, the tip narrows and leans toward the finger; the sides hug the edge, then reach out (a neck, like cheese).
        float tipHalf = dp(19) / (1 + stretch * 1.6f);
        float lean = fingerY < 0 ? 0 : Math.max(-base / 3, Math.min(base / 3, fingerY - h / 2)) * Math.min(1, stretch);
        float tipTop = h / 2 + lean - tipHalf, tipBottom = h / 2 + lean + tipHalf, depth = thick + pull;
        path.reset();
        // At rest the sides are straight (a plain trapezoid); pulled, they bend into a neck that hugs the edge, and the tip
        // rounds into a blob, as a strand of melted cheese does. k blends the two.
        float k = Math.min(1, stretch), ax = 0, cx = depth;
        float c1x = lerp((cx - ax) / 3, depth * 0.12f, k), c1y = lerp(top + (tipTop - top) / 3, top + (tipTop - top) * 0.85f, k);
        float c2x = lerp(2 * (cx - ax) / 3, depth * 0.7f, k), c2y = lerp(top + 2 * (tipTop - top) / 3, tipTop, k);
        path.moveTo(x(0, w), top);
        path.cubicTo(x(c1x, w), c1y, x(c2x, w), c2y, x(depth, w), tipTop);
        path.quadTo(x(depth + tipHalf * 0.9f * k, w), (tipTop + tipBottom) / 2, x(depth, w), tipBottom);
        // The lower side mirrors the upper one: from the tip back to the edge.
        float span = bottom - tipBottom;
        path.cubicTo(x(c2x, w), lerp(tipBottom + span / 3, tipBottom, k), x(c1x, w), lerp(tipBottom + 2 * span / 3, bottom - span * 0.85f, k), x(0, w), bottom);
        path.close();
        canvas.drawPath(path, paint);
    }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private float x(float fromEdge, float w) { return right ? w - fromEdge : fromEdge; }
}
