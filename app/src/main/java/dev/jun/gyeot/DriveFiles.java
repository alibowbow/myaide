package dev.jun.gyeot;

import android.content.Context;
import android.net.Uri;
import dev.jun.gyeot.core.GoogleDrive;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.obj;

/**
 * The phone side of the Drive tools (DriveWorkTools.Phone), shared by 작업창 and the main chat: which file on the phone an
 * upload sends, and where a downloaded file is kept. Only files inside 들퍼레 go up: a gallery picture or video by its id,
 * an address inside the 들퍼레 folder, or (in 작업창) a file of the conversation. Downloads land where Work's own files do:
 * pictures, video and audio in their 들퍼레 media folders, other files in the 들퍼레 folder.
 */
final class DriveFiles {
    private DriveFiles() {}

    /** The phone bridge for the main chat: no conversation files, only gallery items and the 들퍼레 folder. */
    static DriveWorkTools.Phone main(Context context, AppStore store, Runnable check) {
        return new DriveWorkTools.Phone() {
            @Override public DriveWorkTools.Payload open(String reference) throws Exception { return DriveFiles.open(context, store, reference, null); }
            @Override public JSONObject keep(String name, String mime, File file) throws Exception { return DriveFiles.keep(context, "drive-download-main", name, mime, file, check); }
        };
    }

    /**
     * The file to upload for reference: attached is the conversation's own file it names ({id, mime, text?} or {uri}), or
     * null; else a gallery picture or video by its id, else an address inside the 들퍼레 folder. Anything else is refused.
     */
    static DriveWorkTools.Payload open(Context context, AppStore store, String reference, JSONObject attached) throws Exception {
        JSONObject picture = attached == null ? dev.jun.gyeot.core.Gallery.find(store.gallery(), reference) : null;
        String address = reference;
        if (attached != null) {
            if (attached.has("uri")) address = attached.optString("uri");
            else {
                String attachment = attached.optString("id"), mime = attached.optString("mime", "application/octet-stream");
                File copy = store.attachmentFile(attachment);
                if (copy.isFile()) return new DriveWorkTools.Payload(mime, copy, false);
                if (!attached.optString("text").isEmpty()) {
                    File text = File.createTempFile("drive-", ".upload", context.getCacheDir());
                    java.nio.file.Files.write(text.toPath(), attached.optString("text").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    return new DriveWorkTools.Payload(mime.startsWith("text/") ? mime : "text/plain", text, true);
                }
                throw new IllegalStateException("그 첨부 파일의 원본이 휴대폰에 남아 있지 않아요 (영상·음성은 내용을 정리한 뒤 지워져요). 다시 첨부해 주세요.");
            }
        } else if (picture != null) {
            address = picture.optString("uri");
            if (address.isBlank() || !SharedOutputFolder.canAccess(context, Uri.parse(address))) {
                File copy = store.attachmentFile(reference);
                if (!copy.isFile()) throw new IllegalStateException("갤러리에서 그 그림 파일을 찾지 못했어요.");
                return new DriveWorkTools.Payload("image/png", copy, false);
            }
        } else {
            JSONObject video = dev.jun.gyeot.core.VideoJobs.find(store.videos(), reference);
            if (video != null) {
                if (!"done".equals(video.optString("state")) || video.optString("uri").isBlank()) throw new IllegalStateException("그 영상은 아직 완성되지 않았어요.");
                address = video.optString("uri");
            }
        }
        Uri uri = Uri.parse(address);
        try { SharedOutputFolder.requireContentAccess(context, uri); }
        catch (IOException e) { throw new IllegalStateException("Drive에는 들퍼레 폴더의 파일만 올릴 수 있어요. 미디어 도구가 돌려준 id나 주소를 써 주세요."); }
        String type = context.getContentResolver().getType(uri);
        if (type == null || type.isBlank()) type = dev.jun.gyeot.core.MediaPaths.mime(uri.getLastPathSegment());
        File temporary = File.createTempFile("drive-", ".upload", context.getCacheDir());
        try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream out = new java.io.FileOutputStream(temporary)) {
            if (in == null) throw new IOException("파일을 열지 못했어요.");
            byte[] buffer = new byte[64 * 1024]; long total = 0; int n;
            while ((n = in.read(buffer)) > 0) {
                total += n; if (total > GoogleDrive.MEDIA_LIMIT) throw new IllegalStateException("파일이 너무 커요 (" + (GoogleDrive.MEDIA_LIMIT >> 20) + "MB까지).");
                out.write(buffer, 0, n);
            }
        } catch (Exception e) { if (!temporary.delete()) temporary.deleteOnExit(); throw e; }
        return new DriveWorkTools.Payload(type, temporary, true);
    }

    /** Keeps a downloaded file on the phone and returns {name, uri, size}; key names the save so a retry does not duplicate it. */
    static JSONObject keep(Context context, String key, String name, String mime, File file, Runnable check) throws Exception {
        check.run();
        String category = java.util.Set.of("image/png", "image/jpeg", "image/webp").contains(mime) ? "이미지" : mime != null && mime.startsWith("video/") ? "영상"
            : java.util.Set.of("audio/wav", "audio/mpeg", "audio/mp4").contains(mime) ? "오디오" : "";
        Uri uri;
        if (!category.isEmpty()) {
            try (InputStream in = new java.io.FileInputStream(file)) { uri = SharedMediaOutput.save(context, key + "-" + java.util.UUID.randomUUID(), category, name, mime, in, check); }
        } else {
            if (file.length() > GoogleDrive.FILE_DOWNLOAD) throw new IllegalStateException("파일이 너무 커요 (" + (GoogleDrive.FILE_DOWNLOAD >> 20) + "MB까지).");
            uri = SharedWorkFiles.save(context, name, mime, java.nio.file.Files.readAllBytes(file.toPath()));
        }
        return obj("name", name, "uri", uri.toString(), "size", file.length());
    }
}
