package dev.jun.gyeot.core;

/** A provider-confirmed quota/rate limit, not a guessed interpretation of model prose. */
public final class ModelLimit extends IllegalStateException {
    public final int status;
    public final long cooldownMillis;
    public boolean safeToResume = true;
    /**
     * What the refusing response's headers said is left (core/Limits), so 설정 → 사용량 shows the limit as reached, not the
     * last answer's higher share (MainAdapters.send and TeamModels.run keep it); NONE when they said nothing.
     */
    public final Limits limits;
    public ModelLimit(int status) { this(status, null, Limits.NONE); }
    /** message: the client's own explanation of the refusal, null for the generic one. */
    public ModelLimit(int status, String message, Limits limits) {
        super(message == null || message.isBlank() ? "모델의 사용 한도·할당량에 도달했어요 (HTTP " + status + ")." : message);
        this.status = status; this.limits = limits == null ? Limits.NONE : limits;
        cooldownMillis = status == 429 ? 60_000 : 15 * 60_000;
    }
    public static void check(int status) { check(status, true, Limits.NONE); }
    public static void check(int status, boolean safeToResume) { check(status, safeToResume, Limits.NONE); }
    /** As above, carrying the limits the refusing response reported. */
    public static void check(int status, boolean safeToResume, Limits limits) {
        if (status == 429 || status == 402) { ModelLimit limit = new ModelLimit(status, null, limits); limit.safeToResume = safeToResume; throw limit; }
    }
    @Override public String getMessage() {
        return super.getMessage() + (safeToResume ? "" : " 이미 시작한 생성·코드 실행의 중복을 막기 위해 자동 전환을 멈췄어요. 기존 결과를 확인해 주세요.");
    }
}
