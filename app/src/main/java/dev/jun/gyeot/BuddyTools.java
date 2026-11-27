package dev.jun.gyeot;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 퍼레's things to do on the phone as tools the brain calls (OpenAI function calling on Groq), not a line of text it writes
 * and 퍼레 reads back (준: 퍼레가 "{tool:openapp,app:YouTube}"를 그대로 외쳤다; "2번 진행"). Each tool is one BuddyActs act:
 * the call becomes {"tool": name, …its arguments}, and when the brain called a tool without words, 퍼레 says a short line
 * for it (say). The text form (BuddyTalk.ACTS, "할일: {…}") stays for brains without tools.
 */
final class BuddyTools {
    private BuddyTools() {}

    private static JSONObject fn(String name, String description, JSONObject properties, String... required) {
        try {
            JSONObject params = new JSONObject().put("type", "object").put("properties", properties == null ? new JSONObject() : properties);
            if (required.length > 0) params.put("required", new JSONArray(java.util.List.of(required)));
            return new JSONObject().put("type", "function").put("function", new JSONObject().put("name", name).put("description", description).put("parameters", params));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static JSONObject props(Object... nameTypeDescription) {
        JSONObject p = new JSONObject();
        try {
            for (int i = 0; i + 2 < nameTypeDescription.length; i += 3) {
                String name = (String) nameTypeDescription[i]; Object type = nameTypeDescription[i + 1]; String about = (String) nameTypeDescription[i + 2];
                JSONObject one = new JSONObject().put("description", about);
                if (type instanceof String[] choices) one.put("type", "string").put("enum", new JSONArray(java.util.List.of(choices)));
                else one.put("type", type);
                p.put(name, one);
            }
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return p;
    }

    /** The tools, as Groq takes them. */
    static final JSONArray TOOLS = new JSONArray()
        .put(fn("open_app", "휴대폰에 깔린 앱을 연다.", props("app", "string", "앱 이름 (예: 카카오톡, 네이버 지도, YouTube)"), "app"))
        .put(fn("navigate", "목적지까지 길안내를 시작한다(네이버 지도). 저장된 자주 가는 곳은 그 부르는 말 그대로 쓴다.",
            props("destination", "string", "목적지: 부르는 말(집, 회사), 장소 이름이나 주소", "mode", new String[]{"car", "public", "walk", "bicycle"}, "이동 수단, 말하지 않았으면 빼기"), "destination"))
        .put(fn("play", "유튜브·유튜브 뮤직·멜론·스포티파이에서 노래나 영상을 찾아 튼다.",
            props("query", "string", "찾을 노래·영상 (예: 아이유 신곡)", "app", "string", "앱 이름, 말하지 않았으면 빼기"), "query"))
        .put(fn("media", "지금 나오는 음악·영상을 조작한다.", props("command", new String[]{"play", "pause", "next", "previous"}, "할 일"), "command"))
        .put(fn("alarm", "알람을 맞춘다.", props("hour", "integer", "시 (0~23)", "minute", "integer", "분"), "hour"))
        .put(fn("timer", "타이머를 시작한다.", props("seconds", "integer", "몇 초"), "seconds"))
        .put(fn("torch", "손전등을 켜거나 끈다.", props("on", "boolean", "켜기 true, 끄기 false"), "on"))
        .put(fn("rotation", "화면 방향: 가로 고정, 세로 고정, 자동 회전, 반대로.", props("mode", new String[]{"landscape", "portrait", "auto", "toggle"}, "방향"), "mode"))
        .put(fn("volume", "미디어 소리 크기를 바꾼다.", props("direction", new String[]{"up", "down", "mute", "unmute"}, "올리기·내리기·음소거·음소거 풀기", "level", "integer", "0~100으로 정할 때")))
        .put(fn("brightness", "화면 밝기를 바꾼다.", props("direction", new String[]{"up", "down"}, "밝게·어둡게", "level", "integer", "0~100으로 정할 때")))
        .put(fn("show_alarms", "맞춰 둔 알람 목록을 보여 준다.", null))
        .put(fn("settings", "설정 화면을 연다(방해 금지, 절전, 화면 꺼짐 시간, 알림 …). 바꾸는 건 사용자가 한다.", props("panel", dev.jun.gyeot.core.DeviceActions.PANEL_NAMES.toArray(new String[0]),
            "어느 설정: dnd=방해 금지, battery=절전 모드, timeout=화면 꺼짐 시간, night=블루라이트, notifications=알림, apps=앱 목록, rotate=자동 회전, data=데이터 사용, cast=화면 공유"), "panel"))
        .put(fn("search", "인터넷에서 검색한다.", props("query", "string", "검색어"), "query"))
        // 앱이 열어 둔 공식 길 (준: "4번")
        .put(fn("share", "카카오톡 같은 앱으로 글을 보낸다(받는 친구는 사용자가 고른다).", props("text", "string", "보낼 글", "app", "string", "앱 이름 (예: 카카오톡), 말하지 않았으면 빼기"), "text"))
        .put(fn("message", "문자 메시지를 써 둔다(보내기는 사용자가 누른다).", props("who", "string", "받는 사람 이름", "number", "string", "전화번호를 말했으면", "text", "string", "보낼 글"), "text"))
        .put(fn("call", "전화 앱에 번호를 눌러 둔다(통화는 사용자가 누른다).", props("who", "string", "받는 사람 이름", "number", "string", "전화번호를 말했으면")))
        .put(fn("camera", "카메라를 연다.", props("mode", new String[]{"photo", "selfie", "video"}, "사진·셀카·동영상")))
        .put(fn("calendar_add", "캘린더에 새 일정을 채워 둔다(저장은 사용자가 누른다).",
            props("title", "string", "일정 이름", "date", "string", "오늘·내일·모레 또는 YYYY-MM-DD", "hour", "integer", "시 (0~23), 시간을 말했으면", "minute", "integer", "분", "minutes", "integer", "길이(분)", "place", "string", "장소"), "title"))
        .put(fn("open_url", "웹 페이지를 연다.", props("url", "string", "주소 (예: naver.com)"), "url"))
        .put(fn("map_search", "지도에서 장소를 찾는다(근처 카페, 맛집 …).", props("query", "string", "찾을 것"), "query"))
        // 더 많은 공식 길 (2026-11-26): the clock's timers, the calendar's day, a memo, a recorder, an app's info, files, photos, people, mail, a translator
        .put(fn("show_timers", "시계 앱의 타이머 화면을 보여 준다.", null))
        .put(fn("calendar_view", "캘린더 앱에서 그날 일정을 보여 준다(오늘 일정, 다음 주 금요일 일정 …).", props("date", "string", "오늘·내일·모레 또는 YYYY-MM-DD, 말하지 않았으면 빼기")))
        .put(fn("memo", "메모 앱(삼성 노트, Google Keep …)에 새 메모를 써 둔다(저장은 그 앱에서 사용자가).",
            props("text", "string", "메모할 글", "title", "string", "메모 제목, 말했으면", "app", "string", "앱 이름, 말하지 않았으면 빼기"), "text"))
        .put(fn("recorder", "음성 녹음 앱을 연다.", null))
        .put(fn("app_settings", "어떤 앱의 정보 화면(알림·권한·저장공간)을 연다. 바꾸는 건 사용자가 한다.", props("app", "string", "앱 이름 (예: 카카오톡)"), "app"))
        .put(fn("downloads", "받은 파일(다운로드) 목록을 연다.", null))
        .put(fn("gallery", "사진·동영상 갤러리를 연다.", null))
        .put(fn("contacts", "연락처 앱을 연다. 사람 이름을 말하면 그 사람 연락처를 연다.", props("who", "string", "사람 이름, 말했으면")))
        .put(fn("calculator", "계산해서 답을 말해 준다(곱하기, 퍼센트, 나눠 내기 …). 식이 없으면 계산기 앱을 연다.", props("expression", "string", "계산식 (예: 35000*0.15, (12000+8000)/3, 3만5천*3)")))
        .put(fn("email", "메일 앱에 새 메일을 써 둔다(보내기는 사용자가 누른다).",
            props("to", "string", "받는 메일 주소, 말했으면", "who", "string", "받는 사람 이름, 말했으면", "subject", "string", "제목", "text", "string", "내용")))
        .put(fn("translate", "번역 앱(파파고·Google 번역)에서 글을 번역해 보여 준다. 짧은 말은 너가 바로 말해 줘도 돼.",
            props("text", "string", "번역할 글", "to", "string", "어느 말로 (en, ja, zh-CN, ko, es, fr, de, vi …), 말하지 않았으면 빼기"), "text"))
        // 앱 없이 퍼레가 직접 (답은 도구가 정한다: 지어내지 않게)
        .put(fn("stopwatch", "너(퍼레)의 스톱워치: 시작, 지금 몇 초째인지, 멈추기.", props("command", new String[]{"start", "check", "stop"}, "시작·지금 몇 초째·멈추기"), "command"))
        .put(fn("coin", "동전을 던져 앞면·뒷면을 정한다(결과는 도구가 정한다).", null))
        .put(fn("dice", "말로 주사위를 굴려 숫자를 정한다(결과는 도구가 정한다). 홈 화면에 주사위 장난감을 꺼내 달라면 buddy_toy.",
            props("count", "integer", "주사위 개수(1~5), 말하지 않았으면 빼기", "sides", "integer", "면 수, 말하지 않았으면 빼기")))
        .put(options())
        .put(fn("world_time", "다른 도시·나라의 지금 시각과 시차를 알려 준다.", props("place", "string", "도시나 나라 (예: 뉴욕, 파리)", "zone", "string", "IANA 시간대를 알면 (예: America/New_York)"), "place"))
        .put(fn("count_days", "그날까지 며칠 남았는지, 그날부터 며칠 지났는지, 며칠 뒤·전이 언제인지 센다.",
            props("date", "string", "YYYY-MM-DD, 오늘, 또는 크리스마스 같은 날 이름", "label", "string", "그날의 이름 (예: 여행, 시험), 말했으면", "add_days", "integer", "며칠 뒤(전이면 음수)가 언제냐고 물을 때 그 날 수"), "date"))
        // 퍼레 itself (준: "음성대화로 퍼레의 상호작용이 안돼"): its toys, its clothes, its plays on the home screen
        // Hundreds of names (준: "시킬 수 있는 목록 … 아직 부족"): the brain says it in its own words, the phone finds the name (BuddyBoard.find).
        .put(fn("buddy_toy", "너(퍼레)의 장난감을 홈 화면에 꺼내서 갖고 논다. 다 치우려면 치우기.", props("toy", "string", "장난감 이름 (예: " + BuddyBoard.sample(BuddyBoard.TOYS, 14) + ")"), "toy"))
        .put(outfit())
        .put(fn("buddy_move", "너(퍼레)가 홈 화면에서 놀이·동작을 한다(춤, 점프, 요가 …).", props("play", "string", "놀이 이름 (예: " + BuddyBoard.sample(BuddyBoard.PLAYS, 16) + ")"), "play"))
        .put(fn("buddy_wish", "너(퍼레)가 갖고 싶은데 아직 없는 것을 소원 목록에 적는다(사용자가 만들어 줄 수도 있어).", props("thing", "string", "갖고 싶은 것 (예: 트램펄린)"), "thing"))
        // 퍼레 itself, asked by the user: what to keep in mind or let go, what to call them, a quiet while
        .put(fn("remember", "사용자가 기억해 달라고 한 것을 너(퍼레)의 기억에 적어 둔다(예: 차는 지하 2층에 세웠어).",
            props("fact", "string", "기억할 것을 '사용자는 …' 한 문장으로, 45자 안쪽", "private", "boolean", "남이 알면 곤란한 이야기(고민·걱정)면 true"), "fact"))
        .put(fn("forget", "사용자가 잊어 달라고 하거나 '그건 틀렸어 지워'라고 한 너(퍼레)의 기억을 지운다. 방금 잊은 걸 되돌려 달라면 undo.",
            props("what", "string", "잊을 기억의 핵심 말 (예: 고양이, 이직 고민)", "undo", "boolean", "방금 잊은 걸 되돌릴 때 true")))
        .put(fn("call_me", "너(퍼레)가 사용자를 부르는 호칭을 바꾼다.", props("title", "string", "부를 호칭 (예: 주인공님, 형)"), "title"))
        .put(fn("quiet", "너(퍼레)가 홈 화면에서 먼저 말을 걸지 않고 조용히 있는다(부르면 대답은 해). 다시 말 걸어도 되면 0.", props("hours", "number", "몇 시간 (0~12), 말하지 않았으면 빼기")));
    /** pick: one of the choices said (an array the props helper does not make). */
    private static JSONObject options() {
        try {
            JSONObject list = new JSONObject().put("type", "array").put("description", "고를 것들 (예: 짜장면, 짬뽕)").put("items", new JSONObject().put("type", "string"));
            return fn("pick", "몇 가지 중에서 하나를 골라 준다(정해 줘, 골라 줘). 결과는 도구가 정한다.", new JSONObject().put("options", list), "options");
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    /** buddy_outfit: one or more things to wear (an array the props helper does not make). */
    private static JSONObject outfit() {
        try {
            JSONObject items = new JSONObject().put("type", "array").put("description", "입을 것(여러 개 가능). 기본=다 벗고 원래 모습, 아무거나=퍼레가 골라 입기. 예: " + BuddyBoard.sample(BuddyBoard.CLOTHES, 16))
                .put("items", new JSONObject().put("type", "string"));
            return fn("buddy_outfit", "너(퍼레)의 옷·모자·소품을 갈아입는다.", new JSONObject().put("items", items), "items");
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * 퍼레's own tools (준: "퍼레의 판단력"): its toys, clothes, plays and wishes, and what the user asks of it (remember, forget,
     * what to call them, a quiet while). Sent only when the words are about 퍼레 (self), so most turns weigh less; a coin, dice,
     * a pick, a sum, another city's time and the days to a day need no app but are answers, sent with the phone's.
     */
    static final java.util.Set<String> OWN = java.util.Set.of("buddy_toy", "buddy_outfit", "buddy_move", "buddy_wish", "remember", "forget", "call_me", "quiet");
    /** The tools sent every turn (all but 퍼레's own), and 퍼레's own (OWN). */
    static final JSONArray PHONE = only(false), SELF = only(true);
    private static JSONArray only(boolean own) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < TOOLS.length(); i++) {
            JSONObject t = TOOLS.optJSONObject(i);
            if (OWN.contains(t.optJSONObject("function").optString("name")) == own) out.put(t);
        }
        return out;
    }
    /**
     * The answer to a forced retry when there is nothing to do after all (a question, small talk): the brain may say so
     * rather than make up a tool (준: "내일 일정 뭐야" was forced into one). Never an act.
     */
    static final JSONObject NOTHING = fn("nothing", "부탁받은 할 일이 없을 때(질문·잡담) 부른다.", null);
    /** The tools for what was said: the phone's, and 퍼레's own too when the words are about 퍼레 (self). */
    static JSONArray forTurn(String said) { return self(said) ? TOOLS : PHONE; }
    /** The tools for a retry that must call one: all of them, and NOTHING. */
    static JSONArray orNothing() {
        JSONArray out = new JSONArray();
        for (int i = 0; i < TOOLS.length(); i++) out.put(TOOLS.opt(i));
        return out.put(NOTHING);
    }
    /** Names of 퍼레's things that are also the phone's (손전등 켜 줘, 카메라, 노래 틀어 줘): they alone do not make the words about 퍼레. */
    private static final java.util.Set<String> SHARED = java.util.Set.of("손전등", "플래시", "카메라", "사진기", "지도", "노래", "비행기", "정리", "안녕", "바이바이", "마이크",
        "헤드폰", "헤드셋", "커피", "운동", "축구", "수영", "간식", "우산", "도시락", "독서");
    /** What is asked of 퍼레 itself, without spaces: to wear, to take out or put away, to play, to remember, forget, call or be quiet. */
    private static final java.util.regex.Pattern ABOUT_SELF = java.util.regex.Pattern.compile("입어|입자|입을래|입혀|벗어|갈아입|옷|모자|안경|꺼내|치워|장난감|놀자|놀아|놀이|해봐|보여줘|춤|노래(?:해|불러)|소원"
        + "|퍼레(?:야)?(?:너|넌|네가)|기억해|기억해줘|기억해둬|잊어|잊지|지워|까먹어|틀렸|되돌려|(?:이)?라고(?:불러|부를|해줘)|부르지마|호칭|조용히|말걸지|말걸어|말시키지");
    /** Whether said is about 퍼레 itself: one of its things by name (two letters or more), or a thing asked of it. A wrong yes costs only a few tokens. */
    static boolean self(String said) {
        if (said == null) return false;
        String bare = said.replaceAll("[\\s\\p{Punct}·…~]+", "");
        if (bare.isEmpty()) return false;
        if (ABOUT_SELF.matcher(bare).find()) return true;
        for (java.util.Map<String, String> things : java.util.List.of(BuddyBoard.TOYS, BuddyBoard.CLOTHES, BuddyBoard.PLAYS))
            for (String name : things.keySet()) { String k = name.replace(" ", ""); if (k.length() >= 2 && !SHARED.contains(k) && bare.contains(k)) return true; }
        return false;
    }

    /** The rule for the brain with tools (in place of BuddyTalk.ACTS). */
    static final String RULE = rule(true);
    /** The rule for the brain with the tools sent: 퍼레's own named only when they are (else it might call one not there). */
    static String rule(boolean self) {
        return "- 사용자가 휴대폰에서 할 일을 부탁하면(앱 열기, 길 안내, 지도에서 찾기, 노래·영상 찾아 틀기, 음악 멈춤·다음 곡, 알람, 타이머, 타이머 화면, 스톱워치, 손전등, 화면 방향, 소리 크기, 밝기, 방해 금지·절전 같은 설정 화면, 앱 정보 화면, 검색, 카톡으로 보내기, 문자 쓰기, 메일 쓰기, 전화 걸 준비, 카메라, 갤러리, 연락처, 녹음, 메모, 다운로드, 일정 넣기·보기, 웹 페이지 열기, 번역) 도구를 불러서 너가 직접 해. "
        + "동전 던지기, 주사위, 골라 주기, 계산, 다른 나라 시각, 며칠 남았는지 세기는 답을 지어내지 말고 꼭 coin·dice·pick·calculator·world_time·count_days 도구를 불러. 답은 도구가 말해 주니 너는 '던져 볼게!'처럼 짧게만 말해. "
        + (self ? "너(퍼레) 자신에게 하는 부탁(장난감 꺼내기, 옷 갈아입기·벗기, 춤·점프 같은 놀이)도 buddy_toy·buddy_outfit·buddy_move 도구로 직접 해. 사용자가 '기억해 둬', '잊어 줘', '그건 틀렸어 지워'라고 하면 remember·forget을 불러(방금 잊은 걸 되돌려 달라면 forget에 undo). 이렇게 불러 달라·조용히 있어 달라는 부탁은 call_me·quiet 도구로 해. 못 한다고 하지 마. " : "")
        + "도구를 부를 때도 무엇을 하는지 짧은 대답 한 문장을 같이 말해(예: 네이버 지도 열게!). 할 일을 글로 적거나 JSON을 말하지 마. "
        + "알람·타이머는 짧게 '알겠어!'만 말하고 alarm·timer 도구를 불러(시각·길이는 도구가 확인해서 다시 말해 줘). "
        + "사용자가 '아니', '그거 말고', '다른 거'라고 고치면 방금 한 일을 같은 도구로 고친 값으로 다시 불러. "
        + "결제·송금처럼 도구가 없는 일은 아직 못 한다고 말해. 대답에 주소(URL)는 절대 쓰지 마. 이모지나 ^^·ㅎㅎ·ㅠㅠ·(๑˃ᴗ˂)ﻭ 같은 텍스트 이모티콘는 어울리면 하나까지(반짝이·별은 빼고).\n";
    }

    /** The first call as a BuddyActs act ({"tool": name, …arguments}), its name made one of ours; null when there is none. */
    static JSONObject act(JSONArray calls) {
        for (int i = 0; calls != null && i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i); if (call == null) continue;
            String name = call.optString("name").strip(), args = call.optString("arguments").strip();
            if (name.equals("nothing")) continue; // nothing to do (NOTHING): no act
            JSONObject a = BuddyTalk.loose("{\"tool\":" + JSONObject.quote(name) + (args.length() > 2 && args.startsWith("{") ? "," + args.substring(1) : "}"));
            if (a == null) { try { a = new JSONObject(args.isEmpty() ? "{}" : args).put("tool", name); a = BuddyTalk.loose(a.toString()); } catch (Exception broken) { a = null; } }
            if (a != null) return a;
        }
        return null;
    }

    private static final java.util.regex.Pattern ASKED = java.util.regex.Pattern.compile("꺼내|입어|입자|벗어|갈아입|옷|모자|장난감|주사위|공 ?놀|큐브|팽이|풍선|비눗방울|춤|점프|해 ?봐|보여 ?줘|열어|켜 ?줘|켜$|꺼 ?줘|꺼$|틀어|재생|알람|타이머|가자|안내|길 ?찾|찾아 ?줘|검색|소리|볼륨|밝기|화면|손전등|플래시|와이파이|블루투스|설정|멈춰|다음 ?곡|이전 ?곡|가로|세로|회전|일정|캘린더|넣어|셀카|카메라|사진 ?찍|동영상|문자|보내|전화|카톡|카카오톡|공유|사이트|홈페이지|근처|맛집"
            + "|스톱워치|초 ?재|시간 ?재|메모|적어|녹음|다운로드|받은 ?파일|갤러리|사진첩|앨범|연락처|계산|더하기|빼기|곱하기|나누기|퍼센트|메일|번역|동전|골라|정해 ?줘|며칠|디데이|D-?day|시차|몇 ?시야"
            + "|기억해 ?줘|기억해 ?둬|기억해$|잊어 ?줘|잊어버려|까먹어|지워 ?줘|틀렸어|되돌려|(이라고|라고) ?불러|부르지 ?마|호칭|조용히|말 ?걸지"),
        PROMISE = java.util.regex.Pattern.compile("꺼낼게|입을게|벗을게|쓸게|출게|뛸게|놀게|해 볼게|할게|줄게|열게|틀게|켤게|끌게|맞출게|찾아볼게|바꿀게|멈출게|둘게|보낼게|걸게|찍게|넣을게|시작"
            + "|잴게|적을게|던질게|던져 볼게|굴릴게|굴려 볼게|고를게|골라 볼게|세어 볼게|셀게|계산해 볼게|번역해 볼게|기억할게|잊을게|지울게|잊었어|지웠어|되돌릴게|라고 부를게|조용히 있을게"
            + "|놓을게|올릴게|줄일게|낮출게|높일게|해 둘게|맞춰 둘게|틀어 줄게|찾아 줄게|알겠어|알았어");
    /** Whether the brain's calls chose NOTHING (there was nothing to do after all). */
    static boolean nothing(JSONArray calls) {
        for (int i = 0; calls != null && i < calls.length(); i++) { JSONObject c = calls.optJSONObject(i); if (c != null && "nothing".equals(c.optString("name").strip())) return true; }
        return false;
    }
    /** The user asked for a thing on the phone and the answer says it will be done (the brain then called no tool). */
    static boolean promised(String said, String answer) {
        return said != null && answer != null && ASKED.matcher(said).find() && PROMISE.matcher(answer).find();
    }
    private static String outfitSay(JSONObject a) {
        JSONArray items = a.optJSONArray("items"); String first = items != null && items.length() > 0 ? items.optString(0).strip() : a.optString("item").strip();
        if (first.isEmpty() || first.equals("기본")) return "원래 모습으로 돌아갈게!";
        if (first.equals("아무거나")) return "뭐 입을지 골라 볼게!";
        return first + " 해 볼게!";
    }
    /** "이제 ‘주인공님’이라고 부를게!" ("" for no title). */
    static String called(String title) {
        if (title == null || title.isBlank()) return "";
        return "이제 ‘" + title + "’" + dev.jun.gyeot.core.Brand.josa(title, "이라고", "라고").substring(title.length()) + " 부를게!";
    }
    /** What 퍼레 says for an act the brain did without words ("" when BuddyActs says it after: an alarm's read-back …). */
    static String say(JSONObject a) {
        if (a == null) return "";
        String tool = a.optString("tool");
        return switch (tool) {
            case "open_app" -> a.optString("app").isBlank() ? "열게!" : a.optString("app").strip() + " 열게!";
            case "navigate" -> a.optString("destination").isBlank() ? "길 안내할게!" : dev.jun.gyeot.core.Brand.euro(a.optString("destination").strip()) + " 안내할게!";
            case "play" -> a.optString("query").isBlank() ? "틀어 줄게!" : a.optString("query").strip() + " 틀어 줄게!";
            case "media" -> switch (a.optString("command")) { case "pause" -> "잠깐 멈출게!"; case "next" -> "다음 곡!"; case "previous" -> "이전 곡!"; default -> "다시 틀게!"; };
            case "torch" -> a.optBoolean("on", true) ? "손전등 켤게!" : "손전등 끌게!";
            case "volume" -> "소리 바꿀게!";
            case "brightness" -> "밝기 바꿀게!";
            case "show_alarms" -> "알람 보여 줄게!";
            case "settings" -> "설정 열게!";
            case "search", "map_search" -> a.optString("query").isBlank() ? "찾아볼게!" : "‘" + a.optString("query").strip() + "’ 찾아볼게!";
            case "share" -> (a.optString("app").isBlank() ? "" : a.optString("app").strip() + "으로 ") + "보낼 준비 할게!";
            case "message" -> "문자 써 둘게!";
            case "camera" -> "selfie".equals(a.optString("mode")) ? "셀카 모드!" : "video".equals(a.optString("mode")) ? "동영상 찍자!" : "카메라 열게!";
            case "open_url" -> "열게!";
            case "buddy_toy" -> "치우기".equals(a.optString("toy")) ? "다 치울게!" : a.optString("toy").isBlank() ? "장난감 꺼낼게!" : a.optString("toy").strip() + " 꺼낼게!";
            case "buddy_outfit" -> outfitSay(a);
            case "buddy_move" -> a.optString("play").isBlank() ? "볼래? 짠!" : a.optString("play").strip() + " 보여 줄게!";
            case "buddy_wish" -> a.optString("thing").isBlank() ? "" : a.optString("thing").strip() + ", 소원 목록에 적어 둘게!";
            case "show_timers" -> "타이머 보여 줄게!";
            case "calendar_view" -> "일정 보여 줄게!";
            case "memo" -> "메모해 둘게!";
            case "recorder" -> "녹음 앱 열게!";
            case "app_settings" -> a.optString("app").isBlank() ? "앱 정보 열게!" : a.optString("app").strip() + " 정보 열게!";
            case "downloads" -> "받은 파일 보여 줄게!";
            case "gallery" -> "사진 보여 줄게!";
            case "contacts" -> a.optString("who").isBlank() ? "연락처 열게!" : a.optString("who").strip() + " 연락처 찾아볼게!";
            case "calculator" -> a.optString("expression").isBlank() ? "계산기 열게!" : "계산해 볼게!";
            case "email" -> "메일 써 둘게!";
            case "translate" -> "번역해 볼게!";
            // The answer itself is the act's own words, said after these (BuddyActs: coin, dice, pick, the time, the days).
            case "stopwatch" -> "start".equals(a.optString("command", "start")) ? "스톱워치 시작!" : "";
            case "coin" -> "동전 던진다!";
            case "dice" -> "주사위 굴린다!";
            case "pick" -> "음… 골라 볼게!";
            case "world_time", "count_days" -> "";
            case "remember" -> "기억해 둘게!";
            case "forget" -> "";
            case "call_me" -> called(dev.jun.gyeot.core.BuddyTricks.title(a.optString("title")));
            case "quiet" -> a.has("hours") && a.optDouble("hours", 1) <= 0 ? "다시 말 걸게!" : "조용히 있을게.";
            default -> "";
        };
    }
}
