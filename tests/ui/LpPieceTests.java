package dev.jun.gyeot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.ComponentName;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import dev.jun.gyeot.core.PieceMusic;
import dev.jun.gyeot.core.PieceTune;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The LP page's host (준: "하방 스크롤 안하고 곡목록 보기 및 선택가능하게해줘", "내 폴더에 있는 음악들도 재생가능하게 해줘", "파일 선택해서
 * 리스트 추가 할 수 있게"): the piece's fixes from resources and its plain fallback, the add-on's address under the piece's own
 * (PieceExtras), the picked files' list with no address of a file in it, a file's bytes with Range for seeking (206,
 * Content-Range, 416), and Android's picker for music files (MusicPickActivity) whose picks come onto the list.
 * The page side is tests/browser/vinyl.test.js.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
public class LpPieceTests {
    static final String AUTH = "dev.jun.gyeot.test.music", PIECE = "/Claude-Opus-5.5-100-HTML-Files/057-vinyl-listening-room.html";
    /** The app's own address for the LP piece, and the creator's site. */
    static final String LP = "https://lp.gyeot.local", SITE = "https://miaai-lab.github.io";
    static final Map<String, byte[]> DOCS = new HashMap<>();
    static File dir;

    /** A documents provider stand-in: a few files with their names, sizes and types. */
    public static final class Docs extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            File f = new File(dir, uri.getLastPathSegment());
            if (!f.isFile()) throw new FileNotFoundException(uri.toString());
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Cursor query(Uri uri, String[] cols, String sel, String[] args, String order) {
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            byte[] b = DOCS.get(uri.getLastPathSegment());
            if (b != null) c.addRow(new Object[]{uri.getLastPathSegment(), b.length});
            return c;
        }
        @Override public String getType(Uri uri) { String n = uri.getLastPathSegment(); return n.endsWith(".mp3") ? "audio/mpeg" : n.endsWith(".ogg") ? "application/ogg" : "text/plain"; }
        @Override public Uri insert(Uri uri, ContentValues v) { return null; }
        @Override public int delete(Uri uri, String s, String[] a) { return 0; }
        @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    }

    Context app;
    @Before public void files() throws Exception {
        app = RuntimeEnvironment.getApplication();
        dir = new File(app.getCacheDir(), "docs"); dir.mkdirs(); DOCS.clear();
        byte[] song = new byte[1000]; for (int i = 0; i < song.length; i++) song[i] = (byte) (i * 7 + 3);
        put("Night_Drive.mp3", song); put("rain.ogg", new byte[]{1, 2, 3, 4, 5}); put("notes.txt", "hello".getBytes(StandardCharsets.UTF_8));
        Robolectric.setupContentProvider(Docs.class, AUTH);
    }
    static void put(String name, byte[] b) throws Exception { DOCS.put(name, b); try (FileOutputStream o = new FileOutputStream(new File(dir, name))) { o.write(b); } }
    static Uri doc(String name) { return Uri.parse("content://" + AUTH + "/doc/" + name); }

    static WebResourceRequest req(String method, String url, Map<String, String> headers) {
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(url); }
            @Override public boolean isForMainFrame() { return false; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return method; }
            @Override public Map<String, String> getRequestHeaders() { return headers; }
        };
    }
    static WebResourceRequest get(String path, String... headers) {
        Map<String, String> h = new HashMap<>(); h.put("Referer", LP + PIECE);
        for (int i = 0; i + 1 < headers.length; i += 2) h.put(headers[i], headers[i + 1]);
        return req("GET", LP + "/__gyeot/" + path, h);
    }
    static String k(String path) { return path + (path.contains("?") ? "&" : "?") + "k=" + PieceExtras.key(); }
    WebResourceResponse serve(WebResourceRequest r) { return PieceExtras.handle(app, r, i -> opened.add(i)); }
    final List<Intent> opened = new ArrayList<>();
    static byte[] read(WebResourceResponse r) throws Exception { try (InputStream in = r.getData()) { ByteArrayOutputStream o = new ByteArrayOutputStream(); in.transferTo(o); return o.toByteArray(); } }
    static String text(WebResourceResponse r) throws Exception { return new String(read(r), StandardCharsets.UTF_8); }
    static String header(WebResourceResponse r, String name) { Map<String, String> h = r.getResponseHeaders(); return h == null ? null : h.get(name); }

    @Test public void fixesComeFromResourcesAndAPieceThatChangedIsLeftAsItIs() {
        List<PieceTune.Fix> fixes = PieceTune.fixes(PIECE);
        assertTrue("the noise, the hooks and the add-on: " + fixes.size(), fixes.size() >= 20);
        assertTrue(PieceTune.knows("miaai-lab.github.io", PIECE));
        // A page holding each fix's text once (the live piece is checked in tests/browser/vinyl.test.js).
        StringBuilder page = new StringBuilder("<html><body>\n");
        for (PieceTune.Fix f : fixes) page.append(f.find()).append("\n/* ~ */\n");
        String tuned = PieceTune.tune(PIECE, page.toString());
        assertNotNull("every fix matched once", tuned);
        assertTrue("the piece's state for the add-on", tuned.contains("window.__lp = {"));
        assertTrue("the add-on's files from the app", tuned.contains("/__gyeot/addon/vinyl.js") && tuned.contains("\"three\":\"/__gyeot/lib/three.module.js\""));
        String keyed = PieceExtras.keyed(tuned);
        assertFalse(keyed.contains(PieceExtras.PLACE)); assertTrue(keyed.contains(PieceExtras.key()));
        assertTrue("a run's own key: " + PieceExtras.key(), PieceExtras.key().matches("[0-9a-f]{32}"));
        // One fix's text gone (the piece was changed): nothing is changed, the noise fixes neither, and which one is said.
        int gone = 12;
        String changed = page.toString().replace(fixes.get(gone - 1).find(), "/* rewritten */");
        assertNull(PieceTune.tune(PIECE, changed));
        PieceTune.Tuned why = PieceTune.apply(PIECE, changed);
        assertEquals(gone, why.failed()); assertTrue(why.why(), why.why().startsWith("찾지 못함"));
        // Twice is as bad as never.
        assertNull(PieceTune.tune(PIECE, page + fixes.get(0).find()));
        assertEquals(1, PieceTune.apply(PIECE, page + fixes.get(0).find()).failed());
        // The other turntable keeps its own three.
        assertEquals(3, PieceTune.fixes("/Sonnet-5.5-100-HTML-Files/036-lofi-turntable.html").size());
    }

    @Test public void rangesAsTheAudioElementAsksThem() {
        assertSame(PieceMusic.ALL, PieceMusic.range(null, 1000));
        assertSame(PieceMusic.ALL, PieceMusic.range("items=0-1", 1000));
        assertEquals(new PieceMusic.Range(0, 999), PieceMusic.range("bytes=0-", 1000));
        assertEquals(new PieceMusic.Range(100, 199), PieceMusic.range("bytes=100-199", 1000));
        assertEquals(new PieceMusic.Range(990, 999), PieceMusic.range("bytes=-10", 1000));
        assertEquals(new PieceMusic.Range(500, 999), PieceMusic.range("bytes=500-5000", 1000));
        assertEquals("of several, the first", new PieceMusic.Range(0, 1), PieceMusic.range("bytes=0-1, 5-6", 1000));
        assertNull(PieceMusic.range("bytes=1000-", 1000));
        assertNull(PieceMusic.range("bytes=5-2", 1000));
        assertNull(PieceMusic.range("bytes=x-", 1000));
        assertEquals("Night Drive", PieceMusic.title("Night_Drive.mp3"));
        assertTrue(PieceMusic.audio("application/octet-stream", "a.flac")); assertFalse(PieceMusic.audio("text/plain", "a.mp3"));
        // what the web view cannot play does not come onto the list
        for (String[] no : new String[][] { {"audio/amr", "a.amr"}, {"audio/3gpp", "a.3gp"}, {"audio/midi", "a.mid"}, {"application/octet-stream", "a.midi"}, {null, "a.amr"}, {"audio/AMR; codecs=x", "a"} })
            assertFalse(no[0] + " " + no[1], PieceMusic.audio(no[0], no[1]));
        assertTrue(PieceMusic.audio("audio/mpeg", "a.mp3")); assertTrue(PieceMusic.audio("audio/ogg; codecs=opus", "a"));
        assertTrue(PieceMusic.validId(PieceMusic.id("content://x/1"))); assertEquals(PieceMusic.id("content://x/1"), PieceMusic.id("content://x/1"));
    }

    @Test public void theListShowsTitlesNeverAddressesAndAFileIsServedWithRanges() throws Exception {
        PieceMusicStore store = PieceMusicStore.of(app);
        store.add(List.of(doc("Night_Drive.mp3"), doc("notes.txt"), doc("rain.ogg"), doc("Night_Drive.mp3")), null);
        assertEquals(403, serve(get("music/list.json")).getStatusCode());
        assertEquals("another page's key", 403, serve(get("music/list.json?k=" + "0".repeat(32))).getStatusCode());
        assertEquals("from another page", 403, serve(get(k("music/list.json"), "Referer", "https://miaai-lab.github.io/other/piece.html")).getStatusCode());
        assertEquals("the LP page's path on the creator's site", 403, serve(get(k("music/list.json"), "Referer", SITE + PIECE)).getStatusCode());
        // On the creator's site (where other pieces' code runs) nothing of 내 음악 is ever answered, even with the key.
        assertEquals(404, serve(req("GET", SITE + "/__gyeot/" + k("music/list.json"), Map.of("Referer", SITE + PIECE))).getStatusCode());
        assertEquals(404, serve(req("GET", SITE + "/__gyeot/" + k("music/pick?kind=files"), Map.of())).getStatusCode());
        assertEquals(404, serve(req("GET", SITE + "/__gyeot/" + k("deck/stats?avg=1"), Map.of())).getStatusCode());
        WebResourceResponse list = serve(get(k("music/list.json")));
        assertEquals(200, list.getStatusCode()); assertEquals("no-store", header(list, "Cache-Control"));
        String body = text(list);
        assertFalse("no file's address: " + body, body.contains("content:") || body.contains(AUTH));
        JSONObject j = new JSONObject(body); JSONArray items = j.getJSONArray("items");
        assertEquals("sound only, each file once: " + body, 2, items.length());
        JSONObject night = items.getJSONObject(0);
        assertEquals("Night Drive", night.getString("title")); assertEquals(1000, night.getLong("size")); assertTrue(night.getBoolean("seek"));
        String id = night.getString("id");
        assertTrue(PieceMusic.validId(id));
        byte[] song = DOCS.get("Night_Drive.mp3");

        WebResourceResponse part = serve(get(k("music/" + id), "Range", "bytes=100-199"));
        assertEquals(206, part.getStatusCode());
        assertEquals("audio/mpeg", part.getMimeType());
        assertEquals("bytes 100-199/1000", header(part, "Content-Range"));
        assertEquals("100", header(part, "Content-Length")); assertEquals("bytes", header(part, "Accept-Ranges"));
        assertArrayEquals(Arrays.copyOfRange(song, 100, 200), read(part));
        // The web view skips to the range's start on the stream itself: the same bytes either way.
        WebResourceResponse skipped = serve(get(k("music/" + id), "Range", "bytes=100-199"));
        assertEquals("sized for the web view's own check", 200, skipped.getData().available());
        try (InputStream in = skipped.getData()) { assertEquals(100, in.skip(100)); byte[] b = in.readAllBytes(); assertArrayEquals(Arrays.copyOfRange(song, 100, 200), b); }
        WebResourceResponse tail = serve(get(k("music/" + id), "Range", "bytes=-10"));
        assertEquals("bytes 990-999/1000", header(tail, "Content-Range")); assertArrayEquals(Arrays.copyOfRange(song, 990, 1000), read(tail));
        WebResourceResponse past = serve(get(k("music/" + id), "Range", "bytes=2000-"));
        assertEquals(416, past.getStatusCode()); assertEquals("bytes */1000", header(past, "Content-Range"));
        WebResourceResponse whole = serve(get(k("music/" + id)));
        assertEquals(200, whole.getStatusCode()); assertEquals("1000", header(whole, "Content-Length")); assertArrayEquals(song, read(whole));
        assertEquals("not an id", 404, serve(get(k("music/../shelf.json"))).getStatusCode());
        assertEquals("no picture in it", 404, serve(get(k("music/" + id + "/art"))).getStatusCode());

        // Gone from the phone: 404 (the page says so on its line and goes on).
        new File(dir, "rain.ogg").delete();
        assertEquals(404, serve(get(k("music/" + items.getJSONObject(1).getString("id")))).getStatusCode());
        // Off the list: its read permission given back, and nothing served for it any more.
        app.getContentResolver().takePersistableUriPermission(doc("Night_Drive.mp3"), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        int rev = j.getInt("rev");
        assertEquals("never on the creator's site", 404, serve(req("DELETE", SITE + "/__gyeot/" + k("music/" + id), Map.of())).getStatusCode());
        assertNotNull("still on the list", PieceMusicStore.of(app).shelf().find(id));
        WebResourceResponse left = serve(req("DELETE", LP + "/__gyeot/" + k("music/" + id), Map.of()));
        JSONObject after = new JSONObject(text(left));
        assertEquals(1, after.getJSONArray("items").length()); assertTrue(after.getInt("rev") > rev);
        assertEquals(404, serve(get(k("music/" + id))).getStatusCode());
        assertTrue("permission given back", app.getContentResolver().getPersistedUriPermissions().stream().noneMatch(p -> p.getUri().equals(doc("Night_Drive.mp3"))));
        // Kept in the app's files, read again only when changed.
        assertEquals(1, PieceMusic.read(new String(java.nio.file.Files.readAllBytes(new File(app.getFilesDir(), "piece-music/shelf.json").toPath()), StandardCharsets.UTF_8)).items().size());
    }

    @Test public void theAddOnsFilesAndTheOpenerComeFromTheApp() throws Exception {
        // The LP piece's frames load it from the app's own address; every other address as it is.
        assertEquals(LP + PIECE, PieceExtras.frameUrl(SITE + PIECE));
        assertEquals(LP + PIECE + "?a=1#b", PieceExtras.frameUrl(SITE + PIECE + "?a=1#b"));
        assertEquals(SITE + PIECE + "x", PieceExtras.frameUrl(SITE + PIECE + "x"));
        assertEquals(SITE + "/Other/piece.html", PieceExtras.frameUrl(SITE + "/Other/piece.html"));
        assertEquals("page:abc", PieceExtras.frameUrl("page:abc"));
        assertEquals(LP + PIECE, PieceExtras.framed(new JSONObject(), SITE + PIECE).getString("frame"));
        for (String at : new String[] { LP, SITE }) {
            WebResourceResponse js = serve(req("GET", at + "/__gyeot/addon/vinyl.js", Map.of()));
            assertEquals(at, 200, js.getStatusCode()); assertEquals("text/javascript", js.getMimeType());
            assertTrue(text(js).contains("window.__gy = {"));
            assertEquals("text/css", serve(req("GET", at + "/__gyeot/addon/vinyl.css", Map.of())).getMimeType());
            assertEquals(200, serve(req("GET", at + "/__gyeot/addon/deck3d.js", Map.of())).getStatusCode());
            WebResourceResponse three = serve(req("GET", at + "/__gyeot/lib/three.module.js", Map.of()));
            assertEquals(200, three.getStatusCode()); assertTrue(new String(read(three), 0, 120, StandardCharsets.UTF_8).contains("Three.js Authors"));
            assertEquals("only the add-on's own names", 404, serve(req("GET", at + "/__gyeot/addon/other.js", Map.of())).getStatusCode());
        }
        // The 3D deck's walnut: three pictures, JPEG
        for (String w : new String[] { "col", "nor", "rgh" }) {
            WebResourceResponse jpg = serve(req("GET", LP + "/__gyeot/addon/walnut-" + w + ".jpg", Map.of()));
            assertEquals(200, jpg.getStatusCode()); assertEquals("image/jpeg", jpg.getMimeType());
            byte[] b = read(jpg); assertTrue("a JPEG", b.length > 1000 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8);
        }
        assertEquals(404, serve(req("GET", LP + "/__gyeot/addon/walnut-x.jpg", Map.of())).getStatusCode());
        assertNull("another piece goes on to the web as before", serve(req("GET", SITE + "/Other/piece.html", Map.of())));
        assertNull(serve(req("GET", "https://example.com/__gyeot/music/list.json", Map.of())));
        assertEquals("the app's own address holds the LP page only", 404, serve(req("GET", LP + "/Other/piece.html", Map.of())).getStatusCode());
        assertTrue("the tuned page is put in only with a key", PieceExtras.keyless("<script>window.__GY = { key: \"" + PieceExtras.PLACE + "\" };</script>").contains("key: \"\""));

        JSONObject asked = new JSONObject(text(serve(get(k("music/pick?kind=folder"))))) ;
        assertTrue(asked.getBoolean("pending")); assertEquals("app", asked.getString("via"));
        assertEquals(1, opened.size());
        assertEquals(new ComponentName(app, MusicPickActivity.class), opened.get(0).getComponent());
        assertEquals("folder", opened.get(0).getStringExtra(MusicPickActivity.KIND));
        assertEquals("open", PieceMusicStore.of(app).pick());
        serve(get(k("music/pick?kind=../../x")));
        assertEquals("files", opened.get(1).getStringExtra(MusicPickActivity.KIND));
        assertEquals(204, serve(get(k("deck/stats?avg=9.8&p95=14&fps=30&pr=2&w=768&h=600"))).getStatusCode());
        assertEquals("LP 3D 평균 9.8ms p95 14ms 30fps ×2 768×600 그리기 16번 삼각형 17880개",
            PieceExtras.statsLine(Uri.parse("https://miaai-lab.github.io/__gyeot/deck/stats?avg=9.8&p95=14&fps=30&pr=2&w=768&h=600&dc=16&tri=17880")));
        assertEquals("LP 3D 평균 ?ms p95 ?ms ?fps ×? ?×? · CSS 덱으로: no webgl2", PieceExtras.statsLine(Uri.parse("https://miaai-lab.github.io/__gyeot/deck/stats?css=no%20webgl2")));
        // Locked: nothing of the user's music on the lock screen's wallpaper, and no picker; the page hears it is locked (and
        // asks again when shown unlocked) rather than that there is no app.
        PieceMusicStore.of(app).add(List.of(doc("Night_Drive.mp3")), null);
        shadowOf(app.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(true);
        for (String asked2 : new String[] { "music/list.json", "music/pick?kind=files" }) {
            WebResourceResponse locked = serve(get(k(asked2)));
            assertEquals(200, locked.getStatusCode());
            String said = text(locked); JSONObject lj = new JSONObject(said);
            assertTrue(said, lj.getBoolean("locked")); assertEquals(0, lj.getJSONArray("items").length()); assertFalse(said, said.contains("Night"));
        }
        assertEquals(2, opened.size());
        shadowOf(app.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(false);
        assertTrue("unlocked, the list again", text(serve(get(k("music/list.json")))).contains("Night Drive"));
        assertEquals(403, serve(get("deck/stats?avg=1")).getStatusCode());
    }

    @Test public void thePickerAsksForMusicFilesAndWhatWasPickedComesOntoTheList() throws Exception {
        ActivityInfo info = app.getPackageManager().getActivityInfo(new ComponentName(app, MusicPickActivity.class), 0);
        assertFalse("only the app opens it", info.exported);
        Activity a = Robolectric.buildActivity(MusicPickActivity.class, new Intent(app, MusicPickActivity.class).putExtra(MusicPickActivity.KIND, "files")).setup().get();
        ShadowActivity sa = shadowOf(a);
        ShadowActivity.IntentForResult asked = sa.getNextStartedActivityForResult();
        assertNotNull("Android's picker", asked);
        Intent pick = asked.intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, pick.getAction());
        assertTrue(pick.getCategories().contains(Intent.CATEGORY_OPENABLE));
        assertEquals("audio/*", pick.getType());
        assertTrue("several at once", pick.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        assertArrayEquals(new String[]{"audio/*", "application/ogg"}, pick.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
        assertEquals("open", PieceMusicStore.of(app).pick());

        ClipData clip = ClipData.newRawUri("", doc("Night_Drive.mp3")); clip.addItem(new ClipData.Item(doc("notes.txt"))); clip.addItem(new ClipData.Item(doc("rain.ogg")));
        Intent got = new Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); got.setClipData(clip);
        sa.receiveResult(pick, Activity.RESULT_OK, got);
        assertTrue("it closes at once", a.isFinishing());
        PieceMusicStore.WORK.submit(() -> null).get(); // the reading ran on the store's own thread
        List<Uri> kept = new ArrayList<>(); app.getContentResolver().getPersistedUriPermissions().forEach(p -> kept.add(p.getUri()));
        assertTrue("the permission kept for what was picked: " + kept, kept.contains(doc("Night_Drive.mp3")) && kept.contains(doc("rain.ogg")));
        assertFalse("and given back for what did not come onto the list (not sound): " + kept, kept.contains(doc("notes.txt")));
        PieceMusic.Shelf shelf = PieceMusicStore.of(app).shelf();
        assertEquals("the two sound files: " + shelf.items(), 2, shelf.items().size());
        assertEquals("Night Drive", shelf.items().get(0).title()); assertEquals("application/ogg", shelf.items().get(1).mime());
        assertEquals("done", PieceMusicStore.of(app).pick());

        // A folder: Android's folder picker; closed without picking, nothing changes.
        Activity b = Robolectric.buildActivity(MusicPickActivity.class, new Intent(app, MusicPickActivity.class).putExtra(MusicPickActivity.KIND, "folder")).setup().get();
        ShadowActivity.IntentForResult folder = shadowOf(b).getNextStartedActivityForResult();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, folder.intent.getAction());
        shadowOf(b).receiveResult(folder.intent, Activity.RESULT_CANCELED, null);
        assertTrue(b.isFinishing());
        assertEquals("cancelled", PieceMusicStore.of(app).pick());
        assertEquals(2, PieceMusicStore.of(app).shelf().items().size());

        // A folder with no music in it: nothing added, and its permission given back.
        Uri empty = android.provider.DocumentsContract.buildTreeDocumentUri(AUTH, "empty");
        Activity e = Robolectric.buildActivity(MusicPickActivity.class, new Intent(app, MusicPickActivity.class).putExtra(MusicPickActivity.KIND, "folder")).setup().get();
        ShadowActivity.IntentForResult tree = shadowOf(e).getNextStartedActivityForResult();
        shadowOf(e).receiveResult(tree.intent, Activity.RESULT_OK, new Intent().setData(empty).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
        PieceMusicStore.WORK.submit(() -> null).get();
        assertEquals(2, PieceMusicStore.of(app).shelf().items().size());
        assertTrue("no permission kept for a folder with nothing on the list", app.getContentResolver().getPersistedUriPermissions().stream().noneMatch(p -> p.getUri().equals(empty)));
        assertTrue("still those of the files on it", app.getContentResolver().getPersistedUriPermissions().stream().anyMatch(p -> p.getUri().equals(doc("rain.ogg"))));

        // The picker left without an answer (the home button over it clears it): the page stops waiting.
        org.robolectric.android.controller.ActivityController<MusicPickActivity> gone = Robolectric.buildActivity(MusicPickActivity.class, new Intent(app, MusicPickActivity.class).putExtra(MusicPickActivity.KIND, "files")).setup();
        assertEquals("open", PieceMusicStore.of(app).pick());
        gone.pause().stop().destroy();
        assertEquals("cancelled", PieceMusicStore.of(app).pick());
    }
}
