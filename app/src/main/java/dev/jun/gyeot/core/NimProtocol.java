package dev.jun.gyeot.core;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * NVIDIA hosted Chat Completions (OpenAI compatible), always streamed: a non-streamed answer longer than about 30 s comes
 * back as 502. One model for every NIM use; it is named only here.
 */
public final class NimProtocol {
    public static final String ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions";
    /** The one NIM model. */
    public static final String MODEL = "nvidia/nemotron-3-ultra-550b-a55b", LABEL = "Nemotron Ultra";
    /**
     * DEPRECATED: the DeepSeek V4.1 Flash and MiniMax M3.1 Flash ids of older versions. Only read, never sent: a work room
     * or setting saved with one runs on MODEL.
     */
    @Deprecated public static final Set<String> RETIRED = Set.of("deepseek-ai/deepseek-v4.1-flash", "minimaxai/minimax-m3.1-flash");
    /** 502 (the model server did not answer) and 429 (rate limit) are tried once more after RETRY_MS, then the request stops. */
    public static final long RETRY_MS = 2000;
    public static final int MAX_RESPONSE = 2_000_000, MAX_EVENT = 1_000_000;
    private NimProtocol() {}
    public static final String MODELS = "https://integrate.api.nvidia.com/v1/models";
    /** Newer Nemotron chat models NVIDIA lists (ModelWatch.nim), set by the account from its saved list; empty until read. */
    private static volatile JSONArray detected = arr();
    public static void detected(JSONArray models) { detected = models == null ? arr() : models; }
    private static JSONObject found(String model) {
        JSONArray list = detected;
        for (int i = 0; i < list.length(); i++) { JSONObject item = list.optJSONObject(i); if (item != null && item.optString("id").equals(model)) return item; }
        return null;
    }
    /** MODEL, or a newer Nemotron NVIDIA has listed since. */
    public static boolean listed(String model) { return MODEL.equals(model) || found(model) != null; }
    public static boolean supported(String model) { return listed(model) || RETIRED.contains(model); }
    /**
     * The quick model the app itself sends for 퍼레's short answers (BuddyPrompt.NIM_MODEL): sent as it is, never offered to
     * choose. 퍼레 자동 점검 (2026-11-27) found every NimClient request with it refused here before it left ("NVIDIA NIM에서는
     * Nemotron Ultra를 사용해요"): ModelWatch lists only the ultra and super lines, so 퍼레's lines and the voice talk's text form
     * never reached NVIDIA when it was the brain.
     */
    public static final Set<String> QUICK = Set.of("nvidia/nemotron-3.5-bolt-e4b");
    /** The id a request sends: the model itself, or MODEL for a retired id saved earlier. */
    public static String model(String model) {
        if (QUICK.contains(model)) return model;
        if (!supported(model)) throw new IllegalArgumentException("NVIDIA NIM에서는 " + LABEL + "를 사용해요.");
        return listed(model) ? model : MODEL;
    }
    public static String label(String model) { JSONObject item = found(model); return item == null || MODEL.equals(model) ? LABEL : item.optString("name", LABEL); }
    /** Nemotron Ultra and the newer Nemotron chat models NVIDIA has listed since, newest first. */
    public static JSONArray models() {
        JSONArray list = detected;
        if (list.length() == 0) return arr(obj("id", MODEL, "name", LABEL));
        try { return new JSONArray(list.toString()); } catch (Exception e) { return arr(obj("id", MODEL, "name", LABEL)); }
    }
    /** Whether a refused request is sent once more (NimClient): before any answer, so nothing runs twice. */
    public static boolean retries(int status) { return status == 502 || status == 429; }
    // No common reasoning-control field is documented for the hosted model: server defaults, no effort names.
    public static Reasoning.Profile profile(String model) { model(model); return Reasoning.Profile.of(List.of(), ""); }
    public static String key(String key) {
        String value = key == null ? "" : key.strip();
        if (value.length() < 16 || value.length() > 4096 || value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw new IllegalArgumentException("NVIDIA에서 발급한 API 키를 확인해 주세요.");
        return value;
    }
    public static JSONObject request(String model, String instructions, JSONArray messages, JSONArray functions) { return request(model, instructions, messages, functions, false); }
    /** As above; usage asks the stream to end with the request's token counts (stream_options.include_usage: a last chunk without choices). */
    public static JSONObject request(String model, String instructions, JSONArray messages, JSONArray functions, boolean usage) {
        if (messages == null || messages.length() == 0) throw new IllegalArgumentException("보낼 대화가 없어요.");
        JSONArray input = arr(obj("role", "system", "content", instructions));
        for (int i = 0; i < messages.length(); i++) input.put(messages.opt(i));
        // Sampling stays at the model's own defaults on NVIDIA's side.
        JSONObject body = obj("model", model(model), "messages", input, "stream", true, "max_tokens", 16384);
        if (usage) put(body, "stream_options", obj("include_usage", true));
        if (functions != null && functions.length() > 0) {
            JSONArray tools = arr();
            for (int i = 0; i < functions.length(); i++) {
                JSONObject function = functions.optJSONObject(i);
                if (function == null) throw new IllegalArgumentException("NIM 도구 형식을 확인해 주세요.");
                tools.put(obj("type", "function", "function", function));
            }
            put(body, "tools", tools); put(body, "tool_choice", "auto");
        }
        return body;
    }
    public static JSONArray conversation(JSONArray turns) {
        JSONArray result = arr();
        for (int i = 0; turns != null && i < turns.length(); i++) {
            JSONObject turn = turns.optJSONObject(i); if (turn == null) continue;
            boolean user = !"assistant".equals(turn.optString("role"));
            String text = turn.optString("content"); JSONArray parts = arr(), files = user ? turn.optJSONArray("files") : null;
            for (int f = 0; files != null && f < files.length(); f++) {
                JSONObject file = files.optJSONObject(f); if (file == null) continue;
                if (WorkBook.PDF.equals(file.optString("kind"))) throw new IllegalArgumentException("NIM에는 PDF 원본을 바로 보낼 수 없어요. PDF를 이미지·텍스트로 바꾸거나 PDF를 지원하는 모델을 골라 주세요.");
                String data = file.optString("data"); if (data.isEmpty()) continue;
                String mime = file.optString("mime");
                if (!WorkBook.IMAGE.equals(file.optString("kind")) || !Set.of("image/jpeg", "image/png", "image/webp").contains(mime))
                    throw new IllegalArgumentException("NIM에는 JPEG·PNG·WebP 이미지와 텍스트를 보낼 수 있어요.");
                parts.put(obj("type", "image_url", "image_url", obj("url", "data:" + mime + ";base64," + data)));
            }
            if (parts.length() == 0) { if (!text.isBlank()) result.put(obj("role", user ? "user" : "assistant", "content", text)); }
            else {
                JSONArray content = arr(obj("type", "text", "text", text.isBlank() ? "첨부한 이미지를 봐 주세요." : text));
                for (int p = 0; p < parts.length(); p++) content.put(parts.opt(p));
                result.put(obj("role", "user", "content", content));
            }
        }
        return result;
    }
    /** A context-window refusal keeps only the provider's numbers, so a caller can send less; never the raw body. */
    public static String error(int status, String detail) {
        if ((status == 400 || status == 413 || status == 422) && ContextWindow.overflow(detail)) return ContextWindow.refusal("NVIDIA NIM", detail);
        return error(status);
    }
    public static String error(int status) {
        return switch (status) {
            case 202 -> "NVIDIA가 요청을 대기 상태로 돌려줬어요. 아직 완료된 답변이 없어요.";
            case 400, 422 -> "NVIDIA NIM이 요청 형식을 거절했어요. 이미지·도구 지원을 확인해 주세요.";
            case 401, 403 -> "NVIDIA API 키 문제예요 (HTTP " + status + "). 키가 틀렸거나 만료됐거나 이 모델을 쓸 권한이 없어요. 설정 → NVIDIA NIM에서 키를 확인해 주세요.";
            case 402 -> "이 NVIDIA 계정의 체험 할당량이 없어요. NVIDIA 계정에서 확인해 주세요.";
            case 404 -> "NVIDIA에서 " + LABEL + " 모델을 찾지 못했어요. 모델 제공 상태를 확인해 주세요.";
            case 429 -> "NVIDIA NIM의 요청 속도 또는 무료 체험 할당량에 도달했어요. 한 번 다시 시도했지만 같아서 멈췄어요. 잠시 뒤 다시 보내 주세요.";
            case 502 -> "NVIDIA NIM 서버가 답하지 못했어요 (HTTP 502). 한 번 다시 시도했지만 같아서 멈췄어요. 잠시 뒤 다시 보내 주세요.";
            default -> "NVIDIA NIM에 연결하지 못했어요 (HTTP " + status + "). 잠시 뒤 다시 시도해 주세요.";
        };
    }
    /**
     * NVIDIA refused the request before doing any work because of stream_options: a 400 or 422 whose raw
     * body names stream_options or include_usage anywhere, in its message or, as FastAPI's validation answers do, only in
     * detail[].loc. An error that merely echoes the request matches too; the client sends such a request again only once,
     * and remembers the refusal only after the request without the field was answered.
     */
    public static boolean refusesUsage(int status, String body) {
        return (status == 400 || status == 422) && body != null && body.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(stream_options|include_usage).*");
    }

    /** Bounded SSE parser; reasoning is retained only for tool continuation, never displayed or persisted. */
    public static final class Stream {
        private final StringBuilder event = new StringBuilder(), text = new StringBuilder(), reasoning = new StringBuilder();
        private final TreeMap<Integer, JSONObject> calls = new TreeMap<>();
        private String finish = "";
        private boolean done;
        private int received;
        /** The usage object of the chunk that carried one (the last, with stream_options.include_usage); others send null. */
        private JSONObject usage;
        public boolean done() { return done; }
        public String text() { return text.toString(); }
        /** Tokens the answer used (Usage.chat), Usage.UNKNOWN when the stream carried no usage. */
        public Usage usage() { return Usage.chat(usage); }
        public boolean thinking() { return reasoning.length() > 0 && text.length() == 0; }
        public String line(String line) {
            if (line == null || line.isEmpty()) return flush();
            if (line.startsWith("data:")) {
                if (event.length() > 0) event.append('\n');
                event.append(line.substring(line.startsWith("data: ") ? 6 : 5));
                if (event.length() > MAX_EVENT) throw new IllegalStateException("NIM 응답 한 조각이 너무 커서 멈췄어요.");
            }
            return "";
        }
        private String flush() {
            if (event.length() == 0) return "";
            String data = event.toString(); event.setLength(0);
            if (done) throw new IllegalStateException("NIM이 완료 뒤에 추가 응답을 보내 멈췄어요.");
            if (data.strip().equals("[DONE]")) { done = true; return ""; }
            received += data.length(); if (received > MAX_RESPONSE) throw new IllegalStateException("NIM 응답이 너무 길어 멈췄어요.");
            JSONObject packet;
            try { packet = new JSONObject(data); } catch (Exception e) { throw new IllegalStateException("NIM 응답 형식을 읽지 못했어요."); }
            if (packet.has("error")) throw new IllegalStateException("NVIDIA NIM이 응답 중 오류를 반환했어요. 계정·모델 상태를 확인해 주세요.");
            if (packet.optJSONObject("usage") != null) usage = packet.optJSONObject("usage");
            JSONArray choices = packet.optJSONArray("choices"); if (choices == null || choices.length() == 0) return "";
            JSONObject choice = choices.optJSONObject(0); if (choice == null || choice.optInt("index", 0) != 0) throw new IllegalStateException("NIM 응답 순서가 올바르지 않아요.");
            if (!finish.isEmpty()) throw new IllegalStateException("NIM이 답변 종료 뒤에 추가 내용을 보내 멈췄어요.");
            JSONObject delta = choice.optJSONObject("delta"); String added = "";
            if (delta != null) {
                if (!delta.isNull("content")) { added = delta.optString("content"); text.append(added); }
                if (!delta.isNull("reasoning_content")) reasoning.append(delta.optString("reasoning_content"));
                if (!delta.optString("refusal").isBlank()) throw new IllegalStateException("NIM 모델이 이 요청에 대한 답변을 거절했어요.");
                JSONArray pieces = delta.optJSONArray("tool_calls");
                for (int i = 0; pieces != null && i < pieces.length(); i++) {
                    JSONObject piece = pieces.optJSONObject(i);
                    if (piece == null) throw new IllegalStateException("NIM 도구 응답이 올바르지 않아요.");
                    int index = piece.optInt("index", -1);
                    if (index < 0 || index >= 16 || piece.has("type") && !piece.optString("type").equals("function")) throw new IllegalStateException("NIM 도구 응답이 올바르지 않아요.");
                    JSONObject call = calls.computeIfAbsent(index, n -> obj("id", "", "type", "function", "function", obj("name", "", "arguments", "")));
                    append(call, piece, "id"); JSONObject function = piece.optJSONObject("function");
                    if (function != null) { append(call.optJSONObject("function"), function, "name"); append(call.optJSONObject("function"), function, "arguments"); }
                }
            }
            if (!choice.isNull("finish_reason")) finish = choice.optString("finish_reason");
            return added;
        }
        private static void append(JSONObject target, JSONObject part, String key) {
            if (!part.isNull(key)) put(target, key, target.optString(key) + part.optString(key));
        }
        public void verify() {
            if (!done || finish.isEmpty()) throw new IllegalStateException("NIM 답변이 완료되기 전에 연결이 끊겼어요. 표시된 내용은 일부일 수 있어요.");
            if (finish.equals("length")) throw new IllegalStateException("NIM 답변이 출력 한도에 도달해 중간에 멈췄어요. 요청을 나눠서 보내 주세요.");
            if (!finish.equals(calls.isEmpty() ? "stop" : "tool_calls")) throw new IllegalStateException("NIM 응답을 정상 완료로 확인하지 못했어요.");
            Set<String> ids = new HashSet<>();
            for (JSONObject call : calls.values()) {
                String id = call.optString("id"); JSONObject f = call.optJSONObject("function");
                if (id.isBlank() || id.length() > 200 || !ids.add(id) || f.optString("name").isBlank()) throw new IllegalStateException("NIM 도구 요청이 비어 있거나 중복됐어요.");
                try { new JSONObject(f.getString("arguments")); } catch (Exception e) { throw new IllegalStateException("NIM 도구 인수가 올바른 JSON이 아니어서 멈췄어요."); }
            }
        }
        public JSONArray calls() {
            JSONArray result = arr();
            for (JSONObject call : calls.values()) {
                JSONObject f = call.optJSONObject("function");
                result.put(obj("id", call.optString("id"), "type", "function", "function", obj("name", f.optString("name"), "arguments", f.optString("arguments"))));
            }
            return result;
        }
        public JSONObject assistant() {
            JSONObject value = obj("role", "assistant", "content", text.length() == 0 ? JSONObject.NULL : text.toString());
            if (reasoning.length() > 0) put(value, "reasoning_content", reasoning.toString());
            if (!calls.isEmpty()) put(value, "tool_calls", calls());
            return value;
        }
    }
}
