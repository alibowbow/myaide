package dev.jun.gyeot;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.mozilla.geckoview.*;

/** Prepares Gecko and the bridge without opening a tab or requesting a website. */
final class BrowserRuntime {
    private static BrowserRuntime instance;
    private static boolean warmupScheduled;
    final GeckoRuntime runtime;
    private GeckoResult<WebExtension> bridge;

    /** Set once by the app: what the runtime is given when it is made (the saved logins Gecko fills forms from). */
    static volatile java.util.function.Consumer<GeckoRuntime> prepare;
    private BrowserRuntime(Context context) {
        runtime = GeckoRuntime.create(context.getApplicationContext());
        java.util.function.Consumer<GeckoRuntime> ready = prepare;
        if (ready != null) try { ready.accept(runtime); } catch (RuntimeException optional) { /* the browser works without it */ }
    }
    static BrowserRuntime get(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Browser must run on the main thread");
        if (instance == null) instance = new BrowserRuntime(context);
        return instance;
    }
    /**
     * The phone's light/dark (and other settings) told to Gecko (준: "휴대폰 다크모드를 라이트모드로 전환했는데 웹페이지 다크모드가 바로
     * 전환이 안돼"): the runtime is made once and kept, so pages went on seeing the theme it was made under (prefers-color-scheme)
     * until the app was closed. Nothing when the browser has not been opened.
     */
    static void configurationChanged(android.content.res.Configuration config) {
        if (instance == null || config == null || Looper.myLooper() != Looper.getMainLooper()) return;
        try { instance.runtime.configurationChanged(config); } catch (RuntimeException ignored) { /* the next page load reads it */ }
    }
    GeckoResult<WebExtension> bridge() {
        if (bridge == null) bridge = runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/browser/", BrowserEngine.EXTENSION);
        return bridge;
    }
    static void prepareWhenIdle(Activity activity) {
        if (instance != null || warmupScheduled) return;
        warmupScheduled = true;
        // Let the app's first frame and foreground work start before optional preparation.
        new Handler(Looper.getMainLooper()).postDelayed(() -> Looper.myQueue().addIdleHandler(() -> {
            warmupScheduled = false;
            if (instance != null || activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return false;
            ActivityManager memory = activity.getSystemService(ActivityManager.class);
            if ((memory != null && memory.isLowRamDevice()) || ((GyeotApp) activity.getApplicationContext()).store().voiceActive) return false;
            try {
                BrowserRuntime shared = get(activity);
                shared.runtime.warmUp();
                shared.bridge();
            } catch (RuntimeException | LinkageError ignored) { /* Optional; an explicit browser open still uses the normal path. */ }
            return false;
        }), 750);
    }
}
