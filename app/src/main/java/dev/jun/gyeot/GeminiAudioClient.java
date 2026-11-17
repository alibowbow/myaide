package dev.jun.gyeot;

import dev.jun.gyeot.core.GeminiAudio;
import dev.jun.gyeot.core.GeminiMedia;
import dev.jun.gyeot.core.GeminiProtocol;
import dev.jun.gyeot.core.GeminiSearch;
import dev.jun.gyeot.core.RequestAbort;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Blocking media analysis. Cancellable Work overloads bind every active HTTP phase to its run control. */
final class GeminiAudioClient {
    private GeminiAudioClient() {}
    static String transcribe(String key, File audio) throws Exception { return transcribe(key, audio, GeminiAudio.MIME); }
    static String transcribe(String key, File audio, String mime) throws Exception { return transcribe(key, audio, mime, () -> {}, null); }
    static String transcribe(String key, File audio, String mime, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        guard.run();
        if (!audio.isFile() || audio.length() == 0) throw new IllegalStateException("녹음 파일을 찾지 못했어요.");
        AtomicReference<String> fileName = new AtomicReference<>();
        try {
            JSONObject request;
            if (audio.length() <= GeminiAudio.INLINE_LIMIT) request = GeminiAudio.inline(local(audio, guard), mime);
            else { JSONObject uploaded = upload(key, audio, mime, 60, guard, cancel, fileName); request = GeminiAudio.uploaded(uploaded.optString("uri"), mime); }
            guard.run();
            return generate(key, GeminiAudio.MODELS, request, GeminiAudio::answer, guard, cancel);
        } finally { cleanup(key, fileName.get(), cancel); }
    }
    /** Notes on a public YouTube video, which Gemini watches from its address (GeminiMedia.youtube). */
    static String describeYouTube(String key, String id, String request, boolean lowResolution, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        return generate(key, GeminiMedia.MODELS, GeminiMedia.request(GeminiMedia.youtube(id), GeminiMedia.videoPrompt(request), lowResolution), GeminiMedia::answer, guard, cancel);
    }
    static String describe(String key, File video, String mime, String request, boolean lowResolution) throws Exception {
        return describe(key, video, mime, request, lowResolution, () -> {}, null);
    }
    static String describe(String key, File video, String mime, String request, boolean lowResolution, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        guard.run();
        if (!video.isFile() || video.length() == 0) throw new IllegalStateException("영상 파일을 찾지 못했어요. 다시 첨부해 주세요.");
        AtomicReference<String> fileName = new AtomicReference<>();
        try {
            JSONObject media;
            if (video.length() <= GeminiAudio.INLINE_LIMIT) media = GeminiMedia.inline(local(video, guard), mime);
            else { JSONObject uploaded = upload(key, video, mime, 150, guard, cancel, fileName); media = GeminiMedia.uploaded(uploaded.optString("uri"), mime); }
            guard.run();
            return generate(key, GeminiMedia.MODELS, GeminiMedia.request(media, GeminiMedia.videoPrompt(request), lowResolution), GeminiMedia::answer, guard, cancel);
        } finally { cleanup(key, fileName.get(), cancel); }
    }
    static String summarize(String key, String instructions, String transcript) throws Exception {
        return generate(key, GeminiSearch.MODELS, GeminiAudio.text(instructions, transcript), GeminiAudio::answer, () -> {}, null);
    }
    private static byte[] local(File file, Runnable guard) throws Exception {
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            transfer(input, output, guard, GeminiAudio.INLINE_LIMIT); return output.toByteArray();
        }
    }
    private static void transfer(InputStream input, OutputStream output, Runnable guard, long limit) throws Exception {
        byte[] buffer = new byte[32768]; long count = 0;
        while (true) { guard.run(); int size = input.read(buffer); if (size == -1) break; guard.run(); count += size;
            if (count > limit) throw new IOException("미디어 크기가 전송 중 바뀌었어요."); output.write(buffer, 0, size); }
        guard.run();
    }
    private static String generate(String key, String[] models, JSONObject request, java.util.function.Function<JSONObject, String> answer,
                                   Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        guard.run(); byte[] body = request.toString().getBytes(StandardCharsets.UTF_8); Exception last = null;
        for (String model : models) {
            guard.run();
            try (Bound bound = new Bound(open("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", "POST", key, 600_000), guard, cancel)) {
                HttpURLConnection connection = bound.connection;
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length); connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) { transfer(new ByteArrayInputStream(body), output, guard, body.length); }
                guard.run(); int status = connection.getResponseCode(); guard.run(); JSONObject json = json(connection, status, guard);
                if (status == 200) { guard.run(); return answer.apply(json); }
                JSONObject error = json.optJSONObject("error"); String message = error == null ? "" : error.optString("message");
                IllegalStateException failure = new IllegalStateException(GeminiProtocol.explain(status + " " + message));
                guard.run(); if (!GeminiSearch.tryNext(status, message) && status != 400) throw failure; last = failure;
            }
        }
        guard.run(); throw last != null ? last : new IllegalStateException("녹음을 처리할 모델을 찾지 못했어요.");
    }
    private static JSONObject upload(String key, File audio, String mime, int checks, Runnable guard,
                                     AtomicReference<Runnable> cancel, AtomicReference<String> uploadedName) throws Exception {
        guard.run(); String uploadUrl;
        try (Bound bound = new Bound(open(GeminiAudio.UPLOAD, "POST", key, 60_000), guard, cancel)) {
            HttpURLConnection start = bound.connection;
            byte[] body = GeminiAudio.startUpload("gyeot-" + audio.getName()).toString().getBytes(StandardCharsets.UTF_8);
            start.setDoOutput(true); start.setFixedLengthStreamingMode(body.length);
            start.setRequestProperty("Content-Type", "application/json"); start.setRequestProperty("X-Goog-Upload-Protocol", "resumable");
            start.setRequestProperty("X-Goog-Upload-Command", "start"); start.setRequestProperty("X-Goog-Upload-Header-Content-Length", String.valueOf(audio.length()));
            start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime);
            try (OutputStream output = start.getOutputStream()) { transfer(new ByteArrayInputStream(body), output, guard, body.length); }
            guard.run(); int status = start.getResponseCode(); guard.run(); uploadUrl = start.getHeaderField("X-Goog-Upload-URL");
            if (status != 200 || !GeminiAudio.ownHost(uploadUrl)) throw new IOException("파일을 Gemini에 올릴 준비를 하지 못했어요 (" + status + ").");
        }
        guard.run(); JSONObject file;
        try (Bound bound = new Bound(open(uploadUrl, "POST", key, 600_000), guard, cancel)) {
            HttpURLConnection send = bound.connection; long size = audio.length();
            send.setDoOutput(true); send.setFixedLengthStreamingMode(size); send.setRequestProperty("X-Goog-Upload-Offset", "0"); send.setRequestProperty("X-Goog-Upload-Command", "upload, finalize");
            try (InputStream input = new FileInputStream(audio); OutputStream output = send.getOutputStream()) { transfer(input, output, guard, size); }
            guard.run(); int status = send.getResponseCode(); JSONObject json = json(send, status, guard);
            if (status != 200) throw new IOException("파일을 Gemini에 올리지 못했어요 (" + status + ").");
            file = GeminiAudio.file(json); uploadedName.set(file.optString("name")); guard.run();
        }
        for (int wait = 0; "PROCESSING".equals(file.optString("state")) && wait < checks; wait++) {
            for (int tick = 0; tick < 20; tick++) { guard.run(); Thread.sleep(100); }
            guard.run();
            try (Bound bound = new Bound(open(GeminiAudio.API + file.optString("name"), "GET", key, 30_000), guard, cancel)) {
                int status = bound.connection.getResponseCode(); guard.run(); if (status == 200) file = GeminiAudio.file(json(bound.connection, status, guard));
            }
        }
        guard.run(); if (!"ACTIVE".equals(file.optString("state", "ACTIVE")) || file.optString("uri").isBlank()) throw new IOException("올린 파일을 Gemini가 아직 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
        return file;
    }
    private static void cleanup(String key, String name, AtomicReference<Runnable> cancel) {
        if (name == null || !name.matches("files/[A-Za-z0-9_-]+")) return;
        // Work cancellation must not wait for cleanup HTTP; best effort and never a replay of upload/generate.
        if (cancel == null) deleteQuietly(key, name); else RequestAbort.submit(() -> deleteQuietly(key, name));
    }
    private static void deleteQuietly(String key, String name) {
        try { HttpURLConnection delete = open(GeminiAudio.API + name, "DELETE", key, 15_000); try { delete.getResponseCode(); } finally { delete.disconnect(); } } catch (Exception ignored) {}
    }
    private static final class Bound implements AutoCloseable {
        final HttpURLConnection connection; final AtomicReference<Runnable> owner; final Runnable abort;
        Bound(HttpURLConnection connection, Runnable guard, AtomicReference<Runnable> owner) {
            this.connection = connection; this.owner = owner; abort = connection::disconnect;
            if (owner != null) owner.set(abort);
            try { guard.run(); } catch (RuntimeException | Error error) { close(); throw error; }
        }
        public void close() { if (owner != null) owner.compareAndSet(abort, null); connection.disconnect(); }
    }
    private static HttpURLConnection open(String url, String method, String key, int readTimeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(readTimeoutMs); connection.setRequestMethod(method);
        if (GeminiAudio.ownHost(url)) connection.setRequestProperty("x-goog-api-key", key);
        return connection;
    }
    private static JSONObject json(HttpURLConnection connection, int status, Runnable guard) throws Exception {
        guard.run(); InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = "";
        if (stream != null) try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            transfer(input, output, guard, 16 * 1024 * 1024); text = output.toString(StandardCharsets.UTF_8);
        }
        guard.run(); try { return new JSONObject(text); } catch (Exception e) { return new JSONObject(); }
    }
}
