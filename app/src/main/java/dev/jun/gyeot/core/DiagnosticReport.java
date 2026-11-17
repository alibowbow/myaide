package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 오류 진단 보고서 as plain text: app version, device, memory/storage, optional status checks and the recent error
 * summaries (DiagnosticLog). Its inputs are an allow-list: there is no field for conversations, keys, tokens, accounts,
 * contacts, location or device identifiers, and every free-text value is masked by DiagnosticRedactor again here.
 */
public final class DiagnosticReport {
    public static final int FORMAT = 1, ERROR_LIMIT = 20, CHECK_LIMIT = 30, VALUE_LIMIT = 120;
    public static final String EXCLUDED = "대화·작업 기록과 첨부 파일, 저장된 API 키·토큰·로그인 정보·연락처·위치·계정·기기 고유 식별자는 직접 읽지 않아요. "
        + "오류 문구 속 키·토큰·이메일·전화번호·웹 주소·파일 경로 형태는 자동으로 가렸어요. 보내기 전에 내용을 한 번 확인해 주세요.";

    public record App(String name, String versionName, long versionCode, String packageName) {}
    public record Device(String manufacturer, String model, String androidRelease, int sdk, String securityPatch, List<String> abis, String locale) {}
    /** Bytes; a negative value is unknown. */
    public record Memory(long appUsed, long appMax, long deviceAvailable, long deviceTotal, boolean low, long storageFree) {}
    /** A short status line such as "마이크 권한: 허용됨", chosen by the screen that opens the report. */
    public record Check(String name, String value) {}
    /** A titled group of status lines from a measuring module (voice timings, exit reasons); numbers and labels only. */
    public record Section(String title, List<Check> items) {}

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);
    private static final Map<String, String> AREA_LABELS = Map.ofEntries(
        Map.entry("app", "앱"), Map.entry("crash", "앱 강제 종료"), Map.entry("voice", "음성"), Map.entry("work", "작업창"),
        Map.entry("browser", "브라우저"), Map.entry("image", "이미지"), Map.entry("video", "영상"), Map.entry("gallery", "갤러리"),
        Map.entry("github", "GitHub"), Map.entry("phone", "폰 조작"), Map.entry("update", "업데이트"), Map.entry("network", "네트워크"),
        Map.entry("storage", "저장공간"), Map.entry("other", "기타"));

    private DiagnosticReport() {}

    /** The subject line for sharing, e.g. "들퍼레 1.0.0 오류 진단 보고서". */
    public static String subject(App app) {
        String name = app == null ? "" : value(app.name(), 40), version = app == null ? "" : value(app.versionName(), 40);
        return (name.isEmpty() ? "앱" : name) + (version.isEmpty() ? "" : " " + version) + " 오류 진단 보고서";
    }

    public static String text(App app, Device device, Memory memory, List<Check> checks, DiagnosticLog log, long now, ZoneId zone) {
        return text(app, device, memory, checks, List.of(), log, now, zone);
    }
    public static String text(App app, Device device, Memory memory, List<Check> checks, List<Section> sections, DiagnosticLog log, long now, ZoneId zone) {
        ZonedDateTime at = Instant.ofEpochMilli(now).atZone(zone);
        StringBuilder out = new StringBuilder();
        String name = app == null ? "" : value(app.name(), 40);
        out.append(name.isEmpty() ? "앱" : name).append(" 오류 진단 보고서\n");
        out.append("작성: ").append(STAMP.format(at)).append(" (UTC").append(offset(at)).append(") · 형식 ").append(FORMAT).append('\n');

        section(out, "앱");
        if (app == null) item(out, "앱 정보", "");
        else {
            String version = value(app.versionName(), 40);
            item(out, "버전", version.isEmpty() ? "" : version + (app.versionCode() > 0 ? " (빌드 " + app.versionCode() + ")" : ""));
            item(out, "패키지", value(app.packageName(), 80));
        }

        section(out, "기기");
        if (device == null) item(out, "기기 정보", "");
        else {
            item(out, "제조사·모델", (value(device.manufacturer(), 40) + " " + value(device.model(), 60)).strip());
            String release = value(device.androidRelease(), 20), patch = value(device.securityPatch(), 20);
            item(out, "Android", release.isEmpty() && device.sdk() <= 0 ? "" : (release + (device.sdk() > 0 ? " (API " + device.sdk() + ")" : "")).strip()
                + (patch.isEmpty() ? "" : " · 보안 패치 " + patch));
            item(out, "ABI", device.abis() == null ? "" : value(String.join(", ", device.abis()), VALUE_LIMIT));
            item(out, "언어", value(device.locale(), 20));
        }

        section(out, "메모리·저장공간");
        if (memory == null) item(out, "메모리 정보", "");
        else {
            item(out, "앱 메모리", memory.appUsed() < 0 && memory.appMax() < 0 ? "" : megabytes(memory.appUsed()) + " / " + megabytes(memory.appMax()));
            item(out, "기기 메모리", memory.deviceAvailable() < 0 && memory.deviceTotal() < 0 ? ""
                : "여유 " + megabytes(memory.deviceAvailable()) + " / 전체 " + megabytes(memory.deviceTotal()) + (memory.low() ? " · 메모리 부족 상태" : ""));
            item(out, "앱 저장공간 여유", memory.storageFree() < 0 ? "" : megabytes(memory.storageFree()));
        }

        if (checks != null && !checks.isEmpty()) {
            section(out, "상태 점검");
            int shown = 0;
            for (Check check : checks) {
                if (check == null || shown >= CHECK_LIMIT) continue;
                String label = value(check.name(), 40);
                if (label.isEmpty()) continue;
                item(out, label, value(check.value(), VALUE_LIMIT)); shown++;
            }
        }
        if (sections != null) for (Section group : sections) {
            if (group == null || group.items() == null || group.items().isEmpty()) continue;
            String title = value(group.title(), 40);
            section(out, title.isEmpty() ? "기타 점검" : title);
            int shown = 0;
            for (Check check : group.items()) {
                if (check == null || shown >= CHECK_LIMIT) continue;
                String label = value(check.name(), 40);
                if (label.isEmpty()) continue;
                item(out, label, value(check.value(), VALUE_LIMIT)); shown++;
            }
        }

        section(out, "최근 오류 요약");
        List<DiagnosticLog.Entry> recent = log == null ? List.of() : log.recent(ERROR_LIMIT);
        if (recent.isEmpty()) out.append("- 기록된 오류가 없어요.\n");
        else {
            out.append("- 오류 ").append(log.size()).append("종류 중 최근 ").append(recent.size()).append("종류 (같은 오류는 횟수로 묶었어요)\n");
            int number = 1;
            for (DiagnosticLog.Entry entry : recent) {
                out.append(number++).append(". ").append(SHORT.format(Instant.ofEpochMilli(entry.lastAt()).atZone(zone))).append(" · ").append(areaLabel(entry.area()));
                if (!entry.type().isEmpty()) out.append(" · ").append(entry.type());
                if (entry.count() > 1) out.append(" · ").append(entry.count()).append("회 (처음 ").append(SHORT.format(Instant.ofEpochMilli(entry.firstAt()).atZone(zone))).append(')');
                out.append('\n');
                out.append("   내용: ").append(value(entry.summary(), DiagnosticLog.SUMMARY_LIMIT)).append('\n');
                if (!entry.where().isEmpty()) out.append("   위치: ").append(entry.where()).append('\n');
            }
        }

        section(out, "넣지 않은 정보");
        out.append(EXCLUDED).append('\n');
        String text = out.toString();
        // Every value was masked on the way in; this is the last guard before the text leaves the app.
        return safe(text);
    }

    /** Final fail-closed boundary, also used by the two explicit export buttons. */
    public static String safe(String text) {
        String cleaned = DiagnosticRedactor.leaks(text) ? DiagnosticRedactor.scrubLines(text) : text;
        return DiagnosticRedactor.leaks(cleaned) ? "민감정보 형태가 남아 진단 내용을 제외했어요." : cleaned;
    }

    public static String areaLabel(String area) { return AREA_LABELS.getOrDefault(DiagnosticLog.area(area), "기타"); }

    private static void section(StringBuilder out, String title) { out.append('\n').append('[').append(title).append("]\n"); }
    private static void item(StringBuilder out, String label, String value) {
        out.append("- ").append(label).append(": ").append(value == null || value.isBlank() ? "확인 불가" : value).append('\n');
    }
    private static String value(String text, int limit) { return DiagnosticRedactor.line(text, limit); }
    /** Decimal megabytes as the app shows them elsewhere (1MB = 1,000,000 bytes). */
    static String megabytes(long bytes) { return bytes < 0 ? "확인 불가" : String.format(Locale.ROOT, "%,dMB", Math.round(bytes / 1_000_000.0)); }
    private static String offset(ZonedDateTime at) {
        String id = at.getOffset().getId();
        return "Z".equals(id) ? "+00:00" : id;
    }
}
