package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 휴대폰 검사: what the phone's own settings, its installed apps and the installer files in its
 * download folder say about its safety, as a list of findings a person can act on. It is a check of risk signs, not a
 * virus scanner: it knows no malware signatures and never says a phone is free of malware. Each rule reads facts the
 * Android collector (PhoneCheck) gathered; nothing here touches Android, so the rules are tested on a computer.
 */
public final class PhoneRisk {
    private PhoneRisk() {}
    /** Most serious first. OK says a check passed; INFO is something to know, not a risk. */
    public enum Level { DANGER, CAUTION, INFO, OK }
    /**
     * One line of the result. action: what its button opens, "" for none: "settings:<android action>", "app:<package>"
     * (that app's info page, where it can be removed), or "files" (the file explorer).
     */
    public record Finding(Level level, String title, String detail, String action) {}

    /** The phone's own settings. encryption: 1 on, 0 off, -1 unknown. patch: "YYYY-MM-DD" or "". */
    public record Device(boolean lock, String patch, long now, boolean adb, boolean developer, boolean rooted, int encryption, String release) {}
    /**
     * One installed app. installer: the package that installed it, "" when unknown (a file, adb). granted: the permissions
     * it holds, by their short names (READ_SMS). self: this app.
     */
    public record App(String id, String label, boolean system, String installer, boolean launcher, boolean accessibility, boolean admin,
                      boolean notifications, Set<String> granted, boolean self) {}
    /** An installer file found in the download folder. */
    public record Installer(String name, long bytes, long modified) {}

    /** App stores that check what they offer. Anything else (a browser, a messenger, a file manager, adb) is outside a store. */
    static final Set<String> STORES = Set.of("com.android.vending", "com.sec.android.app.samsungapps", "com.skt.skaf.A000Z00040",
        "com.kt.olleh.storefront", "com.lguplus.appstore", "com.amazon.venezia", "com.xiaomi.mipicks", "com.huawei.appmarket");
    private static final Set<String> MESSAGES = Set.of("READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CALL_LOG");
    private static final long DAY = 24L * 60 * 60 * 1000;

    public static boolean fromStore(App app) { return app.system() || STORES.contains(app.installer()); }

    public static List<Finding> device(Device d) {
        List<Finding> out = new ArrayList<>();
        out.add(d.lock() ? new Finding(Level.OK, "화면 잠금을 쓰고 있어요", "", "")
            : new Finding(Level.DANGER, "화면 잠금이 없어요", "휴대폰을 잃어버리면 누구나 열 수 있어요. PIN, 패턴이나 지문을 설정해 주세요.", "settings:android.settings.SECURITY_SETTINGS"));
        long days = patchAge(d.patch(), d.now());
        if (days < 0) out.add(new Finding(Level.INFO, "보안 패치 날짜를 알 수 없어요", "", "settings:android.settings.SYSTEM_UPDATE_SETTINGS"));
        else if (days > 365) out.add(new Finding(Level.DANGER, "보안 업데이트가 1년 넘게 없었어요", "마지막 보안 패치: " + d.patch() + ". 알려진 취약점이 고쳐지지 않은 상태예요. 소프트웨어 업데이트를 확인해 주세요.", "settings:android.settings.SYSTEM_UPDATE_SETTINGS"));
        else if (days > 180) out.add(new Finding(Level.CAUTION, "보안 업데이트가 반년 넘게 없었어요", "마지막 보안 패치: " + d.patch() + ". 소프트웨어 업데이트를 확인해 주세요.", "settings:android.settings.SYSTEM_UPDATE_SETTINGS"));
        else out.add(new Finding(Level.OK, "보안 패치가 최근 것이에요", "마지막 보안 패치: " + d.patch() + " · Android " + d.release(), ""));
        if (d.rooted()) out.add(new Finding(Level.DANGER, "루팅된 흔적이 있어요", "관리자 권한(su) 파일이나 시험용 서명이 보여요. 앱이 서로의 데이터를 볼 수 있는 상태일 수 있어요. 직접 한 것이 아니라면 초기화를 권해요.", ""));
        else out.add(new Finding(Level.OK, "루팅 흔적이 없어요", "", ""));
        if (d.adb()) out.add(new Finding(Level.CAUTION, "USB 디버깅이 켜져 있어요", "컴퓨터에 연결하면 휴대폰을 조작할 수 있어요. 개발할 때만 켜 두세요.", "settings:android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));
        else if (d.developer()) out.add(new Finding(Level.INFO, "개발자 옵션이 켜져 있어요", "USB 디버깅은 꺼져 있어요.", "settings:android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));
        else out.add(new Finding(Level.OK, "USB 디버깅이 꺼져 있어요", "", ""));
        if (d.encryption() == 0) out.add(new Finding(Level.CAUTION, "저장 공간 암호화가 꺼져 있어요", "휴대폰을 잃어버리면 파일을 꺼내 볼 수 있어요.", "settings:android.settings.SECURITY_SETTINGS"));
        else if (d.encryption() == 1) out.add(new Finding(Level.OK, "저장 공간이 암호화돼 있어요", "", ""));
        return out;
    }

    /** Days since the security patch, or -1 when its date cannot be read. */
    static long patchAge(String patch, long now) {
        try {
            if (patch == null || !patch.matches("\\d{4}-\\d{2}-\\d{2}")) return -1;
            long at = java.time.LocalDate.parse(patch).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            return Math.max(0, (now - at) / DAY);
        } catch (RuntimeException unreadable) { return -1; }
    }

    /**
     * The apps: one finding for each app that is both from outside a store and able to watch or control the phone (or
     * hidden), one for each store app with that reach, and one line naming the rest of the apps from outside a store.
     * all: whether every installed app was visible (false: only apps with an icon were, so hidden ones could not be seen).
     */
    public static List<Finding> apps(List<App> apps, boolean all) {
        List<Finding> out = new ArrayList<>(); List<String> outside = new ArrayList<>(); int danger = 0, hidden = 0;
        for (App app : apps) {
            if (app.system()) continue;
            boolean store = fromStore(app); List<String> reach = reach(app);
            if (app.self()) { out.add(new Finding(Level.INFO, app.label() + "(이 앱)", "스토어 밖에서 설치됐고" + (reach.isEmpty() ? " 특별한 권한은 쓰지 않아요." : " " + String.join(", ", reach) + ". 폰 조작 등 직접 켠 기능에 쓰여요."), "")); continue; }
            if (!app.launcher()) hidden++;
            if (!store && (!reach.isEmpty() || !app.launcher())) {
                danger++;
                List<String> why = new ArrayList<>(reach); if (!app.launcher()) why.add(0, "앱 목록에 아이콘이 없어요");
                out.add(new Finding(Level.DANGER, app.label() + ": 스토어 밖에서 설치됐고 휴대폰을 지켜볼 수 있어요", String.join(", ", why) + ". 직접 설치한 앱이 아니면 삭제해 주세요. (" + app.id() + ")", "app:" + app.id()));
            } else if (!store) outside.add(app.label());
            else if (app.accessibility() || app.admin())
                out.add(new Finding(Level.CAUTION, app.label() + ": " + String.join(", ", reach), "스토어에서 받은 앱이지만 화면을 읽거나 기기를 관리할 수 있어요. 쓰는 기능인지 확인해 주세요.", "app:" + app.id()));
        }
        if (!outside.isEmpty()) {
            java.util.Collections.sort(outside);
            String names = String.join(", ", outside.subList(0, Math.min(12, outside.size()))) + (outside.size() > 12 ? " 외 " + (outside.size() - 12) + "개" : "");
            out.add(new Finding(Level.CAUTION, "스토어 밖에서 설치한 앱 " + outside.size() + "개", names + ". 스토어의 검사를 거치지 않은 앱이에요. 모르는 앱이 있으면 삭제해 주세요.", "settings:android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS"));
        }
        if (danger == 0) out.add(new Finding(Level.OK, "스토어 밖에서 온 앱 가운데 휴대폰을 지켜볼 수 있는 앱이 없어요", "", ""));
        if (!all) out.add(new Finding(Level.INFO, "아이콘이 없는 앱은 확인하지 못했어요", "Android가 이 앱에 아이콘 있는 앱만 보여 줘요.", ""));
        else if (hidden == 0) out.add(new Finding(Level.OK, "아이콘 없이 숨은 앱이 없어요", "", ""));
        return out;
    }
    /** What lets an app watch or control the phone, in words. */
    static List<String> reach(App app) {
        List<String> out = new ArrayList<>();
        if (app.accessibility()) out.add("접근성 서비스로 화면을 읽고 누를 수 있어요");
        if (app.admin()) out.add("기기 관리자 권한이 있어요");
        if (app.notifications()) out.add("알림 내용을 읽을 수 있어요");
        for (String permission : MESSAGES) if (app.granted().contains(permission)) { out.add("문자·통화 기록을 읽을 수 있어요"); break; }
        return out;
    }

    /** allowed: whether the download folder could be read at all. */
    public static List<Finding> installers(List<Installer> files, boolean allowed) {
        if (!allowed) return List.of(new Finding(Level.INFO, "다운로드 폴더는 검사하지 못했어요", "파일 접근 권한이 없어요. 내 휴대폰 → 파일 탐색기에서 허용하면 다음 검사에 포함돼요.", "files"));
        if (files.isEmpty()) return List.of(new Finding(Level.OK, "다운로드 폴더에 설치 파일(APK)이 없어요", "", ""));
        List<String> names = new ArrayList<>(); long bytes = 0;
        for (Installer file : files) { if (names.size() < 8) names.add(file.name()); bytes += Math.max(0, file.bytes()); }
        return List.of(new Finding(Level.CAUTION, "다운로드 폴더에 설치 파일(APK) " + files.size() + "개", String.join(", ", names) + (files.size() > 8 ? " 외 " + (files.size() - 8) + "개" : "")
            + " · " + size(bytes) + ". 문자나 메신저 링크로 받은 설치 파일은 열지 말고 지워 주세요. 설치를 마친 파일도 지우는 편이 안전해요.", "files"));
    }
    public static boolean installer(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".apk") || lower.endsWith(".xapk") || lower.endsWith(".apks") || lower.endsWith(".apkm");
    }
    static String size(long bytes) { return bytes < 1_048_576 ? Math.max(1, bytes / 1024) + "KB" : String.format(Locale.ROOT, "%.1fMB", bytes / 1_048_576.0); }

    /** {위험, 주의, 양호} counts, and the findings most serious first (their order kept within a level). */
    public static int[] counts(List<Finding> findings) {
        int[] n = new int[3];
        for (Finding f : findings) { if (f.level() == Level.DANGER) n[0]++; else if (f.level() == Level.CAUTION) n[1]++; else if (f.level() == Level.OK) n[2]++; }
        return n;
    }
    public static List<Finding> sorted(List<Finding> findings) {
        List<Finding> out = new ArrayList<>(findings);
        out.sort(java.util.Comparator.comparingInt(f -> f.level().ordinal()));
        return out;
    }
    /** One line for the menu and the top of the result. */
    public static String summary(List<Finding> findings) {
        int[] n = counts(findings);
        return n[0] > 0 ? "위험 " + n[0] + "건 · 주의 " + n[1] + "건" : n[1] > 0 ? "주의 " + n[1] + "건 · 위험 없음" : "위험 신호가 없어요";
    }
    /** What the check is and is not, shown with every result. */
    public static final String SCOPE = "설정, 설치된 앱, 다운로드 폴더의 설치 파일에서 위험 신호를 찾았어요. 악성 코드 목록과 대조하는 백신 검사는 아니에요. 그런 검사는 Play 프로텍트나 V3 같은 백신 앱이 해요.";
}
