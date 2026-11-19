package dev.jun.gyeot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.BrowserArtifactPolicy;
import dev.jun.gyeot.core.WorkBook;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.mozilla.geckoview.WebResponse;
import static dev.jun.gyeot.core.Jsons.*;

/** Captures only Gecko's original response stream. No URL fetch, cookie export or file execution. */
final class BrowserWorkArtifacts {
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), r -> { Thread t = new Thread(r, "work-browser-file"); t.setDaemon(true); return t; });
    private static final ScheduledExecutorService WATCH = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "work-browser-file-cancel"); t.setDaemon(true); return t;
    });
    private BrowserWorkArtifacts() {}
    static void save(Context context, AppStore store, WebResponse response, JSONObject provenance,
                     BooleanSupplier cancelled, Consumer<JSONObject> callback) {
        Handler main = new Handler(Looper.getMainLooper());
        // Snapshot only known provenance; no cookies, credentials, signed query parameters or arbitrary page fields.
        JSONObject origin = obj("room_id", provenance.optString("room_id"), "run_id", provenance.optString("run_id"),
            "job_id", provenance.optString("job_id"), "tab_id", provenance.optString("tab_id"),
            "source_url", BrowserEngine.safeUrl(response.uri), "page_url", BrowserEngine.safeUrl(provenance.optString("page_url")), "saved_at", System.currentTimeMillis());
        // The label of the page's link the file came from, to tell files apart (untrusted page text, one short line).
        String link = provenance.optString("link_text").replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (!link.isEmpty()) put(origin, "link_text", link.length() > 120 ? link.substring(0, 120) : link);
        long requestedAt = System.nanoTime();
        java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        ScheduledFuture<?> watch = WATCH.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() - requestedAt > TimeUnit.MINUTES.toNanos(5)) timedOut.set(true);
            if (cancelled.getAsBoolean() || timedOut.get()) close(response.body);
        }, 0, 100, TimeUnit.MILLISECONDS);
        try {
            IO.execute(() -> {
                String id = "b" + UUID.randomUUID().toString().replace("-", "");
                File original = store.attachmentFile(id); JSONObject attachment = null, result;
                try (InputStream input = response.body) {
                    if (cancelled.getAsBoolean() || timedOut.get()) throw new InterruptedIOException("다운로드를 취소했어요.");
                    if (response.statusCode < 200 || response.statusCode >= 300 || response.statusCode == 206)
                        throw new IOException("완전한 다운로드 응답이 아니에요.");
                    response.setReadTimeoutMillis(30_000);
                    BrowserArtifactPolicy.Info info = BrowserArtifactPolicy.classify(response.uri, response.headers);
                    long count;
                    try (OutputStream output = new FileOutputStream(original)) {
                        count = BrowserArtifactPolicy.copy(input, output, info, BrowserArtifactPolicy.expected(response.headers), () -> cancelled.getAsBoolean() || timedOut.get());
                    }
                    JSONObject artifact = obj("id", id, "name", info.name(), "mime", info.mime(), "size", count,
                        "uri", BrowserArtifactFiles.uri(id).toString(), "provenance", origin, "analysis", "unsupported", "model_has_contents", false);
                    String analysisError = "";
                    if (!info.kind().isEmpty()) {
                        try {
                            attachment = WorkFiles.browserAttachment(store, id, info.name(), info.mime(), info.kind());
                            put(artifact, "analysis", info.kind().equals(WorkBook.TEXT) ? "text" :
                                info.kind().equals(WorkBook.VIDEO) || info.kind().equals(WorkBook.AUDIO) ? "media_requires_analysis" : "attachment");
                        } catch (Exception e) { analysisError = message(e, "모델 첨부를 준비하지 못했어요."); }
                    }
                    if (cancelled.getAsBoolean() || timedOut.get()) throw new InterruptedIOException("다운로드를 취소했어요.");
                    result = obj("ok", true, "artifact", artifact);
                    if (attachment != null) put(result, "attachment", attachment);
                    if (!analysisError.isBlank()) put(result, "analysis_error", analysisError);
                } catch (Exception e) {
                    original.delete();
                    if (attachment != null && !id.equals(attachment.optString("id"))) store.attachmentFile(attachment.optString("id")).delete();
                    result = obj("ok", false, "cancelled", cancelled.getAsBoolean(), "error", timedOut.get() ? "파일 저장 시간이 5분을 넘어 중단했어요." : message(e, "파일을 저장하지 못했어요."), "provenance", origin);
                } finally { watch.cancel(false); }
                JSONObject completed = result;
                main.post(() -> {
                    if (cancelled.getAsBoolean() && completed.optBoolean("ok")) {
                        discard(store, completed);
                        callback.accept(obj("ok", false, "cancelled", true, "error", "다운로드를 취소했어요.", "provenance", origin));
                    } else callback.accept(completed);
                });
            });
        } catch (RejectedExecutionException full) {
            watch.cancel(false);
            close(response.body);
            main.post(() -> callback.accept(obj("ok", false, "error", "동시 파일 저장 한도에 도달했어요. 완료 후 새 지시로 다시 요청해 주세요.", "provenance", origin)));
        }
    }
    /** Remove only this operation's newly created copies, never an existing user file. */
    static void discard(AppStore store, JSONObject result) {
        JSONObject artifact = result.optJSONObject("artifact"), attachment = result.optJSONObject("attachment");
        if (artifact != null && WorkBook.safeId(artifact.optString("id"))) store.attachmentFile(artifact.optString("id")).delete();
        if (attachment != null && WorkBook.safeId(attachment.optString("id"))) store.attachmentFile(attachment.optString("id")).delete();
    }
    private static void close(InputStream input) { if (input != null) try { input.close(); } catch (IOException ignored) { } }
}
