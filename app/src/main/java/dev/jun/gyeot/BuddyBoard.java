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
    static final Map<String, String> TOYS = map(
        "공", "ball", "주사위", "dice", "큐브", "cube", "팽이", "top", "풍선", "balloon", "나비", "butterfly",
        "별똥별", "meteors", "비눗방울", "bubbles", "치우기", "away", "축구공", "ball", "퍼즐 큐브", "cube", "루빅스 큐브", "cube",
        "고무풍선", "balloon", "유성", "meteors", "버블", "bubbles", "정리", "away", "장난감 치우기", "away", "트리", "tree",
        "크리스마스 트리", "tree", "썰매", "sleigh", "산타 썰매", "sleigh", "고무 오리", "duck", "오리", "duck", "오리 인형", "duck",
        "블록", "blocks", "나무 블록", "blocks", "자동차", "car", "장난감 자동차", "car", "장난감 차", "car", "로켓", "rocket",
        "장난감 로켓", "rocket", "북", "drum", "장난감 북", "drum", "오르골", "musicbox", "뮤직박스", "musicbox", "비치볼", "beachball",
        "물놀이 공", "beachball", "구슬", "marbles", "유리구슬", "marbles");
    /** Clothes: the words → board.js WEAR. "기본" is none; "아무거나" is the page's own pick of a cute one. */
    static final Map<String, String> CLOTHES = map(
        "기본", "", "아무거나", "any", "고양이 귀", "catears", "토끼 귀", "bunny", "리본", "bow", "왕관", "crown",
        "티아라", "tiara", "꽃", "flower", "꽃왕관", "flowercrown", "새싹", "sprout", "베레모", "beret", "벙거지", "bucket",
        "비니", "beanie", "귀마개", "muffs", "헤드폰", "phones", "밀짚모자", "straw", "파티 모자", "party", "산타 모자", "santa",
        "마녀 모자", "witch", "개구리 모자", "froghat", "버섯 모자", "mushroom", "요리사 모자", "chef", "헤어밴드", "headband", "두건", "bandana",
        "해적 모자", "pirate", "카우보이 모자", "cowboy", "중절모", "tophat", "선글라스", "shades", "동그란 안경", "roundglasses", "하트 안경", "heartglasses",
        "고글", "goggles", "목도리", "scarf", "나비넥타이", "bowtie", "방울", "bell", "목 리본", "ribbon", "꽃목걸이", "lei",
        "우산", "umbrella", "후드티", "hoodie", "스웨터", "sweater", "잠옷", "pajamas", "우비", "raincoat", "앞치마", "apron",
        "망토", "cape", "가방", "backpack", "장갑", "mittens", "곰인형", "teddy", "마이크", "mic", "요술봉", "wand",
        "막대사탕", "lollipop", "아이스크림", "icecream", "벗기", "", "원래대로", "", "머리 리본", "bow", "화관", "flowercrown",
        "버킷햇", "bucket", "털모자", "beanie", "헤드셋", "phones", "고깔모자", "party", "머리띠", "headband", "실크햇", "tophat",
        "썬글라스", "shades", "안경", "roundglasses", "비행 고글", "goggles", "보타이", "bowtie", "방울 목걸이", "bell", "후드", "hoodie",
        "파자마", "pajamas", "비옷", "raincoat", "배낭", "backpack", "벙어리장갑", "mittens", "곰돌이", "teddy", "마법봉", "wand",
        "사탕", "lollipop", "수면 모자", "cap", "나이트캡", "cap", "우비 모자", "hood", "노란 후드", "hood", "나뭇잎", "leaf",
        "단풍잎", "leaf", "눈 모자", "snowtop", "머리 위 눈", "snowtop", "수면 안대", "mask", "안대", "mask", "턱받이", "bib",
        "냅킨", "bib", "호박", "pumpkin", "할로윈 호박", "pumpkin", "복주머니", "luckybag", "부채", "handfan", "손부채", "handfan",
        "커피", "coffee", "커피잔", "coffee", "밥그릇", "lunch", "도시락", "lunch", "책", "book", "그림책", "book",
        "하트 풍선", "balloon", "담요", "blanket", "이불", "blanket", "먹구름", "storm", "번개 구름", "storm", "학사모", "grad",
        "졸업 모자", "grad", "바이킹 모자", "viking", "바이킹 투구", "viking", "선캡", "visor", "썬캡", "visor", "천사 링", "halo",
        "천사 고리", "halo", "악마 뿔", "horns", "뿔", "horns", "외알 안경", "monocle", "모노클", "monocle", "수경", "swimgoggles",
        "물안경", "swimgoggles", "콧수염", "mustache", "수염", "mustache", "한쪽 안대", "eyepatch", "해적 안대", "eyepatch", "광대 코", "clownnose",
        "빨간 코", "clownnose", "뿔테 안경", "nerdglasses", "두꺼운 안경", "nerdglasses", "진주 목걸이", "pearls", "메달", "medal", "금메달", "medal",
        "스카프", "neckbandana", "반다나", "neckbandana", "호루라기", "whistle", "아이스바", "popsicle", "하드", "popsicle", "수박", "watermelon",
        "해바라기", "sunflower", "튤립", "tulip", "민들레", "dandelion", "민들레 홀씨", "dandelion", "돋보기", "magnifier", "지도", "map",
        "보물 지도", "map", "손전등", "flashlight", "플래시", "flashlight", "랜턴", "lantern", "등불", "lantern", "작은 선물", "gift",
        "칫솔", "toothbrush", "찻잔", "tea", "따뜻한 차", "tea", "코코아", "cocoa", "핫초코", "cocoa", "붕어빵", "fishbread",
        "군고구마", "sweetpotato", "고구마", "sweetpotato", "주먹밥", "riceball", "삼각김밥", "riceball", "눈뭉치", "snowball", "눈덩이", "snowball",
        "깃발", "flag", "카메라", "camera", "사진기", "camera", "비눗방울 막대", "bubblewand", "버블 막대", "bubblewand", "무지개", "rainbow",
        "달", "moon", "초승달", "moon", "꼬마 산타 모자", "santa", "보라 귀마개", "muffs", "주방장 모자", "chef", "해골 모자", "pirate",
        "서부 모자", "cowboy", "파란 목도리", "scarf");
    /** Plays: the words → board.js PLAYS. */
    static final Map<String, String> PLAYS = map(
        "춤", "dance", "점프", "jump", "빙글빙글", "spin", "옆돌기", "cartwheel", "물구나무", "handstand", "낮잠", "nap",
        "요가", "yoga", "체조", "exercise", "줄넘기", "skipping", "권투", "boxing", "태권도", "karate", "수영", "swim",
        "문워크", "moonwalk", "로봇춤", "robotdance", "훌라춤", "hula", "노래", "singsong", "마술", "magic", "닌자", "ninja",
        "숨바꼭질", "hideseek", "비행기", "airplane", "발레", "ballerina", "저글링", "juggling", "종이비행기", "paperplane", "연날리기", "kite",
        "명상", "meditate", "책 읽기", "reading", "커피 타임", "coffeebreak", "구름 구경", "cloudwatch", "박수", "applause", "하이파이브", "highfives",
        "손 흔들기", "goodbye", "이리 와", "comehere", "슈퍼히어로", "superhero", "헬리콥터", "helicopter", "드럼", "drummer", "지휘", "orchestra",
        "팔굽혀펴기", "pushups", "스트레칭", "morningstretch", "그네", "swing", "시소", "seesawride", "요요", "yoyo", "원반던지기", "frisbee",
        "바람개비", "pinwheel", "반딧불", "fireflies", "축하 꽃가루", "confetti", "눈놀이", "snowplay", "낙엽 잡기", "leafcatch", "간식 먹기", "snacktime",
        "웃음보", "laughfit", "떼쓰기", "tantrum", "더듬이 춤", "antennadance", "공차기", "kickball", "주사위 굴리기", "rolldice", "춤추기", "dance",
        "댄스", "dance", "높이뛰기", "jump", "한 바퀴 돌기", "spin", "물구나무서기", "handstand", "잠자기", "nap", "쿨쿨", "nap",
        "운동", "exercise", "깡충깡충", "skipping", "복싱", "boxing", "가라테", "karate", "헤엄", "swim", "뒤로 걷기", "moonwalk",
        "로봇 댄스", "robotdance", "훌라", "hula", "노래하기", "singsong", "랄랄라", "singsong", "마술 쇼", "magic", "닌자 놀이", "ninja",
        "숨기", "hideseek", "비행기 놀이", "airplane", "발레리나", "ballerina", "종이비행기 날리기", "paperplane", "연", "kite", "독서", "reading",
        "커피 마시기", "coffeebreak", "쉬는 시간", "coffeebreak", "구름 보기", "cloudwatch", "기립 박수", "applause", "안녕", "goodbye", "바이바이", "goodbye",
        "손짓", "comehere", "영웅 놀이", "superhero", "프로펠러", "helicopter", "드럼 치기", "drummer", "지휘자", "orchestra", "오케스트라", "orchestra",
        "푸시업", "pushups", "기지개", "morningstretch", "그네 타기", "swing", "시소 타기", "seesawride", "원반", "frisbee", "프리스비", "frisbee",
        "반딧불이", "fireflies", "꽃가루", "confetti", "빵빠레", "confetti", "눈송이 잡기", "snowplay", "낙엽 놀이", "leafcatch", "간식", "snacktime",
        "냠냠", "snacktime", "배꼽 잡기", "laughfit", "깔깔", "laughfit", "삐지기", "tantrum", "더듬이", "antennadance", "축구", "kickball",
        "주사위 던지기", "rolldice", "공 튀기기", "ball", "공놀이", "ball", "나비 잡기", "butterfly", "비눗방울 불기", "bubbles", "비눗방울 놀이", "bubbles",
        "까꿍", "peek", "까꿍 놀이", "peek", "시계 보기", "clock", "시계 놀이", "clock", "팔 벌려 뛰기", "jacks", "치어리더", "cheerleader",
        "응원 춤", "cheerleader", "록스타", "rockstar", "로큰롤", "rockstar", "회오리", "tornado", "회오리 돌기", "tornado", "데굴데굴", "rolling",
        "구르기", "rolling", "풍선 타기", "balloon", "둥실둥실", "balloon", "풍선껌", "gum", "풍선껌 불기", "gum", "앞구르기", "tumbling",
        "재주넘기", "tumbling", "재채기", "sneezing", "에취", "sneezing", "녹아내리기", "melting", "흐물흐물", "melting", "손뼉 치기", "clapping",
        "짝짝짝", "clapping", "스카이 콩콩", "pogostick", "콩콩 뛰기", "pogostick", "젤리 놀이", "jello", "말랑말랑", "jello", "어깨춤", "shimmydance",
        "흔들흔들 춤", "shimmydance", "몰래 덮치기", "sneakattack", "살금살금", "sneakattack", "마라톤", "marathon", "달리기", "marathon", "게걸음", "sidestep",
        "옆걸음", "sidestep", "두근두근", "crush", "콩닥콩닥", "crush", "풍선 불기", "balloonpop", "풍선 터뜨리기", "balloonpop", "작아지기", "tinyme",
        "콩알만 해지기", "tinyme", "벌러덩", "rollover", "뒹굴뒹굴", "rollover", "흔들의자", "rockingchair", "공중 부양", "hover", "둥둥 뜨기", "hover",
        "모래 늪", "quicksand", "늪에 빠지기", "quicksand", "어이쿠", "oops", "헛디디기", "oops", "바나나 껍질", "bananaslip", "미끄러지기", "bananaslip",
        "덜덜 떨기", "brrr", "오들오들", "brrr", "탐험", "explorer", "탐험 놀이", "explorer", "그러거나 말거나", "whatever", "시큰둥", "whatever",
        "하늘 보기", "skygaze", "하늘 구경", "skygaze", "부끄부끄", "shytime", "부끄럼 타기", "shytime", "킥킥", "chuckles", "킥킥 웃기", "chuckles",
        "슬픈 영화", "sadmovie", "훌쩍훌쩍", "sadmovie", "팔 돌리기", "swingarms", "팔 휘휘", "swingarms", "새 흉내", "birdie", "파닥파닥", "birdie",
        "셀프 허그", "selfhug", "토닥토닥", "selfhug", "챔피언", "champion", "우승 세리머니", "champion", "비밀 이야기", "secret", "쉿", "secret",
        "갸우뚱", "puzzled", "뭐였더라", "puzzled", "레이더", "radar", "레이더 놀이", "radar", "흐느적", "noodle", "국수 흉내", "noodle",
        "지그재그 달리기", "zigzagrun", "지그재그", "zigzagrun", "어질어질", "dizzyorbit", "궤도 돌기", "dizzyorbit", "상상하기", "daydream", "꿈꾸기", "daydream",
        "주사위 놀이", "dice", "큐브 맞추기", "puzzlecube", "퍼즐 맞추기", "puzzlecube", "팽이 돌리기", "spintop", "팽이치기", "spintop", "풍선 치기", "balloonbat",
        "풍선 통통", "balloonbat", "나비 구경", "flutter", "나비 친구", "flutter", "별똥별 구경", "meteors", "유성우", "meteors", "트리 꾸미기", "decorate",
        "트리 장식하기", "decorate", "트리 구경", "admire", "트리 불빛 보기", "admire", "썰매 타기", "sleighride", "루돌프 썰매", "sleighride", "오리 놀이", "rubberduck",
        "삑삑 오리", "rubberduck", "자동차 놀이", "toycar", "부릉부릉", "toycar", "로켓 발사", "rocket", "로켓 놀이", "rocket", "북 치기", "drumroll",
        "둥둥둥", "drumroll", "오르골 듣기", "musicbox", "오르골 틀기", "musicbox", "비치볼 놀이", "beachball", "비치볼 치기", "beachball", "구슬치기", "marbles",
        "구슬 놀이", "marbles", "블록 쌓기", "blocks", "블록 놀이", "blocks");

    private static Map<String, String> map(String... kv) { Map<String, String> m = new LinkedHashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]); return m; }
    static List<String> words(Map<String, String> m) { return List.copyOf(m.keySet()); }
    /** A few of the names (one per thing), for the brain to see the kind of words that work. */
    static String sample(Map<String, String> m, int most) {
        java.util.LinkedHashMap<String, String> one = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : m.entrySet()) if (!e.getValue().isEmpty() && !one.containsKey(e.getValue())) one.put(e.getValue(), e.getKey());
        List<String> w = new java.util.ArrayList<>(one.values());
        return String.join(", ", w.subList(0, Math.min(most, w.size()))) + (w.size() > most ? " 등 " + w.size() + "가지" : "");
    }

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
        // The most specific name inside what was said ("빨간 산타 모자" → 산타 모자, not 모자), else the shortest name holding it.
        String best = null; int len = 0;
        for (Map.Entry<String, String> e : m.entrySet()) { String k = e.getKey().replace(" ", ""); if (k.length() >= 2 && bare.contains(k) && k.length() > len) { best = e.getValue(); len = k.length(); } }
        if (best != null) return best;
        len = Integer.MAX_VALUE;
        for (Map.Entry<String, String> e : m.entrySet()) { String k = e.getKey().replace(" ", ""); if (bare.length() >= 2 && k.contains(bare) && k.length() < len) { best = e.getValue(); len = k.length(); } }
        return best;
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
