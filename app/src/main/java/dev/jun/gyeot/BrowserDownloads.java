package dev.jun.gyeot;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import dev.jun.gyeot.core.BrowserArtifactPolicy;
import dev.jun.gyeot.core.BrowserDownloadPolicy;
import dev.jun.gyeot.core.MusicDownload;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.mozilla.geckoview.WebResponse;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * A file the user downloads in the AI 브라우저 (준: "쓸데 없는 제한이 너무 많아"): a PDF, ZIP, HWP/HWPX, an Office file, a picture,
 * a video or a sound goes to the phone's 다운로드 folder as Chrome puts it there, with a notification that opens it. Gecko's own
 * response is read, the way the Work browser reads its files (BrowserDownloadPolicy, BrowserArtifactPolicy): an installer or
 * a program is never kept. Music still goes to 들퍼레/음악 when that folder is connected (BrowserMusicDownloads).
 */
final class BrowserDownloads {
    private BrowserDownloads() {}
    static final String CHANNEL = "browser_downloads";
    /** Downloads run beside each other (a long video does not hold up a PDF), off the main thread. */
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "browser-download"); t.setDaemon(true); return t; });
    private static final AtomicInteger NOTIFICATIONS = new AtomicInteger();
    private static final int NOTIFICATION_BASE = 0x4500;

    /** Saves the response's file; done hears on the main thread {ok, name, mime, size, uri, message} or {ok:false, message}. */
    static void save(Context context, WebResponse response, Consumer<JSONObject> done) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        IO.execute(() -> {
            if (music(app, response)) {
                main.post(() -> BrowserMusicDownloads.save(app, response, line -> done.accept(obj("ok", line.startsWith("저장 완료"), "message", line))));
                return;
            }
            JSONObject result;
            try {
                result = toDownloads(app.getContentResolver(), MediaStore.Downloads.EXTERNAL_CONTENT_URI, response);
                notifySaved(app, result);
            } catch (Exception | OutOfMemoryError failed) {
                close(response.body);
                String why = failed.getMessage() == null || !(failed instanceof IOException) ? "저장 공간을 확인하고 사이트에서 다시 받아 주세요." : failed.getMessage();
                result = obj("ok", false, "refused", why.contains("실행 파일"), "message", why.contains("실행 파일")
                    ? "설치 파일·실행 파일이라 받지 않았어요(휴대폰 보안). 웹페이지는 그대로예요." : "다운로드하지 못했어요 · " + why);
            }
            JSONObject value = result;
            main.post(() -> done.accept(value));
        });
    }
    /** A sound the music folder (들퍼레/음악) takes, when that folder is connected. */
    private static boolean music(Context app, WebResponse response) {
        if (MusicDownload.classify(response.uri, response.headers) == null) return false;
        try { BrowserMusicDownloads.selectedTree(app); return true; } catch (IOException | RuntimeException none) { return false; }
    }
    /**
     * Writes the body into a new row of collection (the phone's Downloads) in Download/, pending until the whole file is there:
     * a refused or broken download leaves nothing behind. The phone renames a name already taken ("시험 (1).pdf").
     */
    static JSONObject toDownloads(ContentResolver resolver, Uri collection, WebResponse response) throws IOException {
        Uri row = null;
        try (InputStream body = response.body) {
            if (response.statusCode < 200 || response.statusCode >= 300 || response.statusCode == 206)
                throw new IOException("다운로드 응답이 완전하지 않아요(" + response.statusCode + "). 사이트에서 다시 받아 주세요.");
            BrowserArtifactPolicy.Info info = BrowserDownloadPolicy.info(response.uri, response.headers);
            try { response.setReadTimeoutMillis(30_000); } catch (RuntimeException notGecko) { /* a body that is not Gecko's has no timeout */ }
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, info.name());
            // A type that says nothing is left out: the phone then reads it from the name, and keeps the name's extension.
            if (!BrowserDownloadPolicy.generic(info.mime())) values.put(MediaStore.MediaColumns.MIME_TYPE, info.mime());
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            row = resolver.insert(collection, values);
            if (row == null) throw new IOException("다운로드 폴더에 파일을 만들지 못했어요.");
            long size;
            try (OutputStream out = resolver.openOutputStream(row, "w")) {
                if (out == null) throw new IOException("다운로드 폴더의 파일을 열지 못했어요.");
                size = BrowserDownloadPolicy.copy(body, out, info, response.headers, () -> Thread.currentThread().isInterrupted());
            }
            ContentValues published = new ContentValues(); published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(row, published, null, null);
            String name = info.name();
            try (Cursor cursor = resolver.query(row, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null && !cursor.getString(0).isBlank()) name = cursor.getString(0);
            } catch (RuntimeException unknown) { /* the name it was given */ }
            Uri saved = row; row = null;
            return obj("ok", true, "name", name, "mime", info.mime(), "size", size, "uri", saved.toString(), "folder", "다운로드",
                "message", "다운로드 완료 · 다운로드/" + name);
        } finally {
            if (row != null) try { resolver.delete(row, null, null); } catch (RuntimeException ignored) { /* best effort */ }
        }
    }
    /** "다운로드 완료 · 시험지.pdf": a tap opens the file, 다운로드 폴더 opens the folder. */
    static void notifySaved(Context app, JSONObject saved) {
        try {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager == null) return;
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "브라우저 다운로드", NotificationManager.IMPORTANCE_DEFAULT));
            int id = NOTIFICATION_BASE + (NOTIFICATIONS.incrementAndGet() & 0xff);
            Uri uri = Uri.parse(saved.optString("uri"));
            String mime = saved.optString("mime");
            Intent view = new Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (BrowserDownloadPolicy.generic(mime)) view.setData(uri); else view.setDataAndType(uri, mime);
            PendingIntent open = PendingIntent.getActivity(app, id, view, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent folder = PendingIntent.getActivity(app, id, new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.notify(id, new Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("다운로드 완료")
                .setContentText(saved.optString("name")).setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "다운로드 폴더", folder).build()).build());
        } catch (RuntimeException unavailable) { /* the file is saved; the browser's line says so */ }
    }
    private static void close(InputStream input) { if (input != null) try { input.close(); } catch (IOException ignored) { } }
}
