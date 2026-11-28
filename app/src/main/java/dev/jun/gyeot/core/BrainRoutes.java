package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 두뇌 배분 (the plan 준 approved: "대화는 빠른 모델, 일은 큰 모델, 가벼운 건 기기 안 모델로 자동 배분하고, 막히면 다음 모델로
 * 넘겨요"): the one table of which kind of request goes to which brain, and who takes over when that brain cannot. The kinds:
 * TALK (퍼레 speaking and typing: quick models), WORK (메인 대화, 작업창, the browser, research: the big model the user chose),
 * LIGHT (titles, summaries, cards, notification gists: Chores), and DEVICE (answered on the phone with no model at all:
 * core/LocalAnswers, the words VoiceTurns knows, 맡은 일's status). A model the user picked stays theirs: the table only says who
 * answers once when it cannot, never what is saved. Pure Java.
 */
public final class BrainRoutes {
    private BrainRoutes() {}
    public enum Kind {
        TALK("대화", "빠른 모델"), WORK("일", "고른 큰 모델"), LIGHT("잡일", "가벼운 모델"), DEVICE("기기 안", "모델 없음");
        public final String label, brain;
        Kind(String label, String brain) { this.label = label; this.brain = brain; }
    }
    /** The usage ledger's surfaces (UsageLedger keeps the same names): where a request came from. */
    public static final String MAIN = "main", WORK = "work", SCHEDULE = "schedule", BROWSER = "browser", VOICE = "voice", BUDDY = "buddy", CHORES = "chores";
    /** The company the ledger names for an answer made on the phone with no model (DEVICE), and its route. */
    public static final String DEVICE = "device", LOCAL = "local";

    /** One line of the table: a kind, the surfaces that are of it, the brains in order, and who takes over. */
    public record Route(Kind kind, String title, List<String> surfaces, String brains, String fallback) {}
    /**
     * The table, as 설정 → 사용량 shows it. TALK: the AI the user chose for 퍼레 (퍼레 두뇌), else Groq's quick model with NVIDIA
     * raced after it (BuddyTalk). WORK: the model chosen there; 메인 대화 hands one request to WORK_FALLBACK's next connected model
     * when the user leaves 막히면 다음 모델로 on, 작업창 hands over as its own team settings say (core/ModelRouting, the user's
     * 자동 전환), the browser goes on with BrowserAgent.FALLBACK (a subscription that reported itself nearly spent after the
     * others: core/SpareQuota.order). LIGHT: Groq GPT OSS 120B, or the Groq model of its tier with the most room (core/SpareQuota),
     * then Gemma 4, then Flash-Lite (Chores: the user's rule about Gemini's free tier). TALK's Qwen, when its day is full, hands
     * the talk to the Groq model of its tier with the most room before NVIDIA. DEVICE: no model.
     */
    public static final List<Route> TABLE = List.of(
        new Route(Kind.TALK, "퍼레 말·글", List.of(BUDDY), "퍼레 두뇌로 고른 AI → Groq 빠른 모델 · NVIDIA", "한도가 남은 다른 Groq 모델 → NVIDIA"),
        new Route(Kind.WORK, "메인 대화", List.of(MAIN, VOICE), "메인 대화에서 고른 모델", "연결된 다음 큰 모델로 한 번(Gemini 제외)"),
        new Route(Kind.WORK, "작업창", List.of(WORK, SCHEDULE), "방에서 고른 모델", "팀 설정의 자동 전환(한도)"),
        new Route(Kind.WORK, "브라우저", List.of(BROWSER), "브라우저 두뇌로 고른 모델", "GPT → Grok → NIM → Claude(한도·서버 오류, 거의 다 쓴 구독은 뒤로)"),
        new Route(Kind.LIGHT, "잡일", List.of(CHORES), "Groq GPT OSS 120B · 밀리면 한도가 남은 Groq 모델", "Gemma 4 → Gemini Flash-Lite"),
        new Route(Kind.DEVICE, "기기 안", List.of(DEVICE), "모델 없이 휴대폰이 바로(시각·날짜·계산·타이머·손전등·앱 열기·날씨 …)", "없음"));

    /** The kind a ledger surface is of; an unknown one is WORK (the user's own chosen model). */
    public static Kind kind(String surface) {
        for (Route route : TABLE) if (route.surfaces().contains(surface)) return route.kind();
        return Kind.WORK;
    }
    /** A ledger surface in words, as 설정 → 사용량 lists them. */
    public static String surfaceLabel(String surface) {
        return switch (surface == null ? "" : surface) {
            case MAIN -> "메인 대화"; case VOICE -> "누르고 말하기"; case WORK -> "작업창"; case SCHEDULE -> "예약";
            case BROWSER -> "브라우저"; case BUDDY -> "퍼레"; case CHORES -> "잡일"; case DEVICE -> "기기 안";
            default -> surface == null || surface.isBlank() ? "기타" : surface;
        };
    }

    /**
     * Who takes over a WORK request (메인 대화) when the chosen one cannot: the user's own paid or subscribed big models first,
     * NVIDIA's free one last. Gemini is never chosen for the user (BrowserAgent.FALLBACK: its free key is kept for other work,
     * and its free tier may use what is sent), nor Groq (8,000 tokens a minute cannot take a conversation) or Antigravity (an
     * agent run in the app's Linux, minutes long); each still answers when the user chose it.
     */
    public static final List<String> WORK_FALLBACK = List.of(WorkBook.CLAUDE, WorkBook.CHATGPT, WorkBook.GROK, WorkBook.NIM);
    /** 잡일's steps in order (Chores): Groq always first, the user's rule about Gemini's free tier. */
    public static final List<String> LIGHT_ORDER = List.of("groq", "gemma", "lite");

    /**
     * Whether a WORK request that failed goes to the next model (메인 대화's 막히면 다음 모델로), and why: only when the user
     * leaves it on (allowed), nothing of the answer reached the user yet (shown: words or a thinking summary streamed), no
     * function of it ran (ran: a save or a change would be made twice), it has not been handed over already (hopped: never a
     * loop), and the failure is one another model can help with (core/Fallback.why: never a stop or a content refusal).
     * Fallback.Why.NONE otherwise.
     */
    public static Fallback.Why hop(Throwable failed, boolean allowed, boolean shown, boolean ran, boolean hopped) {
        if (!allowed || shown || ran || hopped) return Fallback.Why.NONE;
        return Fallback.why(failed);
    }
    /**
     * The chain for a request of kind, chosen first: for WORK the chosen engine and, when allowed, the first engine of
     * WORK_FALLBACK other than it that usable accepts (connected, able to take this request); for the other kinds just the
     * chosen one (their own orders are LIGHT_ORDER and BuddyTalk's race). At most two: one hop, never a loop.
     */
    public static List<String> chain(Kind kind, String chosen, boolean allowed, Predicate<String> usable) {
        List<String> out = new ArrayList<>();
        if (chosen == null || chosen.isBlank()) return out;
        out.add(chosen);
        if (kind != Kind.WORK || !allowed || usable == null) return out;
        for (String next : WORK_FALLBACK) if (!next.equals(chosen) && usable.test(next)) { out.add(next); break; }
        return out;
    }
}
