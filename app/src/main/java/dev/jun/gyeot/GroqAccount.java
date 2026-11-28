package dev.jun.gyeot;

import android.content.SharedPreferences;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Groq (console.groq.com): the user's API key, kept encrypted on the device (SecretStore.GROQ) and sent only to Groq. A key is
 * saved only after Groq's model list answers to it; the list is kept so the settings page can show what the key can use
 * (GPT OSS 120B/20B, Qwen, Whisper, Orpheus …). Groq's free tier gives a daily allowance per model.
 */
final class GroqAccount {
    static final String API = "https://api.groq.com/openai/v1/", MODELS = API + "models";
    private final SecretStore secrets; private final SharedPreferences settings; private final Runnable changed;
    private final ExecutorService network = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-groq"); t.setDaemon(true); return t; });
    volatile String notice = ""; volatile boolean busy;
    GroqAccount(SecretStore secrets, SharedPreferences settings, Runnable changed) { this.secrets = secrets; this.settings = settings; this.changed = changed; }

    boolean connected() { return secrets.has(SecretStore.GROQ); }
    /** The key's models as Groq listed them at the last check (ids), newest read first. */
    JSONArray models() { try { return new JSONArray(settings.getString("groqModels", "[]")); } catch (Exception e) { return new JSONArray(); } }
    long modelsAt() { return settings.getLong("groqModelsAt", 0); }

    /** Checks a pasted key with Groq's model list, then keeps it (and the list); says why when it does not. */
    void save(String value) {
        String key = value == null ? "" : value.strip();
        if (key.isEmpty() || key.contains(" ") || key.length() < 20) { notice("키 형식을 확인해 주세요. Groq 키는 gsk_로 시작해요."); return; }
        busy = true; notice("키를 확인하는 중이에요…");
        network.execute(() -> {
            try { JSONArray found = list(key); secrets.put(SecretStore.GROQ, key); keep(found); notice(""); }
            catch (Exception e) { notice(e.getMessage() == null ? "키를 확인하지 못했어요." : e.getMessage()); }
            finally { busy = false; changed.run(); }
        });
    }
    /** 연결 확인: the saved key's model list read again. */
    void check() {
        if (!connected()) return;
        busy = true; notice("Groq에 연결해 보는 중이에요…");
        network.execute(() -> {
            try { JSONArray found = list(secrets.get(SecretStore.GROQ)); keep(found); notice("연결돼요 · 모델 " + found.length() + "개"); }
            catch (Exception e) { notice(e.getMessage() == null ? "연결하지 못했어요." : e.getMessage()); }
            finally { busy = false; changed.run(); }
        });
    }
    void remove() {
        try { secrets.put(SecretStore.GROQ, ""); } catch (Exception ignored) {}
        settings.edit().remove("groqModels").remove("groqModelsAt").apply(); notice("");
    }
    private void keep(JSONArray found) { settings.edit().putString("groqModels", found.toString()).putLong("groqModelsAt", System.currentTimeMillis()).apply(); }
    private void notice(String value) { notice = value; changed.run(); }

    /** Groq's model list for a key: the ids, active ones only. Throws a Korean reason on a refusal. */
    static JSONArray list(String key) throws Exception {
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", MODELS, Map.of("Authorization", "Bearer " + key, "Accept", "application/json"), null, 20_000, null)) {
            String body = OpenAiAccount.Http.read(response.body, 2 * 1024 * 1024);
            if (response.status == 401 || response.status == 403) throw new IllegalStateException("Groq가 이 키를 받지 않았어요. 키를 다시 확인해 주세요.");
            if (response.status >= 300) throw new IllegalStateException("Groq에 연결하지 못했어요 (HTTP " + response.status + ").");
            JSONArray data = new JSONObject(body).optJSONArray("data"), out = new JSONArray();
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject model = data.optJSONObject(i);
                if (model != null && model.optBoolean("active", true) && !model.optString("id").isBlank()) out.put(model.optString("id"));
            }
            return out;
        }
    }
}
