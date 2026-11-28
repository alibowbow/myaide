package dev.jun.gyeot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import dev.jun.gyeot.core.GoogleDrive;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONObject;

/**
 * The user's own Google Drive, connected with Google's sign-in sheet on the phone (pick the account, allow). The app keeps
 * no password and no refresh token: Google Play services hands out a short-lived access token whenever a Work tool needs
 * one. It needs this app's package name and signing key registered once as an Android OAuth client in the user's Google
 * Cloud project; until then Google answers with DEVELOPER_ERROR, which the settings card explains.
 */
final class GoogleDriveAccount {
    static final String CONNECTED = "driveConnected", EMAIL = "driveEmail", WORK = "driveWork";
    /** Whether Google granted the full Drive scope, and whether the user wants Work to change existing files with it. */
    static final String FULL = "driveFull", WRITE = "driveWrite";
    static final String REVOKE = "https://oauth2.googleapis.com/revoke";

    /** A token and the scopes Google actually granted with it. */
    record Grant(String token, List<String> scopes) {
        boolean full() { return scopes != null && scopes.contains(GoogleDrive.FULL); }
    }
    /** Google's sign-in, replaced in tests. Full means the whole Drive (changes allowed); otherwise read + this app's files. */
    interface Authorizer {
        /** From settings: Google's consent sheet when needed (the result comes back to onActivityResult), or a grant at once. */
        void begin(Activity activity, int requestCode, boolean full, Consumer<Grant> granted, Consumer<String> failed);
        /** The grant in the consent sheet's result. */
        Grant finish(Activity activity, Intent data) throws Exception;
        /** A token without any screen, for Work tools (never on the main thread). */
        String token(Context context, boolean full) throws Exception;
        /** Drops a token Google refused, so the next one is fresh. */
        void forget(Context context, String token);
    }
    static volatile Authorizer authorizer = new PlayServices();

    /** Seam for tests: every HTTP exchange with Google. */
    interface Http { Response exchange(String method, String url, String token, String type, byte[] body, File upload, long limit, File save) throws Exception; }
    record Response(int status, String body, String location) {}
    static volatile Http http = GoogleDriveAccount::send;

    private final Context context; private final SharedPreferences settings; private final Runnable changed;
    GoogleDriveAccount(Context context, SharedPreferences settings, Runnable changed) { this.context = context.getApplicationContext(); this.settings = settings; this.changed = changed; }

    boolean connected() { return settings.getBoolean(CONNECTED, false); }
    String email() { return settings.getString(EMAIL, ""); }
    /** Connected and allowed for the Work room. */
    boolean forWork() { return connected() && settings.getBoolean(WORK, true); }
    /** Work may change, move, rename and trash existing files: the full scope was granted and the user turned changes on. */
    boolean writable() { return connected() && settings.getBoolean(FULL, false) && settings.getBoolean(WRITE, false); }
    /** Turns changes off here (the grant stays; tokens then ask only for read + this app's files). */
    void readOnly() { settings.edit().putBoolean(WRITE, false).apply(); changed.run(); }

    /**
     * After the user allowed: the account's address, from Drive itself (which also proves the Drive API is on), is kept, with
     * what Google granted. A full grant turns changes on, since only the 수정·정리 switch asks for it.
     */
    String remember(Grant grant) throws Exception {
        Response r = http.exchange("GET", GoogleDrive.API + "about?fields=" + java.net.URLEncoder.encode("user(emailAddress,displayName)", StandardCharsets.UTF_8), grant.token(), null, null, null, 64 * 1024, null);
        if (r.status() >= 300) throw new IllegalStateException(GoogleDrive.explain(r.status(), r.body()));
        JSONObject user = new JSONObject(r.body()).optJSONObject("user");
        String email = user == null ? "" : user.optString("emailAddress");
        SharedPreferences.Editor edit = settings.edit().putBoolean(CONNECTED, true).putString(EMAIL, email).putBoolean(FULL, grant.full());
        if (grant.full()) edit.putBoolean(WRITE, true);
        edit.apply(); changed.run();
        return email;
    }
    /** Forgets the connection here and asks Google to withdraw the grant (best effort; never on the main thread). */
    void disconnect() {
        try {
            String token = authorizer.token(context, settings.getBoolean(FULL, false));
            http.exchange("POST", REVOKE, null, "application/x-www-form-urlencoded", ("token=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8), null, 4096, null);
            authorizer.forget(context, token);
        } catch (Exception ignored) {}
        settings.edit().remove(CONNECTED).remove(EMAIL).remove(FULL).remove(WRITE).apply(); changed.run();
    }

    /** A Drive text answer (JSON or a file's text), at most {@code limit} bytes. */
    String get(String url, int limit) throws Exception { return call("GET", url, null, null, null, limit, null).body(); }
    /** A metadata request with a JSON body (create a folder, rename, move, trash): Drive's JSON answer. */
    JSONObject send(String method, String url, JSONObject body) throws Exception {
        return new JSONObject(call(method, url, "application/json; charset=UTF-8", body.toString().getBytes(StandardCharsets.UTF_8), null, 64 * 1024, null).body());
    }
    /** A file's bytes into {@code target}, refused beyond {@code limit} bytes. */
    void download(String url, File target, long limit) throws Exception { call("GET", url, null, null, null, limit, target); }
    /**
     * A new Drive file: a resumable session, then the bytes (text) or the file (media). The session address must be Google's
     * own upload endpoint, so the token never goes anywhere else. Returns Drive's {id, name, mimeType, size, webViewLink}.
     */
    JSONObject upload(JSONObject meta, String type, byte[] bytes, File file) throws Exception { return resumable("POST", GoogleDrive.uploadStart(), meta, type, bytes, file); }
    /** New content (and maybe a new name) for an existing file: Drive keeps the earlier version in its history. */
    JSONObject replace(String id, JSONObject meta, String type, byte[] bytes, File file) throws Exception { return resumable("PATCH", GoogleDrive.updateStart(id), meta, type, bytes, file); }
    private JSONObject resumable(String method, String start, JSONObject meta, String type, byte[] bytes, File file) throws Exception {
        Response session = call(method, start, "application/json; charset=UTF-8", meta.toString().getBytes(StandardCharsets.UTF_8), null, 64 * 1024, null);
        String location = session.location();
        if (location == null || !(location.startsWith(GoogleDrive.UPLOAD + "?") || location.startsWith(GoogleDrive.UPLOAD + "/"))) throw new IllegalStateException("Google Drive가 업로드 주소를 주지 않았어요.");
        return new JSONObject(call("PUT", location, type, bytes, file, 64 * 1024, null).body());
    }
    /** One request with a fresh token; a refused (401) token is dropped and the request tried once more. */
    private Response call(String method, String url, String type, byte[] body, File file, long limit, File save) throws Exception {
        if (!connected()) throw new IllegalStateException("Google Drive가 연결되지 않았어요. 설정 → 커넥터 → Google Drive에서 연결해 주세요.");
        boolean full = writable();
        String token = authorizer.token(context, full);
        Response r = http.exchange(method, url, token, type, body, file, limit, save);
        if (r.status() == 401) { authorizer.forget(context, token); token = authorizer.token(context, full); r = http.exchange(method, url, token, type, body, file, limit, save); }
        if (r.status() >= 300) throw new IllegalStateException(GoogleDrive.explain(r.status(), r.body()));
        return r;
    }
    private static Response send(String method, String address, String token, String type, byte[] body, File upload, long limit, File save) throws Exception {
        if (!address.startsWith("https://www.googleapis.com/") && !address.equals(REVOKE)) throw new IllegalArgumentException("Google 주소가 아니에요.");
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            // HttpURLConnection has no PATCH: Google's APIs take POST with the method override header instead.
            if ("PATCH".equals(method)) { c.setRequestMethod("POST"); c.setRequestProperty("X-HTTP-Method-Override", "PATCH"); }
            else c.setRequestMethod(method);
            c.setConnectTimeout(30_000); c.setReadTimeout(180_000); c.setInstanceFollowRedirects(false);
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            if (type != null) c.setRequestProperty("Content-Type", type);
            if (body != null || upload != null) {
                c.setDoOutput(true); c.setFixedLengthStreamingMode(body != null ? body.length : upload.length());
                try (OutputStream out = c.getOutputStream(); InputStream in = body != null ? new ByteArrayInputStream(body) : new FileInputStream(upload)) { in.transferTo(out); }
            }
            int status = c.getResponseCode();
            if (save != null && status < 300) {
                try (InputStream in = c.getInputStream(); OutputStream out = new java.io.FileOutputStream(save)) {
                    byte[] buffer = new byte[64 * 1024]; long total = 0; int n;
                    while ((n = in.read(buffer)) > 0) { total += n; if (total > limit) throw new IllegalStateException("파일이 너무 커요 (" + DriveWorkTools.size(limit) + "까지)."); out.write(buffer, 0, n); }
                }
                return new Response(status, "", c.getHeaderField("Location"));
            }
            InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream read = new ByteArrayOutputStream();
            if (stream != null) try (InputStream in = stream) {
                byte[] buffer = new byte[16384]; int n;
                while ((n = in.read(buffer)) > 0) { long room = limit - read.size(); if (n >= room) { read.write(buffer, 0, (int) Math.max(0, room)); break; } read.write(buffer, 0, n); }
            }
            return new Response(status, read.toString(StandardCharsets.UTF_8), c.getHeaderField("Location"));
        } finally { c.disconnect(); }
    }

    /** Google's sign-in errors in words the user can act on. */
    static String explain(Throwable error) {
        Throwable cause = error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof com.google.android.gms.common.api.ApiException api) return switch (api.getStatusCode()) {
            case 10 -> "Google Cloud에 이 앱(패키지 이름·SHA-1)이 아직 등록되지 않았어요. ‘Google Cloud 등록 안내’를 따라 한 뒤 몇 분 뒤에 다시 연결해 주세요.";
            case 7 -> "인터넷 연결을 확인해 주세요.";
            case 16, 12501 -> "Google Drive 연결을 취소했어요.";
            default -> "Google 로그인에 실패했어요 (코드 " + api.getStatusCode() + ").";
        };
        return cause.getMessage() == null || cause.getMessage().isBlank() ? "Google 로그인에 실패했어요." : cause.getMessage();
    }

    /** The SHA-1 of this app's signing certificate as Google Cloud asks for it (AA:BB:…), or "" when it cannot be read. */
    static String signingSha1(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
            if (info.signingInfo == null) return "";
            Signature[] signers = info.signingInfo.hasMultipleSigners() ? info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory();
            if (signers == null || signers.length == 0) return "";
            // With key rotation the newest certificate is last; it is the one the installed app is signed with now.
            return sha1(signers[signers.length - 1].toByteArray());
        } catch (Exception unreadable) { return ""; }
    }
    static String sha1(byte[] certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(certificate);
        StringBuilder out = new StringBuilder();
        for (byte b : digest) { if (out.length() > 0) out.append(':'); out.append(String.format(Locale.ROOT, "%02X", b)); }
        return out.toString();
    }

    /** Google Play services' Authorization API: Drive scopes only, no Google sign-in profile, no stored refresh token. */
    static final class PlayServices implements Authorizer {
        static com.google.android.gms.auth.api.identity.AuthorizationRequest request(boolean full) {
            List<com.google.android.gms.common.api.Scope> scopes = new ArrayList<>();
            for (String scope : GoogleDrive.scopes(full)) scopes.add(new com.google.android.gms.common.api.Scope(scope));
            return com.google.android.gms.auth.api.identity.AuthorizationRequest.builder().setRequestedScopes(scopes).build();
        }
        static Grant grant(com.google.android.gms.auth.api.identity.AuthorizationResult result) {
            List<String> scopes = result.getGrantedScopes();
            return new Grant(result.getAccessToken(), scopes == null ? List.of() : List.copyOf(scopes));
        }
        @Override public void begin(Activity activity, int requestCode, boolean full, Consumer<Grant> granted, Consumer<String> failed) {
            try {
                com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(activity).authorize(request(full))
                    .addOnSuccessListener(result -> {
                        if (result.hasResolution() && result.getPendingIntent() != null) {
                            try { activity.startIntentSenderForResult(result.getPendingIntent().getIntentSender(), requestCode, null, 0, 0, 0); }
                            catch (Exception e) { failed.accept("Google 로그인 화면을 열지 못했어요."); }
                        } else if (result.getAccessToken() != null) granted.accept(grant(result));
                        else failed.accept("Google이 Drive 권한을 주지 않았어요.");
                    })
                    .addOnFailureListener(e -> failed.accept(explain(e)));
            } catch (RuntimeException e) { failed.accept(explain(e)); }
        }
        @Override public Grant finish(Activity activity, Intent data) throws Exception {
            com.google.android.gms.auth.api.identity.AuthorizationResult result = com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data);
            if (result.getAccessToken() == null) throw new IllegalStateException("Google이 Drive 권한을 주지 않았어요.");
            return grant(result);
        }
        @Override public String token(Context context, boolean full) throws Exception {
            com.google.android.gms.auth.api.identity.AuthorizationResult result;
            try { result = com.google.android.gms.tasks.Tasks.await(com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(context).authorize(request(full)), 30, TimeUnit.SECONDS); }
            catch (ExecutionException e) { throw new IllegalStateException(explain(e)); }
            if (result.hasResolution() || result.getAccessToken() == null) throw new IllegalStateException("Google Drive 권한이 끝났어요. 설정 → 커넥터 → Google Drive에서 다시 연결해 주세요.");
            return result.getAccessToken();
        }
        @Override public void forget(Context context, String token) {
            try { com.google.android.gms.auth.GoogleAuthUtil.clearToken(context, token); } catch (Exception ignored) {}
        }
    }
}
