package dev.jun.gyeot;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import dev.jun.gyeot.core.GrokOAuth;
import dev.jun.gyeot.core.GrokVideo;
import dev.jun.gyeot.core.VideoJobs;
import dev.jun.gyeot.core.VideoRun;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Background image upload, result polling and bounded MP4 download. No web cookies and no API-key fallback. */
final class GrokVideoTask {
    private GrokVideoTask() {}
    static void run(Context context, AppStore store, JSONObject job, AtomicReference<Runnable> cancel, Runnable guard) throws Exception {
        VideoRun.run(job, new VideoRun.Backend() {
            private OpenAiAccount.Http.Response send(String method, String path, JSONObject body) throws Exception {
                return store.grok.video(method, GrokVideo.BASE + path, body, job.optString("accountId"), cancel, guard);
            }
            @Override public JSONObject prepare(JSONObject current) throws Exception {
                guard.run();
                if (!current.optString("accountId").equals(store.grok.accountId())) throw new IllegalStateException("설정 → Grok에서 영상 요청에 사용할 계정을 연결해 주세요.");
                if (current.optString("sourceId").isBlank()) return GrokVideo.textRequest(current.optString("prompt"), current.optInt("duration"), VideoJobs.resolution(current), current.optString("aspect", "16:9"));
                Uri sourceUri = Uri.parse(current.optString("sourceUri"));
                // Historical external originals are not read under the new all-files grant.
                // An already-held app-private preview remains a valid operational asset.
                Bitmap bitmap = SharedOutputFolder.canAccess(context, sourceUri)
                    ? WorkFiles.galleryPreview(context, sourceUri.toString(), store.attachmentFile(current.optString("sourceId")), 2048,
                        () -> { guard.run(); try { SharedOutputFolder.requireContentAccess(context, sourceUri); } catch (java.io.IOException denied) { throw new IllegalStateException("원본 사진 권한이 바뀌었어요.", denied); } })
                    : WorkFiles.preview(store.attachmentFile(current.optString("sourceId")), 2048);
                guard.run();
                if (bitmap == null) throw new IllegalStateException("원본 이미지를 읽지 못했어요. 이미지가 갤러리에 남아 있는지 확인해 주세요.");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, bytes)) throw new IllegalStateException("이미지를 준비하지 못했어요."); }
                finally { bitmap.recycle(); }
                String image = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP);
                return GrokVideo.request(current.optString("prompt"), image, current.optInt("duration"), VideoJobs.resolution(current));
            }
            @Override public String create(JSONObject request) throws Exception {
                OpenAiAccount.Http.Response response = send("POST", "/generations", request);
                if (response.status >= 400 && response.status < 500) throw new VideoRun.Rejected(GrokOAuth.error(response.status));
                if (response.status < 200 || response.status >= 300) throw new java.io.IOException("Grok submission interrupted");
                return response.json().optString("request_id");
            }
            @Override public JSONObject poll(String id) throws Exception {
                OpenAiAccount.Http.Response response = send("GET", "/" + GrokVideo.requestId(id), null);
                if (response.status < 200 || response.status >= 300) throw new IllegalStateException(GrokOAuth.error(response.status));
                return response.json();
            }
            @Override public JSONObject download(String url, JSONObject current) throws Exception { return saveVideo(context, store, current, url, cancel, guard); }
        }, updated -> store.changeVideos(all -> {
            JSONObject target = VideoJobs.find(all, job.optString("id"));
            if (target == null) throw new IllegalStateException("영상 요청 기록이 없어 작업을 중지했어요.");
            for (java.util.Iterator<String> keys = updated.keys(); keys.hasNext();) { String key = keys.next(); put(target, key, updated.opt(key)); }
            return null;
        }), guard, new VideoRun.Clock() {
            @Override public long now() { return android.os.SystemClock.elapsedRealtime(); }
            @Override public long wallTime() { return System.currentTimeMillis(); }
            @Override public void waitFor(long millis) throws Exception { Thread.sleep(millis); }
        });
    }
    private static JSONObject saveVideo(Context context, AppStore store, JSONObject job, String url, AtomicReference<Runnable> cancel, Runnable guard) throws Exception {
        String name = "grok-" + job.getString("id") + ".mp4", outputKey = "grok-video-" + job.getString("id");
        // Stable local receipt closes the public-save -> VideoJobs-save process-death gap.
        // Never inspect, reuse or delete legacy Movies rows while saving a new result.
        Uri resumed = SharedMediaOutput.resume(context, outputKey, guard);
        if (resumed != null) return obj("uri", resumed.toString(), "size", mediaSize(context, resumed));
        File partial = new File(context.getCacheDir(), job.getString("id") + ".mp4.part");
        long count = 0;
        try {
            guard.run();
            // Signed download URL only. The subscription bearer is NEVER attached to a media download.
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", GrokVideo.downloadUrl(url), Map.of("Accept", "video/mp4"), null, 90_000, cancel)) {
                if (response.status != 200) throw new IllegalStateException("Grok 영상 다운로드를 마치지 못했어요. 결과를 다시 확인해 주세요.");
                try (FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = response.body.read(buffer)) != -1) {
                        guard.run(); count += n;
                        if (count > 250L * 1024 * 1024) throw new IllegalStateException("영상이 250MB를 넘어 저장하지 않았어요.");
                        output.write(buffer, 0, n);
                    }
                }
            } finally { cancel.set(null); }
            byte[] head = new byte[12];
            try (FileInputStream in = new FileInputStream(partial)) {
                if (count < 32 || in.read(head) != 12 || head[4] != 'f' || head[5] != 't' || head[6] != 'y' || head[7] != 'p')
                    throw new IllegalStateException("다운로드 결과가 MP4 영상이 아니에요.");
            }
            guard.run();
            Uri target;
            try (FileInputStream input = new FileInputStream(partial)) {
                target = SharedMediaOutput.save(context, outputKey, "영상", name, "video/mp4", input, guard);
            }
            return obj("uri", target.toString(), "size", count);
        }
        finally { partial.delete(); }
    }
    private static long mediaSize(Context context, Uri uri) throws Exception {
        SharedOutputFolder.requireContentAccess(context, uri);
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, new String[]{"_size"}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)) throw new java.io.IOException("저장된 영상 크기를 확인하지 못했어요.");
            long size = cursor.getLong(0); if (cursor.moveToNext() || size < 32) throw new java.io.IOException("저장된 영상 크기가 잘못됐어요.");
            return size;
        }
    }

}
