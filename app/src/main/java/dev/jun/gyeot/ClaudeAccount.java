package dev.jun.gyeot;

import android.content.SharedPreferences;
import dev.jun.gyeot.core.ClaudeProtocol;
import dev.jun.gyeot.core.Net;
import dev.jun.gyeot.core.Reasoning;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The Claude connection: an Anthropic API key, encrypted in SecretStore and sent only in the x-api-key header to
 * api.anthropic.com, with the model and effort chosen for it. Network work runs on a private thread, never the main thread.
 */
final class ClaudeAccount {
    private final SecretStore secrets;
    private final SharedPreferences settings;
    private final Runnable changed;
    private final ExecutorService network = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-claude"); t.setDaemon(true); return t; });
    /** Masked key for settings; cached because reading it means a Keystore decryption. */
    private volatile String label;
    /** Progress or problem of the latest key check, shown in settings; empty when there is nothing to say. */
    volatile String notice = "";

    ClaudeAccount(SecretStore secrets, SharedPreferences settings, Runnable changed) { this.secrets = secrets; this.settings = settings; this.changed = changed; }
    boolean connected() { return secrets.has(SecretStore.CLAUDE); }
    String label() {
        String cached = label;
        if (cached != null) return cached;
        try { String key = secrets.get(SecretStore.CLAUDE); return label = key.length() <= 12 ? "••••" : key.substring(0, 7) + "…" + key.substring(key.length() - 4); }
        catch (Exception e) { return "저장된 키를 읽지 못했어요"; }
    }
    String model() { return dev.jun.gyeot.core.ModelIds.current(dev.jun.gyeot.core.WorkBook.CLAUDE, settings.getString("claudeModel", "")); }
    void model(String id) { settings.edit().putString("claudeModel", id).apply(); changed.run(); }
    /**
     * The saved catalog, without replaced models and those older than 5.2 (core/ModelIds). A list older than MODELS_FRESH_MS is read again in the
     * background, so a model Anthropic released since (Fable 5.2) shows up in every picker without saving the key again.
     */
    JSONArray models() {
        JSONArray saved; try { saved = new JSONArray(settings.getString("claudeModels", "[]")); } catch (Exception e) { saved = new JSONArray(); }
        refreshIfStale();
        JSONArray result = new JSONArray();
        for (int i = 0; i < saved.length(); i++) { JSONObject item = saved.optJSONObject(i); if (item != null && !dev.jun.gyeot.core.ModelIds.retired(dev.jun.gyeot.core.WorkBook.CLAUDE, item.optString("id"))) result.put(item); }
        return result;
    }
    static final long MODELS_FRESH_MS = dev.jun.gyeot.core.ModelWatch.DAY, MODELS_RETRY_MS = 10L * 60 * 1000;
    /** When the last background read of the catalog started (memory only), so a failing one is not repeated on every draw. */
    private volatile long modelsTried;
    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - settings.getLong("claudeModelsAt", 0) < MODELS_FRESH_MS || now - modelsTried < MODELS_RETRY_MS || !connected()) return;
        modelsTried = now;
        // Quiet: a failed read keeps the saved list and says nothing; the key check in settings still reports problems.
        network.execute(() -> { try { keepModels(fetchModels(key())); } catch (Exception ignored) {} });
    }
    /** The chosen model's catalog entry (capabilities), or an empty one. */
    JSONObject info(String model) { JSONObject found = ClaudeProtocol.find(models(), model); return found == null ? new JSONObject() : found; }
    Reasoning.Profile profile(String model) { return Reasoning.profile(model, info(model)); }
    String effort(String model) { return new Reasoning.Selections(settings.getString("claudeEffort", "{}")).get(model, profile(model)); }
    void effort(String model, String effort) {
        if (!model.equals(model())) return;
        Reasoning.Selections selections = new Reasoning.Selections(settings.getString("claudeEffort", "{}")); selections.set(model, effort, profile(model));
        settings.edit().putString("claudeEffort", selections.serialize()).apply(); changed.run();
    }
    /** The key for one request. Call off the main thread. */
    String key() throws Exception {
        String key = secrets.get(SecretStore.CLAUDE);
        if (key.isBlank()) throw new IllegalStateException("설정 → Claude에서 API 키를 먼저 연결해 주세요.");
        return key;
    }
    /** Headers for every request to the Claude API; betas may be null. */
    Map<String, String> headers(String betas) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", key()); headers.put("anthropic-version", ClaudeProtocol.VERSION);
        if (betas != null && !betas.isBlank()) headers.put("anthropic-beta", betas);
        return headers;
    }

    /** Checks the key by reading the model list, then keeps it; a key the API refuses is not saved. */
    void saveKey(String value) {
        String key = value == null ? "" : value.strip();
        if (!ClaudeProtocol.validKey(key)) throw new IllegalArgumentException("Claude API 키 형식을 확인해 주세요 (sk-ant-로 시작).");
        notice("키를 확인하고 모델 목록을 불러오는 중이에요…");
        network.execute(() -> {
            try {
                JSONArray models = fetchModels(key);
                synchronized (this) { secrets.put(SecretStore.CLAUDE, key); label = null; }
                keepModels(models); notice("");
            } catch (Exception error) { notice(error instanceof java.io.IOException ? Net.explain(error) : error.getMessage() == null ? "키를 확인하지 못했어요." : error.getMessage()); }
        });
    }
    void refreshModelsAsync() {
        network.execute(() -> {
            try { keepModels(fetchModels(key())); notice(""); }
            catch (Exception error) { notice(error instanceof java.io.IOException ? Net.explain(error) : error.getMessage() == null ? "모델 목록을 불러오지 못했어요." : error.getMessage()); }
        });
    }
    void disconnect() {
        network.execute(() -> {
            synchronized (this) { try { secrets.put(SecretStore.CLAUDE, ""); } catch (Exception ignored) {} label = null; }
            settings.edit().remove("claudeModels").remove("claudeModelsAt").apply(); notice("");
        });
    }
    /** The model list, read with the beta header that adds each model's fallback options; without it if that header is refused. */
    private JSONArray fetchModels(String key) throws Exception {
        for (String beta : new String[]{ClaudeProtocol.FALLBACK_LIST_BETA, null}) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-api-key", key); headers.put("anthropic-version", ClaudeProtocol.VERSION); headers.put("Accept", "application/json");
            if (beta != null) headers.put("anthropic-beta", beta);
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", ClaudeProtocol.MODELS, headers, null, 30_000, null)) {
                String body = OpenAiAccount.Http.read(response.body, 4 * 1024 * 1024);
                JSONObject json; try { json = new JSONObject(body); } catch (Exception e) { json = new JSONObject(); }
                if (response.status == 400 && beta != null) continue;
                if (response.status >= 300) throw new IllegalStateException(ClaudeProtocol.explain(response.status, ClaudeProtocol.errorType(json), ClaudeProtocol.message(json)));
                JSONArray models = ClaudeProtocol.models(json);
                if (models.length() == 0) throw new IllegalStateException("이 키로 쓸 수 있는 Claude 모델을 찾지 못했어요.");
                return models;
            }
        }
        throw new IllegalStateException("모델 목록을 불러오지 못했어요.");
    }
    private void keepModels(JSONArray models) {
        settings.edit().putString("claudeModels", models.toString()).putLong("claudeModelsAt", System.currentTimeMillis()).apply();
        // A saved default that is gone (or replaced, ModelIds) moves to the successor when listed, else to the preferred model.
        String saved = settings.getString("claudeModel", ""), now = model();
        if (ClaudeProtocol.find(models, now) == null) settings.edit().putString("claudeModel", ClaudeProtocol.preferred(models)).apply();
        else if (!now.equals(saved)) settings.edit().putString("claudeModel", now).apply();
        changed.run();
    }
    private void notice(String value) { notice = value; changed.run(); }
}
