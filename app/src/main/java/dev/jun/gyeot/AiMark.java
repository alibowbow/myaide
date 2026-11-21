package dev.jun.gyeot;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

/**
 * The AI 창 button's mark, drawn in code: a fine speech-bubble outline with "AI" set in the app's own type inside it, one
 * color, as light as the toolbar's other icons. It follows ImageView's tint (setImageTintList), so open, closed and dark
 * mode only change its color.
 */
final class AiMark extends Drawable {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), letters = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bubble = new Path();
    private final int size;
    private ColorStateList tint;
    private int color = 0xff3a3632;

    AiMark(float density) {
        size = Math.round(24 * density);
        line.setStyle(Paint.Style.STROKE); line.setStrokeJoin(Paint.Join.ROUND); line.setStrokeCap(Paint.Cap.ROUND);
        letters.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 560, false));
        letters.setTextAlign(Paint.Align.CENTER); letters.setLetterSpacing(0.04f);
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds(); if (b.isEmpty()) return;
        float s = Math.min(b.width(), b.height()) / 24f, x = b.left + (b.width() - 24 * s) / 2f, y = b.top + (b.height() - 24 * s) / 2f;
        line.setColor(color); letters.setColor(color);
        line.setStrokeWidth(1.25f * s);
        // The bubble: a soft rounded box with a small tail at its lower left (24-unit grid).
        bubble.reset();
        bubble.moveTo(x + 7.2f * s, y + 4.6f * s);
        bubble.lineTo(x + 16.8f * s, y + 4.6f * s);
        bubble.cubicTo(x + 19.1f * s, y + 4.6f * s, x + 20.6f * s, y + 6.1f * s, x + 20.6f * s, y + 8.4f * s);
        bubble.lineTo(x + 20.6f * s, y + 13.6f * s);
        bubble.cubicTo(x + 20.6f * s, y + 15.9f * s, x + 19.1f * s, y + 17.4f * s, x + 16.8f * s, y + 17.4f * s);
        bubble.lineTo(x + 10.2f * s, y + 17.4f * s);
        bubble.lineTo(x + 6.6f * s, y + 20.1f * s);
        bubble.lineTo(x + 6.9f * s, y + 17.3f * s);
        bubble.cubicTo(x + 4.8f * s, y + 17.0f * s, x + 3.4f * s, y + 15.5f * s, x + 3.4f * s, y + 13.6f * s);
        bubble.lineTo(x + 3.4f * s, y + 8.4f * s);
        bubble.cubicTo(x + 3.4f * s, y + 6.1f * s, x + 4.9f * s, y + 4.6f * s, x + 7.2f * s, y + 4.6f * s);
        bubble.close();
        canvas.drawPath(bubble, line);
        letters.setTextSize(7.4f * s);
        Paint.FontMetrics m = letters.getFontMetrics();
        float baseline = y + 11.0f * s - (m.ascent + m.descent) / 2f;
        canvas.drawText("AI", x + 12f * s, baseline, letters);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { line.setAlpha(alpha); letters.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { line.setColorFilter(filter); letters.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void setTintList(ColorStateList list) { tint = list; pick(); }
    @Override public void setTint(int value) { tint = ColorStateList.valueOf(value); pick(); }
    @Override public boolean isStateful() { return tint != null && tint.isStateful(); }
    @Override protected boolean onStateChange(int[] state) { int before = color; pick(); return before != color; }
    private void pick() { if (tint != null) color = tint.getColorForState(getState(), tint.getDefaultColor()); invalidateSelf(); }
}
