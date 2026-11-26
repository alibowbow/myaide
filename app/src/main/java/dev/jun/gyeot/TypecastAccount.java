package dev.jun.gyeot;

import android.content.SharedPreferences;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Typecast (typecast.ai): the voice of 퍼레 on the home screen (준: "Api 추가에 타입캐스트도 넣어줘", "타입캐스트 음성 api 한달에
 * 15000자 무료인데 이걸 홈화면 퍼레 음성대화에 넣을꺼야", "어울리는 귀여운 목소리로 설정해줘"). The user's API key is kept encrypted
 * on the device (SecretStore.TYPECAST) and sent only to api.typecast.ai. A key is saved once Typecast's voice list answers to
 * it; then 퍼레's voice is chosen: the voices Typecast recommends for a cute, bright, young character are checked against its
 * list (a child or a teen first, for anime, games or conversation, with a happy emotion) and the best is taken, with the
 * next few kept to choose from on the settings page. The characters spoken are counted per month against the free 15,000;
 * past them 퍼레 speaks with the phone's own voice (TypecastVoice).
 */
final class TypecastAccount {
    static final String API = "https://api.typecast.ai", KEYS = "https://typecast.ai/developers/api";
    static final int FREE = 15_000;
    private static final String VOICES = "typecastVoices", VOICE = "typecastVoice", USED = "typecastUsed", MONTH = "typecastMonth";
    /** What 퍼레 sounds like, for Typecast's recommendations. */
    static final String WANTED = "귀엽고 밝고 상냥한 어린 캐릭터 목소리, 한국어 대화, 애니메이션 마스코트";
    private final SecretStore secrets; private final SharedPreferences settings; private final Runnable changed;
    private final ExecutorService network = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-typecast"); t.setDaemon(true); return t; });
    volatile String notice = ""; volatile boolean busy;
    TypecastAccount(SecretStore secrets, SharedPreferences settings, Runnable changed) { this.secrets = secrets; this.settings = settings; this.changed = changed; }

    boolean connected() { return secrets.has(SecretStore.TYPECAST); }
    String key() { try { return secrets.get(SecretStore.TYPECAST); } catch (Exception e) { return ""; } }
    /** 퍼레's voice: {id, name, model, emotions, age, gender, preview}; empty before a key. */
    JSONObject voice() { try { return new JSONObject(settings.getString(VOICE, "{}")); } catch (Exception e) { return new JSONObject(); } }
    /** The voices kept to choose from, best first. */
    JSONArray candidates() { try { return new JSONArray(settings.getString(VOICES, "[]")); } catch (Exception e) { return new JSONArray(); } }
    void choose(JSONObject voice) { if (voice != null && !voice.optString("id").isBlank()) { settings.edit().putString(VOICE, voice.toString()).apply(); changed.run(); } }
    /** Characters spoken this month (the count starts again each month). */
    int used() { return YearMonth.now().toString().equals(settings.getString(MONTH, "")) ? settings.getInt(USED, 0) : 0; }
    synchronized void count(int chars) {
        String month = YearMonth.now().toString();
        int before = month.equals(settings.getString(MONTH, "")) ? settings.getInt(USED, 0) : 0;
        settings.edit().putString(MONTH, month).putInt(USED, before + Math.max(0, chars)).apply();
    }
    /** Whether this many more characters still fit in the month's free allowance. */
    boolean fits(int chars) { return used() + chars <= FREE; }

    /** Checks a pasted key with Typecast's voice list, then keeps it and chooses 퍼레's voice; says why when it does not. */
    void save(String value) {
        String key = value == null ? "" : value.strip();
        if (key.isEmpty() || key.contains(" ") || key.length() < 16) { notice("키 형식을 확인해 주세요. 타입캐스트 API 콘솔의 키를 붙여 넣어요."); return; }
        busy = true; notice("키를 확인하고 퍼레 목소리를 고르는 중이에요…");
        network.execute(() -> {
            try { JSONArray picked = pick(key); secrets.put(SecretStore.TYPECAST, key); keep(picked); notice(""); }
            catch (Exception e) { notice(e.getMessage() == null ? "키를 확인하지 못했어요." : e.getMessage()); }
            finally { busy = false; changed.run(); }
        });
    }
    /** 다시 고르기: the voices asked again with the saved key. */
    void check() {
        if (!connected()) return;
        busy = true; notice("타입캐스트에 연결해 보는 중이에요…");
        network.execute(() -> {
            try { JSONArray picked = pick(key()); keep(picked); notice("연결돼요 · 목소리 후보 " + picked.length() + "개"); }
            catch (Exception e) { notice(e.getMessage() == null ? "연결하지 못했어요." : e.getMessage()); }
            finally { busy = false; changed.run(); }
        });
    }
    void remove() {
        try { secrets.put(SecretStore.TYPECAST, ""); } catch (Exception ignored) {}
        settings.edit().remove(VOICES).remove(VOICE).apply(); notice("");
    }
    /** The candidates kept; the chosen voice stays when it is still among them, else the best is taken. */
    private void keep(JSONArray picked) {
        String now = voice().optString("id"); JSONObject chosen = null;
        for (int i = 0; i < picked.length(); i++) if (picked.optJSONObject(i).optString("id").equals(now)) chosen = picked.optJSONObject(i);
        if (chosen == null && picked.length() > 0) chosen = picked.optJSONObject(0);
        SharedPreferences.Editor e = settings.edit().putString(VOICES, picked.toString());
        if (chosen != null) e.putString(VOICE, chosen.toString());
        e.apply();
    }
    private void notice(String value) { notice = value; changed.run(); }

    /** The cute voices for 퍼레, best first (at most eight): recommended ones that pass the checks, then the best of the list. */
    static JSONArray pick(String key) throws Exception {
        JSONArray all = new JSONArray(get(key, "/v3/voices", 4 * 1024 * 1024));
        JSONArray recommended = new JSONArray();
        try { recommended = new JSONArray(get(key, "/v1/voices/recommendations?count=10&query=" + URLEncoder.encode(WANTED, StandardCharsets.UTF_8.name()), 256 * 1024)); }
        catch (Exception unavailable) { /* the list alone decides */ }
        java.util.Map<String, Double> boost = new java.util.HashMap<>();
        for (int i = 0; i < recommended.length(); i++) { JSONObject r = recommended.optJSONObject(i); if (r != null) boost.put(r.optString("voice_id"), 6.0 * Math.max(0.3, r.optDouble("score", .5))); }
        java.util.List<JSONObject> scored = new java.util.ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject v = all.optJSONObject(i); if (v == null || !"original".equals(v.optString("voice_type", "original"))) continue;
            JSONObject model = best(v.optJSONArray("models")); if (model == null) continue;
            double score = cute(v, model) + boost.getOrDefault(v.optString("voice_id"), 0.0);
            JSONObject name = v.optJSONObject("voice_name");
            JSONArray emotions = model.optJSONArray("emotions");
            scored.add(new JSONObject().put("id", v.optString("voice_id")).put("name", name == null ? v.optString("voice_name") : name.optString("kor", name.optString("eng")))
                .put("model", model.optString("version")).put("emotions", emotions == null ? new JSONArray() : emotions)
                .put("age", v.optString("age")).put("gender", v.optString("gender")).put("preview", v.optString("preview_url")).put("score", score));
        }
        if (scored.isEmpty()) throw new IllegalStateException("타입캐스트에서 쓸 수 있는 목소리를 찾지 못했어요.");
        scored.sort((a, b) -> Double.compare(b.optDouble("score"), a.optDouble("score")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < scored.size() && out.length() < 8; i++) out.put(scored.get(i));
        return out;
    }
    /** How well a voice fits 퍼레: young, for anime/games/conversation, able to sound happy. */
    private static double cute(JSONObject v, JSONObject model) {
        double s = switch (v.optString("age")) { case "child" -> 5; case "teenager" -> 4; case "young_adult" -> 1.5; default -> 0; };
        JSONArray uses = v.optJSONArray("use_cases");
        for (int i = 0; uses != null && i < uses.length(); i++) switch (uses.optString(i)) { case "Anime" -> s += 2.5; case "Game" -> s += 1; case "Conversational" -> s += 1.5; default -> {} }
        if ("female".equals(v.optString("gender"))) s += .5;
        JSONArray emotions = model.optJSONArray("emotions");
        for (int i = 0; emotions != null && i < emotions.length(); i++) if ("happy".equals(emotions.optString(i)) || "toneup".equals(emotions.optString(i))) { s += 1; break; }
        return s;
    }
    /** The newest model the voice speaks with. */
    private static JSONObject best(JSONArray models) {
        String[] order = {"ssfm-v31", "ssfm-v30", "ssfm-v21"};
        for (String want : order) for (int i = 0; models != null && i < models.length(); i++) { JSONObject m = models.optJSONObject(i); if (m != null && want.equals(m.optString("version"))) return m; }
        return models != null && models.length() > 0 ? models.optJSONObject(0) : null;
    }
    static String get(String key, String path, int limit) throws Exception {
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", API + path, Map.of("X-API-KEY", key, "Accept", "application/json"), null, 20_000, null)) {
            String body = OpenAiAccount.Http.read(response.body, limit);
            if (response.status == 401 || response.status == 403) throw new IllegalStateException("타입캐스트가 이 키를 받지 않았어요. 키를 다시 확인해 주세요.");
            if (response.status >= 300) throw new IllegalStateException("타입캐스트에 연결하지 못했어요 (HTTP " + response.status + ").");
            return body;
        }
    }
}
