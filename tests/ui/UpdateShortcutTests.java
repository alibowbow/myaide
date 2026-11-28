package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import dev.jun.gyeot.core.UpdateCheck;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The home screen's 업데이트 shortcut (준: "업데이트 바로가기 버튼 만들어줘. 새로운 업데이트가 있으면 바로 다운 받기까지 되고 없으면
 * 업데이트 페이지 열리기"): what it opens (HtmlWallpaper.target "update"), and the update page acting on it at once: a newer build
 * goes straight to the download (here it stops at the one-time 이 출처 허용 question, as on a phone that never allowed it); none
 * known, a check starts on the update page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = GyeotApp.class)
public class UpdateShortcutTests {
    @After public void forget() { ((GyeotApp) RuntimeEnvironment.getApplication()).store().updater.available = null; }

    private static UpdateCheck.Update update(String version) throws Exception {
        java.lang.reflect.Constructor<UpdateCheck.Update> make = UpdateCheck.Update.class.getDeclaredConstructor(String.class, String.class, String.class, String.class, String.class, long.class, List.class);
        make.setAccessible(true);
        return make.newInstance(version, "https://example.invalid/gyeot.apk", "", "00", "https://example.invalid/release", 1L, List.of());
    }
    private static boolean says(View v, String words) {
        if (v instanceof TextView t && t.getText().toString().contains(words)) return true;
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) if (says(g.getChildAt(i), words)) return true;
        return false;
    }
    private static Intent asked() {
        Intent target = HtmlWallpaper.target(RuntimeEnvironment.getApplication(), "update");
        assertNotNull("the shortcut opens something", target);
        assertEquals(MainActivity.class.getName(), target.getComponent().getClassName());
        assertEquals("update", target.getStringExtra(MainActivity.SETTINGS_PAGE));
        assertTrue(target.getBooleanExtra(MainActivity.UPDATE_NOW, false));
        return target;
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi") public void aNewerBuildIsDownloadedAtOnce() throws Exception {
        AppStore store = ((GyeotApp) RuntimeEnvironment.getApplication()).store();
        store.updater.available = update("9.9.9");
        Activity a = Robolectric.buildActivity(MainActivity.class, asked()).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("straight on to installing: Android asks once to allow this source first", d);
        assertTrue("the one-time 이 출처 허용 question", says(d.getWindow().getDecorView(), "이 출처 허용"));
        assertFalse("not asked twice when the page opens again", a.getIntent().getBooleanExtra(MainActivity.UPDATE_NOW, false));
    }

    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi") public void noneKnownTheUpdatePageChecksNow() throws Exception {
        AppStore store = ((GyeotApp) RuntimeEnvironment.getApplication()).store();
        store.updater.available = null;
        Activity a = Robolectric.buildActivity(MainActivity.class, asked()).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the update page", says(a.getWindow().getDecorView(), "업데이트"));
        assertTrue("checking now (or already answered): " + store.updater.notice, store.updater.busy || !store.updater.notice.isBlank());
        Dialog d = ShadowDialog.getLatestDialog();
        assertTrue("nothing to install: no question", d == null || !d.isShowing() || !says(d.getWindow().getDecorView(), "이 출처 허용"));
    }
}
