package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.GrokOAuth;
import dev.jun.gyeot.core.GrokVideo;
import dev.jun.gyeot.core.GrokWork;
import dev.jun.gyeot.core.Reasoning;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** One encrypted, device-owned subscription session shared by every Grok video request. */
final class GrokAccount implements GrokWorkClient.Session {
    private final Context context;
    private final SecretStore secrets;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object state = new Object(), refreshLock = new Object();
    private long epoch;
    private volatile Thread login;
    private volatile String accountId = "";
    volatile boolean signingIn;
    volatile String notice = "", userCode = "", verificationUrl = "";
    GrokAccount(Context context, SecretStore secrets, Runnable changed) {
        this.context = context.getApplicationContext(); this.secrets = secrets; this.changed = changed;
        try { JSONObject saved = load(); if (saved != null) accountId = saved.optString("accountId"); }
        catch (Exception e) { notice = "저장된 Grok 로그인을 읽지 못했어요. 다시 로그인해 주세요."; }
        try { GrokWork.detected(new org.json.JSONArray(prefs().getString("models", "[]"))); } catch (Exception ignored) {}
    }
    private android.content.SharedPreferences prefs() { return context.getSharedPreferences("grok-work", Context.MODE_PRIVATE); }
    boolean connected() { return !accountId.isBlank(); }
    public String accountId() { return accountId; }
    /** Local consent identity also changes when the same account reconnects. */
    String connectionEpoch() { synchronized(state) { return accountId + ":" + epoch; } }
    /** The Grok model chosen here, while xAI still lists it; else Grok 4.7. */
    String model() { String chosen = prefs().getString("model", ""); return GrokWork.listed(chosen) ? chosen : GrokWork.MODEL; }
    /** Grok 4.7 and the newer chat models xAI lists (read once a day at most, refreshModelsIfDue). */
    org.json.JSONArray models() { return GrokWork.models(); }
    void model(String value) { GrokWork.model(value); prefs().edit().putString("model", value).apply(); changed.run(); }
    private volatile long modelsTried;
    /**
     * Reads xAI's model list at most once a day (ModelWatch.due) on a thread of its own, so a newer Grok shows up in every
     * picker without an app update. Quiet: a failed read keeps the list there was and tries again after ten minutes at the
     * earliest.
     */
    void refreshModelsIfDue() {
        long now = System.currentTimeMillis();
        if (!connected() || !dev.jun.gyeot.core.ModelWatch.due(prefs().getLong("modelsAt", 0), now) || now - modelsTried < 10 * 60_000L) return;
        modelsTried = now;
        Thread read = new Thread(() -> {
            try {
                String expected = accountId; if (expected.isBlank()) return;
                String token = session(expected, null).getString("accessToken");
                JSONObject body;
                try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", GrokWork.MODELS, Map.of("Authorization", "Bearer " + token, "Accept", "application/json"), null, 20_000, null)) {
                    if (response.status >= 300) return;
                    body = new JSONObject(OpenAiAccount.Http.read(response.body, 4 * 1024 * 1024));
                }
                org.json.JSONArray found = dev.jun.gyeot.core.ModelWatch.grok(body, GrokWork.MODEL, GrokWork.NAME);
                prefs().edit().putString("models", found.toString()).putLong("modelsAt", System.currentTimeMillis()).apply();
                GrokWork.detected(found); main.post(changed);
            } catch (Exception quiet) { /* the saved list stays */ }
        }, "gyeot-grok-models");
        read.setDaemon(true); read.start();
    }
    Reasoning.Profile profile(String model) { GrokWork.model(model); return GrokWork.profile(); }
    String effort(String model) { return profile(model).normalize(context.getSharedPreferences("grok-work", Context.MODE_PRIVATE).getString("effort", "")); }
    void effort(String model, String value) {
        if (value == null || !value.isEmpty() && !profile(model).efforts.contains(value)) throw new IllegalArgumentException("Grok의 생각 깊이를 다시 골라 주세요.");
        context.getSharedPreferences("grok-work", Context.MODE_PRIVATE).edit().putString("effort", value).apply(); changed.run();
    }
    @Override public void check(String expectedAccount) {
        if (expectedAccount == null || expectedAccount.isBlank() || !expectedAccount.equals(accountId))
            throw new IllegalStateException("Grok 연결이 해제되거나 바뀌었어요. 현재 계정을 확인한 뒤 다시 보내 주세요.");
    }
    @Override public OpenAiAccount.Http.Exchange responses(JSONObject body, String expectedAccount, AtomicReference<Runnable> cancel, Runnable guard) throws Exception {
        GrokWork.model(body.optString("model")); String rejected = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            guard.run(); check(expectedAccount);
            JSONObject credentials = session(expectedAccount, rejected); guard.run(); check(expectedAccount);
            String token = credentials.getString("accessToken");
            OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", GrokWork.RESPONSES,
                Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json", "Accept", "text/event-stream"),
                body.toString().getBytes(StandardCharsets.UTF_8), 300_000, cancel);
            if (response.status != 401 || attempt > 0) return response;
            response.close(); cancel.set(null); rejected = token;
        }
        throw new IllegalStateException(GrokWork.error(401));
    }
    /**
     * The subscription's token for a spoken conversation (VoiceChat): refreshed when near its end, or when rejected is the
     * one the server just turned down. Blocking: never on the main thread.
     */
    String voiceToken(String rejected) throws Exception {
        String expected = accountId;
        if (expected.isBlank()) throw new IllegalStateException("설정 → Grok에서 구독 계정으로 로그인해 주세요.");
        return session(expected, rejected).getString("accessToken");
    }
    private JSONObject load() throws Exception { String value = secrets.get(SecretStore.GROK); return value.isBlank() ? null : new JSONObject(value); }
    private void notice(String value) { notice = value; changed.run(); }
    void signIn() {
        final long generation;
        // The first notice is set before the attempt starts, so it can never overwrite that attempt's own fast failure.
        synchronized (state) { cancelSignIn(); generation = ++epoch; signingIn = true; notice = "Grok 구독 계정 로그인 페이지를 준비하고 있어요."; login = new Thread(() -> login(generation), "gyeot-grok-login"); login.setDaemon(true); login.start(); }
        changed.run();
    }
    private boolean current(long generation) { synchronized (state) { return generation == epoch && signingIn; } }
    private void login(long generation) {
        try {
            OpenAiAccount.Http.Response discovery = OpenAiAccount.Http.send("GET", GrokOAuth.DISCOVERY, "application/json", null, null, 20_000);
            if (discovery.status != 200) throw new IllegalStateException(GrokOAuth.error(discovery.status));
            String endpoint = GrokOAuth.endpoint(discovery.json().optString("token_endpoint"));
            OpenAiAccount.Http.Response start = form(GrokOAuth.DEVICE, GrokOAuth.deviceForm());
            if (start.status != 200) throw new IllegalStateException(GrokOAuth.error(start.status));
            JSONObject device = GrokOAuth.device(start.json(), System.currentTimeMillis());
            synchronized (state) {
                if (!current(generation)) return;
                userCode = device.getString("userCode"); verificationUrl = device.getString("url");
            }
            notice("브라우저에서 구독 계정으로 로그인하고 연결을 승인해 주세요.");
            main.post(() -> { if (current(generation)) openVerification(); });
            long interval = device.getLong("interval") * 1000;
            while (current(generation) && System.currentTimeMillis() < device.getLong("expiresAt")) {
                Thread.sleep(interval); if (!current(generation)) return;
                if (System.currentTimeMillis() >= device.getLong("expiresAt")) break;
                OpenAiAccount.Http.Response response = form(endpoint, GrokOAuth.pollForm(device.getString("deviceCode")));
                JSONObject reply = response.json();
                if (response.status == 200) {
                    JSONObject session = GrokOAuth.tokens(reply, null, endpoint, System.currentTimeMillis());
                    synchronized (state) {
                        if (!current(generation)) return;
                        secrets.put(SecretStore.GROK, session.toString()); accountId = session.getString("accountId");
                    }
                    notice("Grok 구독 계정을 연결했어요. 메인 대화·작업창의 Grok 4.7 대화와 영상 만들기에서 써요."); return;
                }
                String error = reply.optString("error");
                if ("authorization_pending".equals(error)) continue;
                if ("slow_down".equals(error)) { interval += 5000; continue; }
                if ("access_denied".equals(error)) throw new IllegalStateException("Grok 연결 승인이 취소됐어요.");
                if ("expired_token".equals(error)) break;
                throw new IllegalStateException(GrokOAuth.error(response.status));
            }
            if (current(generation)) notice("로그인 코드가 만료됐어요. Grok 로그인을 다시 눌러 주세요.");
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (Exception e) { if (current(generation)) notice(e instanceof IllegalStateException || e instanceof IllegalArgumentException ? e.getMessage() : "Grok 로그인에 연결하지 못했어요. 네트워크를 확인한 뒤 다시 시도해 주세요."); }
        finally {
            synchronized (state) { if (generation == epoch) { login = null; signingIn = false; userCode = ""; verificationUrl = ""; } }
            changed.run();
        }
    }
    void openVerification() {
        if (verificationUrl.isBlank()) return;
        try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GrokOAuth.endpoint(verificationUrl))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { notice("브라우저를 열지 못했어요. 기본 브라우저를 확인하고 ‘로그인 페이지 열기’를 눌러 주세요."); }
    }
    void cancelSignIn() {
        // The attempt's own instructions go with it; an earlier attempt's error stays.
        synchronized (state) { ++epoch; if (login != null) { login.interrupt(); notice = ""; } login = null; signingIn = false; userCode = ""; verificationUrl = ""; }
        changed.run();
    }
    void disconnect() {
        synchronized (state) {
            cancelSignIn(); accountId = "";
            try { secrets.put(SecretStore.GROK, ""); notice = "이 기기의 Grok 연결을 해제했어요."; }
            catch (Exception e) { notice = "Grok 연결을 지우지 못했어요. 다시 시도해 주세요."; }
        }
        changed.run();
    }
    private OpenAiAccount.Http.Response form(String endpoint, String body) throws Exception {
        return OpenAiAccount.Http.send("POST", GrokOAuth.endpoint(endpoint), "application/x-www-form-urlencoded", body, null, 25_000);
    }
    private JSONObject session(String expectedAccount, String rejected) throws Exception {
        synchronized (refreshLock) {
            final JSONObject saved; final long generation;
            synchronized (state) {
                check(expectedAccount);
                saved = load(); generation = epoch;
            }
            if (saved == null) throw new IllegalStateException("설정 → Grok에서 구독 계정으로 로그인해 주세요.");
            if (saved.optLong("expiresAt") > System.currentTimeMillis() + 120_000 && (rejected == null || !rejected.equals(saved.optString("accessToken")))) return saved;
            String endpoint = GrokOAuth.endpoint(saved.getString("tokenEndpoint"));
            OpenAiAccount.Http.Response response = form(endpoint, GrokOAuth.refreshForm(saved.getString("refreshToken")));
            if (response.status != 200) throw new IllegalStateException(GrokOAuth.error(response.status));
            JSONObject next = GrokOAuth.tokens(response.json(), saved, endpoint, System.currentTimeMillis());
            synchronized (state) {
                if (generation != epoch || !expectedAccount.equals(accountId)) throw new IllegalStateException("Grok 연결이 변경되어 이전 요청을 중지했어요.");
                secrets.put(SecretStore.GROK, next.toString());
            }
            return next;
        }
    }
    /**
     * The subscription's weekly pool, as the Grok Build CLI's /usage reads it (core/PlanUsage.GROK): blocking, never on the
     * main thread. A rejected token is refreshed once; any other refusal is an IOException naming its status.
     */
    dev.jun.gyeot.core.Limits usage(AtomicReference<Runnable> cancel) throws Exception {
        String expectedAccount = accountId, rejected = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            JSONObject credentials = session(expectedAccount, rejected);
            String token = credentials.getString("accessToken");
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", dev.jun.gyeot.core.PlanUsage.GROK,
                    dev.jun.gyeot.core.PlanUsage.grokHeaders(token), null, 20_000, cancel)) {
                String text = OpenAiAccount.Http.read(response.body, 256 * 1024);
                if (response.status == 401 && attempt == 0) { rejected = token; continue; }
                if (response.status != 200) throw new java.io.IOException(dev.jun.gyeot.core.PlanUsage.failure(response.status));
                check(expectedAccount);
                return dev.jun.gyeot.core.PlanUsage.grok(new JSONObject(text), System.currentTimeMillis());
            } finally { if (cancel != null) cancel.set(null); }
        }
        throw new java.io.IOException(dev.jun.gyeot.core.PlanUsage.failure(401));
    }
    OpenAiAccount.Http.Response video(String method, String url, JSONObject body, String expectedAccount, AtomicReference<Runnable> cancel, Runnable guard) throws Exception {
        if (!(url.equals(GrokVideo.BASE + "/generations") && "POST".equals(method))
            && !(url.startsWith(GrokVideo.BASE + "/") && "GET".equals(method) && url.substring((GrokVideo.BASE + "/").length()).matches("[A-Za-z0-9_-]{1,180}")))
            throw new IllegalArgumentException("Grok 영상 요청 주소가 올바르지 않아요.");
        String rejected = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            guard.run(); JSONObject credentials = session(expectedAccount, rejected); guard.run();
            if (!expectedAccount.equals(accountId)) throw new IllegalStateException("Grok 연결이 변경됐어요.");
            String token = credentials.getString("accessToken");
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open(method, url,
                Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json", "Accept", "application/json"),
                body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), 60_000, cancel)) {
                String text = OpenAiAccount.Http.read(response.body, 1024 * 1024);
                if (response.status == 401 && attempt == 0) { rejected = token; continue; }
                return new OpenAiAccount.Http.Response(response.status, text);
            } finally { cancel.set(null); }
        }
        throw new IllegalStateException(GrokOAuth.error(401));
    }
}
