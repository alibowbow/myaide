package dev.jun.gyeot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 퍼레 itself on the home screen, from the voice talk and 퍼레 대화 (준: "음성 대화로 주사위 꺼내봐 해도 안된다고해", "의상바꿔도
 * 안되고", "음성대화로 퍼레의 상호작용이 안돼"): a toy out, clothes to wear (or none), a play by name. The words the brain picks
 * from (BuddyTools) are Korean; the page's are its own (board.js selfAct). Done at once while the home screen is in front, else
 * kept and done when it is shown again.
 */
final class BuddyBoard {
    private BuddyBoard() {}

    /** Toys: the words → board.js ASK_TOY ("away" puts them all away). */
    static final Map<String, String> TOYS = map("공", "ball", "주사위", "dice", "큐브", "cube", "팽이", "top", "풍선", "balloon", "나비", "butterfly",
        "별똥별", "meteors", "비눗방울", "bubbles", "치우기", "away");
    /** Clothes: the words → board.js WEAR. "기본" is none; "아무거나" is the page's own pick of a cute one. */
    static final Map<String, String> CLOTHES = map("기본", "", "아무거나", "any",
        "고양이 귀", "catears", "토끼 귀", "bunny", "리본", "bow", "왕관", "crown", "티아라", "tiara", "꽃", "flower", "꽃왕관", "flowercrown", "새싹", "sprout",
        "베레모", "beret", "벙거지", "bucket", "비니", "beanie", "귀마개", "muffs", "헤드폰", "phones", "밀짚모자", "straw", "파티 모자", "party", "산타 모자", "santa",
        "마녀 모자", "witch", "개구리 모자", "froghat", "버섯 모자", "mushroom", "요리사 모자", "chef", "헤어밴드", "headband", "두건", "bandana",
        "해적 모자", "pirate", "카우보이 모자", "cowboy", "중절모", "tophat", "선글라스", "shades", "동그란 안경", "roundglasses", "하트 안경", "heartglasses",
        "고글", "goggles", "목도리", "scarf", "나비넥타이", "bowtie", "방울", "bell", "목 리본", "ribbon", "꽃목걸이", "lei",
        "우산", "umbrella", "후드티", "hoodie", "스웨터", "sweater", "잠옷", "pajamas", "우비", "raincoat", "앞치마", "apron", "망토", "cape", "가방", "backpack",
        "장갑", "mittens", "곰인형", "teddy", "마이크", "mic", "요술봉", "wand", "막대사탕", "lollipop", "아이스크림", "icecream");
    /** Plays: the words → board.js PLAYS. */
    static final Map<String, String> PLAYS = map("춤", "dance", "점프", "jump", "빙글빙글", "spin", "옆돌기", "cartwheel", "물구나무", "handstand",
        "낮잠", "nap", "요가", "yoga", "체조", "exercise", "줄넘기", "skipping", "권투", "boxing", "태권도", "karate", "수영", "swim", "문워크", "moonwalk",
        "로봇춤", "robotdance", "훌라춤", "hula", "노래", "singsong", "마술", "magic", "닌자", "ninja", "숨바꼭질", "hideseek", "비행기", "airplane",
        "발레", "ballerina", "저글링", "juggling", "종이비행기", "paperplane", "연날리기", "kite", "명상", "meditate", "책 읽기", "reading", "커피 타임", "coffeebreak",
        "구름 구경", "cloudwatch", "박수", "applause", "하이파이브", "highfives", "손 흔들기", "goodbye", "이리 와", "comehere", "슈퍼히어로", "superhero",
        "헬리콥터", "helicopter", "드럼", "drummer", "지휘", "orchestra", "팔굽혀펴기", "pushups", "스트레칭", "morningstretch", "그네", "swing", "시소", "seesawride",
        "요요", "yoyo", "원반던지기", "frisbee", "바람개비", "pinwheel", "반딧불", "fireflies", "축하 꽃가루", "confetti", "눈놀이", "snowplay", "낙엽 잡기", "leafcatch",
        "간식 먹기", "snacktime", "웃음보", "laughfit", "떼쓰기", "tantrum", "더듬이 춤", "antennadance", "공차기", "kickball", "주사위 굴리기", "rolldice");

    private static Map<String, String> map(String... kv) { Map<String, String> m = new LinkedHashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]); return m; }
    static List<String> words(Map<String, String> m) { return List.copyOf(m.keySet()); }

    /** The home screen's page while it is in front (HomeActivity: its launcher calls), else null. */
    static volatile java.util.function.Consumer<String> home;
    /** Asked while the home screen was away: done when it is shown again. */
    private static volatile String pending;

    /** The page's call for an act (buddy_toy, buddy_outfit, buddy_move), or null when it names nothing the page knows. */
    static String call(JSONObject act) {
        try {
            String tool = act.optString("tool"); JSONObject o = new JSONObject();
            switch (tool) {
                case "buddy_toy" -> { String k = find(TOYS, act.optString("toy", act.optString("name"))); if (k == null) return null; o.put("toy", k); }
                case "buddy_outfit" -> {
                    JSONArray items = act.optJSONArray("items"); if (items == null) { items = new JSONArray(); for (String one : act.optString("item", act.optString("items")).split("[,/·]| 랑 | 하고 ")) if (!one.isBlank()) items.put(one.strip()); }
                    JSONArray wear = new JSONArray(); boolean any = false, plain = items.length() == 0;
                    for (int i = 0; i < items.length(); i++) { String k = find(CLOTHES, items.optString(i)); if (k == null) continue; if (k.equals("any")) any = true; else if (k.isEmpty()) plain = true; else wear.put(k); }
                    if (wear.length() == 0 && !any && !plain) any = true; // a thing it does not have: something cute of its own
                    o.put("wear", wear); if (any && wear.length() == 0) o.put("any", true);
                }
                case "buddy_move" -> { String k = find(PLAYS, act.optString("play", act.optString("what", act.optString("name")))); if (k == null) return null; o.put("play", k); }
                default -> { return null; }
            }
            return "launcher.self(" + o + ")";
        } catch (Exception broken) { return null; }
    }
    /** The key for words as said or as the brain wrote them (exact, else one inside the other: "고양이귀" → 고양이 귀, "dice" → dice). */
    static String find(Map<String, String> m, String said) {
        if (said == null) return null;
        String s = said.strip(), bare = s.replace(" ", "");
        if (s.isEmpty()) return null;
        for (Map.Entry<String, String> e : m.entrySet()) if (e.getKey().replace(" ", "").equals(bare) || e.getValue().equalsIgnoreCase(s)) return e.getValue();
        for (Map.Entry<String, String> e : m.entrySet()) { String k = e.getKey().replace(" ", ""); if (k.length() >= 2 && (bare.contains(k) || k.contains(bare) && bare.length() >= 2)) return e.getValue(); }
        return null;
    }
    /** Done now when the home screen is in front (true), else kept for when it is (false). */
    static boolean send(String call) {
        java.util.function.Consumer<String> h = home;
        if (h != null) { h.accept(call); return true; }
        pending = call; return false;
    }
    /** The home screen is in front again: what was asked meanwhile. */
    static void shown(java.util.function.Consumer<String> h) {
        home = h; String p = pending; pending = null;
        if (p != null && h != null) h.accept(p);
    }
    static void away() { home = null; }
}
