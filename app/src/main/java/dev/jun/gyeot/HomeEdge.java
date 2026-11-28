package dev.jun.gyeot;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.List;

/**
 * The AI 브라우저 opened from the home screen's left page (ClaudePage), back to 홈 with a swipe in from its right edge (준: "뒤로가기로
 * 홈페이지 돌아가는 건 넣지마, 홈버튼이나 우측 스와이프로"), as from the left page to 홈: a finger put down within EDGE of the right edge
 * and drawn left past FAR, mostly sideways, goes 홈. Anything else there (a tap, a scroll, a swipe up or down) stays the page's:
 * nothing is taken before the swipe is clear, and then the page is told the touch ended.
 *
 * The phone's back gesture owns that edge, so a stretch of it (the most Android lets an app keep, KEPT dp) is kept from it, with
 * a small handle showing where; with the phone's buttons the whole edge works. Main thread.
 */
final class HomeEdge {
    static final int EDGE = 22, FAR = 56, KEPT = 200;
    private final Activity activity; private final Ui ui; private final Runnable home;
    /** The kept stretch of the edge with its handle (drawn in the middle), over the browser's content; touches pass through it. */
    final View handle;
    private boolean on, tracking, taken;
    private float x0, y0;
    /** For the screen tests: how often the swipe went 홈. */
    int homes;

    HomeEdge(Activity activity, Ui ui, Runnable home) {
        this.activity = activity; this.ui = ui; this.home = home;
        Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG); pill.setColor(0x8C8E8E93);
        handle = new View(activity) {
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c);
                float w = ui.dp(4), h = ui.dp(40), x = getWidth() - ui.dp(3) - w, y = (getHeight() - h) / 2f;
                c.drawRoundRect(new RectF(x, y, x + w, y + h), w / 2, w / 2, pill);
            }
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                setSystemGestureExclusionRects(List.of(new Rect(0, 0, r - l, b - t)));
            }
        };
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        handle.setVisibility(View.GONE);
    }
    /** Laid over the content (android.R.id.content), at the right edge a little under the middle. */
    void attach(ViewGroup content) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ui.dp(EDGE), ui.dp(KEPT), Gravity.END | Gravity.CENTER_VERTICAL);
        lp.topMargin = ui.dp(48);
        content.addView(handle, lp);
    }
    void on(boolean on) { this.on = on; handle.setVisibility(on ? View.VISIBLE : View.GONE); if (!on) tracking = taken = false; }
    boolean on() { return on; }

    /**
     * A touch on the screen, before the page has it: true when it is this swipe's (the caller keeps it from the page). pass hands
     * an event on to the page (the cancel sent when the swipe is taken).
     */
    boolean touch(MotionEvent e, java.util.function.Consumer<MotionEvent> pass) {
        if (!on) return false;
        int width = activity.getWindow().getDecorView().getWidth();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                taken = false; tracking = width > 0 && e.getRawX() >= width - ui.dp(EDGE);
                x0 = e.getRawX(); y0 = e.getRawY();
                return false;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (taken) return true;
                if (!tracking) return false;
                float dx = e.getRawX() - x0, dy = Math.abs(e.getRawY() - y0);
                if (dy > ui.dp(24) && dy > -dx) { tracking = false; return false; }
                if (-dx >= ui.dp(FAR) && -dx > dy * 2) {
                    tracking = false; taken = true;
                    MotionEvent cancel = MotionEvent.obtain(e); cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try { pass.accept(cancel); } finally { cancel.recycle(); }
                    homes++; home.run();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                boolean was = taken; tracking = taken = false; return was;
            }
            default -> { return taken; }
        }
    }
}
