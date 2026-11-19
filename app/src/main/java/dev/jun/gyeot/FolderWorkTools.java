package dev.jun.gyeot;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import dev.jun.gyeot.core.FolderTools;
import dev.jun.gyeot.core.GitHubFunctions;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * folder_list and folder_save for a work job (core/FolderTools): the 들퍼레 folder on the phone's shared storage, through
 * ManagedSharedStorage, so nothing outside 들퍼레 is read or written and nothing that is there is replaced. Only files the
 * job itself downloaded with the browser can be saved, and only when the user's own words for the run ask to keep files.
 */
final class FolderWorkTools extends WorkTools {
    /** The files this job downloaded: a browser result's artifact by its id (null when the job has none such), and its bytes. */
    interface Downloads { JSONObject find(String id); File bytes(String id); }
    /** The phone's 들퍼레 folder; tests put their own here. */
    interface Shelf { ManagedSharedStorage.Access open() throws IOException; }
    static Shelf shelf;
    private final Context context; private final Supplier<String> words; private final Downloads downloads; private final BooleanSupplier stopped;
    FolderWorkTools(Context context, Supplier<String> words, Downloads downloads, BooleanSupplier stopped) {
        this.context = context.getApplicationContext(); this.words = words; this.downloads = downloads; this.stopped = stopped;
    }
    /** The files a work room got from its browser jobs, as its browser results recorded them. */
    static Downloads room(AppStore store, String room) {
        return new Downloads() {
            @Override public JSONObject find(String id) {
                if (id == null || !dev.jun.gyeot.core.WorkBook.safeId(id)) return null;
                JSONObject thread = dev.jun.gyeot.core.WorkBook.find(store.works(), room);
                JSONArray messages = thread == null ? null : thread.optJSONArray("messages");
                for (int i = 0; messages != null && i < messages.length(); i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message == null || !"browser_result".equals(message.optString("kind"))) continue;
                    JSONArray files = message.optJSONArray("files");
                    for (int f = 0; files != null && f < files.length(); f++) { JSONObject file = files.optJSONObject(f); if (file != null && id.equals(file.optString("id"))) return file; }
                }
                return null;
            }
            @Override public File bytes(String id) { return store.attachmentFile(id); }
        };
    }
    @Override JSONArray declarations() { return FolderTools.declarations(); }
    @Override String instructions() { return FolderTools.INSTRUCTIONS; }
    @Override JSONObject run(String name, Object arguments) {
        JSONObject args = arguments instanceof JSONObject given ? given : parse(arguments);
        try {
            if (FolderTools.LIST.equals(name)) return list(args);
            if (FolderTools.SAVE.equals(name)) return saveAll(args);
            if (FolderTools.MAKE.equals(name)) return make(args);
            return GitHubFunctions.failure("이 도구는 없어요.");
        } catch (IOException | RuntimeException failed) {
            return GitHubFunctions.failure(failed.getMessage() == null || failed.getMessage().isBlank() ? "들퍼레 폴더를 다루지 못했어요." : failed.getMessage());
        }
    }
    private static JSONObject parse(Object arguments) { try { return new JSONObject(String.valueOf(arguments)); } catch (Exception unreadable) { return new JSONObject(); } }
    private ManagedSharedStorage.Access access() throws IOException { return shelf != null ? shelf.open() : ManagedSharedStorage.access(context); }
    private static String path(String folder) { return ManagedSharedStorage.ROOT_NAME + (folder.isEmpty() ? "" : "/" + folder); }

    private JSONObject list(JSONObject args) throws IOException {
        String folder = FolderTools.folder(args.optString("folder"));
        if (folder == null) return GitHubFunctions.failure("들퍼레 안의 폴더 경로만 볼 수 있어요. 예: 수능/2025");
        try (ManagedSharedStorage.Access access = access()) {
            ManagedSharedStorage.Node directory;
            try { directory = access.directory(path(folder), false); }
            catch (IOException missing) { if (ManagedSharedStorage.missing(missing)) return obj("ok", true, "folder", path(folder), "exists", false, "entries", arr()); throw missing; }
            try (ManagedSharedStorage.Node shown = directory; var children = shown.children()) {
                List<JSONObject> entries = new ArrayList<>(); boolean more = false;
                for (java.nio.file.Path child : children) {
                    if (stopped.getAsBoolean()) throw new IOException("작업을 멈췄어요.");
                    if (entries.size() >= FolderTools.ENTRIES) { more = true; break; }
                    String name = child.getFileName().toString(); if (name.startsWith(".")) continue;
                    StructStat stat; try { stat = Os.lstat(child.toString()); } catch (ErrnoException gone) { continue; }
                    boolean isFolder = OsConstants.S_ISDIR(stat.st_mode);
                    if (!isFolder && !OsConstants.S_ISREG(stat.st_mode)) continue;
                    entries.add(isFolder ? obj("name", name, "folder", true) : obj("name", name, "size", stat.st_size));
                }
                entries.sort((a, b) -> a.optString("name").compareTo(b.optString("name")));
                JSONArray out = arr(); for (JSONObject entry : entries) out.put(entry);
                return obj("ok", true, "folder", path(folder), "exists", true, "entries", out, "more", more);
            }
        }
    }

    private JSONObject make(JSONObject args) throws IOException {
        if (!FolderTools.asked(words.get())) return GitHubFunctions.failure(FolderTools.NOT_ASKED);
        String folder = FolderTools.folder(args.optString("folder"));
        if (folder == null || folder.isEmpty()) return GitHubFunctions.failure("들퍼레 안의 폴더 경로를 주세요. 예: 수능/2025");
        try (ManagedSharedStorage.Access access = access(); ManagedSharedStorage.Node made = access.directory(path(folder), true)) { made.check(); }
        return obj("ok", true, "folder", path(folder));
    }
    /** Every file of a folder_save call (files, or one file_id for the older form), each with its own result. */
    private JSONObject saveAll(JSONObject args) throws IOException {
        if (!FolderTools.asked(words.get())) return GitHubFunctions.failure(FolderTools.NOT_ASKED);
        org.json.JSONArray files = args.optJSONArray("files");
        if (files == null) return save(args);
        if (files.length() == 0) return GitHubFunctions.failure("저장할 파일이 없어요.");
        if (files.length() > FolderTools.BATCH) return GitHubFunctions.failure("한 번에 " + FolderTools.BATCH + "개까지 저장해요. 나눠서 다시 저장해 주세요.");
        org.json.JSONArray results = new org.json.JSONArray(); int saved = 0, kept = 0, failed = 0;
        for (int i = 0; i < files.length(); i++) {
            if (stopped.getAsBoolean()) throw new IOException("작업을 멈췄어요.");
            JSONObject one = files.optJSONObject(i); JSONObject result;
            try { result = one == null ? GitHubFunctions.failure("파일 정보가 아니에요.") : save(one); }
            catch (IOException | RuntimeException error) { result = GitHubFunctions.failure(error.getMessage() == null ? "저장하지 못했어요." : error.getMessage()); }
            if (one != null) dev.jun.gyeot.core.Jsons.put(result, "file_id", one.optString("file_id"));
            if (!result.optBoolean("ok")) failed++; else if (result.optBoolean("kept_already")) kept++; else saved++;
            results.put(result);
        }
        return obj("ok", failed == 0, "saved", saved, "kept_already", kept, "failed", failed, "results", results);
    }
    private JSONObject save(JSONObject args) throws IOException {
        String id = args.optString("file_id").strip(); JSONObject file = downloads.find(id);
        if (file == null) return GitHubFunctions.failure("이 작업에서 브라우저로 받은 파일만 저장할 수 있어요. 브라우저 결과의 artifact id를 file_id로 주세요.");
        File source = downloads.bytes(id);
        if (source == null || !source.isFile()) return GitHubFunctions.failure("받은 파일이 앱에 남아 있지 않아요. 다시 받아 주세요.");
        String folder = FolderTools.folder(args.optString("folder"));
        if (folder == null) return GitHubFunctions.failure("들퍼레 안의 폴더 경로만 쓸 수 있어요. 예: 수능/2025");
        byte[] head = new byte[16]; int got;
        try (InputStream in = new FileInputStream(source)) { got = Math.max(0, in.read(head)); }
        String kind = FolderTools.sniff(java.util.Arrays.copyOf(head, got));
        String name = FolderTools.name(args.optString("name"), file.optString("name"), kind);
        if (name == null) {
            String asked = FolderTools.extension(args.optString("name"));
            return GitHubFunctions.failure(asked.equals("pdf") && !kind.equals("pdf") ? "받은 파일이 PDF가 아니에요. 웹페이지나 오류 화면을 받은 것 같아요. 저장하지 않았어요."
                : "저장할 수 없는 파일 이름이나 형식이에요. 문서, 사진, 소리, 영상, 압축 파일만 저장해요.");
        }
        long size = source.length();
        try (ManagedSharedStorage.Access access = access()) {
            String where = path(folder); Set<String> taken = new HashSet<>();
            try (ManagedSharedStorage.Node directory = access.directory(where, true); var children = directory.children()) {
                for (java.nio.file.Path child : children) {
                    String existing = child.getFileName().toString(); taken.add(existing);
                    if (!existing.equals(name)) continue;
                    try { StructStat stat = Os.lstat(child.toString()); if (OsConstants.S_ISREG(stat.st_mode) && stat.st_size == size) return obj("ok", true, "kept_already", true, "path", where + "/" + name, "size", size); }
                    catch (ErrnoException unreadable) { /* taken all the same */ }
                }
            }
            String kept = FolderTools.unique(name, taken);
            try (ManagedSharedStorage.Created made = access.create(where + "/" + kept); InputStream in = new FileInputStream(source)) {
                byte[] buffer = new byte[64 * 1024]; long offset = 0; int read;
                while ((read = in.read(buffer)) > 0) {
                    if (stopped.getAsBoolean()) throw new IOException("작업을 멈춰서 저장을 마치지 못했어요: " + where + "/" + kept);
                    made.check(); int done = 0;
                    while (done < read) {
                        int wrote; try { wrote = Os.pwrite(made.fd.getFileDescriptor(), buffer, done, read - done, offset); } catch (ErrnoException e) { throw ManagedSharedStorage.io(e); }
                        if (wrote <= 0) throw new IOException("파일 쓰기가 진행되지 않아요.");
                        done += wrote; offset += wrote;
                    }
                }
                made.sync();
            }
            return obj("ok", true, "path", where + "/" + kept, "size", size, "renamed", !kept.equals(name));
        }
    }
}
