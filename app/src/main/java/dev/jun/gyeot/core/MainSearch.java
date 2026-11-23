package dev.jun.gyeot.core;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The main chat's web search: the composer's 검색 setting (자동, 항상, 끔) and the hosted tools a
 * reply then offers its engine, a refused search or page reading remembered for an account and model for a week (each on
 * its own), and what an answer brought from the web (the pages it cited, Google's search suggestions, whether it came
 * without search), kept beside the answer for the source strip and never in the text the model reads back. Pure data.
 */
public final class MainSearch {
    private MainSearch() {}
    /** The composer chip's setting. It is global: the app has one main conversation. */
    public static final String SETTING = "mainSearch";
    /** The answer's foot when search was wanted but the answer came without it. */
    public static final String UNSEARCHED = "검색 없이 답했어요";
    /** At most this many pages show above an answer. */
    public static final int STRIP = 8;
    /** How long a refused tool stays remembered for an account and model. */
    public static final long REFUSAL_MS = 7L * 24 * 60 * 60 * 1000;
    /** The longest search-suggestion snippet kept (Google's is a few kB of HTML and CSS). */
    public static final int SUGGESTIONS = 64_000;
    /** The site a chip names for a Gemini grounding redirect whose title is not a site: the page hides behind Google's address. */
    public static final String GOOGLE = "Google 검색 결과";
    /** The 검색 sheet: what search can cost, and how a kept refusal is tried again (as 작업창 says it). */
    public static final String FEES = "API 키로 연결했다면 검색마다 추가 요금이 붙을 수 있어요.", RETRY = "켜고 끄면 다시 시도해요";
    /**
     * After the web sentence of a reply that offers a hosted tool, and in 작업창's search instructions: what a search or a
     * page fetch sends leaves the phone, so nothing personal goes into it unasked.
     */
    public static final String PRIVATE = "Search queries and page addresses leave the app: never put the user's name, memories, notes or other personal details in them unless the user asked for that exact search.";
    private static final int TITLE = 120;
    /** A web address in a question: the scheme in any case, then ASCII up to the first space or other character (SpeechText's rule). */
    private static final Pattern LINK = Pattern.compile("https?://[\\x21-\\x7E&&[^<>\"'`]]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOMAIN = Pattern.compile("[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}");
    /** A link in Google's suggestion snippet: its address. */
    private static final Pattern HREF = Pattern.compile("<a\\b[^>]*?\\bhref\\s*=\\s*\"([^\"]{1,2048})\"", Pattern.CASE_INSENSITIVE);

    public enum Mode {
        AUTO("auto", "자동"), ALWAYS("always", "항상"), OFF("off", "끔");
        public final String key, label;
        Mode(String key, String label) { this.key = key; this.label = label; }
        /** The stored setting; anything else (nothing saved yet) is 자동. */
        public static Mode of(String key) { for (Mode mode : values()) if (mode.key.equals(key)) return mode; return AUTO; }
    }
    /**
     * What one reply offers: hosted search and page reading, the search forced (항상, where the provider can force it), and
     * unsearched when search was wanted but this engine cannot carry it (NIM).
     */
    public record Tools(boolean search, boolean fetch, boolean always, boolean unsearched) {
        public boolean any() { return search || fetch; }
    }
    /**
     * The tools for a reply to question on an engine that carries these options. 자동 and 항상 offer search and, where it is a
     * tool of its own, page reading, the same on every turn so the provider's prompt cache holds. 끔 offers no search; a
     * question with a link still gets page reading where the engine has it.
     */
    public static Tools tools(Mode mode, String engine, Set<MainRequest.Option> carried, String question) {
        boolean search = mode != Mode.OFF && carried.contains(MainRequest.Option.SEARCH);
        boolean fetch = carried.contains(MainRequest.Option.FETCH) && (mode != Mode.OFF || linked(question));
        return new Tools(search, fetch, search && mode == Mode.ALWAYS && forces(engine), mode != Mode.OFF && !search);
    }
    /**
     * Whether the engine's request can force its search: ChatGPT's, whose one tool is then required (tool_choice). Claude's
     * current models refuse a forced tool_choice, Gemini's google_search has no forcing mode, and xAI documents none for Grok.
     */
    public static boolean forces(String engine) { return WorkBook.CHATGPT.equals(WorkBook.engine(engine)); }
    /** Whether the engine reads a linked page with a tool of its own (Claude web_fetch, Gemini url_context). */
    public static boolean reads(String engine) { return MainRequest.supported(engine).contains(MainRequest.Option.FETCH); }
    /**
     * The text holds a web address: http or https, in any case, with a host. The address stops at the first character
     * outside ASCII, so Korean right after it is not part of it ("https://naver.com에 있는 기사").
     */
    public static boolean linked(String text) {
        Matcher match = LINK.matcher(text == null ? "" : text);
        while (match.find()) {
            String found = match.group(); int colon = found.indexOf(':');
            if (page(found.substring(0, colon).toLowerCase(Locale.ROOT) + found.substring(colon)) != null) return true;
        }
        return false;
    }

    /** The chip's help line for the engine in use. */
    public static String help(String engine, Mode mode) {
        String chosen = WorkBook.engine(engine), name = WorkBook.author(chosen);
        if (WorkBook.NIM.equals(chosen)) return "NVIDIA NIM에는 웹 검색 도구가 없어서 검색 없이 답해요.";
        if (WorkBook.ANTIGRAVITY.equals(chosen)) return "Antigravity는 앱의 웹 검색 없이 답해요.";
        return switch (mode) {
            case OFF -> reads(chosen) ? "검색하지 않아요. 질문에 링크가 있으면 " + name + "에서 그 페이지만 읽어요." : "검색하지 않아요. " + name + "에서는 링크도 열어 보지 않아요.";
            case ALWAYS -> forces(chosen) ? name + "에서 질문마다 먼저 검색해요." : name + "에서는 검색을 강제할 수 없어서 자동처럼 필요할 때 검색해요.";
            default -> name + "에서 필요할 때 검색하고, 찾은 페이지를 답 위에 보여 줘요.";
        };
    }

    // ── Refusal memory ──────────────────────────────────────────────────────

    /** Where a refused web search is kept: per engine and model. */
    public static String refusalKey(String engine, String model) { return "mainSearchRefused." + engine + "." + model; }
    /** Where refused page reading is kept, apart from search, so it never turns search off. */
    public static String fetchRefusalKey(String engine, String model) { return "mainFetchRefused." + engine + "." + model; }
    /** How every key that keeps an engine's refusals starts, for any model: picking 자동 or 항상 again forgets them. */
    public static List<String> refusalPrefixes(String engine) { return List.of("mainSearchRefused." + engine + ".", "mainFetchRefused." + engine + "."); }
    /** What the 검색 sheet says while a refusal is kept for the connection and model in use; "" while none is. */
    public static String remembered(boolean search, boolean fetch) {
        if (search) return "이 연결이 웹 검색을 거부해서 검색 없이 답하고 있어요(" + RETRY + ").";
        return fetch ? "이 연결이 링크 읽기를 거부해서 링크를 열지 않고 있어요(" + RETRY + ")." : "";
    }
    /** What is kept: the account's connection fingerprint (a hash, never a key) and when it refused. */
    public static String refusal(String fingerprint, long now) { return obj("account", fingerprint == null ? "" : fingerprint, "at", now).toString(); }
    /** This account refused the tools within the last week; another account, an older refusal or an unreadable one has not. */
    public static boolean refused(String saved, String fingerprint, long now) {
        if (saved == null || saved.isBlank() || fingerprint == null || fingerprint.isBlank()) return false;
        try {
            JSONObject refusal = new JSONObject(saved); long at = refusal.optLong("at", -1);
            return fingerprint.equals(refusal.optString("account")) && at >= 0 && at <= now && now - at < REFUSAL_MS;
        } catch (Exception unreadable) { return false; }
    }

    // ── What came from the web ──────────────────────────────────────────────

    /** A saved answer's web record: the pages for its strip, Google's search suggestions, and whether it came without search. */
    public record Web(JSONArray sources, String suggestions, boolean unsearched) {
        public static final Web NONE = new Web(arr(), "", false);
    }
    /** The web column of an answer (Web as JSON), "" when there is nothing to keep. */
    public static String record(JSONArray sources, String suggestions, boolean unsearched) {
        JSONArray strip = strip(sources); String entry = suggestions == null || suggestions.length() > SUGGESTIONS ? "" : suggestions;
        if (strip.length() == 0 && entry.isBlank() && !unsearched) return "";
        JSONObject record = obj();
        if (strip.length() > 0) put(record, "sources", strip);
        if (!entry.isBlank()) put(record, "suggestions", entry);
        if (unsearched) put(record, "unsearched", true);
        return record.toString();
    }
    /** The record a web column holds; nothing for an empty or unreadable one. */
    public static Web web(String column) {
        if (column == null || column.isBlank()) return Web.NONE;
        try {
            JSONObject record = new JSONObject(column); String entry = record.optString("suggestions");
            return new Web(strip(record.optJSONArray("sources")), entry.length() > SUGGESTIONS ? "" : entry, record.optBoolean("unsearched"));
        } catch (Exception unreadable) { return Web.NONE; }
    }
    /**
     * The pages to show above an answer, as [{uri, site, title}]: cited ones first, then the rest in the order they came,
     * each page once (a Gemini grounding redirect, which hides the page, once per site it names, else once per title), at
     * most STRIP. Only http and https addresses are kept. Titles are untrusted web text kept as plain text: control and
     * formatting characters go, spaces fold, a long one is cut, and one that only repeats the site, the address or a
     * citation number ("1") is left out.
     */
    public static JSONArray strip(JSONArray sources) {
        Map<String, JSONObject> pages = new LinkedHashMap<>(); Set<String> cited = new HashSet<>();
        for (int i = 0; sources != null && i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i); if (source == null) continue;
            String uri = page(source.optString("uri", source.optString("url"))); if (uri == null) continue;
            // A record read back names a redirect's site in its own field, its title having been left out for repeating it.
            String title = plain(source.optString("title"), TITLE), site = site(uri, title.isEmpty() ? plain(source.optString("site"), TITLE) : title);
            boolean bare = title.equalsIgnoreCase(site) || title.equals(uri) || title.equals(plain(uri, TITLE)) || title.matches("\\[?\\d{1,3}]?");
            String shown = bare ? "" : title, key = key(uri, site, shown);
            pages.putIfAbsent(key, obj("uri", uri, "site", site, "title", shown));
            if (source.optBoolean("cited")) cited.add(key);
        }
        JSONArray strip = arr();
        for (boolean first : new boolean[]{true, false})
            for (Map.Entry<String, JSONObject> page : pages.entrySet()) if (strip.length() < STRIP && cited.contains(page.getKey()) == first) strip.put(page.getValue());
        return strip;
    }
    /** The address as the app's browser takes it (BrowserTools.url: http or https with a host, no user info), else null. */
    public static String page(String uri) {
        if (uri == null || uri.isBlank() || uri.length() > 4096) return null;
        try { return BrowserTools.url(uri); } catch (IllegalArgumentException invalid) { return null; }
    }
    /**
     * The site a chip names: the host without www. or m. (the chip shows its end, the registrable part, when it is long), or
     * for a Gemini grounding redirect the site Google gave as its title when that title is a site, else GOOGLE.
     */
    private static final Set<String> SECOND = Set.of("co", "ac", "go", "or", "ne", "re", "pe", "gov", "edu", "com", "net", "org", "mil", "nhs");
    private static final Set<String> GENERIC = Set.of("www", "m", "mobile", "amp", "news", "blog", "blogs", "docs", "doc", "en", "ko", "kr", "ja", "jp", "zh", "de", "fr", "es",
        "apps", "app", "help", "support", "api", "web", "search", "store", "shop", "cafe", "post", "view", "data", "info", "home", "my", "n", "v", "dic", "dict", "about", "community", "forum", "forums", "wiki");
    /**
     * A site's short name, as a small citation mark shows it: the name it is registered under (onlinelibrary.wiley.com
     * is "wiley", news.bbc.co.uk "bbc"), or the service in front of a short umbrella name (pubmed.ncbi.nlm.nih.gov is
     * "pubmed"). Anything that is not a host name is given back as it is.
     */
    public static String shortName(String site) {
        String host = site == null ? "" : site.strip().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        if (!DOMAIN.matcher(host).matches()) return site == null ? "" : site.strip();
        String[] labels = host.split("\\."); int count = labels.length, name = count - 2;
        if (count >= 3 && labels[count - 1].length() == 2 && SECOND.contains(labels[count - 2])) name = count - 3;
        String registered = labels[name];
        if (registered.length() <= 3 && name > 0 && labels[0].length() >= 4 && !GENERIC.contains(labels[0])) return labels[0];
        return registered;
    }
    /** The host of a web address, without www., or "" when it has none. */
    public static String host(String uri) {
        try { String host = URI.create(uri).getHost(); return host == null ? "" : host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""); } catch (Exception invalid) { return ""; }
    }
    public static String site(String uri, String title) {
        String host;
        try { host = URI.create(uri).getHost(); } catch (Exception invalid) { host = null; }
        if (host == null) return "";
        host = host.toLowerCase(Locale.ROOT);
        if (redirect(host, uri)) return title != null && DOMAIN.matcher(title.toLowerCase(Locale.ROOT)).matches() ? title.toLowerCase(Locale.ROOT) : GOOGLE;
        return host.replaceFirst("^(www|m|mobile)\\.(?=.+\\.)", "");
    }
    /** Text from the web for a TextView: no control or formatting characters (bidi overrides, zero-width marks), spaces folded, at most limit characters. */
    public static String plain(String text, int limit) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(c -> {
            int type = Character.getType(c);
            if (Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSpaceChar(c)) out.append(' ');
            else if (type != Character.FORMAT && type != Character.PRIVATE_USE && type != Character.SURROGATE && type != Character.UNASSIGNED) out.appendCodePoint(c);
        });
        String folded = out.toString().replaceAll(" +", " ").strip();
        if (folded.codePointCount(0, folded.length()) <= limit) return folded;
        return folded.substring(0, folded.offsetByCodePoints(0, Math.max(1, limit - 1))).strip() + "…";
    }
    /**
     * Google's suggestions as plain searches, for a phone whose WebView cannot show the snippet: [{query, url}] from its
     * Google Search links (https, google.com, /search with a q), each query once, at most STRIP, each with a Google Search
     * address of its own. None when the snippet has no such link.
     */
    public static JSONArray queries(String html) {
        JSONArray found = arr(); Set<String> seen = new HashSet<>();
        if (html == null || html.isBlank() || html.length() > SUGGESTIONS) return found;
        Matcher link = HREF.matcher(html);
        while (link.find() && found.length() < STRIP) {
            String query = query(link.group(1).replace("&amp;", "&"));
            if (!query.isEmpty() && seen.add(query)) found.put(obj("query", query, "url", "https://www.google.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)));
        }
        return found;
    }
    /** The search a Google Search address asks for, as plain text; "" for any other address. */
    private static String query(String url) {
        try {
            URI parsed = new URI(url); String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || !(host.equals("www.google.com") || host.equals("google.com")) || !"/search".equals(parsed.getPath()) || parsed.getRawQuery() == null) return "";
            for (String pair : parsed.getRawQuery().split("&")) if (pair.startsWith("q=")) {
                String query = URLDecoder.decode(pair.substring(2), StandardCharsets.UTF_8);
                return query.indexOf('\uFFFD') >= 0 ? "" : plain(query, 80);
            }
        } catch (Exception invalid) {}
        return "";
    }
    private static boolean redirect(String host, String uri) {
        return "vertexaisearch.cloud.google.com".equals(host) && uri.contains("/grounding-api-redirect/");
    }
    /**
     * One page once: the address without its fragment or trailing slash, the host in lower case; a redirect once per site,
     * or when it names none (GOOGLE), once per title (once per address without one), so distinct pages stay distinct.
     */
    private static String key(String uri, String site, String title) {
        try {
            URI parsed = URI.create(uri); String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
            if (redirect(host, uri)) return GOOGLE.equals(site) ? "title:" + (title.isEmpty() ? uri : title) : "site:" + site;
            String path = parsed.getRawPath() == null ? "" : parsed.getRawPath().replaceFirst("/+$", "");
            return parsed.getScheme().toLowerCase(Locale.ROOT) + "://" + host + (parsed.getPort() < 0 ? "" : ":" + parsed.getPort()) + path + (parsed.getRawQuery() == null ? "" : "?" + parsed.getRawQuery());
        } catch (Exception invalid) { return uri; }
    }
}
