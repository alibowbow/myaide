package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 퍼레's own world and heart, for any question about it (준: "너의 최애 장난감은 뭐야?라고 물으면 현재 가지고 있는 장난감 리스트
 * 중에서 답변", "최애 장난감은 변할 수 있어", "그건 퍼레의 감정에 의해 결정되는거라 우리가 관여할 게 아냐", "생명력을 가지게
 * 해줘", "그 장난감이 없다면 내가 클로드를 이용해 만들어서 줄 수도 있는거고", "최애장난감을 예로 든건 수많은 질문 중 하나일뿐
 * 그런식으로 자유도가 무한인 상호작용").
 *
 * What it has (toys, clothes, plays: the board's), what it lately did, how each went, and what it wants. Nobody sets its
 * likes: each thing gets a fondness from its own days (a play that went well +, one that went badly −, the user playing along
 * or choosing it +), fading by half in three days, plus a whim of its own that changes each day; its favorite of a kind moves
 * only when another is clearly dearer, and it keeps why. What it wants and does not have it keeps as wishes, for the user to
 * make (with Claude) and give. The brain gets all of it (words) and answers anything as 퍼레, inside this world.
 */
public final class BuddyWorld {
    private BuddyWorld() {}

    /** The kinds of things it has: toy, wear, play. */
    public static final String TOY = "toy", WEAR = "wear", PLAY = "play";
    /** Its toys (its words → board.js names): the ones on the board and the ones its plays hold. */
    public static final Map<String, String> TOYS = map("공", "ball", "주사위", "dice", "큐브", "cube", "팽이", "top", "풍선", "balloon", "나비", "butterfly",
        "별똥별", "meteors", "비눗방울", "bubbles", "연", "kite", "요요", "yoyo", "원반", "frisbee", "바람개비", "pinwheel", "종이비행기", "paperplane", "곰인형", "teddy");
    private static final Map<String, Map<String, String>> KINDS = new LinkedHashMap<>();
    /** Its clothes and its plays come from the voice talk's lists (BuddyBoard), given once at start. */
    public static void know(Map<String, String> clothes, Map<String, String> plays) {
        Map<String, String> wear = new LinkedHashMap<>(clothes); wear.remove("기본"); wear.remove("아무거나");
        KINDS.put(TOY, TOYS); KINDS.put(WEAR, wear); KINDS.put(PLAY, new LinkedHashMap<>(plays));
    }
    static { KINDS.put(TOY, TOYS); }
    private static Map<String, String> map(String... kv) { Map<String, String> m = new LinkedHashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]); return m; }
    static final long HALF = 3 * 86_400_000L, STICK = 3_600_000L;

    static Map<String, String> of(String kind) { Map<String, String> m = KINDS.get(kind); return m == null ? Map.of() : m; }
    /** Its words for a board name of a kind, or null. */
    public static String word(String kind, String key) { for (Map.Entry<String, String> e : of(kind).entrySet()) if (e.getValue().equals(key)) return e.getKey(); return null; }

    /** Fondness now: what it was, faded by half every three days since it was set. */
    static double fond(JSONObject state, String kind, String key, long now) {
        JSONObject all = state.optJSONObject("fond"), f = all == null ? null : all.optJSONObject(kind + ":" + key);
        if (f == null) return 0;
        return f.optDouble("v", 0) * Math.pow(0.5, Math.max(0, now - f.optLong("t", now)) / (double) HALF);
    }
    /** Its own whim for a thing today (−0.6 … 0.6): the same all day, another tomorrow. */
    static double whim(String kind, String key, long now) {
        long day = now / 86_400_000L; int h = (int) (((kind + key).hashCode() * 2654435761L + day * 40503L) ^ (day >>> 3));
        h ^= h >>> 15; h *= 0x2c1b3c6d; h ^= h >>> 12;
        return ((h & 0xffff) / 65535.0 - 0.5) * 1.2;
    }

    /**
     * Something it lived with a thing (joy about −1.5 … 2.5; why: a few words of what happened, kept when it was good): its
     * fondness moves, and the day's log gets a line.
     */
    public static JSONObject lived(JSONObject state, String kind, String key, double joy, String why, long now) {
        String w = word(kind, key); if (w == null) return state;
        try {
            JSONObject all = state.optJSONObject("fond"); if (all == null) { all = new JSONObject(); state.put("fond", all); }
            double v = fond(state, kind, key, now) + Math.max(-1.5, Math.min(2.5, joy));
            JSONObject f = new JSONObject().put("v", Math.max(-4, Math.min(12, v))).put("t", now);
            JSONObject old = all.optJSONObject(kind + ":" + key);
            String said = why == null ? "" : why.strip();
            if (joy > 0 && !said.isEmpty()) f.put("why", said); else if (old != null && old.has("why")) f.put("why", old.optString("why"));
            if (joy < 0 && !said.isEmpty()) f.put("bad", said); else if (old != null && old.has("bad")) f.put("bad", old.optString("bad"));
            all.put(kind + ":" + key, f);
            JSONArray log = state.optJSONArray("log"), kept = new JSONArray();
            for (int i = log == null ? 0 : Math.max(0, log.length() - 11); log != null && i < log.length(); i++) kept.put(log.get(i));
            kept.put(new JSONObject().put("at", now).put("what", w).put("kind", kind).put("joy", Math.round(joy * 10) / 10.0).put("why", said));
            state.put("log", kept);
        } catch (Exception ignored) { /* kept as it was */ }
        return state;
    }

    /** Its favorite of a kind now (a board name): the dearest with today's whims, kept unless another is clearly dearer or an hour went by. */
    public static String favorite(JSONObject state, String kind, long now) {
        String best = null; double top = -99;
        for (String key : of(kind).values()) { double v = fond(state, kind, key, now) + whim(kind, key, now); if (v > top) { top = v; best = key; } }
        if (best == null) return null;
        JSONObject favs = state.optJSONObject("fav"); if (favs == null) { favs = new JSONObject(); try { state.put("fav", favs); } catch (Exception ignored) {} }
        JSONObject was = favs.optJSONObject(kind);
        String had = was == null ? "" : was.optString("key"); long since = was == null ? 0 : was.optLong("at");
        if (!had.isEmpty() && of(kind).containsValue(had) && !had.equals(best)) {
            double kept = fond(state, kind, had, now) + whim(kind, had, now);
            if (now - since < STICK || top - kept < 0.8) best = had;
        }
        try { if (!best.equals(had)) favs.put(kind, new JSONObject().put("key", best).put("at", now)); } catch (Exception ignored) {}
        return best;
    }
    /** What of a kind it likes least lately (a board name), or null when nothing went badly. */
    public static String least(JSONObject state, String kind, long now) {
        String worst = null; double low = -0.5;
        for (String key : of(kind).values()) { double v = fond(state, kind, key, now); if (v < low) { low = v; worst = key; } }
        return worst;
    }

    /** Something it wants and does not have (its own words); false when it has it or wished for it already. Ten at most. */
    public static boolean wish(JSONObject state, String thing, long now) {
        String t = thing == null ? "" : thing.strip().replaceAll("[\"'“”‘’]", "");
        if (t.isEmpty() || t.length() > 24) return false;
        for (Map<String, String> m : KINDS.values()) for (String w : m.keySet()) if (w.replace(" ", "").equals(t.replace(" ", ""))) return false;
        try {
            JSONArray w = state.optJSONArray("wish"); if (w == null) w = new JSONArray();
            for (int i = 0; i < w.length(); i++) if (t.replace(" ", "").equals(w.optJSONObject(i).optString("thing").replace(" ", ""))) return false;
            JSONArray kept = new JSONArray(); for (int i = Math.max(0, w.length() - 9); i < w.length(); i++) kept.put(w.get(i));
            kept.put(new JSONObject().put("thing", t).put("at", now));
            state.put("wish", kept);
            return true;
        } catch (Exception e) { return false; }
    }
    public static List<String> wishes(JSONObject state) {
        List<String> out = new ArrayList<>(); JSONArray w = state.optJSONArray("wish");
        for (int i = 0; w != null && i < w.length(); i++) { JSONObject o = w.optJSONObject(i); if (o != null && !o.optString("thing").isBlank()) out.add(o.optString("thing")); }
        return out;
    }
    /** A wish granted or let go. */
    public static void forget(JSONObject state, String thing) {
        JSONArray w = state.optJSONArray("wish"), kept = new JSONArray(); if (w == null) return;
        for (int i = 0; i < w.length(); i++) { JSONObject o = w.optJSONObject(i); if (o != null && !o.optString("thing").equals(thing)) kept.put(o); }
        try { state.put("wish", kept); } catch (Exception ignored) {}
    }

    private static String list(String kind, int most) {
        List<String> all = new ArrayList<>(of(kind).keySet());
        return all.size() <= most ? String.join(", ", all) : String.join(", ", all.subList(0, most)) + " 등 " + all.size() + "가지";
    }
    private static String fav(JSONObject state, String kind, long now) {
        String key = favorite(state, kind, now); if (key == null) return "";
        JSONObject all = state.optJSONObject("fond"), f = all == null ? null : all.optJSONObject(kind + ":" + key);
        String why = f == null ? "" : f.optString("why");
        return word(kind, key) + (why.isEmpty() ? "" : "(" + why + ")");
    }
    /** What the brain knows of 퍼레's world and heart, and how to answer anything about it. */
    public static String words(JSONObject state, long now) {
        StringBuilder out = new StringBuilder("너의 세계와 마음(너 스스로 겪고 느낀 거야):\n");
        out.append("- 가진 장난감: ").append(list(TOY, 20)).append('\n');
        if (!of(WEAR).isEmpty()) out.append("- 옷장: ").append(list(WEAR, 24)).append('\n');
        if (!of(PLAY).isEmpty()) out.append("- 할 줄 아는 놀이: ").append(list(PLAY, 24)).append('\n');
        out.append("- 요즘 마음속 최애: 장난감 ").append(fav(state, TOY, now));
        if (!of(WEAR).isEmpty()) out.append(" · 옷 ").append(fav(state, WEAR, now));
        if (!of(PLAY).isEmpty()) out.append(" · 놀이 ").append(fav(state, PLAY, now));
        out.append('\n');
        List<String> meh = new ArrayList<>();
        for (String kind : List.of(TOY, WEAR, PLAY)) {
            String k = least(state, kind, now); if (k == null) continue;
            JSONObject f = state.optJSONObject("fond").optJSONObject(kind + ":" + k);
            meh.add(word(kind, k) + (f != null && !f.optString("bad").isEmpty() ? "(" + f.optString("bad") + ")" : ""));
        }
        if (!meh.isEmpty()) out.append("- 요즘 좀 별로인 것: ").append(String.join(", ", meh)).append('\n');
        JSONArray log = state.optJSONArray("log");
        if (log != null && log.length() > 0) {
            List<String> did = new ArrayList<>();
            for (int i = Math.max(0, log.length() - 5); i < log.length(); i++) {
                JSONObject e = log.optJSONObject(i); if (e == null) continue;
                String w = e.optString("what"), why = e.optString("why"); double j = e.optDouble("joy", 0);
                did.add(w + (why.isEmpty() ? (j >= 1 ? "(신났어)" : j < 0 ? "(별로였어)" : "") : "(" + why + ")"));
            }
            out.append("- 요즘 한 일: ").append(String.join(", ", did)).append('\n');
        }
        List<String> w = wishes(state);
        if (!w.isEmpty()) out.append("- 갖고 싶은 것(소원): ").append(String.join(", ", w)).append('\n');
        out.append("- 너에 대해 뭘 물어도(좋아하는 것·싫어하는 것·하고 싶은 것·기분·이유·추억) 이 세계 안에서 너답게 자유롭게 답해. 가진 걸 물으면 위 목록에서 고르고, "
            + "마음은 겪은 일에 따라 바뀌어(예전엔 다른 걸 좋아했다고 해도 돼). 위에 없는 걸 원하면 소원으로 말하고 도구가 있으면 buddy_wish로 적어 둬. 사용자가 만들어 줄 수도 있다고 설레며 기대해.\n");
        return out.toString();
    }
}
