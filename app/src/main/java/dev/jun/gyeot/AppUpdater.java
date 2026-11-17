package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.Net;
import dev.jun.gyeot.core.UpdateCheck;
import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InterruptedIOException;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-update from the repository's GitHub releases: check, download, verify, then hand the APK to Android's own package
 * installer (UpdateFiles), where the user confirms. Nothing installs silently, and a build signed with a different key is
 * refused before the installer runs.
 */
final class AppUpdater {
    /** Test builds come often: the app looks again whenever it opens, at most this often (a token allows thousands of checks an hour). */
    private static final long CHECK_EVERY_MS = 30 * 60 * 1000L;
    private final Context context;
    private final SecretStore secrets;
    private final SharedPreferences settings;
    private final Runnable changed;
    private final ExecutorService network = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-update"); t.setDaemon(true); return t; });
    volatile String notice = "";
    volatile boolean busy;
    volatile UpdateCheck.Update available;
    /** The verified APK waiting for Android's installer, while the app was away when it finished downloading. */
    volatile File ready;

    AppUpdater(Context context, SecretStore secrets, SharedPreferences settings, Runnable changed) {
        this.context = context.getApplicationContext(); this.secrets = secrets; this.settings = settings; this.changed = changed;
        network.execute(this::sweep);
    }
    /** A read-only token lets the app see releases of a private repository; it is optional for a public one. */
    boolean hasToken() { return secrets.has(SecretStore.GITHUB); }
    void saveToken(String value) {
        String token = value == null ? "" : value.strip();
        if (!(token.startsWith("github_pat_") || token.startsWith("ghp_")) || token.contains(" ") || token.length() < 20) throw new IllegalArgumentException("GitHub 토큰 형식을 확인해 주세요 (github_pat_ 또는 ghp_로 시작).");
        network.execute(() -> { try { secrets.put(SecretStore.GITHUB, token); } catch (Exception e) { notice("토큰을 저장하지 못했어요."); return; } changed.run(); });
        check(false);
    }
    void clearToken() { network.execute(() -> { try { secrets.put(SecretStore.GITHUB, ""); } catch (Exception ignored) {} available = null; notice("GitHub 토큰을 지웠어요."); }); }
    private String token() { try { return secrets.get(SecretStore.GITHUB); } catch (Exception e) { return ""; } }
    void notice(String value) { notice = value; changed.run(); }
    String currentVersion() {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0)).versionName; } catch (Exception e) { return "0"; }
    }
    /** Quietly checks when the app opens, at most every half hour, so a new test build shows up the same day. */
    void checkIfDue() { if (System.currentTimeMillis() - settings.getLong("updateCheckedAt", 0) > CHECK_EVERY_MS) check(true); }
    /** Checks now unless it did within the last minute, e.g. when the update page opens. */
    void checkSoon() { if (System.currentTimeMillis() - settings.getLong("updateCheckedAt", 0) > 60_000L) check(false); }
    void check(boolean quiet) {
        if (busy) return;
        busy = true; if (!quiet) notice("업데이트를 확인하는 중…");
        network.execute(() -> {
            try {
                UpdateCheck.Update found = fetch(quiet);
                available = found;
                // One answer settles the page: a file downloaded for an older release is no longer
                // offered for install, and a quiet check that finds a release clears the "최신 버전이에요" it made wrong.
                File downloaded = ready;
                if (downloaded != null && !UpdateCheck.readyStillCurrent(downloaded.getName(), found)) ready = null;
                String next = UpdateCheck.noticeAfter(quiet, notice, found, "최신 버전이에요 (" + currentVersion() + ").");
                if (!next.equals(notice)) notice(next);
            } catch (Checked reason) { if (!quiet) notice(reason.getMessage());
            } catch (Exception error) {
                if (!quiet) notice("업데이트를 확인하지 못했어요. " + Net.explain(error));
            } finally { busy = false; changed.run(); }
        });
    }
    /** A check that got an answer the user should read as it is (no access, a limit), rather than a network failure. */
    private static final class Checked extends Exception { Checked(String message) { super(message); } }
    /** The newest release above the installed version, read from GitHub now; null when up to date. Network thread only. */
    private UpdateCheck.Update fetch(boolean quiet) throws Exception {
        String token = token();
        HttpURLConnection connection = open(UpdateCheck.RELEASES_API, 20_000, token);
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json"); connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int status = connection.getResponseCode();
            settings.edit().putLong("updateCheckedAt", System.currentTimeMillis()).apply();
            if (status == 404) throw new Checked(token.isEmpty() ? "저장소가 비공개라 확인할 수 없어요. 아래 고급의 ‘업데이트 확인용 GitHub 토큰’에 읽기 전용 토큰을 넣거나, 릴리스 페이지에서 받아 주세요."
                : "이 토큰으로는 저장소를 볼 수 없어요. 토큰의 저장소 접근(alibowbow/myaide)과 Contents 읽기 권한을 확인해 주세요.");
            if (status == 401) throw new Checked("GitHub 토큰이 만료됐거나 잘못됐어요. 새 토큰을 넣어 주세요.");
            if (status == 403 || status == 429) throw new Checked("GitHub 요청이 잠시 제한됐어요. 조금 뒤 다시 확인해 주세요.");
            if (status != 200) throw new IOException("GitHub 응답 " + status);
            String body; try (InputStream input = connection.getInputStream()) { body = OpenAiAccount.Http.read(input, 2 * 1024 * 1024); }
            return UpdateCheck.newest(new JSONArray(body), currentVersion());
        } finally { connection.disconnect(); }
    }
    /**
     * Downloads and verifies the available build. The verified file is kept as ready and handed to onReady (on the network
     * thread), which opens Android's own package installer with it, as file managers and browsers do; the user confirms there.
     */
    void install(java.util.function.Consumer<File> onReady) {
        UpdateCheck.Update known = available;
        if (known == null || busy) return;
        busy = true; ready = null; notice("새 버전 " + known.version + "을 내려받는 중…");
        network.execute(() -> {
            File folder = UpdateFiles.folder(context);
            File apk = new File(folder, "gyeot-" + known.version + ".apk");
            try {
                // The release is read again first: a test build may have been rebuilt (a new file and SHA-256) or replaced by a newer one since the check.
                UpdateCheck.Update update;
                try { update = fetch(false); } catch (Exception stale) { update = null; }
                if (update == null) update = known;
                available = update;
                apk = new File(folder, "gyeot-" + update.version + ".apk");
                if (update.sha256.isEmpty()) throw new IllegalStateException("릴리스에 SHA-256 값이 없어 설치하지 않았어요. 릴리스 페이지에서 직접 받아 주세요.");
                folder.mkdirs();
                // Installed sourceDir is the immutable base. Never clean an active base or another pending update here.
                apk = File.createTempFile("update-", ".partial", folder);
                notice("새 버전 " + update.version + "을 내려받는 중…");
                String digest = tryDelta(update, apk) ? update.sha256 : download(update, apk);
                if (!digest.equalsIgnoreCase(update.sha256)) {
                    // Rebuilt while downloading: read the release once more and take the file it lists now.
                    UpdateCheck.Update again = fetch(false);
                    if (again == null || again.sha256.equalsIgnoreCase(update.sha256)) throw new IllegalStateException("내려받은 파일이 릴리스의 SHA-256과 달라 설치하지 않았어요.");
                    update = again; available = again; apk.delete(); apk = File.createTempFile("update-", ".partial", folder);
                    if (!download(again, apk).equalsIgnoreCase(again.sha256)) throw new IllegalStateException("내려받은 파일이 릴리스의 SHA-256과 달라 설치하지 않았어요.");
                }
                verifyPackage(apk, update.version);
                File complete = new File(folder, UpdateCheck.readyName(update.version));
                if (!apk.renameTo(complete)) throw new IOException("검증한 설치 파일을 저장하지 못했어요.");
                apk = complete;
                ready = apk;
                notice("내려받고 확인했어요 · Android 설치 화면에서 ‘업데이트’를 눌러 주세요.");
                onReady.accept(apk);
            } catch (Exception error) {
                apk.delete(); ready = null;
                notice(error instanceof Checked ? error.getMessage() : Net.explain(error));
            } finally { busy = false; changed.run(); }
        });
    }
    /** Fail closed on every patch issue and fall back to the independently verified full APK. */
    private boolean tryDelta(UpdateCheck.Update update, File output) throws Exception {
        if (update.deltas.isEmpty()) return false;
        File base = new File(context.getApplicationInfo().sourceDir);
        File patch = null;
        try {
            // Split installs and universal APKs use the existing full-download path.
            if (context.getApplicationInfo().splitSourceDirs != null && context.getApplicationInfo().splitSourceDirs.length != 0) return false;
            if (!arm64Only(base)) return false;
            String baseHash = hashFile(base);
            UpdateCheck.Delta selected = null;
            for (UpdateCheck.Delta delta : update.deltas) if (delta.baseSha256.equals(baseHash)) { selected = delta; break; }
            if (selected == null) return false;
            if (output.getParentFile().getUsableSpace() < update.size + selected.size + 16L * 1024 * 1024) return false;
            patch = File.createTempFile("delta-", ".partial", output.getParentFile());
            notice("변경된 부분만 내려받는 중… " + Math.max(1, selected.size / 1024 / 1024) + "MB");
            String digest = downloadAsset(selected.url, selected.apiUrl, selected.size, patch, "변경된 부분을 내려받는 중…");
            if (!selected.sha256.equals(digest)) throw new IOException("차등 파일 검증 실패");
            notice("새 설치 파일을 복원하고 확인하는 중…");
            if (!output.delete()) throw new IOException("차등 임시 파일을 준비하지 못했어요.");
            ApkDelta.apply(base, patch, output, baseHash, update.sha256, update.size, () -> Thread.currentThread().isInterrupted());
            if (!arm64Only(output)) throw new IOException("차등 APK 아키텍처 불일치");
            verifyPackage(output, update.version);
            return true;
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (Exception invalidPatch) {
            output.delete();
            notice("차등 업데이트를 사용할 수 없어 전체 설치 파일로 전환해요.");
            return false;
        } finally { if (patch != null) patch.delete(); }
    }
    private static boolean arm64Only(File apk) throws IOException {
        boolean engine = false;
        try (ZipFile zip = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                if (++count > 100_000) return false;
                String name = entries.nextElement().getName();
                if (name.startsWith("lib/") && !name.startsWith("lib/arm64-v8a/")) return false;
                if (name.equals("lib/arm64-v8a/libxul.so")) engine = true;
            }
        }
        return engine;
    }
    private static String hashFile(File file) throws Exception {
        if (file.length() <= 0 || file.length() > UpdateCheck.MAX_APK_BYTES) throw new IOException("APK 크기 제한");
        long deadline = System.nanoTime() + 300_000_000_000L;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("업데이트를 취소했어요.");
                if (System.nanoTime() > deadline) throw new IOException("APK 확인 시간이 초과됐어요.");
                sha.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(64); for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }
    /** Update files left behind by an earlier version (a finished install ends this process before it can tidy up). */
    private void sweep() {
        File[] files = UpdateFiles.folder(context).listFiles();
        if (files != null) for (File file : files) if (System.currentTimeMillis() - file.lastModified() > 60 * 60 * 1000L) file.delete();
    }
    private static HttpURLConnection open(String url, int timeoutMs, String token) throws IOException {
        URI target = URI.create(url);
        if (!"https".equals(target.getScheme()) || !UpdateCheck.allowedHost(target.getHost()) || target.getUserInfo() != null || target.getPort() != -1) throw new IOException("허용되지 않은 다운로드 주소예요.");
        HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(timeoutMs);
        connection.setRequestProperty("User-Agent", "gyeot-android-updater");
        if (!token.isEmpty() && UpdateCheck.sendsToken(target.getHost())) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }
    /** Follows only https redirects to GitHub's asset hosts; checks the size while hashing. Returns the SHA-256 hex digest. */
    private String download(UpdateCheck.Update update, File target) throws Exception {
        return downloadAsset(update.url, update.apiUrl, update.size, target, "새 버전 " + update.version + "을 내려받는 중…");
    }
    private String downloadAsset(String publicUrl, String apiUrl, long size, File target, String label) throws Exception {
        if (size <= 0 || size > UpdateCheck.MAX_APK_BYTES) throw new IOException("다운로드 크기 제한");
        if (!UpdateCheck.safeAssetUrl(publicUrl, false) || (!apiUrl.isEmpty() && !UpdateCheck.safeAssetUrl(apiUrl, true))) throw new IOException("허용되지 않은 자산 주소예요.");
        long deadline = System.nanoTime() + 300_000_000_000L;
        // A private repository's asset is read through the API with the token; the signed storage link it redirects to gets no token.
        String token = token();
        boolean api = !token.isEmpty() && !apiUrl.isEmpty();
        String url = api ? apiUrl : publicUrl;
        for (int hop = 0; hop < 5; hop++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("업데이트를 취소했어요.");
            if (System.nanoTime() > deadline) throw new IOException("다운로드 시간이 초과됐어요.");
            HttpURLConnection connection = open(url, 20_000, token);
            if (api) connection.setRequestProperty("Accept", "application/octet-stream");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    URI next = URI.create(url).resolve(connection.getHeaderField("Location"));
                    if (!"https".equals(next.getScheme()) || !UpdateCheck.allowedHost(next.getHost())) throw new IOException("허용되지 않은 다운로드 주소예요.");
                    url = next.toString(); continue;
                }
                if (status != 200) throw new IOException("다운로드에 실패했어요 (" + status + ").");
                MessageDigest sha = MessageDigest.getInstance("SHA-256"); long total = 0; int lastPercent = -1;
                try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024]; int count;
                    while ((count = input.read(buffer)) > 0) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("업데이트를 취소했어요.");
                        if (System.nanoTime() > deadline) throw new IOException("다운로드 시간이 초과됐어요.");
                        total += count;
                        if (total > size || total > UpdateCheck.MAX_APK_BYTES) throw new IOException("파일 크기가 릴리스 정보와 달라요.");
                        sha.update(buffer, 0, count); output.write(buffer, 0, count);
                        int percent = (int) (total * 100 / size);
                        if (percent / 10 != lastPercent / 10) { lastPercent = percent; notice(label + " " + percent + "%"); }
                    }
                }
                if (total != size) throw new IOException("다운로드가 중간에 끊겼어요. 다시 시도해 주세요.");
                // java.util.HexFormat needs Android 14; this app also runs on 13.
                StringBuilder hex = new StringBuilder(64); for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                return hex.toString();
            } finally { connection.disconnect(); }
        }
        throw new IOException("다운로드 주소가 너무 여러 번 바뀌었어요.");
    }
    /** Same package, higher version, same signing key as the installed app. */
    private void verifyPackage(File apk, String expectedVersion) throws Exception {
        PackageManager packages = context.getPackageManager();
        PackageManager.PackageInfoFlags flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo archive = packages.getPackageArchiveInfo(apk.getPath(), flags), installed = packages.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || !context.getPackageName().equals(archive.packageName)) throw new IllegalStateException(Brand.NAME + " 설치 파일이 아니어서 설치하지 않았어요.");
        if (!expectedVersion.equals(archive.versionName)) throw new IllegalStateException("릴리스와 설치 파일의 버전이 달라요.");
        if (archive.getLongVersionCode() <= installed.getLongVersionCode()) throw new IllegalStateException("이미 같은 버전이거나 더 최신 버전이 설치돼 있어요.");
        Signature[] incoming = archive.signingInfo == null ? null : archive.signingInfo.getApkContentsSigners();
        Signature[] current = installed.signingInfo == null ? null : installed.signingInfo.getApkContentsSigners();
        // This updater deliberately does not accept key rotation. Fail closed before invoking the installer.
        if (incoming == null || current == null || incoming.length == 0 || current.length == 0 || !Arrays.equals(incoming, current))
            throw new IllegalStateException("새 버전의 서명키가 지금 앱과 달라 덮어 설치할 수 없어요. 기존 앱과 데이터를 유지하고 배포 서명을 확인해 주세요.");
    }
}
