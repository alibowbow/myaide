package dev.jun.gyeot.core;

/**
 * The user's GitHub connection for 작업창 coding: the token the app keeps in SecretStore, whether changes are allowed, and
 * the repository to work in unless the user names another. The app itself calls GitHub with it (GitHubFunctions, run by
 * GitHubRunner), so the token never goes to an AI company.
 */
public final class GitHubTools {
    public static final String USER = "https://api.github.com/user";
    public final String token, repo;
    public final boolean write;

    public GitHubTools(String token, boolean write, String repo) { this.token = token; this.write = write; this.repo = repo == null ? "" : repo; }

    /** A GitHub token: a fine-grained (github_pat_…) or classic (ghp_…) personal access token, one word. */
    public static boolean validToken(String token) {
        return token != null && (token.startsWith("github_pat_") || token.startsWith("ghp_")) && token.matches("[A-Za-z0-9_]{30,255}");
    }
    /** "owner/name" from what the user typed (a name or a github.com address), or "" when it is not one. */
    public static String repo(String text) {
        String value = text == null ? "" : text.strip().replaceFirst("(?i)^https?://(www\\.)?github\\.com/", "").replaceFirst("\\.git$", "").replaceFirst("/+$", "");
        String[] parts = value.split("/");
        return parts.length >= 2 && parts[0].matches("[A-Za-z0-9-]{1,39}") && parts[1].matches("[A-Za-z0-9._-]{1,100}") ? parts[0] + "/" + parts[1] : "";
    }
}
