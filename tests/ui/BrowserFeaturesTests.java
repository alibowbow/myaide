package dev.jun.gyeot;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import dev.jun.gyeot.core.BrowserAppLinks;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mozilla.geckoview.WebResponse;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * AI 브라우저 제한 풀기 (준: "쓸데 없는 제한이 너무 많아"), the parts that need Android: a link to another app the user tapped
 * (a KakaoTalk sign-in's intent://, tel:) becomes an intent the way Chrome makes it (Intent.parseUri, CATEGORY_BROWSABLE, no
 * component, no selector component, no URI grants, never this app), and a PDF the user downloads lands in the phone's
 * 다운로드 (pending until whole) with a notification that opens it, while an installer leaves nothing behind.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
public class BrowserFeaturesTests {
    @Test public void intentLinkHandedOffOnATapCleaned() throws Exception {
        String kakao = "intent://kakaolink?appkey=1#Intent;scheme=kakaolink;package=com.kakao.talk;component=com.evil/.Steal;launchFlags=0x10000043;"
            + "S.browser_fallback_url=https%3A%2F%2Fsharer.kakao.com%2Fpicker;SEL;component=com.evil/.Other;end";
        // The user's tap is handed over; an AI step (or the page reacting to it) is not: the old browser refused every one but YouTube.
        assertEquals(BrowserAppLinks.Verdict.HANDOFF, BrowserAppLinks.decide(kakao, true, true, false));
        assertEquals("an AI step keeps the refusal", BrowserAppLinks.Verdict.REFUSE, BrowserAppLinks.decide(kakao, true, true, true));
        Intent intent = BrowserAppIntent.of(kakao, "dev.jun.gyeot");
        assertNotNull("intent:// becomes an intent", intent);
        assertEquals("com.kakao.talk", intent.getPackage());
        assertNull("no component the page named", intent.getComponent());
        assertTrue("only an activity that opens from the web", intent.hasCategory(Intent.CATEGORY_BROWSABLE));
        int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        assertEquals("no URI grant the page asked for", 0, intent.getFlags() & grants);
        assertEquals("its own task", Intent.FLAG_ACTIVITY_NEW_TASK, intent.getFlags());
        assertNull("no clip data", intent.getClipData());
        // A selector (Android keeps one only when no package is named) loses its component as well.
        Intent viaSelector = BrowserAppIntent.of("intent://open?x=1#Intent;scheme=nmap;component=com.evil/.Steal;SEL;action=android.intent.action.VIEW;component=com.evil/.Other;end", "dev.jun.gyeot");
        assertNotNull(viaSelector);
        assertNull(viaSelector.getComponent());
        Intent selector = viaSelector.getSelector();
        if (selector != null) {
            assertNull("the selector's component dropped", selector.getComponent());
            assertTrue("and browsable too", selector.hasCategory(Intent.CATEGORY_BROWSABLE));
        }
        if (intent.getSelector() != null) assertNull(intent.getSelector().getComponent());
        assertEquals("the web page when no app takes it", "https://sharer.kakao.com/picker", BrowserAppLinks.fallback(kakao));
        assertEquals("Play opens on the app it names", "com.kakao.talk", BrowserAppIntent.packageOf(kakao));

        Intent call = BrowserAppIntent.of("tel:01012345678", "dev.jun.gyeot");
        assertNotNull(call);
        assertEquals(Intent.ACTION_VIEW, call.getAction());
        assertEquals("tel:01012345678", call.getDataString());
        assertTrue(call.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertNotNull("mailto:", BrowserAppIntent.of("mailto:a@example.com?subject=hi", "dev.jun.gyeot"));
        assertNull("never this app", BrowserAppIntent.of("intent:#Intent;action=android.intent.action.VIEW;package=dev.jun.gyeot;end", "dev.jun.gyeot"));
        assertNull("never a file", BrowserAppIntent.of("intent://sdcard/secret#Intent;scheme=file;end", "dev.jun.gyeot"));
        assertNull("never app data", BrowserAppIntent.of("intent://media/external/1#Intent;scheme=content;end", "dev.jun.gyeot"));
        assertNull("never a script", BrowserAppIntent.of("javascript:alert(1)", "dev.jun.gyeot"));
        assertNull("a broken link", BrowserAppIntent.of("intent://x#Intent;launchFlags=zz;end", "dev.jun.gyeot"));
    }

    /** The phone's Downloads, as MediaStore keeps it: rows, their files, and what was pending. */
    public static final class FakeMedia extends ContentProvider {
        final Map<Uri, ContentValues> rows = new LinkedHashMap<>();
        final Map<Uri, File> files = new LinkedHashMap<>();
        int next = 1, pendingAtWrite = -1;
        @Override public boolean onCreate() { return true; }
        @Override public Uri insert(Uri uri, ContentValues values) { Uri row = uri.buildUpon().appendPath(String.valueOf(next++)).build(); rows.put(row, new ContentValues(values)); return row; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            try {
                File file = files.computeIfAbsent(uri, u -> { try { return File.createTempFile("download", ".bin"); } catch (IOException e) { throw new RuntimeException(e); } });
                ContentValues row = rows.get(uri); pendingAtWrite = row == null || row.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == null ? -1 : row.getAsInteger(MediaStore.MediaColumns.IS_PENDING);
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
            } catch (RuntimeException e) { throw new java.io.FileNotFoundException(e.getMessage()); }
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { ContentValues row = rows.get(uri); if (row == null) return 0; row.putAll(values); return 1; }
        @Override public int delete(Uri uri, String selection, String[] args) { files.remove(uri); return rows.remove(uri) == null ? 0 : 1; }
        @Override public String getType(Uri uri) { ContentValues row = rows.get(uri); return row == null ? null : row.getAsString(MediaStore.MediaColumns.MIME_TYPE); }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            ContentValues row = rows.get(uri); if (row == null) return null;
            MatrixCursor cursor = new MatrixCursor(new String[]{MediaStore.MediaColumns.DISPLAY_NAME}); cursor.addRow(new Object[]{row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)}); return cursor;
        }
    }

    @Test public void pdfDownloadSavedToThePhonesDownloads() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        FakeMedia media = Robolectric.buildContentProvider(FakeMedia.class).create("media").get();
        byte[] pdf = "%PDF-1.7\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
        WebResponse response = new WebResponse.Builder("https://school.example.kr/files/download?id=7").statusCode(200)
            .addHeader("Content-Type", "application/pdf").addHeader("Content-Disposition", "attachment; filename*=UTF-8''%EC%8B%9C%ED%97%98%EC%A7%80.pdf")
            .addHeader("Content-Length", String.valueOf(pdf.length)).body(new ByteArrayInputStream(pdf)).build();
        JSONObject saved = BrowserDownloads.toDownloads(app.getContentResolver(), MediaStore.Downloads.EXTERNAL_CONTENT_URI, response);
        assertTrue("saved: " + saved, saved.optBoolean("ok"));
        assertEquals("시험지.pdf", saved.optString("name"));
        assertEquals("다운로드 완료 · 다운로드/시험지.pdf", saved.optString("message"));
        assertEquals("one row in the phone's Downloads", 1, media.rows.size());
        Uri row = Uri.parse(saved.optString("uri"));
        ContentValues values = media.rows.get(row);
        assertNotNull("the row the answer names", values);
        assertEquals("시험지.pdf", values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("application/pdf", values.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals("in Download/", android.os.Environment.DIRECTORY_DOWNLOADS, values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertEquals("pending while it was written", 1, media.pendingAtWrite);
        assertEquals("published when whole", Integer.valueOf(0), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertArrayEquals("the whole file", pdf, Files.readAllBytes(media.files.get(row).toPath()));
        assertEquals(pdf.length, saved.optLong("size"));

        // The notification opens the file it saved.
        BrowserDownloads.notifySaved(app, saved);
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        Notification shown = shadowOf(manager).getAllNotifications().stream()
            .filter(n -> "다운로드 완료".equals(String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)))).findFirst().orElse(null);
        assertNotNull("a 다운로드 완료 notification", shown);
        assertEquals("시험지.pdf", String.valueOf(shown.extras.getCharSequence(Notification.EXTRA_TEXT)));
        Intent opens = shadowOf(shown.contentIntent).getSavedIntent();
        assertEquals(Intent.ACTION_VIEW, opens.getAction());
        assertEquals(row, opens.getData());
        assertEquals("application/pdf", opens.getType());
        assertTrue("the viewer may read it", (opens.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertEquals("and the folder is one tap away", "다운로드 폴더", String.valueOf(shown.actions[0].title));

        // An installer is never kept: nothing is left behind.
        WebResponse apk = new WebResponse.Builder("https://x.example.com/get").statusCode(200)
            .addHeader("Content-Disposition", "attachment; filename=\"free.apk\"").body(new ByteArrayInputStream(new byte[]{'P', 'K', 3, 4})).build();
        try { BrowserDownloads.toDownloads(app.getContentResolver(), MediaStore.Downloads.EXTERNAL_CONTENT_URI, apk); fail("an installer was kept"); }
        catch (IOException refused) { assertTrue(refused.getMessage(), refused.getMessage().contains("실행 파일")); }
        // A broken download (the server said more than it sent) leaves nothing either.
        WebResponse cut = new WebResponse.Builder("https://x.example.com/report.zip").statusCode(200).addHeader("Content-Type", "application/zip")
            .addHeader("Content-Length", "999").body(new ByteArrayInputStream(new byte[]{'P', 'K', 3, 4, 0, 0})).build();
        try { BrowserDownloads.toDownloads(app.getContentResolver(), MediaStore.Downloads.EXTERNAL_CONTENT_URI, cut); fail("a cut download was kept"); }
        catch (IOException broken) { assertTrue(broken.getMessage(), broken.getMessage().contains("완성되지")); }
        assertEquals("only the PDF is in Downloads", 1, media.rows.size());
    }
}
