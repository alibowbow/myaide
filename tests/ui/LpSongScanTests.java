package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import dev.jun.gyeot.core.PieceMusic;
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
 * 휴대폰 노래 on the LP page (준: "내음악 가져오기에서 mp3들은 자동인식해서 목록들을 쭉 가져오면 안될까", "재휴대폰 파일들", "그 중에서
 * 선택하게", "음성대화녹음파일들은 파일명 보고 자동으로 걸러", "노래만 가져오기"): which of the phone's sound files are songs
 * (PieceMusic.song, with the paths a Galaxy keeps: Voice Recorder, call recordings, ringtones, 들퍼레's own voice files), and
 * PieceExtras' /music/scan, /music/add-media and /music/settings over a stand-in for Android's media index: READ_MEDIA_AUDIO
 * asked first (MusicPickActivity), the songs only, the chosen ones on the list and served like picked files, nothing of it
 * while the phone is locked. The page side is tests/browser/vinyl.test.js.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
public class LpSongScanTests {
    static final String LP = "https://lp.gyeot.local", PIECE = "/Claude-Opus-5.5-100-HTML-Files/057-vinyl-listening-room.html";
    static final Uri AUDIO = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
    static File dir;

    /** One row of the media index: id, name, folder (RELATIVE_PATH), title, artist, length, type, and IS_MUSIC … IS_RECORDING. */
    record Row(long id, String name, String folder, String title, String artist, long durMs, String mime, int music, int ringtone, int notification, int alarm, int podcast, int recording) {}
    static final List<Row> ROWS = new ArrayList<>();
    static Row music(long id, String name, String folder, String title, String artist, long durMs) { return new Row(id, name, folder, title, artist, durMs, name.endsWith(".mp3") ? "audio/mpeg" : "audio/mp4", 1, 0, 0, 0, 0, 0); }

    /** Android's media index, as much of it as the app asks: the audio table with its columns, and a file's bytes. */
    public static final class Media extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] cols, String sel, String[] args, String order) {
            MatrixCursor c = new MatrixCursor(cols);
            for (Row r : ROWS) {
                if (!uri.getPath().equals("/external/audio/media") && !uri.getPath().equals("/external/audio/media/" + r.id())) continue;
                Object[] v = new Object[cols.length];
                for (int i = 0; i < cols.length; i++) v[i] = switch (cols[i]) {
                    case MediaStore.Audio.Media._ID -> r.id();
                    case MediaStore.Audio.Media.DISPLAY_NAME -> r.name();
                    case MediaStore.Audio.Media.TITLE -> r.title();
                    case MediaStore.Audio.Media.ARTIST -> r.artist();
                    case MediaStore.Audio.Media.DURATION -> r.durMs();
                    case MediaStore.Audio.Media.DATA -> "/storage/emulated/0/" + r.folder() + r.name();
                    case MediaStore.Audio.Media.RELATIVE_PATH -> r.folder();
                    case MediaStore.Audio.Media.MIME_TYPE -> r.mime();
                    case MediaStore.Audio.Media.SIZE -> 1000L;
                    case MediaStore.Audio.Media.IS_MUSIC -> r.music();
                    case MediaStore.Audio.Media.IS_RINGTONE -> r.ringtone();
                    case MediaStore.Audio.Media.IS_NOTIFICATION -> r.notification();
                    case MediaStore.Audio.Media.IS_ALARM -> r.alarm();
                    case MediaStore.Audio.Media.IS_PODCAST -> r.podcast();
                    case MediaStore.Audio.Media.IS_AUDIOBOOK -> 0;
                    case MediaStore.Audio.Media.IS_RECORDING -> r.recording();
                    default -> null;
                };
                c.addRow(v);
            }
            return c;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            File f = new File(dir, uri.getLastPathSegment());
            if (!f.isFile()) throw new FileNotFoundException(uri.toString());
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { for (Row r : ROWS) if (uri.getLastPathSegment().equals(String.valueOf(r.id()))) return r.mime(); return null; }
        @Override public Uri insert(Uri uri, ContentValues v) { return null; }
        @Override public int delete(Uri uri, String s, String[] a) { return 0; }
        @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
    }

    Context app;
    final List<Intent> opened = new ArrayList<>();
    @Before public void phone() throws Exception {
        app = RuntimeEnvironment.getApplication();
        dir = new File(app.getCacheDir(), "media"); dir.mkdirs();
        ROWS.clear();
        // a Galaxy's music, its recordings and alert tones, and what 들퍼레 itself saved (a voice line, a MyTube MP3)
        ROWS.add(music(1, "아이유 - 좋은날.mp3", "Music/", "좋은 날", "아이유", 231_000));
        ROWS.add(music(2, "음성 001.m4a", "Recordings/Voice Recorder/", "음성 001", "<unknown>", 312_000));          // (an older index: no IS_RECORDING)
        ROWS.add(music(3, "통화 녹음 엄마_231101_103022.m4a", "Recordings/Call/", "통화 녹음 엄마_231101_103022", "<unknown>", 185_000));
        ROWS.add(music(4, "Over_the_Horizon.mp3", "Music/Samsung/", "Over the Horizon", "Samsung", 196_000));
        ROWS.add(music(5, "Call Me Maybe.mp3", "Download/", "Call Me Maybe", "Carly Rae Jepsen", 193_000));
        ROWS.add(new Row(6, "Pixie Dust.ogg", "Notifications/", "Pixie Dust", "<unknown>", 1_500, "audio/ogg", 0, 0, 1, 0, 0, 0));
        ROWS.add(new Row(7, "Galaxy Bells.ogg", "Ringtones/", "Galaxy Bells", "Samsung", 31_000, "audio/ogg", 0, 1, 0, 0, 0, 0));
        ROWS.add(music(8, "음성-20261127-101500-ab12-1.wav", "들퍼레/오디오/", "음성-20261127-101500-ab12-1", "<unknown>", 62_000));
        ROWS.add(music(9, "Short jingle.mp3", "Music/", "Short jingle", "", 12_000));
        ROWS.add(music(10, "PTT-20231101-WA0003.opus", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202344/", "PTT-20231101-WA0003", "<unknown>", 48_000));
        ROWS.add(music(11, "밤편지.mp3", "들퍼레/오디오/", "밤편지", "아이유", 253_000));
        ROWS.add(new Row(12, "회의 0412.m4a", "Recordings/", "회의 0412", "<unknown>", 1_800_000, "audio/mp4", 1, 0, 0, 0, 0, 1));
        ROWS.add(music(13, "The Voice Within.mp3", "Music/", "The Voice Within", "Christina Aguilera", 304_000));
        ROWS.add(music(14, "대화가 필요해.mp3", "Music/", "대화가 필요해", "더 자두", 214_000));
        ROWS.add(music(15, "Track 01.mp3", "Music/Unknown album/", "", "<unknown>", 200_000));                 // (no tags at all)
        for (Row r : ROWS) try (FileOutputStream o = new FileOutputStream(new File(dir, String.valueOf(r.id())))) { byte[] b = new byte[1000]; Arrays.fill(b, (byte) r.id()); o.write(b); }
        Robolectric.setupContentProvider(Media.class, "media");
    }
    static Uri item(long id) { return android.content.ContentUris.withAppendedId(AUDIO, id); }
    static String id(long row) { return PieceMusic.id(item(row).toString()); }
    static WebResourceRequest get(String path, String... headers) {
        Map<String, String> h = new HashMap<>(); h.put("Referer", LP + PIECE);
        for (int i = 0; i + 1 < headers.length; i += 2) h.put(headers[i], headers[i + 1]);
        return new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(LP + "/__gyeot/" + path + (path.contains("?") ? "&" : "?") + "k=" + PieceExtras.key()); }
            @Override public boolean isForMainFrame() { return false; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return h; }
        };
    }
    WebResourceResponse serve(String path, String... headers) { return PieceExtras.handle(app, get(path, headers), opened::add); }
    static byte[] read(WebResourceResponse r) throws Exception { try (InputStream in = r.getData()) { ByteArrayOutputStream o = new ByteArrayOutputStream(); in.transferTo(o); return o.toByteArray(); } }
    static JSONObject json(WebResourceResponse r) throws Exception { return new JSONObject(new String(read(r), StandardCharsets.UTF_8)); }

    @Test public void songsAreMusicAndRecordingsAreNot() {
        int M = PieceMusic.MUSIC;
        // songs, wherever they are kept
        assertTrue(PieceMusic.song("아이유 - 좋은날.mp3", "/storage/emulated/0/Music/아이유 - 좋은날.mp3", 231_000, M));
        assertTrue(PieceMusic.song("Over_the_Horizon.mp3", "Music/Samsung/", 196_000, M));
        assertTrue("a song whose name has 'call' in it", PieceMusic.song("Call Me Maybe.mp3", "/storage/emulated/0/Download/Call Me Maybe.mp3", 193_000, M));
        assertTrue("… or 'voice'", PieceMusic.song("The Voice Within.mp3", "Music/", 304_000, M));
        assertTrue("… or 대화", PieceMusic.song("대화가 필요해.mp3", "Music/", 214_000, M));
        assertTrue("a MyTube MP3 in 들퍼레's folder", PieceMusic.song("밤편지.mp3", "/storage/emulated/0/들퍼레/오디오/밤편지.mp3", 253_000, M));
        assertTrue("a length not known", PieceMusic.song("Track 1.flac", "Music/Album/", 0, M));
        // a Galaxy's recordings: by folder, by name, by its own flag
        assertFalse(PieceMusic.song("음성 001.m4a", "/storage/emulated/0/Recordings/Voice Recorder/음성 001.m4a", 312_000, M));
        assertFalse("a recorder's name anywhere", PieceMusic.song("Voice 012.m4a", "Music/", 90_000, M));
        assertFalse(PieceMusic.song("통화 녹음 엄마_231101_103022.m4a", "/storage/emulated/0/Recordings/Call/통화 녹음 엄마_231101_103022.m4a", 185_000, M));
        assertFalse("an old Galaxy's call folder", PieceMusic.song("홍길동_231101_103022.m4a", "/storage/emulated/0/Call/홍길동_231101_103022.m4a", 185_000, M));
        assertFalse("an old call recording's name", PieceMusic.song("Call@홍길동(01012345678)_20231101103022.m4a", "Music/", 185_000, M));
        assertFalse("Google's recorder", PieceMusic.song("Recording 3.m4a", "Download/", 600_000, M));
        assertFalse("a meeting", PieceMusic.song("회의 녹음 0412.m4a", "Documents/", 1_800_000, M));
        assertFalse("WhatsApp's voice notes", PieceMusic.song("PTT-20231101-WA0003.opus", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202344/", 48_000, M));
        assertFalse("a screen recording's sound", PieceMusic.song("Screen_Recording_20231101-103022_Chrome.m4a", "Movies/", 90_000, M));
        assertFalse("들퍼레's own voice lines", PieceMusic.song("음성-20261127-101500-ab12-1.wav", "/storage/emulated/0/들퍼레/오디오/음성-20261127-101500-ab12-1.wav", 62_000, M));
        assertFalse("a voice chat saved", PieceMusic.song("퍼레 voice chat 2026-11-27.m4a", "Download/", 300_000, M));
        assertFalse("the index says it is a recording", PieceMusic.song("Interview.m4a", "Music/", 900_000, M | PieceMusic.RECORDING));
        // alert tones, short sounds, not music
        assertFalse(PieceMusic.song("Pixie Dust.ogg", "Notifications/", 1_500, PieceMusic.NOTIFICATION));
        assertFalse(PieceMusic.song("Galaxy Bells.ogg", "Ringtones/", 31_000, M | PieceMusic.RINGTONE));
        assertFalse(PieceMusic.song("Morning Flower.ogg", "Alarms/", 60_000, M | PieceMusic.ALARM));
        assertFalse(PieceMusic.song("Episode 12.mp3", "Podcasts/", 1_800_000, M | PieceMusic.PODCAST));
        assertFalse("under 45 s", PieceMusic.song("Short jingle.mp3", "Music/", 12_000, M));
        assertFalse("not music by its flag", PieceMusic.song("Song.mp3", "Music/", 200_000, 0));
        // the ids the page sends: twelve hex digits, each once
        assertEquals(List.of("a1b2c3d4e5f6", "0f1e2d3c4b5a"), PieceMusic.ids("a1b2c3d4e5f6,../x,0f1e2d3c4b5a,A1B2C3D4E5F6,a1b2c3d4e5f6,"));
    }

    @Test public void theScanAsksForThePermissionThenListsTheSongsOnlyAndAddsTheChosen() throws Exception {
        shadowOf((android.app.Application) app).denyPermissions(Manifest.permission.READ_MEDIA_AUDIO);
        // Not allowed yet: Android's question over the page, and the page told it is asking.
        JSONObject asking = json(serve("music/scan?ask=1"));
        assertEquals("asking", asking.getString("perm")); assertFalse(asking.has("items"));
        assertEquals(1, opened.size());
        assertEquals(new ComponentName(app, MusicPickActivity.class), opened.get(0).getComponent());
        assertEquals(MusicPickActivity.SONGS, opened.get(0).getStringExtra(MusicPickActivity.KIND));
        assertEquals("waiting for the answer, not asking again", "asking", json(serve("music/scan?ask=0")).getString("perm"));
        assertEquals(1, opened.size());
        assertEquals("nothing added without it", "ask", json(serve("music/add-media?ids=" + id(1))).getString("perm"));

        // The question: READ_MEDIA_AUDIO, from a window over the page that closes itself.
        Activity a = Robolectric.buildActivity(MusicPickActivity.class, new Intent(opened.get(0))).setup().get();
        ShadowActivity.PermissionsRequest asked = shadowOf(a).getLastRequestedPermission();
        assertArrayEquals(new String[]{ Manifest.permission.READ_MEDIA_AUDIO }, asked.requestedPermissions);
        ShadowActivity.IntentForResult started = shadowOf(a).getNextStartedActivityForResult();   // (Android's own permission screen, if any)
        assertTrue("no file picker for this", started == null || !String.valueOf(started.intent.getAction()).contains("OPEN_DOCUMENT"));
        shadowOf((android.app.Application) app).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO);
        a.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, new int[]{ PackageManager.PERMISSION_GRANTED });
        assertTrue(a.isFinishing()); assertEquals("granted", PieceMusicStore.of(app).perm());

        // The songs only, each with what the page shows; no address of a file; how many were left out.
        WebResourceResponse r = serve("music/scan?ask=0");
        String body = new String(read(r), StandardCharsets.UTF_8);
        assertFalse("no file's address: " + body, body.contains("content:") || body.contains("/storage") || body.contains("Recordings"));
        JSONObject list = new JSONObject(body);
        JSONArray items = list.getJSONArray("items");
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) titles.add(items.getJSONObject(i).getString("title"));
        assertEquals("the seven songs: " + titles, 7, items.length());
        assertTrue(titles.containsAll(List.of("좋은 날", "밤편지", "Over the Horizon", "Call Me Maybe", "The Voice Within", "대화가 필요해", "Track 01")));
        assertEquals("recordings, voice lines, alert tones and short sounds left out", 8, list.getInt("hidden"));
        assertEquals("one artist's songs together, by title", 1, titles.indexOf("좋은 날") - titles.indexOf("밤편지"));
        assertTrue("by artist", titles.indexOf("Call Me Maybe") < titles.indexOf("The Voice Within") && titles.indexOf("The Voice Within") < titles.indexOf("Over the Horizon"));
        JSONObject good = items.getJSONObject(titles.indexOf("좋은 날"));
        assertEquals(id(1), good.getString("id")); assertEquals("아이유", good.getString("artist")); assertEquals(231.0, good.getDouble("dur"), 0.01); assertFalse(good.getBoolean("on"));
        JSONObject bare = items.getJSONObject(items.length() - 1);
        assertEquals("no artist last, its file name for a title", "Track 01", bare.getString("title"));
        assertEquals("the index's <unknown> is no artist", "", bare.getString("artist"));

        // Two songs, a recording and nonsense asked: the songs only, looked up again in the index, read off the main thread.
        JSONObject add = json(serve("music/add-media?ids=" + id(1) + "," + id(5) + "," + id(2) + ",zzz"));
        assertTrue(add.getBoolean("pending")); assertEquals(2, add.getInt("n"));
        PieceMusicStore.WORK.submit(() -> null).get();
        PieceMusic.Shelf shelf = PieceMusicStore.of(app).shelf();
        assertEquals(2, shelf.items().size());
        assertEquals(List.of("좋은 날", "Call Me Maybe"), shelf.items().stream().map(PieceMusic.Track::title).toList());
        assertEquals("Carly Rae Jepsen", shelf.items().get(1).artist()); assertEquals(193_000, shelf.items().get(1).durMs());
        assertEquals("done", PieceMusicStore.of(app).pick());
        // Served like a picked file: its bytes with Range.
        WebResourceResponse part = serve("music/" + id(1), "Range", "bytes=10-19");
        assertEquals(206, part.getStatusCode()); assertEquals("audio/mpeg", part.getMimeType()); assertEquals("bytes 10-19/1000", part.getResponseHeaders().get("Content-Range"));
        byte[] ten = new byte[10]; Arrays.fill(ten, (byte) 1); assertArrayEquals(ten, read(part));
        // On the list now, said so.
        JSONArray again = json(serve("music/scan?ask=0")).getJSONArray("items");
        for (int i = 0; i < again.length(); i++) {
            JSONObject o = again.getJSONObject(i);
            assertEquals(o.getString("title"), o.getString("id").equals(id(1)) || o.getString("id").equals(id(5)), o.getBoolean("on"));
        }
        // The same again adds nothing.
        assertEquals(0, json(serve("music/add-media?ids=" + id(1))).getInt("n"));
    }

    @Test public void refusedTheSettingsAreOneTapAwayAndNothingWhileLocked() throws Exception {
        shadowOf((android.app.Application) app).denyPermissions(Manifest.permission.READ_MEDIA_AUDIO);
        serve("music/scan?ask=1");
        Activity a = Robolectric.buildActivity(MusicPickActivity.class, new Intent(opened.get(0))).setup().get();
        ShadowActivity.PermissionsRequest asked = shadowOf(a).getLastRequestedPermission();
        shadowOf(app.getPackageManager()).setShouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_AUDIO, true);
        a.onRequestPermissionsResult(asked.requestCode, asked.requestedPermissions, new int[]{ PackageManager.PERMISSION_DENIED });
        assertEquals("refused, can be asked again", "denied", json(serve("music/scan?ask=0")).getString("perm"));
        // A second no (or "don't ask again"): only the settings can give it now.
        serve("music/scan?ask=1");
        Activity b = Robolectric.buildActivity(MusicPickActivity.class, new Intent(opened.get(1))).setup().get();
        shadowOf(app.getPackageManager()).setShouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_AUDIO, false);
        b.onRequestPermissionsResult(shadowOf(b).getLastRequestedPermission().requestCode, new String[]{ Manifest.permission.READ_MEDIA_AUDIO }, new int[]{ PackageManager.PERMISSION_DENIED });
        assertEquals("blocked", json(serve("music/scan?ask=0")).getString("perm"));
        // The question gone unanswered (the window closed over it): the page stops waiting.
        serve("music/scan?ask=1");
        Robolectric.buildActivity(MusicPickActivity.class, new Intent(opened.get(2))).setup().pause().stop().destroy();
        assertEquals("cancelled", json(serve("music/scan?ask=0")).getString("perm"));
        // One tap to the app's own page in Android's settings.
        assertEquals("app", json(serve("music/settings")).getString("via"));
        Intent settings = opened.get(opened.size() - 1);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.getAction());
        assertEquals("package:" + app.getPackageName(), settings.getData().toString());
        // Locked: nothing of the phone's songs, no question, no settings over the lock screen.
        shadowOf((android.app.Application) app).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO);
        int before = opened.size();
        shadowOf(app.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(true);
        for (String p : new String[] { "music/scan?ask=1", "music/add-media?ids=" + id(1), "music/settings" }) {
            String said = new String(read(serve(p)), StandardCharsets.UTF_8);
            assertTrue(p + ": " + said, new JSONObject(said).getBoolean("locked")); assertFalse(said, said.contains("좋은"));
        }
        assertEquals(before, opened.size());
        assertEquals(0, PieceMusicStore.of(app).shelf().items().size());
        shadowOf(app.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(false);
        assertEquals(7, json(serve("music/scan?ask=0")).getJSONArray("items").length());
        // Without the key, nothing at all.
        WebResourceRequest keyless = new WebResourceRequest() {
            @Override public Uri getUrl() { return Uri.parse(LP + "/__gyeot/music/scan?ask=1"); }
            @Override public boolean isForMainFrame() { return false; }
            @Override public boolean isRedirect() { return false; }
            @Override public boolean hasGesture() { return false; }
            @Override public String getMethod() { return "GET"; }
            @Override public Map<String, String> getRequestHeaders() { return Map.of(); }
        };
        assertEquals(403, PieceExtras.handle(app, keyless, opened::add).getStatusCode());
    }
}
