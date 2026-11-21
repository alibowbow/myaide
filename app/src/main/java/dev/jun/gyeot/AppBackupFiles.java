package dev.jun.gyeot;

import android.content.Context;
import android.content.pm.PackageManager;
import dev.jun.gyeot.core.AppBackup;
import dev.jun.gyeot.core.MemoryBackup;
import dev.jun.gyeot.core.PageBook;
import dev.jun.gyeot.core.WorkBook;
import dev.jun.gyeot.core.WorkSchedules;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 앱 백업 on this phone and in the user's Google Drive (core/AppBackup says what a backup is): makes the zip from the
 * stores, uploads it, lists and downloads the ones in Drive, and brings one in by adding to each store through the
 * store's own methods. Never on the main thread.
 */
final class AppBackupFiles {
    private AppBackupFiles() {}

    /** Writes a backup of this phone's data into target and returns its counts (AppBackup.summary reads them). */
    static JSONObject create(Context context, AppStore store, File target) throws Exception {
        long now = System.currentTimeMillis();
        boolean chat = store.mainChat.available();
        JSONObject memory = MemoryBackup.export(store.memories(), store.persona(), chat ? store.mainChat.notes() : new JSONArray(), now);
        JSONArray notes = store.notes(), sessions = store.allSessions(), works = store.works(), schedules = store.schedules(), routines = store.routines();
        File pages = PageStore.book(context).dir(); List<File> pageFiles = new ArrayList<>();
        File[] found = pages.listFiles();
        if (found != null) for (File file : found) if (file.isFile() && AppBackup.allowed(AppBackup.PAGES + file.getName())) pageFiles.add(file);
        long messages = chat ? store.mainChat.count() : 0;
        JSONObject settings = settings(context); List<String> appFiles = new ArrayList<>(); appFiles(context.getFilesDir(), "", appFiles);
        int settingCount = 0; for (java.util.Iterator<String> names = settings.keys(); names.hasNext(); ) settingCount += settings.optJSONObject(names.next()).length();
        JSONObject counts = obj("settings", settingCount, "appFiles", appFiles.size(), "memories", length(memory.optJSONArray("memories")), "pinned", length(memory.optJSONArray("notes")), "persona", !memory.optString("persona").isBlank(),
            "notes", notes.length(), "sessions", sessions.length(), "works", works.length(), "schedules", schedules.length(), "routines", routines.length(), "pages", PageStore.book(context).pages().size(), "messages", messages);
        File copy = new File(context.getCacheDir(), "backup-main-chat.db"); copy.delete();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(target)))) {
            entry(zip, AppBackup.MANIFEST, AppBackup.manifest(version(context), now, counts).toString(2).getBytes(StandardCharsets.UTF_8));
            entry(zip, AppBackup.MEMORY, MemoryBackup.json(memory).getBytes(StandardCharsets.UTF_8));
            entry(zip, AppBackup.NOTES, notes.toString().getBytes(StandardCharsets.UTF_8));
            entry(zip, AppBackup.SESSIONS, sessions.toString().getBytes(StandardCharsets.UTF_8));
            entry(zip, AppBackup.WORKS, works.toString().getBytes(StandardCharsets.UTF_8));
            entry(zip, AppBackup.SCHEDULES, schedules.toString().getBytes(StandardCharsets.UTF_8));
            entry(zip, AppBackup.ROUTINES, routines.toString().getBytes(StandardCharsets.UTF_8));
            for (File file : pageFiles) { zip.putNextEntry(new ZipEntry(AppBackup.PAGES + file.getName())); Files.copy(file.toPath(), zip); zip.closeEntry(); }
            if (messages > 0) { store.mainChat.backupTo(copy); zip.putNextEntry(new ZipEntry(AppBackup.MAIN)); Files.copy(copy.toPath(), zip); zip.closeEntry(); }
            entry(zip, AppBackup.SETTINGS, settings.toString().getBytes(StandardCharsets.UTF_8));
            for (String relative : appFiles) {
                File file = new File(context.getFilesDir(), relative);
                try { zip.putNextEntry(new ZipEntry(AppBackup.APP_FILES + relative)); Files.copy(file.toPath(), zip); zip.closeEntry(); }
                catch (IOException unreadable) { /* A file that went away while backing up is left out. */ }
            }
        } finally { copy.delete(); }
        return counts;
    }
    /**
     * Every settings file but the secret and this phone's own (AppBackup.settingsFile), each value with its type:
     * {file: {key: {t: b|i|l|f|s|set, v: value}}}; keys and sign-ins are left out (AppBackup.settingKey).
     */
    static JSONObject settings(Context context) {
        JSONObject out = obj();
        File[] files = new File(context.getDataDir(), "shared_prefs").listFiles();
        if (files == null) return out;
        for (File file : files) {
            String name = file.getName(); if (!name.endsWith(".xml")) continue; name = name.substring(0, name.length() - 4);
            if (!AppBackup.settingsFile(name)) continue;
            JSONObject values = obj();
            for (java.util.Map.Entry<String, ?> each : context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                Object value = each.getValue(); if (!AppBackup.settingKey(each.getKey(), value)) continue;
                String type = value instanceof Boolean ? "b" : value instanceof Integer ? "i" : value instanceof Long ? "l" : value instanceof Float ? "f" : value instanceof String ? "s" : value instanceof java.util.Set ? "set" : "";
                if (type.isEmpty()) continue;
                put(values, each.getKey(), obj("t", type, "v", value instanceof java.util.Set<?> set ? new JSONArray(set) : value));
            }
            if (values.length() > 0) put(out, name, values);
        }
        return out;
    }
    /** The app's own files worth keeping (AppBackup.appFile), as paths relative to its folder. */
    private static void appFiles(File dir, String prefix, List<String> out) {
        File[] files = dir.listFiles(); if (files == null) return;
        for (File file : files) {
            String relative = prefix + file.getName();
            if (file.isDirectory()) { if (AppBackup.appFile(relative + "/x")) appFiles(file, relative + "/", out); }
            else if (file.isFile() && AppBackup.appFile(relative)) out.add(relative);
        }
    }
    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry(); }
    private static int length(JSONArray list) { return list == null ? 0 : list.length(); }
    private static String version(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.PackageInfoFlags.of(0)).versionName; } catch (Exception unknown) { return ""; }
    }

    /**
     * Brings a backup in by adding to what this phone has: nothing here is replaced or removed. Returns what was added, by
     * kind, with "skipped" (lines for the user about what was left out and why). A part that cannot be read is left out
     * and said so; the other parts still come in.
     */
    static JSONObject restore(Context context, AppStore store, File zipFile) throws Exception {
        File dir = new File(context.getCacheDir(), "backup-in"); delete(dir);
        if (!dir.mkdirs()) throw new IOException("백업을 풀 자리를 만들지 못했어요.");
        JSONObject added = obj(); JSONArray skipped = new JSONArray();
        try {
            unzip(zipFile, dir);
            File manifest = new File(dir, AppBackup.MANIFEST);
            if (!manifest.isFile()) throw new IllegalArgumentException(dev.jun.gyeot.core.Brand.NAME + " 백업 파일이 아니에요.");
            AppBackup.check(new JSONObject(text(manifest)));
            long now = System.currentTimeMillis();
            // 기억, 나에 대해, 고정 기억: the memory backup's own merge.
            part(skipped, "기억", () -> {
                File file = new File(dir, AppBackup.MEMORY); if (!file.isFile()) return;
                MemoryBackup.Backup backup = MemoryBackup.parse(text(file));
                MemoryBackup.Plan plan = MemoryBackup.plan(backup, store.memories(), store.persona(), store.mainChat.available() ? store.mainChat.notes() : null, now);
                if (!plan.memories().isEmpty()) put(added, "memories", store.changeMemory(list -> MemoryBackup.addMemories(list, plan.memories(), now)));
                if (plan.persona() != null) { store.settings.edit().putString("persona", plan.persona()).apply(); put(added, "persona", true); }
                int pinned = 0;
                for (MemoryBackup.Item note : plan.notes()) { try { store.mainChat.addNote(note.kind(), note.text(), 0); pinned++; } catch (Exception refused) { /* Counted as left out below. */ } }
                if (pinned > 0) put(added, "pinned", pinned);
            });
            part(skipped, "메모", () -> { JSONArray list = list(dir, AppBackup.NOTES); if (list != null) put(added, "notes", store.mergeNotes(list)); });
            part(skipped, "대화 기록", () -> { JSONArray list = list(dir, AppBackup.SESSIONS); if (list != null) put(added, "sessions", store.mergeSessions(list)); });
            part(skipped, "작업창", () -> {
                JSONArray list = list(dir, AppBackup.WORKS); if (list == null) return;
                // A run that was going on the other phone is not going here: marked as cut off, as after a restart.
                WorkBook.recover(list, now);
                put(added, "works", store.changeWorks(threads -> {
                    int count = AppBackup.addUnseen(threads, list, -1, null);
                    if (count > 0) { JSONArray ordered = AppBackup.sorted(threads, "createdAt", true); while (threads.length() > 0) threads.remove(0); for (int i = 0; i < ordered.length(); i++) threads.put(ordered.opt(i)); }
                    return count;
                }));
            });
            part(skipped, "예약", () -> {
                JSONArray list = list(dir, AppBackup.SCHEDULES); if (list == null) return;
                // Brought in switched off: a schedule starts work by itself, so the user turns each one on here.
                int count = store.changeSchedules(schedules -> AppBackup.addUnseen(schedules, list, Math.max(0, WorkSchedules.MAX_SCHEDULES - schedules.length()), item -> put(item, "enabled", false)));
                put(added, "schedules", count);
                if (count > 0) skipped.put("예약은 꺼진 채로 가져왔어요. 예약 작업에서 하나씩 켜 주세요.");
            });
            part(skipped, "한마디 루틴", () -> {
                JSONArray list = list(dir, AppBackup.ROUTINES); if (list == null) return;
                JSONArray mine = store.routines(); int count = dev.jun.gyeot.core.Routines.addUnseen(mine, list);
                if (count > 0) store.saveRoutines(mine);
                put(added, "routines", count);
            });
            part(skipped, "페이지", () -> {
                File pages = new File(dir, "pages"); if (!new File(pages, "index.json").isFile()) return;
                PageBook from = new PageBook(pages), into = PageStore.book(context);
                java.util.Set<String> have = new java.util.HashSet<>();
                for (PageBook.Page page : into.pages()) have.add(page.category() + "\u0000" + page.title());
                int count = 0;
                for (PageBook.Page page : from.pages()) {
                    if (!have.add(page.category() + "\u0000" + page.title())) continue;
                    into.save(page.title(), page.category(), page.format(), from.body(page.id()), PageBook.USER, ""); count++;
                }
                put(added, "pages", count);
            });
            part(skipped, "설정", () -> {
                File file = new File(dir, AppBackup.SETTINGS); if (!file.isFile()) return;
                JSONObject all = new JSONObject(text(file)); int count = 0;
                for (java.util.Iterator<String> names = all.keys(); names.hasNext(); ) {
                    String name = names.next(); JSONObject values = all.optJSONObject(name);
                    if (values == null || !AppBackup.settingsFile(name)) continue;
                    android.content.SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE); android.content.SharedPreferences.Editor edit = prefs.edit();
                    // Only what this phone has not set yet: a choice made here is kept.
                    for (java.util.Iterator<String> keys = values.keys(); keys.hasNext(); ) {
                        String key = keys.next(); JSONObject each = values.optJSONObject(key);
                        if (each == null || prefs.contains(key)) continue;
                        Object v = each.opt("v"); if (!AppBackup.settingKey(key, v)) continue;
                        switch (each.optString("t")) {
                            case "b" -> edit.putBoolean(key, each.optBoolean("v"));
                            case "i" -> edit.putInt(key, each.optInt("v"));
                            case "l" -> edit.putLong(key, each.optLong("v"));
                            case "f" -> edit.putFloat(key, (float) each.optDouble("v"));
                            case "s" -> edit.putString(key, each.optString("v"));
                            case "set" -> { JSONArray items = each.optJSONArray("v"); java.util.Set<String> set = new java.util.HashSet<>(); for (int i = 0; items != null && i < items.length(); i++) set.add(items.optString(i)); edit.putStringSet(key, set); }
                            default -> { continue; }
                        }
                        count++;
                    }
                    edit.commit();
                }
                if (count > 0) { put(added, "settings", count); put(added, "restart", true); }
            });
            part(skipped, "앱 파일", () -> {
                File from = new File(dir, AppBackup.APP_FILES.substring(0, AppBackup.APP_FILES.length() - 1)); if (!from.isDirectory()) return;
                List<String> files = new ArrayList<>(); walk(from, "", files); int count = 0;
                for (String relative : files) {
                    if (!AppBackup.appFile(relative)) continue;
                    File target = new File(context.getFilesDir(), relative);
                    // A list this phone has only started (still []) takes the backup's; anything else here is kept as it is.
                    boolean emptyIndex = AppBackup.INDEXES.contains(relative) && target.isFile() && text(target).strip().matches("\\[\\s*\\]|");
                    if (target.exists() && !emptyIndex) continue;
                    File parent = target.getParentFile(); if (parent != null && !parent.isDirectory() && !parent.mkdirs()) continue;
                    Files.copy(new File(from, relative).toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); count++;
                }
                if (count > 0) { put(added, "appFiles", count); put(added, "restart", true); }
            });
            part(skipped, "메인 대화", () -> {
                File chat = new File(dir, AppBackup.MAIN); if (!chat.isFile()) return;
                if (!store.mainChat.available()) { skipped.put("메인 대화 저장소를 열지 못해서 메인 대화는 가져오지 않았어요."); return; }
                int count = store.mainChat.importFrom(chat);
                if (count < 0) skipped.put("이 폰의 메인 대화에 이미 대화가 있어서 메인 대화는 합치지 않았어요. 대화를 비운 뒤 다시 가져오면 들어와요.");
                else put(added, "messages", count);
            });
            store.changed();
        } finally { delete(dir); }
        put(added, "skipped", skipped);
        return added;
    }
    private static void walk(File dir, String prefix, List<String> out) {
        File[] files = dir.listFiles(); if (files == null) return;
        for (File file : files) { String relative = prefix + file.getName(); if (file.isDirectory()) walk(file, relative + "/", out); else if (file.isFile()) out.add(relative); }
    }
    private interface Part { void run() throws Exception; }
    private static void part(JSONArray skipped, String name, Part part) {
        try { part.run(); }
        catch (Exception failed) { skipped.put(name + ": 가져오지 못했어요" + (failed.getMessage() == null || failed.getMessage().isBlank() ? "." : " (" + failed.getMessage() + ")")); }
    }
    private static JSONArray list(File dir, String name) throws Exception { File file = new File(dir, name); return file.isFile() ? new JSONArray(text(file)) : null; }
    private static String text(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    /** Only the backup's own parts leave the zip, each under its own name, within the size limits. */
    private static void unzip(File zipFile, File dir) throws IOException {
        long total = 0; byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (entry.isDirectory() || !AppBackup.allowed(name)) continue;
                File out = new File(dir, name); out.getParentFile().mkdirs();
                long limit = name.equals(AppBackup.MAIN) || name.startsWith(AppBackup.PAGES) || name.startsWith(AppBackup.APP_FILES) ? AppBackup.MAX_BYTES : AppBackup.MAX_LIST_BYTES, written = 0;
                try (OutputStream to = new BufferedOutputStream(new FileOutputStream(out))) {
                    int count;
                    while ((count = zip.read(buffer)) > 0) {
                        written += count; total += count;
                        if (written > limit || total > AppBackup.MAX_BYTES) throw new IOException("백업이 너무 커서 가져오지 않았어요.");
                        to.write(buffer, 0, count);
                    }
                }
            }
        }
    }
    private static void delete(File file) {
        File[] inside = file.listFiles();
        if (inside != null) for (File child : inside) delete(child);
        file.delete();
    }

    /** Uploads a backup file to the user's Drive; Drive's answer names it. */
    static JSONObject upload(AppStore store, File file, String name, String parent) throws Exception {
        JSONObject meta = AppBackup.uploadMeta(name); if (parent != null) put(meta, "parents", new JSONArray().put(parent));
        return store.drive.upload(meta, AppBackup.MIME, null, file);
    }
    /** This app's backups in the user's Drive, newest first. */
    static List<AppBackup.Listed> list(AppStore store) throws Exception { return AppBackup.listed(new JSONObject(store.drive.get(AppBackup.listUrl(), 256 * 1024))); }
    static void download(AppStore store, String id, File target) throws Exception { target.delete(); store.drive.download(AppBackup.downloadUrl(id), target, AppBackup.MAX_BYTES); }
}
