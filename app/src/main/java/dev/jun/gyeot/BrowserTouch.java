package dev.jun.gyeot;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.PanZoomController;

/**
 * A real tap on a page, at a point in the page view's pixels: the page sees the user's own touch. Used for the one click a
 * script click cannot make: a page's upload button, whose file chooser opens only for a real touch (the file the AI got
 * ready then goes into it, BrowserEngine.stagedUpload).
 */
final class BrowserTouch {
    private BrowserTouch() {}
    static void tap(GeckoSession session, double x, double y) {
        if (session == null || x < 0 || y < 0) return;
        try {
            PanZoomController touch = session.getPanZoomController();
            long down = SystemClock.uptimeMillis();
            MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, (float) x, (float) y, 0); press.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            touch.onTouchEvent(press); press.recycle();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                MotionEvent lift = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, (float) x, (float) y, 0); lift.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try { touch.onTouchEvent(lift); } catch (RuntimeException gone) { /* the page went away */ }
                lift.recycle();
            }, 70);
        } catch (RuntimeException unavailable) { /* no view for the page: the user taps the button (the file still goes in) */ }
    }
}
