package dev.jun.gyeot;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import dev.jun.gyeot.core.BoardInk;

/** Small drawings of the actual tips and marks in the whiteboard tool shelf. */
final class BoardInkIcon extends Drawable {
    private final String kind;
    private final int ink, outline;
    private final boolean selected;
    private final float weight;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    BoardInkIcon(String kind, int ink, int outline, boolean selected, float weight) {
        this.kind = kind;
        this.ink = ink;
        this.outline = outline;
        this.selected = selected;
        this.weight = weight;
    }
    private void stroke(int color, float width) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }
    private void fill(int color) { paint.setColor(color); paint.setStyle(Paint.Style.FILL); }
    private void polygon(Canvas canvas, int color, float... xy) {
        Path path = new Path();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i+1]);
        path.close(); fill(color); canvas.drawPath(path, paint);
    }
    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float scale = Math.min(b.width(), b.height()) / 36f;
        int saved = canvas.save();
        canvas.translate(b.exactCenterX() - 18 * scale, b.exactCenterY() - 18 * scale);
        canvas.scale(scale, scale);
        if (BoardInk.freehand(kind)) tip(canvas);
        else if ("swatch".equals(kind)) {
            fill(ink); canvas.drawCircle(18, 18, 9.1f, paint);
            stroke((outline & 0x00ffffff) | 0x38000000, .8f); canvas.drawCircle(18,18,9.1f,paint);
            if (selected) {
                stroke(outline, 1.3f); canvas.drawCircle(18, 18, 12.2f, paint);
                stroke(Color.luminance(ink) > .41 ? 0xff272827 : Color.WHITE, 2);
                Path p = new Path(); p.moveTo(13.8f,18); p.lineTo(16.8f,21); p.lineTo(22.1f,15); canvas.drawPath(p,paint);
            }
        } else if ("width".equals(kind)) {
            stroke(ink, Math.max(1.4f, weight * .52f)); canvas.drawLine(4, 24, 32, 12, paint);
        } else mark(canvas);
        canvas.restoreToCount(saved);
    }
    private void tip(Canvas canvas) {
        boolean pencil = BoardInk.PENCIL.equals(kind), brush = BoardInk.BRUSH.equals(kind), marker = BoardInk.MARKER.equals(kind);
        int barrel = pencil ? 0xffc49857 : marker ? ink : outline;
        int saved = canvas.save();
        canvas.rotate(35, 17, 14);
        float half = marker ? 5.4f : brush ? 3.6f : 2.8f;
        fill(barrel); canvas.drawRoundRect(17-half, 2, 17+half, 16.8f, 1.5f, 1.5f, paint);
        if (brush) {
            Path hair = new Path(); hair.moveTo(13.4f,16); hair.lineTo(20.6f,16);
            hair.quadTo(21.1f,21.1f,15.2f,26.4f); hair.quadTo(16.1f,22.4f,14.4f,20.4f); hair.close();
            fill(ink); canvas.drawPath(hair, paint);
        } else if (marker) {
            polygon(canvas, outline, 12,16, 22,16, 20.6f,20.1f, 13.4f,20.1f);
            polygon(canvas, ink, 13.9f,20, 20.2f,20, 20.2f,22.3f, 13.9f,25.5f);
        } else {
            polygon(canvas, pencil ? 0xffe1bc84 : 0xffa1aaa7, 14.2f,16, 19.8f,16, 17,24);
            polygon(canvas, ink, 16,21, 18,21, 17,25);
        }
        canvas.restoreToCount(saved);
        Path sample = new Path(); sample.moveTo(4,29); sample.cubicTo(11,24,14,32,22,28);
        sample.quadTo(28,25,32,26);
        if (brush) {
            sample.quadTo(28,26,24,29); sample.cubicTo(17,34,11,26,4,29); sample.close(); fill(ink);
        } else {
            stroke(ink, marker ? 4.8f : pencil ? 1.1f : 1.7f);
            if (marker) { paint.setAlpha(155); paint.setStrokeCap(Paint.Cap.SQUARE); }
        }
        canvas.drawPath(sample,paint);
    }
    private void mark(Canvas c) {
        stroke(ink, 2.2f);
        Path p = new Path();
        switch (kind) {
            case BoardInk.DOUBLE -> { c.drawLine(4,16,32,16,paint); c.drawLine(4,21,32,21,paint); }
            case BoardInk.DOTTED -> { fill(ink); for (int x=5;x<=32;x+=6) c.drawCircle(x,18,1.5f,paint); }
            case BoardInk.WAVY -> { p.moveTo(3,18); for(int x=3;x<30;x+=10) { p.rQuadTo(2.5f,-5,5,0); p.rQuadTo(2.5f,5,5,0); } c.drawPath(p,paint); }
            case BoardInk.BRACKET -> { p.moveTo(23,6); p.lineTo(14,6); p.lineTo(14,30); p.lineTo(23,30); c.drawPath(p,paint); }
            case BoardInk.CHECK -> { p.moveTo(6,18); p.lineTo(14,25); p.lineTo(29,10); c.drawPath(p,paint); }
            case BoardView.RECT -> c.drawRoundRect(5,9,31,27,2,2,paint);
            case BoardView.OVAL -> c.drawOval(4,9,32,27,paint);
            case BoardView.ARROW -> { c.drawLine(4,18,31,18,paint); p.moveTo(24,12); p.lineTo(31,18); p.lineTo(24,24); c.drawPath(p,paint); }
            default -> c.drawLine(4,18,32,18,paint);
        }
    }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 36; }
    @Override public int getIntrinsicHeight() { return 36; }
}
