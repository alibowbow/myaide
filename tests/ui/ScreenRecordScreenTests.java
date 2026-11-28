package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import dev.jun.gyeot.core.ScreenRecordPlan;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.MediaCodecInfoBuilder;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowMediaCodecList;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 화면 녹화 in the app: the manifest has the screen capture service, the consent window and the quick settings tile; the row
 * sits in 녹음's list and is a list row, not a big button; its settings are two small switchers in a sheet.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenRecordScreenTests {
    @After public void forgetEncoders() { ShadowMediaCodecList.reset(); ScreenRecorder.rescan(); }

    @Test public void manifestDeclaresTheServiceTheConsentAndTheTile() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        PackageManager pm = app.getPackageManager();
        ServiceInfo service = pm.getServiceInfo(new ComponentName(app, ScreenRecordService.class), 0);
        assertFalse("only the app starts it", service.exported);
        int types = service.getForegroundServiceType();
        assertTrue("type mediaProjection: " + types, (types & ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0);
        assertTrue("and microphone, for a recording with sound: " + types, (types & ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0);
        assertTrue("and shortService, to answer a start Android allows no other type for: " + types, (types & ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE) != 0);
        PackageInfo info = pm.getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS);
        List<String> asked = Arrays.asList(info.requestedPermissions);
        assertTrue(asked.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"));
        assertTrue(asked.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE"));
        assertTrue(asked.contains("android.permission.RECORD_AUDIO"));
        ActivityInfo consent = pm.getActivityInfo(new ComponentName(app, ScreenRecordActivity.class), 0);
        assertFalse(consent.exported);
        List<ResolveInfo> tiles = pm.queryIntentServices(new Intent(TileService.ACTION_QS_TILE).setPackage(app.getPackageName()), 0);
        ResolveInfo tile = tiles.stream().filter(r -> r.serviceInfo.name.equals(ScreenRecordTile.class.getName())).findFirst().orElse(null);
        assertNotNull("the 화면 녹화 tile: " + tiles, tile);
        assertEquals("only the system UI binds it", "android.permission.BIND_QUICK_SETTINGS_TILE", tile.serviceInfo.permission);
        assertEquals("화면 녹화", tile.serviceInfo.loadLabel(pm).toString());
    }

    @Test public void readsThePhonesEncodersThroughMediaCodecList() {
        addHevcEncoder();
        List<ScreenRecorder.Codec> found = ScreenRecorder.encoders();
        assertEquals(1, found.size());
        ScreenRecorder.Codec hevc = found.get(0);
        assertEquals(ScreenRecordPlan.HEVC, hevc.mime());
        assertTrue(hevc.hardware());
        assertTrue(hevc.sizeSupported(1440, 3120));
        assertEquals("its best HEVC Main level, pinned so the encoder does not pick a lower one", MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51, hevc.topLevel);
        ScreenRecordPlan.Plan plan = ScreenRecordPlan.best(1440, 3120, ScreenRecordPlan.Quality.BEST, found);
        assertNotNull(plan);
        assertEquals("c2.test.hevc.encoder", plan.encoder());
        assertEquals(1440, plan.width()); assertEquals(3120, plan.height());
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void rowBesideRecordingAndACompactSheet() throws Exception {
        addHevcEncoder();
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        settle(a);
        View decor = a.getWindow().getDecorView();
        View tab = exact(decor, "녹음"); assertNotNull("녹음 tab", tab);
        assertTrue(tab.performClick()); settle(a);
        View title = exact(decor, "화면 녹화"); assertNotNull("화면 녹화 row", title);
        View row = title; while (!row.isClickable() && row.getParent() instanceof View p) row = p;
        View recording = text(decor, "눌러서 녹음 시작"); assertNotNull(recording);
        View recordingRow = recording; while (!recordingRow.isClickable() && recordingRow.getParent() instanceof View p) recordingRow = p;
        assertSame("in 녹음's list, beside it", recordingRow.getParent(), row.getParent());
        float density = a.getResources().getDisplayMetrics().density;
        // Its line becomes what the recording will be, worked out off the main thread from the phone's encoders.
        TextView line = (TextView) ((Ui.ListRow) row).parts.subtitle();
        for (int i = 0; i < 50 && !line.getText().toString().contains("fps"); i++) { Thread.sleep(50); settle(a); }
        String said = line.getText().toString();
        assertTrue(said, said.matches("\\d+×\\d+ · (60|30)fps · HEVC \\d+Mbps"));
        assertEquals("the whole plan on one line: " + said, 1, line.getLineCount());
        assertEquals(0, line.getLayout().getEllipsisCount(0));
        assertTrue("a list row, not a big button: " + row.getHeight() / density + "dp", row.getHeight() <= 74 * density);
        // The sound at its end, which opens the settings.
        View settings = exact(decor, "미디어 ›"); assertNotNull("the sound at the row's end", settings);
        assertTrue(settings.getContentDescription().toString().startsWith("화면 녹화 설정"));
        shot(decor, "screen_record_row");

        assertTrue(settings.performClick()); settle(a);
        Dialog sheet = ShadowDialog.getLatestDialog(); assertNotNull(sheet); assertTrue(sheet.isShowing());
        View panel = sheet.getWindow().getDecorView();
        layout(panel, a); shadowOf(Looper.getMainLooper()).idle(); layout(panel, a);
        View best = exact(panel, "최고"), normal = exact(panel, "보통"), mic = exact(panel, "마이크+미디어");
        assertNotNull(best); assertNotNull(normal); assertNotNull(mic);
        View qualities = (View) best.getParent();
        assertSame("one switcher for 화질", qualities, normal.getParent());
        assertTrue("a 34dp switcher, not a 48dp bar: " + qualities.getHeight() / density, qualities.getHeight() <= 42 * density);
        View line2 = (View) qualities.getParent();
        assertTrue("at the end of its line, not the whole width", qualities.getWidth() < line2.getWidth());
        assertTrue(normal.performClick()); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(ScreenRecordPlan.Quality.NORMAL, ScreenRecordService.quality(((GyeotApp) a.getApplication()).store().settings));
        assertTrue(mic.performClick()); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(ScreenRecordPlan.Sound.BOTH, ScreenRecordService.sound(((GyeotApp) a.getApplication()).store().settings));
        View preview = null;
        for (int i = 0; i < 50 && (preview = text(panel, "1080×")) == null; i++) { Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle(); layout(panel, a); }
        assertNotNull("the sheet says what 보통 will record", preview);
        String next = ((TextView) preview).getText().toString();
        assertTrue(next, next.matches("1080×\\d+ · 30fps · HEVC [0-9.]+Mbps · 마이크\\+미디어"));
        shot(panel, "screen_record_sheet");
        sheet.dismiss(); settle(a);
        // The row follows the new settings.
        for (int i = 0; i < 50 && !line.getText().toString().startsWith("1080×"); i++) { Thread.sleep(50); settle(a); }
        assertTrue(line.getText().toString(), line.getText().toString().matches("1080×\\d+ · 30fps · HEVC [0-9.]+Mbps"));
        assertNotNull(exact(decor, "마이크+미디어 ›"));
    }

    @Test public void unfinishedPartsAreLookedForWithoutFailing() {
        // Robolectric has no media store: nothing to delete, and no exception on the way.
        assertEquals("", ScreenRecorder.clearUnfinished(RuntimeEnvironment.getApplication()));
    }

    /** A hardware HEVC encoder that takes a Surface, as a phone lists it. */
    private static void addHevcEncoder() {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, ScreenRecordPlan.HEVC);
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, 8192);
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, 8192);
        MediaCodecInfo.CodecProfileLevel main = new MediaCodecInfo.CodecProfileLevel();
        main.profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain; main.level = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51;
        MediaCodecInfo.CodecCapabilities caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder().setMediaFormat(format).setIsEncoder(true)
            .setProfileLevels(new MediaCodecInfo.CodecProfileLevel[]{main}).setColorFormats(new int[]{MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface}).build();
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("c2.test.hevc.encoder").setIsEncoder(true).setIsVendor(true)
            .setIsHardwareAccelerated(true).setIsSoftwareOnly(false).setCapabilities(caps).build());
        ScreenRecorder.rescan();
    }

    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static View exact(View root, String words) { return find(root, x -> x instanceof TextView t && x.isShown() && t.getText().toString().equals(words)); }
    private static View text(View root, String start) { return find(root, x -> x instanceof TextView t && x.isShown() && t.getText().toString().startsWith(start)); }
    private static void layout(View decor) {
        var m = decor.getResources().getDisplayMetrics();
        decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
    }
    private static void layout(View panel, Activity a) {
        var m = a.getResources().getDisplayMetrics();
        panel.measure(View.MeasureSpec.makeMeasureSpec(Math.min(m.widthPixels - Math.round(24 * m.density), Math.round(480 * m.density)), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.AT_MOST));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
    }
    private static void settle(Activity a) { View d = a.getWindow().getDecorView(); for (int i = 0; i < 3; i++) { layout(d); shadowOf(Looper.getMainLooper()).idle(); } }
    private static void shot(View view, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(Math.max(1, view.getWidth()), Math.max(1, view.getHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); view.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
}
