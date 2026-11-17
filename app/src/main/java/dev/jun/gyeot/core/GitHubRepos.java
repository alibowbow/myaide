package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The user's GitHub repositories for the repository picker (작업창's 저장소 chip and 설정 → 커넥터 → GitHub's 기본 저장소), the way
 * Claude Code lists them: read from GET /user/repos, which answers with what the token can see (a fine-grained token only
 * the repositories it was given), most recently pushed first. A small cache lets the list open at once and is refreshed
 * behind it. Only names and a few flags are kept, never the token.
 */
public final class GitHubRepos {
    private GitHubRepos() {}

    /** Pages of 100; at most PAGES pages (300 repositories) are read. */
    public static final int PER_PAGE = 100, PAGES = 3;
    /** A cached list older than this is shown and refreshed behind it. */
    public static final long FRESH_MS = 10 * 60_000L;

    /** One repository: "owner/name", whether it is private, archived or a fork, when it was last pushed, its default branch, and whether the user may push to it. */
    public record Repo(String name, boolean secret, boolean archived, boolean fork, String pushed, String branch, boolean push) {
        /** The part after the owner: "myaide" for "alibowbow/myaide". */
        public String shortName() { int slash = name.indexOf('/'); return slash < 0 ? name : name.substring(slash + 1); }
    }

    /** The page'th page of the user's repositories, most recently pushed first: their own, those they collaborate on and their organizations'. */
    public static String url(int page) {
        return "https://api.github.com/user/repos?per_page=" + PER_PAGE + "&page=" + Math.max(1, page) + "&sort=pushed&direction=desc&affiliation=owner,collaborator,organization_member";
    }

    /** One page of the answer; entries without a usable owner/name are skipped. */
    public static List<Repo> parse(JSONArray page) {
        List<Repo> result = new ArrayList<>();
        for (int i = 0; page != null && i < page.length(); i++) {
            JSONObject item = page.optJSONObject(i); if (item == null) continue;
            String name = GitHubTools.repo(item.optString("full_name"));
            if (name.isEmpty()) continue;
            JSONObject permissions = item.optJSONObject("permissions");
            result.add(new Repo(name, item.optBoolean("private"), item.optBoolean("archived"), item.optBoolean("fork"), item.optString("pushed_at"),
                item.optString("default_branch"), permissions != null && (permissions.optBoolean("push") || permissions.optBoolean("admin"))));
        }
        return result;
    }

    /** Pages joined in order, each repository once (a repository pushed while the pages are read can move to the next page). */
    public static List<Repo> join(List<Repo> into, List<Repo> page) {
        Map<String, Repo> all = new LinkedHashMap<>();
        for (Repo repo : into) all.putIfAbsent(repo.name().toLowerCase(Locale.ROOT), repo);
        for (Repo repo : page) all.putIfAbsent(repo.name().toLowerCase(Locale.ROOT), repo);
        return new ArrayList<>(all.values());
    }

    /** Whether to read the next page: this one was full and the limit is not reached. */
    public static boolean more(int page, int size) { return size >= PER_PAGE && page < PAGES; }

    /**
     * What the picker shows for what is typed: every word must appear in owner/name, ignoring case; a pasted github.com
     * address is read as its owner/name. Nothing typed keeps every repository.
     */
    public static List<Repo> matching(List<Repo> repos, String query) {
        String typed = query == null ? "" : query.strip();
        String asRepo = GitHubTools.repo(typed);
        String[] words = (asRepo.isEmpty() ? typed : asRepo).toLowerCase(Locale.ROOT).split("\\s+");
        List<Repo> result = new ArrayList<>();
        for (Repo repo : repos) {
            String name = repo.name().toLowerCase(Locale.ROOT); boolean all = true;
            for (String word : words) if (!word.isEmpty() && !name.contains(word)) { all = false; break; }
            if (all) result.add(repo);
        }
        return result;
    }

    /**
     * A typed owner/name that is not in the list (a public repository of someone else, or one GitHub has not listed yet),
     * offered as its own choice; "" when the words are not an owner/name or it is already listed.
     */
    public static String typed(List<Repo> repos, String query) {
        String name = GitHubTools.repo(query);
        if (name.isEmpty()) return "";
        for (Repo repo : repos) if (repo.name().equalsIgnoreCase(name)) return "";
        return name;
    }

    /** The list in the picker's order: the chosen repository first (listed or not), then the others as GitHub ordered them, archived ones last. */
    public static List<Repo> ordered(List<Repo> repos, String chosen) {
        List<Repo> result = new ArrayList<>(), archived = new ArrayList<>();
        Repo first = null;
        for (Repo repo : repos) {
            if (!chosen.isEmpty() && repo.name().equalsIgnoreCase(chosen)) first = repo;
            else if (repo.archived()) archived.add(repo);
            else result.add(repo);
        }
        if (!chosen.isEmpty()) result.add(0, first != null ? first : new Repo(chosen, false, false, false, "", "", false));
        result.addAll(archived);
        return result;
    }

    // ── Cache ───────────────────────────────────────────────────────────────

    /** The cache kept in settings: whose list it is (the token's GitHub user), when it was read, and the repositories. */
    public static JSONObject cache(String user, long at, List<Repo> repos) {
        JSONArray list = new JSONArray();
        for (Repo repo : repos) list.put(obj("name", repo.name(), "private", repo.secret(), "archived", repo.archived(), "fork", repo.fork(), "pushed", repo.pushed(), "branch", repo.branch(), "push", repo.push()));
        return obj("user", user == null ? "" : user, "at", at, "repos", list);
    }

    /** The cached repositories when the cache belongs to user; nothing when another account's token was connected since. */
    public static List<Repo> cached(JSONObject cache, String user) {
        List<Repo> result = new ArrayList<>();
        if (cache == null || user == null || user.isBlank() || !user.equalsIgnoreCase(cache.optString("user"))) return result;
        JSONArray list = cache.optJSONArray("repos");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i); if (item == null) continue;
            String name = GitHubTools.repo(item.optString("name"));
            if (!name.isEmpty()) result.add(new Repo(name, item.optBoolean("private"), item.optBoolean("archived"), item.optBoolean("fork"), item.optString("pushed"), item.optString("branch"), item.optBoolean("push")));
        }
        return result;
    }

    /** Whether the cache should be read again: older than FRESH_MS, from the future (the clock moved back), or never read. */
    public static boolean stale(JSONObject cache, long now) {
        long at = cache == null ? 0 : cache.optLong("at");
        return at <= 0 || now - at > FRESH_MS || at > now;
    }
}
