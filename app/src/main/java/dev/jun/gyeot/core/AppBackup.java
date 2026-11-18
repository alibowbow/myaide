package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 앱 백업: one zip that holds what this phone's app remembers and has written, for moving to another phone through the
 * user's Google Drive. Its parts: backup.json (what it is), memory.json (MemoryBackup: memories, 나에 대해, pinned notes),
 * notes.json, sessions.json (대화 기록), works.json (작업창), schedules.json (예약), pages/ (페이지) and main-chat.db (메인
 * 대화). Keys, sign-ins and device settings are never in it. Bringing one in only adds: nothing on the phone is replaced
 * or removed. Pure Java: names, the manifest, what a zip entry may be called, and adding unseen items to a list.
 */
public final class AppBackup {
    private AppBackup() {}
    public static final String FORMAT = "dev.jun.gyeot.app-backup", MIME = "application/zip", KIND = "gyeot-backup";
    public static final int VERSION = 1;
    public static final String MANIFEST = "backup.json", MEMORY = "memory.json", NOTES = "notes.json", SESSIONS = "sessions.json", WORKS = "works.json",
        SCHEDULES = "schedules.json", ROUTINES = "routines.json", MAIN = "main-chat.db", PAGES = "pages/";
    /** The largest backup brought in, and the largest of its list files. */
    public static final long MAX_BYTES = 1024L * 1024 * 1024, MAX_LIST_BYTES = 64L * 1024 * 1024;

    /** One backup in Drive. */
    public record Listed(String id, String name, long size, String created) {}

    /** 들퍼레-백업-20261118-1530.zip: the time in the phone's zone. */
    public static String fileName(long now, ZoneId zone) {
        return Brand.NAME + "-백업-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT).format(Instant.ofEpochMilli(now).atZone(zone)) + ".zip";
    }
    public static JSONObject manifest(String appVersion, long now, JSONObject counts) {
        return obj("format", FORMAT, "version", VERSION, "app", appVersion == null ? "" : appVersion, "exportedAt", now, "counts", counts == null ? obj() : counts);
    }
    /** Refuses a file that is not this app's backup, or one a newer app wrote in a way this one does not know. */
    public static void check(JSONObject manifest) {
        if (manifest == null || !FORMAT.equals(manifest.optString("format"))) throw new IllegalArgumentException(Brand.NAME + " 백업 파일이 아니에요.");
        if (manifest.optInt("version", 0) > VERSION) throw new IllegalArgumentException("더 새 버전의 앱이 만든 백업이에요. 이 폰의 앱을 먼저 업데이트해 주세요.");
    }
    /** Whether a zip entry is one of the backup's own parts: a known file, or one plain file name under pages/. Anything else is skipped. */
    public static boolean allowed(String entry) {
        if (entry == null) return false;
        if (Set.of(MANIFEST, MEMORY, NOTES, SESSIONS, WORKS, SCHEDULES, ROUTINES, MAIN).contains(entry)) return true;
        return entry.startsWith(PAGES) && entry.substring(PAGES.length()).matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,120}") && !entry.contains("..");
    }
    /**
     * Adds to list the objects of incoming whose id the list does not hold yet, each prepared first (prepare may be null), until
     * room would be exceeded (room below 0: no limit). Nothing already in the list is touched. Returns how many were added.
     */
    public static int addUnseen(JSONArray list, JSONArray incoming, int room, Consumer<JSONObject> prepare) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.length(); i++) { JSONObject item = list.optJSONObject(i); if (item != null) seen.add(item.optString("id")); }
        int added = 0;
        for (int i = 0; incoming != null && i < incoming.length(); i++) {
            JSONObject item = incoming.optJSONObject(i);
            if (item == null || item.optString("id").isBlank() || !seen.add(item.optString("id"))) continue;
            if (room >= 0 && added >= room) break;
            JSONObject copy = copy(item);
            if (prepare != null) prepare.accept(copy);
            list.put(copy); added++;
        }
        return added;
    }
    /** The list as it was, ordered by a time field, oldest first (or newest first); items without the field keep their place among equals. */
    public static JSONArray sorted(JSONArray list, String field, boolean newestFirst) {
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) { JSONObject item = list.optJSONObject(i); if (item != null) items.add(item); }
        items.sort((a, b) -> newestFirst ? Long.compare(b.optLong(field), a.optLong(field)) : Long.compare(a.optLong(field), b.optLong(field)));
        JSONArray out = new JSONArray(); for (JSONObject item : items) out.put(item);
        return out;
    }

    /** Drive's search for this app's backups, newest first. */
    public static String listUrl() {
        String q = "(appProperties has { key='kind' and value='" + KIND + "' } or (name contains '" + Brand.NAME + "-백업-' and mimeType='" + MIME + "')) and trashed=false";
        return GoogleDrive.API + "files?q=" + enc(q) + "&orderBy=" + enc("createdTime desc") + "&pageSize=20&fields=" + enc("files(id,name,size,createdTime)");
    }
    public static String downloadUrl(String id) { return GoogleDrive.API + "files/" + enc(id) + "?alt=media"; }
    public static JSONObject uploadMeta(String name) { return obj("name", name, "mimeType", MIME, "appProperties", obj("kind", KIND)); }
    public static List<Listed> listed(JSONObject answer) {
        List<Listed> out = new ArrayList<>(); JSONArray files = answer == null ? null : answer.optJSONArray("files");
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file != null && !file.optString("id").isBlank()) out.add(new Listed(file.optString("id"), file.optString("name"), file.optLong("size"), file.optString("createdTime")));
        }
        return out;
    }
    private static String enc(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }

    /** "기억 12개 · 메모 3개 · …" from counts (memories, notes, sessions, works, schedules, pages, messages); only what is above zero. */
    public static String summary(JSONObject counts) {
        String[][] names = {{"memories", "기억"}, {"pinned", "고정 기억"}, {"notes", "메모"}, {"sessions", "대화 기록"}, {"works", "작업창"}, {"schedules", "예약"}, {"routines", "한마디 루틴"}, {"pages", "페이지"}, {"messages", "메인 대화 메시지"}};
        List<String> parts = new ArrayList<>();
        for (String[] name : names) if (counts != null && counts.optInt(name[0]) > 0) parts.add(name[1] + " " + counts.optInt(name[0]) + "개");
        if (counts != null && counts.optBoolean("persona")) parts.add("‘나에 대해’");
        return String.join(" · ", parts);
    }
    /** "12.3 MB", "640 KB". */
    public static String size(long bytes) {
        return bytes >= 1024 * 1024 ? String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024)) : Math.max(1, bytes / 1024) + " KB";
    }
}
