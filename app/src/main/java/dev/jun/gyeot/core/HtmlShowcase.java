package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 화면 꾸미기's 움직이는 배경화면 catalogue (준: "jev에 opus5.5 활용예시 html 파트에 있는 여러 다양한 디자인의 인터랙티브한
 * 화면"): the HTML 100 collections the user's jev showcase lists (Opus 5.5, Sonnet 5.5, Gemini 4 Argon, GPT-6 Astra; each
 * piece one self-contained page MiaAI-Lab published on its own site), read from jev's data files. Only the creator's own
 * addresses are kept: a piece is loaded from there, never copied into the app. Pure Java.
 */
public final class HtmlShowcase {
    private HtmlShowcase() {}
    /** One collection: its id, its name, and jev's data file for it. */
    public record Collection(String id, String name, String data) {}
    public static final List<Collection> COLLECTIONS = List.of(
        new Collection("opus", "Opus 5.5", "https://raw.githubusercontent.com/alibowbow/jev/main/assets/opus-html-data.js"),
        new Collection("sonnet", "Sonnet 5.5", "https://raw.githubusercontent.com/alibowbow/jev/main/assets/sonnet-html-data.js"),
        new Collection("argon", "Gemini 4 Argon", "https://raw.githubusercontent.com/alibowbow/jev/main/assets/argon-html-data.js"),
        new Collection("astra", "GPT-6 Astra", "https://raw.githubusercontent.com/alibowbow/jev/main/assets/astra-html-data.js"));
    public static Collection collection(String id) { for (Collection c : COLLECTIONS) if (c.id().equals(id)) return c; return COLLECTIONS.get(0); }
    /** The host a piece may be loaded from (the creator's own site). */
    public static final String HOST = "miaai-lab.github.io";

    public record Piece(String id, String number, String category, String title, String original, String summary, String demo, String poster) {
        public JSONObject json() {
            JSONObject o = new JSONObject();
            try { o.put("id", id).put("number", number).put("category", category).put("title", title).put("original", original).put("summary", summary).put("demo", demo).put("poster", poster); } catch (Exception ignored) {}
            return o;
        }
        public static Piece of(JSONObject o) {
            return o == null ? null : new Piece(o.optString("id"), o.optString("number"), o.optString("category"), o.optString("title"), o.optString("original"), o.optString("summary"), o.optString("demo"), o.optString("poster"));
        }
    }
    public record Category(String id, String name) {}
    public record Catalogue(List<Category> categories, List<Piece> pieces) {}

    /** A jev data file (window.X = {…};) read into its categories and pieces; pieces not on the creator's site are left out. */
    public static Catalogue parse(String script) {
        List<Category> categories = new ArrayList<>(); List<Piece> pieces = new ArrayList<>();
        if (script == null) return new Catalogue(categories, pieces);
        int from = script.indexOf('{'), to = script.lastIndexOf('}');
        if (from < 0 || to <= from) return new Catalogue(categories, pieces);
        JSONObject data;
        try { data = new JSONObject(script.substring(from, to + 1)); } catch (Exception unreadable) { return new Catalogue(categories, pieces); }
        JSONArray cats = data.optJSONArray("categories");
        for (int i = 0; cats != null && i < cats.length(); i++) { JSONObject c = cats.optJSONObject(i); if (c != null && !c.optString("id").isBlank()) categories.add(new Category(c.optString("id"), c.optString("name", c.optString("id")))); }
        JSONArray cases = data.optJSONArray("cases");
        for (int i = 0; cases != null && i < cases.length(); i++) {
            JSONObject c = cases.optJSONObject(i); if (c == null) continue;
            String demo = c.optString("demo", c.optString("source"));
            if (!allowed(demo)) continue;
            JSONObject media = c.optJSONObject("media"); String poster = media == null ? "" : media.optString("poster");
            if (!allowed(poster)) poster = "";
            pieces.add(new Piece(c.optString("id"), c.optString("number"), c.optString("category"), c.optString("title"), c.optString("originalTitle"), c.optString("summary"), demo, poster));
        }
        return new Catalogue(categories, pieces);
    }
    /** An address on the creator's site over HTTPS (what a wallpaper may load). */
    public static boolean allowed(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        String rest = url.substring(8); int slash = rest.indexOf('/');
        String host = (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(java.util.Locale.ROOT);
        return host.equals(HOST);
    }
}
