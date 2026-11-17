package dev.jun.gyeot;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import dev.jun.gyeot.core.MediaPaths;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Registers pictures and videos saved in the 들퍼레 folder with Android's media index (the media scanner). Then galleries,
 * Google Photos and other apps' photo pickers (Claude's attach button, for one) list them and read them from Android's own
 * media storage, instead of streaming them through this app's document connection. Registration only: the files stay
 * where they were saved, nothing is copied, renamed or deleted, and a failed registration never fails the save.
 */
final class MediaIndex {
    /** Set once pictures and videos saved by an older version have been registered too. */
    static final String EXISTING = "mediaIndexExisting";
    private MediaIndex() {}

    /** Where the primary volume is; replaced in tests, where Android has no real volume. */
    static volatile java.util.function.Function<Context, String> volumes = MediaIndex::primary;
    static String volume(Context context) { try { return volumes.apply(context); } catch (RuntimeException unavailable) { return null; } }
    /** The primary volume's directory ("/storage/emulated/0"), or null when it cannot be read. */
    static String primary(Context context) {
        try {
            StorageManager manager = context.getSystemService(StorageManager.class); StorageVolume primary = manager == null ? null : manager.getPrimaryStorageVolume();
            File directory = primary == null ? null : primary.getDirectory(); return directory == null ? null : directory.getPath();
        } catch (RuntimeException unavailable) { return null; }
    }
    /** A saved document's file path inside 들퍼레, or null for anything else. */
    static String path(Context context, Uri uri, String volume) {
        if (uri == null || !"content".equals(uri.getScheme())) return null;
        try { return MediaPaths.path(uri.getAuthority(), DocumentsContract.getDocumentId(uri), volume); } catch (RuntimeException notDocument) { return null; }
    }
    /** Asks the media scanner to register these saved files; returns how many paths were handed over. Never throws. */
    static int register(Context context, List<Uri> uris, List<String> mimes) {
        try {
            String volume = volume(context); if (volume == null) return 0;
            ArrayList<String> paths = new ArrayList<>(), types = new ArrayList<>();
            for (int i = 0; i < uris.size(); i++) {
                String mime = i < mimes.size() ? mimes.get(i) : ""; if (!MediaPaths.media(mime)) continue;
                String path = path(context, uris.get(i), volume); if (path == null || paths.contains(path)) continue;
                paths.add(path); types.add(mime);
            }
            if (!paths.isEmpty()) MediaScannerConnection.scanFile(context.getApplicationContext(), paths.toArray(new String[0]), types.toArray(new String[0]), null);
            return paths.size();
        } catch (RuntimeException unavailable) { return 0; }
    }
    static int register(Context context, Uri uri, String mime) { return register(context, List.of(uri), List.of(mime)); }
    /** Once: the gallery's pictures and the saved videos from before this version, so the ones already made can be attached too. */
    static int registerExisting(Context context, AppStore store) {
        if (store.settings.getBoolean(EXISTING, false)) return 0;
        List<Uri> uris = new ArrayList<>(); List<String> mimes = new ArrayList<>();
        collect(store.gallery(), "uri", "name", uris, mimes); collect(store.videos(), "uri", "name", uris, mimes);
        // Without a readable volume nothing was handed over: try again on the next start instead of marking it done.
        if (volume(context) == null) return 0;
        int handed = register(context, uris, mimes);
        store.settings.edit().putBoolean(EXISTING, true).apply();
        return handed;
    }
    private static void collect(JSONArray rows, String uriKey, String nameKey, List<Uri> uris, List<String> mimes) {
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null || row.optString(uriKey).isBlank()) continue;
            Uri uri = Uri.parse(row.optString(uriKey)); String mime = MediaPaths.mime(row.optString(nameKey));
            if (mime.isEmpty()) mime = MediaPaths.mime(uri.getLastPathSegment());
            uris.add(uri); mimes.add(mime);
        }
    }
}
