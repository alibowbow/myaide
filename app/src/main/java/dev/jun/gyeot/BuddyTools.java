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
        .put(fn("settings", "설정 화면을 연다.", props("panel", new String[]{"internet", "wifi", "bluetooth", "volume", "nfc", "display", "location", "battery"}, "어느 설정"), "panel"))
        .put(fn("search", "인터넷에서 검색한다.", props("query", "string", "검색어"), "query"))
        // 앱이 열어 둔 공식 길 (준: "4번")
        .put(fn("share", "카카오톡 같은 앱으로 글을 보낸다(받는 친구는 사용자가 고른다).", props("text", "string", "보낼 글", "app", "string", "앱 이름 (예: 카카오톡), 말하지 않았으면 빼기"), "text"))
        .put(fn("message", "문자 메시지를 써 둔다(보내기는 사용자가 누른다).", props("who", "string", "받는 사람 이름", "number", "string", "전화번호를 말했으면", "text", "string", "보낼 글"), "text"))
        .put(fn("call", "전화 앱에 번호를 눌러 둔다(통화는 사용자가 누른다).", props("who", "string", "받는 사람 이름", "number", "string", "전화번호를 말했으면")))
        .put(fn("camera", "카메라를 연다.", props("mode", new String[]{"photo", "selfie", "video"}, "사진·셀카·동영상")))
        .put(fn("calendar_add", "캘린더에 새 일정을 채워 둔다(저장은 사용자가 누른다).",
            props("title", "string", "일정 이름", "date", "string", "오늘·내일·모레 또는 YYYY-MM-DD", "hour", "integer", "시 (0~23), 시간을 말했으면", "minute", "integer", "분", "minutes", "integer", "길이(분)", "place", "string", "장소"), "title"))
        .put(fn("open_url", "웹 페이지를 연다.", props("url", "string", "주소 (예: naver.com)"), "url"))
        .put(fn("map_search", "지도에서 장소를 찾는다(근처 카페, 맛집 …).", props("query", "string", "찾을 것"), "query"));

    /** The rule for the brain with tools (in place of BuddyTalk.ACTS). */
    static final String RULE = "- 사용자가 휴대폰에서 할 일을 부탁하면(앱 열기, 길 안내, 지도에서 찾기, 노래·영상 찾아 틀기, 음악 멈춤·다음 곡, 알람, 타이머, 손전등, 화면 방향, 소리 크기, 밝기, 설정 화면, 검색, 카톡으로 보내기, 문자 쓰기, 전화 걸 준비, 카메라, 일정 넣기, 웹 페이지 열기) 도구를 불러서 너가 직접 해. "
        + "도구를 부를 때도 무엇을 하는지 짧은 대답 한 문장을 같이 말해(예: 네이버 지도 열게!). 할 일을 글로 적거나 JSON을 말하지 마. "
        + "결제·송금처럼 도구가 없는 일은 아직 못 한다고 말해. 대답에 주소(URL)와 이모지는 절대 쓰지 마.\n";

    /** The first call as a BuddyActs act ({"tool": name, …arguments}), its name made one of ours; null when there is none. */
    static JSONObject act(JSONArray calls) {
        for (int i = 0; calls != null && i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i); if (call == null) continue;
            String name = call.optString("name").strip(), args = call.optString("arguments").strip();
            JSONObject a = BuddyTalk.loose("{\"tool\":" + JSONObject.quote(name) + (args.length() > 2 && args.startsWith("{") ? "," + args.substring(1) : "}"));
            if (a == null) { try { a = new JSONObject(args.isEmpty() ? "{}" : args).put("tool", name); a = BuddyTalk.loose(a.toString()); } catch (Exception broken) { a = null; } }
            if (a != null) return a;
        }
        return null;
    }

    private static final java.util.regex.Pattern ASKED = java.util.regex.Pattern.compile("열어|켜 ?줘|켜$|꺼 ?줘|꺼$|틀어|재생|알람|타이머|가자|안내|길 ?찾|찾아 ?줘|검색|소리|볼륨|밝기|화면|손전등|플래시|와이파이|블루투스|설정|멈춰|다음 ?곡|이전 ?곡|가로|세로|회전|일정|캘린더|넣어|셀카|카메라|사진 ?찍|동영상|문자|보내|전화|카톡|카카오톡|공유|사이트|홈페이지|근처|맛집"),
        PROMISE = java.util.regex.Pattern.compile("할게|줄게|열게|틀게|켤게|끌게|맞출게|찾아볼게|바꿀게|멈출게|둘게|보낼게|걸게|찍게|넣을게|시작");
    /** The user asked for a thing on the phone and the answer says it will be done (the brain then called no tool). */
    static boolean promised(String said, String answer) {
        return said != null && answer != null && ASKED.matcher(said).find() && PROMISE.matcher(answer).find();
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
            default -> "";
        };
    }
}
