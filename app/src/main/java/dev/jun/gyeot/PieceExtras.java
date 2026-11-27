package dev.jun.gyeot;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import dev.jun.gyeot.core.HtmlShowcase;
import dev.jun.gyeot.core.Jsons;
import dev.jun.gyeot.core.PieceMusic;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The LP piece's add-on and 내 음악. The app serves the LP piece from an address of its own, https://lp.gyeot.local/<the piece's
 * path> (frameUrl: the launcher's and the wallpaper's frames, the wallpaper alone, the preview), fetched from the creator's
 * site and tuned (HtmlWallpaper.piece), so that page is the only code on that origin: another piece on a page (code someone
 * else may change, on miaai-lab.github.io) cannot read it, its key or its answers, and the browser keeps them apart. The page
 * reads the add-on as its own (no cross-origin rules; Web Audio reads the music's real samples, so the VU meters show the
 * real loudness). Nothing here goes to the network but the piece itself: the add-on's files are the app's assets, 내 음악 is
 * PieceMusicStore. Every request of a piece on the creator's site goes on to HtmlWallpaper.tuned (the fixed pieces).
 *
 *   /__gyeot/lib/three.module.js            three.js 0.170.0 (assets/piece, MIT)
 *   /__gyeot/addon/vinyl.css|vinyl.js|deck3d.js   the add-on (assets/piece)
 *   /__gyeot/addon/walnut-col|nor|rgh.jpg  the 3D deck's walnut (a Poly Haven scan, CC0, graded to walnut)
 *   /__gyeot/music/list.json?k=             the list: titles and lengths, never a file's address ({locked} while locked)
 *   /__gyeot/music/pick?k=&kind=files|folder  Android's picker (MusicPickActivity), answered at once {pending, rev, via}
 *   /__gyeot/music/scan?k=&ask=1|0          휴대폰 노래: the songs of Android's media index ({perm:"granted", items: id, title,
 *                                           artist, dur, on; hidden}), or how asking for READ_MEDIA_AUDIO goes ({perm}); ask=1
 *                                           asks (MusicPickActivity, kind songs) when it is not given
 *   /__gyeot/music/add-media?k=&ids=a,b     the chosen songs onto the list (read off the main thread), answered at once {pending, rev, n}
 *   /__gyeot/music/settings?k=              the app's page in Android's settings (to allow 음악 및 오디오 after a refusal)
 *   /__gyeot/music/<id>?k=                  the file's bytes, with Range (seeking)
 *   /__gyeot/music/<id>/art?k=              the picture found in it
 *   DELETE /__gyeot/music/<id>?k=           off the list (also GET …/<id>/remove)
 *   /__gyeot/deck/stats?k=&avg=…            the 3D deck's frame times, noted once a run in the diagnostic report
 *   /__gyeot/lp/state?k=&playing=&title=&artist=&album=&hidden=  what the LP page plays (LpMedia: its media notification and the
 *                                           audio focus while it plays, 퍼레 hearing it), answered with a command for it {cmd}
 *
 * The music answers only at the app's own address, with this run's key, which is only in the page served there. On the
 * creator's site the add-on's files still come (the piece's bar and list), never the music and never a key. A Referer, when
 * the request has one, must be the LP page too (a small extra; the origin is what keeps the rest out). The list and the
 * picker give nothing while the phone is locked. Called on the web view's request thread, never the main one.
 */
final class PieceExtras {
    private PieceExtras() {}
    static final String PREFIX = "/__gyeot/", PLACE = "__GY_KEY__";
    /** The app's own address for the LP piece (only that page's code runs there). */
    static final String LP_HOST = "lp.gyeot.local";
    static final String LP = "/Claude-Opus-5.5-100-HTML-Files/057-vinyl-listening-room.html";
    private static final String KEY = newKey();
    private static volatile boolean statsNoted, cssNoted;

    private static String newKey() {
        byte[] b = new byte[16]; new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }
    static String key() { return KEY; }
    /** A tuned page with this run's key in its place (served only at LP_HOST). */
    static String keyed(String page) { return page.replace(PLACE, KEY); }
    /** A tuned page with no key (on the creator's site: the add-on without 내 음악). */
    static String keyless(String page) { return page.replace(PLACE, ""); }
    /** Where a page's frame loads a piece: the LP piece from the app's own address, any other address as it is. */
    static String frameUrl(String src) {
        if (src == null) return null;
        String at = "https://" + HtmlShowcase.HOST + LP;
        if (!src.regionMatches(true, 0, at, 0, at.length())) return src;
        String rest = src.substring(at.length());
        return rest.isEmpty() || rest.startsWith("?") || rest.startsWith("#") ? "https://" + LP_HOST + LP + rest : src;
    }

    /** A page's slot for the launcher or the board, with where its frame loads it ("frame"). */
    static org.json.JSONObject framed(org.json.JSONObject slot, String src) { Jsons.put(slot, "frame", frameUrl(src)); return slot; }

    /** A piece's request: the app's own address for the LP piece answered here, the creator's site as HtmlWallpaper.tuned does. */
    static WebResourceResponse handle(Context c, WebResourceRequest r, Consumer<Intent> opener) {
        Uri u = r.getUrl();
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT), path = u.getPath() == null ? "" : u.getPath();
        try {
            if (LP_HOST.equals(host)) {
                if (path.startsWith(PREFIX)) return serve(c, r, u, path.substring(PREFIX.length()), opener);
                if (path.equals(LP) && "GET".equalsIgnoreCase(r.getMethod() == null ? "GET" : r.getMethod())) return page(c);
                return status(404, "Not Found");
            }
            if (HtmlShowcase.HOST.equals(host) && path.startsWith(PREFIX)) {
                WebResourceResponse file = addon(c, path.substring(PREFIX.length()));
                return file != null ? file : status(404, "Not Found");
            }
        } catch (Exception failed) { DiagnosticsExport.record(c, "storage", failed); return status(500, "Server Error"); }
        return HtmlWallpaper.tuned(c, r);
    }
    /** The LP page at the app's own address: tuned with this run's key; as it came when a fix no longer matches; offline, a wait. */
    private static WebResourceResponse page(Context c) {
        HtmlWallpaper.Piece p = HtmlWallpaper.piece(c, LP);
        String body = p == null ? OFFLINE : p.tuned() != null ? keyed(p.tuned()) : p.raw();
        return new WebResourceResponse("text/html", "utf-8", 200, "OK", Map.of("Cache-Control", "no-store"), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
    private static final String OFFLINE = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><body style='margin:0;height:100vh;display:grid;"
        + "place-items:center;background:#1f3b2d;color:#efe6d8;font:15px/1.5 serif'>인터넷이 연결되면 LP 페이지를 불러와요"
        + "<script>addEventListener('online',()=>location.reload());setTimeout(()=>location.reload(),30000)</script></body>";
    /** The add-on's own files (none of the user's): three.js, the add-on, the walnut. */
    private static WebResourceResponse addon(Context c, String path) {
        if (path.equals("lib/three.module.js")) return asset(c, "piece/three-0.170.0.module.min.js", "text/javascript");
        if (path.matches("addon/(vinyl\\.css|vinyl\\.js|deck3d\\.js)")) {
            String name = path.substring(6);
            return asset(c, "piece/" + name, name.endsWith(".css") ? "text/css" : "text/javascript");
        }
        if (path.matches("addon/walnut-(col|nor|rgh)\\.jpg")) return asset(c, "piece/" + path.substring(6), "image/jpeg");
        return null;
    }

    private static WebResourceResponse serve(Context c, WebResourceRequest r, Uri u, String path, Consumer<Intent> opener) throws Exception {
        String method = r.getMethod() == null ? "GET" : r.getMethod().toUpperCase(Locale.ROOT);
        WebResourceResponse file = addon(c, path);
        if (file != null) return file;
        if (!path.startsWith("music/") && !path.startsWith("deck/") && !path.equals("lp/state")) return status(404, "Not Found");
        if (!allowed(r, u)) return status(403, "Forbidden");
        PieceMusicStore store = PieceMusicStore.of(c);
        // Locked, a wallpaper shows nothing of the user's (HtmlWallpaper): no list of their music and no picker, but an answer
        // that says so (the page asks again once it is shown unlocked); a file already playing goes on (its bytes are asked by id).
        if (PRIVATE.contains(path) && locked(c)) return json(Jsons.obj("locked", true, "rev", -1, "items", new org.json.JSONArray(), "pick", "").toString());
        switch (path) {
            case "music/list.json": return json(PieceMusic.page(store.shelf(), store.pick()).toString());
            case "music/pick": {
                String kind = MusicPickActivity.FOLDER.equals(u.getQueryParameter("kind")) ? MusicPickActivity.FOLDER : MusicPickActivity.FILES;
                store.pick("open");
                opener.accept(new Intent(c, MusicPickActivity.class).putExtra(MusicPickActivity.KIND, kind));
                return json(Jsons.obj("pending", true, "rev", store.shelf().rev(), "via", "app").toString());
            }
            case "music/scan": {
                // The media index is read here (the request thread); without the permission, it is asked for once (ask=1),
                // and the page asks again (ask=0) until the answer comes.
                if (!songsAllowed(c)) {
                    if (!"0".equals(u.getQueryParameter("ask"))) {
                        store.perm("asking");
                        opener.accept(new Intent(c, MusicPickActivity.class).putExtra(MusicPickActivity.KIND, MusicPickActivity.SONGS));
                    }
                    String perm = store.perm();
                    return json(Jsons.obj("perm", perm.isEmpty() || perm.equals("granted") ? "ask" : perm).toString());
                }
                store.perm("granted");
                return json(PieceMusic.songs(store.scan(), store.shelf()).toString());
            }
            case "music/add-media": {
                if (!songsAllowed(c)) return json(Jsons.obj("perm", "ask").toString());
                java.util.Set<String> want = new java.util.HashSet<>(PieceMusic.ids(u.getQueryParameter("ids")));
                java.util.List<PieceMusic.Found> chosen = new java.util.ArrayList<>();
                // the page names ids only: each is looked up again in the index (never an address from the page)
                if (!want.isEmpty()) for (PieceMusic.Found f : store.scan()) if (want.contains(f.id()) && f.song() && !store.shelf().has(f.uri())) chosen.add(f);
                store.pick("open");
                store.addMediaLater(chosen);
                return json(Jsons.obj("pending", true, "rev", store.shelf().rev(), "n", chosen.size(), "via", "app").toString());
            }
            case "music/settings":
                opener.accept(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.getPackageName(), null)));
                return json(Jsons.obj("via", "app").toString());
            case "deck/stats": return stats(c, u);
            case "lp/state": {
                // What the page plays (LpMedia: the media notification and the audio focus while it plays, 퍼레 hearing it), and
                // what the notification's buttons asked of it since (said on the lock screen too: nothing of the user's comes back)
                dev.jun.gyeot.core.LpPlayback.State s = new dev.jun.gyeot.core.LpPlayback.State("1".equals(u.getQueryParameter("playing")), u.getQueryParameter("title"),
                    u.getQueryParameter("artist"), u.getQueryParameter("album"), "1".equals(u.getQueryParameter("hidden")), System.currentTimeMillis());
                return json(Jsons.obj("cmd", LpMedia.report(c, s)).toString());
            }
            default: break;
        }
        String[] parts = path.substring(6).split("/");
        if (parts.length == 0 || !PieceMusic.validId(parts[0])) return status(404, "Not Found");
        String id = parts[0];
        if ("DELETE".equals(method) || (parts.length == 2 && parts[1].equals("remove"))) return json(PieceMusic.page(store.remove(id), store.pick()).toString());
        if (parts.length == 2 && parts[1].equals("art")) {
            File f = store.art(id);
            return f.isFile() ? new WebResourceResponse("image/jpeg", null, 200, "OK", Map.of("Cache-Control", "no-store"), new FileInputStream(f)) : status(404, "Not Found");
        }
        if (parts.length != 1) return status(404, "Not Found");
        PieceMusic.Track t = store.find(id);
        if (t == null) return status(404, "Not Found");
        return bytes(c.getContentResolver(), t, header(r, "Range"));
    }

    /** What tells of the user's music (or opens a window over the lock screen): nothing while the phone is locked. */
    private static final java.util.Set<String> PRIVATE = java.util.Set.of("music/list.json", "music/pick", "music/scan", "music/add-media", "music/settings");
    /**
     * Whether the app may read the phone's songs from the media index: READ_MEDIA_AUDIO (asked for itself even where all files
     * access was given for 들퍼레's folder: that one is about files, and the index answers for the permission it knows).
     */
    static boolean songsAllowed(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    private static boolean locked(Context c) {
        android.app.KeyguardManager k = c.getSystemService(android.app.KeyguardManager.class);
        return k != null && k.isKeyguardLocked();
    }
    /** The key, and when the request says where it comes from, the LP page itself (the origin is what keeps other pages out). */
    private static boolean allowed(WebResourceRequest r, Uri u) {
        String k = u.getQueryParameter("k");
        if (k == null || !MessageDigest.isEqual(k.getBytes(StandardCharsets.UTF_8), KEY.getBytes(StandardCharsets.UTF_8))) return false;
        String from = header(r, "Referer");
        if (from == null || from.isEmpty()) return true;
        Uri f = Uri.parse(from);
        return LP_HOST.equalsIgnoreCase(f.getHost() == null ? "" : f.getHost()) && LP.equals(f.getPath());
    }
    static String header(WebResourceRequest r, String name) {
        Map<String, String> h = r.getRequestHeaders();
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    /**
     * A file's bytes. With a Range the answer is 206 with Content-Range, so the audio element can seek; 416 for a range past
     * the end; 200 with the whole file without one. The size comes from the open file (its length, or the file's own size),
     * else from what the provider said when it was added; unknown (a cloud document), it is the plain stream (no seeking).
     * 404 when the file is gone or its permission was taken back (the page then says so on its line and goes on).
     */
    static WebResourceResponse bytes(ContentResolver cr, PieceMusic.Track t, String rangeHeader) {
        AssetFileDescriptor afd;
        try { afd = cr.openAssetFileDescriptor(Uri.parse(t.uri()), "r"); }
        catch (Exception | Error gone) { return status(404, "Not Found"); }
        if (afd == null) return status(404, "Not Found");
        long size = afd.getLength();
        if (size < 0 && afd.getStartOffset() == 0) {
            ParcelFileDescriptor pfd = afd.getParcelFileDescriptor();
            try { size = pfd.getStatSize(); } catch (RuntimeException unknown) { size = -1; }
        }
        if (size <= 0) size = t.size();
        InputStream in;
        try { in = afd.createInputStream(); } catch (IOException unreadable) { close(afd); return status(404, "Not Found"); }
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        String mime = t.mime() == null || t.mime().isEmpty() ? "audio/mpeg" : t.mime();
        if (size <= 0) return new WebResourceResponse(mime, null, 200, "OK", headers, in);
        headers.put("Accept-Ranges", "bytes");
        PieceMusic.Range range = PieceMusic.range(rangeHeader, size);
        if (range == null) {
            close(in);
            headers.put("Content-Range", "bytes */" + size);
            return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers, new ByteArrayInputStream(new byte[0]));
        }
        if (range == PieceMusic.ALL) {
            headers.put("Content-Length", String.valueOf(size));
            return new WebResourceResponse(mime, null, 200, "OK", headers, new Span(in, 0, size - 1));
        }
        headers.put("Content-Range", "bytes " + range.first() + "-" + range.last() + "/" + size);
        headers.put("Content-Length", String.valueOf(range.length()));
        return new WebResourceResponse(mime, null, 206, "Partial Content", headers, new Span(in, range.first(), range.last()));
    }

    /**
     * The asked bytes of a file, from first to last. The stream starts at the file's beginning: the web view itself skips to
     * the start of a request's range on the stream it is given (and sizes it by available()), so this starts there only when
     * nobody skipped before the first read (another web view, a test reading it straight).
     */
    static final class Span extends InputStream {
        private final InputStream in; private final long first, end; private long at; private boolean reading;
        Span(InputStream in, long first, long last) { this.in = in; this.first = first; this.end = last + 1; }
        private void begin() throws IOException {
            if (reading) return;
            reading = true;
            byte[] waste = null;
            while (at < first) {
                long s;
                try { s = in.skip(first - at); } catch (IOException unseekable) { s = 0; }
                if (s <= 0) { // a stream that cannot seek (a pipe): read past instead
                    if (waste == null) waste = new byte[64 * 1024];
                    int n = in.read(waste, 0, (int) Math.min(waste.length, first - at));
                    if (n < 0) { at = end; return; }
                    s = n;
                }
                at += s;
            }
        }
        @Override public int read() throws IOException {
            begin(); if (at >= end) return -1;
            int b = in.read(); if (b >= 0) at++; return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            begin(); if (at >= end) return -1;
            int n = in.read(b, off, (int) Math.min(len, end - at)); if (n > 0) at += n; return n;
        }
        @Override public long skip(long n) throws IOException {
            if (n <= 0) return 0;
            long s = in.skip(Math.min(n, end - at)); if (s > 0) at += s; return Math.max(0, s);
        }
        @Override public int available() { return (int) Math.min(Integer.MAX_VALUE, Math.max(0, end - at)); }
        @Override public void close() throws IOException { in.close(); }
    }

    /** The deck's frame times from the phone, and why the CSS deck took over if it did: each noted once a run (statsLine). */
    private static WebResourceResponse stats(Context c, Uri u) {
        boolean css = u.getQueryParameter("css") != null;
        if (css ? !cssNoted : !statsNoted) {
            if (css) cssNoted = true; else statsNoted = true;
            DiagnosticsExport.record(c, "other", statsLine(u));
        }
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", Map.of(), new ByteArrayInputStream(new byte[0]));
    }
    /** "LP 3D 평균 9.8ms p95 14ms 30fps ×2 768×600 그리기 16번 삼각형 17880개", and why the CSS deck took over when it did. */
    static String statsLine(Uri u) {
        String line = "LP 3D 평균 " + num(u, "avg") + "ms p95 " + num(u, "p95") + "ms " + num(u, "fps") + "fps ×" + num(u, "pr") + " " + num(u, "w") + "×" + num(u, "h");
        if (u.getQueryParameter("dc") != null) line += " 그리기 " + num(u, "dc") + "번 삼각형 " + num(u, "tri") + "개";
        if (u.getQueryParameter("gpu") != null) line += " GPU " + num(u, "gpu") + "ms";
        String why = u.getQueryParameter("css");
        if (why != null && why.matches("[a-z0-9 _-]{1,40}")) line += " · CSS 덱으로: " + why;
        return line;
    }
    private static String num(Uri u, String name) {
        String v = u.getQueryParameter(name);
        return v != null && v.matches("\\d{1,6}(\\.\\d{1,2})?") ? v : "?";
    }

    private static WebResourceResponse asset(Context c, String name, String mime) {
        try { return new WebResourceResponse(mime, mime.startsWith("text/") ? "utf-8" : null, 200, "OK", Map.of("Cache-Control", "no-cache"), c.getAssets().open(name)); }
        catch (IOException gone) { return status(404, "Not Found"); }
    }
    private static WebResourceResponse json(String body) {
        return new WebResourceResponse("application/json", "utf-8", 200, "OK", Map.of("Cache-Control", "no-store"), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
    static WebResourceResponse status(int code, String why) {
        return new WebResourceResponse("text/plain", "utf-8", code, why, Map.of("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
    }
    private static void close(AutoCloseable c) { try { c.close(); } catch (Exception ignored) {} }
}
