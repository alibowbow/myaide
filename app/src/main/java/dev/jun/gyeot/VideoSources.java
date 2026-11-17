package dev.jun.gyeot;

import android.content.Context;
import android.net.Uri;
import java.io.InputStream;
import org.json.JSONObject;
import dev.jun.gyeot.core.WorkBook;

/** Explicit gallery imports for video creation, separate from private conversation attachments. */
final class VideoSources {
    private VideoSources() {}
    /** Saves an orientation-corrected app-owned copy; the user's original photo is never changed. */
    static JSONObject importPhoto(Context context, AppStore store, Uri picked) throws Exception {
        final Uri source = selectedPhoto(context, picked);
        // Broad OS storage access is not permission for arbitrary photo import.
        try { SharedOutputFolder.requireContentAccess(context, source); }
        catch (java.io.IOException outside) { throw new java.io.IOException("들퍼레 폴더 안의 사진만 영상 원본으로 가져올 수 있어요. 파일탐색기의 연결된 들퍼레에서 사진을 선택해 주세요.", outside); }
        JSONObject file = WorkFiles.add(context, store, source, () -> { try { SharedOutputFolder.requireContentAccess(context, source); } catch (java.io.IOException denied) { throw new IllegalStateException("사진 권한이 바뀌어 가져오기를 중단했어요.", denied); } }); String id = file.optString("id");
        if (!WorkBook.IMAGE.equals(file.optString("kind"))) { store.attachmentFile(id).delete(); throw new IllegalArgumentException("이미지 파일을 선택해 주세요."); }
        String name = "video-source-" + id + ("image/png".equals(file.optString("mime")) ? ".png" : ".jpg");
        Uri saved;
        try (InputStream input = new java.io.FileInputStream(store.attachmentFile(id))) {
            saved = SharedMediaOutput.save(context, "video-source-" + id, "이미지", name, file.optString("mime"), input,
                () -> { try { SharedOutputFolder.requireContentAccess(context, source); } catch (java.io.IOException denied) { throw new IllegalStateException("사진 권한이 바뀌어 가져오기를 중단했어요.", denied); } });
        }
        JSONObject entry = dev.jun.gyeot.core.Gallery.entry(id, saved.toString(), name, file.optString("name"), "", "", "영상 원본 사진", "", System.currentTimeMillis(), file.optLong("size"), 0, 0, "", true);
        store.changeGallery(all -> dev.jun.gyeot.core.Gallery.add(all, entry)); return entry;
    }

    /** ACTION_OPEN_DOCUMENT reuses only an existing canonical root permission. */
    static Uri selectedPhoto(Context context, Uri picked) throws Exception {
        try { return WorkFiles.rootInputUri(context,picked); }
        catch(java.io.IOException denied){throw new java.io.IOException("들퍼레 폴더 안의 사진만 영상 원본으로 가져올 수 있어요.",denied);}
    }
}
