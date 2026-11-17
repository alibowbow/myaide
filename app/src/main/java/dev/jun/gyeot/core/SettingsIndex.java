package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 설정's search: one entry per page, the settings people look for by name, and guides to what
 * lives outside 설정. find ranks a title that starts with what was typed over a title that contains it, then the path,
 * then another word for it; inside a rank the list's order (pages, settings, guides). Case, spaces, 「·」 and quotes do not
 * count. Titles are the home's row names and the page titles; paths name the home's sections.
 */
public final class SettingsIndex {
    private SettingsIndex() {}

    public enum Kind { PAGE, SETTING, GUIDE }
    /** One thing to find: the page it opens ("" for a guide), its name, where it is, and other words people use for it. */
    public record Entry(Kind kind, String page, String title, String path, List<String> keywords) {}
    /** A found entry and the word that matched when only one of its keywords did ("" otherwise). */
    public record Result(Entry entry, String matched) {
        /** The row's second line: the path, or 「{word} · {path}」 when only a keyword matched; a guide says where it is. */
        public String subtitle() { return matched.isEmpty() || entry.kind() == Kind.GUIDE ? entry.path() : matched + " · " + entry.path(); }
    }
    /** The most results a search shows. */
    public static final int LIMIT = 12;

    public static final List<Entry> ENTRIES = List.of(
        page("key", "Gemini", "AI 연결", "제미나이, 제미니, 구글, Google, API 키, AI Studio, 연결 확인, 모델별 한도, 작업창용 키, 메인 대화용 키, 두 번째 키"),
        page("chatgpt", "ChatGPT", "AI 연결", "챗GPT, 챗지피티, GPT, OpenAI, 오픈AI, 로그인, 구독, API 키, OAuth, Codex, 코덱스, 이미지용 Codex, 그림, 이미지, 모델, 추론 레벨, 속도, Fast, 모델 목록 진단"),
        page("claude", "Claude", "AI 연결", "클로드, Anthropic, 앤트로픽, API 키, 모델, 생각 깊이"),
        page("grok", "Grok", "AI 연결", "그록, xAI, 구독, 로그인, 영상, 동영상"),
        page("nim", "NVIDIA NIM", "AI 연결", "엔비디아, Nemotron, 네모트론, 무료 체험, API 키"),
        page("ai", "AI 역할", "AI 활용", "두뇌, 외부 두뇌, Gemini Flash, 생각, 코드 실행, 파일 만들기, 그림 그리기, 이미지, 자동 분류, 웹 검색, 출처, 작업 기능, 분석"),
        page("connectors", "커넥터", "AI 활용", "연결, 서비스, MCP, 마이아카이브, 서재, 네이버 길안내, 지도, Runpod, 런팟, 커넥터 추가, 토큰"),
        page("github", "GitHub", "AI 활용 › 커넥터", "깃허브, 깃헙, 저장소, 레포, repo, 기본 저장소, 토큰, PR, 병합, 머지"),
        page("drive", "Google Drive", "AI 활용 › 커넥터", "구글 드라이브, 드라이브, Drive, 파일, 문서, 시트, Google Cloud, 등록, SHA-1"),
        page("calendar", "캘린더", "AI 활용", "구글 캘린더, 일정, 약속, 스케줄, 달력, 권한"),
        page("usage", "사용량 · 남은 한도", "AI 활용", "사용량, 토큰, 캐시, 한도, 요금, 비용, 초기화, 5시간, 주간"),
        page("voice", "목소리와 대화", "음성 비서", "음성, 목소리, 보이스, 말투, 음성 모델, 라이브, Live, Extended Thinking, 생각 깊이, 검색, 검색 담당, 자막, 정밀 자막, 받아쓰기, 에코, 인사, 자동 종료, 작게 말해도, 통역, 번역, 언어"),
        page("quick", "바로 부르기", "음성 비서", "빅스비, Bixby, 가장자리 버튼, 엣지, 홈 버튼, 디지털 어시스턴트, 기본 앱, 알림창 타일, 바로 대화, 앱 열면 바로 대화, 아이콘"),
        page("wake", "차에서 호출어", "음성 비서 › 바로 부르기", "호출어, 차, 자동차, 블루투스, 들퍼레, 퍼레, 녹음, 반응 정도, 민감도"),
        // 작은 버튼: the word the earlier search found it by.
        page("background", "다른 앱 쓰는 동안", "음성 비서", "작은 음성 버튼, 작은 버튼, 떠 있는 버튼, 플로팅, 백그라운드, 다른 앱 위에 표시, 배터리, 알림, 음소거"),
        page("phone", "폰 조작", "휴대폰", "접근성, 권한, 휴대폰, 앱 실행, 디시전, 멈춤 버튼, 폰 조작 기록, 로그, 안전"),
        page("look", "화면 보기", "휴대폰", "캡처, 스크린샷, 화면 공유, 화면 봐 줘"),
        page("persona", "나에 대해", "나에게 맞추기", "호칭, 이름, 답변 스타일, 존댓말, 반말, 자기소개"),
        page("memory", "기억", "나에게 맞추기", "장기 기억, 자동 기억, 백업, 내보내기, 가져오기, 다른 AI"),
        page("memories", "기억한 것", "나에게 맞추기 › 기억", "기억 목록, 폰 사용법, 직접 추가, 지우기"),
        page("update", "업데이트", "앱", "새 버전, 설치, 버전, 릴리스, 릴리스 페이지, 삼성, 보안 위험 자동 차단, 설치가 안 될 때, 업데이트 확인용 토큰"),
        page("about", "정보", "앱", "버전, 오류, 진단 보고서, 미디어 연결 점검, 도움말, 요금, 개인용 시제품"),

        setting("voice", "음성 모델", "목소리와 대화 › 음성 모델"),
        setting("voice", "검색 담당", "목소리와 대화 › 검색"),
        setting("voice", "정밀 자막 함께 사용", "목소리와 대화 › 듣기"),
        setting("voice", "말이 없으면 자동 종료", "목소리와 대화 › 듣기"),
        setting("voice", "켤 때 인사하기", "목소리와 대화 › 말하기와 알림"),
        setting("voice", "음성으로 작업창에 일 시키기", "목소리와 대화 › 말하기와 알림"),
        setting("voice", "통역 언어", "목소리와 대화 › 통역 언어"),
        setting("voice", "말투", "목소리와 대화 › 목소리"),
        setting("quick", "화면 가장자리 버튼", "바로 부르기 › 누르는 곳"),
        setting("quick", "홈 버튼 길게 누르기", "바로 부르기 › 누르는 곳"),
        setting("quick", "알림창 타일 추가", "바로 부르기 › 누르는 곳"),
        setting("quick", "앱을 열면 바로 음성 대화", "바로 부르기 › 앱을 열 때"),
        setting("background", "작은 음성 버튼 표시", "다른 앱 쓰는 동안"),
        setting("ai", "두뇌", "AI 역할 › 음성 비서의 두뇌"),
        setting("ai", "작업창에서 코드 실행", "AI 역할 › 작업 기능"),
        setting("ai", "작업창에서 그림 그리기", "AI 역할 › 작업 기능"),
        setting("ai", "작업창 대화 자동 분류", "AI 역할 › 작업 기능"),
        setting("ai", "웹 검색 사용", "AI 역할 › 작업 기능"),
        setting("github", "기본 저장소", "커넥터 › GitHub"),
        setting("github", "저장소 바꾸기 허용", "커넥터 › GitHub"),
        setting("drive", "Drive 파일 수정·정리 허용", "커넥터 › Google Drive"),
        setting("chatgpt", "이미지용 Codex", "ChatGPT › 그림과 사용 한도"),
        setting("chatgpt", "추론 레벨", "ChatGPT › 음성 비서가 쓸 모델"),
        setting("key", "작업창·메인 대화용 키", "Gemini"),
        setting("phone", "2단계 · 음성 비서의 폰 조작 허용", "폰 조작 › 켜는 방법"),
        setting("phone", "Gemini 디시전으로 빠르게 조작", "폰 조작 › 더 잘하게"),
        setting("phone", "최근 폰 조작 기록", "폰 조작 › 기록"),
        setting("calendar", "대화에서 캘린더 쓰기", "캘린더"),
        setting("memory", "기억 내보내기", "기억 › 백업"),
        setting("memory", "백업에서 가져오기", "기억 › 백업"),
        setting("update", "업데이트 확인용 GitHub 토큰", "업데이트 › 고급"),
        setting("about", "오류 진단 보고서", "정보 › 문제 해결"),
        setting("about", "미디어 연결 점검", "정보 › 문제 해결"),

        guide("에이전트 팀", "작업창 입력칸의 ＋에서 골라요", "팀, 에이전트, 병렬"),
        guide("작업창 모델·생각 깊이", "작업창 입력칸의 모델 이름에서 골라요", ""),
        guide("녹음 요약 모델", "음성 비서 화면의 ‘녹음’에서 골라요", ""),
        guide("메인 대화 설정", "메인 대화의 ⋯ 메뉴에 있어요", ""),
        guide("브라우저 모델", "브라우저 화면의 모델 설정에서 골라요", ""));

    private static Entry page(String page, String title, String path, String keywords) { return new Entry(Kind.PAGE, page, title, path, words(keywords)); }
    private static Entry setting(String page, String title, String path) { return new Entry(Kind.SETTING, page, title, path, List.of()); }
    private static Entry guide(String title, String where, String keywords) { return new Entry(Kind.GUIDE, "", title, where, words(keywords)); }
    private static List<String> words(String list) { return list.isBlank() ? List.of() : List.of(list.split(", ")); }

    /** What is compared: lower case (Locale.ROOT) without spaces, 「·」 or quotes, so 「사용량 · 남은 한도」 and 「사용량남은한도」 meet. */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder kept = new StringBuilder();
        for (char c : text.strip().toLowerCase(Locale.ROOT).toCharArray()) if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && "·‘’“”".indexOf(c) < 0) kept.append(c);
        return kept.toString();
    }

    /** At most LIMIT entries for what was typed, best first; nothing for a blank query. */
    public static List<Result> find(String query) {
        String wanted = normalize(query);
        if (wanted.isEmpty()) return List.of();
        // Title start, title, path, keyword.
        List<List<Result>> ranks = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (Entry entry : ENTRIES) {
            String title = normalize(entry.title());
            if (title.startsWith(wanted)) ranks.get(0).add(new Result(entry, ""));
            else if (title.contains(wanted)) ranks.get(1).add(new Result(entry, ""));
            else if (normalize(entry.path()).contains(wanted)) ranks.get(2).add(new Result(entry, ""));
            else for (String keyword : entry.keywords()) if (normalize(keyword).contains(wanted)) { ranks.get(3).add(new Result(entry, keyword)); break; }
        }
        List<Result> found = new ArrayList<>();
        for (List<Result> rank : ranks) for (Result result : rank) if (found.size() < LIMIT) found.add(result);
        return found;
    }
}
