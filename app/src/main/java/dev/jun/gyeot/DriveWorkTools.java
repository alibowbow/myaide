package dev.jun.gyeot;

import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.GoogleDrive;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.obj;

/**
 * Google Drive for every Work model (core/GoogleDrive): search, read as text, upload a new file (text the model wrote, a
 * file the user attached, a file made earlier), download a file to the phone, and create folders. With the user's
 * 수정·정리 permission also change a file's content or name, move it, and move it to the trash or back; nothing is ever
 * deleted permanently. Every tool with an effect is journaled by the ledger before it runs and never repeated in a turn.
 */
final class DriveWorkTools extends WorkTools {
    /** A file on the phone to send, resolved by the Work run inside 들퍼레 or the conversation's own attachments. */
    record Payload(String mime, File file, boolean temporary) {}
    /** The phone side: a file to upload, and where a downloaded file is kept ({name, uri}). */
    interface Phone {
        Payload open(String reference) throws Exception;
        JSONObject keep(String name, String mime, File file) throws Exception;
        /** The copy this phone already holds of that Drive file at that version, still there with the same size; else null. */
        default JSONObject already(String fileId, String version) { return null; }
        /** Remembers the copy just kept of that Drive file at that version. */
        default void remember(String fileId, String version, JSONObject kept) {}
    }
    private final GoogleDriveAccount drive; private final boolean changes; private final Phone phone; private final BooleanSupplier stopped;
    private final AtomicInteger changed = new AtomicInteger();
    DriveWorkTools(GoogleDriveAccount drive, boolean changes, Phone phone, BooleanSupplier stopped) { this.drive = drive; this.changes = changes; this.phone = phone; this.stopped = stopped; }

    @Override JSONArray declarations() { return GoogleDrive.declarations(changes); }
    @Override String instructions() { return GoogleDrive.instructions(changes); }
    @Override JSONObject run(String name, Object arguments) {
        stop();
        JSONObject args;
        try { args = arguments instanceof JSONObject given ? given : new JSONObject(String.valueOf(arguments)); }
        catch (Exception e) { return GitHubFunctions.failure("도구의 인수를 확인해 주세요."); }
        try {
            if (GoogleDrive.CHANGES.contains(name)) {
                if (!changes) return GitHubFunctions.failure("Drive 파일을 바꾸려면 사용자가 설정 → 커넥터 → Google Drive에서 ‘Drive 파일 수정·정리 허용’을 켜야 해요.");
                if (changed.get() >= GoogleDrive.CHANGE_LIMIT) return GitHubFunctions.failure("한 번에 바꿀 수 있는 파일 수(" + GoogleDrive.CHANGE_LIMIT + "개)를 넘었어요. 나눠서 요청해 주세요.");
            }
            return switch (name) {
                case GoogleDrive.SEARCH -> search(args);
                case GoogleDrive.READ -> read(args);
                case GoogleDrive.SAVE -> save(args);
                case GoogleDrive.DOWNLOAD -> download(args);
                case GoogleDrive.FOLDER -> folder(args);
                case GoogleDrive.UPDATE -> counted(update(args));
                case GoogleDrive.MOVE -> counted(move(args));
                case GoogleDrive.TRASH -> counted(trash(args));
                default -> GitHubFunctions.failure("허용하지 않은 도구예요.");
            };
        } catch (CancellationException stop) { throw stop; }
        catch (Exception failed) {
            stop();
            return GitHubFunctions.failure(failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage());
        }
    }
    private void stop() { if (stopped.getAsBoolean()) throw new CancellationException("작업을 멈췄어요."); }
    private JSONObject counted(JSONObject result) { if (result.optBoolean("ok")) changed.incrementAndGet(); return result; }
    private JSONObject meta(String id) throws Exception {
        JSONObject raw = new JSONObject(drive.get(GoogleDrive.metadataUrl(id), 64 * 1024));
        if (GoogleDrive.file(raw) == null) throw new IllegalStateException("Drive에서 그 파일을 찾지 못했어요.");
        return raw;
    }
    private static String id(JSONObject args, String key, String what) {
        String id = args.optString(key).strip();
        if (!GoogleDrive.id(id)) throw new IllegalArgumentException(what + " id가 올바르지 않아요. drive_search로 먼저 찾아 주세요.");
        return id;
    }

    private JSONObject search(JSONObject args) throws Exception {
        String url = GoogleDrive.searchUrl(args.optString("query"), args.optString("folder_id"), args.optInt("limit"), args.optBoolean("folders_only"), args.optString("page"));
        JSONObject answer = new JSONObject(drive.get(url, 1024 * 1024));
        JSONArray files = GoogleDrive.files(answer);
        JSONObject out = obj("ok", true, "count", files.length(), "files", files);
        if (!answer.optString("nextPageToken").isBlank()) out.put("next_page", answer.optString("nextPageToken"));
        return out;
    }
    private JSONObject read(JSONObject args) throws Exception {
        String id = id(args, "file_id", "Drive 파일");
        JSONObject file = GoogleDrive.file(meta(id));
        String url = GoogleDrive.readUrl(id, file.optString("type"));
        if (url.isEmpty()) return obj("ok", true, "file", file, "text", "", "note", "이 형식은 글로 읽을 수 없어요. 필요하면 drive_download로 휴대폰에 내려받아요.");
        stop();
        String text = drive.get(url, GoogleDrive.READ_BYTES), clipped = GoogleDrive.clip(text);
        return obj("ok", true, "file", file, "text", clipped, "truncated", clipped.length() < text.length() || text.getBytes(StandardCharsets.UTF_8).length >= GoogleDrive.READ_BYTES);
    }
    private JSONObject save(JSONObject args) throws Exception {
        JSONObject checked = GoogleDrive.save(args);
        if (checked.has("text")) {
            JSONObject meta = GoogleDrive.upload(checked.optString("name"), checked.optString("target"), checked.optString("folder"));
            return saved(drive.upload(meta, checked.optString("mime") + "; charset=UTF-8", checked.optString("text").getBytes(StandardCharsets.UTF_8), null), meta);
        }
        Payload payload = open(checked.optString("media"));
        try {
            JSONObject meta = GoogleDrive.upload(checked.optString("name"), payload.mime(), checked.optString("folder"));
            return saved(drive.upload(meta, payload.mime(), null, payload.file()), meta);
        } finally { discard(payload); }
    }
    private Payload open(String reference) throws Exception {
        Payload payload = phone.open(reference);
        if (payload.file().length() > GoogleDrive.MEDIA_LIMIT) { discard(payload); throw new IllegalStateException("파일이 너무 커요 (" + (GoogleDrive.MEDIA_LIMIT >> 20) + "MB까지)."); }
        if (payload.mime() == null || payload.mime().isBlank()) payload = new Payload("application/octet-stream", payload.file(), payload.temporary());
        try { stop(); } catch (CancellationException e) { discard(payload); throw e; }
        return payload;
    }
    private static void discard(Payload payload) { if (payload != null && payload.temporary() && !payload.file().delete()) payload.file().deleteOnExit(); }
    private static JSONObject saved(JSONObject answer, JSONObject meta) {
        return obj("ok", true, "id", answer.optString("id"), "name", answer.optString("name", meta.optString("name")), "type", answer.optString("mimeType"), "link", answer.optString("webViewLink"));
    }
    private JSONObject download(JSONObject args) throws Exception {
        String id = id(args, "file_id", "Drive 파일");
        JSONObject file = GoogleDrive.file(meta(id)), plan = GoogleDrive.download(file, args.optString("format"));
        long limit = plan.optLong("limit");
        if (file.optLong("size") > limit) return GitHubFunctions.failure("파일이 너무 커서 내려받지 않았어요 (" + (limit >> 20) + "MB까지). 링크로 열어 주세요: " + file.optString("link"));
        // The same file at the same version, received before and still in 들퍼레: handed back as it is. Downloading it again
        // only made another copy (x (1).mp4, x (2).mp4 …) and took a minute for a large video.
        String version = String.join("|", file.optString("sha256"), file.optString("modified"), Long.toString(file.optLong("size")), args.optString("format"));
        JSONObject before = phone.already(id, version);
        if (before != null) {
            dev.jun.gyeot.core.BrowserUploads.allow(before.optString("uri"), before.optString("name"), plan.optString("mime"), System.currentTimeMillis());
            JSONObject out = dev.jun.gyeot.core.Jsons.copy(before);
            dev.jun.gyeot.core.Jsons.put(out, "ok", true); dev.jun.gyeot.core.Jsons.put(out, "already_on_phone", true);
            dev.jun.gyeot.core.Jsons.put(out, "note", "이미 받아 둔 같은 파일이라 다시 받지 않았어요 (들퍼레 폴더에 그대로 있어요). size와 sha256은 받을 때 잰 값이에요.");
            return out;
        }
        File target = File.createTempFile("drive-", ".download");
        try {
            drive.download(plan.optString("url"), target, limit);
            stop();
            // The bytes' own SHA-256, taken before they are handed on: a document's "check the size and hash first" is answered.
            String sha = sha256(target); long size = target.length();
            stop();
            JSONObject kept = phone.keep(plan.optString("name"), plan.optString("mime"), target);
            // The browser's AI may hand this very file to a page's file chooser (attach), and no other.
            dev.jun.gyeot.core.BrowserUploads.allow(kept.optString("uri"), kept.optString("name", plan.optString("name")), plan.optString("mime"), System.currentTimeMillis());
            JSONObject out = obj("ok", true, "name", kept.optString("name", plan.optString("name")), "uri", kept.optString("uri"), "size", size, "sha256", sha, "from", file.optString("link"));
            String stored = file.optString("sha256");
            if (!stored.isEmpty()) { dev.jun.gyeot.core.Jsons.put(out, "drive_sha256", stored); dev.jun.gyeot.core.Jsons.put(out, "matches_drive", stored.equals(sha)); }
            phone.remember(id, version, out);
            return out;
        } finally { if (target.exists() && !target.delete()) target.deleteOnExit(); }
    }
    /** SHA-256 of a file, lowercase hex (read in 1 MB pieces; a 120 MB video takes about a second). */
    private String sha256(File f) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1 << 20];
        try (java.io.InputStream in = new java.io.FileInputStream(f)) { for (int n; (n = in.read(buffer)) > 0;) { digest.update(buffer, 0, n); stop(); } }
        StringBuilder hex = new StringBuilder(64); for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b));
        return hex.toString();
    }
    private JSONObject folder(JSONObject args) throws Exception {
        String parent = args.optString("folder_id").strip();
        if (!parent.isEmpty() && !GoogleDrive.id(parent)) throw new IllegalArgumentException("상위 폴더 id가 올바르지 않아요.");
        JSONObject meta = GoogleDrive.folder(args.optString("name"), parent);
        return saved(drive.send("POST", GoogleDrive.createUrl(), meta), meta);
    }
    private JSONObject update(JSONObject args) throws Exception {
        JSONObject checked = GoogleDrive.update(args); String id = checked.optString("id");
        JSONObject file = GoogleDrive.file(meta(id));
        boolean folder = GoogleDrive.FOLDER_TYPE.equals(file.optString("type"));
        JSONObject rename = checked.has("name") ? obj("name", checked.optString("name")) : new JSONObject();
        JSONObject answer;
        if (checked.has("text")) {
            if (folder) throw new IllegalArgumentException("폴더에는 내용을 넣을 수 없어요.");
            String type = GoogleDrive.textUpdateType(file.optString("type"), file.optString("name"));
            answer = drive.replace(id, rename, type + "; charset=UTF-8", checked.optString("text").getBytes(StandardCharsets.UTF_8), null);
        } else if (checked.has("media")) {
            if (folder || file.optString("type").startsWith("application/vnd.google-apps.")) throw new IllegalArgumentException("Google 문서·폴더는 파일로 덮어쓸 수 없어요. text로 바꾸거나 새 파일로 올려 주세요.");
            Payload payload = open(checked.optString("media"));
            try { answer = drive.replace(id, rename, payload.mime(), null, payload.file()); } finally { discard(payload); }
        } else answer = drive.send("PATCH", GoogleDrive.changeUrl(id, null, null), rename);
        return obj("ok", true, "id", id, "name", answer.optString("name", rename.optString("name", file.optString("name"))), "link", answer.optString("webViewLink", file.optString("link")),
            "note", checked.has("text") || checked.has("media") ? "이전 내용은 Drive의 버전 기록에서 되돌릴 수 있어요." : "이름을 바꿨어요.");
    }
    private JSONObject move(JSONObject args) throws Exception {
        String id = id(args, "file_id", "옮길 파일"), to = id(args, "folder_id", "옮길 폴더");
        if (id.equals(to)) throw new IllegalArgumentException("폴더를 자기 안으로 옮길 수 없어요.");
        JSONObject destination = GoogleDrive.file(meta(to));
        if (!GoogleDrive.FOLDER_TYPE.equals(destination.optString("type"))) throw new IllegalArgumentException("‘" + destination.optString("name") + "’은(는) 폴더가 아니에요.");
        JSONObject raw = meta(id); JSONArray parents = raw.optJSONArray("parents");
        StringBuilder remove = new StringBuilder();
        for (int i = 0; parents != null && i < parents.length(); i++) {
            String parent = parents.optString(i);
            if (parent.equals(to)) return obj("ok", true, "id", id, "name", raw.optString("name"), "folder", to, "note", "이미 그 폴더에 있어요.");
            if (GoogleDrive.id(parent)) { if (remove.length() > 0) remove.append(','); remove.append(parent); }
        }
        JSONObject answer = drive.send("PATCH", GoogleDrive.changeUrl(id, to, remove.toString()), new JSONObject());
        return obj("ok", true, "id", id, "name", answer.optString("name", raw.optString("name")), "folder", to, "folder_name", destination.optString("name"), "link", answer.optString("webViewLink", raw.optString("webViewLink")));
    }
    private JSONObject trash(JSONObject args) throws Exception {
        String id = id(args, "file_id", "Drive 파일"); boolean restore = args.optBoolean("restore");
        JSONObject answer = drive.send("PATCH", GoogleDrive.changeUrl(id, null, null), obj("trashed", !restore));
        return obj("ok", true, "id", id, "name", answer.optString("name"), "trashed", answer.optBoolean("trashed", !restore),
            "note", restore ? "휴지통에서 되돌렸어요." : "휴지통으로 옮겼어요. 30일 안에 Drive 휴지통에서 되돌릴 수 있어요.");
    }
}
