package dev.jun.gyeot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;
import java.util.function.IntConsumer;

/** Pinch/pan and slow horizontal paging coexist: a pinch never also advances the picture. */
final class GalleryPhotoView extends ImageView {
    private final Matrix transform = new Matrix();
    private final ScaleGestureDetector pinch; private final GestureDetector taps;
    private final IntConsumer page; private final Runnable toggle, close;
    private float zoom = 1, panX, panY, fit = 1, downX, downY; private boolean multi;
    GalleryPhotoView(Context context, IntConsumer page, Runnable toggle, Runnable close) {
        super(context); this.page = page; this.toggle = toggle; this.close = close;
        setScaleType(ScaleType.MATRIX); setFocusable(true); setContentDescription("이미지. 좌우로 넘기기, 두 번 눌러 확대, 두 손가락으로 확대·축소");
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float next = Math.max(1, Math.min(5, zoom * detector.getScaleFactor())), factor = next / zoom;
                panX = (panX - detector.getFocusX() + getWidth() / 2f) * factor + detector.getFocusX() - getWidth() / 2f;
                panY = (panY - detector.getFocusY() + getHeight() / 2f) * factor + detector.getFocusY() - getHeight() / 2f;
                zoom = next; update(true); return true;
            }
        });
        taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { performClick(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                zoom = zoom > 1.05f ? 1 : 2.5f; panX = zoom > 1 ? (getWidth() / 2f - e.getX()) * (zoom - 1) : 0;
                panY = zoom > 1 ? (getHeight() / 2f - e.getY()) * (zoom - 1) : 0; update(true); return true;
            }
            @Override public boolean onScroll(MotionEvent down, MotionEvent event, float dx, float dy) {
                if (!pinch.isInProgress() && zoom > 1.05f) { panX -= dx; panY -= dy; update(true); } return true;
            }
        });
        setOnKeyListener((v, key, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_UP) return false;
            if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { page.accept(-1); return true; }
            if (key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { page.accept(1); return true; } return false;
        });
    }
    void reset() { zoom = 1; panX = panY = 0; update(true); }
    @Override public void setImageBitmap(Bitmap bitmap) { super.setImageBitmap(bitmap); update(true); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); update(true); }
    private void update(boolean clamp) {
        if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
        float w = getDrawable().getIntrinsicWidth(), h = getDrawable().getIntrinsicHeight(); if (w <= 0 || h <= 0) return;
        fit = Math.min(getWidth() / w, getHeight() / h);
        if (clamp) {
            float mx = Math.max(0, (w * fit * zoom - getWidth()) / 2), my = Math.max(0, (h * fit * zoom - getHeight()) / 2);
            panX = Math.max(-mx, Math.min(mx, panX)); panY = Math.max(-my, Math.min(my, panY));
        }
        transform.setScale(fit * zoom, fit * zoom); transform.postTranslate((getWidth() - w * fit * zoom) / 2 + panX, (getHeight() - h * fit * zoom) / 2 + panY); setImageMatrix(transform);
    }
    @Override public boolean performClick() { super.performClick(); toggle.run(); return true; }
    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); multi = false; }
        if (event.getPointerCount() > 1) multi = true;
        pinch.onTouchEvent(event); taps.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && !multi && zoom <= 1.05f) {
            float dx = event.getX() - downX, dy = event.getY() - downY, density = getResources().getDisplayMetrics().density;
            if (Math.abs(dx) > Math.max(48 * density, getWidth() * 0.15f) && Math.abs(dx) > Math.abs(dy) * 1.3f) { page.accept(dx < 0 ? 1 : -1); }
            else if (dy > 100 * density && dy > Math.abs(dx) * 1.5f) close.run();
        }
        return true;
    }
}
