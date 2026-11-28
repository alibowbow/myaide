package dev.jun.gyeot;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 들퍼레's edge handle (준: "들퍼레 우측 엣지바 살짝 건드려도 실행이 되어버려 좀 더 많이 당겨야 실행되게 해줘"): a tap or a short pull
 * into the screen calls nothing (the handle only nudges and springs back); pulled EdgeButton.PULL dp in, it calls 들퍼레's voice.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = GyeotApp.class, qualifiers = "w411dp-h891dp-xxhdpi")
public class EdgePullTests {
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static void send(View v, int action, float x, float y, long at) {
        MotionEvent e = MotionEvent.obtain(0, at, action, x, y, 0); v.dispatchTouchEvent(e); e.recycle(); idle();
    }
    private static Intent called(Application app) {
        Intent next; Intent voice = null;
        while ((next = shadowOf(app).getNextStartedActivity()) != null) if (next.getComponent() != null && next.getComponent().getClassName().equals(QuickVoiceActivity.class.getName())) voice = next;
        return voice;
    }

    @Test public void onlyALongPullCalls() {
        Application app = RuntimeEnvironment.getApplication(); AppStore store = ((GyeotApp) app).store();
        store.settings.edit().putBoolean("edgeButton", true).putBoolean("edgeRight", true).apply();
        PhoneService phone = Robolectric.buildService(PhoneService.class).create().get();
        EdgeButton edge = new EdgeButton(phone, store); edge.refresh(); idle();
        View v = edge.handle(); assertNotNull(v);
        float d = app.getResources().getDisplayMetrics().density, x = 600, y = 900; long t = SystemClock.uptimeMillis();
        called(app);
        // A tap: a nudge, nothing called.
        send(v, MotionEvent.ACTION_DOWN, x, y, t); send(v, MotionEvent.ACTION_UP, x, y, t + 60);
        assertNull("a tap calls nothing", called(app));
        // A brush into the screen, half the way: it stretches and springs back, nothing called.
        send(v, MotionEvent.ACTION_DOWN, x, y, t + 400);
        send(v, MotionEvent.ACTION_MOVE, x - 20 * d, y, t + 420);
        send(v, MotionEvent.ACTION_MOVE, x - 40 * d, y, t + 440);
        send(v, MotionEvent.ACTION_UP, x - 40 * d, y, t + 460);
        assertNull("a short pull (40dp, what used to call) calls nothing", called(app));
        // Pulled far enough: called once, at once.
        send(v, MotionEvent.ACTION_DOWN, x, y, t + 1000);
        send(v, MotionEvent.ACTION_MOVE, x - 40 * d, y, t + 1020);
        assertNull(called(app));
        send(v, MotionEvent.ACTION_MOVE, x - (EdgeButton.PULL + 4) * d, y, t + 1040);
        assertNotNull("pulled " + EdgeButton.PULL + "dp in: 들퍼레's voice", called(app));
        send(v, MotionEvent.ACTION_MOVE, x - 120 * d, y, t + 1060); send(v, MotionEvent.ACTION_UP, x - 120 * d, y, t + 1080);
        assertNull("once", called(app));
    }
}
