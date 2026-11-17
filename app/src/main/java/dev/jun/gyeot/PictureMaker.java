package dev.jun.gyeot;

import dev.jun.gyeot.core.Drawing;
import dev.jun.gyeot.core.OpenAiProtocol;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs draw_picture: Codex OAuth's Images backend for a ChatGPT plan, the public Images API for an API key.
 * Returns {data (base64), format, revised, model}; network thread only.
 */
final class PictureMaker {
    private PictureMaker() {}

    static JSONObject draw(AppStore store, JSONObject args, JSONArray sources, AtomicReference<Runnable> cancel, Runnable guard, boolean codex) throws Exception {
        if (args.optBoolean("edit") && sources.length() == 0) throw new IllegalStateException("고칠 사진을 먼저 첨부해 주세요.");
        if (codex) return store.codexImages.draw(args, sources, cancel, guard);
        if (store.openai.usesPlan()) throw new IllegalStateException("이미지용 Codex 연결이 필요해요. 설정 → ChatGPT에서 연결해 주세요.");
        guard.run();
        boolean edit = args.optBoolean("edit");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + store.openai.bearer()); headers.put("Accept", "application/json");
        byte[] body;
        if (edit) {
            String boundary = "gyeot" + Long.toHexString(System.nanoTime());
            headers.put("Content-Type", "multipart/form-data; boundary=" + boundary); body = Drawing.openAiEdit(args, sources, boundary);
        } else { headers.put("Content-Type", "application/json"); body = Drawing.openAiRequest(args).toString().getBytes(StandardCharsets.UTF_8); }
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", edit ? Drawing.OPENAI_EDITS : Drawing.OPENAI_IMAGES, headers, body, 180_000, cancel)) {
            String text = OpenAiAccount.Http.read(response.body, 60 * 1024 * 1024);
            JSONObject json; try { json = new JSONObject(text); } catch (Exception e) { json = new JSONObject(); }
            if (response.status >= 300) {
                String message = OpenAiProtocol.failure(json, text);
                if (Drawing.refusedService(response.status, message)) {
                    store.openai.refuse(Drawing.OPENAI_REFUSAL);
                    throw new IllegalStateException("OpenAI가 이 API 키의 이미지 요청을 거절했어요. 설정 → ChatGPT에서 키의 이미지 생성 권한을 확인해 주세요."
                        + (message.isBlank() ? "" : " (" + message + ")"));
                }
                throw new IllegalStateException(OpenAiProtocol.explain(response.status, message));
            }
            JSONObject picture = Drawing.openAiPicture(json);
            if (picture == null) throw new IllegalStateException("OpenAI가 그림을 보내지 않았어요. 표현을 바꿔 다시 요청해 보세요.");
            picture.put("model", Drawing.OPENAI_MODEL);
            return picture;
        } finally { if (cancel != null) cancel.set(null); }
    }
}
