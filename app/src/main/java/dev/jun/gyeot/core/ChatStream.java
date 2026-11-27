package dev.jun.gyeot.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One streamed chat answer on an OpenAI-style endpoint (Groq, NVIDIA NIM), in plain Java: the request's body and the reading
 * of its event stream into the words and the tools called. GroqWork.stream (퍼레's voice talk) and the dev-side runner
 * (tools/BuddyEval.java, 준: "자동테스트도 구현해줘") both send and read it this way, so a check off the phone sends what the
 * phone sends.
 */
public final class ChatStream {
    private ChatStream() {}

    /**
     * The request: the instructions as the system message, then the turns; streamed; the tools with toolChoice ("auto", or
     * "required" when the answer must be one) when there are any; reasoning for GPT OSS and Qwen only (null: the model's own).
     */
    public static JSONObject body(String model, String instructions, JSONArray turns, String reasoning, double temperature, JSONArray tools, String toolChoice) {
        try {
            JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", instructions));
            for (int i = 0; turns != null && i < turns.length(); i++) messages.put(turns.get(i));
            JSONObject body = new JSONObject().put("model", model).put("temperature", temperature).put("messages", messages).put("stream", true);
            if (tools != null && tools.length() > 0) body.put("tools", tools).put("tool_choice", toolChoice == null ? "auto" : toolChoice);
            if (reasoning != null && (model.startsWith("openai/gpt-oss") || model.toLowerCase(java.util.Locale.ROOT).contains("qwen"))) body.put("reasoning_effort", reasoning);
            return body;
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** What came: the words, the tools called ({"name", "arguments"} each, the arguments as the model wrote them) and the tokens counted. */
    public record Turn(String text, JSONArray calls, int tokens) {}
    /** Reads an event stream to its end ([DONE]): each piece of the words to onText as it comes (may be null). */
    public static Turn read(BufferedReader in, Consumer<String> onText) throws IOException {
        StringBuilder text = new StringBuilder(); TreeMap<Integer, String[]> calls = new TreeMap<>(); int tokens = 0;
        for (String line; (line = in.readLine()) != null; ) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).strip();
            if (data.equals("[DONE]")) break;
            JSONObject chunk; try { chunk = new JSONObject(data); } catch (Exception notJson) { continue; }
            JSONObject groq = chunk.optJSONObject("x_groq"), usage = groq == null ? chunk.optJSONObject("usage") : groq.optJSONObject("usage");
            if (usage != null) tokens = usage.optInt("total_tokens", tokens);
            JSONArray choices = chunk.optJSONArray("choices");
            JSONObject delta = choices == null || choices.length() == 0 ? null : choices.optJSONObject(0).optJSONObject("delta");
            if (delta == null) continue;
            if (delta.has("content") && !delta.isNull("content")) {
                String piece = delta.optString("content");
                if (!piece.isEmpty()) { text.append(piece); if (onText != null) onText.accept(piece); }
            }
            JSONArray tc = delta.optJSONArray("tool_calls");
            for (int i = 0; tc != null && i < tc.length(); i++) {
                JSONObject call = tc.optJSONObject(i); if (call == null) continue;
                String[] got = calls.computeIfAbsent(call.optInt("index", i), k -> new String[]{"", ""});
                JSONObject f = call.optJSONObject("function"); if (f == null) continue;
                if (f.has("name") && !f.isNull("name")) got[0] += f.optString("name");
                if (f.has("arguments") && !f.isNull("arguments")) got[1] += f.optString("arguments");
            }
        }
        JSONArray out = new JSONArray();
        try { for (String[] c : calls.values()) if (!c[0].isBlank()) out.put(new JSONObject().put("name", c[0].strip()).put("arguments", c[1])); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return new Turn(text.toString(), out, tokens);
    }
}
