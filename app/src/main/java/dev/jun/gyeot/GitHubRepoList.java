package dev.jun.gyeot;

import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.GitHubRepos;
import dev.jun.gyeot.core.Net;
import dev.jun.gyeot.core.RequestAbort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The repository picker's list (core/GitHubRepos): read from api.github.com with the saved token and kept in settings, so
 * the picker opens at once with what was read last and refreshes behind it. The token goes to api.github.com only
 * (OpenAiAccount.Http follows no redirect). Results come back on the main thread.
 */
final class GitHubRepoList {
    private GitHubRepoList() {}

    /** settings: the last list read, for the account in githubUser. Removed when the token is replaced or removed. */
    static final String CACHE = "githubRepoCache";
    /** Why a read failed when GitHub refused the saved token (401); the picker then offers 설정 → 커넥터 → GitHub beside it. */
    static final String REFUSED = "GitHub가 저장된 토큰을 받지 않았어요. 설정 → 커넥터 → GitHub에서 토큰을 바꿔 주세요.";

    /** The list, or why it could not be read (then repos is null). */
    interface Done { void accept(List<GitHubRepos.Repo> repos, String error); }

    /** What was read last for the connected account; empty when nothing was read or another account was connected since. */
    static List<GitHubRepos.Repo> cached(AppStore store) {
        try { return GitHubRepos.cached(new JSONObject(store.settings.getString(CACHE, "{}")), store.settings.getString("githubUser", "")); }
        catch (Exception e) { return new ArrayList<>(); }
    }
    static boolean stale(AppStore store, long now) {
        try { return GitHubRepos.stale(new JSONObject(store.settings.getString(CACHE, "{}")), now); } catch (Exception e) { return true; }
    }
    static void forget(AppStore store) { store.settings.edit().remove(CACHE).apply(); }

    /**
     * Reads every page again off the main thread; done runs on the main thread unless the returned stop ran first. The stop
     * also aborts a page in flight.
     */
    static Runnable refresh(AppStore store, Done done) {
        AtomicReference<Runnable> cancel = new AtomicReference<>();
        boolean[] stopped = {false};
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<GitHubRepos.Repo> repos = null; String error = "";
            try {
                String token = store.secrets.get(SecretStore.GITHUB_CODE);
                if (token.isBlank()) throw new IllegalStateException("GitHub를 먼저 연결해 주세요.");
                String user = store.settings.getString("githubUser", "");
                repos = fetch(token, cancel);
                if (cancel.get() != RequestAbort.STOPPED && token.equals(store.secrets.get(SecretStore.GITHUB_CODE)))
                    store.settings.edit().putString(CACHE, GitHubRepos.cache(user, System.currentTimeMillis(), repos).toString()).apply();
            } catch (Exception e) {
                repos = null; error = e instanceof java.io.IOException ? Net.explain(e) : e.getMessage() == null ? "GitHub 저장소 목록을 읽지 못했어요." : e.getMessage();
            }
            List<GitHubRepos.Repo> result = repos; String reason = error;
            main.post(() -> { if (!stopped[0]) done.accept(result, reason); });
        }, "gyeot-github-repos").start();
        return () -> {
            stopped[0] = true;
            Runnable inFlight = cancel.getAndSet(RequestAbort.STOPPED);
            if (inFlight != null && inFlight != RequestAbort.STOPPED) RequestAbort.submit(inFlight);
        };
    }

    /** Every page the token can see, most recently pushed first (core/GitHubRepos.url), up to GitHubRepos.PAGES pages. */
    static List<GitHubRepos.Repo> fetch(String token, AtomicReference<Runnable> cancel) throws Exception {
        List<GitHubRepos.Repo> all = new ArrayList<>();
        for (int page = 1; ; page++) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + token); headers.put("Accept", "application/vnd.github+json"); headers.put("X-GitHub-Api-Version", "2022-11-28");
            JSONArray listed;
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", GitHubRepos.url(page), headers, null, 20_000, cancel)) {
                String body = OpenAiAccount.Http.read(response.body, 4 * 1024 * 1024);
                if (response.status == 401) throw new IllegalStateException(REFUSED);
                if (response.status == 403 || response.status == 429) throw new IllegalStateException("GitHub가 지금은 목록을 주지 않아요. 잠시 뒤 다시 불러와 주세요.");
                if (response.status != 200) throw new IllegalStateException("GitHub 저장소 목록을 읽지 못했어요 (" + response.status + ").");
                listed = new JSONArray(body);
            }
            all = GitHubRepos.join(all, GitHubRepos.parse(listed));
            if (!GitHubRepos.more(page, listed.length())) return all;
        }
    }
}
