package dev.jun.gyeot;

import android.content.SharedPreferences;
import dev.jun.gyeot.core.NimProtocol;
import dev.jun.gyeot.core.Reasoning;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device-encrypted NVIDIA key. A key change invalidates queued and streaming requests. */
final class NimAccount implements NimClient.Session {
    private final SecretStore secrets;
    private final SharedPreferences settings;
    private final Runnable changed;
    private final ExecutorService network = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-nim-account"); t.setDaemon(true); return t; });
    private volatile long revision;
    volatile String notice = "";
    volatile boolean busy;
    NimAccount(SecretStore secrets, SharedPreferences settings, Runnable changed) { this.secrets = secrets; this.settings = settings; this.changed = changed; }
    boolean connected() { return secrets.has(SecretStore.NIM); }
    /** The one NIM model. DEPRECATED setting "nimModel" (the DeepSeek/MiniMax choice of older versions) is no longer read. */
    String model() { return NimProtocol.MODEL; }
    JSONArray models() { return NimProtocol.models(); }
    Reasoning.Profile profile(String model) { return NimProtocol.profile(model); }
    String effort(String model) { NimProtocol.model(model); return ""; }
    void effort(String model, String effort) { NimProtocol.model(model); if (!"".equals(effort)) throw new IllegalArgumentException("NIM은 현재 모델의 기본 생각 설정을 사용해요."); }
    @Override public long generation() { return revision; }
    @Override public void check(long expected) {
        if (revision != expected || !connected()) throw new IllegalStateException("NVIDIA 연결이 해제되거나 키가 바뀌었어요. 현재 연결을 확인한 뒤 다시 보내 주세요.");
    }
    /**
     * Models whose stream_options NVIDIA refused, as "model|hash" (16 hex characters of a SHA-256 over the key; the key is
     * never kept) with when, so a request does not fail first every time. After a week the field is tried again, in case
     * NVIDIA takes it by then. Kept in memory and in settings.
     */
    private static final String USAGE_REFUSED = "nimUsageRefused";
    private static final long USAGE_REFUSAL_MS = 7L * 24 * 60 * 60 * 1000;
    private JSONObject usageRefused;
    /** The stored key's hash, worked out once per key (revision). */
    private String keyHash = ""; private long hashed = -1;
    @Override public synchronized boolean asksUsage(String model) {
        String entry = usageEntry(model);
        return entry.isEmpty() || System.currentTimeMillis() - usageRefusals().optLong(entry) >= USAGE_REFUSAL_MS;
    }
    @Override public synchronized void refuseUsage(String model) {
        String entry = usageEntry(model); if (entry.isEmpty()) return;
        JSONObject refusals = usageRefusals(); long now = System.currentTimeMillis();
        java.util.List<String> names = new java.util.ArrayList<>(); for (java.util.Iterator<String> each = refusals.keys(); each.hasNext(); ) names.add(each.next());
        for (String name : names) if (now - refusals.optLong(name) >= USAGE_REFUSAL_MS) refusals.remove(name);
        try { refusals.put(entry, now); } catch (Exception ignored) {}
        settings.edit().putString(USAGE_REFUSED, refusals.toString()).apply();
    }
    private JSONObject usageRefusals() {
        if (usageRefused == null) try { usageRefused = new JSONObject(settings.getString(USAGE_REFUSED, "{}")); } catch (Exception broken) { usageRefused = new JSONObject(); }
        return usageRefused;
    }
    /** "model|hash" for the key in use, "" when none is stored or it cannot be read. */
    private String usageEntry(String model) {
        if (hashed != revision) {
            try {
                String key = secrets.get(SecretStore.NIM);
                byte[] digest = key == null || key.isBlank() ? null : java.security.MessageDigest.getInstance("SHA-256").digest(("gyeot-nim-usage\n" + key).getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(); for (int i = 0; digest != null && i < 8; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
                keyHash = hex.toString(); hashed = revision;
            } catch (Exception unreadable) { return ""; }
        }
        return keyHash.isEmpty() ? "" : model + "|" + keyHash;
    }
    void saveKey(String input) {
        String key = NimProtocol.key(input); final long expected;
        synchronized (this) { expected = ++revision; busy = true; notice = "NVIDIA API 키를 저장하는 중…"; } changed.run();
        network.execute(() -> {
            synchronized (this) {
                if (revision != expected) return;
                try { secrets.put(SecretStore.NIM, key); revision++; notice = "키를 저장했어요. 연결 확인으로 " + NimProtocol.LABEL + "에 짧게 물어볼 수 있어요."; }
                catch (Exception e) { notice = "NVIDIA 키를 저장하지 못했어요. 다시 시도해 주세요."; }
                busy = false;
            }
            changed.run();
        });
    }
    synchronized void disconnect() {
        revision++; busy = false;
        try { secrets.put(SecretStore.NIM, ""); notice = "이 기기의 NVIDIA 연결을 해제했어요."; }
        catch (Exception e) { notice = "NVIDIA 키를 지우지 못했어요. 다시 시도해 주세요."; }
        changed.run();
    }
    void testConnection() {
        final long expected = generation(); final String model = model();
        if (busy) return; busy = true; notice = NimProtocol.label(model) + "의 응답을 확인하는 중…"; changed.run();
        network.execute(() -> {
            String result;
            try {
                WorkClient.Result reply = NimClient.complete(this, expected, model, "Reply in Korean with one short greeting.", "연결 확인입니다.", () -> {}, new AtomicReference<>());
                result = NimProtocol.label(model) + " 연결 확인 ✓ · " + reply.text.strip().substring(0, Math.min(100, reply.text.strip().length()));
            } catch (Exception e) { result = e instanceof IllegalStateException || e instanceof IllegalArgumentException ? e.getMessage() : "NVIDIA 연결을 확인하지 못했어요. 네트워크를 확인해 주세요."; }
            synchronized (this) { if (revision != expected) return; busy = false; notice = result; }
            changed.run();
        });
    }
    @Override public OpenAiAccount.Http.Exchange completions(JSONObject body, long expected, AtomicReference<Runnable> cancel, Runnable guard) throws Exception {
        NimProtocol.model(body.optString("model")); guard.run(); check(expected);
        final String key;
        synchronized (this) { check(expected); key = secrets.get(SecretStore.NIM); }
        guard.run(); check(expected);
        return OpenAiAccount.Http.open("POST", NimProtocol.ENDPOINT,
            Map.of("Authorization", "Bearer " + NimProtocol.key(key), "Content-Type", "application/json", "Accept", "text/event-stream"),
            body.toString().getBytes(StandardCharsets.UTF_8), 300_000, cancel);
    }
}
