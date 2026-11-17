package dev.jun.gyeot.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Why Android ended earlier processes (ApplicationExitInfo), and the small state summary the app hands Android so a later
 * start knows whether a voice call or a task was running. Reason and importance numbers mirror the platform constants.
 * The summary holds flags and a work-room id only; an ANR keeps only its reason, and other descriptions are masked and cut.
 */
public final class ExitReasons {
    public static final int UNKNOWN = 0, EXIT_SELF = 1, SIGNALED = 2, LOW_MEMORY = 3, CRASH = 4, CRASH_NATIVE = 5, ANR = 6,
        INITIALIZATION_FAILURE = 7, PERMISSION_CHANGE = 8, EXCESSIVE_RESOURCE_USAGE = 9, USER_REQUESTED = 10, USER_STOPPED = 11,
        DEPENDENCY_DIED = 12, OTHER = 13, FREEZER = 14, PACKAGE_STATE_CHANGE = 15, PACKAGE_UPDATED = 16;
    /** getHistoricalProcessExitReasons(pkg, 0, LIMIT); ActivityManager caps a summary at 128 bytes. */
    public static final int LIMIT = 16, SUMMARY_BYTES = 128, DESCRIPTION_LIMIT = 60, REPORT_LIMIT = 8;
    private static final String PREFIX = "g1";
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

    private ExitReasons() {}

    /** What was running: voice is "" (off) or the mode letter a/d/i, work is a room id or "". */
    public record State(String voice, boolean phone, String work) {
        public static final State IDLE = new State("", false, "");
        public boolean busy() { return !voice.isEmpty() || phone || !work.isEmpty(); }
    }
    public record Exit(int reason, int importance, long at, String description, State state, boolean main) {}

    /** The bytes for ActivityManager.setProcessStateSummary, e.g. "g1;v=a;p=1;w=1a2b3c4d". */
    public static byte[] summary(String voiceMode, boolean phone, String work) {
        String voice = switch (voiceMode == null ? "" : voiceMode) {
            case GeminiProtocol.ASSISTANT -> "a"; case GeminiProtocol.DICTATION -> "d"; case GeminiProtocol.INTERPRETER -> "i"; default -> "";
        };
        String id = work == null ? "" : work.strip();
        if (!id.isEmpty() && !id.matches("[A-Za-z0-9\\-]{1,16}")) id = "1";
        return (PREFIX + ";v=" + (voice.isEmpty() ? "0" : voice) + ";p=" + (phone ? 1 : 0) + ";w=" + (id.isEmpty() ? "0" : id)).getBytes(StandardCharsets.US_ASCII);
    }
    /** Parses a summary this app wrote; anything else (none, another format) reads as idle. */
    public static State state(byte[] summary) {
        if (summary == null || summary.length == 0 || summary.length > SUMMARY_BYTES) return State.IDLE;
        String[] parts = new String(summary, StandardCharsets.US_ASCII).split(";");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) return State.IDLE;
        String voice = value(parts[1], "v"), phone = value(parts[2], "p"), work = value(parts[3], "w");
        if (voice == null || phone == null || work == null) return State.IDLE;
        return new State(voice.matches("[adi]") ? voice : "", "1".equals(phone), "0".equals(work) || !work.matches("[A-Za-z0-9\\-]{1,16}") ? "" : work);
    }
    private static String value(String part, String name) { return part.startsWith(name + "=") ? part.substring(name.length() + 1) : null; }

    public static Exit exit(int reason, int importance, long at, String description, byte[] summary, boolean main) {
        // A crash or ANR description can quote the app's own state; keep only the reason for those.
        boolean keep = reason != ANR && reason != CRASH && reason != CRASH_NATIVE;
        return new Exit(reason, importance, at, keep ? DiagnosticRedactor.line(description, DESCRIPTION_LIMIT) : "", state(summary), main);
    }

    /** Android ended the process on its own: memory, resources, freezing, a signal or not responding. */
    public static boolean systemKill(int reason) {
        return reason == SIGNALED || reason == LOW_MEMORY || reason == ANR || reason == EXCESSIVE_RESOURCE_USAGE || reason == OTHER || reason == FREEZER;
    }
    /** The one-time notice for the latest main-process exit after `seenAt`, or null. */
    public static String notice(List<Exit> exits, long seenAt) {
        Exit latest = latest(exits);
        if (latest == null || latest.at() <= seenAt || !systemKill(latest.reason()) || !latest.state().busy()) return null;
        return latest.state().voice().isEmpty() ? "지난 작업이 시스템에 의해 종료됐어요" : "지난 음성 대화가 시스템에 의해 종료됐어요";
    }
    /** The most recent exit of the app's main process. */
    public static Exit latest(List<Exit> exits) {
        Exit latest = null;
        if (exits != null) for (Exit exit : exits) if (exit != null && exit.main() && (latest == null || exit.at() > latest.at())) latest = exit;
        return latest;
    }

    /** Report lines: main-process exits newest first, and a count for the browser's own processes. */
    public static List<DiagnosticReport.Check> lines(List<Exit> exits, ZoneId zone) {
        List<DiagnosticReport.Check> out = new ArrayList<>();
        List<Exit> main = new ArrayList<>(); int others = 0;
        if (exits != null) for (Exit exit : exits) { if (exit == null) continue; if (exit.main()) main.add(exit); else others++; }
        main.sort((a, b) -> Long.compare(b.at(), a.at()));
        if (main.isEmpty()) out.add(new DiagnosticReport.Check("앱 종료 기록", "Android가 남긴 기록이 없어요."));
        for (Exit exit : main.subList(0, Math.min(REPORT_LIMIT, main.size()))) {
            StringBuilder value = new StringBuilder(reasonLabel(exit.reason())).append(" · ").append(importanceLabel(exit.importance()));
            State state = exit.state();
            if (!state.voice().isEmpty()) value.append(" · 음성 중");
            if (state.phone()) value.append(" · 폰 작업 중");
            if (!state.work().isEmpty()) value.append(" · 작업 ").append(state.work());
            if (!exit.description().isEmpty()) value.append(" · ").append(exit.description());
            out.add(new DiagnosticReport.Check(SHORT.format(Instant.ofEpochMilli(exit.at()).atZone(zone)), value.toString()));
        }
        if (others > 0) out.add(new DiagnosticReport.Check("브라우저 등 다른 프로세스", "종료 " + others + "회"));
        return out;
    }
    public static String reasonLabel(int reason) {
        return switch (reason) {
            case EXIT_SELF -> "앱이 스스로 종료"; case SIGNALED -> "시스템 신호로 종료"; case LOW_MEMORY -> "메모리 부족으로 시스템이 종료";
            case CRASH -> "앱 오류로 종료"; case CRASH_NATIVE -> "네이티브 오류로 종료"; case ANR -> "응답 없음(ANR)";
            case INITIALIZATION_FAILURE -> "시작 실패"; case PERMISSION_CHANGE -> "권한 변경으로 종료"; case EXCESSIVE_RESOURCE_USAGE -> "자원 과다 사용으로 시스템이 종료";
            case USER_REQUESTED -> "사용자가 강제 종료"; case USER_STOPPED -> "사용자가 중지"; case DEPENDENCY_DIED -> "연결된 프로세스 종료로 함께 종료";
            case OTHER -> "시스템이 종료(기타)"; case FREEZER -> "시스템이 멈춘 앱을 종료"; case PACKAGE_STATE_CHANGE -> "앱 상태 변경으로 종료";
            case PACKAGE_UPDATED -> "앱 업데이트로 종료"; default -> "원인 모름(" + reason + ")";
        };
    }
    /** ActivityManager.RunningAppProcessInfo importance at the moment it ended. */
    public static String importanceLabel(int importance) {
        String label;
        if (importance <= 100) label = "화면 사용 중";
        else if (importance <= 125) label = "포그라운드 서비스";
        else if (importance <= 200) label = "화면에 보임";
        else if (importance <= 230) label = "사용자가 느끼는 작업";
        else if (importance <= 300) label = "백그라운드 서비스";
        else if (importance == 325) label = "화면 꺼진 채 사용 중";
        else if (importance < 1000) label = "백그라운드";
        else label = "실행 안 함";
        return label + "(" + importance + ")";
    }
}
