package dev.jun.gyeot;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Texts the app fetched for a request (a video's transcript, a web page's words), kept in its private files so the user
 * can read them by themselves: one file per key in a folder per kind, written whole, the newest KEPT of each kind.
 */
final class KeptText {
    private KeptText() {}
    static final int KEPT = 30;
    private static boolean named(String key) { return key != null && key.matches("[A-Za-z0-9_-]{1,64}"); }
    private static File file(Context context, String kind, String key) { return new File(new File(context.getFilesDir(), kind), key + ".txt"); }
    static boolean kept(Context context, String kind, String key) { return named(key) && file(context, kind, key).isFile(); }
    /** The kept text, or "" when there is none. */
    static String saved(Context context, String kind, String key) {
        if (!named(key)) return "";
        File file = file(context, kind, key);
        try { return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : ""; } catch (IOException | RuntimeException unreadable) { return ""; }
    }
    /** Removes the kept text (to read it again another way). */
    static void remove(Context context, String kind, String key) { if (named(key)) file(context, kind, key).delete(); }
    static void save(Context context, String kind, String key, String text) {
        if (!named(key)) return;
        try {
            File folder = new File(context.getFilesDir(), kind); folder.mkdirs();
            File temporary = new File(folder, key + ".tmp"), file = new File(folder, key + ".txt");
            Files.write(temporary.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            File[] all = folder.listFiles((dir, name) -> name.endsWith(".txt"));
            if (all != null && all.length > KEPT) {
                Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = KEPT; i < all.length; i++) all[i].delete();
            }
        } catch (IOException | RuntimeException failed) { /* Reading it later is a convenience; the answer does not depend on it. */ }
    }
}
