package dev.jun.gyeot;

import android.content.SharedPreferences;
import dev.jun.gyeot.core.GeminiWork;
import dev.jun.gyeot.core.Net;
import dev.jun.gyeot.core.Reasoning;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Gemini in 작업창: its key (one saved for 작업창, else the voice's Gemini key), the model and thinking level chosen for it,
 * and the models the key offers. Network work runs on a private thread, never the main thread.
 */
final class GeminiWorkAccount {
    private final SecretStore secrets;
    private final SharedPreferences settings;
    private final Runnable changed;
    private final ExecutorService network = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-gemini-work"); t.setDaemon(true); return t; });
    /** Masked 작업창 key for settings; cached because reading it means a Keystore decryption. */
    private volatile String label;
    /** Progress or problem of the latest key check or model list, shown in settings; empty when there is nothing to say. */
    volatile String notice = "";

    GeminiWorkAccount(SecretStore secrets, SharedPreferences settings, Runnable changed) { this.secrets = secrets; this.settings = settings; this.changed = changed; }
    /** 작업창 Gemini can answer: a key is saved. */
    boolean connected() { return secrets.hasWorkKey(); }
    /** An API key is saved (voice, speech, transcription and the key's model list need one). */
    boolean hasKey() { return secrets.hasWorkKey(); }
    /** A key of its own was saved for 작업창 (else it uses the voice key). */
    boolean ownKey() { return secrets.has(SecretStore.GEMINI_WORK); }
    String label() {
        String cached = label;
        if (cached != null) return cached;
        try { String key = secrets.get(SecretStore.GEMINI_WORK); return label = key.length() <= 12 ? "••••" : key.substring(0, 6) + "…" + key.substring(key.length() - 4); }
        catch (Exception e) { return "저장된 키를 읽지 못했어요"; }
    }
    String model() { return settings.getString("geminiWorkModel", GeminiWork.DEFAULT_MODELS[0][0]); }
    void model(String id) { settings.edit().putString("geminiWorkModel", id).apply(); changed.run(); }
    /** A model typed by name (one the list does not show yet); it stays chosen when the list is read again. */
    void customModel(String id) {
        if (!GeminiWork.validModel(id)) throw new IllegalArgumentException("모델 이름은 gemini-로 시작해요 (예: gemini-4-argon).");
        settings.edit().putString("geminiWorkCustom", id).putString("geminiWorkModel", id).apply(); changed.run();
    }
    /** The key's models as [{id, name}], a typed one first; the -latest ones until the list has been read. */
    JSONArray models() { return keyModels(); }
    /** The API key's models (the speech tool picks its voice model from these). */
    JSONArray keyModels() {
        JSONArray list;
        try { list = new JSONArray(settings.getString("geminiWorkModels", "[]")); } catch (Exception e) { list = new JSONArray(); }
        if (list.length() == 0) list = GeminiWork.models(new JSONObject());
        String custom = settings.getString("geminiWorkCustom", "");
        if (custom.isEmpty() || listed(list, custom)) return list;
        JSONArray result = new JSONArray().put(new JSONObject(java.util.Map.of("id", custom, "name", GeminiWork.label(custom))));
        for (int i = 0; i < list.length(); i++) result.put(list.opt(i));
        return result;
    }
    private static boolean listed(JSONArray models, String id) {
        for (int i = 0; i < models.length(); i++) if (models.optJSONObject(i) != null && id.equals(models.optJSONObject(i).optString("id"))) return true;
        return false;
    }
    /** Raised when GeminiWork.models keeps more models than before (families like Argon), so a saved list is read again. */
    private static final int LIST_VERSION = 2;
    /** The key's own list has been read, with the current filter. */
    boolean listed() { return settings.contains("geminiWorkModels") && settings.getInt("geminiWorkListVersion", 1) == LIST_VERSION; }
    Reasoning.Profile profile(String model) { return GeminiWork.profile(model); }
    String effort(String model) { return new Reasoning.Selections(settings.getString("geminiWorkEffort", "{}")).get(model, profile(model)); }
    void effort(String model, String effort) {
        if (!model.equals(model())) return;
        Reasoning.Selections selections = new Reasoning.Selections(settings.getString("geminiWorkEffort", "{}")); selections.set(model, effort, profile(model));
        settings.edit().putString("geminiWorkEffort", selections.serialize()).apply(); changed.run();
    }
    /** The API key one text request goes with. Call off the main thread. */
    String key() throws Exception { return apiKey(); }
    /** The connection behind key(), for the fingerprint that stops work bound to an older one. */
    String credential() throws Exception { return secrets.workKey(); }
    /** The API key itself (voice, speech, transcription use it too). */
    String apiKey() throws Exception {
        String key = secrets.workKey();
        if (key.isBlank()) throw new IllegalStateException("설정 → Gemini API 키에서 키를 먼저 넣어 주세요.");
        return key;
    }

    /** Checks a key for 작업창 by reading its model list, then keeps it; blank goes back to the voice key. */
    void saveKey(String value) {
        String key = value == null ? "" : value.strip();
        if (key.isEmpty()) {
            network.execute(() -> {
                synchronized (this) { try { secrets.putWork(""); } catch (Exception ignored) {} label = null; }
                notice(""); refresh();
            });
            return;
        }
        if (key.contains(" ") || key.contains("\n") || key.length() < 16) throw new IllegalArgumentException("API 키 형식을 확인해 주세요.");
        notice("키를 확인하고 모델 목록을 불러오는 중이에요…");
        network.execute(() -> {
            try {
                JSONArray models = GeminiWorkClient.models(key);
                synchronized (this) { secrets.putWork(key); label = null; }
                keepModels(models); notice("");
            } catch (Exception error) { notice(error instanceof java.io.IOException ? Net.explain(error) : error.getMessage() == null ? "키를 확인하지 못했어요." : error.getMessage()); }
        });
    }
    /** Reads the key's model list again (after a key change, or from the model picker). */
    void refreshModelsAsync() { network.execute(this::refresh); }
    private void refresh() {
        try { if (hasKey()) { keepModels(GeminiWorkClient.models(apiKey())); notice(""); } }
        catch (Exception error) { notice(error instanceof java.io.IOException ? Net.explain(error) : error.getMessage() == null ? "모델 목록을 불러오지 못했어요." : error.getMessage()); }
    }
    private void keepModels(JSONArray models) {
        settings.edit().putString("geminiWorkModels", models.toString()).putInt("geminiWorkListVersion", LIST_VERSION).apply();
        String keyModel = settings.getString("geminiWorkModel", GeminiWork.DEFAULT_MODELS[0][0]);
        boolean kept = listed(models, keyModel) || keyModel.equals(settings.getString("geminiWorkCustom", ""));
        if (!kept && models.length() > 0) settings.edit().putString("geminiWorkModel", models.optJSONObject(0).optString("id")).apply();
        changed.run();
    }
    private void notice(String value) { notice = value; changed.run(); }
}
