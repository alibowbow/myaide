package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.DirectHttps;
import dev.jun.gyeot.core.Net;
import dev.jun.gyeot.core.OpenAiLoopback;
import dev.jun.gyeot.core.OpenAiProtocol;
import dev.jun.gyeot.core.PhoneFormats;
import dev.jun.gyeot.core.Reasoning;
import dev.jun.gyeot.core.RequestAbort;
import dev.jun.gyeot.core.GptSpeed;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HttpsURLConnection;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The OpenAI connection used as 들퍼레's analysis LLM: either "Sign in with ChatGPT" (the user's plan pays, no API key) or an
 * API key. Credentials live encrypted in SecretStore; network work runs on a private thread, never the main thread.
 */
final class OpenAiAccount {
    private static final long LOGIN_TIMEOUT_MS = 10 * 60_000L;
    private final Context context;
    private final SecretStore secrets;
    private final SharedPreferences settings;
    private final PhoneFormats phoneFormats;
    private final PhoneFormats workFormats;
    private volatile long phoneSocketRetryAt;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-openai"); t.setDaemon(true); return t; });
    private final Object signInLock = new Object();
    private volatile JSONObject provider;
    /** The current sign-in attempt; a cancelled attempt's cleanup must not touch a newer one. */
    private volatile Thread login;
    private volatile OpenAiLoopback loopback;
    /** Cached describe() result; settings refresh often, and reading it means a Keystore decryption. */
    private volatile JSONObject described;
    /** Progress or problem of the latest sign-in, shown in settings; empty when there is nothing to say. */
    volatile String notice = "";
    volatile boolean signingIn;
    private final Object modelsLock = new Object();
    private final Object modelSelectionLock = new Object();
    private volatile boolean modelsRefreshing;
    private volatile String modelsError = "";
    private long modelsAttemptAt;
    private boolean modelsRefreshAgain;
    private static final long MODELS_TTL_MS = 10 * 60_000L, MODELS_RETRY_MS = 60_000L;

    OpenAiAccount(Context context, SecretStore secrets, SharedPreferences settings, Runnable changed) {
        this.context = context.getApplicationContext(); this.secrets = secrets; this.settings = settings; this.changed = changed;
        phoneFormats = new PhoneFormats(settings.getString("openaiPhoneFormats", "{}"));
        workFormats = new PhoneFormats(settings.getString("openaiWorkFormats", "{}"));
        if (settings.contains("reasoningEffort")) {
            Reasoning.Selections selections = reasoningSelections();
            selections.migrate(model(), settings.getString("reasoningEffort", ""), reasoningProfile(model()));
            settings.edit().putString("openaiReasoning", selections.serialize()).remove("reasoningEffort").apply();
        }
    }
    boolean connected() { return secrets.has(SecretStore.OPENAI); }
    boolean phonePlans(String model) { return phoneFormats.plans(model); }
    long phoneFormatGeneration() { return phoneFormats.generation(); }
    boolean phoneSocketAllowed() { long retry = phoneSocketRetryAt; return retry == 0 || System.nanoTime() - retry >= 0; }
    synchronized void phoneSocketUnavailable(long generation) {
        if (generation == phoneFormats.generation()) phoneSocketRetryAt = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
    }
    synchronized void rememberPhonePlans(String model, long generation) {
        if (phoneFormats.rememberPlans(model, generation)) settings.edit().putString("openaiPhoneFormats", phoneFormats.serialize()).apply();
    }
    boolean workPlans(String model) { return workFormats.plans(model); }
    long workFormatGeneration() { return workFormats.generation(); }
    synchronized void rememberWorkPlans(String model, long generation) {
        if (workFormats.rememberPlans(model, generation)) settings.edit().putString("openaiWorkFormats", workFormats.serialize()).apply();
    }
    private void resetPhoneFormats() {
        phoneFormats.reset(); workFormats.reset(); phoneSocketRetryAt = 0;
        modelsError = ""; synchronized (modelsLock) { modelsAttemptAt = 0; }
        settings.edit().remove("openaiModels").remove("openaiModelsAt").remove("openaiModelsEpoch").remove("openaiModelsDiagnostics").remove("openaiModelsDiagnosticsEpoch").remove("openaiPhoneFormats").remove("openaiWorkFormats").putString("speedConsentEpoch", java.util.UUID.randomUUID().toString()).apply();
    }
    /** The saved choice; a retired one (GPT-5.6 or older, core/ModelIds) reads as its successor. */
    String model() { return dev.jun.gyeot.core.ModelIds.current(dev.jun.gyeot.core.WorkBook.CHATGPT, settings.getString("openaiModel", "")); }
    void model(String id) { synchronized (modelSelectionLock) { settings.edit().putString("openaiModel", id).apply(); } changed.run(); }
    /** The saved catalog, without GPT-5.6 and older, which no picker offers (core/ModelIds); a list saved by an older version may still hold them. */
    JSONArray models() {
        JSONArray saved; try { saved = new JSONArray(settings.getString("openaiModels", "[]")); } catch (Exception e) { return new JSONArray(); }
        JSONArray result = new JSONArray();
        for (int i = 0; i < saved.length(); i++) { JSONObject item = saved.optJSONObject(i); if (item == null || !dev.jun.gyeot.core.ModelIds.gptOld(item.optString("id"))) result.put(saved.opt(i)); }
        return result;
    }
    private Reasoning.Selections reasoningSelections() { return new Reasoning.Selections(settings.getString("openaiReasoning", "{}")); }
    Reasoning.Profile reasoningProfile(String model) { return Reasoning.profile(model, models()); }
    String speedEpoch() { return settings.getString("speedConsentEpoch", "initial"); }
    String speed(String model) {
        if (!speedEpoch().equals(settings.getString("openaiSpeedEpoch." + model, ""))) return "standard";
        return GptSpeed.normalize(settings.getString("openaiSpeed." + model, "standard"));
    }
    void speed(String model, String value) {
        if (!model.equals(model())) return;
        String speed = GptSpeed.normalize(value);
        if ("fast".equals(speed) && !GptSpeed.supported(describe().optString("mode"), model, models()))
            throw new IllegalArgumentException("이 연결과 모델에서 Fast 지원을 확인하지 못했어요.");
        if (!settings.edit().putString("openaiSpeed." + model, speed).putString("openaiSpeedEpoch." + model, speedEpoch()).commit()) throw new IllegalStateException("GPT 속도를 저장하지 못했어요.");
        changed.run();
    }
    String reasoningEffort(String model) { return reasoningSelections().get(model, reasoningProfile(model)); }
    String effectiveReasoningEffort(String model) { return reasoningProfile(model).resolve(reasoningEffort(model)); }
    void reasoningEffort(String model, String effort) {
        // Ignore an old spinner's callback after its model was replaced.
        if (!model.equals(model())) return;
        Reasoning.Selections selections = reasoningSelections(); selections.set(model, effort, reasoningProfile(model));
        settings.edit().putString("openaiReasoning", selections.serialize()).apply(); changed.run();
    }
    /** "chatgpt", "key" or "" with the account label for settings. */
    /** Never takes the credential lock, which a token refresh can hold across a network call; safe on the main thread. */
    JSONObject describe() {
        JSONObject cached = described;
        if (cached != null) return cached;
        return described = describeStored();
    }
    private JSONObject describeStored() {
        try {
            JSONObject stored = load();
            if (stored == null) return obj("mode", "", "label", "");
            return stored.optString("mode").equals("key") ? obj("mode", "key", "label", "API 키 " + mask(stored.optString("apiKey")))
                : obj("mode", "chatgpt", "label", stored.optString("email", stored.optString("name")), "plan", OpenAiProtocol.hasPlanScope(stored.optString("scope")));
        } catch (Exception e) { return obj("mode", "", "label", "", "error", "저장된 ChatGPT 연결 정보를 읽지 못했어요. 다시 연결해 주세요."); }
    }
    private static String mask(String key) { return key.length() <= 8 ? "••••" : key.substring(0, 3) + "…" + key.substring(key.length() - 4); }
    private JSONObject load() throws Exception { String raw = secrets.get(SecretStore.OPENAI); return raw.isBlank() ? null : new JSONObject(raw); }
    private void save(JSONObject value) throws Exception { described = null; secrets.put(SecretStore.OPENAI, value.toString()); }
    private void notice(String value) { notice = value; changed.run(); }

    // ── Sign in with ChatGPT ──────────────────────────────────────────────

    /** Opens the browser for sign-in and finishes in the background when the loopback redirect arrives. */
    void signIn() {
        Thread attempt;
        synchronized (signInLock) {
            cancelSignIn();
            signingIn = true; notice("브라우저에서 ChatGPT 로그인을 마쳐 주세요. 끝나면 " + Brand.EURO + " 돌아오세요.");
            // Its own thread: waiting up to ten minutes for the browser must not hold up model or revocation requests.
            attempt = new Thread(this::runSignIn, "gyeot-openai-signin"); attempt.setDaemon(true); login = attempt;
        }
        attempt.start();
    }
    private void runSignIn() {
        Thread self = Thread.currentThread();
        try (OpenAiLoopback server = new OpenAiLoopback()) {
            synchronized (signInLock) { if (login != self) return; loopback = server; }
            JSONObject endpoints = provider();
            String state = OpenAiProtocol.randomValue(), nonce = OpenAiProtocol.randomValue(), verifier = OpenAiProtocol.randomValue();
            String saved = settings.getString("openaiClientId", ""), hint = describe().optString("label");
            String url = OpenAiProtocol.authorizeUrl(endpoints.getString("authorization_endpoint"), saved, server.redirectUri, state, nonce, verifier, Brand.NAME, hint.contains("@") ? hint : null);
            main.post(() -> { if (login == self) open(url); });
            OpenAiProtocol.Callback callback = OpenAiProtocol.callback(server.await(state, LOGIN_TIMEOUT_MS), saved.isBlank() ? null : saved);
            // Keep the registration even if the one-time code exchange fails, so a retry does not register the app again.
            settings.edit().putString("openaiClientId", callback.clientId).apply();
            Http.Response response = Http.send("POST", endpoints.getString("token_endpoint"), "application/x-www-form-urlencoded",
                OpenAiProtocol.codeExchange(callback.clientId, callback.code, verifier, server.redirectUri), null, 30_000);
            JSONObject data = response.json();
            if (response.status >= 300) throw new IllegalStateException(OpenAiProtocol.explain(response.status, OpenAiProtocol.message(data, "")));
            long now = System.currentTimeMillis();
            JSONObject tokens = OpenAiProtocol.tokens(data, null, now, false);
            JSONObject who = OpenAiProtocol.identity(data.optString("id_token"), callback.clientId, nonce, now / 1000);
            // Only the current attempt saves: one cancelled or replaced meanwhile (로그인 취소, 연결 해제, a new sign-in), whose
            // code exchange could not be interrupted, must not sign the account back in.
            synchronized (signInLock) {
                if (login != self) return;
                synchronized (this) {
                    tokens.put("mode", "chatgpt").put("clientId", callback.clientId).put("sub", who.optString("sub")).put("email", who.optString("email")).put("name", who.optString("name"));
                    resetPhoneFormats();
                    save(tokens);
                }
            }
            notice(OpenAiProtocol.hasPlanScope(tokens.optString("scope")) ? "" : "로그인은 됐지만 ‘ChatGPT 플랜 사용’을 허용하지 않아 모델을 쓸 수 없어요. 다시 로그인해서 허용해 주세요.");
            refreshModels();
        } catch (Exception error) {
            if (login == self) notice(Net.explain(error));
        } finally {
            synchronized (signInLock) { if (login == self) { login = null; loopback = null; signingIn = false; } }
            changed.run();
        }
    }
    void cancelSignIn() {
        synchronized (signInLock) {
            OpenAiLoopback server = loopback; if (server != null) server.close();
            // The attempt's own "finish in the browser" notice goes with it; an earlier attempt's error stays.
            if (login != null) { login.interrupt(); notice = ""; }
            login = null; loopback = null; signingIn = false;
        }
    }
    private void open(String url) {
        try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { cancelSignIn(); notice("브라우저를 열지 못했어요. 기본 브라우저 설정을 확인해 주세요."); }
    }
    private JSONObject provider() throws Exception {
        JSONObject cached = provider;
        if (cached != null) return cached;
        Http.Response response = Http.send("GET", OpenAiProtocol.ISSUER + "/.well-known/openid-configuration", null, null, null, 20_000);
        if (response.status != 200) throw new IllegalStateException("ChatGPT 로그인 설정을 불러오지 못했어요 (" + response.status + ").");
        return provider = OpenAiProtocol.discovery(response.json());
    }

    // ── Credentials for requests ──────────────────────────────────────────

    void saveApiKey(String key) throws Exception {
        String value = key.strip();
        if (!value.startsWith("sk-") || value.length() < 20 || value.contains(" ")) throw new IllegalArgumentException("OpenAI API 키 형식을 확인해 주세요 (sk-로 시작).");
        notice("API 키를 저장하고 모델 목록을 불러오는 중이에요…");
        network.execute(() -> {
            try { synchronized (this) { resetPhoneFormats(); save(obj("mode", "key", "apiKey", value)); } notice(""); refreshModels(); }
            catch (Exception error) { notice("API 키를 저장하지 못했어요."); }
        });
    }
    /** Bearer token for one request, refreshing a ChatGPT token first when it is about to expire. Call off the main thread. */
    synchronized String bearer() throws Exception {
        JSONObject stored = load();
        if (stored == null) throw new IllegalStateException("설정에서 ChatGPT를 먼저 연결해 주세요.");
        if (stored.optString("mode").equals("key")) return stored.getString("apiKey");
        if (!OpenAiProtocol.hasPlanScope(stored.optString("scope"))) throw new IllegalStateException("ChatGPT 플랜 사용 권한이 없어요. 설정에서 다시 로그인해 허용해 주세요.");
        long now = System.currentTimeMillis();
        if (OpenAiProtocol.shouldRefresh(stored, now)) stored = refresh(stored);
        else if (stored.optLong("expiresAt") <= now) throw new IllegalStateException("ChatGPT 연결을 아직 갱신할 수 없어요. 잠시 후 다시 시도해 주세요.");
        return stored.getString("accessToken");
    }
    boolean usesPlan() { return describe().optString("mode").equals("chatgpt"); }
    /**
     * Hosted tools this kind of connection said it does not support ("code_interpreter", "image_generation", "mcp"): a ChatGPT
     * plan takes only web search and the app's own functions. Not offered again for a week, per sign-in kind, so a request does
     * not fail first every time and OpenAI adding one gets tried again.
     */
    boolean refused(String tool) {
        try { return System.currentTimeMillis() - new JSONObject(settings.getString(refusalKey(), "{}")).optLong(tool) < REFUSAL_MS; } catch (Exception e) { return false; }
    }
    void refuse(String tool) {
        JSONObject refusals; try { refusals = new JSONObject(settings.getString(refusalKey(), "{}")); } catch (Exception e) { refusals = new JSONObject(); }
        try { refusals.put(tool, System.currentTimeMillis()); } catch (Exception ignored) {}
        settings.edit().putString(refusalKey(), refusals.toString()).apply(); changed.run();
    }
    private String refusalKey() { return "openaiRefusedTools:" + (usesPlan() ? "plan" : "key"); }
    private static final long REFUSAL_MS = 7L * 24 * 60 * 60 * 1000;
    /** Forces the next bearer() to refresh, after the API rejected an apparently valid token. */
    synchronized void expire() throws Exception {
        JSONObject stored = load();
        if (stored != null && stored.optString("mode").equals("chatgpt")) { stored.put("expiresAt", 0).put("earliestRefreshAt", 0); save(stored); }
    }
    private JSONObject refresh(JSONObject stored) throws Exception {
        JSONObject endpoints = provider();
        Http.Response response = Http.send("POST", endpoints.getString("token_endpoint"), "application/x-www-form-urlencoded",
            OpenAiProtocol.refreshRequest(stored.getString("clientId"), stored.optString("refreshToken")), null, 30_000);
        JSONObject data = response.json();
        if (response.status >= 300) {
            String detail = OpenAiProtocol.message(data, "");
            if (response.status == 400 || response.status == 401) notice("ChatGPT 연결이 만료됐어요. 설정에서 다시 로그인해 주세요.");
            throw new IllegalStateException(OpenAiProtocol.explain(response.status == 400 ? 401 : response.status, detail));
        }
        long now = System.currentTimeMillis();
        JSONObject tokens = OpenAiProtocol.tokens(data, stored.optString("scope"), now, true);
        if (!data.optString("id_token").isEmpty()) {
            JSONObject who = OpenAiProtocol.identity(data.optString("id_token"), stored.getString("clientId"), null, now / 1000);
            if (!who.optString("sub").equals(stored.optString("sub"))) throw new IllegalStateException("갱신된 ChatGPT 계정이 연결된 계정과 달라요. 다시 로그인해 주세요.");
        }
        java.util.Iterator<String> keys = tokens.keys();
        while (keys.hasNext()) { String key = keys.next(); stored.put(key, tokens.get(key)); }
        // Refresh tokens rotate: persist the new one before anything else can fail.
        save(stored);
        return stored;
    }
    /** What modelsStatus says when the list is this account's own and current; settings has nothing to add then. */
    static final String MODELS_FRESH = "연결된 계정의 최신 모델 목록이에요.";
    /** A cached catalog is not a permanent entitlement list. Never replace a user's selection. */
    String modelsStatus() {
        if (modelsRefreshing) return "모델 목록 확인 중… 저장된 목록을 표시해요.";
        if (!modelsError.isBlank()) return "목록 갱신 실패 · 저장된 목록을 표시해요. " + modelsError;
        long at = settings.getLong("openaiModelsAt", 0);
        if (at == 0 || System.currentTimeMillis() < at || System.currentTimeMillis() - at >= MODELS_TTL_MS || !speedEpoch().equals(settings.getString("openaiModelsEpoch", "")))
            return "저장된 모델 목록이에요. 새로고침해 최신 목록을 확인해 주세요.";
        return models().length() == 0 ? "이 연결의 응답에 표시할 모델이 없어요. 다시 확인해 주세요." : MODELS_FRESH;
    }
    /** Local, allowlisted catalog evidence only; never includes credentials or raw response/error text. */
    String modelsDiagnostics() {
        String report = speedEpoch().equals(settings.getString("openaiModelsDiagnosticsEpoch", ""))
            ? settings.getString("openaiModelsDiagnostics", "") : "";
        String version = "";
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; } catch (Exception ignored) {}
        return "GPT 모델 목록 진단 · 앱 " + version + "\n"
            + (modelsRefreshing ? "목록 조회 중 · 아래는 마지막 완료 결과\n" : "")
            + (report.isBlank() ? "아직 이 연결의 진단 결과가 없어요. 모델 목록을 조회하면 기록돼요." : report)
            + "\n목록 표시 여부만 확인해요. 응답에 없다는 사실만으로 계정 권한이 없다고 단정할 수 없어요.\n토큰·이메일·대화·응답 원문은 포함하지 않아요. 자동 전송하지 않아요.";
    }
    private void saveModelsDiagnostics(String epoch, String mode, int status, String outcome, JSONObject body, JSONArray shown) {
        synchronized (this) {
            if (!epoch.equals(speedEpoch())) return;
            settings.edit().putString("openaiModelsDiagnostics", dev.jun.gyeot.core.GptCatalogDiagnostics.report(mode, System.currentTimeMillis(), status, outcome, body, shown).toString())
                .putString("openaiModelsDiagnosticsEpoch", epoch).apply();
        }
    }
    void refreshModelsIfDue() {
        long now = System.currentTimeMillis(), at = settings.getLong("openaiModelsAt", 0);
        if (speedEpoch().equals(settings.getString("openaiModelsEpoch", "")) && at > 0 && now >= at && now - at < MODELS_TTL_MS) return;
        requestModels(false);
    }
    void refreshModelsAsync() { requestModels(true); }
    private void requestModels(boolean force) {
        if (!connected()) return;
        synchronized (modelsLock) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (modelsRefreshing || (!force && modelsAttemptAt != 0 && now - modelsAttemptAt < MODELS_RETRY_MS)) return;
            modelsRefreshing = true; modelsAttemptAt = Math.max(1, now); modelsError = "";
        }
        changed.run();
        network.execute(this::fetchModels);
    }
    /** Called by the serialized sign-in/key-save worker as well as the explicit refresh action. */
    void refreshModels() {
        synchronized (modelsLock) { if (modelsRefreshing) { modelsRefreshAgain = true; return; } modelsRefreshing = true; modelsAttemptAt = Math.max(1, android.os.SystemClock.elapsedRealtime()); modelsError = ""; }
        changed.run(); fetchModels();
    }
    private void fetchModels() {
        String epoch = "", mode = ""; int httpStatus = 0; String outcome = "connection_error";
        try {
            String token;
            synchronized (this) { epoch = speedEpoch(); token = bearer(); mode = describe().optString("mode"); }
            Http.Response response = Http.send("GET", OpenAiProtocol.MODELS, null, null, token, 30_000);
            httpStatus = response.status; outcome = "http_error";
            if (response.status >= 300) throw new IllegalStateException("모델 목록 HTTP " + response.status);
            outcome = "invalid_shape";
            JSONObject body = response.json();
            if (body.optJSONArray("models") == null && body.optJSONArray("data") == null) throw new IllegalStateException("모델 목록 응답을 읽지 못했어요.");
            JSONArray catalog = OpenAiProtocol.models(body);
            synchronized (this) {
                if (!epoch.equals(speedEpoch())) return;
                synchronized (modelSelectionLock) {
                    String selected = model();
                    settings.edit().putString("openaiModels", catalog.toString()).putLong("openaiModelsAt", System.currentTimeMillis())
                        .putString("openaiModelsEpoch", epoch).putString("openaiModel", OpenAiProtocol.refreshedModel(selected, catalog)).apply();
                }
                modelsError = "";
                saveModelsDiagnostics(epoch, mode, httpStatus, "ok", body, catalog);
            }
        } catch (Exception error) { if (epoch.equals(speedEpoch())) { modelsError = outcome.equals("http_error") ? "서버가 목록 조회를 완료하지 못했어요 (HTTP " + httpStatus + ")."
                : outcome.equals("invalid_shape") ? "모델 목록 응답 형식을 확인하지 못했어요." : "연결 또는 네트워크 상태를 확인해 주세요."; saveModelsDiagnostics(epoch, mode, httpStatus, outcome, null, null); } }
        finally {
            boolean again;
            synchronized (modelsLock) { modelsRefreshing = false; again = modelsRefreshAgain; modelsRefreshAgain = false; }
            changed.run();
            if (again) requestModels(true);
        }
    }
    /** Removes local credentials now; a ChatGPT session is also revoked on the server when reachable. */
    void disconnect() {
        cancelSignIn();
        network.execute(() -> {
            JSONObject revoked;
            synchronized (this) {
                try { revoked = load(); } catch (Exception e) { revoked = null; }
                described = null;
                resetPhoneFormats();
                try { secrets.put(SecretStore.OPENAI, ""); } catch (Exception ignored) {}
            }
            settings.edit().remove("openaiModels").apply(); notice("");
            if (revoked == null || !revoked.optString("mode").equals("chatgpt") || revoked.optString("refreshToken").isEmpty()) return;
            try {
                String endpoint = provider().optString("revocation_endpoint");
                if (endpoint.isEmpty()) throw new IOException("no revocation endpoint");
                Http.Response response = Http.send("POST", endpoint, "application/x-www-form-urlencoded", OpenAiProtocol.revokeRequest(revoked.getString("clientId"), revoked.getString("refreshToken")), null, 15_000);
                if (response.status != 200) throw new IOException("revocation " + response.status);
            } catch (Exception e) { notice("이 기기의 연결 정보는 지웠지만 서버 연결 해제를 확인하지 못했어요. ChatGPT 설정에서 앱 연결을 해제해 주세요."); }
        });
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    /** Minimal HTTPS client: no redirects (a redirect could forward credentials), bounded bodies. */
    /** HTTPS for OpenAI. When the phone's resolver cannot find a host, the request goes through public DNS instead (DirectHttps). */
    static final class Http {
        static final class Response {
            final int status; final String body;
            Response(int status, String body) { this.status = status; this.body = body; }
            JSONObject json() { try { return new JSONObject(body); } catch (Exception e) { return new JSONObject(); } }
        }
        /**
         * A response head with its body still streaming; close it when done. headers are the response's own, names in lower
         * case; they are read for limit headers (core/Limits) and never logged.
         */
        static final class Exchange implements Closeable {
            final int status; final InputStream body; final Map<String, String> headers; private final Runnable end;
            Exchange(int status, InputStream body, Runnable end) { this(status, body, Map.of(), end); }
            Exchange(int status, InputStream body, Map<String, String> headers, Runnable end) { this.status = status; this.body = body; this.headers = headers == null ? Map.of() : headers; this.end = end; }
            @Override public void close() { end.run(); }
        }
        static Response send(String method, String url, String contentType, String body, String bearer, int timeoutMs) throws IOException {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            if (bearer != null) headers.put("Authorization", "Bearer " + bearer);
            if (body != null) headers.put("Content-Type", contentType);
            try (Exchange exchange = open(method, url, headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8), timeoutMs, null)) {
                return new Response(exchange.status, read(exchange.body, 4 * 1024 * 1024));
            }
        }
        /**
         * Sends the request and returns once the response head arrives. cancel, when given, always holds an action that aborts
         * the request in flight (safe from any thread, never needed on the main thread). Nothing has been sent when the host
         * lookup fails, so retrying over public DNS cannot repeat a request.
         */
        static Exchange open(String method, String url, Map<String, String> headers, byte[] body, int timeoutMs, AtomicReference<Runnable> cancel) throws IOException {
            URI target = URI.create(url);
            String host = target.getHost();
            if (!DirectHttps.preferred(host)) {
                try { return system(method, target, headers, body, timeoutMs, cancel); }
                catch (UnknownHostException error) { DirectHttps.remember(host); }
            }
            DirectHttps.Response response = DirectHttps.send(method, target, headers, body, timeoutMs, HttpsURLConnection.getDefaultHostnameVerifier(), cancel);
            return new Exchange(response.status, response.body, response.headers, response::close);
        }
        private static Exchange system(String method, URI target, Map<String, String> headers, byte[] body, int timeoutMs, AtomicReference<Runnable> cancel) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
            // The stop is in place before any network I/O. A disconnect before the connection exists does nothing, so a stop
            // that comes first (run, or marked STOPPED by its caller) is also checked before the connection starts and before the
            // request goes out.
            AtomicBoolean stopped = new AtomicBoolean();
            Runnable stop = () -> { stopped.set(true); connection.disconnect(); };
            if (!RequestAbort.register(cancel, stop)) throw DirectHttps.stopped();
            try {
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(timeoutMs);
                connection.setRequestMethod(method);
                for (Map.Entry<String, String> header : headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
                if (stopped(stopped, cancel)) throw DirectHttps.stopped();
                if (body != null) { connection.setDoOutput(true); try (OutputStream output = connection.getOutputStream()) { output.write(body); } }
                if (stopped(stopped, cancel)) throw DirectHttps.stopped();
                int status = connection.getResponseCode();
                InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                Map<String, String> received = new LinkedHashMap<>();
                for (Map.Entry<String, java.util.List<String>> field : connection.getHeaderFields().entrySet())
                    if (field.getKey() != null && field.getValue() != null && !field.getValue().isEmpty()) received.put(field.getKey().toLowerCase(java.util.Locale.ROOT), String.join(", ", field.getValue()));
                return new Exchange(status, input == null ? InputStream.nullInputStream() : input, received, connection::disconnect);
            } catch (IOException error) {
                connection.disconnect();
                // A stopped request never falls back to DirectHttps (open() retries only an unknown host).
                if (error instanceof UnknownHostException && stopped(stopped, cancel)) throw DirectHttps.stopped();
                throw error;
            }
        }
        /** Its stop ran, or its caller marked the request STOPPED (RequestAbort). */
        private static boolean stopped(AtomicBoolean stopped, AtomicReference<Runnable> cancel) {
            return stopped.get() || cancel != null && cancel.get() == RequestAbort.STOPPED;
        }
        static String read(InputStream input, int limit) throws IOException {
            try (InputStream stream = input) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) > 0) { if (bytes.size() + count > limit) throw new IOException("응답이 너무 커요."); bytes.write(buffer, 0, count); }
                return bytes.toString(StandardCharsets.UTF_8.name());
            }
        }
    }
}

