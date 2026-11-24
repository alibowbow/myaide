package dev.jun.gyeot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import dev.jun.gyeot.core.Gmail;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;

/**
 * The user's own Gmail (준: "지메일 읽기, 쓰기 붙여줘"), connected like Google Drive: Google's sign-in sheet on the phone
 * (pick the account, allow) with Gmail's scopes (Gmail.SCOPES: read, drafts and sending), short-lived tokens from Google
 * Play services, no password or refresh token kept. The same Google Cloud project as Drive, with the Gmail API turned on.
 */
final class GmailAccount {
    static final String CONNECTED = "gmailConnected", EMAIL = "gmailEmail", WORK = "gmailWork", SEND = "gmailSend";
    private final Context context; private final SharedPreferences settings; private final Runnable changed;
    GmailAccount(Context context, SharedPreferences settings, Runnable changed) { this.context = context.getApplicationContext(); this.settings = settings; this.changed = changed; }

    boolean connected() { return settings.getBoolean(CONNECTED, false); }
    String email() { return settings.getString(EMAIL, ""); }
    /** Connected and on for the main chat. */
    boolean forWork() { return connected() && settings.getBoolean(WORK, true); }
    /** Sending allowed at all (설정 → Gmail · 보내기 허용); off, everything becomes a draft. */
    boolean sends() { return settings.getBoolean(SEND, true); }

    private static com.google.android.gms.auth.api.identity.AuthorizationRequest request() {
        List<com.google.android.gms.common.api.Scope> scopes = new ArrayList<>();
        for (String scope : Gmail.SCOPES) scopes.add(new com.google.android.gms.common.api.Scope(scope));
        return com.google.android.gms.auth.api.identity.AuthorizationRequest.builder().setRequestedScopes(scopes).build();
    }
    /** From settings: Google's consent sheet when needed (its result comes back to onActivityResult), or a token at once. */
    static void begin(Activity activity, int requestCode, Consumer<String> granted, Consumer<String> failed) {
        try {
            com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(activity).authorize(request())
                .addOnSuccessListener(result -> {
                    if (result.hasResolution() && result.getPendingIntent() != null) {
                        try { activity.startIntentSenderForResult(result.getPendingIntent().getIntentSender(), requestCode, null, 0, 0, 0); }
                        catch (Exception e) { failed.accept("Google 로그인 화면을 열지 못했어요."); }
                    } else if (result.getAccessToken() != null) granted.accept(result.getAccessToken());
                    else failed.accept("Google이 Gmail 권한을 주지 않았어요.");
                })
                .addOnFailureListener(e -> failed.accept(explain(e)));
        } catch (RuntimeException e) { failed.accept(explain(e)); }
    }
    static String finish(Activity activity, Intent data) throws Exception {
        com.google.android.gms.auth.api.identity.AuthorizationResult result = com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data);
        if (result.getAccessToken() == null) throw new IllegalStateException("Google이 Gmail 권한을 주지 않았어요.");
        List<String> granted = result.getGrantedScopes();
        if (granted != null && !granted.isEmpty() && !granted.containsAll(Gmail.SCOPES)) throw new IllegalStateException("Gmail 권한 일부를 허용하지 않았어요. 다시 연결하면서 두 항목(읽기, 보내기·초안)을 모두 체크해 주세요.");
        return result.getAccessToken();
    }
    /** After the user allowed: the address, from Gmail itself (which also proves the Gmail API is on), is kept. */
    String remember(String token) throws Exception {
        Response r = exchange("GET", Gmail.API + "profile", token, null);
        if (r.status() >= 300) throw new IllegalStateException(explain(r.status(), r.body()));
        String email = new JSONObject(r.body()).optString("emailAddress");
        settings.edit().putBoolean(CONNECTED, true).putString(EMAIL, email).apply(); changed.run();
        return email;
    }
    void disconnect() {
        try {
            String token = token();
            exchange("POST", "https://oauth2.googleapis.com/revoke?token=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8), null, null);
            com.google.android.gms.auth.GoogleAuthUtil.clearToken(context, token);
        } catch (Exception ignored) {}
        settings.edit().remove(CONNECTED).remove(EMAIL).apply(); changed.run();
    }
    /** A token without any screen (never on the main thread). */
    private String token() throws Exception {
        com.google.android.gms.auth.api.identity.AuthorizationResult result;
        try { result = com.google.android.gms.tasks.Tasks.await(com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(context).authorize(request()), 30, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw new IllegalStateException(explain(e)); }
        if (result.hasResolution() || result.getAccessToken() == null) throw new IllegalStateException("Gmail 권한이 끝났어요. 설정 → 커넥터 → Gmail에서 다시 연결해 주세요.");
        return result.getAccessToken();
    }
    /** Gmail's JSON answer; a refused (401) token is dropped and the request tried once more. */
    JSONObject call(String method, String url, JSONObject body) throws Exception {
        if (!connected()) throw new IllegalStateException("Gmail이 연결되지 않았어요. 설정 → 커넥터 → Gmail에서 연결해 주세요.");
        String token = token();
        Response r = exchange(method, url, token, body);
        if (r.status() == 401) { try { com.google.android.gms.auth.GoogleAuthUtil.clearToken(context, token); } catch (Exception ignored) {} r = exchange(method, url, token(), body); }
        if (r.status() >= 300) throw new IllegalStateException(explain(r.status(), r.body()));
        return r.body().isBlank() ? new JSONObject() : new JSONObject(r.body());
    }
    record Response(int status, String body) {}
    private static Response exchange(String method, String address, String token, JSONObject body) throws Exception {
        if (!address.startsWith(Gmail.API) && !address.startsWith("https://oauth2.googleapis.com/revoke")) throw new IllegalArgumentException("Gmail 주소가 아니에요.");
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setRequestMethod(method); c.setConnectTimeout(30_000); c.setReadTimeout(60_000); c.setInstanceFollowRedirects(false);
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8"); c.setDoOutput(true); c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            } else if ("POST".equals(method)) { c.setDoOutput(true); c.setFixedLengthStreamingMode(0); c.getOutputStream().close(); }
            int status = c.getResponseCode();
            InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            if (stream != null) try (InputStream in = stream) { byte[] buffer = new byte[16384]; int n; while ((n = in.read(buffer)) > 0 && read.size() < 8 * 1024 * 1024) read.write(buffer, 0, n); }
            return new Response(status, read.toString(StandardCharsets.UTF_8));
        } finally { c.disconnect(); }
    }
    /** Gmail's refusals in words the user can act on. */
    static String explain(int status, String body) {
        String lower = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
        if (status == 403 && (lower.contains("has not been used") || lower.contains("is disabled") || lower.contains("accessnotconfigured")))
            return "Google Cloud 프로젝트에서 Gmail API가 꺼져 있어요. 설정 → 커넥터 → Gmail의 ‘Gmail API 켜기’를 눌러 켠 뒤 몇 분 뒤에 다시 해 주세요.";
        if (status == 403 && lower.contains("insufficient")) return "Gmail 권한이 모자라요. 설정 → 커넥터 → Gmail에서 다시 연결해 주세요.";
        if (status == 404) return "그 메일을 찾지 못했어요 (지워졌거나 id가 달라요).";
        if (status == 429) return "Gmail 요청이 너무 많아요. 잠시 뒤 다시 해 주세요.";
        if (status == 400 && lower.contains("invalid to header")) return "받는 사람 주소가 올바르지 않아요.";
        return "Gmail이 요청을 처리하지 못했어요 (HTTP " + status + ").";
    }
    static String explain(Throwable error) {
        Throwable cause = error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof com.google.android.gms.common.api.ApiException api) return switch (api.getStatusCode()) {
            case 10 -> "Google Cloud에 이 앱(패키지 이름·SHA-1)이 아직 등록되지 않았어요. Google Drive와 같은 등록 안내를 따라 한 뒤 다시 연결해 주세요.";
            case 7 -> "인터넷 연결을 확인해 주세요.";
            case 16, 12501 -> "Gmail 연결을 취소했어요.";
            default -> "Google 로그인에 실패했어요 (코드 " + api.getStatusCode() + ").";
        };
        return cause.getMessage() == null || cause.getMessage().isBlank() ? "Google 로그인에 실패했어요." : cause.getMessage();
    }
}
