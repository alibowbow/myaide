package dev.jun.gyeot;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import dev.jun.gyeot.core.AppBackup;
import dev.jun.gyeot.core.GoogleDrive;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The 들퍼레 folder (내장 저장공간/들퍼레: images, videos, music, documents …) kept in the user's Drive as files, under
 * 내 드라이브 › 들퍼레 백업 › 들퍼레 폴더 with the same subfolders. A backup uploads what Drive does not have yet or has
 * at another size (one file at a time, so a large video never sits in memory); bringing it in downloads what this phone's
 * 들퍼레 folder does not have, and never replaces or removes a file here. Never on the main thread.
 */
final class DriveFolderBackup {
    static final String FOLDER = "application/vnd.google-apps.folder", ROOT_KIND = "gyeot-backup-root", FILES_KIND = "gyeot-backup-files";
    static final String ROOT_NAME = "들퍼레 백업", FILES_NAME = "들퍼레 폴더";
    /** The largest single file moved either way (Drive downloads here are bounded by an int). */
    static final long MAX_FILE = Integer.MAX_VALUE;
    /** Room kept free on the phone while a file passes through the cache. */
    private static final long SPARE = 200L * 1024 * 1024;
    private DriveFolderBackup() {}

    interface Progress { void at(String line); }
    /** What a run did: files looked at, files moved and their bytes, and lines about what was left out. */
    static final class Result {
        int files, moved; long bytes; final List<String> notes = new ArrayList<>();
        void note(String line) { if (notes.size() < 20) notes.add(line); }
    }

    // ── Drive ────────────────────────────────────────────────────────────────

    /** 내 드라이브 › 들퍼레 백업, made the first time; the app data backups and the 들퍼레 folder's copy live in it. */
    static String root(AppStore store) throws Exception {
        String found = find(store, ROOT_KIND, null);
        return found != null ? found : create(store, ROOT_NAME, "root", ROOT_KIND);
    }
    /** 들퍼레 백업 › 들퍼레 폴더, made when make is true; null when there is none and none is to be made. */
    static String files(AppStore store, String root, boolean make) throws Exception {
        String found = find(store, FILES_KIND, root);
        return found != null || !make ? found : create(store, FILES_NAME, root, FILES_KIND);
    }
    private static String find(AppStore store, String kind, String parent) throws Exception {
        String q = "appProperties has { key='kind' and value='" + kind + "' } and mimeType='" + FOLDER + "' and trashed=false" + (parent == null ? "" : " and '" + parent + "' in parents");
        JSONObject answer = new JSONObject(store.drive.get(GoogleDrive.API + "files?q=" + enc(q) + "&orderBy=" + enc("createdTime") + "&pageSize=10&fields=" + enc("files(id,name)"), 256 * 1024));
        JSONArray files = answer.optJSONArray("files");
        String id = files == null || files.length() == 0 ? "" : files.optJSONObject(0).optString("id");
        return id.isBlank() ? null : id;
    }
    private static String create(AppStore store, String name, String parent, String kind) throws Exception {
        JSONObject meta = obj("name", name, "mimeType", FOLDER, "parents", arr(parent));
        if (kind != null) put(meta, "appProperties", obj("kind", kind));
        String id = store.drive.send("POST", GoogleDrive.createUrl(), meta).optString("id");
        if (id.isBlank()) throw new IOException("Drive에 백업 폴더를 만들지 못했어요.");
        return id;
    }
    record Remote(String id, String name, String mime, long size) { boolean folder() { return FOLDER.equals(mime); } }
    /** A Drive folder's files and folders by name (the first of a name wins). */
    static Map<String, Remote> children(AppStore store, String folder) throws Exception {
        Map<String, Remote> out = new HashMap<>(); String page = "";
        do {
            String url = GoogleDrive.API + "files?q=" + enc("'" + folder + "' in parents and trashed=false") + "&pageSize=1000&fields="
                + enc("nextPageToken,files(id,name,mimeType,size)") + (page.isEmpty() ? "" : "&pageToken=" + enc(page));
            JSONObject answer = new JSONObject(store.drive.get(url, 8 * 1024 * 1024));
            JSONArray files = answer.optJSONArray("files");
            for (int i = 0; files != null && i < files.length(); i++) {
                JSONObject file = files.optJSONObject(i); if (file == null || file.optString("id").isBlank()) continue;
                out.putIfAbsent(file.optString("name"), new Remote(file.optString("id"), file.optString("name"), file.optString("mimeType"), file.optLong("size", -1)));
            }
            page = answer.optString("nextPageToken");
        } while (!page.isEmpty());
        return out;
    }

    // ── this phone's 들퍼레 folder ─────────────────────────────────────────────

    record Local(String id, String name, String mime, long size, Uri uri) { boolean folder() { return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime); } }
    /** One folder of the granted 들퍼레 tree, hidden files left out. */
    static List<Local> local(Context context, Uri tree, String parent) throws IOException {
        List<Local> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
                new String[]{"document_id", "_display_name", "mime_type", "_size"}, null, null, null)) {
            if (c == null) throw new IOException("들퍼레 폴더 목록을 읽지 못했어요.");
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1);
                if (id == null || name == null || name.isBlank() || name.startsWith(".") || name.contains("/")) continue;
                out.add(new Local(id, name, c.getString(2), c.isNull(3) ? -1 : c.getLong(3), DocumentsContract.buildDocumentUriUsingTree(tree, id)));
            }
        }
        return out;
    }

    // ── backing up ───────────────────────────────────────────────────────────

    /** Copies the 들퍼레 folder into 들퍼레 백업 › 들퍼레 폴더: what is new or changed goes up, the rest stays as it is. */
    static Result upload(Context context, AppStore store, String root, Progress progress, BooleanSupplier cancelled) throws Exception {
        Uri tree = SharedOutputFolder.selectedTree(context);
        Result result = new Result();
        upload(context, store, tree, DocumentsContract.getTreeDocumentId(tree), files(store, root, true), "", result, progress, cancelled, 0);
        return result;
    }
    private static void upload(Context context, AppStore store, Uri tree, String localFolder, String remoteFolder, String path, Result result, Progress progress, BooleanSupplier cancelled, int depth) throws Exception {
        if (depth > 16) { result.note(path + " · 폴더가 너무 깊어 빼요"); return; }
        Map<String, Remote> remote = children(store, remoteFolder);
        for (Local item : local(context, tree, localFolder)) {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("백업을 멈췄어요.");
            Remote there = remote.get(item.name());
            if (item.folder()) {
                String folder = there != null && there.folder() ? there.id() : create(store, item.name(), remoteFolder, null);
                upload(context, store, tree, item.id(), folder, path + item.name() + "/", result, progress, cancelled, depth + 1);
                continue;
            }
            result.files++;
            if (there != null && !there.folder() && there.size() == item.size()) continue;
            if (item.size() > MAX_FILE) { result.note(path + item.name() + " · 2GB가 넘어 빼요"); continue; }
            File temp = new File(context.getCacheDir(), "backup-file.tmp");
            if (context.getCacheDir().getUsableSpace() < Math.max(0, item.size()) + SPARE) { result.note(path + item.name() + " · 휴대폰 저장 공간이 모자라 빼요"); continue; }
            try {
                try (InputStream in = context.getContentResolver().openInputStream(item.uri())) {
                    if (in == null) { result.note(path + item.name() + " · 읽지 못해 빼요"); continue; }
                    Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                String mime = item.mime() == null || item.mime().isBlank() ? "application/octet-stream" : item.mime();
                if (there != null && !there.folder()) store.drive.replace(there.id(), obj(), mime, null, temp);
                else store.drive.upload(obj("name", item.name(), "parents", arr(remoteFolder)), mime, null, temp);
                result.moved++; result.bytes += temp.length();
                progress.at("들퍼레 폴더 올리는 중 · " + result.files + "개 확인 · 새로 " + result.moved + "개 (" + AppBackup.size(result.bytes) + ")");
            } finally { temp.delete(); }
        }
    }

    // ── bringing in ──────────────────────────────────────────────────────────

    /** Downloads into the 들퍼레 folder what Drive's copy has and this phone does not; nothing here is replaced. */
    static Result download(Context context, AppStore store, Progress progress, BooleanSupplier cancelled) throws Exception {
        Result result = new Result();
        String root = find(store, ROOT_KIND, null); if (root == null) return result;
        String remoteRoot = files(store, root, false); if (remoteRoot == null) return result;
        Uri tree = SharedOutputFolder.selectedTree(context);
        download(context, store, tree, DocumentsContract.getTreeDocumentId(tree), remoteRoot, "", result, progress, cancelled, 0);
        return result;
    }
    private static void download(Context context, AppStore store, Uri tree, String localFolder, String remoteFolder, String path, Result result, Progress progress, BooleanSupplier cancelled, int depth) throws Exception {
        if (depth > 16) return;
        ContentResolver resolver = context.getContentResolver();
        Map<String, Local> here = new HashMap<>(); for (Local item : local(context, tree, localFolder)) here.putIfAbsent(item.name(), item);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, localFolder);
        for (Remote item : children(store, remoteFolder).values()) {
            if (cancelled.getAsBoolean()) throw new InterruptedIOException("가져오기를 멈췄어요.");
            if (item.name().isBlank() || item.name().contains("/") || item.name().startsWith(".")) continue;
            Local there = here.get(item.name());
            if (item.folder()) {
                String folder = there != null && there.folder() ? there.id() : made(DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, item.name()));
                if (folder == null) { result.note(path + item.name() + " · 폴더를 만들지 못해 빼요"); continue; }
                download(context, store, tree, folder, item.id(), path + item.name() + "/", result, progress, cancelled, depth + 1);
                continue;
            }
            // Google's own documents have no file to download; a file already here is kept as it is.
            if (item.mime().startsWith("application/vnd.google-apps.")) continue;
            result.files++;
            if (there != null) continue;
            if (item.size() > MAX_FILE) { result.note(path + item.name() + " · 2GB가 넘어 빼요"); continue; }
            if (context.getCacheDir().getUsableSpace() < Math.max(0, item.size()) * 2 + SPARE) { result.note(path + item.name() + " · 휴대폰 저장 공간이 모자라 빼요"); continue; }
            File temp = new File(context.getCacheDir(), "restore-file.tmp");
            try {
                store.drive.download(GoogleDrive.API + "files/" + enc(item.id()) + "?alt=media", temp, MAX_FILE);
                Uri out = DocumentsContract.createDocument(resolver, parent, item.mime().isBlank() ? "application/octet-stream" : item.mime(), item.name());
                if (out == null) { result.note(path + item.name() + " · 만들지 못해 빼요"); continue; }
                try (OutputStream to = resolver.openOutputStream(out, "w")) {
                    if (to == null) { result.note(path + item.name() + " · 쓰지 못해 빼요"); continue; }
                    Files.copy(temp.toPath(), to);
                }
                result.moved++; result.bytes += temp.length();
                progress.at("들퍼레 폴더 받는 중 · " + result.files + "개 확인 · 새로 " + result.moved + "개 (" + AppBackup.size(result.bytes) + ")");
            } finally { temp.delete(); }
        }
    }
    private static String made(Uri uri) { return uri == null ? null : DocumentsContract.getDocumentId(uri); }
    private static String enc(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }
}
