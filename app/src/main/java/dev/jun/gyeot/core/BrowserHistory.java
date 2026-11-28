package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The AI 브라우저's own browsing history, for the address bar's autocomplete (준: "Ai브라우저 주소창 자동완성이 안돼"): each page
 * the user saw (never a secret tab's), its title, how often and when last. As a word is typed, the address it most likely
 * means is filled in after the cursor (inline: "cla" → "claude.ai/code"), and the pages that match are listed, the often and
 * recently seen first, an address that starts with the words before one that only has them. Pure Java; the caller keeps
 * it (load/save as JSON) and serializes access.
 */
public final class BrowserHistory {
    /** One page: its address, its title, how many times it was opened, when last (ms). */
    public record Page(String url, String title, int visits, long at) {}
    /** Kept so the list and its file stay small on a phone; the least seen and oldest go first. */
    static final int KEPT = 3000;
    private final Map<String, Page> pages = new LinkedHashMap<>();

    /** A page seen now (http or https only, not search result pages' scrolling within the same address). */
    public void visit(String url, String title, long now) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        String key = key(url); if (key.isEmpty()) return;
        Page was = pages.get(key);
        String name = title == null || title.isBlank() || title.equals(url) ? (was == null ? "" : was.title()) : title.strip();
        pages.put(key, new Page(was == null || url.length() < was.url().length() && !url.contains("#") ? url : was.url(), name, was == null ? 1 : was.visits() + 1, now));
        if (pages.size() > KEPT) {
            List<Map.Entry<String, Page>> all = new ArrayList<>(pages.entrySet());
            all.sort(Comparator.comparingDouble(e -> score(e.getValue(), now)));
            for (int i = 0; i < all.size() - KEPT; i++) pages.remove(all.get(i).getKey());
        }
    }
    /** A page's title known later than its address (the page set it after loading). */
    public void title(String url, String title) {
        String key = key(url); Page was = key.isEmpty() ? null : pages.get(key);
        if (was != null && title != null && !title.isBlank() && !title.equals(url)) pages.put(key, new Page(was.url(), title.strip(), was.visits(), was.at()));
    }
    /** Forgets every page (설정 → 방문 기록 지우기). */
    public void clear() { pages.clear(); }
    public int size() { return pages.size(); }

    /** The address without its scheme, "www." and a trailing slash, lowercased: what a typed word is matched against. */
    public static String bare(String url) {
        String u = url == null ? "" : url.strip().toLowerCase(Locale.ROOT);
        u = u.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        int hash = u.indexOf('#'); if (hash >= 0) u = u.substring(0, hash);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
    private static String key(String url) { return bare(url); }
    /** How much a page counts now: its visits, worth less as it gets older (a week halves it). */
    static double score(Page p, long now) {
        double days = Math.max(0, (now - p.at()) / 86_400_000.0);
        return (1 + Math.log(1 + p.visits())) * Math.pow(0.5, days / 7.0);
    }

    /**
     * What to fill in after the typed words (inline autocomplete), or "": the best page whose bare address starts with them,
     * cut at the next "/" when the words are only part of its site (so "cla" gives "claude.ai", "claude.ai/c" gives
     * "claude.ai/code"). Only for words that look like the start of an address (no spaces).
     */
    public String inline(String typed, long now) {
        String t = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.contains(" ")) return "";
        t = t.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        if (t.isEmpty()) return "";
        Page best = null; double top = -1;
        for (Page p : pages.values()) {
            String b = bare(p.url());
            if (!b.startsWith(t)) continue;
            double s = score(p, now) + (b.length() == t.length() ? 0 : 0) - b.length() / 1000.0;
            if (s > top) { top = s; best = p; }
        }
        if (best == null) return "";
        String b = bare(best.url());
        // Within the site name: complete the site only; past it, up to the next path part.
        int slash = b.indexOf('/');
        if (slash < 0 || t.length() <= slash) return slash < 0 ? b : b.substring(0, slash);
        int next = b.indexOf('/', t.length()); return next < 0 ? b : b.substring(0, next);
    }

    /** The pages that match the words, best first: an address starting with them, then one or a title that has them. */
    public List<Page> matches(String typed, int most, long now) {
        String t = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT);
        List<Page> out = new ArrayList<>();
        if (t.isEmpty()) return out;
        String start = t.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        List<Object[]> found = new ArrayList<>();
        for (Page p : pages.values()) {
            String b = bare(p.url()), title = p.title().toLowerCase(Locale.ROOT);
            double s = score(p, now);
            if (b.startsWith(start)) s += 100;
            else if (b.contains(start)) s += 10;
            else if (!title.contains(t)) {
                // Every word of several somewhere in the address or the title.
                boolean all = t.contains(" "); for (String w : t.split("\\s+")) all &= !w.isEmpty() && (b.contains(w) || title.contains(w));
                if (!all) continue;
                s += 5;
            } else s += 8;
            found.add(new Object[]{p, s});
        }
        found.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
        for (Object[] f : found) { out.add((Page) f[0]); if (out.size() >= most) break; }
        return out;
    }

    public JSONArray json() {
        JSONArray out = new JSONArray();
        for (Page p : pages.values()) {
            try { out.put(new JSONObject().put("u", p.url()).put("t", p.title()).put("v", p.visits()).put("a", p.at())); } catch (Exception ignored) {}
        }
        return out;
    }
    public static BrowserHistory of(JSONArray saved) {
        BrowserHistory h = new BrowserHistory();
        if (saved != null) for (int i = 0; i < saved.length(); i++) {
            JSONObject o = saved.optJSONObject(i); if (o == null) continue;
            String u = o.optString("u"); if (u.isEmpty()) continue;
            h.pages.put(key(u), new Page(u, o.optString("t"), Math.max(1, o.optInt("v", 1)), o.optLong("a")));
        }
        return h;
    }
}
