package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.CodexImages;
import dev.jun.gyeot.core.Net;
import dev.jun.gyeot.core.OpenAiLoopback;
import dev.jun.gyeot.core.OpenAiProtocol;
import java.net.BindException;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** A device-owned Codex OAuth session for images. Never shares or replaces the text/phone connection's tokens. */
final class CodexImageAccount {
    private final Context context;
    private final SecretStore secrets;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object loginLock = new Object(), credentials = new Object();
    private volatile Thread login;
    private volatile OpenAiLoopback loopback;
    private volatile JSONObject described;
    private long connectionEpoch;
    volatile boolean signingIn;
    volatile String notice = "";

    CodexImageAccount(Context context, SecretStore secrets, Runnable changed) {
        this.context = context.getApplicationContext(); this.secrets = secrets; this.changed = changed;
    }
    boolean connected() { return secrets.has(SecretStore.CODEX_IMAGES); }
    JSONObject describe() {
        JSONObject cached = described;
        if (cached != null) return cached;
        try {
            JSONObject stored = load();
            return described = stored == null ? obj("connected", false) : obj("connected", true, "label", stored.optString("email"));
        } catch (Exception e) { return obj("connected", false, "error", "이미지용 Codex 연결을 읽지 못했어요. 다시 로그인해 주세요."); }
    }
    private JSONObject load() throws Exception {
        String value = secrets.get(SecretStore.CODEX_IMAGES);
        return value.isBlank() ? null : new JSONObject(value);
    }
    private void save(JSONObject value) throws Exception { secrets.put(SecretStore.CODEX_IMAGES, value.toString()); described = null; }
    private void notice(String text) { notice = text; changed.run(); }

    void signIn() {
        Thread attempt;
        synchronized (loginLock) {
            cancelSignIn(); connectionEpoch++; signingIn = true;
            attempt = new Thread(this::runSignIn, "gyeot-codex-image-login"); attempt.setDaemon(true); login = attempt;
        }
        notice("브라우저에서 이미지 생성용 Codex 로그인을 마쳐 주세요."); attempt.start();
    }
    private void runSignIn() {
        Thread self = Thread.currentThread();
        try (OpenAiLoopback server = new OpenAiLoopback(1455, "localhost")) {
            synchronized (loginLock) { if (login != self) return; loopback = server; }
            String state = OpenAiProtocol.randomValue(), nonce = OpenAiProtocol.randomValue(), verifier = OpenAiProtocol.randomValue();
            String url = CodexImages.authorize(state, nonce, verifier);
            main.post(() -> {
                if (login != self) return;
                try { context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { cancelSignIn(); notice("브라우저를 열지 못했어요. 기본 브라우저를 확인해 주세요."); }
            });
            OpenAiProtocol.Callback callback = OpenAiProtocol.callback(server.await(state, 5 * 60_000L), CodexImages.CLIENT_ID);
            OpenAiAccount.Http.Response response = OpenAiAccount.Http.send("POST", CodexImages.TOKEN, "application/x-www-form-urlencoded",
                CodexImages.exchange(callback.code, verifier), null, 30_000);
            if (response.status >= 300) throw new IllegalStateException("Codex 로그인을 마치지 못했어요 (" + response.status + "). 다시 시도해 주세요.");
            JSONObject session = CodexImages.tokens(response.json(), null, nonce, System.currentTimeMillis());
            // A cancelled or superseded attempt must never replace a newer session.
            synchronized (loginLock) {
                if (login != self) return;
                synchronized (credentials) { save(session); }
                notice("이미지용 Codex를 연결했어요. 작업창에서 그림을 요청해 보세요.");
            }
        } catch (Exception e) {
            if (login == self) notice(e instanceof BindException ? "다른 로그인 창이 열려 있어요. 닫은 뒤 Codex 로그인을 다시 눌러 주세요." : Net.explain(e));
        } finally {
            synchronized (loginLock) { if (login == self) { login = null; loopback = null; signingIn = false; } }
            changed.run();
        }
    }
    void cancelSignIn() {
        synchronized (loginLock) {
            if (loopback != null) loopback.close();
            // The attempt's own "finish in the browser" notice goes with it; an earlier attempt's error stays.
            if (login != null) { login.interrupt(); notice = ""; }
            login = null; loopback = null; signingIn = false;
        }
    }
    void disconnect() {
        final long epoch;
        synchronized (loginLock) { cancelSignIn(); epoch = ++connectionEpoch; }
        // Refresh can own the credential lock during I/O; never make the main thread wait for it.
        Thread clear = new Thread(() -> {
            try {
                synchronized (loginLock) {
                    if (epoch != connectionEpoch) return;
                    synchronized (credentials) { secrets.put(SecretStore.CODEX_IMAGES, ""); described = null; }
                    notice("이 기기의 이미지용 Codex 연결을 해제했어요.");
                }
            }
            catch (Exception e) { notice("Codex 연결을 지우지 못했어요. 다시 시도해 주세요."); }
        }, "gyeot-codex-image-disconnect");
        clear.setDaemon(true); clear.start();
    }
    private JSONObject session() throws Exception {
        synchronized (credentials) {
            JSONObject stored = load();
            if (stored == null) throw new IllegalStateException("설정 → ChatGPT에서 ‘이미지용 Codex로 로그인’을 먼저 눌러 주세요.");
            return stored.optLong("expiresAt") <= System.currentTimeMillis() + 60_000 ? refreshLocked(stored) : stored;
        }
    }
    /**
     * The Codex sign-in's token for a spoken conversation with GPT (VoiceChat): the ChatGPT plan's Realtime access, as the
     * Codex CLI's own login gives it. Refreshed when near its end, or when rejected is the one just turned down. Blocking.
     */
    String voiceToken(String rejected) throws Exception { return (rejected == null ? session() : refresh(rejected)).getString("accessToken"); }
    /** The ChatGPT account the Codex sign-in belongs to (chatgpt-account-id), for a GPT-Live call. Blocking. */
    String voiceAccount() throws Exception { return session().optString("accountId"); }
    private JSONObject refresh(String rejected) throws Exception {
        synchronized (credentials) {
            JSONObject stored = load();
            if (stored == null) throw new IllegalStateException("이미지용 Codex 연결이 해제됐어요.");
            return !rejected.equals(stored.optString("accessToken")) ? stored : refreshLocked(stored);
        }
    }
    private JSONObject refreshLocked(JSONObject stored) throws Exception {
        OpenAiAccount.Http.Response response = OpenAiAccount.Http.send("POST", CodexImages.TOKEN, "application/x-www-form-urlencoded",
            CodexImages.refresh(stored.getString("refreshToken")), null, 30_000);
        if (response.status >= 300) {
            notice("이미지용 Codex 로그인을 갱신하지 못했어요. 다시 로그인해 주세요.");
            throw new IllegalStateException(notice);
        }
        JSONObject next = CodexImages.tokens(response.json(), stored, null, System.currentTimeMillis());
        save(next); return next;
    }
    /**
     * The plan's Codex limits, as Codex's /status reads them (core/PlanUsage.CODEX): blocking, never on the main thread.
     * A rejected token is refreshed once; any other refusal is an IOException naming its status.
     */
    dev.jun.gyeot.core.Limits usage(AtomicReference<Runnable> cancel) throws Exception {
        String rejected = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            JSONObject session = rejected == null ? session() : refresh(rejected);
            String token = session.getString("accessToken");
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", dev.jun.gyeot.core.PlanUsage.CODEX,
                    dev.jun.gyeot.core.PlanUsage.codexHeaders(token, session.getString("accountId")), null, 20_000, cancel)) {
                String text = OpenAiAccount.Http.read(response.body, 256 * 1024);
                if (response.status == 401 && attempt == 0) { rejected = token; continue; }
                if (response.status != 200) throw new java.io.IOException(dev.jun.gyeot.core.PlanUsage.failure(response.status));
                return dev.jun.gyeot.core.PlanUsage.codex(new JSONObject(text), System.currentTimeMillis());
            } finally { if (cancel != null) cancel.set(null); }
        }
        throw new java.io.IOException(dev.jun.gyeot.core.PlanUsage.failure(401));
    }
    /** The usage ledger's connection for this sign-in: 16 hex characters of a SHA-256 over its ChatGPT account, "" without one. */
    String usageIdentity() {
        try {
            JSONObject stored; synchronized (credentials) { stored = load(); }
            String account = stored == null ? "" : stored.optString("accountId");
            if (account.isBlank()) return "";
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(("gyeot-usage\ncodex\n" + account).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(); for (int i = 0; i < 8; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 255));
            return hex.toString();
        } catch (Exception unreadable) { return ""; }
    }
    /** A GPT Image 2.6 request (GIF, SVG) with its own body; the reply's status, JSON and raw text come back unread. */
    CodexImages.Reply send(String path, JSONObject body, AtomicReference<Runnable> cancel, Runnable guard, StringBuilder raw) throws Exception {
        try {
            return CodexImages.send(new CodexImages.Session() {
                @Override public JSONObject get() throws Exception { return session(); }
                @Override public JSONObject refresh(String rejected) throws Exception { return CodexImageAccount.this.refresh(rejected); }
            }, (url, headers, bytes) -> {
                guard.run();
                try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", url, headers, bytes, 300_000, cancel)) {
                    String text = OpenAiAccount.Http.read(response.body, response.status >= 300 ? 64 * 1024 : 60 * 1024 * 1024);
                    raw.setLength(0); raw.append(text);
                    JSONObject json; try { json = new JSONObject(text); } catch (Exception e) { json = obj(); }
                    return new CodexImages.Reply(response.status, json);
                }
            }, path, body, guard);
        } finally { if (cancel != null) cancel.set(null); }
    }
    JSONObject draw(JSONObject args, JSONArray sources, AtomicReference<Runnable> cancel, Runnable guard) throws Exception {
        try {
            return CodexImages.draw(new CodexImages.Session() {
                @Override public JSONObject get() throws Exception { return session(); }
                @Override public JSONObject refresh(String rejected) throws Exception { return CodexImageAccount.this.refresh(rejected); }
            }, (url, headers, body) -> {
                guard.run();
                try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", url, headers, body, 300_000, cancel)) {
                    String text = OpenAiAccount.Http.read(response.body, response.status >= 300 ? 64 * 1024 : 60 * 1024 * 1024);
                    JSONObject json; try { json = new JSONObject(text); } catch (Exception e) { json = obj(); }
                    return new CodexImages.Reply(response.status, json);
                }
            }, args, sources, guard);
        } finally { if (cancel != null) cancel.set(null); }
    }
}
