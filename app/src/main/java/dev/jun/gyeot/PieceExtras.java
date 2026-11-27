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
 * The LP piece's add-on, answered by the app under the piece's own address (https://miaai-lab.github.io/__gyeot/…), so the
 * page reads it as its own (no cross-origin rules; Web Audio reads the music's real samples, so the VU meters show the real
 * loudness). Nothing here goes to the network: the add-on's files are the app's assets, 내 음악 is PieceMusicStore. Every
 * other request of a piece goes on to HtmlWallpaper.tuned (the fixed pieces).
 *
 *   /__gyeot/lib/three.module.js            three.js 0.170.0 (assets/piece, MIT)
 *   /__gyeot/addon/vinyl.css|vinyl.js|deck3d.js
 *   /__gyeot/music/list.json?k=             the list: titles and lengths, never a file's address
 *   /__gyeot/music/pick?k=&kind=files|folder  Android's picker (MusicPickActivity), answered at once {pending, rev, via}
 *   /__gyeot/music/<id>?k=                  the file's bytes, with Range (seeking)
 *   /__gyeot/music/<id>/art?k=              the picture found in it
 *   DELETE /__gyeot/music/<id>?k=           off the list (also GET …/<id>/remove)
 *   /__gyeot/deck/stats?k=&avg=…            the 3D deck's frame times, noted once a run in the diagnostic report
 *
 * The music answers only with this run's key, which the app puts in the tuned page (and only for a request from the LP page,
 * when the request says where it comes from); its list and picker not while the phone is locked. Called on the web view's
 * request thread, never the main one.
 */
final class PieceExtras {
    private PieceExtras() {}
    static final String PREFIX = "/__gyeot/", PLACE = "__GY_KEY__";
    private static final String KEY = newKey();
    /** The pages allowed to use 내 음악 (a Referer from anywhere else is refused). */
    private static final String LP = "/Claude-Opus-5.5-100-HTML-Files/057-vinyl-listening-room.html";
    private static volatile boolean statsNoted;

    private static String newKey() {
        byte[] b = new byte[16]; new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }
    static String key() { return KEY; }
    /** A tuned page with this run's key in its place. */
    static String keyed(String page) { return page.replace(PLACE, KEY); }

    /** A piece's request: the add-on's own address answered here, everything else as HtmlWallpaper.tuned does. */
    static WebResourceResponse handle(Context c, WebResourceRequest r, Consumer<Intent> opener) {
        Uri u = r.getUrl();
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT), path = u.getPath() == null ? "" : u.getPath();
        if (!HtmlShowcase.HOST.equals(host) || !path.startsWith(PREFIX)) return HtmlWallpaper.tuned(c, r);
        try { return serve(c, r, u, path.substring(PREFIX.length()), opener); }
        catch (Exception failed) { DiagnosticsExport.record(c, "storage", failed); return status(500, "Server Error"); }
    }

    private static WebResourceResponse serve(Context c, WebResourceRequest r, Uri u, String path, Consumer<Intent> opener) throws Exception {
        String method = r.getMethod() == null ? "GET" : r.getMethod().toUpperCase(Locale.ROOT);
        if (path.equals("lib/three.module.js")) return asset(c, "piece/three-0.170.0.module.min.js", "text/javascript");
        if (path.matches("addon/(vinyl\\.css|vinyl\\.js|deck3d\\.js)")) {
            String name = path.substring(6);
            return asset(c, "piece/" + name, name.endsWith(".css") ? "text/css" : "text/javascript");
        }
        if (!path.startsWith("music/") && !path.startsWith("deck/")) return status(404, "Not Found");
        if (!allowed(r, u)) return status(403, "Forbidden");
        PieceMusicStore store = PieceMusicStore.of(c);
        // Locked, a wallpaper shows nothing of the user's (HtmlWallpaper): no list of their music and no picker; a file already
        // playing goes on (its bytes are asked by id).
        if ((path.equals("music/list.json") || path.equals("music/pick")) && locked(c)) return status(403, "Locked");
        switch (path) {
            case "music/list.json": return json(PieceMusic.page(store.shelf(), store.pick()).toString());
            case "music/pick": {
                String kind = MusicPickActivity.FOLDER.equals(u.getQueryParameter("kind")) ? MusicPickActivity.FOLDER : MusicPickActivity.FILES;
                store.pick("open");
                opener.accept(new Intent(c, MusicPickActivity.class).putExtra(MusicPickActivity.KIND, kind));
                return json(Jsons.obj("pending", true, "rev", store.shelf().rev(), "via", "app").toString());
            }
            case "deck/stats": return stats(c, u);
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

    private static boolean locked(Context c) {
        android.app.KeyguardManager k = c.getSystemService(android.app.KeyguardManager.class);
        return k != null && k.isKeyguardLocked();
    }
    /** The key, and when the request says where it comes from, the LP page itself. */
    private static boolean allowed(WebResourceRequest r, Uri u) {
        String k = u.getQueryParameter("k");
        if (k == null || !MessageDigest.isEqual(k.getBytes(StandardCharsets.UTF_8), KEY.getBytes(StandardCharsets.UTF_8))) return false;
        String from = header(r, "Referer");
        if (from == null || from.isEmpty()) return true;
        Uri f = Uri.parse(from);
        return HtmlShowcase.HOST.equalsIgnoreCase(f.getHost() == null ? "" : f.getHost()) && LP.equals(f.getPath());
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

    /** The deck's frame times from the phone, noted once a run ("LP 3D 평균 9.8ms p95 14ms 30fps ×2.0 768×600"). */
    private static WebResourceResponse stats(Context c, Uri u) {
        if (!statsNoted) {
            statsNoted = true;
            String line = "LP 3D 평균 " + num(u, "avg") + "ms p95 " + num(u, "p95") + "ms " + num(u, "fps") + "fps ×" + num(u, "pr") + " " + num(u, "w") + "×" + num(u, "h");
            String why = u.getQueryParameter("css");
            if (why != null && why.matches("[a-z0-9 _-]{1,40}")) line += " · CSS 덱으로: " + why;
            DiagnosticsExport.record(c, "other", line);
        }
        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content", Map.of(), new ByteArrayInputStream(new byte[0]));
    }
    private static String num(Uri u, String name) {
        String v = u.getQueryParameter(name);
        return v != null && v.matches("\\d{1,6}(\\.\\d{1,2})?") ? v : "?";
    }

    private static WebResourceResponse asset(Context c, String name, String mime) {
        try { return new WebResourceResponse(mime, "utf-8", 200, "OK", Map.of("Cache-Control", "no-cache"), c.getAssets().open(name)); }
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
