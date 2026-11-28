package dev.jun.gyeot;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * 홈's picture over everything while the phone switches from the AI 브라우저 to the home screen (준: "슬라이드 전환할때 홈으로 바로
 * 가지않고 다시 깜빡이는 효과가 들어가", "깜빡이는 효과가 이중으로", and the same again in 0.4.675's video). The swipe ends with 홈's
 * picture filling the browser; then the phone plays its own going-home transition whatever the app asks (the home screen is
 * another task, and One UI ignored both makeCustomAnimation(0, 0) and FLAG_ACTIVITY_NO_ANIMATION): the browser gone at once,
 * the phone's wallpaper alone, then 홈 fading and shrinking in over it. So that picture is laid over every window for those
 * moments (a window over other apps: the overlay permission 퍼레 uses, else 들퍼레 폰 조작's own), 홈 comes in under it, and it
 * is lifted once 홈 is in front and the phone's transition has run. Touches pass through it. Nothing when neither window can
 * be had: then the phone's transition shows as before. Main thread.
 */
final class HomeCurtain {
    private HomeCurtain() {}
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Never left over the screen longer than this, whatever happens to 홈. */
    static final long MOST_MS = 1800;
    /** After 홈 is resumed: the phone's transition (its wallpaper, then 홈 fading in) runs about half a second under it. */
    static final long AFTER_HOME_MS = 650;
    private static View shown;
    private static WindowManager manager;
    private static final Runnable lift = HomeCurtain::lift;

    /** For the screen tests: whether it is over the screen now. */
    static boolean covering() { return shown != null; }

    /**
     * Covers the screen with picture where 홈's window was when it was taken (at, in screen pixels). False when no window over
     * other apps can be had.
     */
    static boolean cover(Context context, Bitmap picture, Rect at) {
        if (picture == null || at == null || at.isEmpty()) return false;
        RecordBubble.Host host = RecordBubble.host(context.getApplicationContext());
        if (host == null) return false;
        remove();
        try {
            WindowManager wm = host.context().getSystemService(WindowManager.class);
            Rect place = new Rect(at);
            // The service's window sits over the status and navigation bars: there it covers only what is between them, so the
            // clock and the bars stay as they are (the app's overlay is under them and covers it all).
            if (host.type() == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                Insets bars = wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Rect screen = wm.getCurrentWindowMetrics().getBounds();
                place.intersect(new Rect(screen.left + bars.left, screen.top + bars.top, screen.right - bars.right, screen.bottom - bars.bottom));
            }
            ImageView view = new ImageView(host.context());
            view.setImageBitmap(picture); view.setScaleType(ImageView.ScaleType.MATRIX);
            // The part of the picture at place: the picture is the whole window (at), scaled down.
            float sx = picture.getWidth() / (float) at.width(), sy = picture.getHeight() / (float) at.height();
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(1 / sx, 1 / sy); m.postTranslate(at.left - place.left, at.top - place.top);
            view.setImageMatrix(m);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(place.width(), place.height(), host.type(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.OPAQUE);
            p.gravity = Gravity.TOP | Gravity.LEFT; p.x = place.left; p.y = place.top; p.windowAnimations = 0;
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS; p.setFitInsetsTypes(0);
            p.setTitle("들퍼레 홈 전환");
            wm.addView(view, p); shown = view; manager = wm;
        } catch (RuntimeException refused) { remove(); return false; }
        MAIN.removeCallbacks(lift); MAIN.postDelayed(lift, MOST_MS);
        return true;
    }
    /** 홈 is in front: lifted once the phone's own transition has run under it. */
    static void homeShown() {
        if (shown == null) return;
        MAIN.removeCallbacks(lift); MAIN.postDelayed(lift, AFTER_HOME_MS);
    }
    /** A quick fade, so 홈 as it is now (the clock may have moved on since the picture) takes its place softly. */
    private static void lift() {
        View view = shown; if (view == null) return;
        view.animate().alpha(0f).setDuration(140).withEndAction(HomeCurtain::remove).start();
    }
    static void remove() {
        MAIN.removeCallbacks(lift);
        View view = shown; WindowManager wm = manager; shown = null; manager = null;
        if (view != null && wm != null) try { wm.removeViewImmediate(view); } catch (RuntimeException gone) { /* already gone */ }
    }
}
