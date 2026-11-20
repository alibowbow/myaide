package dev.jun.gyeot.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Narrow native GET escape from an unresolved mutation. Never authorizes click/form replay or clears its ledger. */
public final class BrowserReadNavigation {
    private BrowserReadNavigation() {}
    private static final Set<String> QUERY = Set.of("id", "no", "page", "p", "pg", "article", "article_id", "articleid", "board", "board_id", "bid", "bo_table", "wr_id", "news_id", "nid", "idx", "index", "category", "cat", "section", "type", "recommend", "exception_mode", "q", "query", "search", "keyword", "s", "s_type", "s_keyword", "sort", "order", "list_num", "offset", "limit", "date", "from", "to", "lang", "hl", "gl", "utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term",
        // Korean public sites' board, menu, search and paging keys (boardID, nttId, m, searchStr, pageIndex …); content.js lists the same.
        "boardid", "boardseq", "bbsid", "bbs_id", "bbsno", "nttid", "ntt_id", "nttsn", "articleseq", "seq", "menuid", "menu_id", "menuno", "menu_no", "mid", "m", "searchstr", "searchtype", "searchcnd", "searchwrd", "searchkeyword", "searchcondition", "pageindex", "pageunit", "currpage", "cpage", "curpage", "pageno", "page_no", "lev", "year", "statusyn");
    private static final Set<String> READ_TYPES = Set.of("best", "all", "list", "view", "read", "article", "news", "search", "title", "subject", "content", "latest", "new", "recommend", "recommendation", "popular", "gallery", "1", "0");
    private static final String ACTION = "(?i)(^|[/_.\\-])(login|signin|signout|logout|oauth|auth|authorize|callback|password|passwd|reset|verify|confirm|captcha|delete|remove|destroy|erase|purge|withdraw|unsubscribe|purchase|buy|checkout|pay|payment|order|submit|send|write|post|edit|update|upload|save|create|execute|trigger|action|redirect|revoke|approve|accept|cancel|vote|like|dislike|react|reaction|follow|unfollow|join|leave|subscribe|register|enroll|invite|share|report|block|unblock|connect|disconnect|install|run|redeem|claim|transfer|donate)([/_.\\-]|$)";

    public static boolean allowed(JSONObject observation, String currentDocument, String requestedUrl) {
        if (observation == null || currentDocument == null || currentDocument.isBlank()
                || !currentDocument.equals(observation.optString("document"))
                || !observation.optBoolean("ok") || observation.optBoolean("requires_user")
                || !safeHref(requestedUrl)) return false;
        // Only explicitly collected anchor destinations, never arbitrary strings in page text.
        JSONArray links = observation.optJSONArray("elements");
        if (links == null) return false;
        for (int i = 0; i < links.length(); i++) {
            JSONObject link = links.optJSONObject(i);
            if (link != null && "a".equalsIgnoreCase(link.optString("tag")) && requestedUrl.equals(link.optString("href"))) return true;
        }
        return false;
    }

    /** Syntax/purpose filter, not a DNS or server-side no-effects guarantee. */
    public static boolean safeHref(String url) {
        if (url == null || url.isBlank() || url.length() > 2048 || !url.equals(url.strip())) return false;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null || host == null
                    || uri.getPort() != -1 && uri.getPort() != 443) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (!host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,63}")
                    || host.endsWith(".local") || host.endsWith(".localhost") || host.endsWith(".internal")
                    || host.endsWith(".invalid") || host.endsWith(".test") || host.endsWith(".onion")
                    || host.endsWith(".nip.io") || host.endsWith(".sslip.io") || host.equals("localtest.me")
                    || host.endsWith(".localtest.me")) return false;
            String path = decode(uri.getRawPath() == null ? "" : uri.getRawPath());
            if (path.indexOf('%') >= 0 || path.indexOf(';') >= 0 || path.indexOf('\\') >= 0
                    || path.contains("..") || path.matches("(?s).*" + ACTION + ".*")) return false;
            String query = uri.getRawQuery();
            if (query == null || query.isEmpty()) return true;
            if (query.length() > 1200) return false;
            for (String pair : query.split("&", -1)) {
                if (pair.isEmpty()) return false;
                String[] bits = pair.split("=", 2);
                String key = decode(bits[0]).toLowerCase(Locale.ROOT);
                String value = bits.length == 2 ? decode(bits[1]) : "";
                if (!QUERY.contains(key) || value.indexOf('%') >= 0 || value.length() > 400) return false;
                if (Set.of("type", "s_type").contains(key) && !READ_TYPES.contains(value.toLowerCase(Locale.ROOT))) return false;
                if (key.equals("recommend") && !Set.of("0", "1").contains(value)) return false;
                if (key.equals("exception_mode") && !Set.of("", "recommend").contains(value)) return false;
                // Destinations and credential-shaped values must not hide in an otherwise benign parameter.
                if (value.matches("(?is).*(https?://|javascript:|bearer\\s|sk[-_]|access_token|password=).*")) return false;
            }
            return true;
        } catch (Exception malformed) { return false; }
    }
    private static String decode(String text) {
        String decoded = URLDecoder.decode(text, StandardCharsets.UTF_8);
        if (decoded.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("control");
        return decoded;
    }
}
